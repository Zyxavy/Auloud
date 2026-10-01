package app.auloud.player.reader

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.Sentence
import app.auloud.player.bundle.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RA4: paragraph layout on plain JVM — joining, spacing fallback, span
 * rebasing/clamping, and edge cases.
 */
class ParagraphLayoutTest {

    private fun sentence(sid: Int, text: String, spans: List<Span> = emptyList()): Sentence {
        return Sentence(sid, "narrator", 0, 100, text, spans)
    }

    @Test
    fun scribeStyle_concatenatesAsStored() {
        val block = Block(1, "para", sentences = listOf(
            sentence(1, "First. "),
            sentence(2, "Second. ")
        ))
        val layout = layoutParagraph(block)
        assertEquals("First. Second. ", layout.text)
        assertEquals(
            listOf(SentenceRange(1, 0, 7), SentenceRange(2, 7, 15)),
            layout.sentences
        )
    }

    @Test
    fun trimmedSource_getsOneInsertedSpace() {
        val block = Block(1, "para", sentences = listOf(
            sentence(1, "First."),
            sentence(2, "Second.")
        ))
        val layout = layoutParagraph(block)
        assertEquals("First. Second.", layout.text)
        assertEquals(SentenceRange(1, 0, 7), layout.sentences[0])
        assertEquals(SentenceRange(2, 7, 14), layout.sentences[1])
    }

    @Test
    fun italicSpan_rebasedOntoParagraph() {
        val block = Block(1, "para", sentences = listOf(
            sentence(1, "Plain. "),
            sentence(2, "Some words here. ", listOf(Span(5, 10, "italic")))
        ))
        val layout = layoutParagraph(block)
        // Second sentence starts at 7; span covers "words" (7+5=12 .. 7+10=17).
        assertEquals(listOf(12 until 17), layout.italics)
        assertTrue(layout.bolds.isEmpty())
        assertEquals("words", layout.text.substring(12, 17))
    }

    @Test
    fun boldAndUnknownStyles() {
        val block = Block(1, "para", sentences = listOf(
            sentence(1, "Bold and odd. ", listOf(Span(0, 4, "bold"), Span(9, 12, "underline")))
        ))
        val layout = layoutParagraph(block)
        assertEquals(listOf(0 until 4), layout.bolds)
        assertTrue(layout.italics.isEmpty())
    }

    @Test
    fun outOfRangeSpan_clampedToSentence() {
        val block = Block(1, "para", sentences = listOf(
            sentence(1, "Short. ", listOf(Span(0, 99, "italic")))
        ))
        val layout = layoutParagraph(block)
        assertEquals(listOf(0 until 7), layout.italics)
    }

    @Test
    fun emptyBlock_yieldsEmptyLayout() {
        val layout = layoutParagraph(Block(1, "break"))
        assertEquals("", layout.text)
        assertTrue(layout.sentences.isEmpty())
        assertTrue(layout.italics.isEmpty())
    }
}
