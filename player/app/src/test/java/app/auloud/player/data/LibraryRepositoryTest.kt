package app.auloud.player.data

import android.net.Uri
import app.auloud.player.bundle.AudioInfo
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.storage.BundleStorage
import app.cash.turbine.test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP4: [RoomLibraryRepository] logic on plain JVM via fakes (no Robolectric):
 * upsert-by-id, field mapping, missing-not-deleted, `books()` flow.
 */
class LibraryRepositoryTest {

    private val bundleDir = "/storage/1234-ABCD/Auloud/example-novel"
    private var clockMs = 1_000L
    private lateinit var dao: FakeBookDao
    private lateinit var storage: FakeBundleStorage
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        dao = FakeBookDao()
        storage = FakeBundleStorage(
            existing = setOf(
                "$bundleDir/manifest.json",
                "$bundleDir/cover.jpg"
            )
        )
        repo = RoomLibraryRepository(dao, storage, now = { clockMs })
    }

    @Test
    fun importBundle_mapsManifestFields() = runBlocking {
        val book = repo.importBundle(bundleDir, manifest()).getOrThrow()

        assertEquals("8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77", book.id)
        assertEquals("Example Novel", book.title)
        assertEquals("A. Author", book.author)
        assertEquals(bundleDir, book.bundlePath)
        assertEquals("$bundleDir/cover.jpg", book.coverPath)
        assertEquals(1832400L + 1640100L, book.durationMs)
        assertEquals(1_000L, book.addedAt)
        assertFalse(book.isMissing)
    }

    @Test
    fun importBundle_twice_leavesOneRowAndKeepsAddedAt() = runBlocking {
        repo.importBundle(bundleDir, manifest()).getOrThrow()
        clockMs = 9_999L
        repo.importBundle(bundleDir, manifest(title = "Example Novel (revised)")).getOrThrow()

        val books = repo.books().first()
        assertEquals(1, books.size)
        assertEquals("Example Novel (revised)", books[0].title)
        assertEquals(1_000L, books[0].addedAt)
    }

    @Test
    fun importBundle_missingCover_coverPathNull() = runBlocking {
        storage = FakeBundleStorage(existing = setOf("$bundleDir/manifest.json"))
        repo = RoomLibraryRepository(dao, storage, now = { clockMs })

        val book = repo.importBundle(bundleDir, manifest()).getOrThrow()

        assertNull(book.coverPath)
    }

    @Test
    fun importBundle_noCoverInManifest_coverPathNull() = runBlocking {
        val book = repo.importBundle(bundleDir, manifest(cover = null)).getOrThrow()

        assertNull(book.coverPath)
    }

    @Test
    fun importBundle_missingManifest_failsInvalidBundle() = runBlocking {
        storage = FakeBundleStorage(existing = emptySet())
        repo = RoomLibraryRepository(dao, storage, now = { clockMs })

        val result = repo.importBundle(bundleDir, manifest())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataError.InvalidBundle)
        assertTrue(repo.books().first().isEmpty())
    }

    @Test
    fun importBundle_blankId_failsWithoutUpsert() = runBlocking {
        val result = repo.importBundle(bundleDir, manifest(id = "  "))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is DataError.InvalidBundle)
        assertTrue(repo.books().first().isEmpty())
    }

    @Test
    fun refreshMissing_marksGoneBookNotDeleted() = runBlocking {
        val otherDir = "/storage/1234-ABCD/Auloud/other-book"
        storage = FakeBundleStorage(
            existing = setOf("$bundleDir/manifest.json", "$otherDir/manifest.json")
        )
        repo = RoomLibraryRepository(dao, storage, now = { clockMs })
        repo.importBundle(bundleDir, manifest()).getOrThrow()
        repo.importBundle(otherDir, manifest(id = "other-id", title = "Other")).getOrThrow()

        repo.refreshMissing(listOf(bundleDir)).getOrThrow()

        val books = repo.books().first()
        assertEquals(2, books.size)
        assertFalse(books.single { it.id == manifest().id }.isMissing)
        assertTrue(books.single { it.id == "other-id" }.isMissing)
    }

    @Test
    fun reimport_clearsMissingFlag() = runBlocking {
        repo.importBundle(bundleDir, manifest()).getOrThrow()
        repo.refreshMissing(emptyList()).getOrThrow()
        assertTrue(repo.books().first().single().isMissing)

        val book = repo.importBundle(bundleDir, manifest()).getOrThrow()

        assertFalse(book.isMissing)
        assertFalse(repo.books().first().single().isMissing)
    }

    @Test
    fun books_emitsEmptyThenImportedRow() = runBlocking {
        repo.books().test {
            assertTrue(awaitItem().isEmpty())
            repo.importBundle(bundleDir, manifest()).getOrThrow()
            val books = awaitItem()
            assertEquals(1, books.size)
            assertEquals("Example Novel", books[0].title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun manifest(
        id: String = "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
        title: String = "Example Novel",
        cover: String? = "cover.jpg"
    ) = Manifest(
        specVersion = "1.0",
        id = id,
        title = title,
        type = "epub",
        audio = AudioInfo(),
        chapters = listOf(
            ChapterInfo(1, "Chapter One", "audio/ch001.mp3", "text/ch001.json", 1832400L),
            ChapterInfo(2, "Chapter Two", "audio/ch002.mp3", "text/ch002.json", 1640100L)
        ),
        author = "A. Author",
        cover = cover
    )

    /** In-memory [BookDao] mirroring the SQL ordering (newest `addedAt` first). */
    private class FakeBookDao : BookDao {
        private val rows = mutableMapOf<String, BookEntity>()
        private val flow = MutableStateFlow<List<BookEntity>>(emptyList())

        private fun emit() {
            flow.value = rows.values.sortedByDescending { it.addedAt }
        }

        override fun observeBooks(): Flow<List<BookEntity>> = flow

        override suspend fun getAll(): List<BookEntity> =
            rows.values.sortedByDescending { it.addedAt }

        override suspend fun getById(id: String): BookEntity? = rows[id]

        override suspend fun upsert(book: BookEntity) {
            rows[book.id] = book
            emit()
        }

        override suspend fun setMissing(id: String, missing: Boolean) {
            rows[id]?.let {
                rows[id] = it.copy(isMissing = missing)
                emit()
            }
        }
    }

    /** Existence-check-only [BundleStorage]; `audioUri` is never used by repositories. */
    private class FakeBundleStorage(private val existing: Set<String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String = throw UnsupportedOperationException()
        override fun exists(path: String): Boolean = path in existing
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by repositories")
    }
}
