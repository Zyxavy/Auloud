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
}
