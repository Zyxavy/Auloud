package app.auloud.player.reader

import android.net.Uri
import app.auloud.player.data.ProgressDao
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.ProgressRepository
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.storage.BundleStorage
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN9: [UnrenderedReaderViewModel] against map-backed storage and the real
 * [RoomProgressRepository] over a fake DAO (plain JVM; the scroll settle
 * runs at 25 ms here).
 *
 * Covers the brief Verify: state, sid position save and restore (including
 * the missing-sid fallback), chapter list jumps and mode-free reading.
 */
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
            dispatcher = Dispatchers.Unconfined,
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

    private suspend fun awaitSaved(
        repo: ProgressRepository,
        chapter: Int,
        sid: Int
    ) {
        var tries = 0
        while (tries < 200) {
            val loaded = repo.load("book-9").getOrThrow()
            if (loaded?.chapterIndex == chapter && loaded?.sentenceSid == sid &&
                loaded?.positionMs == 0L
            ) {
                return
            }
            delay(10L)
            tries++
        }
        val loaded = repo.load("book-9").getOrThrow()
        throw AssertionError("expected saved ($chapter, sid $sid), got: $loaded")
    }

    @Test
    fun initialLoad_noSavedProgress_opensChapterStartAndSaves() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(0, state.chapterIndex)
            assertEquals("Ch 1", state.chapter?.title)
            assertEquals(1, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        awaitSaved(repo, 0, 1)
    }

    @Test
    fun initialLoad_savedChapterAndSid_restored() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 1, 0L, sentenceSid = 2).getOrThrow()
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(1, state.chapterIndex)
            assertEquals("Ch 2", state.chapter?.title)
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun initialLoad_missingSavedSid_fallsBackToChapterStart() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 0, 0L, sentenceSid = 99).getOrThrow()
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(0, state.chapterIndex)
            assertEquals(1, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        awaitSaved(repo, 0, 1)
    }

    @Test
    fun initialLoad_savedChapterOutOfRange_clampsToLast() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 7, 0L, sentenceSid = 2).getOrThrow()
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(1, state.chapterIndex)
            assertEquals("Ch 2", state.chapter?.title)
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun initialLoad_msRowWithoutSid_fallsBackToChapterStart() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        repo.save("book-9", 0, 61_000L).getOrThrow()
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals(0, state.chapterIndex)
            assertEquals(1, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun jumpToChapter_loadsStartAndSaves() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.jumpToChapter(1)
            state = awaitItem()
            while (state.chapter?.title != "Ch 2") state = awaitItem()
            assertEquals(1, state.chapterIndex)
            assertEquals(1, state.currentSid)
            assertEquals(FollowState.Following, state.follow)
            cancelAndIgnoreRemainingEvents()
        }
        awaitSaved(repo, 1, 1)
    }

    @Test
    fun jumpToChapter_outOfRange_noop() = runBlocking {
        val dao = FakeProgressDao()
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.jumpToChapter(5)
            vm.jumpToChapter(-1)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sentenceTap_movesAndSaves() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onSentenceTap(2)
            state = awaitItem()
            while (state.currentSid != 2) state = awaitItem()
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        awaitSaved(repo, 0, 2)
    }

    @Test
    fun sentenceTap_unknownSid_ignored() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onSentenceTap(99)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(repo.load("book-9").getOrThrow()?.sentenceSid?.takeIf { it == 99 })
    }

    @Test
    fun topVisible_settlesLastReportAndSaves() = runBlocking {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(files(), dao, debounceMs = 50L)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onTopVisibleSid(1)
            vm.onTopVisibleSid(2)
            state = awaitItem()
            while (state.currentSid != 2) state = awaitItem()
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
        awaitSaved(repo, 0, 2)
    }

    @Test
    fun topVisible_unknownSid_ignored() = runBlocking {
        val dao = FakeProgressDao()
        val vm = viewModel(files(), dao, debounceMs = 50L)
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onTopVisibleSid(99)
            delay(150L)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun loadFailure_missingFile_setsMissingError() = runBlocking {
        val dao = FakeProgressDao()
        val vm = viewModel(emptyMap(), dao)
        vm.collectTest {
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
    fun scrollAndBackToNow_flipFollow() = runBlocking {
        val dao = FakeProgressDao()
        val vm = viewModel(files(), dao)
        vm.collectTest {
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
