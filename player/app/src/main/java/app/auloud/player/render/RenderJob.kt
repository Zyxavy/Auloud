package app.auloud.player.render

/**
 * RN3: render job state machine (Slice 10).
 *
 * States (plan decision 7): queued, running, paused, done, failed,
 * cancelled, interrupted (the process died; resumable from finished
 * chapters plus the spool, never redoing finished work).
 *
 * Legal transitions:
 *
 * - QUEUED -> RUNNING (start), QUEUED -> CANCELLED (cancel before start)
 * - RUNNING -> PAUSED (pause), RUNNING -> DONE (finish, only when every
 *   planned chapter is complete), RUNNING -> FAILED (error),
 *   RUNNING -> CANCELLED (cancel), RUNNING -> INTERRUPTED (startup
 *   recovery marks a job the dead process left behind)
 * - PAUSED -> RUNNING (resume), PAUSED -> CANCELLED (cancel),
 *   PAUSED -> INTERRUPTED (died while paused; still resumable)
 * - INTERRUPTED -> RUNNING (resume), INTERRUPTED -> QUEUED (requeue),
 *   INTERRUPTED -> CANCELLED (abandon)
 * - FAILED -> QUEUED (retry, keeps finished chapters so resume skips
 *   them), CANCELLED -> QUEUED (restart, same resume rule)
 * - DONE is terminal (no outgoing transitions)
 *
 * Per-chapter progress is not a state change: [onChapterDone] advances
 * [RenderJob.completedChapters] inside RUNNING and moves to DONE only
 * when the last planned chapter finishes. Requeueing keeps
 * [completedChapters] so resume skips finished work; cancelling clears
 * only the in-flight [currentChapter], never the finished list (a fresh
 * start after delete-audio is RN9 work, not a state rule).
 *
 * Illegal transitions are caller bugs and throw [IllegalStateException]
 * directly (the `ProgressRepository.save` guard style), never a wrapped
 * `Result`. Timestamps are epoch millis via the injected [clock], never
 * `java.time` (unavailable on API 24 without desugaring).
 *
 * API 24 safe: pure Kotlin, no Android types, no storage access.
 */
