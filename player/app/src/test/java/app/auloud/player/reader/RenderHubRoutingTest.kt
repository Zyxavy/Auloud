package app.auloud.player.reader

import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.render.ChapterOpenTarget
import app.auloud.player.render.ChapterRenderState
import app.auloud.player.render.buildChapterMediaMap
import app.auloud.player.render.chapterStateFor
import app.auloud.player.render.chapterStatusText
import app.auloud.player.render.isChapterListeningEnabled
import app.auloud.player.render.isEndOfRenderedPortion
import app.auloud.player.render.partialChapterTarget
import app.auloud.player.render.planForOption
import app.auloud.player.render.RenderOption
import app.auloud.player.render.renderChipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN9 fix: the render hub is reachable for fresh imports (D-112).
 *
 * `none` books (fresh imports, the common case) open the hub just like
 * `partial` books; rendered paths (1.x null, 2.0 `complete`) are
 * unchanged. The zero-rendered hub degrades gracefully: an empty media
 * map never crashes, every chapter routes to READ, book-level Listen
 * stays disabled, and every row reports UNRENDERED with a Render action.
 *
 * Pure JVM: routing plus the per-chapter gates the hub reads. No
 * composable runs here (see the chip note in the fix report).
 */
class RenderHubRoutingTest {

    private fun unrendered(index: Int): ChapterInfo =
        ChapterInfo(index = index, title = "Ch $index", text = "text/ch%03d.json".format(index))

    private fun rendered(index: Int): ChapterInfo =
        ChapterInfo(
            index = index,
            title = "Ch $index",
            text = "text/ch%03d.json".format(index),
            audio = "audio/ch%03d.m4a".format(index),
            durationMs = 60_000L
        )

    @Test
    fun hubRouting_noneOpensHub() {
        assertTrue(shouldOpenRenderHub("none"))
    }

    @Test
    fun hubRouting_partialOpensHub() {
        assertTrue(shouldOpenRenderHub("partial"))
    }

    @Test
    fun hubRouting_renderedPathsUnchanged() {
        assertFalse(shouldOpenRenderHub(null))
        assertFalse(shouldOpenRenderHub("complete"))
        assertFalse(shouldOpenRenderHub("half"))
        assertFalse(shouldOpenRenderHub(""))
    }

    @Test
    fun hubRouting_bookLevelListenStaysDisabledForUnrendered() {
        assertFalse(isListenAvailable("none"))
        assertFalse(isListenAvailable("partial"))
        assertTrue(isListenAvailable(null))
        assertTrue(isListenAvailable("complete"))
    }

    @Test
    fun zeroRendered_emptyMapNeverCrashesAndAllChaptersRead() {
        val book = Manifest(
            specVersion = "2.0",
            id = "fresh-1",
            title = "Fresh",
            type = "epub",
            audio = null,
            renderState = "none",
            chapters = listOf(unrendered(1), unrendered(2), unrendered(3))
        )
        val map = buildChapterMediaMap(book)

        assertTrue(map.entries.isEmpty())
        assertEquals(3, map.chapterCount)
        for (pos in 0..2) {
            assertFalse(map.isRendered(pos))
            assertFalse(isChapterListeningEnabled(pos, map))
            assertEquals(ChapterOpenTarget.READ, partialChapterTarget(pos, map))
            assertEquals(ChapterRenderState.UNRENDERED, chapterStateFor(pos, false, null))
        }
        assertEquals("Not rendered yet", chapterStatusText(ChapterRenderState.UNRENDERED))
        // Empty maps never report the end of a rendered portion.
        assertFalse(isEndOfRenderedPortion(0, map))
        // The panel can still plan the whole book from chapter 0.
        val plan = planForOption(
            bookId = "fresh-1",
            chapterCount = 3,
            readingChapter = 0,
            option = RenderOption.WHOLE_BOOK,
            nextN = 5,
            isRendered = { false }
        )
        assertEquals(listOf(0, 1, 2), plan.orderedChapters)
        assertEquals("Not rendered", renderChipText("none", null))
    }

    @Test
    fun renderedBook_allChaptersListen() {
        val book = Manifest(
            specVersion = "2.0",
            id = "done-1",
            title = "Done",
            type = "epub",
            renderState = "complete",
            chapters = listOf(rendered(1), rendered(2))
        )
        val map = buildChapterMediaMap(book)

        assertEquals(2, map.entries.size)
        assertTrue(map.isIdentity)
        for (pos in 0..1) {
            assertTrue(isChapterListeningEnabled(pos, map))
            assertEquals(ChapterOpenTarget.LISTEN, partialChapterTarget(pos, map))
        }
    }
}
