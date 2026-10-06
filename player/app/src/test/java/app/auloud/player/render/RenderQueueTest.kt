package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN3: [RenderQueues] cross-book FIFO tests on plain JVM.
 *
 * Proves one global runner (never one per book), queued-behind startup
 * for a second book, and sane cancel/remove behavior.
 */
class RenderQueueTest {

    private var now = 100_000L
    private fun tick(): Long {
        now += 1_000L
        return now
    }
    private val clock: () -> Long = { now }

    private fun queuedJob(bookId: String, createdAt: Long, chapters: List<Int> = listOf(0, 1)): RenderJob {
        val plan = RenderPlan(bookId, 4, 0, RenderScope.FromHere, chapters, createdAt)
        return RenderJob(bookId, RenderJobState.QUEUED, plan, createdAt = createdAt, updatedAt = createdAt)
    }

    private fun enqueueAll(vararg jobs: RenderJob): RenderQueue {
        var queue = RenderQueues.empty()
        for (job in jobs) {
            when (val outcome = RenderQueues.enqueue(queue, job)) {
                is RenderQueues.EnqueueOutcome.Enqueued -> queue = outcome.queue
                is RenderQueues.EnqueueOutcome.Duplicate ->
                    fail("want enqueue of ${job.bookId} to succeed")
            }
        }
        return queue
    }

