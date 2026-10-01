package app.auloud.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RA8: sleep-timer countdown on plain JVM (fake clock, no Android).
 */
class SleepTimerTest {

    @Test
    fun off_isIdle() {
        val timer = SleepTimer()
        assertFalse(timer.isActive)
        assertNull(timer.remainingMs(1_000L, 0L, 5_000L))
        assertFalse(timer.isExpired(1_000L, 0L, 5_000L))
        assertEquals(1f, timer.fadeVolume(1_000L, 0L, 5_000L), 0f)
    }

    @Test
    fun minutes_countDownToZeroThenExpire() {
        val timer = SleepTimer()
        timer.start(SleepOption.Min15, 0L)
        assertTrue(timer.isActive)
        assertEquals(15 * 60_000L, timer.remainingMs(0L, 0L, 1_000L))
        assertEquals(60_000L, timer.remainingMs(14 * 60_000L, 0L, 1_000L))
        assertFalse(timer.isExpired(14 * 60_000L, 0L, 1_000L))
        assertEquals(0L, timer.remainingMs(15 * 60_000L, 0L, 1_000L))
        assertTrue(timer.isExpired(15 * 60_000L, 0L, 1_000L))
        assertTrue(timer.isExpired(16 * 60_000L, 0L, 1_000L))
    }

    @Test
    fun cancel_disarms() {
        val timer = SleepTimer()
        timer.start(SleepOption.Min30, 0L)
        timer.cancel()
        assertFalse(timer.isActive)
        assertNull(timer.remainingMs(0L, 0L, 1_000L))
    }

    @Test
    fun endOfChapter_followsPlaybackPosition() {
        val timer = SleepTimer()
        timer.start(SleepOption.EndOfChapter, 9_999L)
        assertEquals(4_000L, timer.remainingMs(10_000L, 1_000L, 5_000L))
        assertFalse(timer.isExpired(10_000L, 1_000L, 5_000L))
        assertTrue(timer.isExpired(10_000L, 5_000L, 5_000L))
        // Unknown duration: no remaining, never expires.
        assertNull(timer.remainingMs(10_000L, 1_000L, 0L))
        assertFalse(timer.isExpired(10_000L, 99_999L, 0L))
    }

    @Test
    fun fade_fullUntilWindow_thenLinearToZero() {
        val timer = SleepTimer()
        timer.start(SleepOption.Min15, 0L)
        val end = 15 * 60_000L
        assertEquals(1f, timer.fadeVolume(end - SLEEP_FADE_MS - 1, 0L, 1L), 0f)
        assertEquals(1f, timer.fadeVolume(end - SLEEP_FADE_MS, 0L, 1L), 0.001f)
        assertEquals(0.5f, timer.fadeVolume(end - SLEEP_FADE_MS / 2, 0L, 1L), 0.001f)
        assertEquals(0f, timer.fadeVolume(end, 0L, 1L), 0f)
    }

    @Test
    fun cycleOrder_offThroughEndAndBack() {
        val expected = listOf(
            SleepOption.Min15, SleepOption.Min30, SleepOption.Min45,
            SleepOption.Min60, SleepOption.EndOfChapter, SleepOption.Off
        )
        var current = SleepOption.Off
        expected.forEach { next ->
            current = cycleSleepOption(current)
            assertEquals(next, current)
        }
    }
}
