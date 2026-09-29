package app.auloud.player.battery

/**
 * WP8: pure battery-prompt decision logic.
 *
 * Plain Kotlin only (no Android imports) so it runs on JVM unit tests
 * without Robolectric. Android glue ([BatterySettingsIntents]) supplies the
 * real `PowerManager` / `PackageManager` answers; tests supply fakes.
 */
enum class BatterySettingsTarget {
    /** `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` list screen. */
    OPTIMIZATION_SETTINGS,

    /** `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` fallback for this app. */
    APP_DETAILS
}

object BatteryPromptLogic {

    /**
     * True only when the prompt has never been shown and the exemption is
     * not already granted. Covers both WP8 gates: shown-once and
     * already-exempt skip.
     */
    fun shouldShowPrompt(wasShown: Boolean, isExempt: Boolean): Boolean =
        !wasShown && !isExempt

    /**
     * Picks the settings destination. The resolvability probe is a lambda so
     * tests control it and Android code passes a `PackageManager` query.
     * Pure function of resolvability.
     */
    fun selectTarget(
        isOptimizationSettingsResolvable: () -> Boolean
    ): BatterySettingsTarget =
        if (isOptimizationSettingsResolvable()) {
            BatterySettingsTarget.OPTIMIZATION_SETTINGS
        } else {
            BatterySettingsTarget.APP_DETAILS
        }

    /**
     * `Uri` string for the app-details fallback. Pure function of the package
     * name (the Android side wraps it in `Uri.parse(...)`).
     */
    fun appDetailsUri(packageName: String): String = "package:$packageName"
}
