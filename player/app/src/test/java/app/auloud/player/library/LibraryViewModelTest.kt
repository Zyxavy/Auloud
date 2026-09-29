package app.auloud.player.library

import android.net.Uri
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.BookEntity
import app.auloud.player.data.DataError
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.storage.BooksFolderStore
import app.auloud.player.storage.BundleStorage
import app.cash.turbine.test
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WP5: [LibraryViewModel] on plain JVM via fakes (no Robolectric): rescan
 * imports valid bundles, invalid bundles yield error state with the WP2
 * file+rule reason, an empty folder yields empty state, and the saved
 * position maps to the expected progress fraction. Turbine observes
 * [LibraryViewModel.uiState].
 */
class LibraryViewModelTest {

    private val root = "/books"
    private val novelDir = "$root/example-novel"
    private val manifestPath = "$novelDir/manifest.json"

    private lateinit var storage: FakeBundleStorage
    private lateinit var folderStore: FakeBooksFolderStore
    private lateinit var libraryRepo: FakeLibraryRepository
    private lateinit var progressRepo: FakeProgressRepository
    private var permissionGranted = true
    private var permissionRequests = 0
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        storage = FakeBundleStorage()
        folderStore = FakeBooksFolderStore(root)
        libraryRepo = FakeLibraryRepository()
        progressRepo = FakeProgressRepository()
        permissionGranted = true
        permissionRequests = 0
        // Unconfined: rescan runs synchronously, so tests can subscribe late
        // and still see the settled state. No `Dispatchers.Main` needed.
        scope = CoroutineScope(Dispatchers.Unconfined)
    }

    @Test
    fun rescan_importsValidBundle() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = mapOf(manifestPath to manifestJson())
        storage.existing = setOf(
            manifestPath,
            "$novelDir/audio/ch001.mp3",
            "$novelDir/audio/ch002.mp3"
        )

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.hasPermission)
            assertFalse(state.isScanning)
            assertEquals(1, state.books.size)
            assertEquals("book-1", state.books[0].id)
            assertEquals("Example Book", state.books[0].title)
            assertEquals("A. Author", state.books[0].author)
            assertTrue(state.errors.isEmpty())
        }
    }

    @Test
    fun rescan_invalidBundle_yieldsErrorWithReason_notCrash() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = mapOf(manifestPath to manifestJson())
        // ch002.mp3 absent: WP2 rule "audio file missing" must surface.
        storage.existing = setOf(manifestPath, "$novelDir/audio/ch001.mp3")

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.books.isEmpty())
            assertEquals(1, state.errors.size)
            assertEquals(novelDir, state.errors[0].bundleDir)
            assertTrue(
                "reason names file+rule, was: ${state.errors[0].reason}",
                state.errors[0].reason.contains("manifest.json") &&
                    state.errors[0].reason.contains("audio file missing audio/ch002.mp3")
            )
        }
    }

    @Test
    fun rescan_malformedManifest_yieldsErrorWithReason() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = mapOf(manifestPath to "{ not json")

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.books.isEmpty())
            assertEquals(1, state.errors.size)
            assertTrue(
                "reason names manifest.json, was: ${state.errors[0].reason}",
                state.errors[0].reason.contains("manifest.json")
            )
        }
    }

    @Test
    fun rescan_emptyFolder_yieldsEmptyState() = runBlocking {
        storage.dirs = emptyList()

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.hasPermission)
            assertFalse(state.isScanning)
            assertTrue(state.books.isEmpty())
            assertTrue(state.errors.isEmpty())
            assertNull(state.selectedBookId)
        }
    }

    @Test
    fun progressFraction_mapsSavedPositionOverTotal() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = mapOf(manifestPath to manifestJson(ch1Ms = 750L, ch2Ms = 250L))
        storage.existing = setOf(
            manifestPath,
            "$novelDir/audio/ch001.mp3",
            "$novelDir/audio/ch002.mp3"
        )
        progressRepo.positions["book-1"] = ProgressEntity("book-1", 0, 250L, 0L)

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertEquals(0.25f, state.books[0].progressFraction, 0.0001f)
        }
    }

    @Test
    fun progressFraction_noSavedPosition_isZero() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = mapOf(manifestPath to manifestJson())
        storage.existing = setOf(
            manifestPath,
            "$novelDir/audio/ch001.mp3",
            "$novelDir/audio/ch002.mp3"
        )

        val vm = viewModel()

        vm.uiState.test {
            assertEquals(0f, awaitItem().books.single().progressFraction, 0f)
        }
    }

    @Test
    fun noPermission_showsStateAndRetryRequestsIt() = runBlocking {
        permissionGranted = false

        val vm = viewModel()

        vm.uiState.test {
            assertFalse(awaitItem().hasPermission)
        }
        vm.onRetryPermission()
        assertEquals(1, permissionRequests)

        // Granting via the WP3 launcher result re-scans and fills the library.
        storage.dirs = listOf(novelDir)
        storage.texts = mapOf(manifestPath to manifestJson())
        storage.existing = setOf(
            manifestPath,
            "$novelDir/audio/ch001.mp3",
            "$novelDir/audio/ch002.mp3"
        )
        permissionGranted = true
        vm.onPermissionResult(true)

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.hasPermission)
            assertEquals(1, state.books.size)
        }
    }

    @Test
    fun selection_hook_setsAndClearsSelectedBook() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = mapOf(manifestPath to manifestJson())
        storage.existing = setOf(
            manifestPath,
            "$novelDir/audio/ch001.mp3",
            "$novelDir/audio/ch002.mp3"
        )

        val vm = viewModel()
        vm.onBookSelected("book-1")

        vm.uiState.test {
            assertEquals("book-1", awaitItem().selectedBookId)
        }
        vm.clearSelection()

        vm.uiState.test {
            assertNull(awaitItem().selectedBookId)
        }
    }

    private fun viewModel() = LibraryViewModel(
        storage = storage,
        booksFolderStore = folderStore,
        libraryRepository = libraryRepo,
        progressRepository = progressRepo,
        isPermissionGranted = { permissionGranted },
        requestPermission = { permissionRequests++ },
        ioDispatcher = Dispatchers.Unconfined,
        externalScope = scope
    )

    private fun manifestJson(
        id: String = "book-1",
        title: String = "Example Book",
        ch1Ms: Long = 600_000L,
        ch2Ms: Long = 400_000L
    ): String = """
        {
          "spec_version": "1.0",
          "id": "$id",
          "title": "$title",
          "author": "A. Author",
          "type": "epub",
          "cover": "cover.jpg",
          "audio": {},
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.mp3",
             "text": "text/ch001.json", "duration_ms": $ch1Ms},
            {"index": 2, "title": "Ch 2", "audio": "audio/ch002.mp3",
             "text": "text/ch002.json", "duration_ms": $ch2Ms}
          ]
        }
        """.trimIndent()

    private class FakeBundleStorage : BundleStorage {
        var dirs: List<String> = emptyList()
        var texts: Map<String, String> = emptyMap()
        var existing: Set<String> = emptySet()

        override fun listBundleDirs(root: String): List<String> = dirs
        override fun readText(path: String): String =
            texts[path] ?: throw IOException("$path: file not found or not readable")
        override fun exists(path: String): Boolean = path in existing
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the library")
    }

    private class FakeBooksFolderStore(private var folder: String) : BooksFolderStore {
        override fun getBooksFolder(): String = folder
        override fun setBooksFolder(path: String) { folder = path }
        override fun clearBooksFolder() { folder = "" }
    }

    /** In-memory [LibraryRepository] mirroring the upsert-by-id + missing-flag semantics. */
    private class FakeLibraryRepository : LibraryRepository {
        private val rows = MutableStateFlow<List<BookEntity>>(emptyList())

        override fun books(): Flow<List<BookEntity>> = rows

        override suspend fun importBundle(
            bundleDir: String,
            manifest: Manifest
        ): Result<BookEntity> {
            if (manifest.id.isBlank() || manifest.title.isBlank()) {
                return Result.failure(
                    DataError.InvalidBundle("manifest.json: missing required field id/title")
                )
            }
            val book = BookEntity(
                id = manifest.id,
                title = manifest.title,
                author = manifest.author,
                bundlePath = bundleDir,
                coverPath = null,
                durationMs = manifest.chapters.sumOf { it.durationMs },
                addedAt = 1_000L,
                isMissing = false
            )
            rows.value = rows.value.filterNot { it.id == book.id } + book
            return Result.success(book)
        }

        override suspend fun refreshMissing(presentBundleDirs: Collection<String>): Result<Unit> {
            val present = presentBundleDirs.map { it.trimEnd('/') }.toSet()
            rows.value = rows.value.map {
                it.copy(isMissing = it.bundlePath.trimEnd('/') !in present)
            }
            return Result.success(Unit)
        }
    }

    private class FakeProgressRepository : ProgressRepository {
        val positions = mutableMapOf<String, ProgressEntity>()

        override suspend fun save(
            bookId: String,
            chapterIndex: Int,
            positionMs: Long
        ): Result<Unit> {
            positions[bookId] = ProgressEntity(bookId, chapterIndex, positionMs, 0L)
            return Result.success(Unit)
        }

        override suspend fun load(bookId: String): Result<ProgressEntity?> =
            Result.success(positions[bookId])
    }
}
