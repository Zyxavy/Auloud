package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN3: [RenderGuards] policy tests on plain JVM.
 *
 * Each signal is an injected field (no `android.*`); RN8 supplies the
 * real temperature and storage readings behind the seam.
 *
 * UX1 (2026-10-10, owner-ordered): the charging-only gate is removed;
 * renders run unplugged, so only temperature and storage can pause.
 */
class RenderGuardsTest {

    private fun conditions(
        batteryTempC: Float? = 32.0f,
        tempLimitC: Float = 40.0f,
        freeBytes: Long = 1_000_000_000L,
        requiredBytes: Long = 100_000_000L
    ) = RenderConditions(batteryTempC, tempLimitC, freeBytes, requiredBytes)

    @Test
    fun proceed_whenAllClear() {
        assertEquals(RenderGuardDecision.PROCEED, RenderGuards.decide(conditions()))
        assertTrue(RenderGuards.shouldRender(conditions()))
    }

    @Test
    fun proceed_whenUnplugged_rendersRunUnplugged() {
        // No charger signal exists anymore; temperature and storage
        // clear means PROCEED regardless of plug state.
        assertEquals(RenderGuardDecision.PROCEED, RenderGuards.decide(conditions()))
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
    fun storage_beatsTemperature() {
        // Both failing: storage wins (hard failure over safety pause).
        val allBad = conditions(batteryTempC = 50.0f, freeBytes = 1L, requiredBytes = 9L)
        assertEquals(RenderGuardDecision.PAUSE_STORAGE, RenderGuards.decide(allBad))
        val hot = conditions(batteryTempC = 50.0f)
        assertEquals(RenderGuardDecision.PAUSE_TEMPERATURE, RenderGuards.decide(hot))
    }

    @Test
    fun decideInputs_mapsLiveSignals() {
        val inputs = object : RenderSignalInputs {
            override fun batteryTempC(): Float? = 30.0f
            override fun freeBytes(): Long = 500L
        }

        assertEquals(
            RenderGuardDecision.PROCEED,
            RenderGuards.decideInputs(inputs, RenderPolicy(), requiredBytes = 100L)
        )
        val hot = object : RenderSignalInputs {
            override fun batteryTempC(): Float? = 50.0f
            override fun freeBytes(): Long = 500L
        }
        assertEquals(
            RenderGuardDecision.PAUSE_TEMPERATURE,
            RenderGuards.decideInputs(hot, RenderPolicy(), requiredBytes = 100L)
        )
    }

    @Test
    fun defaultPolicy_hasTempLimitOnly() {
        assertEquals(40.0f, RenderPolicy().tempLimitC)
    }
}
