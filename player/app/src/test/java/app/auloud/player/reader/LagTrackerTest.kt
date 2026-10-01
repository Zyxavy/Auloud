package app.auloud.player.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RA9: lag tracker on plain JVM.
 */
class LagTrackerTest {

    @Test
    fun empty_yieldsNulls() {
        val tracker = LagTracker()
        assertEquals(0, tracker.count)
        assertNull(tracker.averageMs)
        assertNull(tracker.maxMs)
    }

    @Test
    fun averageAndMax_overSamples() {
        val tracker = LagTracker()
        tracker.record(100L)
        tracker.record(200L)
        tracker.record(300L)
        assertEquals(3, tracker.count)
        assertEquals(200L, tracker.averageMs)
        assertEquals(300L, tracker.maxMs)
    }

    @Test
    fun window_evictsOldest() {
        val tracker = LagTracker(window = 3)
        tracker.record(100L)
        tracker.record(200L)
        tracker.record(300L)
        tracker.record(900L)
        assertEquals(3, tracker.count)
        assertEquals((200L + 300L + 900L) / 3, tracker.averageMs)
        assertEquals(900L, tracker.maxMs)
    }

    @Test
    fun reset_clears() {
        val tracker = LagTracker()
        tracker.record(100L)
        tracker.reset()
        assertEquals(0, tracker.count)
        assertNull(tracker.averageMs)
        assertNull(tracker.maxMs)
    }
}
