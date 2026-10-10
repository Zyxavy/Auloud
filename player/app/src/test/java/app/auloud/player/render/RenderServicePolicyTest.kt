package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN8: [RenderServicePolicy] on plain JVM.
 *
 * Task-removal, restart, guard messages, media-to-chapter conversion and
 * the spool-dir rule all live here so the service stays a thin Android
 * shell around tested decisions.
 */
class RenderServicePolicyTest {

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

    private fun sparseMap(): ChapterMediaMap = ChapterMediaMap(
        entries = listOf(
            ChapterMediaEntry(chapterPos = 0, chapterNumber = 1, mediaIndex = 0),
            ChapterMediaEntry(chapterPos = 2, chapterNumber = 3, mediaIndex = 1)
        ),
        chapterCount = 3
    )

    private fun identityMap(): ChapterMediaMap = ChapterMediaMap(
        entries = listOf(
            ChapterMediaEntry(chapterPos = 0, chapterNumber = 1, mediaIndex = 0),
            ChapterMediaEntry(chapterPos = 1, chapterNumber = 2, mediaIndex = 1)
        ),
        chapterCount = 2
    )

    @Test
    fun taskRemoved_pausesRunningJob() {
        val paused = RenderServicePolicy.onTaskRemoved(job(RenderJobState.RUNNING)) { 7L }

        assertEquals(RenderJobState.PAUSED, paused.state)
        assertEquals(7L, paused.updatedAt)
    }

    @Test
    fun taskRemoved_leavesParkedJobsAlone() {
        for (state in listOf(
            RenderJobState.QUEUED,
            RenderJobState.PAUSED,
            RenderJobState.INTERRUPTED,
            RenderJobState.DONE,
            RenderJobState.FAILED,
            RenderJobState.CANCELLED
        )) {
            val kept = RenderServicePolicy.onTaskRemoved(job(state)) { 7L }
            assertEquals(state, kept.state)
        }
    }

    @Test
    fun restartAction_noJobStaysIdle() {
        assertEquals(RenderServicePolicy.RestartAction.NO_JOB, RenderServicePolicy.restartAction(null))
    }

    @Test
    fun restartAction_activeJobsWaitExplicit() {
        for (state in listOf(
            RenderJobState.RUNNING,
            RenderJobState.PAUSED,
            RenderJobState.INTERRUPTED,
            RenderJobState.QUEUED,
            RenderJobState.FAILED,
            RenderJobState.CANCELLED
        )) {
            assertEquals(
                RenderServicePolicy.RestartAction.WAIT_EXPLICIT,
                RenderServicePolicy.restartAction(job(state))
            )
        }
    }

    @Test
    fun restartAction_doneHasNothingToResume() {
        assertEquals(
            RenderServicePolicy.RestartAction.NOTHING_TO_RESUME,
            RenderServicePolicy.restartAction(job(RenderJobState.DONE))
        )
    }

    @Test
    fun shouldAutoResume_onlyGuardPausesWithAllClear() {
        assertTrue(RenderServicePolicy.shouldAutoResume(true, RenderGuardDecision.PROCEED))
        assertFalse(RenderServicePolicy.shouldAutoResume(false, RenderGuardDecision.PROCEED))
        assertFalse(
            RenderServicePolicy.shouldAutoResume(true, RenderGuardDecision.PAUSE_TEMPERATURE)
        )
        assertFalse(RenderServicePolicy.shouldAutoResume(true, RenderGuardDecision.PAUSE_STORAGE))
    }

    @Test
    fun pauseMessage_namesEachGuard() {
        assertNull(RenderServicePolicy.pauseMessage(RenderGuardDecision.PROCEED))
        assertEquals(
            "Paused - letting the battery cool down",
            RenderServicePolicy.pauseMessage(RenderGuardDecision.PAUSE_TEMPERATURE)
        )
        assertEquals(
            "Paused - low storage (free space to keep rendering)",
            RenderServicePolicy.pauseMessage(RenderGuardDecision.PAUSE_STORAGE)
        )
    }

    @Test
    fun chapterPosForMedia_nullMapPassesThrough() {
        assertEquals(2, RenderServicePolicy.chapterPosForMedia(2, null))
    }

    @Test
    fun chapterPosForMedia_identityMapsOntoItself() {
        assertEquals(1, RenderServicePolicy.chapterPosForMedia(1, identityMap()))
    }

    @Test
    fun chapterPosForMedia_sparseMapConverts() {
        val map = sparseMap()
        assertEquals(0, RenderServicePolicy.chapterPosForMedia(0, map))
        assertEquals(2, RenderServicePolicy.chapterPosForMedia(1, map))
    }

    @Test
    fun chapterPosForMedia_unmappedSavesNothing() {
        assertNull(RenderServicePolicy.chapterPosForMedia(5, sparseMap()))
    }

    @Test
    fun finishedSaveAllowed_onlyIdentity() {
        assertTrue(RenderServicePolicy.finishedSaveAllowed(null))
        assertTrue(RenderServicePolicy.finishedSaveAllowed(identityMap()))
        assertFalse(RenderServicePolicy.finishedSaveAllowed(sparseMap()))
    }

    @Test
    fun spoolDirFor_joinsCachePlusBook() {
        assertEquals(
            "/cache/render-spool/book-1",
            RenderServicePolicy.spoolDirFor("/cache", "book-1")
        )
        assertEquals(
            "/cache/render-spool/book-1",
            RenderServicePolicy.spoolDirFor("/cache/", "book-1")
        )
    }

    @Test
    fun resumeAfterStream_onlyWhenStreamPausedIt() {
        assertTrue(RenderServicePolicy.resumeAfterStream(pausedByStream = true))
        assertFalse(RenderServicePolicy.resumeAfterStream(pausedByStream = false))
    }
}
