package app.auloud.player.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN4: jsoup walker unit tests (JVM only, synthetic HTML).
 *
 * Mirrors the Scribe mini-EPUB shapes (`test_extract_epub.py`) at the
 * walker level: element map, breaks, spans with exact offsets, footnote
 * strips, script/style removal, forgiving parsing. Cleaning drops,
 * titles and merges live in [StructureCleanerTest]; end-to-end order
 * lives in [StructurePipelineTest].
 */
class EpubStructureWalkerTest {

    private fun parse(html: String, label: String = "ch1.xhtml"): ParsedSpineDocument {
        val result = EpubStructureWalker.parseDocument(html.toByteArray(Charsets.UTF_8), label)
        assertTrue(
            "expected success but got: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
        return result.getOrThrow()
    }

    private fun kinds(doc: ParsedSpineDocument): List<String> = doc.blocks.map { it.kind }

    @Test
    fun headings_mapLevelsAndClampDeepOnes() {
        val doc = parse(
            "<h1>One</h1><h2>Two</h2><h3>Three</h3>" +
                "<h4>Four</h4><h5>Five</h5><h6>Six</h6>"
        )
        assertEquals(
            listOf("heading", "heading", "heading", "heading", "heading", "heading"),
            kinds(doc)
        )
        assertEquals(listOf(1, 2, 3, 3, 3, 3), doc.blocks.map { it.level })
        assertEquals("One", doc.blocks[0].text)
    }

    @Test
    fun emptyHeading_becomesEmptyHeadingBlock() {
        val doc = parse("<h1>  </h1><p>Text.</p>")
        assertEquals(listOf("heading", "para"), kinds(doc))
        assertEquals("", doc.blocks[0].text)
    }

    @Test
    fun blockquote_collapsesInnerParagraphs() {
        val doc = parse(
            "<blockquote><p>First half.</p>\n<p>Second half.</p></blockquote><p>After.</p>"
        )
        assertEquals(listOf("quote", "para"), kinds(doc))
        assertEquals("First half. Second half.", doc.blocks[0].text)
        assertEquals("After.", doc.blocks[1].text)
    }

    @Test
    fun emptyBlockquote_becomesEmptyParaBlock() {
        val doc = parse("<blockquote>   </blockquote>")
        assertEquals(listOf("para"), kinds(doc))
        assertEquals("", doc.blocks[0].text)
    }

    @Test
    fun hr_isUnconditionalBreak() {
        val doc = parse("<p>Before.</p><hr/><p>After.</p>")
        assertEquals(listOf("para", "break", "para"), kinds(doc))
        assertEquals("", doc.blocks[1].text)
    }

    @Test
    fun centeredAsterisks_areBreaks() {
        val doc = parse("<p>Before.</p><p>* * *</p><p>After.</p>")
        assertEquals(listOf("para", "break", "para"), kinds(doc))
    }

    @Test
    fun loneDinkus_isBreak() {
        val doc = parse("<p>Before.</p><p>\u2042</p>")
        assertEquals(listOf("para", "break"), kinds(doc))
    }

    @Test
    fun plainDashes_areNotBreaks() {
        val doc = parse("<p>---</p><p>___</p><p>~~~</p>")
        assertEquals(listOf("para", "para", "para"), kinds(doc))
    }

    @Test
    fun dividerLengthBoundary_breakAtTwelveNotThirteen() {
        // "* * * * * *" is 11 chars: break. Seven stars is 13: kept para.
        val eleven = parse("<p>* * * * * *</p>")
        assertEquals(listOf("break"), kinds(eleven))
        val thirteen = parse("<p>* * * * * * *</p>")
        assertEquals(listOf("para"), kinds(thirteen))
    }

    @Test
    fun spanOffsets_matchScribeMiniEpub() {
        val doc = parse("<p>She left <em>early</em> and <strong>ran fast</strong> home.</p>")
        assertEquals(1, doc.blocks.size)
        val block = doc.blocks[0]
        assertEquals("She left early and ran fast home.", block.text)
        assertEquals(2, block.spans.size)
        val byStyle = block.spans.associateBy { it.style }
        assertEquals(IngestSpan(9, 14, SPAN_ITALIC), byStyle[SPAN_ITALIC])
        assertEquals(IngestSpan(19, 27, SPAN_BOLD), byStyle[SPAN_BOLD])
        assertEquals("early", block.text.substring(9, 14))
        assertEquals("ran fast", block.text.substring(19, 27))
    }

    @Test
    fun nestedEmInStrong_yieldsTwoIndependentSpans() {
        val doc = parse("<p><strong><em>both</em></strong> plain</p>")
        assertEquals("both plain", doc.blocks[0].text)
        assertEquals(
            listOf(IngestSpan(0, 4, SPAN_BOLD), IngestSpan(0, 4, SPAN_ITALIC)),
            doc.blocks[0].spans.sortedWith(compareBy({ it.start }, { it.end }, { it.style }))
        )
    }

    @Test
    fun spanAcrossInnerSpace_staysOneSpan() {
        val doc = parse("<p><em>foo bar</em> baz</p>")
        assertEquals("foo bar baz", doc.blocks[0].text)
        assertEquals(listOf(IngestSpan(0, 7, SPAN_ITALIC)), doc.blocks[0].spans)
    }

    @Test
    fun spanEdges_trimWhitespace() {
        val doc = parse("<p><em> foo </em>bar</p>")
        assertEquals("foo bar", doc.blocks[0].text)
        assertEquals(listOf(IngestSpan(0, 3, SPAN_ITALIC)), doc.blocks[0].spans)
    }

    @Test
    fun styleThroughNestedInline_isDetected() {
        val doc = parse("<p><em><span>deep</span></em> plain</p>")
        assertEquals(listOf(IngestSpan(0, 4, SPAN_ITALIC)), doc.blocks[0].spans)
    }

    @Test
    fun curlyQuotes_preserved() {
        val doc = parse("<p>\u201CHello\u201D world</p>")
        assertEquals("\u201CHello\u201D world", doc.blocks[0].text)
    }

    @Test
    fun nfc_combiningMarkBecomesSingleCodePoint() {
        val doc = parse("<p>cafe\u0301</p>")
        assertEquals("caf\u00E9", doc.blocks[0].text)
        assertEquals(4, doc.blocks[0].text.length)
    }

    @Test
    fun spanAfterCombiningMark_usesComposedOffsets() {
        val doc = parse("<p><em>cafe\u0301</em> x</p>")
        assertEquals("caf\u00E9 x", doc.blocks[0].text)
        assertEquals(listOf(IngestSpan(0, 4, SPAN_ITALIC)), doc.blocks[0].spans)
    }

    @Test
    fun scriptAndStyle_removed() {
        val doc = parse("<p>Keep</p><script>evil()</script><style>p { color: red; }</style>")
        assertEquals(listOf("para"), kinds(doc))
        assertEquals("Keep", doc.blocks[0].text)
    }

    @Test
    fun supFootnote_strippedAndCounted() {
        val doc = parse("<p>The trail continues.<sup><a href=\"#fn1\">[1]</a></sup> And the fox follows.</p>")
        assertEquals(1, doc.blocks.size)
        assertEquals("The trail continues. And the fox follows.", doc.blocks[0].text)
        assertEquals(1, doc.footnoteStrips)
    }

    @Test
    fun footnoteLink_strippedAndCounted() {
        val doc = parse("<p>A word<a href=\"#footnote-1\">[2]</a> then more.</p>")
        assertEquals("A word then more.", doc.blocks[0].text)
        assertEquals(1, doc.footnoteStrips)
    }

    @Test
    fun shortRefLink_strippedByLengthRule() {
        val doc = parse("<p>A word<a href=\"#preface\">see</a> then more.</p>")
        assertEquals("A word then more.", doc.blocks[0].text)
        assertEquals(1, doc.footnoteStrips)
    }

    @Test
    fun contentLink_keptWithText() {
        val doc = parse("<p>See <a href=\"ch2.xhtml\">next chapter</a> now.</p>")
        assertEquals("See next chapter now.", doc.blocks[0].text)
        assertEquals(0, doc.footnoteStrips)
        assertTrue(doc.blocks[0].spans.isEmpty())
    }

    @Test
    fun emptyPara_becomesEmptyBlock() {
        val doc = parse("<p></p><p>   </p><p>Text.</p>")
        assertEquals(listOf("para", "para", "para"), kinds(doc))
        assertEquals("", doc.blocks[0].text)
        assertEquals("", doc.blocks[1].text)
    }

    @Test
    fun imageOnlyPara_yieldsEmptyText() {
        val doc = parse("<p><img src=\"pic.jpg\" alt=\"a picture\"/></p>")
        assertEquals(listOf("para"), kinds(doc))
        assertEquals("", doc.blocks[0].text)
    }

    @Test
    fun divFallback_leafBecomesPara() {
        val doc = parse("<div>Leaf text here.</div>")
        assertEquals(listOf("para"), kinds(doc))
        assertEquals("Leaf text here.", doc.blocks[0].text)
    }

    @Test
    fun divFallback_skipsContainersWithBlockChildren() {
        val doc = parse("<div><p>Real para.</p></div>")
        assertEquals(listOf("para"), kinds(doc))
        assertEquals("Real para.", doc.blocks[0].text)
    }

    @Test
    fun divFallback_emptySkippedSilently() {
        val doc = parse("<div>   </div><p>Text.</p>")
        assertEquals(listOf("para"), kinds(doc))
        assertEquals("Text.", doc.blocks[0].text)
    }

    @Test
    fun divFallback_dividerBecomesBreak() {
        val doc = parse("<div>* * *</div>")
        assertEquals(listOf("break"), kinds(doc))
    }

    @Test
    fun sectionFallback_behavesLikeDiv() {
        val doc = parse("<section>Section text.</section>")
        assertEquals(listOf("para"), kinds(doc))
        assertEquals("Section text.", doc.blocks[0].text)
    }

    @Test
    fun malformedMarkup_neverThrows() {
        val doc = parse("<p>Unclosed <em>oops<div>mess</p>trailing")
        assertTrue(doc.blocks.isNotEmpty())
        assertTrue(doc.blocks.any { it.text.contains("Unclosed") })
    }

    @Test
    fun hasImages_detectsImgCaseInsensitively() {
        assertTrue(EpubStructureWalker.hasImages("<p><IMG SRC=\"x.jpg\"/></p>".toByteArray()))
        assertTrue(EpubStructureWalker.hasImages("<div><img src=\"x.jpg\"/></div>".toByteArray()))
        assertTrue(!EpubStructureWalker.hasImages("<p>Just text.</p>".toByteArray()))
    }

    @Test
    fun headings_insideBlockquoteCoveredByQuote() {
        val doc = parse("<blockquote><h2>Quoted head</h2>\n<p>Quoted para.</p></blockquote>")
        assertEquals(listOf("quote"), kinds(doc))
        assertEquals("Quoted head Quoted para.", doc.blocks[0].text)
    }

    @Test
    fun compactBlockquote_joinsWithoutInsertedSpace() {
        // Scribe parity: the single-space separator comes from source
        // whitespace between inner paragraphs (pretty-printed EPUBs).
        // Compact markup with none concatenates directly.
        val doc = parse("<blockquote><p>A.</p><p>B.</p></blockquote>")
        assertEquals(listOf("quote"), kinds(doc))
        assertEquals("A.B.", doc.blocks[0].text)
    }
}
