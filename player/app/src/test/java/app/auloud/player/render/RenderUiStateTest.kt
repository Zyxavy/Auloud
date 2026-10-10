package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN9: [RenderUiState] pure UI logic on plain JVM.
 *
 * Covers the brief Verify: option mapping, estimate ranges (always
 * ranges, never single numbers), error mapping, chip text and chapter
 * states. No Android, no Robolectric.
 */
class RenderUiStateTest {

    // Option mapping.

    @Test
    fun planForOption_wholeBook_wrapsFromReadingPosition() {
        val plan = planForOption(
            bookId = "b",
            chapterCount = 4,
            readingChapter = 2,
            option = RenderOption.WHOLE_BOOK,
            nextN = 5,
            isRendered = { it == 0 }
        )
        assertEquals(listOf(2, 3, 1), plan.orderedChapters)
    }

    @Test
    fun planForOption_fromHere_runsToEnd() {
        val plan = planForOption(
            bookId = "b",
            chapterCount = 4,
            readingChapter = 1,
            option = RenderOption.FROM_HERE,
            nextN = 5,
            isRendered = { it == 2 }
        )
        assertEquals(listOf(1, 3), plan.orderedChapters)
    }

    @Test
    fun planForOption_nextN_takesFirstN() {
        val plan = planForOption(
            bookId = "b",
            chapterCount = 5,
            readingChapter = 0,
            option = RenderOption.NEXT_N,
            nextN = 2,
            isRendered = { false }
        )
        assertEquals(listOf(0, 1), plan.orderedChapters)
    }

    @Test
    fun planForOption_nextN_clampsToAtLeastOne() {
        val plan = planForOption(
            bookId = "b",
            chapterCount = 3,
            readingChapter = 0,
            option = RenderOption.NEXT_N,
            nextN = 0,
            isRendered = { false }
        )
        assertEquals(listOf(0), plan.orderedChapters)
    }

    @Test
    fun planForOption_emptyBook_plansNothing() {
        val plan = planForOption(
            bookId = "b",
            chapterCount = 0,
            readingChapter = 0,
            option = RenderOption.WHOLE_BOOK,
            nextN = 5,
            isRendered = { false }
        )
        assertTrue(plan.orderedChapters.isEmpty())
    }

    // Estimate math.

    @Test
    fun rtfRange_slowBelowFast() {
        assertTrue(RENDER_RTF_LOW > 0.0)
        assertTrue(RENDER_RTF_HIGH > RENDER_RTF_LOW)
    }

    @Test
    fun computeRenderEstimate_sumsPlanAndRangesWallTime() {
        val view = computeRenderEstimate(
            ordered = listOf(0, 1),
            audioMsByChapter = mapOf(0 to 60_000L, 1 to 120_000L)
        )
        assertEquals(180_000L, view.audioMs)
        assertEquals(0, view.unknownChapters)
        assertEquals((180_000L / RENDER_RTF_HIGH).toLong(), view.wallFastMs)
        assertEquals((180_000L / RENDER_RTF_LOW).toLong(), view.wallSlowMs)
        assertTrue(view.wallFastMs < view.wallSlowMs)
        assertTrue(view.sizeBytes > 0L)
    }

    @Test
    fun computeRenderEstimate_unknownChaptersUseFallbackAndCount() {
        val view = computeRenderEstimate(
            ordered = listOf(0, 1),
            audioMsByChapter = mapOf(0 to 60_000L)
        )
        assertEquals(60_000L + RenderEstimates.DEFAULT_CHAPTER_AUDIO_MS, view.audioMs)
        assertEquals(1, view.unknownChapters)
    }

    @Test
    fun computeRenderEstimate_emptyPlan_needsNothing() {
        val view = computeRenderEstimate(emptyList())
        assertEquals(0L, view.audioMs)
        assertEquals(0L, view.sizeBytes)
        assertEquals(0, view.unknownChapters)
    }

    // Formatting.

    @Test
    fun formatAudioLength_shapes() {
        assertEquals("less than a minute of audio", formatAudioLength(0L))
        assertEquals("less than a minute of audio", formatAudioLength(59_000L))
        assertEquals("about 5 min of audio", formatAudioLength(300_000L))
        assertEquals("about 2 h 5 min of audio", formatAudioLength(7_500_000L))
        assertEquals("about 3 h of audio", formatAudioLength(10_800_000L))
    }

    @Test
    fun formatSizeBytes_shapes() {
        assertEquals("about 0 KB", formatSizeBytes(0L))
        assertEquals("about 900 KB", formatSizeBytes(921_600L))
        assertEquals("about 104 MB", formatSizeBytes(109_051_904L))
    }

