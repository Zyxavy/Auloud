package app.auloud.player.library

import app.auloud.player.ingest.ImportProgress
import app.auloud.player.ingest.ImportReport
import app.auloud.player.ingest.IngestOutcome
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * IN8: [ImportViewModel] state machine on plain JVM (Slice 9).
 *
 * The pipeline is a [RunImport] fake (immediate outcomes, progress
 * capture, or a hanging import for cancel/busy); the copy source is a
 * lambda over temp files. The scope is [Dispatchers.Unconfined], so every
 * step runs eagerly and assertions read `state.value` directly (the
 * VoiceAudition test pattern, no turbine needed).
 */
class ImportViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val progressSeen = ArrayList<ImportProgress>()

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        progressSeen.clear()
    }

    private fun viewModel(runImport: RunImport) = ImportViewModel(
        runImport = runImport,
        ioDispatcher = Dispatchers.Unconfined,
        externalScope = scope
    )

    private fun report() = ImportReport(
        bookId = "book-1",
        bundleDir = "/books/book-1",
        title = "Imported Book",
        author = "A. Author",
        chapters = 2,
        words = 500,
        drops = listOf("ch1.xhtml: dropped empty heading (empty)"),
        warnings = listOf("ch2.xhtml: unbalanced quote treated as narration"),
        elapsedMs = 123L
    )

    private fun scratch(): File {
        val file = temp.newFile("picked.epub")
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file
    }

    @Test
    fun success_reportsSummaryAndDeletesScratch() {
        val vm = viewModel { _, _ -> IngestOutcome.Imported(report()) }
        val file = scratch()

        assertTrue(vm.startImport("book.epub", copySource = { file }))
        try {
            val state = vm.state.value as? ImportUiState.Succeeded
                ?: throw AssertionError("want Succeeded, got ${vm.state.value}")
            assertEquals("Imported Book", state.report.title)
            assertEquals(2, state.report.chapters)
            assertEquals(500, state.report.words)
            assertEquals(1, state.report.drops.size)
            assertEquals(1, state.report.warnings.size)
            assertFalse("scratch cache must be deleted", file.exists())
        } finally {
            vm.clear()
        }
    }

    @Test
    fun progress_flowsThroughImportingState() {
        val vm = viewModel { _, onProgress ->
            onProgress(ImportProgress(1, 1, 2, "First"))
            onProgress(ImportProgress(2, 2, 2, "Second"))
            IngestOutcome.Imported(report())
        }

        vm.startImport("book.epub", copySource = { scratch() })
        try {
            assertTrue(vm.state.value is ImportUiState.Succeeded)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun cancel_midImport_leavesCancelledAndDeletesScratch(): Unit = runBlocking {
        val gate = CompletableDeferred<IngestOutcome>()
        val vm = viewModel { _, _ -> gate.await() }
        val file = scratch()

        assertTrue(vm.startImport("book.epub", copySource = { file }))
        assertTrue(vm.state.value is ImportUiState.Importing)
        vm.cancel()
        delay(50)

        try {
            assertTrue("want Cancelled, got ${vm.state.value}", vm.state.value is ImportUiState.Cancelled)
            assertFalse("scratch cache must be deleted on cancel", file.exists())
        } finally {
            vm.clear()
        }
    }

    @Test
    fun busySecondStart_isIgnored(): Unit = runBlocking {
        val gate = CompletableDeferred<IngestOutcome>()
        val vm = viewModel { _, _ -> gate.await() }

        assertTrue(vm.startImport("first.epub", copySource = { scratch() }))
        assertFalse(vm.startImport("second.epub", copySource = { scratch() }))
        val importing = vm.state.value as? ImportUiState.Importing
            ?: throw AssertionError("want Importing, got ${vm.state.value}")
        assertEquals("first.epub", importing.displayName)
        vm.cancel()
        delay(50)
        try {
            assertTrue(vm.state.value is ImportUiState.Cancelled)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun duplicate_reportsAlreadyInLibrary() {
        val vm = viewModel { _, _ -> IngestOutcome.Duplicate("book-9", "/books/book-9") }

        vm.startImport("book.epub", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.AlreadyInLibrary
                ?: throw AssertionError("want AlreadyInLibrary, got ${vm.state.value}")
            assertEquals("book-9", state.bookId)
            assertEquals("/books/book-9", state.bundleDir)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun drmFailure_mapsHeadlineAndKeepsRuleToken() {
        val raw = "drm.epub: found META-INF/encryption.xml (DRM-protected books are not supported)"
        val vm = viewModel { _, _ -> IngestOutcome.Failed(listOf(raw)) }

        vm.startImport("drm.epub", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertTrue("headline names DRM, was: ${state.headline}", "DRM" in state.headline)
            assertEquals(listOf(raw), state.details)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun corruptFailure_mapsHeadlineAndKeepsRuleToken() {
        val raw = "corrupt.epub: not a valid ZIP archive (truncated)"
        val vm = viewModel { _, _ -> IngestOutcome.Failed(listOf(raw)) }

        vm.startImport("corrupt.epub", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertTrue("headline names corruption, was: ${state.headline}", "corrupt" in state.headline)
            assertEquals(listOf(raw), state.details)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun unsupportedFailure_mapsGenericHeadlineAndKeepsRuleToken() {
        val raw = "book.pdf: PDF books stay on the PC (unsupported on this device)"
        val vm = viewModel { _, _ -> IngestOutcome.Failed(listOf(raw)) }

        vm.startImport("odd.fb2", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertTrue("headline is generic, was: ${state.headline}", "could not be imported" in state.headline)
            assertEquals(listOf(raw), state.details)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun oversizeFailure_mapsHeadlineAndKeepsRuleToken() {
        val raw = "big.epub: total uncompressed size exceeds limit (over 268435456 bytes)"
        val vm = viewModel { _, _ -> IngestOutcome.Failed(listOf(raw)) }

        vm.startImport("big.epub", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertTrue("headline names size, was: ${state.headline}", "too large" in state.headline)
            assertEquals(listOf(raw), state.details)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun emptyBookFailure_mapsHeadlineAndKeepsRuleToken() {
        val raw = "cover.epub: no readable chapters (every spine item was skipped or dropped)"
        val vm = viewModel { _, _ -> IngestOutcome.Failed(listOf(raw)) }

        vm.startImport("cover.epub", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertTrue(
                "headline names empty book, was: ${state.headline}",
                "No readable chapters" in state.headline
            )
            assertEquals(listOf(raw), state.details)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun raceLoserFailure_surfacesRenameRefusalVerbatim() {
        val raw = "/books/id: book folder already exists (duplicate import?)"
        val vm = viewModel { _, _ -> IngestOutcome.Failed(listOf(raw)) }

        vm.startImport("book.epub", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertTrue(
                "loser headline, was: ${state.headline}",
                "just finished importing" in state.headline
            )
            assertEquals("rename refusal must stay verbatim", listOf(raw), state.details)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun multipleFailures_countExtraAndKeepAllTokens() {
        val first = "a.epub: not a valid ZIP archive (truncated)"
        val second = "a.epub: OPF/content.opf: spine missing or empty (no itemrefs)"
        val vm = viewModel { _, _ -> IngestOutcome.Failed(listOf(first, second)) }

        vm.startImport("a.epub", copySource = { scratch() })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertTrue("counts the extra error, was: ${state.headline}", "plus 1 more" in state.headline)
            assertEquals(listOf(first, second), state.details)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun copyFailure_reportsUnreadablePick() {
        val vm = viewModel { _, _ -> IngestOutcome.Imported(report()) }

        vm.startImport("gone.epub", copySource = {
            throw IOException("picked document cannot be opened")
        })
        try {
            val state = vm.state.value as? ImportUiState.Failed
                ?: throw AssertionError("want Failed, got ${vm.state.value}")
            assertEquals("gone.epub", state.displayName)
            assertTrue(
                "headline names the pick problem, was: ${state.headline}",
                "Could not read the picked file" in state.headline
            )
            assertEquals(1, state.details.size)
            assertTrue("detail names the file, was: ${state.details}", "gone.epub" in state.details.single())
        } finally {
            vm.clear()
        }
    }

    @Test
    fun reset_returnsToIdleFromTerminalState() {
        val vm = viewModel { _, _ -> IngestOutcome.Imported(report()) }

        vm.startImport("book.epub", copySource = { scratch() })
        try {
            assertTrue(vm.state.value is ImportUiState.Succeeded)
            vm.reset()
            assertTrue(vm.state.value is ImportUiState.Idle)
        } finally {
            vm.clear()
        }
    }

    @Test
    fun reset_ignoredWhileRunning(): Unit = runBlocking {
        val gate = CompletableDeferred<IngestOutcome>()
        val vm = viewModel { _, _ -> gate.await() }

        vm.startImport("book.epub", copySource = { scratch() })
        vm.reset()
        try {
            assertTrue("running import must survive reset", vm.state.value is ImportUiState.Importing)
        } finally {
            vm.cancel()
            delay(50)
            vm.clear()
        }
    }
}
