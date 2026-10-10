package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN3: [RenderJobs] state-machine tests on plain JVM.
 *
 * Covers the full transition matrix (every legal move plus illegal
 * refusals), per-chapter progress into DONE, and interrupted-then-resumed.
 */
class RenderJobTest {

    private var now = 10_000L
    private val clock: () -> Long = { now }

    private fun plan(chapters: List<Int> = listOf(2, 3, 4)): RenderPlan =
        RenderPlan(
            bookId = "b1", chapterCount = 6, startChapter = 2,
            scope = RenderScope.FromHere, orderedChapters = chapters, createdAt = now
        )

    private fun queued(chapters: List<Int> = listOf(2, 3, 4)): RenderJob =
        RenderJob(bookId = "b1", state = RenderJobState.QUEUED, plan = plan(chapters), createdAt = now, updatedAt = now)

    private fun running(chapters: List<Int> = listOf(2, 3, 4)): RenderJob =
        RenderJobs.transition(queued(chapters), RenderJobState.RUNNING, clock)

    @Test
    fun canTransition_allowsEveryLegalMove() {
        val legal = listOf(
            RenderJobState.QUEUED to RenderJobState.RUNNING,
            RenderJobState.QUEUED to RenderJobState.CANCELLED,
            RenderJobState.RUNNING to RenderJobState.PAUSED,
            RenderJobState.RUNNING to RenderJobState.DONE,
            RenderJobState.RUNNING to RenderJobState.FAILED,
            RenderJobState.RUNNING to RenderJobState.CANCELLED,
            RenderJobState.RUNNING to RenderJobState.INTERRUPTED,
            RenderJobState.PAUSED to RenderJobState.RUNNING,
            RenderJobState.PAUSED to RenderJobState.CANCELLED,
            RenderJobState.PAUSED to RenderJobState.INTERRUPTED,
            RenderJobState.INTERRUPTED to RenderJobState.RUNNING,
            RenderJobState.INTERRUPTED to RenderJobState.QUEUED,
            RenderJobState.INTERRUPTED to RenderJobState.CANCELLED,
            RenderJobState.FAILED to RenderJobState.QUEUED,
            RenderJobState.CANCELLED to RenderJobState.QUEUED
        )
        for ((from, to) in legal) {
            assertTrue("$from to $to", RenderJobs.canTransition(from, to))
        }
    }

    @Test
    fun canTransition_doneIsTerminal() {
        for (to in RenderJobState.values()) {
            assertFalse("DONE to $to", RenderJobs.canTransition(RenderJobState.DONE, to))
        }
        assertTrue(RenderJobs.isTerminal(RenderJobState.DONE))
        assertTrue(RenderJobs.isTerminal(RenderJobState.FAILED))
        assertTrue(RenderJobs.isTerminal(RenderJobState.CANCELLED))
        assertFalse(RenderJobs.isTerminal(RenderJobState.RUNNING))
        assertFalse(RenderJobs.isTerminal(RenderJobState.QUEUED))
        assertFalse(RenderJobs.isTerminal(RenderJobState.PAUSED))
        assertFalse(RenderJobs.isTerminal(RenderJobState.INTERRUPTED))
    }

    @Test
    fun canTransition_refusesIllegalMoves() {
        assertFalse(RenderJobs.canTransition(RenderJobState.QUEUED, RenderJobState.PAUSED))
        assertFalse(RenderJobs.canTransition(RenderJobState.QUEUED, RenderJobState.DONE))
        assertFalse(RenderJobs.canTransition(RenderJobState.PAUSED, RenderJobState.FAILED))
        assertFalse(RenderJobs.canTransition(RenderJobState.FAILED, RenderJobState.RUNNING))
        assertFalse(RenderJobs.canTransition(RenderJobState.CANCELLED, RenderJobState.RUNNING))
        assertFalse(RenderJobs.canTransition(RenderJobState.DONE, RenderJobState.QUEUED))
    }

    @Test
    fun start_selectsFirstPendingChapter() {
        val job = running()

        assertEquals(RenderJobState.RUNNING, job.state)
        assertEquals(2, job.currentChapter)
    }

    @Test
    fun start_resumesAfterCompletedChapters() {
        val partial = queued().copy(completedChapters = listOf(2))

        val job = RenderJobs.transition(partial, RenderJobState.RUNNING, clock)

        assertEquals(3, job.currentChapter)
    }

    @Test
    fun queued_cancel_clearsCurrent() {
        val job = RenderJobs.transition(queued(), RenderJobState.CANCELLED, clock)

        assertEquals(RenderJobState.CANCELLED, job.state)
        assertNull(job.currentChapter)
    }

    @Test
    fun running_pause_keepsCurrentChapter() {
        val paused = RenderJobs.transition(running(), RenderJobState.PAUSED, clock)

        assertEquals(RenderJobState.PAUSED, paused.state)
        assertEquals(2, paused.currentChapter)
    }

    @Test
    fun paused_resume_continuesSameChapter() {
        val paused = RenderJobs.transition(running(), RenderJobState.PAUSED, clock)

        val resumed = RenderJobs.transition(paused, RenderJobState.RUNNING, clock)

        assertEquals(RenderJobState.RUNNING, resumed.state)
        assertEquals(2, resumed.currentChapter)
    }

    @Test
    fun paused_cancel() {
        val paused = RenderJobs.transition(running(), RenderJobState.PAUSED, clock)

        val job = RenderJobs.transition(paused, RenderJobState.CANCELLED, clock)

        assertEquals(RenderJobState.CANCELLED, job.state)
        assertNull(job.currentChapter)
    }