    @Test
    fun formatWallRange_alwaysARange() {
        val range = formatWallRange(1_200_000L, 3_600_000L)
        assertTrue("range needs two endpoints, was: $range", " to " in range)
        assertEquals("less than a minute of rendering", formatWallRange(0L, 0L))
        val degenerate = formatWallRange(60_000L, 60_000L)
        assertTrue("even equal ends read as a range, was: $degenerate", " to " in degenerate)
    }

    @Test
    fun estimateNote_namesUnknownChapters() {
        assertEquals("Estimate from chapter lengths.", estimateNote(0))
        assertTrue("was: ${estimateNote(2)}", "2 chapter" in estimateNote(2))
    }

    // Error mapping.

    @Test
    fun renderErrorText_blank_isPlain() {
        assertTrue(renderErrorText(null).startsWith("Rendering stopped"))
        assertTrue(renderErrorText("  ").startsWith("Rendering stopped"))
    }

    @Test
    fun renderErrorText_nothingToRender_isPlain() {
        val text = renderErrorText("Nothing to render: every planned chapter already has audio")
        assertEquals(
            "There is nothing to render. Every planned chapter already has audio.",
            text
        )
    }

    @Test
    fun renderErrorText_missingVoice_pointsAtSettings() {
        val text = renderErrorText("narrator voice is not set (choose one in voice settings)")
        assertTrue("was: $text", "Settings" in text && "Voices" in text)
    }

    @Test
    fun renderErrorText_missingEngine_pointsAtSettings() {
        val text = renderErrorText("dialogue voice \"x\" needs engine \"piper\" (engine not installed)")
        assertTrue("was: $text", "engine" in text && "Settings" in text)
    }

    @Test
    fun renderErrorText_missingPack_pointsAtModels() {
        val text = renderErrorText("voice \"x\" is not available from engine \"y\" (missing model pack or voice)")
        assertTrue("was: $text", "/Auloud/models/" in text)
    }

    @Test
    fun renderErrorText_lowStorage_isPlain() {
        val text = renderErrorText("Paused - low storage (free space to keep rendering)")
        assertTrue("was: $text", "free space" in text)
    }

    @Test
    fun renderErrorText_tooWarm_isPlain() {
        val text = renderErrorText("Paused - letting the battery cool down")
        assertTrue("was: $text", "too warm" in text)
    }

    @Test
    fun renderErrorText_safBook_isPlain() {
        val text = renderErrorText("Books in picked folders need app-storage output (later)")
        assertTrue("was: $text", "picked folder" in text)
    }

    @Test
    fun renderErrorText_unreadableManifest_isPlain() {
        val text = renderErrorText("manifest unreadable (unexpected end)")
        assertTrue("was: $text", "Re-import" in text)
    }

    @Test
    fun renderErrorText_cancelled_isPlain() {
        assertEquals("Rendering cancelled.", renderErrorText("rendering cancelled by user"))
    }

    @Test
    fun renderErrorText_unknown_keepsDetail() {
        val text = renderErrorText("chapter 2: spool index unreadable")
        assertTrue("was: $text", text.startsWith("Rendering stopped: "))
        assertTrue("was: $text", "spool index unreadable" in text)
    }

    @Test
    fun userStrings_haveNoBannedCharacters() {
        val samples = mutableListOf<String>()
        samples.add(formatAudioLength(7_500_000L))
        samples.add(formatSizeBytes(109_051_904L))
        samples.add(formatWallRange(1_200_000L, 3_600_000L))
        samples.add(estimateNote(2))
        samples.add(estimateNote(0))
        samples.add(voicesSummary("", "", 1.0f, 1.0f))
        samples.add(voicesSummary("system:a", "system:b", 1.0f, 0.75f))
        for (state in ChapterRenderState.values()) samples.add(chapterStatusText(state))
        samples.add(renderChipText("partial", null)!!)
        samples.add(renderChipText("none", null)!!)
        samples.add(renderChipText(null, RenderJobProgress(1, 2, RenderJobState.RUNNING))!!)
        samples.add(renderChipText(null, RenderJobProgress(1, 2, RenderJobState.PAUSED))!!)
        samples.add(renderChipText(null, RenderJobProgress(0, 1, RenderJobState.FAILED))!!)
        samples.add(renderErrorText(null))
        samples.add(renderErrorText("Nothing to render: every planned chapter already has audio"))
        samples.add(renderErrorText("narrator voice is not set (choose one in voice settings)"))
        samples.add(renderErrorText("chapter 2: spool index unreadable"))
        samples.add(
            renderDebugText(0.86, 2.56, "ch 2 (1 of 3 done)", "live in notification", 36.5f, 12_288L)
        )
        for (text in samples) {
            assertFalse("em-dash in: $text", "—" in text)
            assertFalse("arrow in: $text", "→" in text)
        }
    }

    // Chips.

