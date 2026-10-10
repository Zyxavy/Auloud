package app.auloud.player.tts

/**
 * PW5: pluggable TTS engine interface (D-065), no framework types.
 *
 * Each engine declares what it can do ([capabilities]) and synthesizes
 * one utterance at a time. Voice ids are namespaced per engine
 * (`kokoro:af_heart`, `piper:en_US-lessac-low`, `kitten:3`,
 * `system:en-us-x-sfg#female`),
 * matching the `engine` field Scribe already writes in the manifest; the
 * [EngineRegistry] routes on the namespace prefix. Two-voice roles
 * ([TtsRole]) are the unit of voice settings everywhere (D-071).
 *
 * Pure Kotlin, no Android imports: JVM-testable, and the System TTS
 * adapter (PW6) keeps all framework calls behind this seam.
 */
interface TtsEngine {

    /** Namespace prefix of this engine's voice ids (`system`, `kokoro`, `piper`). */
    val namespace: String

    /** Voices this engine can synthesize right now (models loaded, TTS service present). */
    fun voices(): List<TtsVoice>

    /** What this engine can do (multi-speaker, load cost, sample rate). */
    fun capabilities(): TtsCapabilities

    /**
     * Synthesize one utterance. Returns PCM at [SynthesizedAudio.sampleRateHz];
     * callers (rendering in Slice 10, audition in PW8) own buffering and
     * timing from the sample counts.
     */
    suspend fun synthesize(text: String, voice: TtsVoice, speed: Float): SynthesizedAudio
}

/** One synthesizable voice: namespaced id plus the engine that owns it. */
data class TtsVoice(
    /** Namespaced id, e.g. `kokoro:af_heart`. */
    val id: String,
    /** The [TtsEngine.namespace] matching the id prefix. */
    val engine: String,
) {
    companion object {
        /**
         * Split `namespace:local` (first colon wins; the local part may
         * itself contain colons, as Android system voice names do), or
         * null when there is no namespace.
         */
        fun parse(id: String): TtsVoice? {
            val cut = id.indexOf(':')
            if (cut <= 0 || cut == id.length - 1) return null
            return TtsVoice(id = id, engine = id.substring(0, cut))
        }
    }
}

/** Raw synthesis output: mono float PCM plus its rate. */
data class SynthesizedAudio(
    val sampleRateHz: Int,
    val samples: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesizedAudio) return false
        return sampleRateHz == other.sampleRateHz && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * sampleRateHz + samples.contentHashCode()
}

/** The two voice roles on the device (D-071: narrator + dialogue only). */
enum class TtsRole {
    Narrator,
    Dialogue,
}
