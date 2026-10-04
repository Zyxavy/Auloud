package app.auloud.player.reader

import android.net.Uri
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.bundle.ChapterTextPdfForm
import app.auloud.player.playback.PlaybackState
import app.auloud.player.storage.BundleStorage
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP7 Text view: blocks-form PDF chapters read end-to-end like EPUB.
 *
 * Loads the real `spec/fixtures/pdf-golden/text/ch001.json` through the
 * REAL [ChapterTextLoader], then drives the shared reader path
 * ([SentenceIndex] highlight, [layoutParagraph] + [sidAtOffset] tap
 * mapping, [ReaderViewModel] follow + tap-confirm). Pure pages-without-
 * blocks stays [TextKind.PdfForm] with playback untouched (listening
 * still works). No Page-view code: `pages` marks are only looked up via
 * [app.auloud.player.bundle.ChapterText.pageAt].
 *
 * API 24 safe: `java.io.File` only. No Robolectric, no new dependencies.
 */
class PdfReaderTest {

    private class FakeStorage(private val files: Map<String, String>) : BundleStorage {
        override fun listBundleDirs(root: String): List<String> = emptyList()
        override fun readText(path: String): String =
            files[path] ?: throw java.io.IOException("missing: $path")
        override fun exists(path: String): Boolean = files.containsKey(path)
        override fun audioUri(bundleDir: String, relPath: String): Uri =
            throw UnsupportedOperationException("not used by the reader")
        override fun coverUri(bundleDirPath: String, coverRel: String): String? = null
    }