    @Test
    fun renderChipText_running_showsPercent() {
        assertEquals("Rendering 50%", renderChipText("partial", RenderJobProgress(1, 2, RenderJobState.RUNNING)))
        assertEquals("Rendering 0%", renderChipText("none", RenderJobProgress(0, 3, RenderJobState.RUNNING)))
        assertEquals("Rendering 100%", renderChipText("partial", RenderJobProgress(2, 2, RenderJobState.RUNNING)))
    }

    @Test
    fun renderChipText_parked_showsPaused() {
        assertEquals("Paused at 50%", renderChipText("partial", RenderJobProgress(1, 2, RenderJobState.PAUSED)))
        assertEquals(
            "Paused at 50%",
            renderChipText("partial", RenderJobProgress(1, 2, RenderJobState.INTERRUPTED))
        )
        assertEquals("Paused at 0%", renderChipText("none", RenderJobProgress(0, 2, RenderJobState.QUEUED)))
    }

    @Test
    fun renderChipText_failed_showsFailed() {
        assertEquals(
            "Render failed",
            renderChipText("partial", RenderJobProgress(1, 2, RenderJobState.FAILED))
        )
    }

    @Test
    fun renderChipText_terminal_fallsBackToRenderState() {
        assertEquals(
            "Partially rendered",
            renderChipText("partial", RenderJobProgress(2, 2, RenderJobState.DONE))
        )
        assertEquals(
            "Partially rendered",
            renderChipText("partial", RenderJobProgress(0, 2, RenderJobState.CANCELLED))
        )
        assertNull(renderChipText("complete", RenderJobProgress(2, 2, RenderJobState.DONE)))
        assertNull(renderChipText(null, RenderJobProgress(2, 2, RenderJobState.DONE)))
    }

    @Test
    fun renderChipText_noJob_readsRenderState() {
        assertEquals("Partially rendered", renderChipText("partial", null))
        assertEquals("Not rendered", renderChipText("none", null))
        assertNull(renderChipText("complete", null))
        assertNull(renderChipText(null, null))
        assertNull(renderChipText("half", null))
    }

    @Test
    fun renderChipText_zeroTotal_fallsBack() {
        assertEquals("Partially rendered", renderChipText("partial", RenderJobProgress(0, 0, RenderJobState.RUNNING)))
    }

    // Chapter states.

    private fun job(
        state: RenderJobState,
        ordered: List<Int>,
        done: List<Int> = emptyList(),
        current: Int? = null
    ): RenderJob {
        val plan = RenderPlan(
            bookId = "b",
            chapterCount = 4,
            startChapter = 0,
            scope = RenderScope.WholeBook,
            orderedChapters = ordered,
            createdAt = 0L
        )
        return RenderJob(
            bookId = "b",
            state = state,
            plan = plan,
            completedChapters = done,
            currentChapter = current
        )
    }

    @Test
    fun chapterStateFor_renderedAlwaysRendered() {
        assertEquals(
            ChapterRenderState.RENDERED,
            chapterStateFor(0, true, job(RenderJobState.RUNNING, listOf(0), current = 0))
        )
        assertEquals(ChapterRenderState.RENDERED, chapterStateFor(0, true, null))
    }

