package app.auloud.player.ingest

import kotlinx.coroutines.yield

/**
 * PDF1: PDF pages into chapters for the shared import tail.
 *
 * Fixed 20-page ranges, mirroring Scribe's fallback (`extract/pdf.py`
 * `FIXED_CHAPTER_PAGES`; outline and heading splits stay a follow-up).
 * Each range becomes one [RawIngestChapter] titled `Pages X–Y`; page
 * text splits into paragraphs on blank lines (no spans on device).
 * Fully empty ranges are skipped. Output feeds
 * [StructureCleaner.assemble] directly (20-page chunks are never tiny,
 * so no merge pass); sentence split plus dialogue tagging stay in the
 * pipeline's shared per-chapter loop.
 *
 * Page sync marks (`pages`) are Scribe-only for now: on-device chapters
 * are plain text chapters, so read plus TTS work and page numbers wait
 * for a later slice. Pure Kotlin, JVM-tested with a fake [PdfBook].
 */
object PdfStructure {

    /** Pages per chapter (Scribe's fallback number). */
    const val PAGES_PER_CHAPTER = 20

    private val paraBreak = Regex("\\R\\s*\\R")
    private val whitespace = Regex("\\s+")

    /**
     * Pages into 20-page chapters. Suspends cooperatively ([yield] per
     * page, so import cancel lands promptly instead of after the whole
     * book) and reports each finished range through [onRange] (the
     * import screen moves during extraction instead of freezing).
     */
    suspend fun chapters(
        book: PdfBook,
        onRange: suspend (ImportProgress) -> Unit = {}
    ): List<IngestChapter> {
        val totalRanges = (book.pageCount + PAGES_PER_CHAPTER - 1) / PAGES_PER_CHAPTER
        val raw = ArrayList<RawIngestChapter>()
        var first = 1
        var done = 0
        while (first <= book.pageCount) {
            val last = minOf(first + PAGES_PER_CHAPTER - 1, book.pageCount)
            val blocks = ArrayList<IngestBlock>()
            for (page in first..last) {
                yield()
                for (para in paragraphs(book.pageText(page))) {
                    blocks.add(IngestBlock(kind = BLOCK_PARA, text = para))
                }
            }
            done++
            if (blocks.isNotEmpty()) {
                raw.add(
                    RawIngestChapter(
                        href = "pages-$first-$last",
                        title = "Pages $first–$last",
                        blocks = blocks
                    )
                )
            }
            onRange(
                ImportProgress(
                    chapterIndex = done,
                    chaptersDone = done,
                    totalChapters = totalRanges,
                    chapterTitle = "Pages $first–$last"
                )
            )
            first = last + 1
        }
        return StructureCleaner.assemble(raw)
    }

    internal fun paragraphs(pageText: String): List<String> {
        return pageText
            .split(paraBreak)
            .map { it.replace(whitespace, " ").trim() }
            .filter { it.isNotEmpty() }
    }
}
