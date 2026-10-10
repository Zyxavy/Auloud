package app.auloud.player.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * PW7b: Piper tier as a [TtsEngine] on sherpa-onnx (D-065, D-077).
 *
 * VC1: full-flavor only (`src/full`; the sherpa-onnx dependency is
 * `fullImplementation`). The `core` flavor carries a same-named stub
 * with no native imports, so `core` builds with zero sherpa files.
 *
 * One loaded model per voice (VITS packs are single-speaker), so (like
 * Scribe's `PiperEngine`) voices load lazily and stay cached: two voices
 * means two native instances (~170 MB each on the Tab E per the Slice 7
 * spike — the two-voice mode this buys is exactly D-071's narrator +
 * dialogue pair). `generateWithConfigAndCallback` returns PCM directly,
 * so unlike the System tier there is no WAV round-trip; the audio rate
 * rides per call in [SynthesizedAudio].
 *
 * The JNI boundary is untestable on JVM (native lib load fails), so all
 * logic (pack completeness, routing, lazy instances, release) runs
 * against the [PiperHandle] seam; only [SherpaPiperHandle] touches
 * sherpa classes, and only on device. Call [release] when the engine is
 * dropped (Slice 10 owns engine lifecycle; the audition host releases
 * on dispose).
 *
 * Speed maps to the generation config 1:1 (faster speech at >1.0, same
 * contract as every other engine). Single-speaker packs: `sid` is 0.
 */
class SherpaPiperEngine(
    packs: List<ModelPack>,
    private val numThreads: Int = 2,
    private val openHandle: (packDir: File, modelFile: File) -> PiperHandle = ::SherpaPiperHandle,
) : TtsEngine {

    override val namespace: String = PIPER_NAMESPACE

    private val complete: Map<String, Pair<File, File>> = packs.flatMap { pack ->
        val dir = File(pack.dirPath)
        val tokens = File(dir, TOKENS_FILENAME)
        val dataDir = File(dir, ESPEAK_DATA_DIRNAME)
        if (!tokens.isFile || !dataDir.isDirectory) return@flatMap emptyList()
        pack.voices.mapNotNull { voice ->
            val model = File(dir, "$voice.onnx")
            if (model.isFile) voice to (dir to model) else null
        }
    }.toMap()

    private val loaded = mutableMapOf<String, PiperHandle>()
    private var released = false

    override fun voices(): List<TtsVoice> {
        if (released) return emptyList()
        return complete.keys.sorted().map { TtsVoice(id = "$PIPER_NAMESPACE:$it", engine = PIPER_NAMESPACE) }
    }

    override fun capabilities(): TtsCapabilities = TtsCapabilities(
        multiSpeaker = false,
        loadCostMb = PIPER_LOAD_MB,
        sampleRateHz = PIPER_NATIVE_HZ
    )

    override suspend fun synthesize(
        text: String,
        voice: TtsVoice,
        speed: Float
    ): SynthesizedAudio {
        require(text.isNotBlank()) { "blank text" }
        require(voice.engine == PIPER_NAMESPACE) { "voice ${voice.id} is not a piper voice" }
        check(!released) { "engine released" }
        val local = voice.id.substringAfter(':')
        val (packDir, model) = complete[local]
            ?: throw IllegalArgumentException("unknown piper voice ${voice.id}")
        val handle = loaded.getOrPut(local) {
            openHandle(packDir, model)
        }
        val (samples, rate) = handle.generate(text, clampTtsSpeed(speed))
        return SynthesizedAudio(sampleRateHz = rate, samples = samples)
    }

    /** Drop every loaded native instance (idempotent). */
    fun release() {
        released = true
        loaded.values.forEach { runCatching { it.release() } }
        loaded.clear()
    }

    companion object {
        const val PIPER_NAMESPACE = "piper"
        const val TOKENS_FILENAME = "tokens.txt"
        const val ESPEAK_DATA_DIRNAME = "espeak-ng-data"

        /**
         * Per-model RAM on the Tab E (Slice 7 spike: 133 MB process PSS
         * before, 304 MB with one model). Static until per-pack
         * measurement exists.
         */
        const val PIPER_LOAD_MB = 170

        /** Typical VITS pack rate (lessac-medium is 22050; actual rides per call). */
        const val PIPER_NATIVE_HZ = 22_050
    }
}

/** One loaded Piper voice: blocking generate, explicit release. */
interface PiperHandle {
    /** PCM float samples + native rate (throws on synthesis failure). */
    fun generate(text: String, speed: Float): Pair<FloatArray, Int>
    fun release()
}

/** Production handle: one sherpa `OfflineTts` per voice (device only). */
class SherpaPiperHandle(packDir: File, modelFile: File) : PiperHandle {

    private val tts: OfflineTts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    tokens = File(packDir, SherpaPiperEngine.TOKENS_FILENAME).absolutePath,
                    dataDir = File(packDir, SherpaPiperEngine.ESPEAK_DATA_DIRNAME).absolutePath
                ),
                numThreads = 2,
                debug = false
            )
        )
    )

    override fun generate(text: String, speed: Float): Pair<FloatArray, Int> {
        val audio = tts.generateWithConfigAndCallback(
            text,
            GenerationConfig(sid = 0, speed = speed),
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
