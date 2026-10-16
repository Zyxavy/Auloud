package app.auloud.player.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PDF1: the [PdfBook] contract over an in-memory fake (the PdfBox
 * adapter needs a real Android context, so plain JVM tests never touch
 * it; device acceptance proves the real path). Pins page counts,
 * per-page text, metadata and the out-of-range refusal the pipeline
 * relies on.
 */
class PdfTextTest {

    private class FakePdfBook(
        private val pages: List<String>,
        override val title: String? = "Tiny PDF",
        override val author: String? = null
    ) : PdfBook {
        override val pageCount: Int = pages.size

        override fun pageText(page: Int): String {
            require(page in 1..pageCount) { "page $page out of $pageCount" }
            return pages[page - 1]
        }

        override fun close() {
        }
    }

    @Test
    fun pagesReadSeparately() {
        FakePdfBook(listOf("First page words.", "Second page words.")).use { book ->
            assertEquals(2, book.pageCount)
            assertEquals("Tiny PDF", book.title)
            assertEquals("First page words.", book.pageText(1))
            assertEquals("Second page words.", book.pageText(2))
            try {
                book.pageText(3)
                fail("page 3 must refuse")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun blankPagesReadEmpty() {
        FakePdfBook(listOf("", "Words.")).use { book ->
            assertEquals("", book.pageText(1))
            assertTrue(book.pageText(2).isNotBlank())
        }
    }
}
