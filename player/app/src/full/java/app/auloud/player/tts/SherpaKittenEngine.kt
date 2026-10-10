package app.auloud.player.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/**
 * KT1: KittenTTS tier as a [TtsEngine] on sherpa-onnx (Slice 14).
 *
 * VC1: full-flavor only (`src/full`; the sherpa-onnx dependency is
 * `fullImplementation`). The `core` flavor carries a same-named stub
 * with no native imports, so `core` builds with zero sherpa files.
 *
 * One loaded model serves every voice (Kitten packs are multi-speaker):
 * narrator and dialogue are two sids of the same instance, so unlike
 * Piper there is no second model to load and no voice-switch gap. The
 * engine uses the first complete pack ([detectKittenPack]); extra packs
 * stay invisible until KT2 refines multi-pack handling.
 *
 * The JNI boundary is untestable on JVM (native lib load fails), so all
 * logic (pack completeness, sid routing, lazy load, release) runs
 * against the [KittenHandle] seam; only [SherpaKittenHandle] touches
 * sherpa classes, and only on device. Call [release] when the engine is
 * dropped. Speed rides per request ([GenerationConfig.speed]), so speed
 * changes never reload the model.
 */
class SherpaKittenEngine(
    packs: List<ModelPack>,
    private val numThreads: Int = 2,
    private val openHandle: (pack: KittenPack, threads: Int) -> KittenHandle =
        { pack, threads -> SherpaKittenHandle(pack, threads) },
) : TtsEngine {

    override val namespace: String = KITTEN_NAMESPACE

    private val pack: KittenPack? = packs.firstNotNullOfOrNull { detectKittenPack(File(it.dirPath)) }

    private var loaded: KittenHandle? = null
    private var released = false

    override fun voices(): List<TtsVoice> {
        val complete = pack ?: return emptyList()
        if (released) return emptyList()
        return (0 until complete.speakerCount).map { TtsVoice(id = kittenVoiceId(it), engine = KITTEN_NAMESPACE) }
    }

    override fun capabilities(): TtsCapabilities = TtsCapabilities(
        multiSpeaker = true,
        loadCostMb = KITTEN_LOAD_MB,
        sampleRateHz = KITTEN_NATIVE_HZ
    )

    override suspend fun synthesize(
        text: String,
        voice: TtsVoice,
        speed: Float
    ): SynthesizedAudio {
        require(text.isNotBlank()) { "blank text" }
        check(!released) { "engine released" }
        val sid = parseKittenSid(voice)
            ?: throw IllegalArgumentException("unknown kitten voice ${voice.id}")
        val complete = pack ?: throw IllegalArgumentException("unknown kitten voice ${voice.id}")
        require(sid < complete.speakerCount) { "unknown kitten voice ${voice.id}" }
        val handle = loaded ?: openHandle(complete, numThreads).also { loaded = it }
        val (samples, rate) = handle.generate(text, sid, clampTtsSpeed(speed))
        return SynthesizedAudio(sampleRateHz = rate, samples = samples)
    }

    /** Drop the loaded native instance (idempotent). */
    fun release() {
        released = true
        loaded?.let { runCatching { it.release() } }
        loaded = null
    }
}

/** One loaded Kitten model: blocking generate by sid, explicit release. */
interface KittenHandle {
    /** PCM float samples + native rate (throws on synthesis failure). */
    fun generate(text: String, sid: Int, speed: Float): Pair<FloatArray, Int>
    fun release()
}

/** Production handle: one sherpa `OfflineTts` per pack (device only). */
class SherpaKittenHandle(pack: KittenPack, numThreads: Int = 2) : KittenHandle {

    private val tts: OfflineTts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = pack.model.absolutePath,
                    voices = File(pack.dir, KITTEN_VOICES_FILENAME).absolutePath,
                    tokens = File(pack.dir, KITTEN_TOKENS_FILENAME).absolutePath,
                    dataDir = File(pack.dir, KITTEN_ESPEAK_DIRNAME).absolutePath,
                    lengthScale = 1.0f
                ),
                numThreads = numThreads,
                debug = false
            )
        )
    )

    override fun generate(text: String, sid: Int, speed: Float): Pair<FloatArray, Int> {
        val audio = tts.generateWithConfigAndCallback(
            text,
            GenerationConfig(sid = sid, speed = speed),
            CALLBACK
        )
        return audio.samples to audio.sampleRate
    }

    override fun release() {
        runCatching { tts.release() }
    }

    companion object {
        private val CALLBACK = fun(@Suppress("unused") samples: FloatArray): Int = 1
    }
}
