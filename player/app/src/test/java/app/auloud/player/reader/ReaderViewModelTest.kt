package app.auloud.player.reader

import android.net.Uri
import app.auloud.player.playback.PlaybackState
import app.auloud.player.storage.BundleStorage
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RA3: [ReaderViewModel] against a fake playback flow and map-backed
 * storage (plain JVM; the ~200 ms ticker runs at 25 ms here).
 */
class ReaderViewModelTest {

    private class FakeStorage(private val files: Map<String, String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String =
            files[path] ?: throw java.io.IOException("missing: $path")
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the reader")
    }

    private fun chapterPayload(title: String, secondStart: Long = 1500L): String {
        return """{"spec_version": "1.0", "chapter": 1, "title": "$title",
            "duration_ms": 3000,
            "blocks": [{"id": 1, "type": "para", "sentences": [
            {"sid": 1, "speaker": "narrator", "start_ms": 0, "end_ms": 1000, "text": "One. "},
            {"sid": 2, "speaker": "narrator", "start_ms": $secondStart, "end_ms": 2500, "text": "Two. "}]}]}"""
    }

    private fun viewModel(
        playback: MutableStateFlow<PlaybackState>,
        files: Map<String, String>,
        pathFor: (Int) -> String? = { "text/ch001.json" },
        onSeekTo: (Long) -> Unit = {}
    ): ReaderViewModel {
        return ReaderViewModel(
            playback = playback,
            storage = FakeStorage(files),
            textPathForChapter = pathFor,
            dispatcher = Dispatchers.Unconfined,
            tickerMs = 25L,
            onSeekTo = onSeekTo
        )
    }

    /** Collects state with turbine, always clearing the ticker afterwards. */
    private suspend fun ReaderViewModel.collectTest(
        block: suspend app.cash.turbine.ReceiveTurbine<ReaderState>.() -> Unit
    ) {
        try {
            state.test(validate = block)
        } finally {
            clear()
        }
    }

    private fun playback(chapterIndex: Int = 0, positionMs: Long = 100L): MutableStateFlow<PlaybackState> {
        return MutableStateFlow(PlaybackState(isPlaying = true, chapterIndex = chapterIndex, positionMs = positionMs))
    }

    @Test
    fun initialLoad_emitsLoadingThenChapterWithSid() = runBlocking {
        val vm = viewModel(playback(), mapOf("text/ch001.json" to chapterPayload("Ch 1")))
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            assertEquals("Ch 1", state.chapter?.title)
            assertEquals(1, state.currentSid)
            assertEquals(100L, state.positionMs)
            assertTrue(state.isPlaying)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun tickStaysSilentWhileSidUnchanged() = runBlocking {
        val vm = viewModel(playback(positionMs = 100L), mapOf("text/ch001.json" to chapterPayload("Ch 1")))
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            // Several 25 ms ticks pass with the highlight on sid 1: nothing new.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun positionAdvance_movesHighlight() = runBlocking {
        val flow = playback(positionMs = 100L)
        val vm = viewModel(flow, mapOf("text/ch001.json" to chapterPayload("Ch 1")))
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            assertEquals(1, state.currentSid)
            flow.value = flow.value.copy(positionMs = 1600L)
            state = awaitItem()
            while (state.currentSid != 2) state = awaitItem()
            assertEquals(2, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun chapterChange_reloadsTextAndResetsFollow() = runBlocking {
        val files = mapOf(
            "text/ch001.json" to chapterPayload("Ch 1"),
            "text/ch002.json" to chapterPayload("Ch 2")
        )
        val flow = playback(chapterIndex = 0)
        val vm = viewModel(flow, files, pathFor = { "text/ch00${it + 1}.json" })
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            assertEquals("Ch 1", state.chapter?.title)
            vm.onUserScrolled()
            assertEquals(FollowState.Detached, awaitItem().follow)
            flow.value = flow.value.copy(chapterIndex = 1, positionMs = 100L)
            state = awaitItem()
            while (state.chapter?.title != "Ch 2") state = awaitItem()
            assertEquals(FollowState.Following, state.follow)
            assertEquals(1, state.currentSid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun loadFailure_setsErrorAndKeepsControlsUsable() = runBlocking {
        val vm = viewModel(playback(), emptyMap())
        vm.collectTest {
            var state = awaitItem()
            while (state.textError == null) state = awaitItem()
            assertNull(state.chapter)
            assertNull(state.currentSid)
            assertTrue(state.textError?.contains("ch001") == true)
            vm.setMode(ReaderMode.Read)
            assertEquals(ReaderMode.Read, awaitItem().mode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun scrollAndBackToNow_flipFollow() = runBlocking {
        val vm = viewModel(playback(), mapOf("text/ch001.json" to chapterPayload("Ch 1")))
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

    @Test
    fun setMode_preservesPlace() = runBlocking {
        val vm = viewModel(playback(positionMs = 1600L), mapOf("text/ch001.json" to chapterPayload("Ch 1")))
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            assertNotNull(state.chapter)
            val sid = state.currentSid
            val pos = state.positionMs
            vm.setMode(ReaderMode.Read)
            state = awaitItem()
            assertEquals(ReaderMode.Read, state.mode)
            assertEquals(sid, state.currentSid)
            assertEquals(pos, state.positionMs)
            assertNotNull(state.chapter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sentenceTap_seeksToStartAndReattaches() = runBlocking {
        val sought = ArrayList<Long>()
        val vm = viewModel(
            playback(positionMs = 100L),
            mapOf("text/ch001.json" to chapterPayload("Ch 1")),
            onSeekTo = { sought.add(it) }
        )
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onUserScrolled()
            assertEquals(FollowState.Detached, awaitItem().follow)
            vm.onSentenceTap(2)
            assertEquals(listOf(1500L), sought)
            assertEquals(FollowState.Following, awaitItem().follow)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sentenceTap_unknownSid_ignored() = runBlocking {
        val sought = ArrayList<Long>()
        val vm = viewModel(
            playback(positionMs = 100L),
            mapOf("text/ch001.json" to chapterPayload("Ch 1")),
            onSeekTo = { sought.add(it) }
        )
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null) state = awaitItem()
            vm.onSentenceTap(99)
            assertTrue(sought.isEmpty())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
