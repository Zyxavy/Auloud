package app.auloud.player.render

import app.auloud.player.bundle.AudioInfo
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN7: [ChapterMediaMap] explicit chapter-to-media mapping (D-099).
 *
 * Rendered books use the identity mapping (untouched behavior); sparse
 * books map playlist positions to manifest chapter positions so progress
 * never lands on the wrong chapter. Gating plus end-of-portion rules ride
 * the same type.
 */
class ChapterMediaMapTest {

    private fun rendered(index: Int, audio: String = "audio/ch%03d.m4a".format(index)): ChapterInfo =
        ChapterInfo(
            index = index,
            title = "Ch $index",
            text = "text/ch%03d.json".format(index),
            audio = audio,
            durationMs = 60_000L
        )

    private fun unrendered(index: Int): ChapterInfo =
        ChapterInfo(index = index, title = "Ch $index", text = "text/ch%03d.json".format(index))

    private fun manifest(chapters: List<ChapterInfo>, renderState: String = "partial"): Manifest =
        Manifest(
            specVersion = "2.0",
            id = "map-1",
            title = "Mapped",
            type = "epub",
            audio = AudioInfo(format = "m4a"),
            renderState = renderState,
            chapters = chapters
        )

    @Test
    fun renderedBook_usesIdentityMapping() {
        val map = buildChapterMediaMap(
            manifest(listOf(rendered(1), rendered(2)), renderState = "complete")
        )

        assertEquals(2, map.entries.size)
        assertEquals(ChapterMediaEntry(chapterPos = 0, chapterNumber = 1, mediaIndex = 0), map.entries[0])
        assertEquals(ChapterMediaEntry(chapterPos = 1, chapterNumber = 2, mediaIndex = 1), map.entries[1])
        assertTrue(map.isIdentity)
        assertEquals(2, map.chapterCount)
    }

    @Test
    fun sparseBook_mapsPlaylistPositionsToChapterPositions() {
        // Chapters 1 and 3 unrendered, chapter 2 rendered.
        val map = buildChapterMediaMap(
            manifest(listOf(unrendered(1), rendered(2), unrendered(3)))
        )

        assertEquals(1, map.entries.size)
        assertEquals(ChapterMediaEntry(chapterPos = 1, chapterNumber = 2, mediaIndex = 0), map.entries[0])
        assertFalse(map.isIdentity)
        assertEquals(3, map.chapterCount)
    }

    @Test
    fun lookup_unrenderedChapter_hasNoMediaIndex() {
        val map = buildChapterMediaMap(
            manifest(listOf(unrendered(1), rendered(2), unrendered(3)))
        )

        assertNull(map.mediaIndexOf(0))
        assertEquals(0, map.mediaIndexOf(1))
        assertNull(map.mediaIndexOf(2))
        assertEquals(1, map.chapterPosOf(0))
        assertNull(map.chapterPosOf(1))
    }

    @Test
    fun lookup_renderedFlags() {
        val map = buildChapterMediaMap(
            manifest(listOf(unrendered(1), rendered(2)))
        )

        assertFalse(map.isRendered(0))
        assertTrue(map.isRendered(1))
        assertTrue(isChapterListeningEnabled(1, map))
        assertFalse(isChapterListeningEnabled(0, map))
    }

    @Test
    fun emptyManifest_mapsNothing() {
        val map = buildChapterMediaMap(manifest(emptyList(), renderState = "none"))

        assertTrue(map.entries.isEmpty())
        assertEquals(0, map.chapterCount)
        assertTrue(map.isIdentity)
        assertNull(map.mediaIndexOf(0))
        assertFalse(isEndOfRenderedPortion(0, map))
    }

    @Test
    fun sortsByManifestIndex() {
        val map = buildChapterMediaMap(
            manifest(listOf(rendered(2), rendered(1)), renderState = "complete")
        )

        assertEquals(listOf(1, 2), map.entries.map { it.chapterNumber })
        assertEquals(listOf(0, 1), map.entries.map { it.mediaIndex })
        assertTrue(map.isIdentity)
    }

    @Test
    fun endOfPortion_onlyOnLastRenderedItem() {
        val map = buildChapterMediaMap(
            manifest(listOf(rendered(1), unrendered(2), rendered(3)))
        )

        assertEquals(2, map.entries.size)
        assertFalse(isEndOfRenderedPortion(0, map))
        assertTrue(isEndOfRenderedPortion(1, map))
    }

    @Test
    fun endMessage_isPlainLanguage() {
        assertEquals(
            "End of the rendered part. Render more chapters to keep listening.",
            END_OF_RENDERED_MESSAGE
        )
    }
}
