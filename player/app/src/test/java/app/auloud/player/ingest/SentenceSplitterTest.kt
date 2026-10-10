package app.auloud.player.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN5: sentence splitting over [SentenceSplitter] (JVM only).
 *
 * Mirrors Scribe's `tests/test_sentences.py` case for case (synthetic
 * text only): abbreviation/initial/ellipsis post-fixes, short-quote
 * attachment vs long-quote splitting, exact round trip on tricky
 * spacing, heading-to-single-sentence, chapter sid runs, and span
 * rebasing (crossing, nested, inside, across a quote split, edge trim).
 * No kind/speaker fields exist on [IngestSentence] by design (IN6 owns
 * dialogue tagging keyed by sid).
 */
class SentenceSplitterTest {

    private fun texts(sentences: List<IngestSentence>): List<String> =
        sentences.map { it.text }

    private fun roundTrip(text: String, spans: List<IngestSpan> = emptyList()): List<IngestSentence> {
        val out = SentenceSplitter.splitParagraph(text, spans)
        assertEquals("round trip: ${text.take(60)}", text, out.joinToString("") { it.text })
        return out
    }

    // Abbreviations and initials (rules 8.1-8.3).

    @Test
    fun mr_notSplit() {
        assertEquals(
            listOf("Mr. Smith went home. ", "He slept."),
            texts(roundTrip("Mr. Smith went home. He slept."))
        )
    }

    @Test
    fun mrsDrSt_notSplit() {
        val out = roundTrip("Mrs. Jones and Dr. Brown met St. Mary. They talked.")
        assertEquals(2, out.size)
        assertTrue("St. Mary. in first: ${out[0].text}", "St. Mary." in out[0].text)
        assertEquals("They talked.", out[1].text)
    }

    @Test
    fun initials_keptTogether() {
        val out = roundTrip("J. K. Rowling arrived early. She smiled.")
        assertEquals(2, out.size)
        assertTrue("initials in first: ${out[0].text}", "J. K. Rowling" in out[0].text)
        assertEquals("She smiled.", out[1].text)
    }

    @Test
    fun quotedAbbrev_notSplit() {
        assertEquals(
            listOf("\"Dr. Smith is here.\" ", "He smiled."),
            texts(roundTrip("\"Dr. Smith is here.\" He smiled."))
        )
    }

    @Test
    fun quotedInitial_notSplit() {
        val out = roundTrip("\"A. B. Smith came.\" She smiled.")
        assertEquals(2, out.size)
        assertTrue("initial in first: ${out[0].text}", "\"A. B. Smith" in out[0].text)
    }

    @Test
    fun curlyQuotedAbbrevAndInitial() {
        assertEquals(
            listOf("\u201CMr. Smith went home.\u201D ", "He slept."),
            texts(roundTrip("\u201CMr. Smith went home.\u201D He slept."))
        )
        val out = roundTrip("\u201CJ. K. Rowling arrived.\u201D She smiled.")
        assertEquals(2, out.size)
        assertTrue("initials kept: ${out[0].text}", "J. K. Rowling" in out[0].text)
    }

    @Test
    fun egIeVs_notSplit() {
        val eg = roundTrip("He likes fruit, e.g. apples. She left.")
        assertEquals(2, eg.size)
        assertTrue("e.g. kept: ${eg[0].text}", "e.g. apples." in eg[0].text)
        val ie = roundTrip("He left, i.e. he fled. She stayed.")
        assertEquals(2, ie.size)
        assertTrue("i.e. kept: ${ie[0].text}", "i.e. he fled." in ie[0].text)
        val vs = roundTrip("It was Ali vs. Liston. He won.")
        assertEquals(2, vs.size)
        assertTrue("vs. kept: ${vs[0].text}", "vs. Liston." in vs[0].text)
    }

    @Test
    fun sentenceFinalNo_staysSplit() {
        assertEquals(
            listOf("The answer was No. ", "He left anyway."),
            texts(roundTrip("The answer was No. He left anyway."))
        )
    }

