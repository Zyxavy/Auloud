package app.auloud.player.bundle

import android.net.Uri
import app.auloud.player.storage.BundleStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RA1: chapter text loading on plain JVM (no Robolectric, no Media3).
 * Golden fixture guards format drift; hand-built payloads pin every error
 * path (invalid JSON, overlap, bad sids, missing file, PDF form).
 */
class ChapterTextLoaderTest {

    private class FakeStorage(private val files: Map<String, String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String =
            files[path] ?: throw java.io.IOException("missing: $path")
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the loader")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
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

    private fun chapterJson(
        sentences: String,
        durationMs: Long = 7450L,
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

    // Golden fixture.

    @Test
    fun golden_ch001_loadsWithExpectedShape() = runBlocking {
        val dir = fixtureDir("scribe-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val raw = File(dir, "text/ch001.json").readText(Charsets.UTF_8)
        val storage = FakeStorage(mapOf("text/ch001.json" to raw))
        val result = ChapterTextLoader.load(storage, "text/ch001.json")
        assertTrue("expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val chapter = result.getOrThrow()
        assertEquals("Chapter One", chapter.title)
        assertEquals(7450L, chapter.durationMs)
        val sentences = chapter.sentencesInOrder()
        assertEquals((1..sentences.size).toList(), sentences.map { it.sid })
        assertEquals(0L, sentences.first().startMs)
        // IN1: sentence timings are nullable (absent for unrendered
        // chapters); this rendered golden always carries them.
        sentences.forEach {
            assertTrue(
                "sid ${it.sid}: bad range",
                it.startMs != null && it.endMs != null && it.startMs < it.endMs
            )
        }
    }

    // Error paths.

    @Test
    fun invalidJson_failsInvalid_namingFile() = runBlocking {
        val storage = FakeStorage(mapOf("text/ch001.json" to "{ not json"))
        val result = ChapterTextLoader.load(storage, "text/ch001.json")
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("wrong type: $error", error is ChapterTextInvalid)
        assertTrue("must name file: ${error?.message}", error?.message?.startsWith("text/ch001.json") == true)
    }

    @Test
    fun overlappingSentences_failsInvalid() = runBlocking {
        val payload = chapterJson("${sentence(1, 0, 2000)}, ${sentence(2, 1500, 3000)}")
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ChapterTextInvalid)
        assertTrue("${result.exceptionOrNull()?.message}", result.exceptionOrNull()?.message?.contains("overlaps") == true)
    }

    @Test
    fun nonConsecutiveSids_failsInvalid() = runBlocking {
        val payload = chapterJson("${sentence(1, 0, 2000)}, ${sentence(3, 2000, 3000)}")
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue(result.isFailure)
        assertTrue("${result.exceptionOrNull()?.message}", result.exceptionOrNull()?.message?.contains("1..2") == true)
    }

    @Test
    fun sentencePastDuration_failsInvalid() = runBlocking {
        val payload = chapterJson(sentence(1, 0, 9000), durationMs = 7450L)
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue(result.isFailure)
        assertTrue("${result.exceptionOrNull()?.message}", result.exceptionOrNull()?.message?.contains("past duration") == true)
    }

    @Test
    fun missingFile_failsUnavailable() = runBlocking {
        val result = ChapterTextLoader.load(FakeStorage(emptyMap()), "text/ch009.json")
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("wrong type: $error", error is ChapterTextUnavailable)
        assertTrue("must name file: ${error?.message}", error?.message?.startsWith("text/ch009.json") == true)
    }

    @Test
    fun pdfPagesForm_failsUnavailable_readerNotAvailable() = runBlocking {
        val payload = """{"spec_version": "1.0", "chapter": 1, "title": "P",
            "duration_ms": 1000, "pages": [{"page": 1, "start_ms": 0}]}"""
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("wrong type: $error", error is ChapterTextUnavailable)
        assertTrue("wrong subtype: $error", error is ChapterTextPdfForm)
        assertTrue("must say page-only: ${error?.message}", error?.message?.contains("page-only") == true)
    }

    @Test
    fun unknownKeys_ignoredAtEveryLevel() = runBlocking {
        val payload = """{"spec_version": "1.0", "chapter": 1, "title": "Ch",
            "duration_ms": 2000, "future_chapter_key": 1,
            "blocks": [{"id": 1, "type": "para", "future_block_key": true,
            "sentences": [{"sid": 1, "speaker": "narrator", "start_ms": 0,
            "end_ms": 2000, "text": "Hi. ", "future_sentence_key": []}]}]}"""
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue("expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1, result.getOrThrow().sentencesInOrder().size)
    }

    @Test
    fun unknownSpecVersion_failsInvalid() = runBlocking {
        val payload = """{"spec_version": "3.0", "chapter": 1, "title": "Ch",
            "duration_ms": 1000, "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "narrator", "start_ms": 0,
            "end_ms": 1000, "text": "Hi. "}]}]}"""
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue("spec 3.0 must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is ChapterTextInvalid)
        assertTrue((result.exceptionOrNull()?.message ?: "").contains("3.0"))
    }

    @Test
    fun spec20TimedChapter_loads() = runBlocking {
        // IN1: a rendered 2.0 chapter carries timings exactly as in 1.x.
        val payload = """{"spec_version": "2.0", "chapter": 1, "title": "Ch",
            "duration_ms": 1000, "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "narrator", "start_ms": 0,
            "end_ms": 1000, "text": "Hi. "}]}]}"""
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue("expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(1000L, result.getOrThrow().durationMs)
    }

    @Test
    fun spec20UntimedChapter_loadsWithoutTimings() = runBlocking {
        // IN1: an unrendered 2.0 chapter omits duration and sentence timings.
        val payload = """{"spec_version": "2.0", "chapter": 1, "title": "Ch",
            "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "narrator", "text": "Hi. "}]}]}"""
        val result = ChapterTextLoader.load(FakeStorage(mapOf("t" to payload)), "t")
        assertTrue("expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertEquals(null, result.getOrThrow().durationMs)
    }
}
