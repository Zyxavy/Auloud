package app.auloud.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP6: [ProgressSavePolicy] trigger rules on plain JVM: the 5 s periodic
 * rule (playing/paused/boundary) and what gets saved on pause, chapter
 * change, periodic tick, task-removed/destroy and end-of-book.
 */
class ProgressSavePolicyTest {

    // Periodic tick: fires only while playing and only after the interval.

    @Test
    fun periodic_firesAfterIntervalWhilePlaying() {
        assertTrue(ProgressSavePolicy.shouldSavePeriodic(0L, 5_001L, isPlaying = true))
    }

    @Test
    fun periodic_firesExactlyAtInterval() {
        assertTrue(ProgressSavePolicy.shouldSavePeriodic(1_000L, 6_000L, isPlaying = true))
    }

    @Test
    fun periodic_suppressedBeforeInterval() {
        assertFalse(ProgressSavePolicy.shouldSavePeriodic(1_000L, 5_999L, isPlaying = true))
    }

    @Test
    fun periodic_suppressedWhenPaused() {
        assertFalse(ProgressSavePolicy.shouldSavePeriodic(0L, 60_000L, isPlaying = false))
    }

    @Test
    fun periodic_measuresSinceLastSave_notSinceStart() {
        // Last save at t=5 s, now t=9 s: only 4 s elapsed, no save yet.
        assertFalse(ProgressSavePolicy.shouldSavePeriodic(5_000L, 9_000L, isPlaying = true))
        assertTrue(ProgressSavePolicy.shouldSavePeriodic(5_000L, 10_000L, isPlaying = true))
    }

    // What gets saved on pause / chapter change / tick / destroy: the current spot.

    @Test
    fun pause_savesCurrentChapterAndPosition() {
        val point = ProgressSavePolicy.pointOrNull("book-1", 1, 61_000L)

        assertEquals(ProgressSavePolicy.SavePoint("book-1", 1, 61_000L), point)
    }

    @Test
    fun transition_savesNewChapterAtItsPosition() {
        val point = ProgressSavePolicy.pointOrNull("book-1", 2, 250L)

        assertEquals(2, point?.chapterIndex)
        assertEquals(250L, point?.positionMs)
    }

    @Test
    fun save_skippedWhenNoBookLoaded() {
        assertNull(ProgressSavePolicy.pointOrNull(null, 0, 0L))
        assertNull(ProgressSavePolicy.pointOrNull("  ", 0, 0L))
    }

    @Test
    fun save_skippedWhenItemIndexUnset() {
        assertNull(ProgressSavePolicy.pointOrNull("book-1", -1, 0L))
    }

    @Test
    fun save_skippedWhenPositionNegative() {
        assertNull(ProgressSavePolicy.pointOrNull("book-1", 0, -1L))
    }

    // End of book: stop and mark finished.

    @Test
    fun endOfBook_trueOnlyWhenEndedOnFinalChapter() {
        assertTrue(ProgressSavePolicy.isEndOfBook(1, 2, playbackEnded = true))
    }

    @Test
    fun endOfBook_falseWhenNotEnded() {
        assertFalse(ProgressSavePolicy.isEndOfBook(1, 2, playbackEnded = false))
    }

    @Test
    fun endOfBook_falseOnNonFinalChapter() {
        assertFalse(ProgressSavePolicy.isEndOfBook(0, 2, playbackEnded = true))
    }

    @Test
    fun endOfBook_falseForEmptyBook() {
        assertFalse(ProgressSavePolicy.isEndOfBook(0, 0, playbackEnded = true))
    }

    @Test
    fun finishedPoint_isLastChapterAtFullDuration() {
        val point = ProgressSavePolicy.finishedPoint("book-1", 2, 400_000L)

        assertEquals(ProgressSavePolicy.SavePoint("book-1", 1, 400_000L), point)
    }

    @Test
    fun finishedPoint_clampsDegenerateInput() {
        assertEquals(
            ProgressSavePolicy.SavePoint("book-1", 0, 0L),
            ProgressSavePolicy.finishedPoint("book-1", 0, -5L)
        )
    }
}
