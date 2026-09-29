package app.auloud.player.storage

import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: [SafCursor] row mapping verifies on plain JVM —
 * directory flag from the MIME type, blank names skipped. The pinned
 * [SafCursor.DIR_MIME] must equal the framework
 * `DocumentsContract.Document.MIME_TYPE_DIR` (a compile-time constant, so
 * referencing it runs no framework code).
 */
class SafCursorTest {

    @Test
    fun dirMime_matchesFrameworkConstant() {
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, SafCursor.DIR_MIME)
    }

    @Test
    fun parseRow_directoryFlagFromMime() {
        assertEquals(
            SafChild("book1", true),
            SafCursor.parseRow("book1", SafCursor.DIR_MIME)
        )
        assertEquals(
            SafChild("note.txt", false),
            SafCursor.parseRow("note.txt", "text/plain")
        )
        // Unknown MIME is a file, never a crash.
        assertEquals(
            SafChild("odd", false),
            SafCursor.parseRow("odd", null)
        )
    }

    @Test
    fun parseRow_blankName_skipped() {
        assertNull(SafCursor.parseRow(null, SafCursor.DIR_MIME))
        assertNull(SafCursor.parseRow("  ", SafCursor.DIR_MIME))
    }

    @Test
    fun parseRow_trimsName() {
        assertEquals(
            SafChild("book1", true),
            SafCursor.parseRow("  book1  ", SafCursor.DIR_MIME)
        )
    }

    @Test
    fun parseRow_customDirMime_honored() {
        assertTrue(SafCursor.parseRow("d", "custom/dir", "custom/dir")!!.isDirectory)
    }
}
