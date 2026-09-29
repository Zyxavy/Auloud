package app.auloud.player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: grant reconciliation verifies on plain JVM — granted
 * trees and all file folders are kept, trees missing from the persisted set
 * are dropped for the caller to surface (never silently).
 */
class WatchFolderGrantsTest {

    private val treeA = WatchFolder.TreeUri(
        "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
    )
    private val treeB = WatchFolder.TreeUri(
        "content://com.android.externalstorage.documents/tree/1234-ABCD%3AAuloud"
    )
    private val file = WatchFolder.FilePath("/storage/emulated/0/Auloud")

    @Test
    fun prune_keepsGrantedTreesAndFiles() {
        val pruned = WatchFolderGrants.prune(
            listOf(file, treeA, treeB),
            setOf(treeA.uriString, treeB.uriString)
        )

        assertEquals(listOf(file, treeA, treeB), pruned.kept)
        assertTrue(pruned.dropped.isEmpty())
    }

    @Test
    fun prune_dropsUngrantedTrees_keepsFiles() {
        val pruned = WatchFolderGrants.prune(
            listOf(file, treeA, treeB),
            setOf(treeA.uriString)
        )

        assertEquals(listOf(file, treeA), pruned.kept)
        assertEquals(listOf(treeB), pruned.dropped)
    }

    @Test
    fun prune_emptyGrants_dropsAllTrees() {
        val pruned = WatchFolderGrants.prune(
            listOf(file, treeA),
            emptySet()
        )

        assertEquals(listOf(file), pruned.kept)
        assertEquals(listOf(treeA), pruned.dropped)
    }

    @Test
    fun prune_toleratesWhitespace() {
        val pruned = WatchFolderGrants.prune(
            listOf(treeA),
            setOf("  ${treeA.uriString}  ")
        )

        assertEquals(listOf(treeA), pruned.kept)
        assertTrue(pruned.dropped.isEmpty())
    }
}
