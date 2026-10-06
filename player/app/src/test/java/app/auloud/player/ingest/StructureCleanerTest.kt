package app.auloud.player.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN4: text rules, drop rules, titles and tiny-merge (JVM only).
 *
 * Ports the Scribe `test_extract_epub.py` rule checks
 * (`test_normalize_keeps_curly_quotes`,
 * `test_boilerplate_and_break_and_words`) plus the cleaning branches of
 * `_clean_document` and `_merge_tiny` with synthetic inputs (no HTML).
 * The walker that feeds these inputs is covered in
 * [EpubStructureWalkerTest].
 */
class StructureCleanerTest {

    private fun input(
        href: String = "ch1.xhtml",
        tocTitle: String? = "Chapter One",
        isNav: Boolean = false,
        hasImages: Boolean = false,
        blocks: List<IngestBlock> = listOf(
            IngestBlock(kind = BLOCK_HEADING, text = "Chapter One", level = 1),
            IngestBlock(kind = BLOCK_PARA, text = "Some body text here.")
        ),
        footnoteStrips: Int = 0
    ) = SpineDocInput(href, tocTitle, isNav, hasImages, blocks, footnoteStrips)

    // ---- Text rules (rules doc section 6) ----

    @Test
    fun normalize_collapsesAndTrimsKeepsCurlyQuotes() {
        assertEquals(
            "\u201CHello\u201D world",
            StructureCleaner.normalizeText("  \u201CHello\u201D   world \n")
        )
    }

    @Test
    fun normalize_nfcComposesCombiningMarks() {
        assertEquals("caf\u00E9", StructureCleaner.normalizeText("cafe\u0301"))
    }

    @Test
    fun normalize_nbspCollapsesLikeScribe() {
        assertEquals("a b", StructureCleaner.normalizeText("a\u00A0 b"))
    }

    @Test
    fun boilerplate_matchesEveryHint() {
        assertTrue(StructureCleaner.isBoilerplate("Copyright 2026 Example Press. All rights reserved."))
        assertTrue(StructureCleaner.isBoilerplate("ISBN 978-0-00-000000-0"))
        assertTrue(StructureCleaner.isBoilerplate("This ebook was produced by Project Gutenberg volunteers."))
        assertTrue(StructureCleaner.isBoilerplate("A Gutenberg ebook, see notes."))
        assertTrue(StructureCleaner.isBoilerplate("Prepared by Distributed Proofreading."))
        assertTrue(StructureCleaner.isBoilerplate("The transcriber's note says more."))
        assertTrue(StructureCleaner.isBoilerplate("Thanks to all transcribers."))
        assertTrue(StructureCleaner.isBoilerplate("Published (c) 2026 by the press."))
        assertTrue(StructureCleaner.isBoilerplate("Text with \u00A9 sign."))
    }

    @Test
    fun boilerplate_rejectsStoryText() {
        assertFalse(StructureCleaner.isBoilerplate("She left early and ran fast home."))
    }

    @Test
    fun breakText_acceptsMarksRejectsPlainLines() {
        assertTrue(StructureCleaner.isBreakText("* * *"))
        assertTrue(StructureCleaner.isBreakText("\u2042"))
        assertTrue(StructureCleaner.isBreakText("\u2022 \u2022"))
        assertFalse(StructureCleaner.isBreakText("She left early."))
        assertFalse(StructureCleaner.isBreakText("---"))
        assertFalse(StructureCleaner.isBreakText("___"))
        assertFalse(StructureCleaner.isBreakText("~~~"))
        assertFalse(StructureCleaner.isBreakText(""))
        assertFalse(StructureCleaner.isBreakText("* * * * * * *"))
    }

    @Test
    fun countWords_countsTokensEmptyIsZero() {
        assertEquals(0, StructureCleaner.countWords(""))
        assertEquals(0, StructureCleaner.countWords("   "))
        assertEquals(3, StructureCleaner.countWords("  a  b c "))
    }

    // ---- Drop rules (rules doc section 4) ----

