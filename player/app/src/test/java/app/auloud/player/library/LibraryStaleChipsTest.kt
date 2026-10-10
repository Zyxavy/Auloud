package app.auloud.player.library

import android.net.Uri
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.BookEntity
import app.auloud.player.data.DataError
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.render.staleChipText
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.WatchFolder
import app.auloud.player.storage.WatchFolderStore
import app.auloud.player.storage.WatchFolders
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * VS5: library stale chips on plain JVM via fakes (no Robolectric).
 *
 * The rescan fills the stale map through the injected [staleReader]
 * (parsed manifest plus bundle dir, stale count or null when unknown);
 * the row shows the stale chip next to the render chip (see
 * [staleChipText]). A throwing reader reads as no chip and never fails
 * the rescan. The scan itself is covered in `StaleBookScanTest`; here
 * only the seam is faked.
 */
class LibraryStaleChipsTest {

    private val root = "/books"
    private val bookDir = "$root/complete"

    private lateinit var storage: FakeStorage
    private lateinit var folderStore: FakeFolderStore
    private lateinit var libraryRepo: FakeLibraryRepo
    private lateinit var progressRepo: FakeProgressRepo
    private lateinit var scope: CoroutineScope
    private var stale: MutableMap<String, Int?> = mutableMapOf()
    private var throwOnRead = false

    @Before
    fun setUp() {
        storage = FakeStorage()
        folderStore = FakeFolderStore(listOf(WatchFolder.FilePath(root)))
        libraryRepo = FakeLibraryRepo()
        progressRepo = FakeProgressRepo()
        stale = mutableMapOf()
        throwOnRead = false
        scope = CoroutineScope(Dispatchers.Unconfined)
        storage.dirs = listOf(bookDir)
        storage.texts = mapOf(
            "$bookDir/manifest.json" to manifestComplete(),
            "$bookDir/text/ch001.json" to chapterTimed(1),
            "$bookDir/text/ch002.json" to chapterTimed(2)
        )
        storage.existing = setOf(
            "$bookDir/manifest.json",
            "$bookDir/audio/ch001.m4a",
            "$bookDir/audio/ch002.m4a",
            "$bookDir/text/ch001.json",
            "$bookDir/text/ch002.json"
        )
    }

    private fun viewModel() = LibraryViewModel(
        storage = storage,
        watchFolderStore = folderStore,
        libraryRepository = libraryRepo,
        progressRepository = progressRepo,
        isPermissionGranted = { true },
        ioDispatcher = Dispatchers.Unconfined,
        externalScope = scope,
        staleReader = { _, dir ->
            if (throwOnRead) throw IOException("$dir: cannot scan staleness")
            if (stale.containsKey(dir)) stale[dir] else 0
        }
    )

    private fun viewModelWithoutReader() = LibraryViewModel(
        storage = storage,
        watchFolderStore = folderStore,
        libraryRepository = libraryRepo,
        progressRepository = progressRepo,
        isPermissionGranted = { true },
        ioDispatcher = Dispatchers.Unconfined,
        externalScope = scope
    )

    @Test
    fun rescan_staleReader_setsCountAndChip() = runBlocking {
        stale[bookDir] = 2

        val vm = viewModel()
        val book = vm.uiState.value.books.single()

        // Complete books show no render chip, but the stale chip shows.
        assertEquals("complete", book.renderState)
        assertNull(book.renderJob)
        assertEquals(2, book.staleChapters)
        assertEquals("2 chapters need re-render", staleChipText(book.staleChapters))
    }

    @Test
    fun rescan_unknownStale_showsNoChip() = runBlocking {
        stale[bookDir] = null

        val vm = viewModel()
        val book = vm.uiState.value.books.single()

        assertEquals(0, book.staleChapters)
        assertNull(staleChipText(book.staleChapters))
    }

    @Test
    fun rescan_noReader_defaultsToZero() = runBlocking {
        val vm = viewModelWithoutReader()
        val book = vm.uiState.value.books.single()

        assertEquals(0, book.staleChapters)
        assertNull(staleChipText(book.staleChapters))
    }

