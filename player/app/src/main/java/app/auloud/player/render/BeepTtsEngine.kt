package app.auloud.player.render

import app.auloud.player.tts.SynthesizedAudio
import app.auloud.player.tts.TtsCapabilities
import app.auloud.player.tts.TtsEngine
import app.auloud.player.tts.TtsVoice
import kotlin.math.PI
import kotlin.math.sin

/**
 * RN10: debug-only deterministic beep engine (Slice 10 plan RN10, D-093).
 *
 * Implements the real [TtsEngine] seam (not the RN4-test fake pattern):
 * the beep path then flows through the unchanged production chain
 * ([RenderVoices.resolve] plus [SpoolRenderer.renderChapter] plus
 * [ChapterAssembler.assembleFromSpool] plus [ChapterEncode]), so the
 * self-check exercises the same code the System TTS render will use.
 * Choosing the fake pattern instead would have needed a parallel seam
 * and proven nothing about the real one.
 *
 * Each sentence renders as a pure sine tone of known length ([BEEP_MS]
 * at [SAMPLE_RATE_HZ], so assembly resampling is a copy) with a
 * distinct frequency per sentence index ([frequencyForSid]: base plus
 * step, all well under Nyquist). Highlight can be checked by ear (each
 * beep sounds different) and by eye (beep N lines up with sentence N).
 * The sentence index rides in the text ([BeepSelfCheck.textForSid]
 * writes "Beep N"; [parseSid] reads the trailing integer, defaulting to
 * 1 for foreign texts). Speed is accepted and ignored: the debug action
 * always uses 1.0, and ignoring it keeps tone lengths known.
 *
 * Deterministic: same (text, voice) always yields byte-identical PCM
 * (pure sine from closed-form math, no randomness, no clock, no state).
 *
 * Debug-only: production call sites obtain this engine only through
 * [DebugRenderEngines.beepEngineIfDebug] behind
 * [app.auloud.player.settings.isBeepSelfCheckAvailable]; release builds
 * never instantiate it (R8 folds the `BuildConfig.DEBUG` constant, the
 * same seam as the CP8 spike screen). Never add this engine to a
 * release registry.
 *
 * API 24 safe: pure Kotlin plus `kotlin.math` only. No `java.time`, no
 * Android types, no new dependency, no permission, no manifest change.
 */
class BeepTtsEngine : TtsEngine {

    override val namespace: String = NAMESPACE

    override fun voices(): List<TtsVoice> = listOf(
        TtsVoice(id = NARRATOR_VOICE_ID, engine = NAMESPACE),
        TtsVoice(id = DIALOGUE_VOICE_ID, engine = NAMESPACE)
    )

    override fun capabilities(): TtsCapabilities = TtsCapabilities(
        multiSpeaker = true,
        loadCostMb = 0,
        sampleRateHz = SAMPLE_RATE_HZ
    )

    override suspend fun synthesize(
        text: String,
        voice: TtsVoice,
        speed: Float
    ): SynthesizedAudio {
        require(text.isNotBlank()) { "blank text" }
        require(voice.engine == NAMESPACE) { "voice ${voice.id} not owned by $NAMESPACE" }
        val sid = parseSid(text) ?: 1
        return SynthesizedAudio(
            sampleRateHz = SAMPLE_RATE_HZ,
            samples = toneSamples(sid, SAMPLE_RATE_HZ)
        )
    }

    companion object {
        /** Engine namespace (voice ids are `beep:narrator`, `beep:dialogue`). */
        const val NAMESPACE = "beep"

        /** Narrator voice id offered by [voices]. */
        const val NARRATOR_VOICE_ID = "beep:narrator"

        /** Dialogue voice id offered by [voices]. */
        const val DIALOGUE_VOICE_ID = "beep:dialogue"

        /** Fingerprint `engine_versions` string for beep renders. */
        const val VERSION = "beep-1"

        /** Native output rate in Hz (matches the bundle rate, resample is a copy). */
        const val SAMPLE_RATE_HZ = 24000

        /** Tone length per sentence in ms (known, identical for every sentence). */
        const val BEEP_MS = 500

        /** Peak amplitude of the tone (headroom under the -1 dBFS target). */
        const val AMPLITUDE = 0.5f

        /** Frequency of sentence 1 in Hz. */
        const val BASE_FREQ_HZ = 440.0

        /** Frequency step per sentence index in Hz. */
        const val FREQ_STEP_HZ = 110.0

        /**
         * Tone frequency for a 1-based sentence index.
         *
         * Sentence 1 is 440 Hz, sentence 2 is 550 Hz, and so on: adjacent
         * beeps are a clearly audible step apart for the ear check.
         */
        fun frequencyForSid(sid: Int): Double {
            require(sid >= 1) { "sid must be 1-based, got $sid." }
            val freq = BASE_FREQ_HZ + FREQ_STEP_HZ * (sid - 1)
            require(freq < SAMPLE_RATE_HZ / 2.0) {
                "sid $sid needs $freq Hz, past Nyquist at $SAMPLE_RATE_HZ Hz."
            }
            return freq
        }

        /**
         * Byte-deterministic tone samples for a sentence index.
         *
         * Pure sine at [frequencyForSid], [BEEP_MS] at [sampleRateHz]:
         * the same sid always yields the same array on the same runtime.
         */
        fun toneSamples(sid: Int, sampleRateHz: Int = SAMPLE_RATE_HZ): FloatArray {
            require(sampleRateHz > 0) { "sample_rate must be positive, got $sampleRateHz." }
            val freq = frequencyForSid(sid)
            val count = ((BEEP_MS.toLong() * sampleRateHz.toLong()) / 1000L).toInt()
            require(count > 0) { "sid $sid: tone length is $count samples (nothing to play)." }
            return FloatArray(count) { i ->
                (AMPLITUDE * sin(2.0 * PI * freq * i.toDouble() / sampleRateHz.toDouble())).toFloat()
            }
        }

        /**
         * Sentence index from trailing text ("Beep 3" gives 3).
         *
         * Null when the text carries no trailing 1-based integer: the
         * engine then renders the sentence-1 tone, still deterministic.
         */
        fun parseSid(text: String): Int? {
            val tail = text.trim().substringAfterLast(' ').trim()
            val sid = tail.toIntOrNull() ?: return null
            return if (sid >= 1) sid else null
        }
    }
}