    @Test
    fun innerNoNumber_staysMerged() {
        val out = roundTrip("He paid No. 12. She watched.")
        assertEquals(2, out.size)
        assertTrue("number kept: ${out[0].text}", "No. 12." in out[0].text)
    }

    @Test
    fun etc_canEndSentence() {
        assertEquals(
            listOf("He bought apples, pears, etc. ", "She watched."),
            texts(roundTrip("He bought apples, pears, etc. She watched."))
        )
    }

    // Ellipses (rule 8.4).

    @Test
    fun ellipsis_mergesWithNext() {
        assertEquals(
            listOf("She trailed off... Then she left."),
            texts(roundTrip("She trailed off... Then she left."))
        )
    }

    @Test
    fun ellipsisChain_staysWhole() {
        assertEquals(
            listOf("He left... She stayed... They met."),
            texts(roundTrip("He left... She stayed... They met."))
        )
    }

    @Test
    fun unicodeEllipsis_merges() {
        val out = roundTrip("He waited\u2026 and waited. Then he left.")
        assertEquals(2, out.size)
        assertTrue("ellipsis in first: ${out[0].text}", "\u2026" in out[0].text)
        assertEquals("Then he left.", out[1].text)
    }

    @Test
    fun ellipsisQuoteContinuation_merges() {
        val text = "\"Wait...\" she whispered... and left."
        assertEquals(listOf(text), texts(roundTrip(text)))
    }

    @Test
    fun ellipsisQuoteClosed_staysSplit() {
        assertEquals(
            listOf("He said \"Wait...\" ", "Then he left."),
            texts(roundTrip("He said \"Wait...\" Then he left."))
        )
    }

    @Test
    fun curlyEllipsisQuoteContinuation() {
        assertEquals(
            listOf("\u201CWait\u2026\u201D she sighed. ", "He left."),
            texts(roundTrip("\u201CWait\u2026\u201D she sighed. He left."))
        )
    }

    // Quotations (rule 8.5).

    @Test
    fun shortQuote_staysAttached() {
        assertEquals(
            listOf("He said, \"I am tired. Let us rest.\" ", "Then they slept."),
            texts(roundTrip("He said, \"I am tired. Let us rest.\" Then they slept."))
        )
    }

    @Test
    fun threeSentenceQuote_staysAttached() {
        assertEquals(
            listOf("\"One. Two. Three.\" ", "They left."),
            texts(roundTrip("\"One. Two. Three.\" They left."))
        )
    }

    @Test
    fun longQuote_splitsInside() {
        val text = "\"One. Two. Three. Four.\" They left."
        assertEquals(
            listOf("\"One. ", "Two. ", "Three. ", "Four.\" ", "They left."),
            texts(roundTrip(text))
        )
    }

    @Test
    fun unbalancedQuote_splitsAsNarration() {
        val text = "\"Unclosed quote here. It keeps going. And going."
        val out = roundTrip(text)
        assertEquals(3, out.size)
    }

    @Test
    fun partiallyUnbalanced_keepsIntactPair() {
        val text = "\"Hi. Bye.\" She waved. He bought a 5\" nail. It hurt."
        assertEquals(
            listOf("\"Hi. Bye.\" ", "She waved. ", "He bought a 5\" nail. ", "It hurt."),
            texts(roundTrip(text))
        )
    }

    @Test
    fun curlyQuoteShort_attached() {
        assertEquals(
            listOf("\u201CI am tired. Let us rest.\u201D ", "Then they slept."),
            texts(roundTrip("\u201CI am tired. Let us rest.\u201D Then they slept."))
        )
    }

    @Test
    fun curlyQuoteLong_splits() {
        val text = "\u201COne. Two. Three. Four.\u201D They left."
        assertEquals(
            listOf("\u201COne. ", "Two. ", "Three. ", "Four.\u201D ", "They left."),
            texts(roundTrip(text))
        )
    }

    @Test
    fun nestedCurly_collapsesToOuter() {
        val text = "\u201CShe shouted \u201Cstop. Wait.\u201D loudly. He ran.\u201D"
        assertEquals(listOf(text), texts(roundTrip(text)))
    }

