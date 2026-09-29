package app.auloud.player.battery

/**
 * WP8: persists whether the battery-optimization prompt was shown.
 *
 * The dialog appears once (first playback); a Help/settings entry reopens
 * the flow on demand regardless of this flag.
 */
interface BatteryPromptStore {
    /** True once the prompt has been shown (either button dismisses it). */
    fun wasShown(): Boolean

    /** Records that the prompt was shown so the auto-dialog does not repeat. */
    fun markShown()
}
