package app.auloud.player.ingest

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File

/**
 * PDF1: on-device PDF text layer over PdfBox-Android (text extraction
 * only; page images stay out of scope).
 *
 * One open document serves page-by-page reads ([pageText] with 1-based
 * pages), so big PDFs never sit whole in memory beyond PDFBox's own
 * model; callers close promptly (try/use). Text comes from the embedded
 * text layer exactly as stored: scanned pages with no layer read empty
 * (no OCR, same rule as Scribe's draft).
 *
 * JVM boundary: PdfBox needs the Android resource loader (a real
 * `Context`), so plain JVM tests use a fake [PdfBook]; only the thin
 * [PdfBoxBook] adapter below touches the library, and it is proven by
 * the owner device acceptance (import a real PDF), never by the JVM
 * suite. API 24 safe: plain file bytes in, strings out.
 */
interface PdfBook : Closeable {

    /** 1-based page count. */
    val pageCount: Int

    /** Document title, or null when the metadata carries none. */
    val title: String?

    /** Document author, or null when the metadata carries none. */
    val author: String?

    /**
     * Plain text of one 1-based page (empty when the page has no text
     * layer). Throws [IllegalArgumentException] for out-of-range pages
     * and [java.io.IOException] for unreadable content.
     */
    fun pageText(page: Int): String
}

/** Opens PDF [file] with the app context (the resource loader needs it). */
fun openPdfBook(file: File, appContext: Context?): PdfBook =
    PdfBoxBook(file, appContext)

/** Production [PdfBook] over PdfBox-Android (device-proven, never JVM). */
internal class PdfBoxBook(
    file: File,
    appContext: Context?
) : PdfBook {

    private val document: PDDocument = run {
        if (appContext != null) {
            try {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(
                    appContext.applicationContext
                )
            } catch (_: Exception) {
            }
        }
        PDDocument.load(file)
    }

    override val pageCount: Int = document.numberOfPages

    override val title: String? =
        document.documentInformation?.title?.takeIf { it.isNotBlank() }

    override val author: String? =
        document.documentInformation?.author?.takeIf { it.isNotBlank() }

    override fun pageText(page: Int): String {
        require(page in 1..pageCount) { "page $page out of $pageCount" }
        val stripper = PDFTextStripper()
        stripper.startPage = page
        stripper.endPage = page
        return stripper.getText(document).trim()
    }

    override fun close() {
        try {
            document.close()
        } catch (_: Exception) {
        }
    }
}
