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
}
