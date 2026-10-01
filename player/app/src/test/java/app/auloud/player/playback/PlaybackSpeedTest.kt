package app.auloud.player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RA8: speed steps on plain JVM.
 */
class PlaybackSpeedTest {

    @Test
    fun cycle_walksStepsThenWraps() {
        var speed = 1.0f
        val expected = listOf(1.25f, 1.5f, 1.75f, 2.0f, 0.75f, 1.0f)
        expected.forEach { next ->
            speed = nextSpeed(speed)
            assertEquals(next, speed, 0f)
        }
    }

    @Test
    fun cycle_fromBelowFirstStep_startsAtFirst() {
        assertEquals(0.75f, nextSpeed(0.5f), 0f)
    }

    @Test
    fun clamp_boundsAndSanitizes() {
        assertEquals(0.75f, clampSpeed(0.1f), 0f)
        assertEquals(2.0f, clampSpeed(3.0f), 0f)
        assertEquals(1.5f, clampSpeed(1.5f), 0f)
        assertEquals(1.0f, clampSpeed(Float.NaN), 0f)
    }

    @Test
    fun format_compactLabels() {
        assertEquals("1x", formatSpeed(1.0f))
        assertEquals("1.5x", formatSpeed(1.5f))
        assertEquals("1.25x", formatSpeed(1.25f))
        assertEquals("0.75x", formatSpeed(0.75f))
        assertEquals("2x", formatSpeed(2.0f))
    }
}
