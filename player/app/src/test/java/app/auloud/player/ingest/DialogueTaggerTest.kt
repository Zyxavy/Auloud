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
 * IN6: dialogue detection over [DialogueTagger] (JVM only).
 *
 * The shared vectors (`spec/fixtures/dialogue-cases.json`, 23 cases)
 * replay through the real splitter exactly like Scribe's
 * `test_vector_runs_match_and_round_trip`: quote-state carry threads
 * paragraphs, adjacent same-kind sentences merge into runs by plain
 * concatenation, chapter scope adds the chapter-end flip, and every
 * paragraph's run texts join back to its input exactly (rule 10.1). The
 * remaining tests port Scribe's `test_dialogue.py` mechanics and the
 * `test_mv7.py` split-pair group to the Kotlin layer (quote keys,
 * continued flags, span rebasing, sid order, warnings, split pairs).
 *
 * No tablet claimed: file access is plain `java.io.File` under the unit
 * test working directory, with the same walk-up pattern as
 * [IngestParityTest].
 */
class DialogueTaggerTest {

    private data class VectorRun(val paragraph: Int, val kind: String, val text: String)

    private data class VectorCase(
        val id: String,
        val scope: String,
        val input: List<String>,
        val expected: List<VectorRun>
    )

    private fun fixturesDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures")
        if (direct.isDirectory) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures")
            if (candidate.isDirectory) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    private fun loadVectors(): List<VectorCase> {
        val path = File(fixturesDir(), "dialogue-cases.json")
        assertTrue("vectors missing: ${path.path}", path.isFile)
        val root = Json.parseToJsonElement(path.readText(Charsets.UTF_8)).jsonObject
        return root["cases"]!!.jsonArray.map { entry ->
            val obj = entry.jsonObject
            VectorCase(
                id = obj["id"]!!.jsonPrimitive.content,
                scope = obj["scope"]!!.jsonPrimitive.content,
                input = obj["input"]!!.jsonArray.map { it.jsonPrimitive.content },
                expected = obj["expected"]!!.jsonArray.map { run ->
                    val runObj = run.jsonObject
                    VectorRun(
                        paragraph = runObj["paragraph"]!!.jsonPrimitive.int,
                        kind = runObj["kind"]!!.jsonPrimitive.content,
                        text = runObj["text"]!!.jsonPrimitive.content
                    )
                }
            )
        }
    }

    private fun mergeRuns(kindsAndTexts: List<Pair<String, String>>): List<Pair<String, String>> {
        val runs = ArrayList<Pair<String, String>>()
        for ((kind, text) in kindsAndTexts) {
            if (runs.isNotEmpty() && runs.last().first == kind) {
                runs[runs.size - 1] = Pair(kind, runs.last().second + text)
            } else {
                runs.add(Pair(kind, text))
            }
        }
        return runs
    }

    /** Paragraph scope: threaded carry, quote numbers restart per paragraph. */
    private fun runsForParagraphs(
        paragraphs: List<String>,
        warnings: MutableList<String>? = null
    ): List<VectorRun> {
        val runs = ArrayList<VectorRun>()
        var inQuote = false
        for ((index, paragraph) in paragraphs.withIndex()) {
            val result = DialogueTagger.splitParagraph(
                paragraph,
                emptyList(),
                chapter = 1,
                block = index + 1,
                startQuote = 1,
                inQuote = inQuote,
                warnings = warnings
            )
            inQuote = result.outQuote
            for ((kind, text) in mergeRuns(result.sentences.map { Pair(it.kind, it.text) })) {
                runs.add(VectorRun(index, kind, text))
            }
        }
        return runs
    }

