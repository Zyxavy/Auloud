package app.auloud.player.battery

import android.content.Context
import android.content.SharedPreferences

/**
 * WP8: [BatteryPromptStore] backed by framework `SharedPreferences` (no new
 * dependency), following the WP3 [PrefsBooksFolderStore] pattern. Shares the
 * same prefs file (`auloud_settings`) under its own key.
 *
 * API 24 safe: `SharedPreferences` only.
 */
class PrefsBatteryPromptStore(
    private val prefs: SharedPreferences
) : BatteryPromptStore {

    override fun wasShown(): Boolean =
        prefs.getBoolean(KEY_BATTERY_PROMPT_SHOWN, false)

    override fun markShown() {
        prefs.edit().putBoolean(KEY_BATTERY_PROMPT_SHOWN, true).apply()
    }

    companion object {
        const val PREFS_NAME = "auloud_settings"
        const val KEY_BATTERY_PROMPT_SHOWN = "battery_prompt_shown"

        fun fromContext(context: Context): PrefsBatteryPromptStore =
            PrefsBatteryPromptStore(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
    }
}
