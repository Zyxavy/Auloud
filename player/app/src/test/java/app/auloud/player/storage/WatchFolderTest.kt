package app.auloud.player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: watch-folder model verifies on plain JVM — encode /
 * decode round-trips, display names for file and tree entries, and the
 * file-vs-tree discriminator plus structural equality used by the store and
 * rescan routing.
 */
class WatchFolderTest {

    @Test
    fun encodeDecode_filePath_roundTrips() {
        val folder = WatchFolder.FilePath("/storage/emulated/0/Auloud")

        assertEquals(folder, WatchFolders.decode(WatchFolders.encode(folder)))
    }

    @Test
    fun encodeDecode_treeUri_roundTrips() {
        val folder = WatchFolder.TreeUri(
            "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        )

        assertEquals(folder, WatchFolders.decode(WatchFolders.encode(folder)))
    }

    @Test
    fun decode_barePath_isFilePath() {
        assertEquals(
            WatchFolder.FilePath("/books"),
            WatchFolders.decode("/books")
        )
    }

    @Test
    fun decode_bareContentUri_isTreeUri() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"

        assertEquals(WatchFolder.TreeUri(uri), WatchFolders.decode(uri))
    }

    @Test
    fun decode_blank_isNull() {
        assertNull(WatchFolders.decode(null))
        assertNull(WatchFolders.decode("  "))
        assertNull(WatchFolders.decode("file:  "))
        assertNull(WatchFolders.decode("tree:  "))
    }

    @Test
    fun rootString_returnsPathOrUri() {
        assertEquals(
            "/storage/emulated/0/Auloud",
            WatchFolders.rootString(WatchFolder.FilePath("/storage/emulated/0/Auloud"))
        )
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        assertEquals(uri, WatchFolders.rootString(WatchFolder.TreeUri(uri)))
    }

    @Test
    fun displayName_filePath_isLastSegment() {
        assertEquals(
            "Auloud",
            WatchFolders.displayName(WatchFolder.FilePath("/storage/emulated/0/Auloud"))
        )
        assertEquals(
            "Auloud",
            WatchFolders.displayName(WatchFolder.FilePath("/storage/emulated/0/Auloud/"))
        )
    }

    @Test
    fun displayName_treeUri_isDocumentTail() {
        assertEquals(
            "Auloud",
            WatchFolders.displayName(
                WatchFolder.TreeUri(
                    "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
                )
            )
        )
        assertEquals(
            "Books",
            WatchFolders.displayName(
                WatchFolder.TreeUri(
                    "content://com.android.externalstorage.documents/tree/primary%3AAuloud%2FBooks"
                )
            )
        )
    }

    @Test
    fun isSafPath_distinguishesTreeFromFile() {
        assertTrue(
            WatchFolders.isSafPath(
                "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
            )
        )
        assertTrue(
            WatchFolders.isSafPath(
                "content://com.android.externalstorage.documents/tree/primary%3AAuloud|book1"
            )
        )
        assertFalse(WatchFolders.isSafPath("/storage/emulated/0/Auloud"))
    }

    @Test
    fun same_filePaths_ignoresTrailingSlash() {
        assertTrue(
            WatchFolders.same(
                WatchFolder.FilePath("/books/"),
                WatchFolder.FilePath("/books")
            )
        )
        assertFalse(
            WatchFolders.same(
                WatchFolder.FilePath("/books"),
                WatchFolder.FilePath("/other")
            )
        )
    }

    @Test
    fun same_treeUris_useExactString() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        assertTrue(
            WatchFolders.same(WatchFolder.TreeUri(uri), WatchFolder.TreeUri(uri))
        )
        assertFalse(
            WatchFolders.same(
                WatchFolder.TreeUri(uri),
                WatchFolder.TreeUri("$uri%2FBooks")
            )
        )
    }

    @Test
    fun same_mixedKinds_neverEqual() {
        assertFalse(
            WatchFolders.same(
                WatchFolder.FilePath("content://x"),
                WatchFolder.TreeUri("content://x")
            )
        )
    }

    @Test
    fun isValid_rejectsBlanksAndNonContentTrees() {
        assertFalse(WatchFolders.isValid(WatchFolder.FilePath("  ")))
        assertFalse(WatchFolders.isValid(WatchFolder.TreeUri("")))
        assertFalse(WatchFolders.isValid(WatchFolder.TreeUri("/storage/emulated/0/Auloud")))
        assertTrue(WatchFolders.isValid(WatchFolder.FilePath("/books")))
        assertTrue(
            WatchFolders.isValid(
                WatchFolder.TreeUri("content://com.android.externalstorage.documents/tree/x")
            )
        )
    }

    @Test
    fun percentDecode_decodesHexAndUtf8() {
        assertEquals("Auloud", WatchFolders.percentDecode("Auloud"))
        assertEquals("primary:Auloud", WatchFolders.percentDecode("primary%3AAuloud"))
        assertEquals("a/b c", WatchFolders.percentDecode("a%2Fb%20c"))
        assertEquals("a+b", WatchFolders.percentDecode("a+b"))
        // Multi-byte UTF-8 (U+2019 RIGHT SINGLE QUOTATION MARK).
        assertEquals("\u2019", WatchFolders.percentDecode("%E2%80%99"))
        // Malformed sequences pass through literally.
        assertEquals("a%2", WatchFolders.percentDecode("a%2"))
        assertEquals("a%zz", WatchFolders.percentDecode("a%zz"))
    }

    @Test
    fun treeDocumentLabel_decodesWholeGrant() {
        assertEquals(
            "primary:Auloud",
            WatchFolders.treeDocumentLabel(
                "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
            )
        )
        assertEquals(
            "primary:Auloud/Books",
            WatchFolders.treeDocumentLabel(
                "content://com.android.externalstorage.documents/tree/primary%3AAuloud%2FBooks"
            )
        )
    }

    @Test
    fun displayPath_collapsesTokens() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        assertEquals("saf-book", WatchFolders.displayPath("$tree|saf-book"))
        assertEquals(
            "saf-book/manifest.json",
            WatchFolders.displayPath("$tree|saf-book/manifest.json")
        )
        assertEquals("primary:Auloud", WatchFolders.displayPath(tree))
        assertEquals("/books/novel", WatchFolders.displayPath("/books/novel"))
    }

    @Test
    fun sanitizeUiText_rewritesTokensAndBareTrees() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        assertEquals(
            "saf-book: cannot list books folder",
            WatchFolders.sanitizeUiText("$tree|saf-book: cannot list books folder")
        )
        assertEquals(
            "primary:Auloud permission lost",
            WatchFolders.sanitizeUiText("$tree permission lost")
        )
        assertEquals(
            "/books/novel: plain paths untouched",
            WatchFolders.sanitizeUiText("/books/novel: plain paths untouched")
        )
    }
}
