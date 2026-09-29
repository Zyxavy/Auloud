package app.auloud.player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: SAF token mapping verifies on plain JVM — token split,
 * bundle-token building, in-bundle containment (same rule as
 * `FileBundleStorage`), and document-ID building. The framework
 * (`DocumentsContract`, `Uri`) is never touched here.
 */
class SafPathsTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"

    @Test
    fun splitToken_roundTrips() {
        val token = SafPaths.bundleToken(tree, "book1")

        assertEquals(tree to "book1", SafPaths.splitToken(token))
    }

    @Test
    fun splitToken_bareRoot_hasEmptyRel() {
        assertEquals(tree to "", SafPaths.splitToken(tree))
    }

    @Test
    fun splitToken_filePath_isNull() {
        assertNull(SafPaths.splitToken("/storage/emulated/0/Auloud/book1"))
    }

    @Test
    fun splitToken_manifestJoin_recoversRel() {
        val bundle = SafPaths.bundleToken(tree, "book1")
        val manifest = "$bundle/manifest.json"

        assertEquals(tree to "book1/manifest.json", SafPaths.splitToken(manifest))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bundleToken_blankName_throws() {
        SafPaths.bundleToken(tree, "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun bundleToken_slashName_throws() {
        SafPaths.bundleToken(tree, "a/b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun bundleToken_nonTree_throws() {
        SafPaths.bundleToken("/storage/emulated/0/Auloud", "book1")
    }

    @Test
    fun resolveInBundle_joinsRel() {
        assertEquals(
            "book1/audio/ch001.mp3",
            SafPaths.resolveInBundle("book1", "audio/ch001.mp3")
        )
        assertEquals(
            "book1/manifest.json",
            SafPaths.resolveInBundle("book1", "manifest.json")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolveInBundle_blank_throws() {
        SafPaths.resolveInBundle("book1", "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolveInBundle_dotDotEscape_throws() {
        SafPaths.resolveInBundle("book1", "../evil.mp3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun resolveInBundle_deepEscape_throws() {
        SafPaths.resolveInBundle("book1", "audio/../../evil.mp3")
    }

    @Test
    fun resolveInBundle_dotSegments_stayInside() {
        assertEquals(
            "book1/audio/ch001.mp3",
            SafPaths.resolveInBundle("book1", "./audio/ch001.mp3")
        )
        assertEquals(
            "book1/ch001.mp3",
            SafPaths.resolveInBundle("book1", "audio/../ch001.mp3")
        )
    }

    @Test
    fun documentId_buildsTreeRelativeId() {
        assertEquals(
            "primary:Auloud",
            SafPaths.documentId("primary:Auloud", "")
        )
        assertEquals(
            "primary:Auloud/book1/manifest.json",
            SafPaths.documentId("primary:Auloud", "book1/manifest.json")
        )
    }

    @Test
    fun isSafPath_matchesContentScheme() {
        assertTrue(SafPaths.isSafPath(tree))
        assertTrue(SafPaths.isSafPath("$tree|book1"))
        assertFalse(SafPaths.isSafPath("/storage/emulated/0/Auloud"))
        assertFalse(SafPaths.isSafPath(""))
    }

    @Test
    fun treeOf_stripsTokenRel() {
        assertEquals(tree, SafPaths.treeOf("$tree|book1/manifest.json"))
        assertEquals(tree, SafPaths.treeOf(tree))
    }

    @Test
    fun requireRelInTree_returnsRel() {
        assertEquals(
            "book1/manifest.json",
            SafPaths.requireRelInTree("$tree|book1/manifest.json", tree)
        )
    }

    @Test(expected = java.io.IOException::class)
    fun requireRelInTree_wrongTree_throws() {
        SafPaths.requireRelInTree("$tree|book1", "$tree%2FBooks")
    }
}
