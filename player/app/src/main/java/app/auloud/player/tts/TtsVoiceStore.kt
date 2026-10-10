package app.auloud.player.tts

/**
 * PW5: narrator + dialogue voice settings (global, no database).
 *
 * Voice ids are namespaced (`kokoro:af_heart`); the engine is implied by
 * the id, so there is no separate engine setting. An empty id means
 * "no voice chosen yet" (audition fills it in PW8). Backed by
 * `SharedPreferences` in production, fakes in tests.
 */
interface TtsVoiceStore {
    fun voiceId(role: TtsRole): String
    fun setVoiceId(role: TtsRole, voiceId: String)
    fun speed(role: TtsRole): Float
    fun setSpeed(role: TtsRole, speed: Float)

    /**
     * ST6: live-stream calibration (per-role volume plus the measured
     * voice id). Defaults keep every existing fake compiling: no
     * calibration means full volume on any voice. Production
     * ([PrefsTtsStore]) persists both halves.
     */
    fun streamVolume(role: TtsRole): Float = 1.0f
    fun setStreamVolume(role: TtsRole, volume: Float) {}
    fun streamVolumeVoice(role: TtsRole): String = ""
    fun setStreamVolumeVoice(role: TtsRole, voiceId: String) {}
}

/** Valid synthesis speeds (slower than playback: audibility floor). */
const val MIN_TTS_SPEED = 0.5f
const val MAX_TTS_SPEED = 2.0f
const val DEFAULT_TTS_SPEED = 1.0f

/** Clamp to [MIN_TTS_SPEED]..[MAX_TTS_SPEED]; garbage becomes 1x. */
fun clampTtsSpeed(speed: Float): Float {
    if (!speed.isFinite()) return DEFAULT_TTS_SPEED
    return speed.coerceIn(MIN_TTS_SPEED, MAX_TTS_SPEED)
}
