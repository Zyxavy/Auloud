package app.auloud.player.render

/**
 * RN3: FIFO render queue across books (Slice 10).
 *
 * One entry per book, oldest creation first. Scope is explicitly global:
 * at most one RUNNING job in the whole queue, never one per book. A
 * second book started while another renders stays QUEUED behind it; the
 * single runner fits the Tab E (one TTS engine plus one encoder on
 * about 1.5 GB RAM; two renderers would thrash memory, battery and
 * heat, and the Scribe UI already runs one global FIFO per D-054).
 *
 * Promotion rules (defined so cancel and remove stay sane):
 *
 * - [startNext] promotes the oldest QUEUED job to RUNNING only when no
 *   job is RUNNING; otherwise the queue is unchanged.
 * - A job leaving RUNNING for a terminal state (DONE, FAILED,
 *   CANCELLED) through [updateJob] auto-promotes the oldest QUEUED job,
 *   so the runner stays busy across books.
 * - PAUSED and INTERRUPTED never auto-promote: a paused job keeps its
 *   claim on the runner (resume continues it, it does not jump behind
 *   the next book), and an interrupted job waits for an explicit resume,
 *   requeue or abandon.
 * - [cancel] moves QUEUED, RUNNING, PAUSED or INTERRUPTED to CANCELLED
 *   (anything else throws); cancelling the RUNNING job promotes the
 *   next QUEUED job, cancelling any other job promotes nothing.
 * - [remove] drops QUEUED jobs directly plus terminal DONE, FAILED and
 *   CANCELLED rows; RUNNING, PAUSED and INTERRUPTED must cancel first
 *   (throw), so active work is never dropped silently.
 * - [enqueue] refuses a second active job for the same book (QUEUED,
 *   RUNNING, PAUSED or INTERRUPTED stay; the caller gets [Duplicate]),
 *   and replaces a terminal row for the same book with the fresh job.
 *
 * All functions are pure (new queue out, no coroutines, no storage), so
 * the service (RN8) owns threading and persistence while the rules stay
 * JVM-testable. Timestamps are epoch millis via the injected [clock].
 */
data class RenderQueue(val jobs: List<RenderJob> = emptyList())

object RenderQueues {

    /** Result of [enqueue]: fresh entry or a kept active row. */
    sealed interface EnqueueOutcome {
        /** The job was added (or replaced a terminal row). */
        data class Enqueued(val queue: RenderQueue) : EnqueueOutcome

        /** An active job for the book already exists; queue unchanged. */
        data class Duplicate(val existing: RenderJob) : EnqueueOutcome
    }

    /** Empty queue. */
    fun empty(): RenderQueue = RenderQueue()

    /** The single RUNNING job, or null when the runner is idle. */
    fun runningJob(queue: RenderQueue): RenderJob? =
        queue.jobs.firstOrNull { it.state == RenderJobState.RUNNING }

    /** FIFO order: oldest creation first, book id breaks ties. */
    private fun fifoOrder(jobs: List<RenderJob>): List<RenderJob> =
        jobs.sortedWith(compareBy({ it.createdAt }, { it.bookId }))

    /**
     * Adds [job] (normally freshly built in QUEUED). One job per book:
     * an active row for the book yields [EnqueueOutcome.Duplicate] and
     * the queue unchanged; a terminal row for the book is replaced.
     */
    fun enqueue(queue: RenderQueue, job: RenderJob): EnqueueOutcome {
        val existing = queue.jobs.firstOrNull { it.bookId == job.bookId }
        if (existing != null && !RenderJobs.isTerminal(existing.state)) {
            return EnqueueOutcome.Duplicate(existing)
        }
        val kept = queue.jobs.filterNot { it.bookId == job.bookId } + job
        return EnqueueOutcome.Enqueued(RenderQueue(fifoOrder(kept)))
    }

    /**
     * Promotes the oldest QUEUED job to RUNNING when the runner is idle.
     * Returns the queue unchanged when a job is already RUNNING or when
     * nothing is QUEUED.
     */
    fun startNext(
        queue: RenderQueue,
        clock: () -> Long = System::currentTimeMillis
    ): RenderQueue {
        if (runningJob(queue) != null) return queue
        val next = fifoOrder(queue.jobs).firstOrNull { it.state == RenderJobState.QUEUED }
            ?: return queue
        val started = RenderJobs.transition(next, RenderJobState.RUNNING, clock)
        return RenderQueue(fifoOrder(queue.jobs.map { if (it.bookId == next.bookId) started else it }))
    }

    /**
     * Stores an externally transitioned [job] (chapter finish, pause,
     * resume, failure, recovery) and auto-promotes the oldest QUEUED job
     * only when [job] just freed the runner by reaching DONE, FAILED or
     * CANCELLED. PAUSED and INTERRUPTED keep the runner idle on purpose.
     */
    fun updateJob(
        queue: RenderQueue,
        job: RenderJob,
        clock: () -> Long = System::currentTimeMillis
    ): RenderQueue {
        require(queue.jobs.any { it.bookId == job.bookId }) {
            "render queue: no job for book ${job.bookId}"
        }
        val replaced = RenderQueue(
            fifoOrder(queue.jobs.map { if (it.bookId == job.bookId) job else it })
        )
        if (!RenderJobs.isTerminal(job.state)) return replaced
        if (runningJob(replaced) != null) return replaced
        return startNext(replaced, clock)
    }

    /**
     * Cancels the job for [bookId] (QUEUED, RUNNING, PAUSED or
     * INTERRUPTED to CANCELLED). Cancelling the RUNNING job promotes the
     * oldest QUEUED job; cancelling any other job promotes nothing.
     *
     * @throws IllegalArgumentException when no job exists for the book.
     * @throws IllegalStateException when the job cannot be cancelled
     * (DONE, FAILED or already CANCELLED).
     */
    fun cancel(
        queue: RenderQueue,
        bookId: String,
        clock: () -> Long = System::currentTimeMillis
    ): RenderQueue {
        val job = queue.jobs.firstOrNull { it.bookId == bookId }
            ?: throw IllegalArgumentException("render queue: no job for book $bookId")
        val wasRunning = job.state == RenderJobState.RUNNING
        val cancelled = RenderJobs.transition(job, RenderJobState.CANCELLED, clock)
        val replaced = RenderQueue(
            fifoOrder(queue.jobs.map { if (it.bookId == bookId) cancelled else it })
        )
        return if (wasRunning) startNext(replaced, clock) else replaced
    }

    /**
     * Drops the entry for [bookId]. Allowed for QUEUED jobs (direct
     * drop, same as cancel plus delete) and terminal DONE, FAILED and
     * CANCELLED rows. RUNNING, PAUSED and INTERRUPTED must cancel first.
     *
     * @throws IllegalArgumentException when no job exists for the book.
     * @throws IllegalStateException when the job is still active.
     */
    fun remove(queue: RenderQueue, bookId: String): RenderQueue {
        val job = queue.jobs.firstOrNull { it.bookId == bookId }
            ?: throw IllegalArgumentException("render queue: no job for book $bookId")
        val droppable = job.state == RenderJobState.QUEUED || RenderJobs.isTerminal(job.state)
        if (!droppable) {
            throw IllegalStateException(
                "render queue: book $bookId is ${job.state}, cancel before removing"
            )
        }
        return RenderQueue(queue.jobs.filterNot { it.bookId == bookId })
    }
}
