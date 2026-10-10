package app.auloud.player.tts

import android.content.Context
import android.content.SharedPreferences

/**
 * PW5: [TtsVoiceStore] on framework `SharedPreferences` (no new
 * dependency), sharing the `auloud_settings` file under its own keys.
 * Stored speeds are clamped on read, so a hand-edited value cannot stick
 * the engine at 9x; unknown content falls back to empty/1x.
 *
 * API 24 safe: `SharedPreferences` only.
 */
class PrefsTtsStore(
    private val prefs: SharedPreferences
) : TtsVoiceStore {

    override fun voiceId(role: TtsRole): String =
        prefs.getString(keyFor(role), "") ?: ""

    override fun setVoiceId(role: TtsRole, voiceId: String) {
        prefs.edit().putString(keyFor(role), voiceId).apply()
    }

    override fun speed(role: TtsRole): Float =
        clampTtsSpeed(prefs.getFloat(speedKeyFor(role), DEFAULT_TTS_SPEED))

    override fun setSpeed(role: TtsRole, speed: Float) {
        prefs.edit().putFloat(speedKeyFor(role), clampTtsSpeed(speed)).apply()
    }

    /** ST6: calibrated live-stream volume (0..1, garbage reads full). */
    override fun streamVolume(role: TtsRole): Float {
        val stored = prefs.getFloat(streamVolumeKeyFor(role), 1.0f)
        if (!stored.isFinite()) return 1.0f
        return stored.coerceIn(0.0f, 1.0f)
    }

    override fun setStreamVolume(role: TtsRole, volume: Float) {
        val clean = if (!volume.isFinite()) 1.0f else volume.coerceIn(0.0f, 1.0f)
        prefs.edit().putFloat(streamVolumeKeyFor(role), clean).apply()
    }

    /** ST6: voice id the calibration above was measured for. */
    override fun streamVolumeVoice(role: TtsRole): String =
        prefs.getString(streamVolumeVoiceKeyFor(role), "") ?: ""

    override fun setStreamVolumeVoice(role: TtsRole, voiceId: String) {
        prefs.edit().putString(streamVolumeVoiceKeyFor(role), voiceId).apply()
    }

    companion object {
        /** Same file as the other settings stores (one prefs file per app). */
        const val PREFS_NAME = "auloud_settings"
        const val KEY_NARRATOR_VOICE = "tts_narrator_voice"
        const val KEY_DIALOGUE_VOICE = "tts_dialogue_voice"
        const val KEY_NARRATOR_SPEED = "tts_narrator_speed"
        const val KEY_DIALOGUE_SPEED = "tts_dialogue_speed"
        const val KEY_NARRATOR_STREAM_VOLUME = "tts_narrator_stream_volume"
        const val KEY_DIALOGUE_STREAM_VOLUME = "tts_dialogue_stream_volume"
        const val KEY_NARRATOR_STREAM_VOLUME_VOICE = "tts_narrator_stream_volume_voice"
        const val KEY_DIALOGUE_STREAM_VOLUME_VOICE = "tts_dialogue_stream_volume_voice"

        private fun keyFor(role: TtsRole): String = when (role) {
            TtsRole.Narrator -> KEY_NARRATOR_VOICE
            TtsRole.Dialogue -> KEY_DIALOGUE_VOICE
        }

        private fun speedKeyFor(role: TtsRole): String = when (role) {
            TtsRole.Narrator -> KEY_NARRATOR_SPEED
            TtsRole.Dialogue -> KEY_DIALOGUE_SPEED
        }

        private fun streamVolumeKeyFor(role: TtsRole): String = when (role) {
            TtsRole.Narrator -> KEY_NARRATOR_STREAM_VOLUME
            TtsRole.Dialogue -> KEY_DIALOGUE_STREAM_VOLUME
        }

        private fun streamVolumeVoiceKeyFor(role: TtsRole): String = when (role) {
            TtsRole.Narrator -> KEY_NARRATOR_STREAM_VOLUME_VOICE
            TtsRole.Dialogue -> KEY_DIALOGUE_STREAM_VOLUME_VOICE
        }

        fun fromContext(context: Context): PrefsTtsStore =
            PrefsTtsStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
