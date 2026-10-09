package app.auloud.player.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS4: engine benchmark categories plus the slow-engine warning
 * (D-122).
 *
 * Pure JVM, no Android, no storage.
 */
class EngineBenchmarkTest {

    @Test
    fun system_isStandardWithNoWarning() {
        assertEquals(EngineSpeedCategory.STANDARD, EngineBenchmark.categoryFor("system"))
        assertFalse(EngineBenchmark.isSlow("system"))
        assertNull(EngineBenchmark.slowWarningFor("system"))
    }

    @Test
    fun beep_isStandardWithNoWarning() {
        assertEquals(EngineSpeedCategory.STANDARD, EngineBenchmark.categoryFor("beep"))
        assertNull(EngineBenchmark.slowWarningFor("beep"))
    }

    @Test
    fun piper_isTooSlowWithPerHourWarning() {
        assertEquals(EngineSpeedCategory.TOO_SLOW, EngineBenchmark.categoryFor("piper"))
        assertTrue(EngineBenchmark.isSlow("piper"))
        val warning = requireNotNull(EngineBenchmark.slowWarningFor("piper"))
        assertEquals(EngineSpeedCategory.TOO_SLOW, warning.category)
        val expected = (EngineBenchmark.AUDIO_HOUR_MS / EngineBenchmark.PIPER_RTF).toLong()
        assertEquals(expected, warning.wallFastMsPerHour)
        assertEquals(expected, warning.wallSlowMsPerHour)
    }

    @Test
    fun unknownEngine_isBackgroundWithWarning() {
        assertEquals(EngineSpeedCategory.BACKGROUND, EngineBenchmark.categoryFor("kokoro"))
        assertTrue(EngineBenchmark.isSlow("kokoro"))
        val warning = requireNotNull(EngineBenchmark.slowWarningFor("kokoro"))
        assertTrue(warning.wallSlowMsPerHour >= warning.wallFastMsPerHour)
        assertTrue(warning.wallFastMsPerHour > 0L)
    }

    @Test
    fun infoFor_standardHasSystemBandPerHour() {
        val info = EngineBenchmark.infoFor("system")
        assertEquals(EngineSpeedCategory.STANDARD, info.category)
        val fast = (EngineBenchmark.AUDIO_HOUR_MS / EngineBenchmark.SYSTEM_RTF_HIGH).toLong()
        val slow = (EngineBenchmark.AUDIO_HOUR_MS / EngineBenchmark.SYSTEM_RTF_LOW).toLong()
        assertEquals(fast, info.wallFastMsPerHour)
        assertEquals(slow, info.wallSlowMsPerHour)
    }
}