    @Test
    fun singleQuotes_ignored() {
        assertEquals(
            listOf("'One. Two. Three. Four.' ", "He left."),
            texts(roundTrip("'One. Two. Three. Four.' He left."))
        )
    }

    @Test
    fun apostrophes_neverGlue() {
        val out = roundTrip("The man's hat fell. The dog's tail wagged. He laughed.")
        assertEquals(3, out.size)
    }

    @Test
    fun curlySingles_glued() {
        assertEquals(
            listOf("\u2018One. Two.\u2019 ", "He left."),
            texts(roundTrip("\u2018One. Two.\u2019 He left."))
        )
    }

    // Round trip (rule 10.1), tested hard.

    @Test
    fun roundTrip_trickySpacing() {
        val out = roundTrip("  Leading space.  Double  inside.\tTabbed. Trailing.  ")
        assertEquals(4, out.size)
    }

    @Test
    fun roundTrip_newlines() {
        val out = roundTrip("First sentence.\nSecond on new line.  Third  spaced.")
        assertEquals(3, out.size)
    }

    @Test
    fun roundTrip_manyTrickyParagraphs() {
        val paragraphs = listOf(
            "Mr. Smith waited... and waited. Then Mrs. Jones arrived.",
            "She said, \"Go home. Now.\" and left. It was late.",
            "\"One. Two. Three. Four. Five.\" They all left.",
            "J. K. Rowling dreamed... of dragons. She woke.",
            "Hello.  World with  double spaces!  Really?  Yes.",
            "  Padded start and end.  ",
            "Tabs\tinside. New\nlines. Mixed   spacing.",
            "Curly \u201Cquotes. Inside.\u201D Then more. End.",
            "Ellipsis\u2026 plus words. Mr. Jones... waited. Done.",
            "\"Wait...\" she whispered. He left. She stayed."
        )
        for (text in paragraphs) {
            val out = SentenceSplitter.splitParagraph(text)
            assertTrue("no sentences for ${text.take(40)}", out.isNotEmpty())
            assertEquals("round trip for ${text.take(40)}", text, out.joinToString("") { it.text })
        }
    }

    @Test
    fun blankParagraph_yieldsNoSentences() {
        assertTrue(SentenceSplitter.splitParagraph("").isEmpty())
        assertTrue(SentenceSplitter.splitParagraph("   ").isEmpty())
        assertTrue(SentenceSplitter.splitParagraph("\t \n ").isEmpty())
    }

    // Headings and chapters (rules 8.6, 8.8).

    private fun chapter(vararg blocks: IngestBlock): IngestChapter =
        IngestChapter(index = 1, title = "Test", href = "test.xhtml", blocks = blocks.toList(), wordCount = 0)

    @Test
    fun splitChapter_headingParaBreak() {
        val result = SentenceSplitter.splitChapter(
            chapter(
                IngestBlock(kind = BLOCK_HEADING, text = "Chapter One: The Beginning", level = 1),
                IngestBlock(kind = BLOCK_PARA, text = "First. Second."),
                IngestBlock(kind = BLOCK_BREAK)
            )
        )
        assertEquals(3, result.size)
        assertEquals(listOf("Chapter One: The Beginning"), texts(result[0].sentences))
        assertEquals(listOf("First. ", "Second."), texts(result[1].sentences))
        assertTrue(result[2].sentences.isEmpty())
        assertEquals(listOf(1, 2, 3), result.flatMap { it.sentences }.map { it.sid })
        assertEquals(listOf(1, 2, 3), result.map { it.block })
    }

    @Test
    fun splitChapter_sidsConsecutiveAcrossBlocks() {
        val result = SentenceSplitter.splitChapter(
            chapter(
                IngestBlock(kind = BLOCK_HEADING, text = "A Title", level = 2),
                IngestBlock(kind = BLOCK_PARA, text = "One. Two. Three."),
                IngestBlock(kind = BLOCK_QUOTE, text = "Quoted. Once.")
            )
        )
        val sids = result.flatMap { it.sentences }.map { it.sid }
        assertEquals((1..sids.size).toList(), sids)
        assertEquals(1 + 3 + 2, sids.size)
    }

