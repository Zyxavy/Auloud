package app.auloud.player.playback

import app.auloud.player.render.ChapterMediaEntry
import app.auloud.player.render.ChapterMediaMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RN8 review: destroy-path save conversion on plain JVM.
 *
 * Pins that [destroySavePointOrNull] routes the playlist index through the
 * media-to-chapter map (like saveProgressNow) instead of saving the raw
 * index, and skips unmapped media instead of writing the wrong chapter.
 * Identity books pass through untouched (no behavior change).
 */
class PlaybackServiceDestroySaveTest {

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
    fun destroy_sparseMap_convertsMediaToChapter() {
        val point = destroySavePointOrNull("book-1", 1, 10_000L, sparseMap())

        assertEquals(ProgressSavePolicy.SavePoint("book-1", 2, 10_000L), point)
    }

    @Test
    fun destroy_sparseMap_firstItemConverts() {
        val point = destroySavePointOrNull("book-1", 0, 5_000L, sparseMap())

        assertEquals(ProgressSavePolicy.SavePoint("book-1", 0, 5_000L), point)
    }

    @Test
    fun destroy_unmappedMedia_skipsSave() {
        assertNull(destroySavePointOrNull("book-1", 5, 1_000L, sparseMap()))
    }

    @Test
    fun destroy_identityMap_passesThrough() {
        val point = destroySavePointOrNull("book-1", 1, 7_000L, identityMap())

        assertEquals(ProgressSavePolicy.SavePoint("book-1", 1, 7_000L), point)
    }

    @Test
    fun destroy_nullMap_passesThrough() {
        val point = destroySavePointOrNull("book-1", 2, 3_000L, null)

        assertEquals(ProgressSavePolicy.SavePoint("book-1", 2, 3_000L), point)
    }

    @Test
    fun destroy_noBook_skipsSave() {
        assertNull(destroySavePointOrNull(null, 0, 0L, sparseMap()))
        assertNull(destroySavePointOrNull("  ", 0, 0L, null))
    }

    @Test
    fun destroy_unsetIndex_skipsSave() {
        assertNull(destroySavePointOrNull("book-1", -1, 0L, null))
        assertNull(destroySavePointOrNull("book-1", -1, 0L, sparseMap()))
    }
}