    @Test
    fun chapterStateFor_noJob_isUnrendered() {
        assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(1, false, null))
    }

    @Test
    fun chapterStateFor_running_marksCurrent() {
        val running = job(RenderJobState.RUNNING, listOf(1, 2), current = 1)
        assertEquals(ChapterRenderState.RENDERING, chapterStateFor(1, false, running))
        assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(2, false, running))
        assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(3, false, running))
    }

    @Test
    fun chapterStateFor_parked_marksPendingPaused() {
        val paused = job(RenderJobState.PAUSED, listOf(1, 2), current = 1)
        assertEquals(ChapterRenderState.PAUSED, chapterStateFor(1, false, paused))
        assertEquals(ChapterRenderState.PAUSED, chapterStateFor(2, false, paused))
        assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(3, false, paused))
    }

    @Test
    fun chapterStateFor_failed_marksCurrent() {
        val failed = job(RenderJobState.FAILED, listOf(1, 2), current = 1, done = listOf())
        assertEquals(ChapterRenderState.FAILED, chapterStateFor(1, false, failed))
        assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(2, false, failed))
    }

    @Test
    fun chapterStateFor_terminal_isUnrendered() {
        val cancelled = job(RenderJobState.CANCELLED, listOf(1), current = null)
        assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(1, false, cancelled))
        val done = job(RenderJobState.DONE, listOf(0), done = listOf(0))
        assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(1, false, done))
    }

    @Test
    fun chapterStatusText_allStatesHaveWords() {
        assertEquals("Ready to listen", chapterStatusText(ChapterRenderState.RENDERED))
        assertEquals("Not rendered yet", chapterStatusText(ChapterRenderState.UNRENDERED))
        assertEquals("Rendering now", chapterStatusText(ChapterRenderState.RENDERING))
        assertEquals("Paused", chapterStatusText(ChapterRenderState.PAUSED))
        assertEquals("Render failed", chapterStatusText(ChapterRenderState.FAILED))
    }

    // Voices, words, debug.

    @Test
    fun voicesSummary_namesBlankVoices() {
        val text = voicesSummary("", "", 1.0f, 1.0f)
        assertTrue("was: $text", "not chosen" in text)
        val full = voicesSummary("system:a", "system:b", 1.0f, 0.75f)
        assertEquals("Narrator system:a at 1.00x. Dialogue system:b at 0.75x.", full)
    }

    @Test
    fun countWords_countsWhitespaceRuns() {
        assertEquals(0, countWords(""))
        assertEquals(0, countWords("   "))
        assertEquals(4, countWords("Hello brave new world."))
        assertEquals(2, countWords("  Five\nsix. "))
        assertEquals(1_600L, audioMsForWords(4))
        assertEquals(0L, audioMsForWords(-3))
    }

    @Test
    fun partialChapterTarget_listenWhenRendered() {
        val map = ChapterMediaMap(
            entries = listOf(app.auloud.player.render.ChapterMediaEntry(0, 1, 0)),
            chapterCount = 2
        )
        assertEquals(
            app.auloud.player.render.ChapterOpenTarget.LISTEN,
            partialChapterTarget(0, map)
        )
        assertEquals(
            app.auloud.player.render.ChapterOpenTarget.READ,
            partialChapterTarget(1, map)
        )
    }

    // UX1: hub Listen entry point.

    @Test
    fun listenChapterTarget_noRendered_returnsNull() {
        assertNull(listenChapterTarget(emptyList(), 0))
    }

    @Test
    fun listenChapterTarget_savedRenderedChapter_wins() {
        assertEquals(2, listenChapterTarget(listOf(0, 2, 3), 2))
    }

    @Test
    fun listenChapterTarget_savedUnrendered_fallsBackToFirstRendered() {
        assertEquals(0, listenChapterTarget(listOf(0, 2, 3), 1))
        assertEquals(2, listenChapterTarget(listOf(2, 3), 9))
    }

    // ST5: streaming entry point.

    @Test
    fun listenChapterTarget_streaming_targetsReadingChapter() {
        assertEquals(1, listenChapterTarget(emptyList(), 1, streamingAvailable = true))
        assertEquals(0, listenChapterTarget(emptyList(), 0, streamingAvailable = true))
    }

    @Test
    fun listenChapterTarget_streaming_keepsRenderedRule() {
        assertEquals(2, listenChapterTarget(listOf(0, 2, 3), 2, streamingAvailable = true))
        assertEquals(0, listenChapterTarget(listOf(0, 2, 3), 1, streamingAvailable = true))
    }

    @Test
    fun listenEntryIsLive_onlyForUnrenderedTargets() {
        assertTrue(listenEntryIsLive(1, listOf(0, 2)))
        assertTrue(!listenEntryIsLive(0, listOf(0, 2)))
        assertTrue(!listenEntryIsLive(null, emptyList()))
    }

    @Test
    fun showRowPlayButton_onlyRenderedRows() {
        assertTrue(showRowPlayButton(ChapterRenderState.RENDERED))
        assertTrue(!showRowPlayButton(ChapterRenderState.UNRENDERED))
        assertTrue(!showRowPlayButton(ChapterRenderState.RENDERING))
        assertTrue(!showRowPlayButton(ChapterRenderState.PAUSED))
        assertTrue(!showRowPlayButton(ChapterRenderState.FAILED))
        assertTrue(!showRowPlayButton(null))
    }

    @Test
    fun renderDebugText_hasFourLines() {
        val text = renderDebugText(0.86, 2.56, "ch 2 (1 of 3 done)", "live in notification", 36.5f, 12_288L)
        val lines = text.split("\n")
        assertEquals(4, lines.size)
        assertTrue("was: $text", "0.86x to 2.56x" in lines[0])
        assertTrue("was: $text", "ch 2" in lines[1])
        assertTrue("was: $text", "live in notification" in lines[2])
        assertTrue("was: $text", "36.5 C" in lines[3] && "12 KB" in lines[3])
        val missing = renderDebugText(0.86, 2.56, "idle", "live in notification", null, null)
        assertTrue("was: $missing", "battery - spool -" in missing)
    }

    @Test
    fun formatDebugBytes_shapes() {
        assertEquals("0 KB", formatDebugBytes(0L))
        assertEquals("12 KB", formatDebugBytes(12_288L))
        assertEquals("3 MB", formatDebugBytes(3_145_728L))
    }
}
