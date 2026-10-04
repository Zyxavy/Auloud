package app.auloud.player.storage

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP3/WP5 refinement: [SafBundleStorage] token logic verifies on plain JVM
 * against an in-memory [SafBackend] fake — bundle listing filters to dirs
 * with `manifest.json`, text/exists delegate through tokens, audio resolves
 * to the backend document URI, and `..` escapes are rejected like the file
 * branch. `Uri.parse` is stubbed exactly like `FileBundleStorageTest` does
 * for `Uri.fromFile`; no framework code runs.
 */
class SafBundleStorageTest {

    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"

    private lateinit var backend: FakeSafBackend
    private lateinit var storage: SafBundleStorage

    @Before
    fun setUp() {
        backend = FakeSafBackend()
        storage = SafBundleStorage(tree, backend)
    }

    @Test
    fun listBundleDirs_listsOnlyDirsWithManifest_sorted() {
        backend.dirs = listOf("book-b", "book-a", "not-a-bundle", "empty.txt")
        backend.isDir = setOf("book-b", "book-a", "not-a-bundle")
        backend.files = setOf("book-b/manifest.json", "book-a/manifest.json")

        assertEquals(
            listOf("$tree|book-a", "$tree|book-b"),
            storage.listBundleDirs(tree)
        )
    }

    @Test
    fun listBundleDirs_otherTree_returnsEmpty() {
        backend.dirs = listOf("book-a")

        assertTrue(
            storage.listBundleDirs("$tree%2FBooks").isEmpty()
        )
    }

    @Test
    fun listBundleDirs_listingFailure_propagates() {
        backend.listFailure = IOException("$tree: permission lost")
        try {
            storage.listBundleDirs(tree)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("permission lost"))
        }
    }

    @Test
    fun readText_returnsBackendContent() {
        backend.files = setOf("book1/manifest.json")
        backend.texts = mapOf("book1/manifest.json" to """{"id":"x"}""")

        assertEquals("""{"id":"x"}""", storage.readText("$tree|book1/manifest.json"))
    }

    @Test(expected = IOException::class)
    fun readText_missing_throws() {
        storage.readText("$tree|book1/manifest.json")
    }

    @Test(expected = IOException::class)
    fun readText_wrongTree_throws() {
        storage.readText("$tree%2FBooks|book1/manifest.json")
    }

    @Test
    fun exists_trueFalseAndWrongTree() {
        backend.files = setOf("book1/manifest.json")

        assertTrue(storage.exists("$tree|book1/manifest.json"))
        assertFalse(storage.exists("$tree|book1/audio/ch001.mp3"))
        assertFalse(storage.exists("$tree%2FBooks|book1/manifest.json"))
        assertFalse(storage.exists("/storage/emulated/0/Auloud/book1"))
    }

    @Test
    fun readText_dotDotEscape_rejected() {
        try {
            storage.readText("$tree|book1/../../evil.txt")
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("escapes watch folder"))
        }
    }

    @Test
    fun exists_dotDotEscape_isFalse() {
        backend.files = setOf("evil.txt")

        assertFalse(storage.exists("$tree|book1/../../evil.txt"))
    }

    @Test
    fun audioUri_returnsBackendDocumentUri() {
        backend.files = setOf("book1/audio/ch001.mp3")
        mockkStatic(Uri::class)
        try {
            val fake = mockk<Uri>()
            every { Uri.parse("doc://primary:Auloud/book1/audio/ch001.mp3") } returns fake

            val result = storage.audioUri("$tree|book1", "audio/ch001.mp3")

            assertEquals(fake, result)
        } finally {
            unmockkStatic(Uri::class)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun audioUri_dotDotEscape_throws() {
        storage.audioUri("$tree|book1", "../evil.mp3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun audioUri_blank_throws() {
        storage.audioUri("$tree|book1", "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun audioUri_fileBundleDir_throws() {
        storage.audioUri("/storage/emulated/0/Auloud/book1", "audio/ch001.mp3")
    }

    @Test
    fun coverUri_returnsBackendDocumentUri() {
        backend.files = setOf("book1/cover.jpg")

        assertEquals(
            "doc://primary:Auloud/book1/cover.jpg",
            storage.coverUri("$tree|book1", "cover.jpg")
        )
    }

    @Test
    fun coverUri_wrongTree_returnsNull() {
        assertEquals(
            null,
            storage.coverUri("$tree%2FBooks|book1", "cover.jpg")
        )
    }

    @Test
    fun coverUri_dotDotEscape_returnsNull() {
        assertEquals(null, storage.coverUri("$tree|book1", "../evil.jpg"))
    }

    @Test
    fun coverUri_blank_returnsNull() {
        assertEquals(null, storage.coverUri("$tree|book1", "  "))
    }

    /** In-memory [SafBackend]: dirs, files, and texts keyed by tree-rel. */
    private class FakeSafBackend : SafBackend {
        var dirs: List<String> = emptyList()
        var isDir: Set<String> = emptySet()
        var files: Set<String> = emptySet()
        var texts: Map<String, String> = emptyMap()
        var listFailure: IOException? = null

        override fun children(parentRel: String): List<SafChild> {
            listFailure?.let { throw it }
            if (parentRel.isNotBlank()) return emptyList()
            return dirs.map { SafChild(it, it in isDir) }
        }

        override fun exists(relPath: String): Boolean = relPath in files

        override fun readText(relPath: String): String =
            texts[relPath] ?: throw IOException("$relPath: cannot open document")

        override fun documentUri(relPath: String): String =
            "doc://primary:Auloud/$relPath"
    }
}
