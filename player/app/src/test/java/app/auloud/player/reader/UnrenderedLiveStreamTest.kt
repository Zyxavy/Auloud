package app.auloud.player.reader

import android.net.Uri
import app.auloud.player.bundle.Sentence
import app.auloud.player.data.ProgressDao
import app.auloud.player.data.ProgressEntity
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.storage.BundleStorage
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST5: live audio attachment on the unrendered reader (plain JVM).
 *
 * A fake [LiveStream] (flow plus recording lambdas) stands in for the
 * controller-backed production one: the voice sid flows into state
 * without touching the saved reading position, taps restart the stream
 * at the sentence index, and chapter jumps move the stream too.
 * Virtual time throughout (VC5 rule).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnrenderedLiveStreamTest {

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

        override suspend fun getAll(): List<ProgressEntity> = rows.values.toList()

        override suspend fun upsert(progress: ProgressEntity) {
            rows[progress.bookId] = progress
        }

        override suspend fun deleteById(bookId: String) {
            rows.remove(bookId)
        }
    }

    private class FakeLive {
        val sids = MutableStateFlow<Int?>(null)
        val chapterPositions = MutableStateFlow<Int?>(null)
        val restarts = mutableListOf<Int>()
        val chapterSeeks = mutableListOf<Int>()
        var nexts = 0
        var previouses = 0
        val stream = LiveStream(
            sid = sids,
            chapterPos = chapterPositions,
            restartAt = { restarts += it },
            seekToChapter = { chapterSeeks += it },
            nextChapter = { nexts++ },
            previousChapter = { previouses++ }
        )
    }

    private fun chapterPayload(): String =
        """{"spec_version": "2.0", "chapter": 1, "title": "Ch 1",
            "blocks": [{"id": 1, "type": "para", "sentences": [
            {"sid": 1, "speaker": "narrator", "text": "One. "},
            {"sid": 2, "speaker": "dialogue", "text": "\"Two.\" "},
            {"sid": 3, "speaker": "narrator", "text": "Three."}]}]}"""

    private fun files(): Map<String, String> = mapOf("text/ch001.json" to chapterPayload())

    private fun viewModel(
        dao: FakeProgressDao,
        dispatcher: CoroutineDispatcher,
        live: LiveStream?,
        bundleFiles: Map<String, String> = files()
    ): UnrenderedReaderViewModel = UnrenderedReaderViewModel(
        bookId = "book-live",
        storage = FakeStorage(bundleFiles),
        textPathForChapter = { "text/ch001.json" },
        chapterCount = 1,
        progress = RoomProgressRepository(dao),
        dispatcher = dispatcher,
        debounceMs = 25L,
        live = live
    )

    @Test
    fun liveSid_flowsIntoStateWithoutMovingSavedPosition() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val fake = FakeLive()
        val vm = viewModel(dao, StandardTestDispatcher(testScheduler), fake.stream)
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                assertNull(state.liveSid)
                fake.sids.value = 2
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.liveSid == null) state = awaitItem()
                assertEquals(2, state.liveSid)
                // Reading position untouched (still the chapter start).
                assertEquals(1, state.currentSid)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertEquals(1, repo.load("book-live").getOrThrow()?.sentenceSid)
    }

    @Test
    fun tap_armsPromptThenConfirmRestartsStreamAtSentenceIndex() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        val vm = viewModel(dao, StandardTestDispatcher(testScheduler), fake.stream)
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                // A live voice is attached: a tap must ask before moving it.
                fake.sids.value = 1
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.liveSid == null) state = awaitItem()
                vm.onSentenceTap(3)
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.pendingTapSid != 3) state = awaitItem()
                assertEquals(1, state.currentSid)
                assertTrue(fake.restarts.isEmpty())
                vm.confirmTapJump()
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.currentSid != 3) state = awaitItem()
                assertNull(state.pendingTapSid)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        // Sids 1,2,3: sid 3 is index 2.
        assertEquals(listOf(2), fake.restarts)
    }

    @Test
    fun confirmTapJump_reattachesOnlyWhenVoiceReachesTarget() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        val vm = viewModel(dao, StandardTestDispatcher(testScheduler), fake.stream)
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                fake.sids.value = 1
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.liveSid == null) state = awaitItem()
                vm.onUserScrolled()
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.follow != FollowState.Detached) state = awaitItem()
                vm.onSentenceTap(3)
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.pendingTapSid != 3) state = awaitItem()
                vm.confirmTapJump()
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.currentSid != 3) state = awaitItem()
                // The old sentence is still the voice position: re-attaching
                // now would scroll there first.
                assertEquals(FollowState.Detached, state.follow)
                fake.sids.value = 3
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.liveSid != 3) state = awaitItem()
                assertEquals(FollowState.Following, state.follow)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
    }

    @Test
    fun dismissTapJump_keepsVoiceAndReadingPosition() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        val vm = viewModel(dao, StandardTestDispatcher(testScheduler), fake.stream)
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                fake.sids.value = 1
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.liveSid == null) state = awaitItem()
                vm.onSentenceTap(3)
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.pendingTapSid != 3) state = awaitItem()
                vm.dismissTapJump()
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.pendingTapSid != null) state = awaitItem()
                assertEquals(1, state.currentSid)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertTrue(fake.restarts.isEmpty())
    }

    @Test
    fun tap_withoutLiveVoice_movesReadingPositionImmediately() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val vm = viewModel(dao, StandardTestDispatcher(testScheduler), live = null)
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                // A manual scroll detached follow; the tap is an explicit
                // jump, so it re-attaches like the rendered reader.
                vm.onUserScrolled()
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.follow != FollowState.Detached) state = awaitItem()
                vm.onSentenceTap(3)
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.currentSid != 3) state = awaitItem()
                assertNull(state.pendingTapSid)
                assertEquals(FollowState.Following, state.follow)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertEquals(3, repo.load("book-live").getOrThrow()?.sentenceSid)
    }

    @Test
    fun tap_mapsSidOntoFilteredStreamIndex() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        // Sid 1 is blank: the service skips it on the stream path, so sid 3
        // is stream index 1 even though it is reading index 2.
        val payload =
            """{"spec_version": "2.0", "chapter": 1, "title": "Ch 1",
                "blocks": [{"id": 1, "type": "para", "sentences": [
                {"sid": 1, "speaker": "narrator", "text": " "},
                {"sid": 2, "speaker": "narrator", "text": "Two. "},
                {"sid": 3, "speaker": "narrator", "text": "Three."}]}]}"""
        val vm = viewModel(
            dao,
            StandardTestDispatcher(testScheduler),
            fake.stream,
            bundleFiles = mapOf("text/ch001.json" to payload)
        )
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                fake.sids.value = 1
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.liveSid == null) state = awaitItem()
                vm.onSentenceTap(3)
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.pendingTapSid != 3) state = awaitItem()
                vm.confirmTapJump()
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.currentSid != 3) state = awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertEquals(listOf(1), fake.restarts)
    }

    @Test
    fun unknownTap_ignoredWithNoRestart() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        val vm = viewModel(dao, StandardTestDispatcher(testScheduler), fake.stream)
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                fake.sids.value = 1
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                while (state.liveSid == null) state = awaitItem()
                vm.onSentenceTap(99)
                testScheduler.advanceUntilIdle()
                assertNull(vm.state.value.pendingTapSid)
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertTrue(fake.restarts.isEmpty())
    }

    @Test
    fun jumpToChapter_movesStreamToo() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        val files = mapOf(
            "text/ch001.json" to chapterPayload(),
            "text/ch002.json" to chapterPayload().replace("\"chapter\": 1", "\"chapter\": 2")
        )
        val vm = UnrenderedReaderViewModel(
            bookId = "book-live",
            storage = FakeStorage(files),
            textPathForChapter = { "text/ch00${it + 1}.json" },
            chapterCount = 2,
            progress = RoomProgressRepository(dao),
            dispatcher = StandardTestDispatcher(testScheduler),
            debounceMs = 25L,
            live = fake.stream
        )
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                vm.jumpToChapter(1)
                testScheduler.advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertEquals(listOf(1), fake.chapterSeeks)
    }

    @Test
    fun jumpOutOfRange_neverReachesStream() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        val vm = viewModel(dao, StandardTestDispatcher(testScheduler), fake.stream)
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                vm.jumpToChapter(7)
                testScheduler.advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertTrue(fake.chapterSeeks.isEmpty())
    }

    @Test
    fun serviceChapterAdvance_loadsTextWithoutCommandingService() = runTest {
        val dao = FakeProgressDao()
        val repo = RoomProgressRepository(dao)
        val fake = FakeLive()
        val files = mapOf(
            "text/ch001.json" to chapterPayload(),
            "text/ch002.json" to chapterPayload().replace("\"chapter\": 1", "\"chapter\": 2")
        )
        val vm = UnrenderedReaderViewModel(
            bookId = "book-live",
            storage = FakeStorage(files),
            textPathForChapter = { "text/ch00${it + 1}.json" },
            chapterCount = 2,
            progress = repo,
            dispatcher = StandardTestDispatcher(testScheduler),
            debounceMs = 25L,
            live = fake.stream
        )
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                assertEquals(0, state.chapterIndex)
                // The voice crossed the chapter end: the reader follows,
                // but sends no seek back (the service is already there).
                fake.chapterPositions.value = 1
                testScheduler.advanceUntilIdle()
                state = awaitItem()
                // chapterIndex flips synchronously when the follow fires;
                // wait for the loaded chapter itself (IO-backed).
                while (state.chapterIndex != 1 || state.chapter == null) {
                    state = awaitItem()
                }
                assertEquals(2, state.chapter?.chapter)
                // Let the follow-up save land before tearing down.
                testScheduler.advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertTrue(
            "follow must not command the service: ${fake.chapterSeeks}",
            fake.chapterSeeks.isEmpty()
        )
        assertEquals(1, repo.load("book-live").getOrThrow()?.chapterIndex)
    }

    @Test
    fun serviceChapterAdvanceOutOfRange_isIgnored() = runTest {
        val dao = FakeProgressDao()
        val fake = FakeLive()
        val files = mapOf(
            "text/ch001.json" to chapterPayload(),
            "text/ch002.json" to chapterPayload().replace("\"chapter\": 1", "\"chapter\": 2")
        )
        val vm = UnrenderedReaderViewModel(
            bookId = "book-live",
            storage = FakeStorage(files),
            textPathForChapter = { "text/ch00${it + 1}.json" },
            chapterCount = 2,
            progress = RoomProgressRepository(dao),
            dispatcher = StandardTestDispatcher(testScheduler),
            debounceMs = 25L,
            live = fake.stream
        )
        try {
            vm.state.test {
                testScheduler.advanceUntilIdle()
                var state = awaitItem()
                while (state.chapter == null) state = awaitItem()
                fake.chapterPositions.value = 9
                testScheduler.advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            vm.clear()
        }
        assertTrue(fake.chapterSeeks.isEmpty())
    }

    @Test
    fun liveFraction_countsSentences() {
        val sentences = listOf(
            Sentence(sid = 1, speaker = "narrator", text = "One."),
            Sentence(sid = 2, speaker = "dialogue", text = "Two."),
            Sentence(sid = 3, speaker = "narrator", text = "Three."),
            Sentence(sid = 4, speaker = "narrator", text = "Four.")
        )
        assertNull(liveFractionOf(null, sentences))
        assertNull(liveFractionOf(1, emptyList()))
        assertNull(liveFractionOf(9, sentences))
        assertEquals(0.25f, liveFractionOf(1, sentences))
        assertEquals(0.5f, liveFractionOf(2, sentences))
        assertEquals(1.0f, liveFractionOf(4, sentences))
    }
}