    @Test
    fun paused_interrupted_isResumable() {
        val paused = RenderJobs.transition(running(), RenderJobState.PAUSED, clock)

        val marked = RenderJobs.transition(paused, RenderJobState.INTERRUPTED, clock)

        assertEquals(RenderJobState.INTERRUPTED, marked.state)
        assertEquals(2, marked.currentChapter)
    }

    @Test
    fun running_fail_needsReason() {
        val job = RenderJobs.transition(running(), RenderJobState.FAILED, clock, error = "tts gone")

        assertEquals(RenderJobState.FAILED, job.state)
        assertEquals("tts gone", job.error)
    }

    @Test
    fun running_fail_blankReason_throws() {
        try {
            RenderJobs.transition(running(), RenderJobState.FAILED, clock, error = "  ")
            fail("want blank failure reason refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reason"))
        }
    }

    @Test
    fun running_cancel() {
        val job = RenderJobs.transition(running(), RenderJobState.CANCELLED, clock)

        assertEquals(RenderJobState.CANCELLED, job.state)
        assertNull(job.currentChapter)
    }

    @Test
    fun running_interrupted_thenResumed_keepsFinishedChapters() {
        var job = running()
        job = RenderJobs.onChapterDone(job, 2, clock)
        assertEquals(listOf(2), job.completedChapters)

        val marked = RenderJobs.transition(job, RenderJobState.INTERRUPTED, clock)
        assertEquals(RenderJobState.INTERRUPTED, marked.state)
        assertEquals(listOf(2), marked.completedChapters)

        val resumed = RenderJobs.transition(marked, RenderJobState.RUNNING, clock)
        assertEquals(RenderJobState.RUNNING, resumed.state)
        assertEquals(listOf(2), resumed.completedChapters)
        assertEquals(3, resumed.currentChapter)
    }

    @Test
    fun interrupted_requeue_keepsFinishedChapters() {
        var job = running()
        job = RenderJobs.onChapterDone(job, 2, clock)
        val marked = RenderJobs.transition(job, RenderJobState.INTERRUPTED, clock)

        val requeued = RenderJobs.transition(marked, RenderJobState.QUEUED, clock)

        assertEquals(RenderJobState.QUEUED, requeued.state)
        assertNull(requeued.currentChapter)
        assertEquals(listOf(2), requeued.completedChapters)
    }

    @Test
    fun interrupted_abandon_cancels() {
        val marked = RenderJobs.transition(running(), RenderJobState.INTERRUPTED, clock)

        val job = RenderJobs.transition(marked, RenderJobState.CANCELLED, clock)

        assertEquals(RenderJobState.CANCELLED, job.state)
    }

    @Test
    fun failed_retry_requeues() {
        val failed = RenderJobs.transition(running(), RenderJobState.FAILED, clock, error = "boom")

        val job = RenderJobs.transition(failed, RenderJobState.QUEUED, clock)

        assertEquals(RenderJobState.QUEUED, job.state)
        assertNull(job.error)
        assertNull(job.currentChapter)
    }

    @Test
    fun cancelled_restart_requeues() {
        val cancelled = RenderJobs.transition(queued(), RenderJobState.CANCELLED, clock)

        val job = RenderJobs.transition(cancelled, RenderJobState.QUEUED, clock)

        assertEquals(RenderJobState.QUEUED, job.state)
    }

    @Test
    fun illegalTransition_throws() {
        try {
            RenderJobs.transition(queued(), RenderJobState.PAUSED, clock)
            fail("want QUEUED to PAUSED refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("QUEUED"))
        }
        try {
            RenderJobs.transition(
                RenderJob(bookId = "b1", state = RenderJobState.DONE, plan = plan()),
                RenderJobState.QUEUED, clock
            )
            fail("want DONE terminal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("DONE"))
        }
    }

    @Test
    fun transition_stampsUpdatedAt() {
        now = 10_000L
        val job = queued()
        now = 11_000L

        val started = RenderJobs.transition(job, RenderJobState.RUNNING, clock)

        assertEquals(11_000L, started.updatedAt)
    }

    @Test
    fun onChapterDone_advancesAndFinishes() {
        var job = running(listOf(2, 3))
        job = RenderJobs.onChapterDone(job, 2, clock)

        assertEquals(RenderJobState.RUNNING, job.state)
        assertEquals(listOf(2), job.completedChapters)
        assertEquals(3, job.currentChapter)

        job = RenderJobs.onChapterDone(job, 3, clock)

        assertEquals(RenderJobState.DONE, job.state)
        assertEquals(listOf(2, 3), job.completedChapters)
        assertNull(job.currentChapter)
    }

    @Test
    fun onChapterDone_outsideRunning_throws() {
        try {
            RenderJobs.onChapterDone(queued(), 2, clock)
            fail("want chapter finish outside RUNNING refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("RUNNING"))
        }
    }

    @Test
    fun onChapterDone_unknownChapter_throws() {
        try {
            RenderJobs.onChapterDone(running(), 99, clock)
            fail("want unknown chapter refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("99"))
        }
    }

    @Test
    fun onChapterDone_duplicate_throws() {
        var job = running()
        job = RenderJobs.onChapterDone(job, 2, clock)
        try {
            RenderJobs.onChapterDone(job, 2, clock)
            fail("want duplicate finish refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("already done"))
        }
    }

    @Test
    fun directFinish_needsCompletePlan() {
        try {
            RenderJobs.transition(running(), RenderJobState.DONE, clock)
            fail("want incomplete finish refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
        val single = running(listOf(7))
        val finished = RenderJobs.onChapterDone(single, 7, clock)
        assertEquals(RenderJobState.DONE, finished.state)
    }
}
