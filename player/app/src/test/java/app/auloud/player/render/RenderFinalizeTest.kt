package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN7: [RenderFinalize] crash-safe write order (D-096).
 *
 * Covers the per-chapter order (audio presence gate, then timed JSON with
 * the offset applied, then the manifest update), the manifest fields
 * (`render_state`, chapter audio/duration, `gain_db` first-wins,
 * `encoder_offset_ms`, `render_fingerprint`), plus crash simulation at
 * each step (the states `RenderRecovery` repairs).
 */
class RenderFinalizeTest {

    private class FakeIo : RenderFileIo {
        val files = HashMap<String, String>()
        var sleeps = 0
        val sleeper: (Long) -> Unit = { sleeps++ }
        var renameFailuresLeft = 0

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String =
            files[path] ?: throw IOException("$path: file not found or not readable")

        override fun writeText(path: String, text: String) {
            files[path] = text
        }

        override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean {
            if (renameFailuresLeft > 0) {
                renameFailuresLeft--
                return false
            }
            val text = files[tmpPath] ?: return false
            files[targetPath] = text
            files.remove(tmpPath)
            return true
        }

        override fun deleteIfExists(path: String) {
            files.remove(path)
        }
    }

    private val bundleDir = "/books/b1"

    private fun untimedChapterJson(chapter: Int = 1): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hello. "},{"sid":2,"speaker":"dialogue","text":"Hi. "}]}]}"""

