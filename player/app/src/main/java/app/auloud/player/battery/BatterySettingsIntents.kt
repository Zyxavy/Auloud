package app.auloud.player.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * WP8: Android glue around [BatteryPromptLogic].
 *
 * - Exemption probe: `PowerManager.isIgnoringBatteryOptimizations` (API 23+,
 *   fine for minSdk 24). Querying needs no permission.
 * - Deep link: the battery-optimization settings list, falling back to this
 *   app's details screen when nothing resolves the settings intent (Samsung
 *   menus vary). Deliberately NOT the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 *   request intent: that needs a manifest permission we do not want; the
 *   settings deep-link needs none.
 * - The pure selection lives in [BatteryPromptLogic.selectTarget] (unit-
 *   tested); this object only supplies the `PackageManager` probe and builds
 *   the resulting `Intent`.
 *
 * DEVICE-TEST (user on the Tab E): confirm the optimization-settings screen
 * exists on Samsung Android 7.1.1 and records the exemption; if Samsung
 * renamed/moved it, document the actual menu path here.
 */
object BatterySettingsIntents {

    /**
     * True when some activity handles the optimization-settings intent.
     * Legacy `queryIntentActivities(intent, Int)` overload on purpose: it
     * works on every API level we care about (the `ResolveInfoFlags`
     * overload is API 33+).
     */
    @Suppress("DEPRECATION")
    fun isOptimizationSettingsResolvable(context: Context): Boolean =
        try {
            val probe = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            context.packageManager.queryIntentActivities(probe, 0).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "resolvability probe failed: ${e.message}")
            false
        }

    fun newOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun newAppDetailsIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse(BatteryPromptLogic.appDetailsUri(packageName))
        )

    /** Pure-function-of-resolvability selection mapped to `Intent`s. */
    fun newBatterySettingsIntent(context: Context): Intent {
        val target = BatteryPromptLogic.selectTarget {
            isOptimizationSettingsResolvable(context)
        }
        return when (target) {
            BatterySettingsTarget.OPTIMIZATION_SETTINGS ->
                newOptimizationSettingsIntent()
            BatterySettingsTarget.APP_DETAILS ->
                newAppDetailsIntent(context.packageName)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Opens the best settings screen, degrading to the app-details screen if
     * the first launch throws (belt and suspenders next to the resolve
     * probe: Samsung firmware varies).
     */
    fun openBatterySettings(context: Context) {
        try {
            context.startActivity(newBatterySettingsIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "battery settings open failed, trying app details: ${e.message}")
            try {
                context.startActivity(
                    newAppDetailsIntent(context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Log.w(TAG, "app details fallback failed: ${e2.message}")
            }
        }
    }

    /**
     * Seam for the WP8 already-exempt skip: callers pass this as the
     * `isExempt` answer to [BatteryPromptLogic.shouldShowPrompt]; unit tests
     * pass plain booleans instead (no `PowerManager` on JVM).
     */
    fun isExemptionGranted(context: Context): Boolean =
        try {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return false
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.w(TAG, "exemption query failed: ${e.message}")
            false
        }

    private const val TAG = "AuloudBattery"
}
