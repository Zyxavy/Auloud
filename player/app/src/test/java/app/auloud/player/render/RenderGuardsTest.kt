package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN3: [RenderGuards] policy tests on plain JVM.
 *
 * Each signal is an injected field (no `android.*`); RN8 supplies the
 * real charger, temperature and storage readings behind the seam.
 */
class RenderGuardsTest {

    private fun conditions(
        charging: Boolean = true,
        chargingOnly: Boolean = true,
        batteryTempC: Float? = 32.0f,
        tempLimitC: Float = 40.0f,
        freeBytes: Long = 1_000_000_000L,
        requiredBytes: Long = 100_000_000L
    ) = RenderConditions(charging, chargingOnly, batteryTempC, tempLimitC, freeBytes, requiredBytes)

    @Test
    fun proceed_whenAllClear() {
        assertEquals(RenderGuardDecision.PROCEED, RenderGuards.decide(conditions()))
        assertTrue(RenderGuards.shouldRender(conditions()))
    }

    @Test
    fun pause_whenChargerUnpluggedAndChargingOnly() {
        val decision = RenderGuards.decide(conditions(charging = false, chargingOnly = true))

        assertEquals(RenderGuardDecision.PAUSE_CHARGER, decision)
        assertFalse(RenderGuards.shouldRender(conditions(charging = false, chargingOnly = true)))
    }

    @Test
    fun proceed_whenChargerUnpluggedButChargingOnlyOff() {
        assertEquals(
            RenderGuardDecision.PROCEED,
            RenderGuards.decide(conditions(charging = false, chargingOnly = false))
        )
    }

    @Test
    fun pause_whenBatteryHot() {
        assertEquals(
            RenderGuardDecision.PAUSE_TEMPERATURE,
            RenderGuards.decide(conditions(batteryTempC = 41.5f, tempLimitC = 40.0f))
        )
    }

    @Test
    fun proceed_atExactlyTheLimit() {
        assertEquals(
            RenderGuardDecision.PROCEED,
            RenderGuards.decide(conditions(batteryTempC = 40.0f, tempLimitC = 40.0f))
        )
    }

    @Test
    fun proceed_whenTemperatureSensorAbsent() {
        assertEquals(
            RenderGuardDecision.PROCEED,
            RenderGuards.decide(conditions(batteryTempC = null))
        )
    }

    @Test
    fun pause_whenStorageShort() {
        assertEquals(
            RenderGuardDecision.PAUSE_STORAGE,
            RenderGuards.decide(conditions(freeBytes = 50L, requiredBytes = 100L))
        )
    }

    @Test
    fun proceed_whenStorageExactlyEnough() {
        assertEquals(
            RenderGuardDecision.PROCEED,
            RenderGuards.decide(conditions(freeBytes = 100L, requiredBytes = 100L))
        )
    }

    @Test
    fun storage_beatsTemperature_beatsCharger() {
        // All three failing: storage wins (hard failure over policy).
        val allBad = conditions(charging = false, batteryTempC = 50.0f, freeBytes = 1L, requiredBytes = 9L)
        assertEquals(RenderGuardDecision.PAUSE_STORAGE, RenderGuards.decide(allBad))
        // Temperature beats the charger rule.
        val hotUnplugged = conditions(charging = false, batteryTempC = 50.0f)
        assertEquals(RenderGuardDecision.PAUSE_TEMPERATURE, RenderGuards.decide(hotUnplugged))
    }

    @Test
    fun decideInputs_mapsLiveSignals() {
        val inputs = object : RenderSignalInputs {
            override fun isCharging(): Boolean = false
            override fun batteryTempC(): Float? = 30.0f
            override fun freeBytes(): Long = 500L
        }

        assertEquals(
            RenderGuardDecision.PAUSE_CHARGER,
            RenderGuards.decideInputs(inputs, RenderPolicy(chargingOnly = true), requiredBytes = 100L)
        )
        assertEquals(
            RenderGuardDecision.PROCEED,
            RenderGuards.decideInputs(inputs, RenderPolicy(chargingOnly = false), requiredBytes = 100L)
        )
    }

    @Test
    fun defaultPolicy_isChargingOnly() {
        assertTrue(RenderPolicy().chargingOnly)
        assertEquals(40.0f, RenderPolicy().tempLimitC)
    }
}
