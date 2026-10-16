package app.auloud.player.ingest

/**
 * Shared fake [PdfBook] for JVM tests (the PdfBox adapter needs a real
 * Android context, so plain JVM tests never touch it). Page text plus
 * metadata only; the pipeline and structure contracts are what the
 * tests pin.
 */
internal class FakePdfBook(
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
