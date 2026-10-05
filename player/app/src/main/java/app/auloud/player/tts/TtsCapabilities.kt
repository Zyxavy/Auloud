package app.auloud.player.tts

/**
 * PW5: what one [TtsEngine] can do (D-065), in its own words.
 *
 * The registry and the voice settings read these, never engine-specific
 * types: [multiSpeaker] decides whether one model covers both roles,
 * [loadCostMb] feeds the Slice 7 per-device recommendation (0 for the
 * System adapter — no model), [sampleRateHz] is the engine's native rate
 * (timings derive from sample counts, so any rate works).
 */
data class TtsCapabilities(
    /** One loaded model speaks every voice (Kokoro) vs one model per voice (Piper). */
    val multiSpeaker: Boolean,
    /** Approximate RAM to keep the engine ready, in MB (0 = no model). */
    val loadCostMb: Int,
    /** Native output rate in Hz. */
    val sampleRateHz: Int,
)