    @Test
    fun splitChapter_blankHeadingHoldsNoSentences() {
        val result = SentenceSplitter.splitChapter(
            chapter(IngestBlock(kind = BLOCK_HEADING, text = "", level = 1))
        )
        assertEquals(1, result.size)
        assertTrue(result[0].sentences.isEmpty())
    }

    @Test
    fun splitChapter_headingTextKeptAsIs() {
        val text = "What happened next? A question."
        val result = SentenceSplitter.splitChapter(
            chapter(IngestBlock(kind = BLOCK_HEADING, text = text, level = 1))
        )
        assertEquals(listOf(text), texts(result[0].sentences))
    }

    @Test
    fun splitParagraph_startSidOffsets() {
        val out = SentenceSplitter.splitParagraph("One. Two.", startSid = 5)
        assertEquals(listOf(5, 6), out.map { it.sid })
    }

    // Span rebasing (rule 7.4).

    @Test
    fun spanCrossingBoundary_clippedToBothSides() {
        val text = "Anna loved old maps. She kept one."
        val span = IngestSpan(start = text.indexOf("maps."), end = text.indexOf("She") + 3, style = SPAN_ITALIC)
        val out = roundTrip(text, listOf(span))
        assertEquals(2, out.size)
        val firstLen = out[0].text.length
        assertEquals(listOf(IngestSpan(start = span.start, end = firstLen, style = SPAN_ITALIC)), out[0].spans)
        assertEquals(
            listOf(IngestSpan(start = 0, end = span.end - firstLen, style = SPAN_ITALIC)),
            out[1].spans
        )
    }

    @Test
    fun nestedSpans_stayIndependent() {
        val text = "Anna loved old maps. She kept one."
        val spans = listOf(
            IngestSpan(start = 0, end = 4, style = SPAN_ITALIC),
            IngestSpan(start = 0, end = 4, style = SPAN_BOLD)
        )
        val out = roundTrip(text, spans)
        assertEquals(spans, out[0].spans)
        assertTrue(out[1].spans.isEmpty())
    }

    @Test
    fun spanInsideSingleSentence() {
        val text = "Anna loved old maps. She kept one."
        val start = text.indexOf("kept")
        val span = IngestSpan(start = start, end = start + 4, style = SPAN_BOLD)
        val out = roundTrip(text, listOf(span))
        assertTrue(out[0].spans.isEmpty())
        val firstLen = out[0].text.length
        assertEquals(
            listOf(IngestSpan(start = span.start - firstLen, end = span.end - firstLen, style = SPAN_BOLD)),
            out[1].spans
        )
    }

    @Test
    fun spanAcrossLongQuoteSplit() {
        val text = "\"One. Two. Three. Four.\" They left."
        val span = IngestSpan(start = 0, end = text.indexOf('"', 1) + 1, style = SPAN_ITALIC)
        val out = roundTrip(text, listOf(span))
        assertEquals(5, out.size)
        var offset = 0
        for (sent in out.take(4)) {
            val start = maxOf(span.start, offset)
            val end = minOf(span.end, offset + sent.text.length)
            assertEquals(
                listOf(IngestSpan(start = start - offset, end = end - offset, style = SPAN_ITALIC)),
                sent.spans
            )
            offset += sent.text.length
        }
        assertTrue(out[4].spans.isEmpty())
    }

    @Test
    fun spanEndingAtBoundary_staysInFirstSentence() {
        val text = "Anna left. She stayed."
        val firstLen = "Anna left. ".length
        val span = IngestSpan(start = 0, end = firstLen, style = SPAN_ITALIC)
        val out = roundTrip(text, listOf(span))
        assertEquals(2, out.size)
        assertEquals(listOf(span), out[0].spans)
        assertTrue("edge-trim span never covers the next sentence", out[1].spans.isEmpty())
    }
}
