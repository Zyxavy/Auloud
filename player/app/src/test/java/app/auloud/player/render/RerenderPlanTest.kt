package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS3: re-render planner selection plus mid-render recompute (D-116, D-119).
 *
 * Pure JVM: staleness lambdas, no Android, no storage, no service.
 * Reuses the RN3 [RenderPlan] type so jobs, queue order and guards keep
 * working unchanged (scope mapping is persistence-only, see file KDoc).
 */
class RerenderPlanTest {

    private fun states(vararg s: ChapterStaleState): (Int) -> ChapterStaleState =
        { pos -> s.getOrNull(pos) ?: ChapterStaleState.NOT_RENDERED }

    @Test
    fun staleOnly_ordersReadingForwardWithWrap() {
        // Chapters 0..4, stale at 1 and 4, reading at 3: forward 4 then wrapped 1.
        val sel = RerenderPlanner.select(
            chapterCount = 5,
            readingChapter = 3,
            mode = RerenderMode.STALE_ONLY,
            staleState = states(
                ChapterStaleState.CURRENT,
                ChapterStaleState.STALE,
                ChapterStaleState.OUTDATED,
                ChapterStaleState.CURRENT,
                ChapterStaleState.STALE
            )
        )
        assertEquals(listOf(4, 1), sel)
    }

    @Test
    fun staleOnly_excludesOutdatedAndNotRendered() {
        val sel = RerenderPlanner.select(
            chapterCount = 4,
            readingChapter = 0,
            mode = RerenderMode.STALE_ONLY,
            staleState = states(
                ChapterStaleState.STALE,
                ChapterStaleState.OUTDATED,
                ChapterStaleState.NOT_RENDERED,
                ChapterStaleState.CURRENT
            )
        )
        assertEquals(listOf(0), sel)
    }

    @Test
    fun fromHere_noWrap() {
        val sel = RerenderPlanner.select(
            chapterCount = 5,
            readingChapter = 2,
            mode = RerenderMode.FROM_HERE,
            staleState = states(
                ChapterStaleState.STALE,
                ChapterStaleState.STALE,
                ChapterStaleState.STALE,
                ChapterStaleState.STALE,
                ChapterStaleState.STALE
            )
        )
        assertEquals(listOf(2, 3, 4), sel)
    }

    @Test
    fun all_includesCurrentAndOutdated_excludesNotRendered() {
        val sel = RerenderPlanner.select(
            chapterCount = 4,
            readingChapter = 1,
            mode = RerenderMode.ALL,
            staleState = states(
                ChapterStaleState.NOT_RENDERED,
                ChapterStaleState.CURRENT,
                ChapterStaleState.OUTDATED,
                ChapterStaleState.STALE
            )
        )
        // Forward from 1 with wrap: 1, 2, 3 (0 is unrendered, excluded).
        assertEquals(listOf(1, 2, 3), sel)
    }

    @Test
    fun plan_reusesRenderPlanScopes() {
        val stale = states(ChapterStaleState.STALE, ChapterStaleState.STALE)
        val staleOnly = RerenderPlanner.plan("b1", 2, 0, RerenderMode.STALE_ONLY, stale) { 7L }
        assertEquals(RenderScope.WholeBook, staleOnly.scope)
        assertEquals(listOf(0, 1), staleOnly.orderedChapters)
        assertEquals(7L, staleOnly.createdAt)
        val fromHere = RerenderPlanner.plan("b1", 2, 0, RerenderMode.FROM_HERE, stale) { 9L }
        assertEquals(RenderScope.FromHere, fromHere.scope)
        val all = RerenderPlanner.plan("b1", 2, 0, RerenderMode.ALL, stale) { 11L }
        assertEquals(RenderScope.WholeBook, all.scope)
    }

    @Test
    fun plan_emptyBook_emptyOrdered() {
        val plan = RerenderPlanner.plan("b1", 0, 0, RerenderMode.STALE_ONLY, states()) { 1L }
        assertTrue(plan.orderedChapters.isEmpty())
    }

    @Test
    fun midRenderVoiceChange_pausesRunningAndDiffs() {
        val plan = RenderPlan(
            bookId = "b1", chapterCount = 4, startChapter = 0,
            scope = RenderScope.WholeBook, orderedChapters = listOf(0, 1, 2, 3),
            createdAt = 1L
        )
        val job = RenderJob(
            bookId = "b1", state = RenderJobState.RUNNING, plan = plan,
            completedChapters = listOf(0), currentChapter = 1,
            createdAt = 1L, updatedAt = 2L
        )
        // New selection under new voices: 1 still stale, 2 now current (removed), 3 still stale.
        val replan = RerenderPlanner.onVoiceChange(job, listOf(1, 3)) { 99L }
        assertEquals(RenderJobState.PAUSED, replan.pausedJob.state)
        assertEquals(listOf(0), replan.pausedJob.completedChapters)
        assertEquals(listOf(1, 2, 3), replan.remainingOld)
        assertEquals(listOf(1, 3), replan.remainingNew)
        assertTrue(replan.added.isEmpty())
        assertEquals(listOf(2), replan.removed)
        assertEquals(listOf(1, 3), replan.newOrdered)
    }

    @Test
    fun midRenderVoiceChange_addedWhenNewCoversUnplanned() {
        // Old plan covered only 0..1 (FROM_HERE window); new voices stale 3 too.
        val plan = RenderPlan(
            bookId = "b1", chapterCount = 4, startChapter = 0,
            scope = RenderScope.FromHere, orderedChapters = listOf(0, 1),
            createdAt = 1L
        )
        val job = RenderJob(
            bookId = "b1", state = RenderJobState.RUNNING, plan = plan,
            completedChapters = listOf(0), currentChapter = 1,
            createdAt = 1L, updatedAt = 2L
        )
        val replan = RerenderPlanner.onVoiceChange(job, listOf(1, 3)) { 99L }
        assertEquals(listOf(1), replan.remainingOld)
        assertEquals(listOf(1, 3), replan.remainingNew)
        assertEquals(listOf(3), replan.added)
        assertTrue(replan.removed.isEmpty())
    }

    @Test
    fun midRenderVoiceChange_nonRunningPassesThrough() {        val plan = RenderPlan(
            bookId = "b1", chapterCount = 2, startChapter = 0,
            scope = RenderScope.WholeBook, orderedChapters = listOf(0, 1),
            createdAt = 1L
        )
        val paused = RenderJob(
            bookId = "b1", state = RenderJobState.PAUSED, plan = plan,
            completedChapters = emptyList(), currentChapter = null,
            createdAt = 1L, updatedAt = 2L
        )
        val replan = RerenderPlanner.onVoiceChange(paused, listOf(0, 1)) { 5L }
        assertEquals(RenderJobState.PAUSED, replan.pausedJob.state)
        assertEquals(listOf(0, 1), replan.remainingNew)
    }
}
