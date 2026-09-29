package app.auloud.player.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP8: pure-logic tests on plain JVM (no Robolectric -- this file has no
 * Android imports). Covers the shown-once gating truth table, the
 * intent-selection lambda, and the app-details URI shape.
 */
class BatteryPromptLogicTest {

    @Test
    fun firstPlay_notExempt_shows() {
        assertTrue(BatteryPromptLogic.shouldShowPrompt(wasShown = false, isExempt = false))
    }

    @Test
    fun alreadyShown_neverShowsAgain() {
        assertFalse(BatteryPromptLogic.shouldShowPrompt(wasShown = true, isExempt = false))
    }

    @Test
    fun alreadyExempt_skipsDialog() {
        assertFalse(BatteryPromptLogic.shouldShowPrompt(wasShown = false, isExempt = true))
    }

    @Test
    fun shownAndExempt_skipsDialog() {
        assertFalse(BatteryPromptLogic.shouldShowPrompt(wasShown = true, isExempt = true))
    }

    @Test
    fun resolvable_selectsOptimizationSettings() {
        assertEquals(
            BatterySettingsTarget.OPTIMIZATION_SETTINGS,
            BatteryPromptLogic.selectTarget { true }
        )
    }

    @Test
    fun unresolvable_fallsBackToAppDetails() {
        assertEquals(
            BatterySettingsTarget.APP_DETAILS,
            BatteryPromptLogic.selectTarget { false }
        )
    }

    @Test
    fun appDetailsUri_isPureFunctionOfPackageName() {
        assertEquals(
            "package:app.auloud.player",
            BatteryPromptLogic.appDetailsUri("app.auloud.player")
        )
    }
}