enum class RenderJobState {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

/**
 * One render job for a book.
 *
 * @param completedChapters 0-based chapter positions finished so far, in
 * completion order (kept across pause, failure and interruption).
 * @param currentChapter 0-based position now rendering (null when idle
 * or terminal).
 * @param error failure reason when [state] is FAILED, else null.
 * @param createdAt epoch millis of the enqueue (FIFO key, immutable).
 * @param updatedAt epoch millis of the last transition or chapter finish.
 */
data class RenderJob(
    val bookId: String,
    val state: RenderJobState,
    val plan: RenderPlan,
    val completedChapters: List<Int> = emptyList(),
    val currentChapter: Int? = null,
    val error: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

object RenderJobs {

    /**
     * True when ([from], [to]) is a legal state move (matrix above).
     * Pure, so the whole matrix is unit-testable without building jobs.
     */
    fun canTransition(from: RenderJobState, to: RenderJobState): Boolean =
        when (from) {
            RenderJobState.QUEUED ->
                to == RenderJobState.RUNNING || to == RenderJobState.CANCELLED
            RenderJobState.RUNNING ->
                to == RenderJobState.PAUSED ||
                    to == RenderJobState.DONE ||
                    to == RenderJobState.FAILED ||
                    to == RenderJobState.CANCELLED ||
                    to == RenderJobState.INTERRUPTED
            RenderJobState.PAUSED ->
                to == RenderJobState.RUNNING ||
                    to == RenderJobState.CANCELLED ||
                    to == RenderJobState.INTERRUPTED
            RenderJobState.INTERRUPTED ->
                to == RenderJobState.RUNNING ||
                    to == RenderJobState.QUEUED ||
                    to == RenderJobState.CANCELLED
            RenderJobState.FAILED ->
                to == RenderJobState.QUEUED
            RenderJobState.CANCELLED ->
                to == RenderJobState.QUEUED
            RenderJobState.DONE -> false
        }

    /** True for DONE, FAILED and CANCELLED (free the single runner). */
    fun isTerminal(state: RenderJobState): Boolean =
        state == RenderJobState.DONE ||
            state == RenderJobState.FAILED ||
            state == RenderJobState.CANCELLED

    /**
     * First planned chapter not yet completed, or null when the plan is
     * fully done. Drives start/resume chapter selection.
     */
    fun firstPending(job: RenderJob): Int? {
        val done = job.completedChapters.toSet()
        return job.plan.orderedChapters.firstOrNull { it !in done }
    }

    /**
     * Moves [job] to [to], stamping [clock] as [RenderJob.updatedAt].
     * Rules beyond the matrix: DONE needs a complete plan (else the
     * caller should use [onChapterDone]); FAILED needs a non-blank
     * [error]; entering RUNNING needs pending work and selects it as
     * [currentChapter]; entering QUEUED or CANCELLED clears
     * [currentChapter] but keeps [completedChapters] for resume.
     *
     * @throws IllegalStateException on an illegal move or unmet rule.
     */
    fun transition(
        job: RenderJob,
        to: RenderJobState,
        clock: () -> Long = System::currentTimeMillis,
        error: String? = null
    ): RenderJob {
        if (!canTransition(job.state, to)) {
            throw IllegalStateException(
                "render job ${job.bookId}: ${job.state} cannot go to $to"
            )
        }
        val now = clock()
        return when (to) {
            RenderJobState.RUNNING -> {
                val next = firstPending(job)
                    ?: throw IllegalStateException(
                        "render job ${job.bookId}: nothing pending, cannot run"
                    )
                job.copy(state = to, currentChapter = next, error = null, updatedAt = now)
            }
            RenderJobState.DONE -> {
                if (firstPending(job) != null) {
                    throw IllegalStateException(
                        "render job ${job.bookId}: plan incomplete, cannot finish"
                    )
                }
                job.copy(state = to, currentChapter = null, error = null, updatedAt = now)
            }
            RenderJobState.FAILED -> {
                require(!error.isNullOrBlank()) {
                    "render job ${job.bookId}: FAILED needs a reason"
                }
                job.copy(state = to, error = error, updatedAt = now)
            }
            RenderJobState.QUEUED ->
                job.copy(state = to, currentChapter = null, error = null, updatedAt = now)
            RenderJobState.CANCELLED ->
                job.copy(state = to, currentChapter = null, error = null, updatedAt = now)
            RenderJobState.PAUSED ->
                job.copy(state = to, error = null, updatedAt = now)
            RenderJobState.INTERRUPTED ->
                job.copy(state = to, error = null, updatedAt = now)
        }
    }

    /**
     * Records one finished chapter inside a RUNNING job. Advances
     * [completedChapters], moves [currentChapter] to the next pending
     * chapter, and flips to DONE when the plan completes.
     *
     * @throws IllegalStateException when the job is not RUNNING, the
     * chapter is not in the plan, or it is already recorded.
     */
    fun onChapterDone(
        job: RenderJob,
        chapter: Int,
        clock: () -> Long = System::currentTimeMillis
    ): RenderJob {
        if (job.state != RenderJobState.RUNNING) {
            throw IllegalStateException(
                "render job ${job.bookId}: chapter finish needs RUNNING, was ${job.state}"
            )
        }
        require(chapter in job.plan.orderedChapters) {
            "render job ${job.bookId}: chapter $chapter is not in the plan"
        }
        require(chapter !in job.completedChapters) {
            "render job ${job.bookId}: chapter $chapter is already done"
        }
        val now = clock()
        val done = job.completedChapters + chapter
        val next = job.copy(completedChapters = done).let(::firstPending)
        return if (next == null) {
            job.copy(
                state = RenderJobState.DONE,
                completedChapters = done,
                currentChapter = null,
                error = null,
                updatedAt = now
            )
        } else {
            job.copy(
                completedChapters = done,
                currentChapter = next,
                updatedAt = now
            )
        }
    }
}
