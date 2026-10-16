package app.auloud.player.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF1: [PdfStructure] over a fake book (page text plus ranges only;
 * the PdfBox adapter is device-proven). Pins the 20-page chunking,
 * paragraph splitting and empty-range skipping the pipeline relies on.
 */
class PdfStructureTest {

    @Test
    fun pagesChunkIntoTwentyPageRanges() {
        val pages = (1..45).map { n -> "Words of page $n.\n\nSecond para of $n." }
        val chapters = PdfStructure.chapters(FakePdfBook(pages, title = null))

        assertEquals(3, chapters.size)
        assertEquals("Pages 1–20", chapters[0].title)
        assertEquals("Pages 21–40", chapters[1].title)
        assertEquals("Pages 41–45", chapters[2].title)
        assertEquals(40, chapters[0].blocks.size)
        assertTrue(chapters.all { it.wordCount > 0 })
        assertEquals((1..3).toList(), chapters.map { it.index })
    }

    @Test
    fun emptyPagesAndRangesFallOut() {
        val chapters = PdfStructure.chapters(FakePdfBook(listOf("", "   ", "Kept words here.")))

        assertEquals(1, chapters.size)
        assertEquals(1, chapters[0].blocks.size)
        assertEquals("Kept words here.", chapters[0].blocks[0].text)
    }

    @Test
    fun emptyBookYieldsNoChapters() {
        assertTrue(PdfStructure.chapters(FakePdfBook(emptyList())).isEmpty())
    }
}
