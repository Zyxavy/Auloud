package app.auloud.player.render

/**
 * RN3: render-ahead plan from the reading position (Slice 10).
 *
 * The planner starts at the chapter containing the reading position, then
 * continues forward. Positions are 0-based indexes into manifest chapter
 * order (the same convention as [app.auloud.player.playback.PlaybackQueue]
 * `chapterIndex` and [app.auloud.player.data.ProgressEntity.chapterIndex],
 * NOT the 1-based `ChapterInfo.index`). Callers map the manifest with
 * `sortedBy { it.index }` first, then pass positions. The reading chapter
 * comes from saved progress or the unrendered reader state
 * (`UnrenderedReaderViewModel` chapter index plus sid); only the chapter
 * matters here, the sid does not change ordering.
 *
 * Scopes:
 *
 * - [RenderScope.FromHere]: every unrendered chapter from the reading
 *   position to the end of the book (no wrap).
 * - [RenderScope.NextN]: the first N unrendered chapters from the reading
 *   position forward (no wrap). This is the rolling window: as reading
 *   advances, [roll] recomputes and newly-entered tail chapters queue.
 * - [RenderScope.WholeBook]: every unrendered chapter in the book, ordered
 *   starting at the reading position then wrapping to earlier chapters, so
 *   a reader at chapter 20 does not wait through already-heard audio first.
 *
 * Already-rendered chapters are skipped (the caller supplies [isRendered],
 * which RN7 wires to the manifest duration/audio presence check; the
 * planner never reads storage itself). Out-of-range reading positions
 * clamp; an empty book yields an empty plan.
 *
 * Estimates: the planner does NOT estimate audio length, size or time.
 * RN9 maps [RenderPlan.orderedChapters] through [RenderEstimateInputs]
 * (per-chapter audio durations, storage rates, benchmark RTF range) to
 * show those numbers. The planner only exposes the ordered chapters.
 *
 * API 24 safe: pure Kotlin, epoch millis only, no `java.time`, no Android
 * types, no storage access.
 */
sealed interface RenderScope {

    /** Every unrendered chapter, ordered from the reading position with wrap. */
    data object WholeBook : RenderScope

    /** Every unrendered chapter from the reading position to the end. */
    data object FromHere : RenderScope

    /**
     * The first [chapters] unrendered chapters from the reading position.
     * Must be at least 1 (caller bug otherwise).
     */
    data class NextN(val chapters: Int) : RenderScope
}

/**
 * One render plan for a book.
 *
 * @param startChapter the clamped reading position (0-based) the order
 * starts from.
 * @param orderedChapters 0-based positions to render, in render order.
 * @param createdAt epoch millis (injected clock, never `java.time`).
 */
data class RenderPlan(
    val bookId: String,
    val chapterCount: Int,
    val startChapter: Int,
    val scope: RenderScope,
    val orderedChapters: List<Int>,
    val createdAt: Long
)

/**
 * Audio-length estimate inputs the planner needs from later work packages.
 * The planner never computes estimates itself; RN9 maps a finished
 * [RenderPlan] through these to show audio length, size and time.
 *
 * - [chapterAudioMs]: 0-based position to estimated audio milliseconds
 *   (from word counts or timings once RN4/RN5 measure; null when unknown).
 * - [bytesPerMs]: storage bytes per audio millisecond for the AAC setting
 *   (about 8 bytes per ms at 64 kbps plus container; RN6 confirms).
 * - [spoolBytesPerMs]: temp spool bytes per audio millisecond (plan notes
 *   about 86 MB per 30 minutes; RN4 confirms and cleans per chapter).
 * - [rtfLow]/[rtfHigh]: slowest/fastest measured render real-time factor
 *   (audio seconds per wall second; System TTS measured 0.86x to 2.56x
 *   run to run, so RN9 shows a range, never a single number).
 */
data class RenderEstimateInputs(
    val chapterAudioMs: Map<Int, Long?> = emptyMap(),
    val bytesPerMs: Double? = null,
    val spoolBytesPerMs: Double? = null,
    val rtfLow: Double? = null,
    val rtfHigh: Double? = null
)

object RenderPlanner {

    /**
     * Default [RenderScope.NextN] window size: a placeholder for "a few
     * hours of audio" until RN9 maps hours through measured chapter
     * durations. Five average chapters (about 20 to 30 minutes each) land
     * near two hours, inside one charging session on System TTS.
     */
    const val DEFAULT_NEXT_N = 5

    /**
     * Builds the plan for [bookId].
     *
     * @param readingChapter 0-based reading position (clamped into range).
     * @param isRendered true for chapters that already have audio (caller
     * supplies; RN7 wires the manifest check).
     * @param clock epoch millis source (default wall clock; tests inject).
     */
    fun plan(
        bookId: String,
        chapterCount: Int,
        readingChapter: Int,
        scope: RenderScope,
        isRendered: (Int) -> Boolean,
        clock: () -> Long = System::currentTimeMillis
    ): RenderPlan {
        require(bookId.isNotBlank()) { "bookId must not be blank" }
        require(chapterCount >= 0) { "chapterCount must be >= 0, got $chapterCount" }
        if (scope is RenderScope.NextN) {
            require(scope.chapters >= 1) { "NextN chapters must be >= 1, got ${scope.chapters}" }
        }
        if (chapterCount == 0) {
            return RenderPlan(bookId, 0, 0, scope, emptyList(), clock())
        }
        val start = readingChapter.coerceIn(0, chapterCount - 1)
        val ordered = when (scope) {
            is RenderScope.FromHere ->
                (start until chapterCount).filter { !isRendered(it) }
            is RenderScope.NextN ->
                (start until chapterCount).filter { !isRendered(it) }.take(scope.chapters)
            is RenderScope.WholeBook -> {
                val forward = (start until chapterCount).filter { !isRendered(it) }
                val wrapped = (0 until start).filter { !isRendered(it) }
                forward + wrapped
            }
        }
        return RenderPlan(bookId, chapterCount, start, scope, ordered, clock())
    }

    /**
     * Moves an existing plan window to [newReadingChapter] (same book,
     * scope and chapter count). The window recomputes from the new start;
     * tail chapters not in the old plan are the newly-queued work (see
     * [newlyEntered]). Already-rendered chapters drop out. The original
     * [RenderPlan.createdAt] is kept: a window move is not a new plan, so
     * queue FIFO order (keyed on job creation) never changes under it.
     */
    fun roll(
        plan: RenderPlan,
        newReadingChapter: Int,
        isRendered: (Int) -> Boolean,
        clock: () -> Long = System::currentTimeMillis
    ): RenderPlan {
        // Reuse plan() for the ordering rules, then restore createdAt so
        // the move does not look like a fresh enqueue. The clock call
        // inside plan() is discarded on purpose.
        val fresh = plan(
            bookId = plan.bookId,
            chapterCount = plan.chapterCount,
            readingChapter = newReadingChapter,
            scope = plan.scope,
            isRendered = isRendered,
            clock = clock
        )
        return fresh.copy(createdAt = plan.createdAt)
    }

    /**
     * Tail chapters in [newPlan] that were not in [oldPlan]: the work the
     * roll newly queues. Both plans must be for the same book.
     */
    fun newlyEntered(oldPlan: RenderPlan, newPlan: RenderPlan): List<Int> {
        require(oldPlan.bookId == newPlan.bookId) {
            "cannot diff plans for different books (${oldPlan.bookId} vs ${newPlan.bookId})"
        }
        val old = oldPlan.orderedChapters.toSet()
        return newPlan.orderedChapters.filter { it !in old }
    }
}