    private fun fixtureDir(name: String): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val direct = File(userDir, "../../spec/fixtures/$name")
        if (direct.isDirectory) return direct
        var cur: File? = userDir
        while (cur != null) {
            val candidate = File(cur, "spec/fixtures/$name")
            if (candidate.isDirectory) return candidate
            cur = cur.parentFile
        }
        return direct
    }

    private fun pdfGoldenRaw(): String {
        val dir = fixtureDir("pdf-golden")
        assertTrue("fixture missing: ${dir.path}", dir.isDirectory)
        return File(dir, "text/ch001.json").readText(Charsets.UTF_8)
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
            onSeekTo = onSeekTo,
            debounceMs = 25L
        )
    }

    private suspend fun ReaderViewModel.collectTest(
        block: suspend app.cash.turbine.ReceiveTurbine<ReaderState>.() -> Unit
    ) {
        try {
            state.test(validate = block)
        } finally {
            clear()
        }
    }

    @Test
    fun pdfBlocks_highlightAndPageAtThroughSharedIndex() = runBlocking {
        val storage = FakeStorage(mapOf("text/ch001.json" to pdfGoldenRaw()))
        val result = ChapterTextLoader.load(storage, "text/ch001.json")
        assertTrue(
            "blocks+pages must read as text: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
        val chapter = result.getOrThrow()
        // Highlight mapping: same SentenceIndex path EPUB uses (page ignored).
        val index = SentenceIndex(chapter.blocks)
        assertEquals(4, index.size)
        assertEquals(1, index.currentSid(0))
        assertEquals(2, index.currentSid(600))
        assertEquals(3, index.currentSid(950))
        assertEquals(4, index.currentSid(1550))
        assertEquals(4, index.currentSid(2150))
        // Tap-to-jump seek targets come from the same index.
        assertEquals(600L, index.startMsOf(2))
        assertEquals(1550L, index.startMsOf(4))
        assertNull(index.startMsOf(99))
        // Page lookup alongside the highlight (Page view reads this later).
        assertEquals(1, chapter.pageAt(0)?.page)
        assertEquals(1, chapter.pageAt(1549)?.page)
        assertEquals(2, chapter.pageAt(1550)?.page)
        assertEquals(2, chapter.pageAt(2000)?.page)
    }

    @Test
    fun pdfBlocks_tapMappingThroughSharedParagraphLayout() = runBlocking {
        val storage = FakeStorage(mapOf("text/ch001.json" to pdfGoldenRaw()))
        val chapter = ChapterTextLoader.load(storage, "text/ch001.json").getOrThrow()
        // Two-sentence block (sids 2-3): offsets tile contiguously, every
        // tap resolves, exactly like an EPUB paragraph.
        val twoSentence = chapter.blocks[1]
        val layout = layoutParagraph(twoSentence)
        assertEquals(listOf(2, 3), layout.sentences.map { it.sid })
        assertEquals(2, sidAtOffset(layout.sentences, 0))
        assertEquals(3, sidAtOffset(layout.sentences, Int.MAX_VALUE))
        // Single-sentence block: any offset lands on its sid.
        val single = layoutParagraph(chapter.blocks[0])
        assertEquals(1, sidAtOffset(single.sentences, 0))
        assertEquals(1, sidAtOffset(single.sentences, 999))
    }

    @Test
    fun pdfBlocks_viewModel_highlightFollowAndTapConfirm() = runBlocking {
        val sought = ArrayList<Long>()
        val flow = MutableStateFlow(
            PlaybackState(isPlaying = true, chapterIndex = 0, positionMs = 100L)
        )
        val vm = viewModel(
            flow,
            mapOf("text/ch001.json" to pdfGoldenRaw()),
            onSeekTo = { sought.add(it) }
        )
        vm.collectTest {
            var state = awaitItem()
            while (state.chapter == null && state.textError == null) state = awaitItem()
            // Text view, not PdfForm: the chapter loads with pages attached.
            assertEquals("Chapter 1", state.chapter?.title)
            assertEquals(1, state.currentSid)
            assertEquals(2, state.chapter?.pages?.size)
            assertEquals(1, state.chapter?.pageAt(100L)?.page)
            // Highlight follows playback through the shared index.
            flow.value = flow.value.copy(positionMs = 1600L)
            state = awaitItem()
            while (state.currentSid != 4) state = awaitItem()
            assertEquals(2, state.chapter?.pageAt(state.positionMs)?.page)
            // Follow detaches on scroll; tap arms confirm without seeking.
            vm.onUserScrolled()
            assertEquals(FollowState.Detached, awaitItem().follow)
            vm.onSentenceTap(2)
            assertTrue(sought.isEmpty())
            assertEquals(2, awaitItem().pendingTapSid)
            // Confirm seeks to the tapped sentence start and re-attaches.
            vm.confirmTapJump()
            assertEquals(listOf(600L), sought)
            state = awaitItem()
            assertNull(state.pendingTapSid)
            assertEquals(FollowState.Following, state.follow)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun purePages_viewModel_reportsPdfFormButListeningContinues() = runBlocking {
        val payload = """{"spec_version": "1.0", "chapter": 1, "title": "P",
            "duration_ms": 1000, "pages": [{"page": 1, "start_ms": 0}]}"""
        val sought = ArrayList<Long>()
        val flow = MutableStateFlow(
            PlaybackState(isPlaying = true, chapterIndex = 0, positionMs = 100L)
        )
        val vm = viewModel(
            flow,
            mapOf("text/ch001.json" to payload),
            onSeekTo = { sought.add(it) }
        )
        vm.collectTest {
            var state = awaitItem()
            while (state.textError == null) state = awaitItem()
            // Page-only: no text, PdfForm kind, truthful message.
            assertNull(state.chapter)
            assertNull(state.currentSid)
            assertEquals(TextKind.PdfForm, state.textKind)
            assertTrue(
                "message must say page-only: ${state.textError}",
                state.textError?.contains("page-only") == true
            )
            // Listening unaffected: playback state still mirrors the service.
            assertEquals(100L, state.positionMs)
            assertTrue(state.isPlaying)
            // Modes still switch without losing the audio place; taps do
            // nothing with no sentences loaded.
            vm.setMode(ReaderMode.Read)
            assertEquals(ReaderMode.Read, awaitItem().mode)
            vm.onSentenceTap(1)
            assertTrue(sought.isEmpty())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun purePages_loaderStaysPdfForm() {
        val pure = """{"spec_version": "1.0", "chapter": 1, "title": "P",
            "duration_ms": 1000, "pages": [{"page": 1, "start_ms": 0}]}"""
        val result = ChapterTextLoader.parse("text/ch001.json", pure)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ChapterTextPdfForm)
    }
}
