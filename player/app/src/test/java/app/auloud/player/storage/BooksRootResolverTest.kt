package app.auloud.player.storage

import org.junit.Assert.assertEquals
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
}
