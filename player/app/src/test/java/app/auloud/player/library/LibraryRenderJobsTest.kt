package app.auloud.player.library

import android.net.Uri
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.BookEntity
import app.auloud.player.data.DataError
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.render.RenderJobProgress
import app.auloud.player.render.RenderJobState
import app.auloud.player.render.renderChipText
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
 * RN9: library render-job chips on plain JVM via fakes (no Robolectric).
 *
 * The rescan fills the job map through the injected [renderJobReader];
 * the chip prefers it over `render_state` (see [renderChipText]). A
 * throwing reader reads as no job and never fails the rescan.
 */
class LibraryRenderJobsTest {

    private val root = "/books"
    private val bookDir = "$root/partial"

    private lateinit var storage: FakeStorage
    private lateinit var folderStore: FakeFolderStore
    private lateinit var libraryRepo: FakeLibraryRepo
    private lateinit var progressRepo: FakeProgressRepo
    private lateinit var scope: CoroutineScope
    private var jobs: MutableMap<String, RenderJobProgress> = mutableMapOf()
    private var throwOnRead = false

    @Before
    fun setUp() {
        storage = FakeStorage()
        folderStore = FakeFolderStore(listOf(WatchFolder.FilePath(root)))
        libraryRepo = FakeLibraryRepo()
        progressRepo = FakeProgressRepo()
        jobs = mutableMapOf()
        throwOnRead = false
        scope = CoroutineScope(Dispatchers.Unconfined)
        storage.dirs = listOf(bookDir)
        storage.texts = mapOf(
            "$bookDir/manifest.json" to manifestPartial(),
            "$bookDir/text/ch001.json" to chapterTimed(),
            "$bookDir/text/ch002.json" to chapterUntimed()
        )
        storage.existing = setOf(
            "$bookDir/manifest.json",
            "$bookDir/audio/ch001.mp3",
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
        renderJobReader = { dir ->
            if (throwOnRead) throw IOException("$dir: cannot read render state")
            jobs[dir]
        }
    )

    @Test
    fun rescan_runningJob_setsProgressAndChip() = runBlocking {
        jobs[bookDir] = RenderJobProgress(done = 1, total = 2, state = RenderJobState.RUNNING)

        val vm = viewModel()
        val book = vm.uiState.value.books.single()

        assertEquals("partial", book.renderState)
        assertEquals(RenderJobProgress(1, 2, RenderJobState.RUNNING), book.renderJob)
        assertEquals("Rendering 50%", renderChipText(book.renderState, book.renderJob))
    }

    @Test
    fun rescan_pausedJob_setsPausedChip() = runBlocking {
        jobs[bookDir] = RenderJobProgress(done = 0, total = 4, state = RenderJobState.PAUSED)

        val vm = viewModel()
        val book = vm.uiState.value.books.single()

        assertEquals("Paused at 0%", renderChipText(book.renderState, book.renderJob))
    }

    @Test
    fun rescan_noJob_fallsBackToRenderState() = runBlocking {
        val vm = viewModel()
        val book = vm.uiState.value.books.single()

        assertNull(book.renderJob)
        assertEquals("Partially rendered", renderChipText(book.renderState, book.renderJob))
    }

    @Test
    fun rescan_throwingReader_neverFailsRescan() = runBlocking {
        throwOnRead = true

        val vm = viewModel()
        val state = vm.uiState.value

        assertTrue("book must still import, got: ${state.errors}", state.errors.isEmpty())
        assertNull(state.books.single().renderJob)
    }

    @Test
    fun rescan_replacesJobMapWholesale() = runBlocking {
        jobs[bookDir] = RenderJobProgress(done = 1, total = 2, state = RenderJobState.RUNNING)
        val vm = viewModel()
        assertEquals("Rendering 50%", renderChipText("partial", vm.uiState.value.books.single().renderJob))

        jobs.remove(bookDir)
        vm.rescan()
        assertNull(vm.uiState.value.books.single().renderJob)
        assertEquals(
            "Partially rendered",
            renderChipText("partial", vm.uiState.value.books.single().renderJob)
        )
    }

    private fun manifestPartial(): String = """
        {
          "spec_version": "2.0",
          "id": "book-p",
          "title": "Partial Book",
          "author": "A. Author",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "mp3"},
          "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0}
          },
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.mp3",
             "text": "text/ch001.json", "duration_ms": 1000},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun chapterTimed(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch","duration_ms":1000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"Hi. "}]}]}"""

    private fun chapterUntimed(): String =
        """{"spec_version":"2.0","chapter":2,"title":"Ch","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hi. "}]}]}"""

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
        override suspend fun loadAll(): Result<Map<String, Long>> = Result.success(emptyMap())
        override suspend fun delete(bookId: String): Result<Unit> = Result.success(Unit)
    }
}
