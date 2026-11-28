package app.auloud.player.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WP3: the books-folder default is SD-first, internal fallback (pure function). */
class BooksRootResolverTest {

    @Test
    fun picksSdWhenPresent() {
        assertEquals(
            "/storage/1234-ABCD/Auloud",
            BooksRootResolver.resolve("/storage/1234-ABCD", "/data/data/app.auloud.player/files")
        )
    }

    @Test
    fun fallsBackToInternalWhenSdNull() {
        assertEquals(
            "/data/data/app.auloud.player/files/Auloud",
            BooksRootResolver.resolve(null, "/data/data/app.auloud.player/files")
        )
    }

    @Test
    fun fallsBackToInternalWhenSdBlank() {
        assertEquals(
            "/data/data/app.auloud.player/files/Auloud",
            BooksRootResolver.resolve("  ", "/data/data/app.auloud.player/files")
        )
    }

    @Test
    fun trimsTrailingSlash() {
        assertEquals(
            "/storage/1234-ABCD/Auloud",
            BooksRootResolver.resolve("/storage/1234-ABCD/", "/data/data/app.auloud.player/files/")
        )
    }

    /**
     * WP3/WP5 refinement: the live default is shared-internal `/Auloud`
     * (SD-first [resolve] above is retained legacy-only).
     */
    @Test
    fun internalSharedDefault_appendsAuloud() {
        assertEquals(
            "/storage/emulated/0/Auloud",
            BooksRootResolver.internalSharedDefault("/storage/emulated/0")
        )
    }

    @Test
    fun internalSharedDefault_trimsTrailingSlash() {
        assertEquals(
            "/storage/emulated/0/Auloud",
            BooksRootResolver.internalSharedDefault("/storage/emulated/0/")
        )
    }

    /** D-214: API 24 to 29 keep the shared `/storage/emulated/0/Auloud` default. */
    @Test
    fun defaultRoot_api29_keepsSharedAuloud() {
        assertEquals(
            "/storage/emulated/0/Auloud",
            BooksRootResolver.defaultBooksRootFor(
                29,
                "/storage/emulated/0",
                "/storage/emulated/0/Android/data/app.auloud.player/files",
                "/data/x"
            )
        )
    }

    /** D-214: API 30+ uses the app-specific external dir (no permission needed). */
    @Test
    fun defaultRoot_api30_usesAppExternalFiles() {
        assertEquals(
            "/storage/emulated/0/Android/data/app.auloud.player/files/Auloud",
            BooksRootResolver.defaultBooksRootFor(
                30,
                "/storage/emulated/0",
                "/storage/emulated/0/Android/data/app.auloud.player/files",
                "/data/x"
            )
        )
    }

    @Test
    fun defaultRoot_api30_noExternal_usesFilesDir() {
        assertEquals(
            "/data/x/Auloud",
            BooksRootResolver.defaultBooksRootFor(30, null, null, "/data/x")
        )
    }

    @Test
    fun defaultRoot_api24_noShared_usesFilesDir() {
        assertEquals(
            "/data/x/Auloud",
            BooksRootResolver.defaultBooksRootFor(24, null, null, "/data/x")
        )
    }

    /**
     * FU3 (#32): API 30+ cannot read the old shared `/storage/emulated/0/Auloud`
     * without a SAF grant, so a saved copy of it needs the user to pick again.
     */
    @Test
    fun legacySharedFolderNeedsPick_api29_false() {
        assertFalse(
            BooksRootResolver.legacySharedFolderNeedsPick(
                29,
                WatchFolder.FilePath("/storage/emulated/0/Auloud"),
                "/storage/emulated/0/Android/data/app.auloud.player/files/Auloud"
            )
        )
    }

    @Test
    fun legacySharedFolderNeedsPick_api30_sharedPath_true() {
        assertTrue(
            BooksRootResolver.legacySharedFolderNeedsPick(
                30,
                WatchFolder.FilePath("/storage/emulated/0/Auloud/"),
                "/storage/emulated/0/Android/data/app.auloud.player/files/Auloud"
            )
        )
    }

    @Test
    fun legacySharedFolderNeedsPick_api30_appDefault_false() {
        val appDefault = "/storage/emulated/0/Android/data/app.auloud.player/files/Auloud"
        assertFalse(
            BooksRootResolver.legacySharedFolderNeedsPick(
                30,
                WatchFolder.FilePath(appDefault),
                appDefault
            )
        )
    }

    @Test
    fun legacySharedFolderNeedsPick_api30_safEntry_false() {
        assertFalse(
            BooksRootResolver.legacySharedFolderNeedsPick(
                30,
                WatchFolder.TreeUri(
                    "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
                ),
                "/storage/emulated/0/Android/data/app.auloud.player/files/Auloud"
            )
        )
    }
}
