package app.auloud.player.bundle

import android.net.Uri
import app.auloud.player.playback.clampSeekRequest
import app.auloud.player.playback.restoreStart
import app.auloud.player.data.ProgressEntity
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP4: no-input-crash fixtures through the exact paths the player calls
 * at play time. The service (`PlaybackService.loadBook`) reads
 * `manifest.json` via storage and calls `BundleParser.parseText` inside a
 * try/catch (failure stops the service, never a crash); the reader loads
 * chapter text via [ChapterTextLoader] (failure is a reader message while
 * audio continues, RA10). Each test pins failure-is-a-result, never an
 * exception.
 */
class BundleResilienceTest {

    private class FakeStorage(private val files: Map<String, String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String =
            files[path] ?: throw java.io.IOException("missing: $path")
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used here")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
    }

    private fun chapterJson(
        sentences: String,
        durationMs: String = "7450",
        blocks: String? = null
    ): String {
        val body = blocks ?: """[{"id": 1, "type": "para", "sentences": [$sentences]}]"""
        return """{
            "spec_version": "1.0", "chapter": 1, "title": "Ch",
            "duration_ms": $durationMs, "blocks": $body
        }"""
    }

    private fun sentence(sid: Int, start: Long, end: Long): String {
        return """{"sid": $sid, "speaker": "narrator",
            "start_ms": $start, "end_ms": $end, "text": "Sentence $sid. "}"""
    }

    // Manifest path (service loadBook: storage.readText + parseText).

    @Test
    fun truncatedManifest_isFailureNotThrow() {
        val truncated = """{"spec_version": "1.0", "title": "Cut off"""
        val result = try {
            BundleParser.parseText(truncated)
        } catch (e: Exception) {
            throw AssertionError("parseText threw on truncated input: $e")
        }
        assertTrue("truncated manifest must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("error must name manifest.json, got: $message", message.contains("manifest.json"))
    }

    @Test
    fun wrongTypesManifest_isFailureNotThrow() {
        val payload = """{"spec_version": "1.0", "id": "x", "title": "T",
            "type": "epub", "audio": {"format": "mp3"},
            "chapters": "not-a-list"}"""
        val result = try {
            BundleParser.parseText(payload)
        } catch (e: Exception) {
            throw AssertionError("parseText threw on wrong types: $e")
        }
        assertTrue("wrong-types manifest must fail", result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("error must name manifest.json, got: $message", message.contains("manifest.json"))
    }

    @Test
    fun wrongTypeDurationManifest_isFailureNotThrow() {
        val payload = """{"spec_version": "1.0", "id": "x", "title": "T",
            "type": "epub", "audio": {"format": "mp3"},
            "chapters": [{"index": 1, "title": "Ch", "audio": "audio/ch001.mp3",
            "text": "text/ch001.json", "duration_ms": "long"}]}"""
        val result = try {
            BundleParser.parseText(payload)
        } catch (e: Exception) {
            throw AssertionError("parseText threw on wrong duration type: $e")
        }
        assertTrue("string duration_ms must fail", result.isFailure)
    }

    @Test
    fun hugeManifestValues_neverThrowAndClampHolds() {
        val payload = """{"spec_version": "1.0", "id": "x", "title": "T",
            "type": "epub", "audio": {"format": "mp3"},
            "chapters": [{"index": 999999999, "title": "Ch", "audio": "audio/ch001.mp3",
            "text": "text/ch001.json", "duration_ms": 9000000000000}]}"""
        val result = try {
            BundleParser.parseText(payload)
        } catch (e: Exception) {
            throw AssertionError("parseText threw on huge values: $e")
        }
        // The parser carries huge values through (range checks live in the
        // player mapping); what matters is nothing throws and the player
        // clamps them. A failure result is also acceptable.
        if (result.isSuccess) {
            val manifest = result.getOrThrow()
            val saved = ProgressEntity("book-1", 7, Long.MAX_VALUE, 0L)
            val start = restoreStart(saved, manifest.chapters.size, manifest.chapters.map { it.durationMs })
            assertTrue("clamped start in range", start.chapterIndex in 0 until manifest.chapters.size)
            assertTrue("clamped position in range", start.positionMs in 0L..manifest.chapters[start.chapterIndex].durationMs)
            clampSeekRequest(Long.MAX_VALUE, manifest.chapters[start.chapterIndex].durationMs)
        } else {
            assertTrue(
                "failure must name manifest.json",
                (result.exceptionOrNull()?.message ?: "").contains("manifest.json")
            )
        }
    }

    // Chapter-text path (reader load: missing text means Listen works).

    @Test
    fun missingChapterFile_isUnavailableNotThrow() = runBlocking {
        val result = try {
            ChapterTextLoader.load(FakeStorage(emptyMap()), "text/ch009.json")
        } catch (e: Exception) {
            throw AssertionError("loader threw on missing file: $e")
        }
        assertTrue("missing chapter file must fail", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("must be unavailable, got: $error", error is ChapterTextUnavailable)
        assertTrue(
            "must name file: ${error?.message}",
            error?.message?.startsWith("text/ch009.json") == true
        )
    }

    @Test
    fun truncatedChapterJson_isInvalidNamingFile() = runBlocking {
        val storage = FakeStorage(mapOf("text/ch001.json" to """{"spec_version": "1.0", "chap"""))
        val result = try {
            ChapterTextLoader.load(storage, "text/ch001.json")
        } catch (e: Exception) {
            throw AssertionError("loader threw on truncated JSON: $e")
        }
        assertTrue("truncated chapter JSON must fail", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("must be invalid, got: $error", error is ChapterTextInvalid)
        assertTrue(
            "must name file: ${error?.message}",
            error?.message?.startsWith("text/ch001.json") == true
        )
    }

    @Test
    fun wrongTypeChapterDuration_isInvalidNotThrow() = runBlocking {
        val payload = chapterJson(sentence(1, 0, 2000), durationMs = """"long"""")
        val result = try {
            ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        } catch (e: Exception) {
            throw AssertionError("loader threw on wrong duration type: $e")
        }
        assertTrue("string duration_ms must fail invalid", result.isFailure)
        assertTrue(result.exceptionOrNull() is ChapterTextInvalid)
    }

    @Test
    fun hugeChapterDuration_neverThrows() = runBlocking {
        val payload = chapterJson(sentence(1, 0, 1000), durationMs = "9223372036854775807")
        val result = try {
            ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        } catch (e: Exception) {
            throw AssertionError("loader threw on huge duration: $e")
        }
        // Valid shape (one sentence inside a huge chapter) loads; the
        // player clamps positions, so Listen still works.
        assertTrue("huge but valid chapter must load: ${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun hugeSentenceTimings_isFailureNotThrow() = runBlocking {
        val payload = chapterJson(sentence(1, 0, 9223372036854775807), durationMs = "7450")
        val result = try {
            ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        } catch (e: Exception) {
            throw AssertionError("loader threw on huge timings: $e")
        }
        assertTrue("sentence past duration must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is ChapterTextInvalid)
    }
}
