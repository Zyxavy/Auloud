package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterTextLoader
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN9: [RenderAudioDelete] on plain JVM (map-backed [RenderFileIo]).
 *
 * Deleting returns chapters to unrendered through RN7-compatible
 * state: timed JSON rewritten untimed, manifest entry stripped with
 * `render_state` recomputed, audio plus job files removed.
 */
class RenderAudioDeleteTest {

    private class FakeIo : RenderFileIo {
        val files = HashMap<String, String>()
        var sleeps = 0
        val sleeper: (Long) -> Unit = { sleeps++ }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String =
            files[path] ?: throw IOException("$path: file not found or not readable")

        override fun writeText(path: String, text: String) {
            files[path] = text
        }

        override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean {
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
    private val manifestPath = "$bundleDir/manifest.json"
    private val text1 = "$bundleDir/text/ch001.json"
    private val text2 = "$bundleDir/text/ch002.json"
    private val audio1 = "$bundleDir/audio/ch001.m4a"
    private val audio2 = "$bundleDir/audio/ch002.m4a"
    private val jobFile = "$bundleDir/render-job.json"

    private fun timedChapterJson(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch 1","duration_ms":2000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":500,"text":"Hello. "},{"sid":2,"speaker":"dialogue","start_ms":750,"end_ms":1250,"text":"Hi. "}]}]}"""

    private fun untimedChapterJson(chapter: Int = 2): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch $chapter","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hello. "}]}]}"""

    private fun manifestPartialJson(): String = """
        {
          "spec_version": "2.0",
          "id": "b1",
          "title": "Partial",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true},
          "gain_db": {"dialogue": 0.5, "narrator": -1.5},
          "encoder_offset_ms": 0,
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 2000},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun partialIo(): FakeIo {
        val io = FakeIo()
        io.files[manifestPath] = manifestPartialJson()
        io.files[text1] = timedChapterJson()
        io.files[text2] = untimedChapterJson()
        io.files[audio1] = "fake-audio-1"
        io.files[jobFile] = """{"version": 1}"""
        return io
    }

    @Test
    fun deleteChapterAudio_stripsEntryJsonAndFiles() {
        val io = partialIo()

        val outcome = RenderAudioDelete.deleteChapterAudio(bundleDir, 1, io, io.sleeper)

        assertTrue("failed: ${outcome.exceptionOrNull()?.message}", outcome.isSuccess)
        val manifest = BundleParser.parseText(io.files[manifestPath]!!).getOrThrow()
        assertEquals("none", manifest.renderState)
        val entry = manifest.chapters.first { it.index == 1 }
        assertEquals("", entry.audio)
        assertNull(entry.durationMs)
        val chapter = ChapterTextLoader.parse("text/ch001.json", io.files[text1]!!).getOrThrow()
        assertNull(chapter.durationMs)
        assertTrue(chapter.sentencesInOrder().all { it.startMs == null && it.endMs == null })
        assertFalse(io.exists(audio1))
        assertFalse(io.exists(jobFile))
        assertTrue(io.exists(text2))
    }

    @Test
    fun deleteChapterAudio_unrenderedChapter_isNoopSuccess() {
        val io = partialIo()

        val outcome = RenderAudioDelete.deleteChapterAudio(bundleDir, 2, io, io.sleeper)

        assertTrue(outcome.isSuccess)
        val manifest = BundleParser.parseText(io.files[manifestPath]!!).getOrThrow()
        assertEquals("partial", manifest.renderState)
        assertTrue(io.exists(audio1))
        assertTrue(io.exists(text1))
    }

    @Test
    fun deleteChapterAudio_unknownChapter_failsNamed() {
        val io = partialIo()

        val outcome = RenderAudioDelete.deleteChapterAudio(bundleDir, 9, io, io.sleeper)

        assertTrue(outcome.isFailure)
        assertTrue(
            "was: ${outcome.exceptionOrNull()?.message}",
            outcome.exceptionOrNull()?.message?.contains("index 9") == true
        )
        assertTrue(io.exists(audio1))
    }

    @Test
    fun deleteChapterAudio_missingManifest_fails() {
        val io = FakeIo()

        val outcome = RenderAudioDelete.deleteChapterAudio(bundleDir, 1, io, io.sleeper)

        assertTrue(outcome.isFailure)
    }

    @Test
    fun deleteBookAudio_clearsBookReferences() {
        val io = partialIo()
        io.files[audio2] = "fake-audio-2"
        io.files[manifestPath] = manifestPartialJson().replace(
            """"index": 2, "title": "Ch 2", "text": "text/ch002.json"""",
            """"index": 2, "title": "Ch 2", "audio": "audio/ch002.m4a", """ +
                """"text": "text/ch002.json", "duration_ms": 1000"""
        )
        io.files[text2] = timedChapterJson().replace("\"chapter\":1", "\"chapter\":2")

        val outcome = RenderAudioDelete.deleteBookAudio(bundleDir, io, io.sleeper)

        assertTrue("failed: ${outcome.exceptionOrNull()?.message}", outcome.isSuccess)
        val manifest = BundleParser.parseText(io.files[manifestPath]!!).getOrThrow()
        assertEquals("none", manifest.renderState)
        assertTrue(manifest.chapters.all { it.durationMs == null })
        assertFalse(io.exists(audio1))
        assertFalse(io.exists(audio2))
        assertFalse(io.exists(jobFile))
        val raw = io.files[manifestPath]!!
        assertFalse("gain_db must go, was: $raw", "gain_db" in raw)
        assertFalse("encoder_offset_ms must go, was: $raw", "encoder_offset_ms" in raw)
        val chapter2 = ChapterTextLoader.parse("text/ch002.json", io.files[text2]!!).getOrThrow()
        assertNull(chapter2.durationMs)
    }

    @Test
    fun deleteBookAudio_missingManifest_fails() {
        val io = FakeIo()

        assertTrue(RenderAudioDelete.deleteBookAudio(bundleDir, io, io.sleeper).isFailure)
    }
}
