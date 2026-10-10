package app.auloud.player.render

/**
 * VS3: re-render planner extension (D-116, D-119, D-121).
 *
 * First-render planning (RN3 [RenderPlanner]) selects unrendered chapters.
 * Re-render planning selects already-rendered chapters whose audio no
 * longer matches the book voices. Staleness comes from VS2
 * [RenderStaleness] (CURRENT, STALE, OUTDATED, NOT_RENDERED); this file
 * only orders the selection. It never reads storage and never touches
 * the service, queue or guards directly: it produces a plain [RenderPlan]
 * the existing RN3 job, RN3 queue and RN8 service consume unchanged
 * (same 0-based positions, same wrap rules, same clock injection).
 *
 * Modes (plan section 5 VS3):
 * - STALE_ONLY: every STALE chapter, ordered reading-position-forward
 *   with wrap (a reader at chapter 20 hears forward chapters first,
 *   matching D-121 "reading position forward first").
 * - FROM_HERE: STALE chapters from the reading position to the end
 *   (no wrap).
 * - ALL: every rendered chapter (STALE plus CURRENT plus OUTDATED),
 *   ordered reading-position-forward with wrap (force re-render;
 *   NOT_RENDERED chapters stay on the first-render path and are never
 *   included here).
 *
 * OUTDATED chapters never auto re-render (D-115): they are excluded
 * from STALE_ONLY and FROM_HERE and only included by an explicit ALL.
 * NOT_RENDERED chapters are excluded from all three modes.
 *
 * Scope mapping is persistence-only: STALE_ONLY and ALL persist as
 * WholeBook, FROM_HERE as FromHere, so the existing
 * [RenderStateStore] file shape and resume rules keep working. The
 * re-render mode itself is selection-time only and is not stored.
 *
 * Pure Kotlin, API 24 safe: no java.time, no Android types, no new
 * dependency, no permission.
 */
enum class RerenderMode {
    STALE_ONLY,
    FROM_HERE,
    ALL
}

/** Ask-what-to-do state for a voice change mid-render (model level, UI is VS4). */
data class VoiceChangeReplan(
    /** Job after the pause (RUNNING moved to PAUSED, others unchanged). */
    val pausedJob: RenderJob,
    /** Full new selection under the new voices (ordered, 0-based). */
    val newOrdered: List<Int>,
    /** Remaining work under the old plan (ordered minus completed). */
    val remainingOld: List<Int>,
    /** Remaining work under the new selection (newOrdered minus completed). */
    val remainingNew: List<Int>,
    /** In remainingNew but not in remainingOld (newly stale). */
    val added: List<Int>,
    /** In remainingOld but not in remainingNew (now current). */
    val removed: List<Int>
)

object RerenderPlanner {

    /**
     * Selects 0-based chapter positions to re-render.
     *
     * @param readingChapter 0-based reading position (clamped into range).
     * @param mode STALE_ONLY, FROM_HERE or ALL (see file KDoc).
     * @param staleState per-position staleness (VS2 states).
     */
    fun select(
        chapterCount: Int,
        readingChapter: Int,
        mode: RerenderMode,
        staleState: (Int) -> ChapterStaleState
    ): List<Int> {
        require(chapterCount >= 0) { "chapterCount must be >= 0, got $chapterCount" }
        if (chapterCount == 0) return emptyList()
        val start = readingChapter.coerceIn(0, chapterCount - 1)
        fun isWanted(pos: Int): Boolean {
            val state = staleState(pos)
            return when (mode) {
                RerenderMode.STALE_ONLY -> state == ChapterStaleState.STALE
                RerenderMode.FROM_HERE -> state == ChapterStaleState.STALE
                RerenderMode.ALL ->
                    state == ChapterStaleState.STALE ||
                        state == ChapterStaleState.CURRENT ||
                        state == ChapterStaleState.OUTDATED
            }
        }
        return when (mode) {
            RerenderMode.STALE_ONLY, RerenderMode.ALL -> {
                val forward = (start until chapterCount).filter(::isWanted)
                val wrapped = (0 until start).filter(::isWanted)
                forward + wrapped
            }
            RerenderMode.FROM_HERE ->
                (start until chapterCount).filter(::isWanted)
        }
    }

    /**
     * Builds a [RenderPlan] for a re-render selection (reuses the RN3
     * type so jobs, queue order and guards keep working unchanged).
     */
    fun plan(
        bookId: String,
        chapterCount: Int,
        readingChapter: Int,
        mode: RerenderMode,
        staleState: (Int) -> ChapterStaleState,
        clock: () -> Long = System::currentTimeMillis
    ): RenderPlan {
        require(bookId.isNotBlank()) { "bookId must not be blank" }
        if (chapterCount == 0) {
            val scope = if (mode == RerenderMode.FROM_HERE) {
                RenderScope.FromHere
            } else {
                RenderScope.WholeBook
            }
            return RenderPlan(bookId, 0, 0, scope, emptyList(), clock())
        }
        val start = readingChapter.coerceIn(0, chapterCount - 1)
        val ordered = select(chapterCount, readingChapter, mode, staleState)
        val scope = when (mode) {
            RerenderMode.FROM_HERE -> RenderScope.FromHere
            RerenderMode.STALE_ONLY, RerenderMode.ALL -> RenderScope.WholeBook
        }
        return RenderPlan(
            bookId = bookId,
            chapterCount = chapterCount,
            startChapter = start,
            scope = scope,
            orderedChapters = ordered,
            createdAt = clock()
        )
    }

    /**
     * Pauses [job] on a mid-render voice change and recomputes the
     * selection (model level; UI in VS4 asks what to do).
     *
     * - A RUNNING job moves to PAUSED (keeping completed chapters and
     *   the spool so resume skips finished work); any other state passes
     *   through unchanged.
     * - [newOrdered] is the full new selection under the new voices
     *   (from [select] or [plan]); remaining lists subtract the job
     *   completed set so finished work is never silently re-queued.
     * - added/removed diff remaining old vs remaining new for the
     *   impact dialog (VS4 owns the dialog, D-121).
     */
    fun onVoiceChange(
        job: RenderJob,
        newOrdered: List<Int>,
        clock: () -> Long = System::currentTimeMillis
    ): VoiceChangeReplan {
        val paused = if (job.state == RenderJobState.RUNNING) {
            RenderJobs.transition(job, RenderJobState.PAUSED, clock)
        } else {
            job
        }
        val done = paused.completedChapters.toSet()
        val remainingOld = paused.plan.orderedChapters.filter { it !in done }
        val remainingNew = newOrdered.filter { it !in done }
        val oldSet = remainingOld.toSet()
        val newSet = remainingNew.toSet()
        return VoiceChangeReplan(
            pausedJob = paused,
            newOrdered = newOrdered.toList(),
            remainingOld = remainingOld,
            remainingNew = remainingNew,
            added = remainingNew.filter { it !in oldSet },
            removed = remainingOld.filter { it !in newSet }
        )
    }
}
