package app.auloud.player.bundle

import android.net.Uri
import app.auloud.player.reader.SentenceIndex
import app.auloud.player.reader.layoutParagraph
import app.auloud.player.reader.sidAtOffset
import app.auloud.player.storage.BundleStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MV9 (JVM half): multi-voice golden contract (`spec/fixtures/multivoice-golden/`).
 *
 * Loads `text/ch001.json` through the REAL [ChapterTextLoader] and checks the
 * layout/spacing round trip on the split sentences. Lives in the `bundle`
 * package to match [ScribeGoldenContractTest]; reader checks use the real
 * [SentenceIndex], `layoutParagraph` and `sidAtOffset`.
 *
 * Storage choice: a map-backed [BundleStorage] fake (same shape as
 * [ChapterTextLoaderTest.FakeStorage]) so the test exercises the real loader
 * path, including its file-not-found and read branches, instead of calling
 * the in-memory `parse` shortcut.
 *
 * Spacing note: Scribe stores the split pair as dialogue `"We should leave,"`
 * (no trailing space) plus tag `" Alice said."` (leading space), so a raw
 * `"".join` reproduces the paragraph exactly with one space. `layoutParagraph`
 * skips its trimmed-source fallback space here because the next sentence
 * already starts with whitespace, so the display text matches the raw join
 * (single space). Both are pinned below: raw join for the round trip, exact
 * display text for the layout.
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class MultivoiceGoldenTest {

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
        // Same walk-up pattern as ScribeGoldenContractTest: works both from
        // the Gradle module dir (player/app) and from direct runs.
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
        val dir = fixtureDir("multivoice-golden")
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
        val dir = fixtureDir("multivoice-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        return File(dir, "text/ch001.json").readText(Charsets.UTF_8)
    }

    @Test
    fun multivoiceGolden_loadsWithExpectedShape() {
        val chapter = loadGolden()
        assertEquals("Chapter One", chapter.title)
        assertEquals(1550L, chapter.durationMs)
        val sentences = chapter.sentencesInOrder()
        assertEquals(3, sentences.size)
        // Sids run 1..3 consecutive, timings ordered, first start 0.
        assertEquals(listOf(1, 2, 3), sentences.map { it.sid })
        assertEquals(0L, sentences.first().startMs)
        // IN1: sentence timings are nullable (absent for unrendered
        // chapters); this rendered golden always carries them.
        sentences.forEach {
            assertTrue(
                "sid ${it.sid}: bad range",
                it.startMs != null && it.endMs != null && it.startMs < it.endMs
            )
        }
        for (i in 1 until sentences.size) {
            assertTrue(
                "sid ${sentences[i].sid} overlaps sid ${sentences[i - 1].sid}",
                sentences[i].startMs != null && sentences[i - 1].endMs != null &&
                    sentences[i].startMs!! >= sentences[i - 1].endMs!!
            )
        }
        // Multi-voice shape: narrator opens, Alice speaks, narrator tags.
        assertEquals(listOf("narrator", "Alice", "narrator"), sentences.map { it.speaker })
        assertEquals(setOf("narrator", "Alice"), sentences.map { it.speaker }.toSet())
    }

    @Test
    fun multivoiceGolden_exactTextsAndTimings() {
        val sentences = loadGolden().sentencesInOrder()
        assertEquals("The amber lamp glowed softly above the quiet river bend.", sentences[0].text)
        assertEquals("\"We should leave,\"", sentences[1].text)
        assertEquals(" Alice said.", sentences[2].text)
        assertEquals(0L to 100L, sentences[0].startMs to sentences[0].endMs)
        assertEquals(600L to 700L, sentences[1].startMs to sentences[1].endMs)
        assertEquals(950L to 1050L, sentences[2].startMs to sentences[2].endMs)
    }

    @Test
    fun multivoiceGolden_splitPairSpacingRoundTrip() {
        val chapter = loadGolden()
        val splitBlock = chapter.blocks.single { it.id == 2 }
        assertEquals(listOf(2, 3), splitBlock.sentences.map { it.sid })
        val dialogue = splitBlock.sentences[0].text
        val tag = splitBlock.sentences[1].text
        // Exact halves: no trailing space on the dialogue, leading space kept.
        assertEquals("\"We should leave,\"", dialogue)
        assertEquals(" Alice said.", tag)
        // Raw join reproduces the paragraph exactly (single space).
        val joined = splitBlock.sentences.joinToString("") { it.text }
        assertEquals("\"We should leave,\" Alice said.", joined)
    }

    @Test
    fun multivoiceGolden_sentenceIndexRapidBoundaries() {
        val chapter = loadGolden()
        val index = SentenceIndex(chapter.blocks)
        assertEquals(3, index.size)
        // Inside sentences and exact starts.
        assertEquals(1, index.currentSid(0))
        assertEquals(1, index.currentSid(50))
        assertEquals(2, index.currentSid(600))
        assertEquals(2, index.currentSid(650))
        assertEquals(3, index.currentSid(950))
        assertEquals(3, index.currentSid(1000))
        // Gap rule (ends exclusive): a position in a gap keeps the previous.
        assertEquals(1, index.currentSid(100))
        assertEquals(1, index.currentSid(300))
        assertEquals(1, index.currentSid(599))
        assertEquals(2, index.currentSid(700))
        assertEquals(2, index.currentSid(800))
        assertEquals(2, index.currentSid(949))
        // Tail gap and past the end stay on the last sentence.
        assertEquals(3, index.currentSid(1050))
        assertEquals(3, index.currentSid(1200))
        assertEquals(3, index.currentSid(1550))
        assertEquals(3, index.currentSid(999_999))
        // Before the first start highlights the first sentence.
        assertEquals(1, index.currentSid(-10))
        // Tables.
        assertEquals(0L, index.startMsOf(1))
        assertEquals(600L, index.startMsOf(2))
        assertEquals(950L, index.startMsOf(3))
        assertEquals(SentenceIndex.SentenceLocation(0, 0), index.locationOf(1))
        assertEquals(SentenceIndex.SentenceLocation(1, 0), index.locationOf(2))
        assertEquals(SentenceIndex.SentenceLocation(1, 1), index.locationOf(3))
    }

    @Test
    fun multivoiceGolden_splitBlockLayoutTiles() {
        val chapter = loadGolden()
        val splitBlock = chapter.blocks.single { it.id == 2 }
        val layout = layoutParagraph(splitBlock)
        // Display text: no fallback space (tag half already starts with
        // whitespace), so it matches the raw join with a single space.
        assertEquals("\"We should leave,\" Alice said.", layout.text)
        assertEquals(30, layout.text.length)
        // Ranges tile contiguously from 0 with no gaps or overlaps.
        assertEquals(2, layout.sentences.size)
        assertEquals(0, layout.sentences.first().start)
        assertEquals(layout.text.length, layout.sentences.last().end)
        for (i in 1 until layout.sentences.size) {
            assertEquals(layout.sentences[i].start, layout.sentences[i - 1].end)
        }
        // Exact ranges with no fallback space (dialogue 18, tag 12).
        assertEquals(2, layout.sentences[0].sid)
        assertEquals(0, layout.sentences[0].start)
        assertEquals(18, layout.sentences[0].end)
        assertEquals(3, layout.sentences[1].sid)
        assertEquals(18, layout.sentences[1].start)
        assertEquals(30, layout.sentences[1].end)
        // Narration block keeps its italic span rebased onto the paragraph.
        val narration = layoutParagraph(chapter.blocks.single { it.id == 1 })
        assertEquals(
            "The amber lamp glowed softly above the quiet river bend.",
            narration.text
        )
        assertEquals("softly", narration.text.substring(22, 28))
        assertEquals(listOf(22 until 28), narration.italics)
    }

    @Test
    fun multivoiceGolden_tapAtSplitBoundary() {
        val chapter = loadGolden()
        val layout = layoutParagraph(chapter.blocks.single { it.id == 2 })
        // Offset 17 is the last char of the dialogue half (closing quote).
        assertEquals(2, sidAtOffset(layout.sentences, 0))
        assertEquals(2, sidAtOffset(layout.sentences, 17))
        // Offset 18 is the first offset of the tag half (" Alice said.").
        assertEquals(3, sidAtOffset(layout.sentences, 18))
        assertEquals(3, sidAtOffset(layout.sentences, 25))
        // Last char of the tag (29) vs past the end (30+).
        assertEquals(3, sidAtOffset(layout.sentences, 29))
        assertEquals(3, sidAtOffset(layout.sentences, 30))
        assertEquals(3, sidAtOffset(layout.sentences, 999))
        assertEquals(2, sidAtOffset(layout.sentences, -1))
    }

    @Test
    fun multivoiceGolden_specShapeKeysNotRequired() {
        val raw = rawGolden()
        // The fixture is spec-shape: no Scribe-internal attribution keys.
        for (key in listOf("kind", "confidence", "quote", "split_pair")) {
            assertTrue("fixture should not carry $key", !raw.contains("\"$key\""))
        }
        // The loader ignores their absence: the same payload loads fine.
        val chapter = loadGolden()
        assertEquals(3, chapter.sentencesInOrder().size)
    }
}
