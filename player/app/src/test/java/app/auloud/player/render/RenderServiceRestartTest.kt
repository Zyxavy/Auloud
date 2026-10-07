package app.auloud.player.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN8 review: null-restart wiring on plain JVM.
 *
 * Pins that [shouldStopAfterNullRestart] runs through
 * [RenderServicePolicy.restartAction] so the tested seam is live:
 * WAIT_EXPLICIT never stops (wait for explicit resume, never auto-start);
 * NO_JOB and NOTHING_TO_RESUME stop only when no book is loaded.
 */
class RenderServiceRestartTest {

    private fun job(state: RenderJobState): RenderJob = RenderJob(
        bookId = "book",
        state = state,
        plan = RenderPlan(
            bookId = "book",
            chapterCount = 3,
            startChapter = 0,
            scope = RenderScope.WholeBook,
            orderedChapters = listOf(0, 1, 2),
            createdAt = 1L
        ),
        createdAt = 1L,
        updatedAt = 1L
    )

    @Test
    fun nullRestart_noJobNoBook_stops() {
        assertTrue(shouldStopAfterNullRestart(null, null))
    }

    @Test
    fun nullRestart_noJobWithBook_stays() {
        assertFalse(shouldStopAfterNullRestart("book", null))
    }

    @Test
    fun nullRestart_waitExplicitWithBook_stays() {
        assertFalse(shouldStopAfterNullRestart("book", job(RenderJobState.RUNNING)))
        assertFalse(shouldStopAfterNullRestart("book", job(RenderJobState.PAUSED)))
        assertFalse(shouldStopAfterNullRestart("book", job(RenderJobState.INTERRUPTED)))
    }

    @Test
    fun nullRestart_waitExplicitWithoutBook_staysNoAutoStart() {
        assertFalse(shouldStopAfterNullRestart(null, job(RenderJobState.RUNNING)))
    }

    @Test
    fun nullRestart_doneWithoutBook_stops() {
        assertTrue(shouldStopAfterNullRestart(null, job(RenderJobState.DONE)))
    }

    @Test
    fun nullRestart_doneWithBook_stays() {
        assertFalse(shouldStopAfterNullRestart("book", job(RenderJobState.DONE)))
    }
}