    @Test
    fun navPage_dropsWholeEvenWithText() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(isNav = true, blocks = listOf(IngestBlock(kind = BLOCK_PARA, text = "Nav text here."))),
            drops
        )
        assertNull(result)
        assertEquals(1, drops.size)
        assertTrue(drops[0].contains("ch1.xhtml") && drops[0].contains("nav/TOC"))
    }

    @Test
    fun emptyHeading_dropsWithHeadingWording() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                tocTitle = null,
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "   "),
                    IngestBlock(kind = BLOCK_PARA, text = "Body stays.")
                )
            ),
            drops
        )
        assertNotNull(result)
        assertTrue(drops.any { it.contains("dropped empty heading") })
        assertEquals(listOf("para"), result!!.blocks.map { it.kind })
    }

    @Test
    fun emptyPara_dropsWithParagraphWording() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head", level = 1),
                    IngestBlock(kind = BLOCK_PARA, text = "  "),
                    IngestBlock(kind = BLOCK_PARA, text = "Body stays.")
                )
            ),
            drops
        )
        assertNotNull(result)
        assertTrue(drops.any { it.contains("dropped empty paragraph") })
        assertEquals(2, result!!.blocks.size)
    }

    @Test
    fun boilerplate_dropsWithPreview() {
        val long = "Copyright 2026 Example Press. All rights reserved. " +
            "This paragraph keeps going past sixty characters total."
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head", level = 1),
                    IngestBlock(kind = BLOCK_PARA, text = long),
                    IngestBlock(kind = BLOCK_PARA, text = "Body stays.")
                )
            ),
            drops
        )
        assertNotNull(result)
        val entry = drops.single { it.contains("boilerplate") }
        assertTrue(entry.contains("ch1.xhtml"))
        assertTrue(entry.contains(long.substring(0, 60)))
        assertTrue(entry.contains("\u2026"))
        assertEquals(2, result!!.blocks.size)
    }

    @Test
    fun boilerplateQuote_dropsLikePara() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head", level = 1),
                    IngestBlock(kind = BLOCK_QUOTE, text = "Copyright 2026 Example Press."),
                    IngestBlock(kind = BLOCK_PARA, text = "Body stays.")
                )
            ),
            drops
        )
        assertNotNull(result)
        assertTrue(drops.any { it.contains("boilerplate") })
        assertEquals(listOf("heading", "para"), result!!.blocks.map { it.kind })
    }

    @Test
    fun leftoverDividerPara_becomesBreak() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head", level = 1),
                    IngestBlock(kind = BLOCK_PARA, text = "* * *"),
                    IngestBlock(kind = BLOCK_PARA, text = "Body stays.")
                )
            ),
            drops
        )
        assertNotNull(result)
        assertEquals(listOf("heading", "break", "para"), result!!.blocks.map { it.kind })
    }

    @Test
    fun dividerQuote_staysQuote() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head", level = 1),
                    IngestBlock(kind = BLOCK_QUOTE, text = "* * *")
                )
            ),
            drops
        )
        assertNotNull(result)
        assertEquals(listOf("heading", "quote"), result!!.blocks.map { it.kind })
    }

    @Test
    fun outOfRangeSpans_droppedInCleaning() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head", level = 1),
                    IngestBlock(
                        kind = BLOCK_PARA,
                        text = "Short.",
                        spans = listOf(
                            IngestSpan(0, 5, SPAN_ITALIC),
                            IngestSpan(0, 99, SPAN_BOLD),
                            IngestSpan(4, 2, SPAN_BOLD)
                        )
                    )
                )
            ),
            drops
        )
        assertNotNull(result)
        assertEquals(
            listOf(IngestSpan(0, 5, SPAN_ITALIC)),
            result!!.blocks[1].spans
        )
    }

    @Test
    fun imageOnlyPage_wordingNamesImages() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(hasImages = true, blocks = listOf(IngestBlock(kind = BLOCK_PARA, text = "  "))),
            drops
        )
        assertNull(result)
        assertTrue(drops.any { it.contains("image-only page") })
    }

    @Test
    fun emptyPage_wordingWithoutImages() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(hasImages = false, blocks = listOf(IngestBlock(kind = BLOCK_PARA, text = "  "))),
            drops
        )
        assertNull(result)
        assertTrue(drops.any { it.contains("empty page") })
    }

    @Test
    fun breaksOnlyPage_dropsAsEmpty() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                hasImages = false,
                blocks = listOf(IngestBlock(kind = BLOCK_BREAK), IngestBlock(kind = BLOCK_BREAK))
            ),
            drops
        )
        assertNull(result)
        assertTrue(drops.any { it.contains("empty page") })
    }

    // ---- Titles (rules doc section 5.1) ----

    @Test
    fun title_prefersTocOverFirstHeading() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                tocTitle = "  TOC Title  ",
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Other Head", level = 1),
                    IngestBlock(kind = BLOCK_PARA, text = "Body.")
                )
            ),
            drops
        )
        assertEquals("TOC Title", result!!.title)
    }

    @Test
    fun title_fallsBackToFirstHeading() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                tocTitle = null,
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head Words", level = 2),
                    IngestBlock(kind = BLOCK_PARA, text = "Body.")
                )
            ),
            drops
        )
        assertEquals("Head Words", result!!.title)
    }

    @Test
    fun title_blankTocFallsBackToHeading() {
        val drops = ArrayList<String>()
        val result = StructureCleaner.cleanDocument(
            input(
                tocTitle = "   ",
                blocks = listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "Head Words", level = 1),
                    IngestBlock(kind = BLOCK_PARA, text = "Body.")
                )
            ),
            drops
        )
        assertEquals("Head Words", result!!.title)
    }

    @Test
    fun assemble_untitledGetsFinalChapterIndex() {
        val chapters = StructureCleaner.assemble(
            listOf(
                RawIngestChapter("a.xhtml", "Chapter One", listOf(IngestBlock(BLOCK_PARA, "x"))),
                RawIngestChapter("b.xhtml", "Chapter Two", listOf(IngestBlock(BLOCK_PARA, "y"))),
                RawIngestChapter("c.xhtml", null, listOf(IngestBlock(BLOCK_PARA, "z")))
            )
        )
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter 3"), chapters.map { it.title })
        assertEquals(listOf(1, 2, 3), chapters.map { it.index })
    }

    // ---- Tiny merge (rules doc section 5.3) ----

    private fun wordsBlock(words: Int, prefix: String = "w"): IngestBlock {
        return IngestBlock(kind = BLOCK_PARA, text = (1..words).joinToString(" ") { "$prefix$it" })
    }

    @Test
    fun mergeTiny_forwardIntoNextKeepsTargetTitle() {
        val drops = ArrayList<String>()
        val tiny = RawIngestChapter(
            "ch2.xhtml", "Tiny Interlude",
            listOf(
                IngestBlock(kind = BLOCK_HEADING, text = "Tiny Interlude", level = 2),
                IngestBlock(kind = BLOCK_PARA, text = "A brief pause.")
            )
        )
        val full = RawIngestChapter(
            "ch3.xhtml", "Chapter Three",
            listOf(
                IngestBlock(kind = BLOCK_HEADING, text = "Chapter Three", level = 1),
                wordsBlock(250)
            )
        )
        val merged = StructureCleaner.mergeTiny(listOf(tiny, full), drops)
        assertEquals(1, merged.size)
        assertEquals("Chapter Three", merged[0].title)
        assertEquals("ch3.xhtml", merged[0].href)
        val headings = merged[0].blocks.filter { it.kind == BLOCK_HEADING }.map { it.text }
        assertEquals(listOf("Tiny Interlude", "Chapter Three"), headings)
        assertTrue(drops.any { it.contains("merged tiny chapter(s)") && it.contains("into next chapter") })
        assertTrue(drops.single { it.contains("merged tiny") }.contains("Chapter Three"))
    }

    @Test
    fun mergeTiny_trailingFoldsBackward() {
        val drops = ArrayList<String>()
        val full = RawIngestChapter(
            "ch1.xhtml", "Chapter One",
            listOf(
                IngestBlock(kind = BLOCK_HEADING, text = "Chapter One", level = 1),
                wordsBlock(250)
            )
        )
        val tiny = RawIngestChapter(
            "ch2.xhtml", "Tiny Tail",
            listOf(IngestBlock(kind = BLOCK_PARA, text = "A brief tail."))
        )
        val merged = StructureCleaner.mergeTiny(listOf(full, tiny), drops)
        assertEquals(1, merged.size)
        assertEquals("Chapter One", merged[0].title)
        assertEquals("ch1.xhtml", merged[0].href)
        assertTrue(merged[0].blocks.any { it.text == "A brief tail." })
        assertTrue(drops.any { it.contains("merged trailing tiny chapter(s)") })
    }

    @Test
    fun mergeTiny_allTinyCombinesIntoOne() {
        val drops = ArrayList<String>()
        val first = RawIngestChapter("a.xhtml", "First Tiny", listOf(wordsBlock(10, "a")))
        val last = RawIngestChapter("b.xhtml", "Last Tiny", listOf(wordsBlock(10, "b")))
        val merged = StructureCleaner.mergeTiny(listOf(first, last), drops)
        assertEquals(1, merged.size)
        assertEquals("Last Tiny", merged[0].title)
        assertEquals(2, merged[0].blocks.size)
        assertTrue(drops.any { it.contains("all chapters tiny") })
    }

    @Test
    fun mergeTiny_singleChapterSkipsMerging() {
        val drops = ArrayList<String>()
        val lone = RawIngestChapter("a.xhtml", null, listOf(wordsBlock(10)))
        val merged = StructureCleaner.mergeTiny(listOf(lone), drops)
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].blocks.size)
        assertTrue(drops.isEmpty())
        assertEquals("Chapter 1", StructureCleaner.assemble(merged)[0].title)
    }

    @Test
    fun chapterWordCount_ignoresBreaks() {
        assertEquals(
            3,
            StructureCleaner.chapterWordCount(
                listOf(
                    IngestBlock(kind = BLOCK_HEADING, text = "One Two"),
                    IngestBlock(kind = BLOCK_BREAK),
                    IngestBlock(kind = BLOCK_PARA, text = "three")
                )
            )
        )
    }
}
