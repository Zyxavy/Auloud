package app.auloud.player.playback

import app.auloud.player.bundle.AudioInfo
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.ProgressEntity
import app.auloud.player.render.buildChapterMediaMap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN7: gated queue mapping for partial books (D-099, #14 items 2-3).
 *
 * `buildPlayable` carries the manifest chapter position in
 * `QueueItem.chapterIndex` (not the filtered playlist position), so saved
 * progress stays keyed by chapter on sparse books; `startFromMap`
 * converts through the explicit mapping; `requirePlayable` is the exact
 * shaped refusal the service throws (same code path, same string).
 */
class PlaybackQueueMappingTest {

    private val audioUriOf: (String, String) -> String = { dir, rel -> "file://$dir/$rel" }

    private fun rendered(index: Int): ChapterInfo =
        ChapterInfo(
            index = index,
            title = "Ch $index",
            text = "text/ch%03d.json".format(index),
            audio = "audio/ch%03d.m4a".format(index),
            durationMs = 60_000L
        )

    private fun unrendered(index: Int): ChapterInfo =
        ChapterInfo(index = index, title = "Ch $index", text = "text/ch%03d.json".format(index))

    private fun manifest(chapters: List<ChapterInfo>, renderState: String): Manifest =
        Manifest(
            specVersion = "2.0",
            id = "sparse-1",
            title = "Sparse",
            type = "epub",
            audio = AudioInfo(format = "m4a"),
            renderState = renderState,
            chapters = chapters
        )

    @Test
    fun buildPlayable_sparseBook_carriesManifestPositions() {
        val book = manifest(listOf(unrendered(1), rendered(2), unrendered(3), rendered(4)), "partial")

        val items = PlaybackQueue.buildPlayable(book, "/books/s1", audioUriOf, null)

        assertEquals(2, items.size)
        // Playlist positions 0 and 1, manifest chapter positions 1 and 3.
        assertEquals(1, items[0].chapterIndex)
        assertEquals(3, items[1].chapterIndex)
        assertEquals("audio/ch002.m4a", items[0].audioRelPath)
        assertEquals("audio/ch004.m4a", items[1].audioRelPath)
    }

    @Test
    fun buildPlayable_orderMatchesChapterMediaMap() {
        val book = manifest(listOf(rendered(1), unrendered(2), rendered(3)), "partial")
        val map = buildChapterMediaMap(book)

        val items = PlaybackQueue.buildPlayable(book, "/books/s1", audioUriOf, null)

        assertEquals(map.entries.size, items.size)
        for ((pos, entry) in map.entries.withIndex()) {
            assertEquals("playlist order must follow the map", entry.chapterPos, items[pos].chapterIndex)
        }
    }

    @Test
    fun buildPlayable_renderedBook_chapterIndexIsPosition() {
        val book = manifest(listOf(rendered(1), rendered(2)), "complete")

        val items = PlaybackQueue.buildPlayable(book, "/books/b1", audioUriOf, null)

        assertEquals(0, items[0].chapterIndex)
        assertEquals(1, items[1].chapterIndex)
    }

    @Test
    fun startFromMap_renderedChapter_mapsToMediaIndex() {
        val book = manifest(listOf(unrendered(1), rendered(2), rendered(3)), "partial")
        val map = buildChapterMediaMap(book)
        val saved = ProgressEntity("sparse-1", 2, 10_000L, 0L)

        val start = PlaybackQueue.startFromMap(
            saved, map, mapOf(0 to 60_000L, 1 to 60_000L, 2 to 60_000L)
        )

        // Manifest chapter position 2 is the second rendered item (media 1).
        assertEquals(StartPosition(1, 10_000L), start)
    }

    @Test
    fun startFromMap_unrenderedChapter_fallsBackToFirstRendered() {
        val book = manifest(listOf(unrendered(1), rendered(2)), "partial")
        val map = buildChapterMediaMap(book)
        val saved = ProgressEntity("sparse-1", 0, 0L, 0L)

        assertEquals(StartPosition(0, 0), PlaybackQueue.startFromMap(saved, map))
    }

    @Test
    fun startFromMap_positionBeyondDuration_clampsToChapter() {
        val book = manifest(listOf(unrendered(1), rendered(2)), "partial")
        val map = buildChapterMediaMap(book)
        val saved = ProgressEntity("sparse-1", 1, 999_999L, 0L)

        assertEquals(
            StartPosition(0, 60_000L),
            PlaybackQueue.startFromMap(saved, map, mapOf(1 to 60_000L))
        )
    }

    @Test
    fun startFromMap_nullProgress_startsAtFirstRendered() {
        val book = manifest(listOf(unrendered(1), rendered(2)), "partial")
        val map = buildChapterMediaMap(book)

        assertEquals(StartPosition(0, 0), PlaybackQueue.startFromMap(null, map))
    }

    @Test
    fun startFromMap_emptyMap_returnsZero() {
        val book = manifest(emptyList(), "none")
        val map = buildChapterMediaMap(book)
        val saved = ProgressEntity("sparse-1", 0, 5L, 0L)

        assertEquals(StartPosition(0, 0), PlaybackQueue.startFromMap(saved, map))
    }

    @Test
    fun startFromMap_outOfRangeChapter_clampsThenFallsBack() {
        val book = manifest(listOf(unrendered(1), rendered(2)), "partial")
        val map = buildChapterMediaMap(book)
        val saved = ProgressEntity("sparse-1", 9, 5L, 0L)

        // Clamped to chapter position 1, which is rendered at media 0.
        assertEquals(StartPosition(0, 5L), PlaybackQueue.startFromMap(saved, map))
    }

    private fun fixtureDir(name: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures/$name")
        if (direct.isDirectory) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures/$name")
            if (candidate.isDirectory) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    @Test
    fun requirePlayable_unrenderedGolden_failsShaped() {
        val dir = fixtureDir("unrendered-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()

        val result = PlaybackQueue.requirePlayable(manifest)

        assertTrue(result.isFailure)
        // Exact string the service throws (same code path via delegation).
        assertEquals(
            "manifest.json: book has no playable audio (render audio to listen)",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun requirePlayable_renderedGolden_succeeds() {
        val dir = fixtureDir("valid-bundle")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifest = BundleParser.parse(dir).getOrThrow()

        assertTrue(PlaybackQueue.requirePlayable(manifest).isSuccess)
    }

    @Test
    fun requirePlayable_partialBook_failsShaped() {
        val book = manifest(listOf(rendered(1), unrendered(2)), "partial")

        val result = PlaybackQueue.requirePlayable(book)

        assertTrue(result.isFailure)
        assertEquals(
            "manifest.json: book has no playable audio (render audio to listen)",
            result.exceptionOrNull()?.message
        )
    }
}
