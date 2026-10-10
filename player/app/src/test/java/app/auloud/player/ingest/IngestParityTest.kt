package app.auloud.player.ingest

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN4: parity against Scribe's ingest expectations (JVM only).
 *
 * Compares chapters (count, titles, word counts) and blocks (order, type
 * and exact text) with the ingest-parity fixture JSON files on the real
 * golden EPUBs, including the EPUB 2 `epub2-minimal` fixture (NCX-only
 * TOC). Runs (narration/dialogue) are IN6's contract and are ignored
 * here; sentence boundaries are IN5. Span offsets are pinned in
 * [EpubStructureWalkerTest] on the Scribe mini-EPUB shapes.
 */
class IngestParityTest {

    private fun epubFile(fixture: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures/$fixture/source/book.epub")
        if (direct.isFile) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures/$fixture/source/book.epub")
            if (candidate.isFile) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    private fun parityFile(fixture: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures/ingest-parity/$fixture.json")
        if (direct.isFile) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures/ingest-parity/$fixture.json")
            if (candidate.isFile) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    private fun checkFixture(fixture: String) {
        val epub = epubFile(fixture)
        assertTrue("epub missing: ${epub.path}", epub.isFile)
        val parity = parityFile(fixture)
        assertTrue("parity missing: ${parity.path}", parity.isFile)

        val book = EpubContainerReader.read(epub).getOrThrow()
        val result = EpubStructurePipeline.ingest(epub, book)
        val root = Json.parseToJsonElement(parity.readText(Charsets.UTF_8)).jsonObject
        val expectedChapters = root["chapters"]!!.jsonArray

        assertEquals(
            "$fixture: chapter count",
            expectedChapters.size,
            result.chapters.size
        )
        for (pos in expectedChapters.indices) {
            val expected = expectedChapters[pos].jsonObject
            val actual = result.chapters[pos]
            val label = "$fixture chapter ${pos + 1}"
            assertEquals("$label: index", expected["index"]!!.jsonPrimitive.int, actual.index)
            assertEquals(
                "$label: title",
                expected["title"]!!.jsonPrimitive.content,
                actual.title
            )
            assertEquals(
                "$label: words",
                expected["words"]!!.jsonPrimitive.int,
                actual.wordCount
            )
            val expectedBlocks = expected["blocks"]!!.jsonArray
            assertEquals("$label: block count", expectedBlocks.size, actual.blocks.size)
            for (bpos in expectedBlocks.indices) {
                val expectedBlock = expectedBlocks[bpos].jsonObject
                val actualBlock = actual.blocks[bpos]
                val blabel = "$label block ${bpos + 1}"
                assertEquals(
                    "$blabel: id",
                    expectedBlock["id"]!!.jsonPrimitive.int,
                    bpos + 1
                )
                assertEquals(
                    "$blabel: type",
                    expectedBlock["type"]!!.jsonPrimitive.content,
                    actualBlock.kind
                )
                if (actualBlock.kind != BLOCK_BREAK) {
                    assertEquals(
                        "$blabel: text",
                        expectedBlock["text"]!!.jsonPrimitive.content,
                        actualBlock.text
                    )
                }
            }
        }
    }

    @Test
    fun scribeGolden_chaptersAndBlocksMatch() {
        checkFixture("scribe-golden")
    }

    @Test
    fun partialGolden_chaptersAndBlocksMatch() {
        checkFixture("partial-golden")
    }

    @Test
    fun multivoiceGolden_chaptersAndBlocksMatch() {
        checkFixture("multivoice-golden")
    }

    @Test
    fun epub2Minimal_chaptersAndBlocksMatch() {
        checkFixture("epub2-minimal")
    }
}
