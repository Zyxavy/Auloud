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
 * IN6: run-level parity with Scribe on the golden EPUBs (JVM only).
 *
 * Runs the full on-device chain (container reader plus structure
 * pipeline plus [SentenceSplitter] plus [DialogueTagger]) over every
 * covered parity fixture and compares narration/dialogue runs per block
 * (kind plus text, adjacent same-kind sentences merged) with the
 * per-fixture parity JSON files under `spec/fixtures/ingest-parity/`. Run agreement must be exact;
 * sentence-boundary agreement stays IN5's percentage report, never
 * pass/fail. The `multivoice-golden` anchor (`"We should leave,"` as
 * dialogue plus ` Alice said.` as narration) is pinned explicitly.
 *
 * No tablet claimed: plain `java.io.File` reads under the unit test
 * working directory, with the same walk-up pattern as [IngestParityTest].
 */
class DialogueParityTest {

    private data class ExpectedRun(val kind: String, val text: String)

    private data class ExpectedBlock(
        val id: Int,
        val type: String,
        val text: String,
        val runs: List<ExpectedRun>?
    )

    private fun repoRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures")
        if (direct.isDirectory) return direct.parentFile?.parentFile ?: userDir
        var cur: File? = userDir
        while (cur != null) {
            if (File(cur, "spec/fixtures").isDirectory) return cur
            cur = cur.parentFile
        }
        return userDir
    }

    private fun coveredFixtures(): List<String> {
        val sources = File(repoRoot(), "spec/fixtures/ingest-parity/sources.json")
        assertTrue("sources missing: ${sources.path}", sources.isFile)
        val root = Json.parseToJsonElement(sources.readText(Charsets.UTF_8)).jsonObject
        return root["sources"]!!.jsonArray.mapNotNull { entry ->
            val obj = entry.jsonObject
            val status = obj["status"]!!.jsonPrimitive.content
            if (status == "covered") obj["fixture"]!!.jsonPrimitive.content else null
        }
    }

    private fun expectedBlocks(fixture: String): List<List<ExpectedBlock>> {
        val path = File(repoRoot(), "spec/fixtures/ingest-parity/$fixture.json")
        assertTrue("parity missing: ${path.path}", path.isFile)
        val root = Json.parseToJsonElement(path.readText(Charsets.UTF_8)).jsonObject
        return root["chapters"]!!.jsonArray.map { chapter ->
            chapter.jsonObject["blocks"]!!.jsonArray.map { block ->
                val obj = block.jsonObject
                ExpectedBlock(
                    id = obj["id"]!!.jsonPrimitive.int,
                    type = obj["type"]!!.jsonPrimitive.content,
                    text = obj["text"]?.jsonPrimitive?.content ?: "",
                    runs = obj["runs"]?.jsonArray?.map { run ->
                        ExpectedRun(
                            kind = run.jsonObject["kind"]!!.jsonPrimitive.content,
                            text = run.jsonObject["text"]!!.jsonPrimitive.content
                        )
                    }
                )
            }
        }
    }

    private fun mergeRuns(sentences: List<TaggedSentence>): List<ExpectedRun> {
        val runs = ArrayList<ExpectedRun>()
        for (sent in sentences) {
            if (runs.isNotEmpty() && runs.last().kind == sent.kind) {
                runs[runs.size - 1] = ExpectedRun(sent.kind, runs.last().text + sent.text)
            } else {
                runs.add(ExpectedRun(sent.kind, sent.text))
            }
        }
        return runs
    }

    private fun actualRuns(fixture: String): List<List<List<ExpectedRun>>> {
        val epub = File(repoRoot(), "spec/fixtures/$fixture/source/book.epub")
        assertTrue("epub missing: ${epub.path}", epub.isFile)
        val book = EpubContainerReader.read(epub).getOrThrow()
        val structure = EpubStructurePipeline.ingest(epub, book)
        return structure.chapters.map { chapter ->
            val split = SentenceSplitter.splitChapter(chapter)
            val tagged = DialogueTagger.tagChapter(chapter, split)
            tagged.blocks.map { block -> mergeRuns(block.sentences) }
        }
    }

    private fun checkFixture(fixture: String) {
        val expected = expectedBlocks(fixture)
        val actual = actualRuns(fixture)
        assertEquals("$fixture: chapter count", expected.size, actual.size)
        for (pos in expected.indices) {
            val expectedBlocks = expected[pos]
            val actualBlocks = actual[pos]
            assertEquals("$fixture chapter ${pos + 1}: block count", expectedBlocks.size, actualBlocks.size)
            for (bpos in expectedBlocks.indices) {
                val expectedBlock = expectedBlocks[bpos]
                val label = "$fixture chapter ${pos + 1} block ${expectedBlock.id} (${expectedBlock.type})"
                val expectedRuns = expectedBlock.runs
                if (expectedRuns == null) {
                    assertEquals(
                        "$label: sentences join to block text",
                        expectedBlock.text,
                        actualBlocks[bpos].joinToString("") { it.text }
                    )
                } else {
                    assertEquals(
                        "$label: runs",
                        expectedRuns.map { Pair(it.kind, it.text) },
                        actualBlocks[bpos].map { Pair(it.kind, it.text) }
                    )
                    assertEquals(
                        "$label: run texts join to block text",
                        expectedBlock.text,
                        actualBlocks[bpos].joinToString("") { it.text }
                    )
                }
            }
        }
    }

    @Test
    fun coveredFixtures_matchExpectedRuns() {
        val fixtures = coveredFixtures()
        assertTrue("no covered fixtures, want at least multivoice-golden", fixtures.isNotEmpty())
        assertTrue("multivoice-golden must be covered: $fixtures", "multivoice-golden" in fixtures)
        for (fixture in fixtures) checkFixture(fixture)
    }

    @Test
    fun multivoiceGolden_anchorRunPinned() {
        val chapters = actualRuns("multivoice-golden")
        assertEquals(1, chapters.size)
        assertEquals(2, chapters.single().size)
        assertEquals(
            listOf(ExpectedRun(KIND_NARRATION, "The amber lamp glowed softly above the quiet river bend.")),
            chapters.single()[0]
        )
        assertEquals(
            listOf(
                ExpectedRun(KIND_DIALOGUE, "\"We should leave,\""),
                ExpectedRun(KIND_NARRATION, " Alice said.")
            ),
            chapters.single()[1]
        )
    }
}
