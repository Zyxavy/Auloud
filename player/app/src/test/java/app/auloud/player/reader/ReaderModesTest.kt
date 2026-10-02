package app.auloud.player.reader

import app.auloud.player.playback.PlaybackState
import app.auloud.player.playback.isFinishedBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RA7: pure mode rules on plain JVM.
 */
class ReaderModesTest {

    @Test
    fun onlyRead_pausesAudio() {
        assertTrue(shouldPauseForMode(ReaderMode.Read))
        assertFalse(shouldPauseForMode(ReaderMode.Listen))
        assertFalse(shouldPauseForMode(ReaderMode.ReadListen))
    }

    @Test
    fun keepScreenOn_neverInListen() {
        ReaderMode.entries.forEach { mode ->
            assertEquals(mode != ReaderMode.Listen, shouldKeepScreenOn(mode, true))
            assertFalse(shouldKeepScreenOn(mode, false))
        }
    }

    @Test
    fun finishedBook_lastChapterAtFullDuration() {
        val finished = PlaybackState(
            chapterIndex = 2, chapterCount = 3, positionMs = 5000L, durationMs = 5000L
        )
        assertTrue(isFinishedBook(finished))
        // Past the end (overshoot) still counts as finished.
        assertTrue(isFinishedBook(finished.copy(positionMs = 6000L)))
    }

    @Test
    fun unfinishedBook_positions() {
        val base = PlaybackState(chapterIndex = 2, chapterCount = 3, positionMs = 4999L, durationMs = 5000L)
        assertFalse(isFinishedBook(base))
        assertFalse(isFinishedBook(base.copy(chapterIndex = 1, positionMs = 5000L)))
        assertFalse(isFinishedBook(base.copy(chapterIndex = 0, chapterCount = 0)))
        assertFalse(isFinishedBook(base.copy(durationMs = 0L)))
        assertFalse(isFinishedBook(PlaybackState()))
    }
}
