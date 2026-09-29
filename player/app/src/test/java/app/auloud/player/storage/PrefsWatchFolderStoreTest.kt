package app.auloud.player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP3/WP5 refinement: watch-list persistence verifies on plain JVM via
 * [FakeSharedPreferences] (no Robolectric) — internal default, add/remove
 * with dedupe, order preservation, explicit-empty vs unset, legacy single
 * `books_folder` migration, and invalid-entry filtering.
 */
class PrefsWatchFolderStoreTest {

    private val defaultFolder = WatchFolder.FilePath("/storage/emulated/0/Auloud")
    private val sdFolder = WatchFolder.FilePath("/storage/1234-ABCD/Auloud")
    private val treeFolder = WatchFolder.TreeUri(
        "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
    )

    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: PrefsWatchFolderStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = PrefsWatchFolderStore(prefs, defaultFolder)
    }

    @Test
    fun unset_returnsInternalDefault() {
        assertEquals(listOf(defaultFolder), store.getWatchFolders())
    }

    @Test
    fun addFolder_appendsAndPersists() {
        store.addFolder(sdFolder)

        assertEquals(listOf(defaultFolder, sdFolder), store.getWatchFolders())
        // A fresh store over the same prefs sees the persisted list.
        assertEquals(
            listOf(defaultFolder, sdFolder),
            PrefsWatchFolderStore(prefs, defaultFolder).getWatchFolders()
        )
    }

    @Test
    fun addFolder_treeUri_roundTrips() {
        store.addFolder(treeFolder)

        assertEquals(
            listOf(defaultFolder, treeFolder),
            PrefsWatchFolderStore(prefs, defaultFolder).getWatchFolders()
        )
    }

    @Test
    fun addFolder_duplicate_isNoOp() {
        store.addFolder(WatchFolder.FilePath("/storage/emulated/0/Auloud/"))

        assertEquals(listOf(defaultFolder), store.getWatchFolders())
    }

    @Test
    fun addFolder_invalid_isNoOp() {
        store.addFolder(WatchFolder.FilePath("  "))
        store.addFolder(WatchFolder.TreeUri("/not-a-uri"))

        assertEquals(listOf(defaultFolder), store.getWatchFolders())
    }

    @Test
    fun removeFolder_dropsEntryAndRescansShorter() {
        store.addFolder(sdFolder)
        store.removeFolder(defaultFolder)

        assertEquals(listOf(sdFolder), store.getWatchFolders())
    }

    @Test
    fun removeFolder_missing_isNoOp() {
        store.removeFolder(sdFolder)

        assertEquals(listOf(defaultFolder), store.getWatchFolders())
    }

    @Test
    fun removeAll_staysEmpty_doesNotResurrectDefault() {
        store.removeFolder(defaultFolder)

        assertTrue(store.getWatchFolders().isEmpty())
        assertTrue(
            PrefsWatchFolderStore(prefs, defaultFolder).getWatchFolders().isEmpty()
        )
    }

    @Test
    fun setWatchFolders_replacesDedupesAndKeepsOrder() {
        store.setWatchFolders(listOf(sdFolder, treeFolder, sdFolder, defaultFolder))

        assertEquals(
            listOf(sdFolder, treeFolder, defaultFolder),
            store.getWatchFolders()
        )
    }

    @Test
    fun setWatchFolders_dropsInvalid() {
        store.setWatchFolders(
            listOf(WatchFolder.FilePath(" "), sdFolder, WatchFolder.TreeUri("bogus"))
        )

        assertEquals(listOf(sdFolder), store.getWatchFolders())
    }

    @Test
    fun legacyBooksFolder_migratesToSingleEntry() {
        prefs.edit().putString("books_folder", "/storage/1234-ABCD/MyBooks").apply()

        assertEquals(
            listOf(WatchFolder.FilePath("/storage/1234-ABCD/MyBooks")),
            PrefsWatchFolderStore(prefs, defaultFolder).getWatchFolders()
        )
    }

    @Test
    fun legacyBooksFolder_blank_isIgnored() {
        prefs.edit().putString("books_folder", "  ").apply()

        assertEquals(listOf(defaultFolder), store.getWatchFolders())
    }

    @Test
    fun explicitList_winsOverLegacy() {
        prefs.edit().putString("books_folder", "/storage/1234-ABCD/MyBooks").apply()
        store.setWatchFolders(listOf(treeFolder))

        assertEquals(listOf(treeFolder), store.getWatchFolders())
    }
}
