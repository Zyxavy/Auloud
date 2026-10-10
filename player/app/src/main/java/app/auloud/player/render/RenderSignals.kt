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
 * Thin adapter only: it reads the sticky battery broadcast
 * (temperature) and the free bytes of the render volume, then hands
 * plain data to [RenderGuards]. All decisions stay in the JVM-tested
 * policy; this file owns no threshold and no behavior.
 *
 * Battery (API 24 safe, no version guard): the sticky
 * `ACTION_BATTERY_CHANGED` intent carries `EXTRA_TEMPERATURE` (tenths
 * of a degree Celsius; absent reads as null, which the guard treats
 * as no temperature block). A missing sticky intent reads as null
 * (unknown, not hot).
 *
 * Storage: `File.usableSpace` of [bundleDir] (API 9, no guard). SAF books
 * (`content://` tokens) have no `java.io.File` meaning; the service
 * refuses those before this is ever consulted, so this path only sees
 * real file-system dirs.
 *
 * UX1 (2026-10-10, owner-ordered): no charger signal; renders run
 * unplugged.
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
 * RN8: persisted render policy (temperature limit).
 *
 * Same `auloud_settings` prefs file as the voice store (one file per
 * app); thresholds stay injectable in tests through [RenderPolicy] while
 * the service reads the persisted values here. Defaults match the policy
 * (40.0 C provisional from D-104 for RN11 to tune).
 *
 * UX1 (2026-10-10, owner-ordered): the charging-only key is gone; an
 * old `render_charging_only` value left in prefs is ignored, never read.
 */
object RenderPolicyPrefs {

    /** Prefs key: battery-temperature pause threshold in Celsius. */
    const val KEY_TEMP_LIMIT_C = "render_temp_limit_c"

    /** Loads the policy (defaults when unset or garbled). */
    fun load(prefs: SharedPreferences): RenderPolicy {
        val tempLimitC = try {
            prefs.getFloat(KEY_TEMP_LIMIT_C, RenderPolicy.DEFAULT_TEMP_LIMIT_C)
                .takeIf { it.isFinite() && it > 0f }
                ?: RenderPolicy.DEFAULT_TEMP_LIMIT_C
        } catch (_: Exception) {
            RenderPolicy.DEFAULT_TEMP_LIMIT_C
        }
        return RenderPolicy(tempLimitC = tempLimitC)
    }

    /** Loads the policy from [context] prefs. */
    fun load(context: Context): RenderPolicy = load(
        context.applicationContext.getSharedPreferences(
            app.auloud.player.tts.PrefsTtsStore.PREFS_NAME,
            Context.MODE_PRIVATE
        )
    )

    /**
     * RN9: persists the policy. Defaults apply on garbled reads in
     * [load], so a partial write still reads back safe.
     */
    fun save(prefs: SharedPreferences, policy: RenderPolicy) {
        try {
            prefs.edit()
                .putFloat(KEY_TEMP_LIMIT_C, policy.tempLimitC)
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Persists the policy into [context] prefs. */
    fun save(context: Context, policy: RenderPolicy) = save(
        context.applicationContext.getSharedPreferences(
            app.auloud.player.tts.PrefsTtsStore.PREFS_NAME,
            Context.MODE_PRIVATE
        ),
        policy
    )
}
