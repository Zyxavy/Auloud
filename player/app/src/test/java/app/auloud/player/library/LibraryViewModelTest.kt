package app.auloud.player.library

import android.net.Uri
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.BookEntity
import app.auloud.player.data.DataError
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.WatchFolder
import app.auloud.player.storage.WatchFolderStore
import app.auloud.player.storage.WatchFolders
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
    private lateinit var folderStore: FakeWatchFolderStore
    private lateinit var libraryRepo: FakeLibraryRepository
    private lateinit var progressRepo: FakeProgressRepository
    private var permissionGranted = true
    private var permissionRequests = 0
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        storage = FakeBundleStorage()
        folderStore = FakeWatchFolderStore(listOf(WatchFolder.FilePath(root)))
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
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()

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
        storage.texts = validTexts(manifestJson())
        // ch002.mp3 absent: CP4 partial import — book still imports (ch1 fine)
        // with one chapter-scoped error naming file+rule.
        storage.existing = validExisting() - "$novelDir/audio/ch002.mp3"

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertEquals("book-1", state.books[0].id)
            assertEquals(1, state.errors.size)
            // File paths render in full; SAF tokens collapse to bundle names.
            assertEquals(novelDir, state.errors[0].bundleDir)
            assertTrue(
                "reason names file+rule, was: ${state.errors[0].reason}",
                state.errors[0].reason.contains("manifest.json") &&
                    state.errors[0].reason.contains("chapter 2") &&
                    state.errors[0].reason.contains("audio file missing audio/ch002.mp3")
            )
        }
    }

    @Test
    fun rescan_allChaptersBad_blocksImport() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = validTexts(manifestJson())
        // Both audio files absent: no good chapter left, import blocked as before.
        storage.existing = setOf(
            manifestPath,
            "$novelDir/text/ch001.json",
            "$novelDir/text/ch002.json"
        )

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.books.isEmpty())
            assertEquals(1, state.errors.size)
            assertEquals(novelDir, state.errors[0].bundleDir)
            assertTrue(state.errors[0].reason.contains("audio file missing"))
        }
    }

    @Test
    fun rescan_manifestProblem_blocksImport_despiteGoodChapters() = runBlocking {
        storage.dirs = listOf(novelDir)
        val bad = manifestJson(title = "  ")
        storage.texts = validTexts(bad)
        storage.existing = validExisting()

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.books.isEmpty())
            assertEquals(1, state.errors.size)
            assertTrue(state.errors[0].reason.contains("manifest.json"))
        }
    }

    @Test
    fun rescan_badTextFile_importsPartiallyWithChapterError() = runBlocking {
        storage.dirs = listOf(novelDir)
        val texts = validTexts(manifestJson()).toMutableMap()
        texts["$novelDir/text/ch002.json"] = "{ truncated"
        storage.texts = texts
        storage.existing = validExisting()

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertEquals(1, state.errors.size)
            assertEquals(novelDir, state.errors[0].bundleDir)
            assertTrue(
                "reason names chapter+file+rule, was: ${state.errors[0].reason}",
                state.errors[0].reason.contains("manifest.json") &&
                    state.errors[0].reason.contains("chapter 2") &&
                    state.errors[0].reason.contains("text/ch002.json")
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
        storage.texts = validTexts(manifestJson(ch1Ms = 750L, ch2Ms = 250L))
        storage.existing = validExisting()
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
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()

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
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()
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
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()

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

    @Test
    fun rescan_aggregatesFileAndSafFolders() = runBlocking {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        val safDir = "$tree|saf-book"
        folderStore.setWatchFolders(
            listOf(WatchFolder.FilePath(root), WatchFolder.TreeUri(tree))
        )
        val routing = FakeRoutingStorage()
        routing.file.dirsByRoot = mapOf(root to listOf(novelDir))
        routing.file.texts = mapOf(
            manifestPath to manifestJson(),
            "$novelDir/text/ch001.json" to chapterTextJson(),
            "$novelDir/text/ch002.json" to chapterTextJson()
        )
        routing.file.existing = setOf(
            manifestPath,
            "$novelDir/audio/ch001.mp3",
            "$novelDir/audio/ch002.mp3",
            "$novelDir/text/ch001.json",
            "$novelDir/text/ch002.json"
        )
        routing.saf = FakeSafBundleStorage(tree)
        routing.saf!!.names = listOf("saf-book")
        routing.saf!!.texts = mapOf(
            "saf-book/manifest.json" to manifestJson(id = "saf-1", title = "SAF Book"),
            "saf-book/text/ch001.json" to chapterTextJson(),
            "saf-book/text/ch002.json" to chapterTextJson()
        )
        routing.saf!!.existing = setOf(
            "saf-book/manifest.json",
            "saf-book/audio/ch001.mp3",
            "saf-book/audio/ch002.mp3",
            "saf-book/text/ch001.json",
            "saf-book/text/ch002.json"
        )

        val vm = viewModel(storage = routing)

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(2, state.books.size)
            assertEquals(
                listOf("Example Book", "SAF Book"),
                state.books.map { it.title }.sorted()
            )
            assertTrue(state.errors.isEmpty())
        }
    }

    @Test
    fun rescan_folderListFailure_surfacesDisplayLabel_notRawToken() = runBlocking {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        folderStore.setWatchFolders(
            listOf(WatchFolder.FilePath(root), WatchFolder.TreeUri(tree))
        )
        storage.dirs = listOf(novelDir)
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()
        storage.dirsByRoot = mapOf(root to listOf(novelDir))
        storage.failRoots = setOf(tree)

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertEquals(1, state.errors.size)
            // Decoded display label, never the raw tree URI/token.
            assertEquals("Auloud", state.errors[0].bundleDir)
            assertTrue(state.errors[0].reason.contains("cannot list books folder"))
            assertFalse(
                "no raw token in UI, was: ${state.errors[0]}",
                state.errors[0].toString().contains("content://")
            )
        }
    }

    @Test
    fun rescan_safBundleError_usesBundleName_notToken() = runBlocking {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AAuloud"
        val safDir = "$tree|saf-book"
        folderStore.setWatchFolders(listOf(WatchFolder.TreeUri(tree)))
        // Manifest text present but ch002.mp3 missing: CP4 partial import
        // must label the bundle by name, not by token.
        storage.dirsByRoot = mapOf(tree to listOf(safDir))
        storage.texts = mapOf(
            "$safDir/manifest.json" to manifestJson(id = "saf-1"),
            "$safDir/text/ch001.json" to chapterTextJson(),
            "$safDir/text/ch002.json" to chapterTextJson()
        )
        storage.existing = setOf(
            "$safDir/manifest.json",
            "$safDir/audio/ch001.mp3",
            "$safDir/text/ch001.json",
            "$safDir/text/ch002.json"
        )

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertEquals(1, state.errors.size)
            assertEquals("saf-book", state.errors[0].bundleDir)
            assertTrue(state.errors[0].reason.contains("audio file missing"))
        }
    }

    @Test
    fun addNotice_surfacesInErrors_withoutRescan() = runBlocking {
        storage.dirs = emptyList()

        val vm = viewModel()
        vm.addNotice("Auloud", "Auloud: folder permission was lost — please re-add it in Settings.")
        // Exact duplicates are ignored.
        vm.addNotice("Auloud", "Auloud: folder permission was lost — please re-add it in Settings.")

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.errors.size)
            assertEquals("Auloud", state.errors[0].bundleDir)
            assertTrue(state.errors[0].reason.contains("permission was lost"))
        }
    }

    @Test
    fun rescan_preservesNotices_alongsideScanFailures() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = validTexts(manifestJson())
        // Partially-invalid bundle (ch2 audio missing) AND a host notice:
        // book imports, both must be visible, notices first.
        storage.existing = validExisting() - "$novelDir/audio/ch002.mp3"

        val vm = viewModel()
        vm.addNotice("Book folders", "The folder picker is unavailable on this device.")

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertEquals(2, state.errors.size)
            assertEquals("Book folders", state.errors[0].bundleDir)
            assertEquals(novelDir, state.errors[1].bundleDir)
        }
    }

    @Test
    fun rescan_emptyWatchList_yieldsEmptyState() = runBlocking {
        folderStore.setWatchFolders(emptyList())

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.hasPermission)
            assertFalse(state.isScanning)
            assertTrue(state.books.isEmpty())
            assertTrue(state.errors.isEmpty())
        }
    }

    @Test
    fun rescan_sweepsStrayTempsBeforeListing() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()
        storage.strayDirs = listOf("$root/.tmp-deadbeef")

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertTrue(state.errors.isEmpty())
        }
        assertEquals(
            "stray temp must be swept",
            listOf("$root/.tmp-deadbeef"),
            storage.deletedPaths
        )
    }

    @Test
    fun rescan_sweepFailure_doesNotFailRescan() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()
        storage.failStrayRoots = setOf(root)

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertTrue(state.errors.isEmpty())
        }
        assertTrue(storage.deletedPaths.isEmpty())
    }

    @Test
    fun rescan_skipsStrayDirsFromListing() = runBlocking {
        val stray = "$root/.tmp-abc123"
        storage.dirs = listOf(novelDir, stray)
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertTrue("stray temp must never surface as an error", state.errors.isEmpty())
        }
    }

    @Test
    fun chip_unrenderedBook_showsNotRendered() = runBlocking {
        val v2dir = "$root/unrendered"
        storage.dirs = listOf(v2dir)
        storage.texts = mapOf(
            "$v2dir/manifest.json" to manifestV2("none"),
            "$v2dir/text/ch001.json" to chapterTextJsonV2(),
            "$v2dir/text/ch002.json" to chapterTextJsonV2()
        )
        storage.existing = setOf(
            "$v2dir/manifest.json",
            "$v2dir/text/ch001.json",
            "$v2dir/text/ch002.json"
        )

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.errors.isEmpty())
            val book = state.books.single()
            assertEquals("none", book.renderState)
            assertTrue(book.showNotRendered)
        }
    }

    @Test
    fun chip_partialBook_showsNotRendered() = runBlocking {
        val v2dir = "$root/partial"
        storage.dirs = listOf(v2dir)
        storage.texts = mapOf(
            "$v2dir/manifest.json" to manifestPartial(),
            "$v2dir/text/ch001.json" to chapterTextJsonV2Timed(),
            "$v2dir/text/ch002.json" to chapterTextJsonV2()
        )
        storage.existing = setOf(
            "$v2dir/manifest.json",
            "$v2dir/audio/ch001.mp3",
            "$v2dir/text/ch001.json",
            "$v2dir/text/ch002.json"
        )

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue("partial must import, got: ${state.errors}", state.errors.isEmpty())
            val book = state.books.single()
            assertEquals("partial", book.renderState)
            assertTrue(book.showNotRendered)
        }
    }

    @Test
    fun chip_renderedBooks_showNoChip() = runBlocking {
        val v2dir = "$root/complete"
        storage.dirs = listOf(novelDir, v2dir)
        val texts = validTexts(manifestJson()).toMutableMap()
        texts["$v2dir/manifest.json"] = manifestV2Complete()
        texts["$v2dir/text/ch001.json"] = chapterTextJsonV2Timed()
        texts["$v2dir/text/ch002.json"] = chapterTextJsonV2Timed()
        storage.texts = texts
        storage.existing = validExisting() + setOf(
            "$v2dir/manifest.json",
            "$v2dir/audio/ch001.mp3",
            "$v2dir/audio/ch002.mp3",
            "$v2dir/text/ch001.json",
            "$v2dir/text/ch002.json"
        )

        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem()
            assertTrue("both books must import, got: ${state.errors}", state.errors.isEmpty())
            assertEquals(2, state.books.size)
            for (book in state.books) {
                assertFalse("no chip for ${book.id} (${book.renderState})", book.showNotRendered)
            }
        }
    }

    @Test
    fun chip_rescanRefreshesMapAfterRenderRewrite() = runBlocking {
        // RN7 (#14 item 5): Slice 10 rewrites render_state under the
        // rescan; the in-memory chip map is replaced wholesale by every
        // rescan, so the chip must follow without any extra invalidation.
        val v2dir = "$root/rerender"
        storage.dirs = listOf(v2dir)
        storage.texts = mapOf(
            "$v2dir/manifest.json" to manifestV2("none", id = "book-r"),
            "$v2dir/text/ch001.json" to chapterTextJsonV2(),
            "$v2dir/text/ch002.json" to chapterTextJsonV2()
        )
        storage.existing = setOf(
            "$v2dir/manifest.json",
            "$v2dir/text/ch001.json",
            "$v2dir/text/ch002.json"
        )

        val vm = viewModel()
        assertEquals("none", vm.uiState.value.books.single().renderState)

        // A render finishes chapter 1: manifest rewritten plus audio and
        // timings land, exactly as the RN7 finalize writes them.
        storage.texts = mapOf(
            "$v2dir/manifest.json" to manifestV2FirstRendered("book-r"),
            "$v2dir/text/ch001.json" to chapterTextJsonV2Timed(),
            "$v2dir/text/ch002.json" to chapterTextJsonV2()
        )
        storage.existing = storage.existing + "$v2dir/audio/ch001.mp3"
        vm.rescan()

        val book = vm.uiState.value.books.single()
        assertTrue("rendered book must still import, got: ${vm.uiState.value.errors}", vm.uiState.value.errors.isEmpty())
        assertEquals("partial", book.renderState)
        assertTrue(book.showNotRendered)
    }

    @Test
    fun deleteBook_removesRowFolderAndProgress() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()
        progressRepo.positions["book-1"] = ProgressEntity("book-1", 0, 250L, 0L)

        val vm = viewModel()
        vm.deleteBook("book-1")

        vm.uiState.test {
            val state = awaitItem()
            assertTrue(state.books.isEmpty())
            assertTrue(state.errors.isEmpty())
        }
        assertEquals(listOf(novelDir), libraryRepo.deletedFolders)
        assertTrue(progressRepo.positions.isEmpty())
    }

    @Test
    fun deleteBook_unknownId_surfacesNotice() = runBlocking {
        storage.dirs = listOf(novelDir)
        storage.texts = validTexts(manifestJson())
        storage.existing = validExisting()

        val vm = viewModel()
        vm.deleteBook("no-such-book")

        vm.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.books.size)
            assertEquals(1, state.errors.size)
            assertTrue(
                "notice names the rule, was: ${state.errors[0].reason}",
                state.errors[0].reason.contains("nothing to delete")
            )
        }
        assertTrue(libraryRepo.deletedFolders.isEmpty())
    }

    private fun viewModel(
        storage: BundleStorage = this.storage
    ) = LibraryViewModel(
        storage = storage,
        watchFolderStore = folderStore,
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

    /** Minimal valid `text/chNNN.json` payload (single sentence, passes ChapterTextLoader rules). */
    private fun chapterTextJson(): String =
        """{"spec_version":"1.0","chapter":1,"title":"Ch","duration_ms":1000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"Hi. "}]}]}"""

    /** Minimal valid 2.0 unrendered chapter text (untimed reserved-speaker sentence). */
    private fun chapterTextJsonV2(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch","blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","text":"Hi. "}]}]}"""

    /** Minimal valid 2.0 rendered chapter text (timed sentence plus chapter duration). */
    private fun chapterTextJsonV2Timed(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch","duration_ms":1000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":1000,"text":"Hi. "}]}]}"""

    private fun voicesV2(): String =
        """"voices": {"dialogue": {"engine": "system", "pitch": 1.0, "speed": 1.0, "voice": "default"}, "narrator": {"engine": "system", "pitch": 1.0, "speed": 1.0, "voice": "default"}}"""

    private fun manifestV2(renderState: String, id: String = "book-2"): String = """
        {
          "spec_version": "2.0",
          "id": "$id",
          "title": "Unrendered Book",
          "author": "A. Author",
          "type": "epub",
          "render_state": "$renderState",
          ${voicesV2()},
          "chapters": [
            {"index": 1, "title": "Ch 1", "text": "text/ch001.json"},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun manifestPartial(): String = """
        {
          "spec_version": "2.0",
          "id": "book-p",
          "title": "Partial Book",
          "author": "A. Author",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "mp3"},
          ${voicesV2()},
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.mp3",
             "text": "text/ch001.json", "duration_ms": 1000},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun manifestV2Complete(): String = """
        {
          "spec_version": "2.0",
          "id": "book-c",
          "title": "Complete Book",
          "author": "A. Author",
          "type": "epub",
          "render_state": "complete",
          "audio": {"format": "mp3"},
          ${voicesV2()},
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.mp3",
             "text": "text/ch001.json", "duration_ms": 1000},
            {"index": 2, "title": "Ch 2", "audio": "audio/ch002.mp3",
             "text": "text/ch002.json", "duration_ms": 1000}
          ]
        }
        """.trimIndent()

    /** RN7: same book as [manifestV2] after chapter 1 renders (partial). */
    private fun manifestV2FirstRendered(id: String): String = """
        {
          "spec_version": "2.0",
          "id": "$id",
          "title": "Unrendered Book",
          "author": "A. Author",
          "type": "epub",
          "render_state": "partial",
          "audio": {"format": "mp3"},
          ${voicesV2()},
          "chapters": [
            {"index": 1, "title": "Ch 1", "audio": "audio/ch001.mp3",
             "text": "text/ch001.json", "duration_ms": 1000},
            {"index": 2, "title": "Ch 2", "text": "text/ch002.json"}
          ]
        }
        """.trimIndent()

    private fun validTexts(manifest: String): Map<String, String> = mapOf(
        manifestPath to manifest,
        "$novelDir/text/ch001.json" to chapterTextJson(),
        "$novelDir/text/ch002.json" to chapterTextJson()
    )

    private fun validExisting(): Set<String> = setOf(
        manifestPath,
        "$novelDir/audio/ch001.mp3",
        "$novelDir/audio/ch002.mp3",
        "$novelDir/text/ch001.json",
        "$novelDir/text/ch002.json"
    )

    private class FakeBundleStorage : BundleStorage {
        var dirs: List<String> = emptyList()
        var dirsByRoot: Map<String, List<String>> = emptyMap()
        var failRoots: Set<String> = emptySet()
        var texts: Map<String, String> = emptyMap()
        var existing: Set<String> = emptySet()
        var strayDirs: List<String> = emptyList()
        var strayDirsByRoot: Map<String, List<String>> = emptyMap()
        var failStrayRoots: Set<String> = emptySet()
        val deletedPaths = mutableListOf<String>()

        override fun listBundleDirs(root: String): List<String> {
            if (root in failRoots) throw IOException("$root: cannot list books folder: denied")
            return dirsByRoot[root] ?: dirs
        }
        override fun listStrayTempDirs(root: String): List<String> {
            if (root in failStrayRoots) throw IOException("$root: cannot list temp folders")
            return strayDirsByRoot[root] ?: strayDirs
        }
        override fun deleteRecursively(path: String) {
            deletedPaths.add(path)
        }
        override fun readText(path: String): String =
            texts[path] ?: throw IOException("$path: file not found or not readable")
        override fun exists(path: String): Boolean = path in existing
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the library")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? {
            if (coverRel.isBlank()) return null
            return bundleDirPath.trimEnd('/') + '/' + coverRel.trimStart('/')
        }
    }

    /**
     * Fake SAF storage mirroring [app.auloud.player.storage.SafBundleStorage]
     * token shape (`<tree>|<rel>`) over an in-memory backend, so multi-folder
     * aggregation covers a tree-URI folder without the framework.
     */
    private class FakeSafBundleStorage(val tree: String) : BundleStorage {
        var names: List<String> = emptyList()
        var texts: Map<String, String> = emptyMap()
        var existing: Set<String> = emptySet()

        override fun listBundleDirs(root: String): List<String> {
            if (root != tree) return emptyList()
            return names
                .filter { "$it/manifest.json" in existing }
                .map { "$tree|$it" }
                .sorted()
        }

        override fun readText(path: String): String {
            val rel = path.substringAfter("$tree|", "")
            if (rel.isBlank()) throw IOException("$path: not a SAF bundle path")
            return texts[rel] ?: throw IOException("$path: file not found or not readable")
        }

        override fun exists(path: String): Boolean {
            val rel = path.substringAfter("$tree|", "")
            return rel.isNotBlank() && rel in existing
        }

        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the library")

        override fun coverUri(bundleDirPath: String, coverRel: String): String? {
            if (coverRel.isBlank()) return null
            val rel = bundleDirPath.substringAfter("$tree|", "")
            if (rel.isBlank()) return null
            return "content://fake-cover/$rel/$coverRel"
        }
    }

    /** Routes `content://` roots to the SAF fake, the rest to the file fake. */
    private class FakeRoutingStorage : BundleStorage {
        val file = FakeBundleStorage()
        var saf: FakeSafBundleStorage? = null

        private fun isSaf(path: String) = path.startsWith("content://")

        override fun listBundleDirs(root: String): List<String> =
            if (isSaf(root)) {
                saf?.listBundleDirs(root) ?: throw IOException("$root: no SAF backend")
            } else {
                file.listBundleDirs(root)
            }

        override fun readText(path: String): String =
            if (isSaf(path)) {
                saf?.readText(path) ?: throw IOException("$path: no SAF backend")
            } else {
                file.readText(path)
            }

        override fun exists(path: String): Boolean =
            if (isSaf(path)) saf?.exists(path) ?: false else file.exists(path)

        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the library")

        override fun coverUri(bundleDirPath: String, coverRel: String): String? =
            if (isSaf(bundleDirPath)) {
                saf?.coverUri(bundleDirPath, coverRel)
            } else {
                file.coverUri(bundleDirPath, coverRel)
            }
    }

    private class FakeWatchFolderStore(initial: List<WatchFolder>) : WatchFolderStore {
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

    /** In-memory [LibraryRepository] mirroring the upsert-by-id + missing-flag semantics. */
    private class FakeLibraryRepository(
        val deletedFolders: MutableList<String> = mutableListOf()
    ) : LibraryRepository {
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
                // IN1: chapter durations are nullable (absent for unrendered
                // 2.0 chapters); this fake mirrors RoomLibraryRepository.
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
            deletedFolders.add(existing.bundlePath)
            rows.value = rows.value.filterNot { it.id == bookId }
            return Result.success(Unit)
        }
    }

    private class FakeProgressRepository : ProgressRepository {
        val positions = mutableMapOf<String, ProgressEntity>()

        override suspend fun save(
            bookId: String,
            chapterIndex: Int,
            positionMs: Long,
            sentenceSid: Int?
        ): Result<Unit> {
            positions[bookId] = ProgressEntity(bookId, chapterIndex, positionMs, 0L, sentenceSid)
            return Result.success(Unit)
        }

        override suspend fun load(bookId: String): Result<ProgressEntity?> =
            Result.success(positions[bookId])

        override suspend fun delete(bookId: String): Result<Unit> {
            positions.remove(bookId)
            return Result.success(Unit)
        }
    }
}