    @Test
    fun rescan_throwingReader_neverFailsRescan() = runBlocking {
        throwOnRead = true

        val vm = viewModel()
        val state = vm.uiState.value

        assertTrue("book must still import, got: ${state.errors}", state.errors.isEmpty())
        assertEquals(0, state.books.single().staleChapters)
    }

    @Test
    fun rescan_replacesStaleMapWholesale() = runBlocking {
        stale[bookDir] = 1
        val vm = viewModel()
        assertEquals(1, vm.uiState.value.books.single().staleChapters)

        stale[bookDir] = 0
        vm.rescan()
        val book = vm.uiState.value.books.single()
        assertEquals(0, book.staleChapters)
        assertNull(staleChipText(book.staleChapters))
    }

    private fun manifestComplete(): String = """
        {
          "spec_version": "2.0",
          "id": "book-c",
          "title": "Complete Book",
          "author": "A. Author",
          "type": "epub",
          "render_state": "complete",
          "audio": {"format": "m4a"},
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.m4a",
             "text": "text/ch001.json", "duration_ms": 1000,
             "render_fingerprint": {"engine": "system",
               "voices": {"dialogue": "system:d", "narrator": "system:n"},
               "speeds": {"dialogue": 1.0, "narrator": 1.0},
               "engine_versions": {"system": "v1"}}},
            {"index": 2, "title": "Ch 2", "audio": "audio/ch002.m4a",
             "text": "text/ch002.json", "duration_ms": 1000,
             "render_fingerprint": {"engine": "system",
               "voices": {"dialogue": "system:d", "narrator": "system:n"},
               "speeds": {"dialogue": 1.0, "narrator": 1.0},
               "engine_versions": {"system": "v1"}}}
          ]
        }
        """.trimIndent()

    private fun chapterTimed(chapter: Int): String =
        """{"spec_version":"2.0","chapter":$chapter,"title":"Ch","duration_ms":1000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"Hi. "}]}]}"""

    private class FakeStorage : BundleStorage {
        var dirs: List<String> = emptyList()
        var texts: Map<String, String> = emptyMap()
        var existing: Set<String> = emptySet()

        override fun listBundleDirs(root: String): List<String> = dirs
        override fun readText(path: String): String =
            texts[path] ?: throw IOException("$path: file not found or not readable")
        override fun exists(path: String): Boolean = path in existing
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the library")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
    }

    private class FakeFolderStore(private val initial: List<WatchFolder>) : WatchFolderStore {
        private val folders = initial.toMutableList()
        override fun getWatchFolders(): List<WatchFolder> = folders.toList()
        override fun setWatchFolders(newFolders: List<WatchFolder>) {
            folders.clear()
            folders.addAll(newFolders)
        }
        override fun addFolder(folder: WatchFolder) {
            if (folders.none { WatchFolders.same(it, folder) }) folders.add(folder)
        }
        override fun removeFolder(folder: WatchFolder) {
            folders.removeAll { WatchFolders.same(it, folder) }
        }
    }

    private class FakeLibraryRepo : LibraryRepository {
        private val rows = MutableStateFlow<List<BookEntity>>(emptyList())

        override fun books(): Flow<List<BookEntity>> = rows

        override suspend fun importBundle(bundleDir: String, manifest: Manifest): Result<BookEntity> {
            val book = BookEntity(
                id = manifest.id,
                title = manifest.title,
                author = manifest.author,
                bundlePath = bundleDir,
                coverPath = null,
                durationMs = manifest.chapters.sumOf { it.durationMs ?: 0L },
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

        override suspend fun deleteBook(bookId: String): Result<Unit> {
            val existing = rows.value.firstOrNull { it.id == bookId }
                ?: return Result.failure(
                    DataError.InvalidBundle("$bookId: book not in library (nothing to delete)")
                )
            rows.value = rows.value.filterNot { it.id == bookId }
            return Result.success(Unit)
        }
    }

    private class FakeProgressRepo : ProgressRepository {
        override suspend fun save(
            bookId: String,
            chapterIndex: Int,
            positionMs: Long,
            sentenceSid: Int?
        ): Result<Unit> = Result.success(Unit)
        override suspend fun load(bookId: String): Result<ProgressEntity?> = Result.success(null)
        override suspend fun delete(bookId: String): Result<Unit> = Result.success(Unit)
    }
}
