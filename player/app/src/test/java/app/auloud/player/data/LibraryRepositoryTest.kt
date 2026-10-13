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
    private lateinit var progressDao: FakeProgressDao
    private var txRuns = 0
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
        progressDao = FakeProgressDao()
        txRuns = 0
        repo = RoomLibraryRepository(dao, storage, now = { clockMs })
    }

    private fun repoWithProgress(): LibraryRepository = RoomLibraryRepository(
        dao, storage, now = { clockMs },
        progressDao = progressDao,
        inTransaction = { block -> txRuns += 1; block() }
    )

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
    fun importBundle_safCover_resolvesToContentUri() = runBlocking {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        val safDir = "$tree|saf-book"
        val safStorage = object : BundleStorage by FakeBundleStorage(
            existing = setOf("$safDir/manifest.json", "$safDir/cover.jpg")
        ) {
            override fun coverUri(bundleDirPath: String, coverRel: String): String? =
                "content://com.android.externalstorage.documents/document/primary%3AAuloud%2Fsaf-book%2Fcover.jpg"
        }
        repo = RoomLibraryRepository(dao, safStorage, now = { clockMs })

        val book = repo.importBundle(safDir, manifest()).getOrThrow()

        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3AAuloud%2Fsaf-book%2Fcover.jpg",
            book.coverPath
        )
    }

    @Test
    fun importBundle_coverEscape_storesNull() = runBlocking {
        val escaping = object : BundleStorage by FakeBundleStorage(
            existing = setOf("$bundleDir/manifest.json", "$bundleDir/cover.jpg")
        ) {
            override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
        }
        repo = RoomLibraryRepository(dao, escaping, now = { clockMs })

        val book = repo.importBundle(bundleDir, manifest()).getOrThrow()

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
    fun refreshMissing_storedTrailingSlash_matchesListedPath() = runBlocking {
        repo.importBundle("$bundleDir/", manifest()).getOrThrow()

        repo.refreshMissing(listOf(bundleDir)).getOrThrow()

        val books = repo.books().first()
        assertEquals(1, books.size)
        assertFalse(books.single().isMissing)
    }

    @Test
    fun refreshMissing_listedTrailingSlash_matchesStoredPath() = runBlocking {
        repo.importBundle(bundleDir, manifest()).getOrThrow()

        repo.refreshMissing(listOf("$bundleDir/")).getOrThrow()

        val books = repo.books().first()
        assertEquals(1, books.size)
        assertFalse(books.single().isMissing)
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

    @Test
    fun deleteBook_removesRowAndFolder() = runBlocking {
        repo.importBundle(bundleDir, manifest()).getOrThrow()

        repo.deleteBook("8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77").getOrThrow()

        assertTrue(repo.books().first().isEmpty())
        assertEquals(listOf(bundleDir), storage.deletedPaths)
    }

    @Test
    fun deleteBook_unknownId_failsNamingRule() = runBlocking {
        repo.importBundle(bundleDir, manifest()).getOrThrow()

        val result = repo.deleteBook("no-such-book")

        assertTrue(result.isFailure)
        assertTrue(
            "failure names the rule, was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("nothing to delete") == true
        )
        assertEquals(1, repo.books().first().size)
    }

    @Test
    fun deleteBook_failedFolderDelete_keepsRow() = runBlocking {
        repo.importBundle(bundleDir, manifest()).getOrThrow()
        storage.deleteFails = true

        val result = repo.deleteBook("8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77")

        assertTrue(result.isFailure)
        assertTrue(
            "failure names the rule, was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("could not delete book folder") == true
        )
        assertEquals(1, repo.books().first().size)
    }

    @Test
    fun deleteBook_removesProgressInOneTransaction() = runBlocking {
        val repo = repoWithProgress()
        repo.importBundle(bundleDir, manifest()).getOrThrow()
        progressDao.rows["8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77"] =
            ProgressEntity("8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77", 1, 500L, 1_000L)

        repo.deleteBook("8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77").getOrThrow()

        assertTrue(repo.books().first().isEmpty())
        assertTrue(progressDao.rows.isEmpty())
        assertEquals(1, txRuns)
    }

    @Test
    fun deleteBook_progressFailure_surfacesFailure() = runBlocking {
        val repo = repoWithProgress()
        repo.importBundle(bundleDir, manifest()).getOrThrow()
        progressDao.fail = true

        val result = repo.deleteBook("8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77")

        assertTrue(result.isFailure)
    }

    @Test
    fun importBundle_sameIdDifferentFolder_skipsAndReports() = runBlocking {
        val otherDir = "/storage/1234-ABCD/Auloud/other-book"
        storage = FakeBundleStorage(
            existing = setOf(
                "$bundleDir/manifest.json",
                "$bundleDir/cover.jpg",
                "$otherDir/manifest.json",
                "$otherDir/cover.jpg"
            )
        )
        repo = RoomLibraryRepository(dao, storage, now = { clockMs })
        repo.importBundle(bundleDir, manifest()).getOrThrow()

        val result = repo.importBundle(otherDir, manifest())

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue("names the file, was: $message", "manifest.json" in message)
        assertTrue("names the id, was: $message", manifest().id in message)
        assertTrue("names the first folder, was: $message", bundleDir in message)
        // First import wins: the row still points at the first folder.
        assertEquals(bundleDir, repo.books().first().single().bundlePath)
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
            ChapterInfo(index = 1, title = "Chapter One", text = "text/ch001.json", audio = "audio/ch001.mp3", durationMs = 1832400L),
            ChapterInfo(index = 2, title = "Chapter Two", text = "text/ch002.json", audio = "audio/ch002.mp3", durationMs = 1640100L)
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

        override suspend fun deleteById(id: String) {
            if (rows.remove(id) != null) emit()
        }
    }

    /** Existence-check-only [BundleStorage]; `audioUri` is never used by repositories. */
    private class FakeBundleStorage(existing: Set<String>) : BundleStorage {
        private val remaining = existing.toMutableSet()
        val deletedPaths = mutableListOf<String>()
        var deleteFails = false

        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String = throw UnsupportedOperationException()
        override fun exists(path: String): Boolean = path in remaining
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by repositories")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? {
            if (coverRel.isBlank()) return null
            return bundleDirPath.trimEnd('/') + '/' + coverRel.trimStart('/')
        }
        override fun deleteRecursively(path: String) {
            deletedPaths.add(path)
            if (deleteFails) return
            val prefix = path.trimEnd('/') + '/'
            remaining.removeAll { it == path || it.startsWith(prefix) }
        }
    }

    /** In-memory [ProgressDao] with an injectable failure. */
    private class FakeProgressDao : ProgressDao {
        val rows = mutableMapOf<String, ProgressEntity>()
        var fail = false

        override suspend fun load(bookId: String): ProgressEntity? = rows[bookId]

        override suspend fun getAll(): List<ProgressEntity> = rows.values.toList()

        override suspend fun upsert(progress: ProgressEntity) {
            rows[progress.bookId] = progress
        }

        override suspend fun deleteById(bookId: String) {
            if (fail) throw java.io.IOException("fake db failure")
            rows.remove(bookId)
        }
    }
}