    /** Chapter scope: full split plus the chapter-end flip. */
    private fun runsForChapter(
        paragraphs: List<String>,
        warnings: MutableList<String>? = null
    ): List<VectorRun> {
        val chapter = IngestChapter(
            index = 1,
            title = "Vector",
            href = "vector.xhtml",
            blocks = paragraphs.map { IngestBlock(kind = BLOCK_PARA, text = it) },
            wordCount = 0
        )
        val split = SentenceSplitter.splitChapter(chapter)
        val result = DialogueTagger.tagChapter(chapter, split, warnings)
        val runs = ArrayList<VectorRun>()
        for ((pos, block) in result.blocks.withIndex()) {
            for ((kind, text) in mergeRuns(block.sentences.map { Pair(it.kind, it.text) })) {
                runs.add(VectorRun(pos, kind, text))
            }
        }
        return runs
    }

    private fun actualRuns(case: VectorCase, warnings: MutableList<String>? = null): List<VectorRun> {
        return if (case.scope == "chapter") {
            runsForChapter(case.input, warnings)
        } else {
            runsForParagraphs(case.input, warnings)
        }
    }

    private fun checkRoundTrip(case: VectorCase, runs: List<VectorRun>) {
        for ((index, paragraph) in case.input.withIndex()) {
            val joined = runs.filter { it.paragraph == index }.joinToString("") { it.text }
            assertEquals("${case.id} paragraph $index round trip", paragraph, joined)
        }
    }

    // Shared-vector harness: every case replays exactly, and round-trips.

    @Test
    fun allVectors_matchExpectedRunsAndRoundTrip() {
        val cases = loadVectors()
        assertEquals("vector count pins the IN2 contract", 23, cases.size)
        for (case in cases) {
            val actual = actualRuns(case)
            assertEquals(
                "vector drift: ${case.id}",
                case.expected.map { Triple(it.paragraph, it.kind, it.text) },
                actual.map { Triple(it.paragraph, it.kind, it.text) }
            )
            checkRoundTrip(case, actual)
        }
    }

    @Test
    fun mixedStraight_anchorRunsAndHalves() {
        val warnings = ArrayList<String>()
        val result = DialogueTagger.splitParagraph(
            "\"We should leave,\" she said.",
            emptyList(),
            warnings = warnings
        )
        assertEquals(2, result.sentences.size)
        val dialogue = result.sentences[0]
        val tag = result.sentences[1]
        assertEquals(KIND_DIALOGUE, dialogue.kind)
        assertEquals(SPEAKER_DIALOGUE, dialogue.speaker)
        assertEquals("\"We should leave,\"", dialogue.text)
        assertEquals(KIND_NARRATION, tag.kind)
        assertEquals(SPEAKER_NARRATOR, tag.speaker)
        assertEquals(" she said.", tag.text)
        assertTrue("no warnings on clean mixed: $warnings", warnings.isEmpty())
    }

    // Scribe-side mechanics (quote keys, flags, spans, sids, warnings).

    @Test
    fun multiparagraphContinuation_keysAndFlags() {
        val first = DialogueTagger.splitParagraph(
            "“We march on through the night with heavy hearts.",
            emptyList(),
            chapter = 1,
            block = 1,
            inQuote = false
        )
        assertTrue("first paragraph ends open", first.outQuote)
        val second = DialogueTagger.splitParagraph(
            "“and on until the morning comes.” He sighed.",
            emptyList(),
            chapter = 1,
            block = 2,
            inQuote = first.outQuote
        )
        assertEquals(listOf(1), first.quotes.map { it.quote })
        assertEquals(false, first.quotes.single().continued)
        assertEquals(listOf(1), second.quotes.map { it.quote })
        assertEquals(true, second.quotes.single().continued)
        assertEquals(
            listOf(Triple(1, 1, 1), Triple(1, 2, 1)),
            first.quotes.map { it.key } + second.quotes.map { Triple(1, 2, it.quote) }
        )
        assertEquals(KIND_DIALOGUE, second.sentences.first().kind)
        assertEquals(true, second.sentences.first().continued)
    }

