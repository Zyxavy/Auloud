package app.auloud.player.playback

import app.auloud.player.bundle.AudioInfo
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN9: playback gate for unrendered books (binding obligation from the IN1
 * review).
 *
 * The unrendered golden (`spec/fixtures/unrendered-golden/`, `render_state`
 * `none`) must yield zero audio items through the queue builder, with the
 * graceful "render audio to listen" outcome instead of a crash (blank-path
 * `IllegalArgumentException`) or a silent empty playlist. Rendered books
 * must build byte-identically through the gated path.
 */
class PlaybackGateTest {

    private val audioUriOf: (String, String) -> String = { dir, rel -> "file://$dir/$rel" }

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

    private fun manifestOf(fixture: String): Manifest {
        val dir = fixtureDir(fixture)
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val parsed = BundleParser.parse(dir)
        assertTrue(
            "expected parse success but got: ${parsed.exceptionOrNull()?.message}",
            parsed.isSuccess
        )
        return parsed.getOrThrow()
    }

    @Test
    fun unrenderedGolden_gateIsNeedsRender() {
        val manifest = manifestOf("unrendered-golden")

        assertEquals(PlaybackQueue.PlaybackGate.NeedsRender, PlaybackQueue.gateFor(manifest))
        assertEquals("render audio to listen", PlaybackQueue.NEEDS_RENDER_MESSAGE)
    }

    @Test
    fun unrenderedGolden_buildPlayableYieldsZeroAudioItems() {
        val manifest = manifestOf("unrendered-golden")

        val items = PlaybackQueue.buildPlayable(manifest, "/books/u1", audioUriOf, null)

        assertTrue("expected zero audio items, got: $items", items.isEmpty())
        assertTrue(
            "no item may carry an empty path",
            items.all { it.audioRelPath.isNotBlank() && it.audioUri.isNotBlank() }
        )
    }

    @Test
    fun unrenderedGolden_legacyBuildShowsWhatTheGatePrevents() {
        val manifest = manifestOf("unrendered-golden")

        val legacy = PlaybackQueue.build(manifest, "/books/u1", audioUriOf, null)

        assertEquals(2, legacy.size)
        assertTrue(
            "legacy items carry the blank paths the gate must never build from",
            legacy.all { it.audioRelPath.isBlank() }
        )
    }

    @Test
    fun renderedBundle_buildPlayableEqualsLegacyBuild() {
        val manifest = manifestOf("valid-bundle")

        assertEquals(PlaybackQueue.PlaybackGate.Playable, PlaybackQueue.gateFor(manifest))
        val legacy = PlaybackQueue.build(manifest, "/books/b1", audioUriOf, null)
        val gated = PlaybackQueue.buildPlayable(manifest, "/books/b1", audioUriOf, null)

        assertEquals(legacy, gated)
        assertTrue(gated.isNotEmpty())
        assertTrue(gated.all { it.audioRelPath.isNotBlank() })
    }

    @Test
    fun partialManifest_gateIsNeedsRenderWithRenderedSubsetSkipped() {
        val manifest = Manifest(
            specVersion = "2.0",
            id = "partial-1",
            title = "Partial",
            type = "epub",
            audio = AudioInfo(),
            renderState = "partial",
            chapters = listOf(
                ChapterInfo(
                    index = 1,
                    title = "Rendered",
                    text = "text/ch001.json",
                    audio = "audio/ch001.mp3",
                    durationMs = 60_000L
                ),
                ChapterInfo(index = 2, title = "Unrendered", text = "text/ch002.json")
            )
        )

        assertEquals(PlaybackQueue.PlaybackGate.NeedsRender, PlaybackQueue.gateFor(manifest))
        val items = PlaybackQueue.buildPlayable(manifest, "/books/p1", audioUriOf, null)
        assertEquals(1, items.size)
        assertEquals("audio/ch001.mp3", items[0].audioRelPath)
    }

    @Test
    fun emptyManifest_gateIsNeedsRender() {
        val manifest = Manifest(
            specVersion = "1.0",
            id = "empty-1",
            title = "Empty",
            type = "epub",
            audio = AudioInfo(),
            chapters = emptyList()
        )

        assertEquals(PlaybackQueue.PlaybackGate.NeedsRender, PlaybackQueue.gateFor(manifest))
        assertTrue(PlaybackQueue.buildPlayable(manifest, "/books/e1", audioUriOf, null).isEmpty())
    }

    @Test
    fun invalidChapter_blankAudioWithDuration_gateIsNeedsRender() {
        val manifest = Manifest(
            specVersion = "1.0",
            id = "bad-1",
            title = "Bad",
            type = "epub",
            audio = AudioInfo(),
            chapters = listOf(
                ChapterInfo(index = 1, title = "Bad", text = "text/ch001.json", durationMs = 60_000L)
            )
        )

        assertEquals(PlaybackQueue.PlaybackGate.NeedsRender, PlaybackQueue.gateFor(manifest))
        assertTrue(PlaybackQueue.buildPlayable(manifest, "/books/x1", audioUriOf, null).isEmpty())
    }
}
