package app.auloud.player.library

import app.auloud.player.ingest.ImportProgress
import app.auloud.player.ingest.ImportReport
import app.auloud.player.ingest.IngestOutcome
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * IN8: EPUB import driver for the picker action (Slice 9).
 *
 * The activity picks a document (no permission needed) and hands this
 * ViewModel a [copySource] that materializes the picked bytes as a file;
 * the ViewModel copies, imports via [runImport] (production wires
 * `IngestPipeline.importEpub` with the books root, storage and library),
 * and publishes [ImportUiState]. The file [copySource] produces is
 * scratch-owned by the importer and deleted best-effort on every terminal
 * path (success, duplicate, failure, cancel); callers must only ever hand
 * over cache copies, never the user's original.
 *
 * Cancellation stays a state, never an outcome: [cancel] raises
 * `CancellationException` in the job (the pipeline deletes its own
 * residue) and the state becomes [ImportUiState.Cancelled]. Pipeline
 * `Failed` errors reach the screen through [ImportErrors] with the raw
 * file-plus-rule strings kept verbatim in the details.
 *
 * Single flight: [startImport] while a job is active is ignored (returns
 * false), so one state machine never mixes two books. Same-book
 * serialization below the UI lives in the pipeline ([ImportLocks]);
 * different books could import concurrently through direct pipeline
 * calls, but this screen does one at a time.
 *
 * API 24 safe: coroutines only. Plain class (like `VoiceAuditionViewModel`)
 * so JVM tests drive it with an injected scope and never need Main.
 */
typealias RunImport =
    suspend (epubFile: File, onProgress: suspend (ImportProgress) -> Unit) -> IngestOutcome

/** IN8: import screen states (thin Compose renders these, owns nothing). */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Copying(val displayName: String) : ImportUiState
    data class Importing(
        val displayName: String,
        val chaptersDone: Int,
        val totalChapters: Int,
        val chapterTitle: String
    ) : ImportUiState
    data class Succeeded(val report: ImportReport) : ImportUiState
    data class AlreadyInLibrary(val bookId: String, val bundleDir: String) : ImportUiState
    data class Failed(
        val displayName: String,
        val headline: String,
        val details: List<String>
    ) : ImportUiState
    data object Cancelled : ImportUiState
}

class ImportViewModel(
    private val runImport: RunImport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    externalScope: CoroutineScope? = null
) {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state

    // Own scope when the UI owns this (mirrors VoiceAuditionViewModel);
    // tests inject an external scope instead.
    private val ownedScope: CoroutineScope? =
        if (externalScope == null) CoroutineScope(SupervisorJob() + dispatcher) else null
    private val scope: CoroutineScope = externalScope ?: ownedScope!!

    private var job: Job? = null

    /**
     * Starts copying [copySource] then importing it. Returns false without
     * doing anything when an import is already running. [displayName] is
     * the picker label shown on the progress screen.
     */
    fun startImport(displayName: String, copySource: suspend () -> File): Boolean {
        if (job?.isActive == true) return false
        _state.value = ImportUiState.Copying(displayName)
        job = scope.launch(ioDispatcher) {
            var scratch: File? = null
            try {
                val copied = try {
                    copySource()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val detail = "$displayName: cannot read picked file (${e.message})"
                    _state.value = ImportUiState.Failed(
                        displayName = displayName,
                        headline = "Could not read the picked file.",
                        details = listOf(detail)
                    )
                    return@launch
                }
                scratch = copied
                _state.value = ImportUiState.Importing(displayName, 0, 0, "")
                val outcome = runImport(copied) { progress ->
                    _state.value = ImportUiState.Importing(
                        displayName = displayName,
                        chaptersDone = progress.chaptersDone,
                        totalChapters = progress.totalChapters,
                        chapterTitle = progress.chapterTitle
                    )
                }
                _state.value = when (outcome) {
                    is IngestOutcome.Imported -> ImportUiState.Succeeded(outcome.report)
                    is IngestOutcome.Duplicate ->
                        ImportUiState.AlreadyInLibrary(outcome.bookId, outcome.bundleDir)
                    is IngestOutcome.Failed -> ImportUiState.Failed(
                        displayName = displayName,
                        headline = ImportErrors.headlineForAll(outcome.errors),
                        details = outcome.errors.toList()
                    )
                }
            } catch (e: CancellationException) {
                _state.value = ImportUiState.Cancelled
                throw e
            } finally {
                deleteScratch(scratch)
            }
        }
        return true
    }

    /** Cancels the running copy/import; the state becomes Cancelled. */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Returns to Idle from a terminal state (the result screen's dismiss
     * action). Ignored while an import is running.
     */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = ImportUiState.Idle
    }

    /** Releases the owned scope (the activity calls this on destroy). */
    fun clear() {
        job?.cancel()
        ownedScope?.cancel()
    }

    private fun deleteScratch(scratch: File?) {
        if (scratch == null) return
        try {
            scratch.delete()
        } catch (_: Exception) {
        }
    }
}