    @Test
    fun multisentenceDoublesQuote_sharesOneQuoteNumber() {
        val text = "“I thought so! That’s the worst of all! Why, a stupid thing " +
            "like this might spoil the whole plan. Yes, my hat is too noticeable. " +
            "It looks absurd.” He sighed."
        val result = DialogueTagger.splitParagraph(text, emptyList(), chapter = 1, block = 5)
        assertEquals(1, result.quotes.size)
        val dialogue = result.sentences.filter { it.kind == KIND_DIALOGUE }
        assertTrue("several sids, got ${dialogue.size}", dialogue.size > 1)
        assertEquals(setOf(1), dialogue.map { it.quote }.toSet())
    }

    @Test
    fun multisentenceSinglesQuote_staysOneRun() {
        val result = DialogueTagger.splitParagraph(
            "'One. Two. Three. Four. Five.' They all left.",
            emptyList()
        )
        assertEquals(1, result.quotes.size)
        val runs = mergeRuns(result.sentences.map { Pair(it.kind, it.text) })
        assertEquals(
            listOf(
                Pair(KIND_DIALOGUE, "'One. Two. Three. Four. Five.'"),
                Pair(KIND_NARRATION, " They all left.")
            ),
            runs
        )
    }

    @Test
    fun goldShapedChapter_quoteKeys() {
        val chapter = IngestChapter(
            index = 1,
            title = "Gold",
            href = "gold.xhtml",
            blocks = listOf(
                IngestBlock(kind = BLOCK_PARA, text = "“I want to attempt a thing like that,” he thought. “Hm, yes, indeed.”"),
                IngestBlock(kind = BLOCK_PARA, text = "He shouted as he drove past: “Hey there, German hatter” loudly."),
                IngestBlock(kind = BLOCK_PARA, text = "“I knew it,” he muttered. “I thought so! Ruin!”"),
                IngestBlock(
                    kind = BLOCK_PARA,
                    text = "He eyed this “hideous” dream before the “rehearsal” calmly. He went on."
                )
            ),
            wordCount = 0
        )
        val result = DialogueTagger.tagChapter(chapter, SentenceSplitter.splitChapter(chapter))
        assertEquals(
            listOf(
                Triple(1, 1, 1),
                Triple(1, 1, 2),
                Triple(1, 2, 1),
                Triple(1, 3, 1),
                Triple(1, 3, 2)
            ),
            result.quotes.map { it.key }
        )
        val scareBlock = result.blocks[3]
        assertTrue("scare block has no dialogue", scareBlock.sentences.none { it.kind == KIND_DIALOGUE })
        assertTrue(scareBlock.sentences.all { it.kind == KIND_NARRATION })
    }

    @Test
    fun inchMarkStray_logsUnbalancedAndNarrates() {
        val warnings = ArrayList<String>()
        val result = DialogueTagger.splitParagraph(
            "He bought a 5\" nail. It hurt.",
            emptyList(),
            warnings = warnings
        )
        assertTrue(result.quotes.isEmpty())
        assertTrue(result.sentences.all { it.kind == KIND_NARRATION })
        assertTrue("warnings mention unbalanced: $warnings", warnings.any { "unbalanced" in it })
    }

    @Test
    fun intactPairWithStray_honorsPairAndLogsStray() {
        val warnings = ArrayList<String>()
        val result = DialogueTagger.splitParagraph(
            "\"Hi. Bye.\" She waved. He bought a 5\" nail. It hurt.",
            emptyList(),
            warnings = warnings
        )
        assertEquals(1, result.quotes.size)
        assertEquals(KIND_DIALOGUE, result.sentences.first().kind)
        assertTrue("warnings mention stray: $warnings", warnings.any { "stray" in it })
    }

    @Test
    fun unclosedSingleParagraph_flipsToNarration() {
        val warnings = ArrayList<String>()
        val runs = runsForChapter(
            listOf("\"Unclosed quote here. It keeps going."),
            warnings
        )
        assertEquals(1, runs.size)
        assertEquals(KIND_NARRATION, runs.single().kind)
        assertTrue("warnings mention unclosed: $warnings", warnings.any { "unclosed" in it })
    }

