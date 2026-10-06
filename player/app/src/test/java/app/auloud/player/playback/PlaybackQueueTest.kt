package app.auloud.player.playback

import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WP6: [PlaybackQueue] mapping on plain JVM (no Media3, no Robolectric):
 * Manifest-to-queue mapping (URIs, titles, artist, ordering, artwork) and
 * saved-position-to-start mapping (including clamping).
 */
class PlaybackQueueTest {

    private val chapters = listOf(
        ChapterInfo(index = 1, title = "Ch 1", text = "text/ch001.json", audio = "audio/ch001.mp3", durationMs = 600_000L),
        ChapterInfo(index = 2, title = "Ch 2", text = "text/ch002.json", audio = "audio/ch002.mp3", durationMs = 400_000L)
    )
    private val manifest = Manifest(
        specVersion = "1.0",
        id = "book-1",
        title = "Example Book",
        type = "epub",
        audio = app.auloud.player.bundle.AudioInfo(),
        chapters = chapters,
        author = "A. Author",
        cover = "cover.jpg"
    )

    private val audioUriOf: (String, String) -> String = { dir, rel -> "file://$dir/$rel" }

    @Test
    fun build_mapsOneItemPerChapter() {
        val items = PlaybackQueue.build(manifest, "/books/b1", audioUriOf, "file:///books/b1/cover.jpg")

        assertEquals(2, items.size)
        assertEquals(0, items[0].chapterIndex)
        assertEquals(1, items[1].chapterIndex)
    }

    @Test
    fun build_resolvesUrisThroughStorage() {
        val items = PlaybackQueue.build(manifest, "/books/b1", audioUriOf, null)

        assertEquals("file:///books/b1/audio/ch001.mp3", items[0].audioUri)
        assertEquals("file:///books/b1/audio/ch002.mp3", items[1].audioUri)
        assertEquals("audio/ch001.mp3", items[0].audioRelPath)
    }

    @Test
    fun build_usesChapterTitlesAndBookTitleAsArtist() {
        val items = PlaybackQueue.build(manifest, "/books/b1", audioUriOf, null)

        assertEquals("Ch 1", items[0].title)
        assertEquals("Ch 2", items[1].title)
        assertEquals("Example Book", items[0].artist)
        assertEquals("Example Book", items[1].artist)
    }

    @Test
    fun build_attachesArtworkToEveryItem() {
        val items = PlaybackQueue.build(
            manifest, "/books/b1", audioUriOf, "file:///books/b1/cover.jpg"
        )

        assertEquals("file:///books/b1/cover.jpg", items[0].artworkUri)
        assertEquals("file:///books/b1/cover.jpg", items[1].artworkUri)
    }

    @Test
    fun build_nullArtwork_staysNull() {
        val items = PlaybackQueue.build(manifest, "/books/b1", audioUriOf, null)

        assertNull(items[0].artworkUri)
        assertNull(items[1].artworkUri)
    }

    @Test
    fun build_sortsByManifestIndex() {
        val shuffled = manifest.copy(chapters = listOf(chapters[1], chapters[0]))

        val items = PlaybackQueue.build(shuffled, "/books/b1", audioUriOf, null)

        assertEquals("Ch 1", items[0].title)
        assertEquals("Ch 2", items[1].title)
        assertEquals("file:///books/b1/audio/ch001.mp3", items[0].audioUri)
    }

    @Test
    fun build_carriesDurations() {
        val items = PlaybackQueue.build(manifest, "/books/b1", audioUriOf, null)

        assertEquals(600_000L, items[0].durationMs)
        assertEquals(400_000L, items[1].durationMs)
    }

    @Test
    fun startFrom_nullProgress_startsAtBeginning() {
        assertEquals(StartPosition(0, 0), PlaybackQueue.startFrom(null, 2))
    }

    @Test
    fun startFrom_savedPosition_mapsChapterAndPosition() {
        val saved = ProgressEntity("book-1", 1, 61_000L, 0L)

        assertEquals(
            StartPosition(1, 61_000L),
            PlaybackQueue.startFrom(saved, 2, listOf(600_000L, 400_000L))
        )
    }

    @Test
    fun startFrom_chapterBeyondEnd_clampsToLastChapter() {
        val saved = ProgressEntity("book-1", 7, 10L, 0L)

        assertEquals(
            StartPosition(1, 10L),
            PlaybackQueue.startFrom(saved, 2, listOf(600_000L, 400_000L))
        )
    }

    @Test
    fun startFrom_negativeValues_clampToZero() {
        val saved = ProgressEntity("book-1", -3, -50L, 0L)

        assertEquals(StartPosition(0, 0), PlaybackQueue.startFrom(saved, 2))
    }

    @Test
    fun startFrom_positionBeyondChapterDuration_clampsToDuration() {
        val saved = ProgressEntity("book-1", 0, 999_999L, 0L)

        assertEquals(
            StartPosition(0, 600_000L),
            PlaybackQueue.startFrom(saved, 2, listOf(600_000L, 400_000L))
        )
    }

    @Test
    fun startFrom_emptyBook_returnsZero() {
        val saved = ProgressEntity("book-1", 1, 61_000L, 0L)

        assertEquals(StartPosition(0, 0), PlaybackQueue.startFrom(saved, 0))
    }
}