    private fun manifestNoneJson(): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Unrendered",
          "type": "epub",
          "render_state": "none",
          "source": {"file": "source/book.epub", "sha256": "abc"},
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "text": "text/ch001.json"},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun fingerprint(): RenderFingerprint = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to "system:narrator", "dialogue" to "system:dialogue"),
        speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
        engineVersions = mapOf("system" to "test-1")
    )

    private fun timings(): List<AssemblySentenceTiming> = listOf(
        AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 500),
        AssemblySentenceTiming(sid = 2, startMs = 750, endMs = 1250)
    )

    private fun seedUnrendered(io: FakeIo) {
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedChapterJson(1)
        io.files["$bundleDir/text/ch002.json"] = untimedChapterJson(2)
        io.files["$bundleDir/audio/ch001.m4a"] = "fake-audio"
    }

    private fun validateClean(io: FakeIo): List<String> {
        val raw = io.files["$bundleDir/manifest.json"] ?: return listOf("manifest missing")
        val manifest = BundleParser.parseText(raw).getOrThrow()
        return BundleValidator.validate(
            bundleDir,
            manifest,
            exists = { io.files.containsKey(it) },
            readText = { io.files[it] }
        )
    }

    @Test
    fun finalizeChapter_happyPath_writesJsonThenManifest() {
        val io = FakeIo()
        seedUnrendered(io)

        val result = RenderFinalize.finalizeChapter(
            bundleDir = bundleDir,
            chapterNumber = 1,
            audioRel = "audio/ch001.m4a",
            timings = timings(),
            durationMs = 2000,
            fingerprint = fingerprint(),
            gainDb = mapOf("narrator" to 0.0, "dialogue" to 1.5),
            encoderOffsetMs = 0,
            io = io,
            sleeper = io.sleeper
        )

        assertTrue(result.isSuccess)
        assertEquals(
            RenderFinalize.FinalizedChapter("audio/ch001.m4a", "text/ch001.json", 2000),
            result.getOrThrow()
        )
        // Timed JSON carries the raw timings at offset 0.
        val chapterRaw = io.files["$bundleDir/text/ch001.json"] ?: error("no chapter json")
        val chapter = BundleParser.json.decodeFromString(
            app.auloud.player.bundle.ChapterText.serializer(), chapterRaw
        )
        assertEquals(2000L, chapter.durationMs)
        val sentences = chapter.sentencesInOrder()
        assertEquals(0L, sentences[0].startMs)
        assertEquals(500L, sentences[0].endMs)
        assertEquals(750L, sentences[1].startMs)
        assertEquals(1250L, sentences[1].endMs)
        // Manifest: chapter entry rendered, state partial, fields stored.
        val manifestRaw = io.files["$bundleDir/manifest.json"] ?: error("no manifest")
        val manifest = BundleParser.parseText(manifestRaw).getOrThrow()
        assertEquals("partial", manifest.renderState)
        assertEquals("audio/ch001.m4a", manifest.chapters[0].audio)
        assertEquals(2000L, manifest.chapters[0].durationMs)
        assertTrue(manifest.chapters[0].renderFingerprint is JsonObject)
        assertEquals("m4a", manifest.audio?.format)
        assertEquals(0, (manifest.encoderOffsetMs as? JsonPrimitive)?.intOrNull)
        val gain = manifest.gainDb as? JsonObject ?: error("no gain_db")
        assertEquals(0.0, (gain["narrator"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN, 0.0)
        assertEquals(1.5, (gain["dialogue"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN, 0.0)
        // No temp residue.
        assertTrue(io.files.keys.none { it.endsWith(".tmp") })
        // The finished book validates clean.
        assertTrue("expected clean, got: ${validateClean(io)}", validateClean(io).isEmpty())
    }

    @Test
    fun finalizeChapter_nonzeroOffset_failsOnFirstStartRule() {
        // Spec section 6 rule 1 (first start_ms 0, carried exactly into
        // 2.0) conflicts with shifted timings until the spec records an
        // offset exception (D-109 open question for the RN11 measurement):
        // finalize applies the offset at write time and then fails shaped
        // on the self-check instead of writing spec-violating JSON. The
        // provisional offset is 0, so no live path hits this.
        val io = FakeIo()
        seedUnrendered(io)

        val result = RenderFinalize.finalizeChapter(
            bundleDir = bundleDir,
            chapterNumber = 1,
            audioRel = "audio/ch001.m4a",
            timings = timings(),
            durationMs = 2000,
            fingerprint = fingerprint(),
            encoderOffsetMs = 40,
            io = io,
            sleeper = io.sleeper
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "must name the file and the rule, was: $message",
            message.contains("text/ch001.json") && message.contains("must be 0")
        )
        // Nothing committed: manifest still none, JSON still untimed.
        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("none", manifest.renderState)
        assertFalse(io.files["$bundleDir/text/ch001.json"]!!.contains("start_ms"))
    }

    @Test
    fun finalizeChapter_audioMissing_failsWithoutWriting() {
        val io = FakeIo()
        io.files["$bundleDir/manifest.json"] = manifestNoneJson()
        io.files["$bundleDir/text/ch001.json"] = untimedChapterJson(1)

        val result = RenderFinalize.finalizeChapter(
            bundleDir = bundleDir,
            chapterNumber = 1,
            audioRel = "audio/ch001.m4a",
            timings = timings(),
            durationMs = 2000,
            fingerprint = fingerprint(),
            io = io,
            sleeper = io.sleeper
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("audio") == true)
        // Nothing written: JSON still untimed, manifest still none.
        assertTrue(io.files["$bundleDir/text/ch001.json"]!!.contains("Hello"))
        assertFalse(io.files["$bundleDir/text/ch001.json"]!!.contains("start_ms"))
        assertTrue(io.files["$bundleDir/manifest.json"]!!.contains("\"none\""))
    }

    @Test
    fun finalizeChapter_secondChapter_completesBook() {
        val io = FakeIo()
        seedUnrendered(io)
        io.files["$bundleDir/audio/ch002.m4a"] = "fake-audio"

        assertTrue(
            RenderFinalize.finalizeChapter(
                bundleDir, 1, "audio/ch001.m4a", timings(), 2000,
                fingerprint(), emptyMap(), 0, io, io.sleeper
            ).isSuccess
        )
        assertTrue(
            RenderFinalize.finalizeChapter(
                bundleDir, 2, "audio/ch002.m4a", timings(), 2000,
                fingerprint(), emptyMap(), 0, io, io.sleeper
            ).isSuccess
        )

        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("complete", manifest.renderState)
        assertTrue(validateClean(io).isEmpty())
    }

    @Test
    fun finalizeChapter_gainKeptWhenPresent_firstChapterWins() {
        val io = FakeIo()
        seedUnrendered(io)

        assertTrue(
            RenderFinalize.finalizeChapter(
                bundleDir, 1, "audio/ch001.m4a", timings(), 2000,
                fingerprint(), mapOf("narrator" to 2.0), 0, io, io.sleeper
            ).isSuccess
        )
        io.files["$bundleDir/audio/ch002.m4a"] = "fake-audio"
        assertTrue(
            RenderFinalize.finalizeChapter(
                bundleDir, 2, "audio/ch002.m4a", timings(), 2000,
                fingerprint(), mapOf("narrator" to 9.0), 0, io, io.sleeper
            ).isSuccess
        )

        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        val gain = manifest.gainDb as? JsonObject ?: error("no gain_db")
        assertEquals(2.0, (gain["narrator"] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN, 0.0)
    }

    @Test
    fun buildTimedChapterJson_sidMismatch_fails() {
        val result = RenderFinalize.buildTimedChapterJson(
            chapterNumber = 1,
            rawUntimedJson = untimedChapterJson(1),
            timings = listOf(AssemblySentenceTiming(sid = 1, startMs = 0, endMs = 500)),
            durationMs = 1000,
            offsetMs = 0
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("chapter 1") == true)
    }

    @Test
    fun buildTimedChapterJson_alreadyTimed_fails() {
        val timed = RenderFinalize.buildTimedChapterJson(
            1, untimedChapterJson(1), timings(), 2000, 0
        ).getOrThrow()

        val again = RenderFinalize.buildTimedChapterJson(1, timed, timings(), 2000, 0)

        assertTrue(again.isFailure)
        assertTrue(again.exceptionOrNull()?.message?.contains("already carries") == true)
    }

    @Test
    fun buildUpdatedManifestJson_unknownChapter_fails() {
        val result = RenderFinalize.buildUpdatedManifestJson(
            rawManifestJson = manifestNoneJson(),
            chapterNumber = 9,
            audio = "audio/ch009.m4a",
            durationMs = 1000L,
            fingerprint = fingerprint().toJsonObject(),
            gainDb = null,
            encoderOffsetMs = 0
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("index 9") == true)
    }

    @Test
    fun crashSimulation_audioDoneJsonMissing_leavesConsistentNone() {
        // Kill after the audio rename, before the JSON write.
        val io = FakeIo()
        seedUnrendered(io)

        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("none", manifest.renderState)
        assertTrue(io.files.containsKey("$bundleDir/audio/ch001.m4a"))
        assertFalse(io.files["$bundleDir/text/ch001.json"]!!.contains("start_ms"))
    }

    @Test
    fun crashSimulation_jsonDoneManifestStale_leavesTimedJson() {
        // Kill after the JSON rename, before the manifest write.
        val io = FakeIo()
        seedUnrendered(io)
        val timed = RenderFinalize.buildTimedChapterJson(
            1, io.files["$bundleDir/text/ch001.json"]!!, timings(), 2000, 0
        ).getOrThrow()
        RenderFinalize.atomicWriteText("$bundleDir/text/ch001.json", timed, io, io.sleeper)

        val manifest = BundleParser.parseText(io.files["$bundleDir/manifest.json"]!!).getOrThrow()
        assertEquals("none", manifest.renderState)
        assertTrue(io.files["$bundleDir/text/ch001.json"]!!.contains("start_ms"))
    }

    @Test
    fun atomicWrite_retryThenSuccess() {
        val io = FakeIo()
        io.renameFailuresLeft = 2

        val result = RenderFinalize.atomicWriteText("/b/f.json", "{}", io, io.sleeper)

        assertTrue(result.isSuccess)
        assertEquals("{}", io.files["/b/f.json"])
        assertEquals(2, io.sleeps)
    }

    @Test
    fun naming_audioAndTextRels() {
        assertEquals("audio/ch001.m4a", RenderFinalize.deviceAudioRel(1))
        assertEquals("audio/ch012.m4a", RenderFinalize.deviceAudioRel(12))
        assertEquals("text/ch001.json", RenderFinalize.chapterTextRel(1))
        assertEquals("$bundleDir/text/ch001.json.tmp", RenderFinalize.tmpPathFor("$bundleDir/text/ch001.json"))
    }
}
