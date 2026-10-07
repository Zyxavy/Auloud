package app.auloud.player.render

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import java.io.File

/**
 * RN8: live platform signals behind the RN3 seam (Slice 10).
 *
 * Thin adapter only: it reads the sticky battery broadcast (charger plus
 * temperature) and the free bytes of the render volume, then hands plain
 * data to [RenderGuards]. All decisions stay in the JVM-tested policy;
 * this file owns no threshold and no behavior.
 *
 * Battery (API 24 safe, no version guard): the sticky
 * `ACTION_BATTERY_CHANGED` intent carries `EXTRA_PLUGGED` (any nonzero
 * plug state reads as charging) and `EXTRA_TEMPERATURE` (tenths of a
 * degree Celsius; absent reads as null, which the guard treats as no
 * temperature block). A missing sticky intent reads as unplugged (pause
 * rather than drain: conservative, and sticky battery is near-universal).
 *
 * Storage: `File.usableSpace` of [bundleDir] (API 9, no guard). SAF books
 * (`content://` tokens) have no `java.io.File` meaning; the service
 * refuses those before this is ever consulted, so this path only sees
 * real file-system dirs.
 *
 * No `java.time`. No new dependency, no new permission.
 */
class AndroidRenderSignals(
    appContext: Context,
    private val bundleDir: String,
    private val batteryIntent: () -> Intent? = {
        appContext.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }
) : RenderSignalInputs {

    private val appContext: Context = appContext.applicationContext

    override fun isCharging(): Boolean {
        val intent = batteryIntent() ?: return false
        if (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) return true
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun batteryTempC(): Float? {
        val intent = batteryIntent() ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null
        return tenths / 10f
    }

    /**
     * Free bytes on the render volume.
     *
     * The `UsableSpace` lint check suggests `StorageManager`
     * allocatable bytes, which needs API 26+; on the API 24 target
     * `File.usableSpace` is the available call (v3 can switch behind an
     * SDK guard).
     */
    @android.annotation.SuppressLint("UsableSpace")
    override fun freeBytes(): Long {
        return try {
            File(bundleDir).usableSpace
        } catch (_: Exception) {
            0L
        }
    }
}

/**
 * RN8: persisted render policy (charging-only plus temperature limit).
 *
 * Same `auloud_settings` prefs file as the voice store (one file per
 * app); thresholds stay injectable in tests through [RenderPolicy] while
 * the service reads the persisted values here. Defaults match the policy
 * (charging-only on, 40.0 C provisional from D-104 for RN11 to tune).
 */
object RenderPolicyPrefs {

    /** Prefs key: pause unless the charger is connected. */
    const val KEY_CHARGING_ONLY = "render_charging_only"

    /** Prefs key: battery-temperature pause threshold in Celsius. */
    const val KEY_TEMP_LIMIT_C = "render_temp_limit_c"

    /** Loads the policy (defaults when unset or garbled). */
    fun load(prefs: SharedPreferences): RenderPolicy {
        val chargingOnly = try {
            prefs.getBoolean(KEY_CHARGING_ONLY, true)
        } catch (_: Exception) {
            true
        }
        val tempLimitC = try {
            prefs.getFloat(KEY_TEMP_LIMIT_C, RenderPolicy.DEFAULT_TEMP_LIMIT_C)
                .takeIf { it.isFinite() && it > 0f }
                ?: RenderPolicy.DEFAULT_TEMP_LIMIT_C
        } catch (_: Exception) {
            RenderPolicy.DEFAULT_TEMP_LIMIT_C
        }
        return RenderPolicy(chargingOnly = chargingOnly, tempLimitC = tempLimitC)
    }

    /** Loads the policy from [context] prefs. */
    fun load(context: Context): RenderPolicy = load(
        context.applicationContext.getSharedPreferences(
            app.auloud.player.tts.PrefsTtsStore.PREFS_NAME,
            Context.MODE_PRIVATE
        )
    )
}
