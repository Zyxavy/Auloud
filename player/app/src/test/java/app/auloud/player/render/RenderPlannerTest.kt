package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN3: [RenderPlanner] ordering and rolling-window tests on plain JVM.
 *
 * Positions are 0-based manifest order throughout. [isRendered] fakes
 * the RN7 manifest check (rendered chapters are skipped).
 */
class RenderPlannerTest {

    private var now = 1_000L
    private val clock: () -> Long = { now }

    private fun noneRendered(): (Int) -> Boolean = { false }

    @Test
    fun fromHere_startsAtReadingPositionThenForward() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 6, readingChapter = 2,
            scope = RenderScope.FromHere, isRendered = noneRendered(), clock = clock
        )

        assertEquals(2, plan.startChapter)
        assertEquals(listOf(2, 3, 4, 5), plan.orderedChapters)
    }

    @Test
    fun fromHere_atFirstChapter_coversWholeBook() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 4, readingChapter = 0,
            scope = RenderScope.FromHere, isRendered = noneRendered(), clock = clock
        )

        assertEquals(listOf(0, 1, 2, 3), plan.orderedChapters)
    }

    @Test
    fun fromHere_atLastChapter_coversOnlyThatChapter() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 4, readingChapter = 3,
            scope = RenderScope.FromHere, isRendered = noneRendered(), clock = clock
        )

        assertEquals(listOf(3), plan.orderedChapters)
    }

    @Test
    fun wholeBook_wrapsFromReadingPosition() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 5, readingChapter = 3,
            scope = RenderScope.WholeBook, isRendered = noneRendered(), clock = clock
        )

        assertEquals(3, plan.startChapter)
        assertEquals(listOf(3, 4, 0, 1, 2), plan.orderedChapters)
    }

    @Test
    fun wholeBook_atZero_isManifestOrder() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 3, readingChapter = 0,
            scope = RenderScope.WholeBook, isRendered = noneRendered(), clock = clock
        )

        assertEquals(listOf(0, 1, 2), plan.orderedChapters)
    }

    @Test
    fun nextN_takesFirstNForward() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 8, readingChapter = 2,
            scope = RenderScope.NextN(3), isRendered = noneRendered(), clock = clock
        )

        assertEquals(listOf(2, 3, 4), plan.orderedChapters)
    }

    @Test
    fun nextN_clampsAtEndOfBook() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 5, readingChapter = 4,
            scope = RenderScope.NextN(3), isRendered = noneRendered(), clock = clock
        )

        assertEquals(listOf(4), plan.orderedChapters)
    }

    @Test
    fun planner_skipsAlreadyRenderedChapters() {
        val isRendered: (Int) -> Boolean = { it == 2 || it == 4 }

        val fromHere = RenderPlanner.plan(
            bookId = "b1", chapterCount = 6, readingChapter = 1,
            scope = RenderScope.FromHere, isRendered = isRendered, clock = clock
        )
        assertEquals(listOf(1, 3, 5), fromHere.orderedChapters)

        val whole = RenderPlanner.plan(
            bookId = "b1", chapterCount = 6, readingChapter = 3,
            scope = RenderScope.WholeBook, isRendered = isRendered, clock = clock
        )
        assertEquals(listOf(3, 5, 0, 1), whole.orderedChapters)

        val next = RenderPlanner.plan(
            bookId = "b1", chapterCount = 6, readingChapter = 1,
            scope = RenderScope.NextN(2), isRendered = isRendered, clock = clock
        )
        assertEquals(listOf(1, 3), next.orderedChapters)
    }

    @Test
    fun planner_allRendered_yieldsEmptyPlan() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 3, readingChapter = 1,
            scope = RenderScope.FromHere, isRendered = { true }, clock = clock
        )

        assertTrue(plan.orderedChapters.isEmpty())
        assertEquals(1, plan.startChapter)
    }

    @Test
    fun planner_clampsOutOfRangeReadingPosition() {
        val low = RenderPlanner.plan(
            bookId = "b1", chapterCount = 4, readingChapter = -7,
            scope = RenderScope.FromHere, isRendered = noneRendered(), clock = clock
        )
        assertEquals(0, low.startChapter)
        assertEquals(listOf(0, 1, 2, 3), low.orderedChapters)

        val high = RenderPlanner.plan(
            bookId = "b1", chapterCount = 4, readingChapter = 99,
            scope = RenderScope.FromHere, isRendered = noneRendered(), clock = clock
        )
        assertEquals(3, high.startChapter)
        assertEquals(listOf(3), high.orderedChapters)
    }

    @Test
    fun planner_emptyBook_yieldsEmptyPlan() {
        val plan = RenderPlanner.plan(
            bookId = "b1", chapterCount = 0, readingChapter = 0,
            scope = RenderScope.WholeBook, isRendered = noneRendered(), clock = clock
        )

        assertTrue(plan.orderedChapters.isEmpty())
    }

    @Test
    fun roll_advancesWindowAndReportsNewlyEntered() {
        val first = RenderPlanner.plan(
            bookId = "b1", chapterCount = 8, readingChapter = 2,
            scope = RenderScope.NextN(3), isRendered = noneRendered(), clock = clock
        )
        assertEquals(listOf(2, 3, 4), first.orderedChapters)

        val moved = RenderPlanner.roll(first, 4, noneRendered(), clock)

        assertEquals(4, moved.startChapter)
        assertEquals(listOf(4, 5, 6), moved.orderedChapters)
        assertEquals(listOf(5, 6), RenderPlanner.newlyEntered(first, moved))
        // A window move keeps the original creation time (queue FIFO key).
        assertEquals(first.createdAt, moved.createdAt)
    }

    @Test
    fun roll_skipsChaptersRenderedSincePlanning() {
        val first = RenderPlanner.plan(
            bookId = "b1", chapterCount = 6, readingChapter = 1,
            scope = RenderScope.NextN(3), isRendered = noneRendered(), clock = clock
        )
        assertEquals(listOf(1, 2, 3), first.orderedChapters)

        val moved = RenderPlanner.roll(first, 2, { it == 2 }, clock)

        assertEquals(listOf(3, 4, 5), moved.orderedChapters)
        assertEquals(listOf(4, 5), RenderPlanner.newlyEntered(first, moved))
    }

    @Test
    fun roll_keepsScopeAndBook() {
        val first = RenderPlanner.plan(
            bookId = "b1", chapterCount = 6, readingChapter = 4,
            scope = RenderScope.WholeBook, isRendered = noneRendered(), clock = clock
        )

        val moved = RenderPlanner.roll(first, 5, noneRendered(), clock)

        assertEquals(RenderScope.WholeBook, moved.scope)
        assertEquals("b1", moved.bookId)
        assertEquals(listOf(5, 0, 1, 2, 3, 4), moved.orderedChapters)
    }

    @Test
    fun defaultNextN_isPositivePlaceholder() {
        assertTrue(RenderPlanner.DEFAULT_NEXT_N >= 1)
    }
}
