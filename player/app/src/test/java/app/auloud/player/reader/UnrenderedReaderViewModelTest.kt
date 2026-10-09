package app.auloud.player.reader

import android.net.Uri
import app.auloud.player.data.ProgressDao
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.storage.BundleStorage
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN9: [UnrenderedReaderViewModel] against map-backed storage and the real
 * [RoomProgressRepository] over a fake DAO (plain JVM).
 *
 * VC5: every test runs on virtual time (`runTest` plus a
 * [StandardTestDispatcher] injected as the ViewModel dispatcher). The old
 * real-time version flaked under full-suite load (VC1 and VC3 runs: the
 * debounce settle or the initial save did not land inside the wall-clock
 * poll window); here delays and saves advance only when the test says so,
 * so timing load cannot move the assertions.
 *
 * Covers the brief Verify: state, sid position save and restore (including
 * the missing-sid fallback), chapter list jumps and mode-free reading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnrenderedReaderViewModelTest {

    private class FakeStorage(private val files: Map<String, String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String =
            files[path] ?: throw java.io.IOException("missing: $path")
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the reader")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
    }

    private class FakeProgressDao : ProgressDao {
        private val rows = mutableMapOf<String, ProgressEntity>()

        override suspend fun load(bookId: String): ProgressEntity? = rows[bookId]

        override suspend fun upsert(progress: ProgressEntity) {
            rows[progress.bookId] = progress
        }

        override suspend fun deleteById(bookId: String) {
            rows.remove(bookId)
        }
    }

    private fun chapterPayload(chapter: Int, title: String, secondSpeaker: String = "dialogue"): String {
        val second = if (chapter == 1) {
            """{"sid": 2, "speaker": "$secondSpeaker", "text": "\"Two.\" "},""" +
                """{"sid": 3, "speaker": "narrator", "text": "Three."}"""
        } else {
            """{"sid": 2, "speaker": "narrator", "text": "They went."}"""
        }
        val first = if (chapter == 1) {
            """{"sid": 1, "speaker": "narrator", "text": "One. "},"""
        } else {
            """{"sid": 1, "speaker": "dialogue", "text": "\"Go.\" "},"""
        }
        return """{"spec_version": "2.0", "chapter": $chapter, "title": "$title",
            "blocks": [{"id": 1, "type": "para", "sentences": [$first$second]}]}"""
    }

    private fun files(): Map<String, String> = mapOf(
        "text/ch001.json" to chapterPayload(1, "Ch 1"),
        "text/ch002.json" to chapterPayload(2, "Ch 2")
    )

    private fun viewModel(
        files: Map<String, String>,
        dao: FakeProgressDao,
        dispatcher: CoroutineDispatcher,
        chapterCount: Int = 2,
        pathFor: (Int) -> String? = { "text/ch00${it + 1}.json" },
        debounceMs: Long = 25L
    ): UnrenderedReaderViewModel {
        return UnrenderedReaderViewModel(
            bookId = "book-9",
            storage = FakeStorage(files),
            textPathForChapter = pathFor,
            chapterCount = chapterCount,
            progress = RoomProgressRepository(dao),
            dispatcher = dispatcher,
            debounceMs = debounceMs
        )
    }

    private suspend fun UnrenderedReaderViewModel.collectTest(
        block: suspend app.cash.turbine.ReceiveTurbine<UnrenderedReaderState>.() -> Unit
    ) {
        try {
            state.test(validate = block)
        } finally {
            clear()
        }
    }

    @Test
    fun initialLoad_noSavedProgress_opensChapterStartAndSaves() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(0, state.chapterIndex)
            assertEquals("Ch 1", state.chapter?.title)
            assertEquals(1, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        val loaded = repo.load("book-9").getOrThrow()
        assertEquals(0, loaded?.chapterIndex)
        assertEquals(1, loaded?.sentenceSid)
        assertEquals(0L, loaded?.positionMs)
    }

    @Test
    fun initialLoad_savedChapterAndSid_restored() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 1, 0L, sentenceSid = 2).getOrThrow()
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(1, state.chapterIndex)
            assertEquals("Ch 2", state.chapter?.title)
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun initialLoad_missingSavedSid_fallsBackToChapterStart() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 0, 0L, sentenceSid = 99).getOrThrow()
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(0, state.chapterIndex)
            assertEquals(1, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        val loaded = repo.load("book-9").getOrThrow()
        assertEquals(0, loaded?.chapterIndex)
        assertEquals(1, loaded?.sentenceSid)
    }

    @Test
    fun initialLoad_savedChapterOutOfRange_clampsToLast() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 7, 0L, sentenceSid = 2).getOrThrow()
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(1, state.chapterIndex)
            assertEquals("Ch 2", state.chapter?.title)
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun initialLoad_msRowWithoutSid_fallsBackToChapterStart() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 0, 61_000L).getOrThrow()
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(0, state.chapterIndex)
            assertEquals(1, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun jumpToChapter_loadsStartAndSaves() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.jumpToChapter(1)
            testScheduler.advanceUntilIdle()
            state = awaitItem()
            while (state.chapter?.title != "Ch 2") state = awaitItem()
            assertEquals(1, state.chapterIndex)
            assertEquals(1, state.currentSid)
            assertEquals(FollowState.Following, state.follow)
            cancelAndIgnoreRemainingEvents()
        }
        val loaded = repo.load("book-9").getOrThrow()
        assertEquals(1, loaded?.chapterIndex)
        assertEquals(1, loaded?.sentenceSid)
    }

    @Test
    fun jumpToChapter_outOfRange_noop() = runTest {
        val dao = FakeProgressDao()
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.jumpToChapter(5)
            vm.jumpToChapter(-1)
            testScheduler.advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sentenceTap_movesAndSaves() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onSentenceTap(2)
            testScheduler.advanceUntilIdle()
            state = awaitItem()
            while (state.currentSid != 2) state = awaitItem()
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        val loaded = repo.load("book-9").getOrThrow()
        assertEquals(0, loaded?.chapterIndex)
        assertEquals(2, loaded?.sentenceSid)
    }

    @Test
    fun sentenceTap_unknownSid_ignored() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onSentenceTap(99)
            testScheduler.advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(repo.load("book-9").getOrThrow()?.sentenceSid?.takeIf { it == 99 })
    }

    @Test
    fun topVisible_settlesLastReportAndSaves() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(
            files(), dao, StandardTestDispatcher(testScheduler), debounceMs = 50L
        )
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onTopVisibleSid(1)
            // Half the debounce: the first report is still pending, so the
            // second report cancels it and only the last one settles.
            testScheduler.advanceTimeBy(25)
            vm.onTopVisibleSid(2)
            testScheduler.advanceTimeBy(100)
            state = awaitItem()
            while (state.currentSid != 2) state = awaitItem()
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        testScheduler.advanceUntilIdle()
        val loaded = repo.load("book-9").getOrThrow()
        assertEquals(0, loaded?.chapterIndex)
        assertEquals(2, loaded?.sentenceSid)
    }

    @Test
    fun topVisible_unknownSid_ignored() = runTest {
        val dao = FakeProgressDao()
        val vm = viewModel(
            files(), dao, StandardTestDispatcher(testScheduler), debounceMs = 50L
        )
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onTopVisibleSid(99)
            // Past the debounce: the settle ran and ignored the sid.
            testScheduler.advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun loadFailure_missingFile_setsMissingError() = runTest {
        val dao = FakeProgressDao()
        val vm = viewModel(emptyMap(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.textError == null) state = awaitItem()
            assertNull(state.chapter)
            assertNull(state.currentSid)
            assertEquals(TextKind.Missing, state.textKind)
            assertTrue(state.textError?.contains("ch001") == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun scrollAndBackToNow_flipFollow() = runTest {
        val dao = FakeProgressDao()
        val vm = viewModel(files(), dao, StandardTestDispatcher(testScheduler))
        vm.collectTest {
            testScheduler.advanceUntilIdle()
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            assertEquals(FollowState.Following, state.follow)
            vm.onUserScrolled()
            assertEquals(FollowState.Detached, awaitItem().follow)
            vm.onBackToNow()
            assertEquals(FollowState.Following, awaitItem().follow)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
