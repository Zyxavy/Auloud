package app.auloud.player.settings

import android.content.Context
import android.content.SharedPreferences
import app.auloud.player.playback.DEFAULT_SPEED
import app.auloud.player.playback.clampSpeed
import app.auloud.player.reader.ReaderMode

/**
 * RA7: [ReaderModeStore] on framework `SharedPreferences` (no new
 * dependency), sharing the `auloud_settings` file under its own keys.
 * An unknown stored mode falls back to [ReaderMode.ReadListen].
 *
 * API 24 safe: `SharedPreferences` only.
 */
class PrefsReaderModeStore(
    private val prefs: SharedPreferences
) : ReaderModeStore {

    override fun mode(): ReaderMode = when (prefs.getString(KEY_MODE, null)) {
        ReaderMode.Read.name -> ReaderMode.Read
        ReaderMode.Listen.name -> ReaderMode.Listen
        else -> ReaderMode.ReadListen
    }

    override fun setMode(mode: ReaderMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    override fun keepScreenOn(): Boolean =
        prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)

    override fun setKeepScreenOn(keepOn: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepOn).apply()
    }

    override fun playbackSpeed(): Float =
        clampSpeed(prefs.getFloat(KEY_SPEED, DEFAULT_SPEED))

    override fun setPlaybackSpeed(speed: Float) {
        prefs.edit().putFloat(KEY_SPEED, clampSpeed(speed)).apply()
    }

    companion object {
        const val PREFS_NAME = "auloud_settings"
        const val KEY_MODE = "reader_mode"
        const val KEY_KEEP_SCREEN_ON = "reader_keep_screen_on"
        const val KEY_SPEED = "playback_speed"

        fun fromContext(context: Context): PrefsReaderModeStore =
            PrefsReaderModeStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