    @Test
    fun unclosedMultiparagraphChain_flipsWhollyToNarration() {
        val chapter = IngestChapter(
            index = 1,
            title = "Chain",
            href = "chain.xhtml",
            blocks = listOf(
                IngestBlock(kind = BLOCK_PARA, text = "“We march on through the night."),
                IngestBlock(kind = BLOCK_PARA, text = "“and on, never stopping.")
            ),
            wordCount = 0
        )
        val warnings = ArrayList<String>()
        val result = DialogueTagger.tagChapter(chapter, SentenceSplitter.splitChapter(chapter), warnings)
        assertTrue(result.quotes.isEmpty())
        assertTrue(result.blocks.flatMap { it.sentences }.all { it.kind == KIND_NARRATION })
        assertTrue(
            "flipped speakers reset too",
            result.blocks.flatMap { it.sentences }.all { it.speaker == SPEAKER_NARRATOR }
        )
        assertTrue("warnings mention unclosed: $warnings", warnings.any { "unclosed" in it })
    }

    // Scare-quote helper units (fragment level).

    @Test
    fun scareRule_shortPlainFragments() {
        assertTrue(DialogueTagger.isScareQuote("hideous"))
        assertTrue(DialogueTagger.isScareQuote("rehearsal"))
        assertTrue(DialogueTagger.isScareQuote("the plan"))
    }

    @Test
    fun scareRule_keepsRealDialogue() {
        assertTrue(!DialogueTagger.isScareQuote("Hey there, German hatter"))
        assertTrue(!DialogueTagger.isScareQuote("I knew it,"))
        assertTrue(!DialogueTagger.isScareQuote("Hi,"))
        assertTrue(!DialogueTagger.isScareQuote("Stop."))
        assertTrue(!DialogueTagger.isScareQuote("Run!"))
        assertTrue(!DialogueTagger.isScareQuote("Hm… yes, indeed"))
    }

    // Sids consecutive, block ids preserved (headings count, breaks hold none).

    @Test
    fun chapterSids_consecutiveAndBlockIdsPreserved() {
        val chapter = IngestChapter(
            index = 1,
            title = "Test",
            href = "test.xhtml",
            blocks = listOf(
                IngestBlock(kind = BLOCK_HEADING, text = "Chapter One", level = 1),
                IngestBlock(kind = BLOCK_PARA, text = "First. Second."),
                IngestBlock(kind = BLOCK_PARA, text = "\"Quoted words,\" she said. Then more."),
                IngestBlock(kind = BLOCK_BREAK),
                IngestBlock(kind = BLOCK_PARA, text = "Last. Words.")
            ),
            wordCount = 0
        )
        val result = DialogueTagger.tagChapter(chapter, SentenceSplitter.splitChapter(chapter))
        assertEquals(listOf(1, 2, 3, 4, 5), result.blocks.map { it.block })
        assertEquals(
            listOf(BLOCK_HEADING, BLOCK_PARA, BLOCK_PARA, BLOCK_BREAK, BLOCK_PARA),
            result.blocks.map { it.kind }
        )
        val sids = result.blocks.flatMap { it.sentences }.map { it.sid }
        assertEquals((1..sids.size).toList(), sids)
        assertTrue(result.blocks[3].sentences.isEmpty())
        assertEquals(listOf("Chapter One"), result.blocks[0].sentences.map { it.text })
        assertEquals(KIND_NARRATION, result.blocks[0].sentences.single().kind)
    }

    @Test
    fun blankHeading_holdsNoSentences() {
        val chapter = IngestChapter(
            index = 1,
            title = "Test",
            href = "test.xhtml",
            blocks = listOf(IngestBlock(kind = BLOCK_HEADING, text = "", level = 1)),
            wordCount = 0
        )
        val result = DialogueTagger.tagChapter(chapter, SentenceSplitter.splitChapter(chapter))
        assertTrue(result.blocks.single().sentences.isEmpty())
    }

    // Span rebasing across quote splits.

