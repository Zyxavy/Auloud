package app.auloud.player.render

/**
 * RN3: render safety-guard policy inputs (Slice 10).
 *
 * Charging-only, temperature and storage enter as injected plain-data
 * inputs plus JVM-testable decision functions. No `android.*` calls
 * live here: RN8 wires the real signals (charger state, battery
 * temperature, free bytes) behind [RenderSignalInputs] and owns the
 * service pause/resume; this file only decides. Thresholds come from
 * [RenderPolicy] (settings-backed in RN8/RN9), never hardcoded behavior.
 *
 * Priority is fixed so tests and the service agree: storage first (a
 * full disk is a hard failure, rendering must not start or continue),
 * then temperature (safety), then charger policy (user setting). The
 * first failing guard wins; only all-clear proceeds.
 *
 * API 24 safe: pure Kotlin, no Android types, no `java.time`.
 */

/** Tunable render policy (settings-backed from RN8 onward). */
data class RenderPolicy(
    /** When true, rendering pauses unless the charger is connected. */
    val chargingOnly: Boolean = true,
    /**
     * Pause when the battery temperature reads above this (Celsius).
     * Provisional default; RN11 tunes it from Tab E numbers (the plan
     * explicitly leaves temperature and storage defaults to acceptance).
     */
    val tempLimitC: Float = DEFAULT_TEMP_LIMIT_C
) {
    companion object {
        /** Provisional battery-temperature pause threshold in Celsius. */
        const val DEFAULT_TEMP_LIMIT_C = 40.0f
    }
}

/** Real-signal seam RN8 implements (BatteryManager, storage stats). */
interface RenderSignalInputs {
    /** True when the charger is connected. */
    fun isCharging(): Boolean

    /** Battery temperature in Celsius, or null when the sensor is absent. */
    fun batteryTempC(): Float?

    /** Free bytes on the render volume. */
    fun freeBytes(): Long
}

/** Snapshot the guard decides on (tests build this directly). */
data class RenderConditions(
    val charging: Boolean,
    val chargingOnly: Boolean,
    val batteryTempC: Float?,
    val tempLimitC: Float,
    val freeBytes: Long,
    /** Bytes the queued work needs (audio plus spool; RN9 estimates). */
    val requiredBytes: Long
)

/** Guard outcome: proceed or the single pause reason. */
enum class RenderGuardDecision {
    PROCEED,
    PAUSE_CHARGER,
    PAUSE_TEMPERATURE,
    PAUSE_STORAGE
}

object RenderGuards {

    /**
     * Decides on a [RenderConditions] snapshot. Storage (free below
     * required) beats temperature (above limit) beats charger policy
     * (charging-only without charger). A null temperature sensor reads
     * as no temperature block (unknown, not hot).
     */
    fun decide(conditions: RenderConditions): RenderGuardDecision {
        if (conditions.freeBytes < conditions.requiredBytes) {
            return RenderGuardDecision.PAUSE_STORAGE
        }
        val temp = conditions.batteryTempC
        if (temp != null && temp > conditions.tempLimitC) {
            return RenderGuardDecision.PAUSE_TEMPERATURE
        }
        if (conditions.chargingOnly && !conditions.charging) {
            return RenderGuardDecision.PAUSE_CHARGER
        }
        return RenderGuardDecision.PROCEED
    }

    /**
     * Decides from live [inputs] plus [policy] and the RN9 byte
     * estimate. Thin adapter over [decide] so the service passes
     * signals without mapping fields by hand.
     */
    fun decideInputs(
        inputs: RenderSignalInputs,
        policy: RenderPolicy,
        requiredBytes: Long
    ): RenderGuardDecision = decide(
        RenderConditions(
            charging = inputs.isCharging(),
            chargingOnly = policy.chargingOnly,
            batteryTempC = inputs.batteryTempC(),
            tempLimitC = policy.tempLimitC,
            freeBytes = inputs.freeBytes(),
            requiredBytes = requiredBytes
        )
    )

    /** True only when [decide] says PROCEED. */
    fun shouldRender(conditions: RenderConditions): Boolean =
        decide(conditions) == RenderGuardDecision.PROCEED
}
