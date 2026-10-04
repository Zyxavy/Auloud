package app.auloud.player.bundle

import android.net.Uri
import app.auloud.player.reader.SentenceIndex
import app.auloud.player.storage.BundleStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP6 (JVM half): PDF text-path golden contract (`spec/fixtures/pdf-golden/`).
 *
 * Loads `text/ch001.json` through the REAL [ChapterTextLoader] and checks the
 * v1.1 shape: `blocks` + `pages` reads as text (NOT [ChapterTextPdfForm]),
 * sentence `page` provenance survives, and `pages` marks match first-sentence
 * starts. Mirrors [MultivoiceGoldenTest]'s storage-fake pattern so the test
 * exercises the real loader path, including file-not-found and read branches.
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class PdfGoldenTest {

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

    private fun loadGolden(): ChapterText {
        val dir = fixtureDir("pdf-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val raw = File(dir, "text/ch001.json").readText(Charsets.UTF_8)
        val storage = FakeStorage(mapOf("text/ch001.json" to raw))
        val result = runBlocking { ChapterTextLoader.load(storage, "text/ch001.json") }
        assertTrue(
            "expected success but got: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
        return result.getOrThrow()
    }

    private fun rawGolden(): String {
        val dir = fixtureDir("pdf-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        return File(dir, "text/ch001.json").readText(Charsets.UTF_8)
    }

    @Test
    fun pdfGolden_manifestIsPdfType() {
        val dir = fixtureDir("pdf-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        val manifestRaw = File(dir, "manifest.json").readText(Charsets.UTF_8)
        val parsed = BundleParser.parseText(manifestRaw)
        assertTrue("manifest must parse: ${parsed.exceptionOrNull()?.message}", parsed.isSuccess)
        val manifest = parsed.getOrThrow()
        assertEquals("pdf", manifest.type)
        assertEquals("1.1", manifest.specVersion)
        assertTrue(
            "source must be book.pdf: ${manifest.source?.file}",
            manifest.source?.file == "source/book.pdf"
        )
    }

    @Test
    fun pdfGolden_loadsBlocksPlusPagesAsText() {
        val chapter = loadGolden()
        assertEquals("Chapter 1", chapter.title)
        assertEquals(2150L, chapter.durationMs)
        assertEquals("1.1", chapter.specVersion)
        val sentences = chapter.sentencesInOrder()
        assertEquals(4, sentences.size)
        assertEquals(listOf(1, 2, 3, 4), sentences.map { it.sid })
        assertEquals(0L, sentences.first().startMs)
        // Multi-voice shape unchanged: narrator opens, Alice speaks, tags close.
        assertEquals(
            listOf("narrator", "Alice", "narrator", "narrator"),
            sentences.map { it.speaker }
        )
    }

    @Test
    fun pdfGolden_sentencePagesAndMarks() {
        val chapter = loadGolden()
        val sentences = chapter.sentencesInOrder()
        assertEquals(listOf(1, 1, 1, 2), sentences.map { it.page })
        val pages = chapter.pages
        assertTrue("expected pages marks", pages != null)
        assertEquals(2, pages!!.size)
        assertEquals(1, pages[0].page)
        assertEquals(0L, pages[0].startMs)
        assertEquals(2, pages[1].page)
        assertEquals(1550L, pages[1].startMs)
        // Each mark matches its page's first sentence start.
        assertEquals(sentences[0].startMs, pages[0].startMs)
        assertEquals(sentences[3].startMs, pages[1].startMs)
    }

    @Test
    fun pdfGolden_exactTextsAndTimings() {
        val sentences = loadGolden().sentencesInOrder()
        assertEquals("The amber lamp glowed softly above the quiet river bend.", sentences[0].text)
        assertEquals("\"We should leave,\"", sentences[1].text)
        assertEquals(" Alice said.", sentences[2].text)
        assertEquals("Morning came at last over the mossy hill.", sentences[3].text)
        assertEquals(0L to 100L, sentences[0].startMs to sentences[0].endMs)
        assertEquals(600L to 700L, sentences[1].startMs to sentences[1].endMs)
        assertEquals(950L to 1050L, sentences[2].startMs to sentences[2].endMs)
        assertEquals(1550L to 1650L, sentences[3].startMs to sentences[3].endMs)
    }

    @Test
    fun pdfGolden_pageAtLookup() {
        val chapter = loadGolden()
        assertEquals(1, chapter.pageAt(0)?.page)
        assertEquals(1, chapter.pageAt(100)?.page)
        assertEquals(1, chapter.pageAt(1549)?.page)
        assertEquals(2, chapter.pageAt(1550)?.page)
        assertEquals(2, chapter.pageAt(2000)?.page)
        assertEquals(2, chapter.pageAt(999_999)?.page)
    }

    @Test
    fun pdfGolden_sentenceIndexBoundaries() {
        val chapter = loadGolden()
        val index = SentenceIndex(chapter.blocks)
        assertEquals(4, index.size)
        assertEquals(1, index.currentSid(0))
        assertEquals(2, index.currentSid(600))
        assertEquals(3, index.currentSid(950))
        assertEquals(4, index.currentSid(1550))
        assertEquals(4, index.currentSid(2150))
    }

    @Test
    fun pdfGolden_specShapeKeysNotRequired() {
        val raw = rawGolden()
        for (key in listOf("kind", "confidence", "quote", "split_pair")) {
            assertTrue("fixture should not carry $key", !raw.contains("\"$key\""))
        }
        assertTrue("fixture must carry pages", raw.contains("\"pages\""))
        assertTrue("fixture must carry sentence page", raw.contains("\"page\""))
        val chapter = loadGolden()
        assertEquals(4, chapter.sentencesInOrder().size)
    }

    @Test
    fun pdfGolden_truncatedPagesJsonIsInvalidNotCrash() {
        val truncated = """{"spec_version": "1.1", "chapter": 1, "title": "T",
            "duration_ms": 1000, "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "narrator", "start_ms": 0,
            "end_ms": 100, "text": "Hi.", "page": 1}]}],
            "pages": [{"page": 1, "start_ms": 0}, {"page":"""
        val result = ChapterTextLoader.parse("text/ch001.json", truncated)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("wrong type: $error", error is ChapterTextInvalid)
        assertTrue("must not be PdfForm: $error", error !is ChapterTextPdfForm)
    }

    @Test
    fun pdfGolden_wrongTypePagesJsonIsInvalidNotCrash() {
        val wrongType = """{"spec_version": "1.1", "chapter": 1, "title": "T",
            "duration_ms": 1000, "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "narrator", "start_ms": 0,
            "end_ms": 100, "text": "Hi.", "page": 1}]}],
            "pages": "not-a-list"}"""
        val result = ChapterTextLoader.parse("text/ch001.json", wrongType)
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("wrong type: $error", error is ChapterTextInvalid)
        assertTrue("must not be PdfForm: $error", error !is ChapterTextPdfForm)
    }

    @Test
    fun pdfGolden_wrongTypeSentencePageIsInvalidNotCrash() {
        val wrongPage = """{"spec_version": "1.1", "chapter": 1, "title": "T",
            "duration_ms": 1000, "blocks": [{"id": 1, "type": "para",
            "sentences": [{"sid": 1, "speaker": "narrator", "start_ms": 0,
            "end_ms": 100, "text": "Hi.", "page": "first"}]}]}"""
        val result = ChapterTextLoader.parse("text/ch001.json", wrongPage)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ChapterTextInvalid)
    }

    @Test
    fun pdfGolden_purePagesWithoutBlocksStaysPdfForm() {
        val pure = """{"spec_version": "1.0", "chapter": 1, "title": "P",
            "duration_ms": 1000, "pages": [{"page": 1, "start_ms": 0}]}"""
        val result = ChapterTextLoader.parse("text/ch001.json", pure)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ChapterTextPdfForm)
    }
}