    @Test
    fun secondBook_queuesBehindRunning() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)

        assertEquals("a", RenderQueues.runningJob(queue)!!.bookId)
        assertEquals(
            RenderJobState.QUEUED,
            queue.jobs.first { it.bookId == "b" }.state
        )
    }

    @Test
    fun onlyOneJobRuns_startNextIsNoOpWhileRunning() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)
        val before = queue

        assertEquals(queue, RenderQueues.startNext(queue, clock))
        assertEquals(1, queue.jobs.count { it.state == RenderJobState.RUNNING })
        assertEquals(before, queue)
    }

    @Test
    fun terminalJob_promotesOldestQueued() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L), queuedJob("c", 3L))
        queue = RenderQueues.startNext(queue, clock)
        var running = RenderQueues.runningJob(queue)!!
        running = RenderJobs.onChapterDone(running, 0, clock)
        running = RenderJobs.onChapterDone(running, 1, clock)
        assertEquals(RenderJobState.DONE, running.state)
        queue = RenderQueues.updateJob(queue, running, clock)

        assertEquals("b", RenderQueues.runningJob(queue)!!.bookId)
        assertEquals(RenderJobState.DONE, queue.jobs.first { it.bookId == "a" }.state)
    }

    @Test
    fun failedJob_freesRunnerForNextBook() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)
        val failed = RenderJobs.transition(RenderQueues.runningJob(queue)!!, RenderJobState.FAILED, clock, error = "x")
        queue = RenderQueues.updateJob(queue, failed, clock)

        assertEquals("b", RenderQueues.runningJob(queue)!!.bookId)
    }

    @Test
    fun pausedJob_doesNotPromoteNext() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)
        val paused = RenderJobs.transition(RenderQueues.runningJob(queue)!!, RenderJobState.PAUSED, clock)
        queue = RenderQueues.updateJob(queue, paused, clock)

        assertNull(RenderQueues.runningJob(queue))
        assertEquals(RenderJobState.QUEUED, queue.jobs.first { it.bookId == "b" }.state)
    }

    @Test
    fun interruptedJob_doesNotPromoteNext() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)
        val marked = RenderJobs.transition(RenderQueues.runningJob(queue)!!, RenderJobState.INTERRUPTED, clock)
        queue = RenderQueues.updateJob(queue, marked, clock)

        assertNull(RenderQueues.runningJob(queue))
        assertEquals(RenderJobState.QUEUED, queue.jobs.first { it.bookId == "b" }.state)
    }

    @Test
    fun enqueue_duplicateActiveBook_isRefused() {
        var queue = enqueueAll(queuedJob("a", 1L))
        queue = RenderQueues.startNext(queue, clock)

        val outcome = RenderQueues.enqueue(queue, queuedJob("a", 9L))

        assertTrue(outcome is RenderQueues.EnqueueOutcome.Duplicate)
        assertEquals(1, queue.jobs.size)
    }

    @Test
    fun enqueue_replacesTerminalRow() {
        var queue = enqueueAll(queuedJob("a", 1L))
        queue = RenderQueues.cancel(queue, "a", clock)
        assertEquals(RenderJobState.CANCELLED, queue.jobs.first { it.bookId == "a" }.state)

        val outcome = RenderQueues.enqueue(queue, queuedJob("a", 9L))
        assertTrue(outcome is RenderQueues.EnqueueOutcome.Enqueued)
        val next = (outcome as RenderQueues.EnqueueOutcome.Enqueued).queue
        assertEquals(1, next.jobs.size)
        assertEquals(9L, next.jobs.first { it.bookId == "a" }.createdAt)
    }

    @Test
    fun cancel_running_promotesNext() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)

        queue = RenderQueues.cancel(queue, "a", clock)

        assertEquals(RenderJobState.CANCELLED, queue.jobs.first { it.bookId == "a" }.state)
        assertEquals("b", RenderQueues.runningJob(queue)!!.bookId)
    }

    @Test
    fun cancel_queued_promotesNothing() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)

        queue = RenderQueues.cancel(queue, "b", clock)

        assertEquals("a", RenderQueues.runningJob(queue)!!.bookId)
        assertEquals(RenderJobState.CANCELLED, queue.jobs.first { it.bookId == "b" }.state)
    }

    @Test
    fun cancel_terminal_throws() {
        var queue = enqueueAll(queuedJob("a", 1L))
        queue = RenderQueues.cancel(queue, "a", clock)
        try {
            RenderQueues.cancel(queue, "a", clock)
            fail("want cancel of CANCELLED refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("CANCELLED"))
        }
    }

    @Test
    fun remove_dropsQueuedAndTerminal() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.remove(queue, "b")
        assertEquals(listOf("a"), queue.jobs.map { it.bookId })

        queue = RenderQueues.cancel(queue, "a", clock)
        queue = RenderQueues.remove(queue, "a")
        assertTrue(queue.jobs.isEmpty())
    }

    @Test
    fun remove_active_needsCancelFirst() {
        var queue = enqueueAll(queuedJob("a", 1L), queuedJob("b", 2L))
        queue = RenderQueues.startNext(queue, clock)
        try {
            RenderQueues.remove(queue, "a")
            fail("want remove of RUNNING refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("cancel"))
        }
        val paused = RenderJobs.transition(RenderQueues.runningJob(queue)!!, RenderJobState.PAUSED, clock)
        queue = RenderQueues.updateJob(queue, paused, clock)
        try {
            RenderQueues.remove(queue, "a")
            fail("want remove of PAUSED refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("cancel"))
        }
    }

    @Test
    fun fifoOrder_isByCreationTime() {
        tick()
        val queue = enqueueAll(queuedJob("b", 20L), queuedJob("a", 10L), queuedJob("c", 30L))

        assertEquals(listOf("a", "b", "c"), queue.jobs.map { it.bookId })
        val started = RenderQueues.startNext(queue, clock)
        assertEquals("a", RenderQueues.runningJob(started)!!.bookId)
    }

    @Test
    fun twoBooks_renderToCompletionInOrder() {
        var queue = enqueueAll(queuedJob("a", 1L, listOf(0)), queuedJob("b", 2L, listOf(0)))
        queue = RenderQueues.startNext(queue, clock)
        assertEquals("a", RenderQueues.runningJob(queue)!!.bookId)

        var done = RenderJobs.onChapterDone(RenderQueues.runningJob(queue)!!, 0, clock)
        queue = RenderQueues.updateJob(queue, done, clock)
        assertEquals("b", RenderQueues.runningJob(queue)!!.bookId)

        done = RenderJobs.onChapterDone(RenderQueues.runningJob(queue)!!, 0, clock)
        queue = RenderQueues.updateJob(queue, done, clock)
        assertNull(RenderQueues.runningJob(queue))
        assertTrue(queue.jobs.all { it.state == RenderJobState.DONE })
    }
}