    @Test
    fun spans_rebasedAcrossQuoteSplit() {
        val text = "\"We should leave,\" she said."
        val span = IngestSpan(start = 1, end = 15, style = SPAN_ITALIC)
        val result = DialogueTagger.splitParagraph(text, listOf(span), chapter = 1, block = 1)
        assertEquals(KIND_DIALOGUE, result.sentences[0].kind)
        assertEquals(listOf(IngestSpan(start = 1, end = 15, style = SPAN_ITALIC)), result.sentences[0].spans)
        assertTrue(result.sentences[1].spans.isEmpty())
    }

    // Split pairs: same-sentence tags link, new sentences do not.

    @Test
    fun splitPair_tagAfterQuote() {
        val result = DialogueTagger.splitParagraph("\"We should leave,\" she said.")
        assertEquals(2, result.sentences.size)
        val (dialogue, tag) = result.sentences
        assertEquals(KIND_DIALOGUE, dialogue.kind)
        assertEquals(KIND_NARRATION, tag.kind)
        assertTrue(dialogue.splitPair != null)
        assertEquals(dialogue.splitPair, tag.splitPair)
    }

    @Test
    fun splitPair_newSentenceHasNoPair() {
        val result = DialogueTagger.splitParagraph("\"Hello.\" She smiled.")
        assertEquals(2, result.sentences.size)
        assertTrue(result.sentences.all { it.splitPair == null })
    }

    @Test
    fun splitPair_tagBeforeQuote() {
        val result = DialogueTagger.splitParagraph("She said, \"Hi.\"")
        assertEquals(2, result.sentences.size)
        val (tag, dialogue) = result.sentences
        assertEquals(KIND_NARRATION, tag.kind)
        assertEquals(KIND_DIALOGUE, dialogue.kind)
        assertTrue(tag.splitPair != null)
        assertEquals(tag.splitPair, dialogue.splitPair)
    }

    @Test
    fun splitPair_twoQuotesOneSentenceShareOneId() {
        val result = DialogueTagger.splitParagraph("\"Hi,\" she said, \"bye.\"")
        assertEquals(3, result.sentences.size)
        val pairs = result.sentences.map { it.splitPair }.toSet()
        assertEquals(1, pairs.size)
        assertTrue(null !in pairs)
    }

    @Test
    fun splitPair_tagAfterExclamation() {
        val result = DialogueTagger.splitParagraph("\"Run!\" he shouted.")
        assertEquals(2, result.sentences.size)
        val (first, second) = result.sentences
        assertTrue(first.splitPair != null)
        assertEquals(first.splitPair, second.splitPair)
    }

    // Apostrophes and singles-only paragraphs.

    @Test
    fun straightApostrophes_neverBoundaries() {
        val result = DialogueTagger.splitParagraph("The man's hat is here. She left.")
        assertTrue(result.quotes.isEmpty())
        assertTrue(result.sentences.all { it.kind == KIND_NARRATION })
        assertEquals("The man's hat is here. She left.", result.sentences.joinToString("") { it.text })
    }

    @Test
    fun curlyApostrophes_neverBoundaries() {
        val result = DialogueTagger.splitParagraph("The man’s hat is here. She left.")
        assertTrue(result.quotes.isEmpty())
        assertTrue(result.sentences.all { it.kind == KIND_NARRATION })
    }

    @Test
    fun singlesOnlyParagraph_formsDialogue() {
        val result = DialogueTagger.splitParagraph("'Hello there.' He left.")
        assertEquals(1, result.quotes.size)
        assertEquals(KIND_DIALOGUE, result.sentences.first().kind)
        assertEquals("'Hello there.'", result.sentences.first().text)
    }

    @Test
    fun nestedSinglesInDoubles_belongToOuterQuote() {
        val result = DialogueTagger.splitParagraph("He said \"hi 'there' loudly.\" He nodded.")
        assertEquals(1, result.quotes.size)
        val runs = mergeRuns(result.sentences.map { Pair(it.kind, it.text) })
        assertEquals(3, runs.size)
        assertEquals(KIND_DIALOGUE, runs[1].first)
        assertEquals("\"hi 'there' loudly.\"", runs[1].second)
    }
}
