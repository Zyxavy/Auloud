package app.auloud.player.storage

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * WP3: books-folder setting verifies — unset returns the resolver default,
 * set persists a custom value, clear falls back to the default. Runs on plain
 * JVM via [FakeSharedPreferences]; no Robolectric.
 */
class BooksFolderStoreTest {

    private val defaultRoot = "/storage/1234-ABCD/Auloud"
    private lateinit var prefs: FakeSharedPreferences
    private lateinit var store: BooksFolderStore

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        store = PrefsBooksFolderStore(prefs, defaultRoot)
    }

    @Test
    fun unset_returnsResolverDefault() {
        assertEquals(defaultRoot, store.getBooksFolder())
    }

    @Test
    fun set_persistsAndReturnsCustomValue() {
        store.setBooksFolder("/storage/1234-ABCD/MyBooks")

        assertEquals("/storage/1234-ABCD/MyBooks", store.getBooksFolder())
        // A fresh store over the same prefs sees the persisted value.
        assertEquals(
            "/storage/1234-ABCD/MyBooks",
            PrefsBooksFolderStore(prefs, defaultRoot).getBooksFolder()
        )
    }

    @Test
    fun clear_fallsBackToDefault() {
        store.setBooksFolder("/storage/1234-ABCD/MyBooks")
        store.clearBooksFolder()

        assertEquals(defaultRoot, store.getBooksFolder())
    }

    @Test
    fun blankStored_fallsBackToDefault() {
        store.setBooksFolder("  ")

        assertEquals(defaultRoot, store.getBooksFolder())
    }
}
