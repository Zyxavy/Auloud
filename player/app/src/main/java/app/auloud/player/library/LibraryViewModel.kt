package app.auloud.player.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.data.BookEntity
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressRepository
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.WatchFolder
import app.auloud.player.storage.WatchFolderStore
import app.auloud.player.storage.WatchFolders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * WP5: one row of the library list.
 *
 * [progressFraction] is a Slice 1 approximation: the saved chapter position
 * over the book's total duration, clamped to 0..1 (the ViewModel does not
 * read chapter tables, only [BookEntity.durationMs]). WP7 shows exact
 * chapter progress.
 */
data class BookUiModel(
    val id: String,
    val title: String,
    val author: String?,
    /** Absolute cover path, or null when the bundle has no readable cover. */
    val coverPath: String?,
    val durationMs: Long,
    val progressFraction: Float,
    val isMissing: Boolean
)

/**
 * WP5: a bundle folder that was skipped during rescan. [reason] always names
 * the file and the rule broken (WP2 validation message format, e.g.
 * `manifest.json: chapter 1 audio file missing audio/ch001.mp3`).
 */
data class ImportError(val bundleDir: String, val reason: String)

/**
 * WP5 library screen state. [selectedBookId] is the minimal WP7 hook: set on
 * row tap, cleared by the player screen when it takes over.
 */
data class LibraryUiState(
    val hasPermission: Boolean = false,
    val isScanning: Boolean = false,
    val books: List<BookUiModel> = emptyList(),
    val errors: List<ImportError> = emptyList(),
    val selectedBookId: String? = null
)

/**
 * WP5: scans the watch folders and exposes imported books. WP3/WP5
 * refinement: rescan covers EVERY entry of [WatchFolderStore] (file-path
 * folders via `FileBundleStorage`, tree-URI folders via `SafBundleStorage`,
 * both behind the single [BundleStorage] routing delegate) and aggregates
 * books plus skipped-with-reason errors across folders.
 *
 * Depends on WP3 ([BundleStorage], [WatchFolderStore]) and WP4
 * ([LibraryRepository], [ProgressRepository]) as-is. The ViewModel performs
 * no file I/O itself and never touches `java.io.File`: manifest text comes
 * from [BundleStorage.readText], parsing from WP2 [BundleParser] (`parseText`
 * seam, so SAF document text uses the same rules with no duplication), and
 * validation from WP2 [BundleValidator] with the existence-check seam wired
 * to [BundleStorage.exists] — one rule implementation, so the messages can
 * never drift from WP2.
 *
 * API 24 safe: string path joins, no `java.time`. (No `android.util.Log`
 * here on purpose: the ViewModel stays plain-JVM-testable; lifecycle and
 * progress events are logged by the activity/service layers.)
 */
class LibraryViewModel(
    private val storage: BundleStorage,
    private val watchFolderStore: WatchFolderStore,
    private val libraryRepository: LibraryRepository,
    private val progressRepository: ProgressRepository,
    private val isPermissionGranted: () -> Boolean,
    private val requestPermission: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    externalScope: CoroutineScope? = null
) : ViewModel() {

    // `viewModelScope` is only touched when no test scope is supplied, so
    // plain-JVM unit tests never need `Dispatchers.Main`.
    private val scope: CoroutineScope by lazy { externalScope ?: viewModelScope }

    private val books: StateFlow<List<BookEntity>> by lazy {
        libraryRepository.books().stateIn(scope, SharingStarted.Eagerly, emptyList())
    }
    private val progressPositions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val errors = MutableStateFlow<List<ImportError>>(emptyList())
    private val isScanning = MutableStateFlow(false)
    private val hasPermission = MutableStateFlow(false)
    private val selectedBookId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<LibraryUiState> by lazy {
        val booksPart = combine(books, progressPositions, errors) { bookList, positions, errorList ->
            Triple(bookList, positions, errorList)
        }
        val flagsPart =
            combine(isScanning, hasPermission, selectedBookId) { scanning, permission, selected ->
                Triple(scanning, permission, selected)
            }
        combine(booksPart, flagsPart) { left, right ->
            val (bookList, positions, errorList) = left
            val (scanning, permission, selected) = right
            LibraryUiState(
                hasPermission = permission,
                isScanning = scanning,
                books = bookList.map { book ->
                    BookUiModel(
                        id = book.id,
                        title = book.title,
                        author = book.author,
                        coverPath = book.coverPath,
                        durationMs = book.durationMs,
                        progressFraction = progressFraction(
                            book.durationMs, positions[book.id]
                        ),
                        isMissing = book.isMissing
                    )
                },
                errors = errorList,
                selectedBookId = selected
            )
        }.stateIn(
            scope, SharingStarted.Eagerly,
            LibraryUiState(hasPermission = hasPermission.value)
        )
    }

    init {
        hasPermission.value = isPermissionGranted()
        // Touch the lazy flows so collection starts even before the UI
        // subscribes (unit tests read `uiState` only after acting).
        @Suppress("UNUSED_EXPRESSION")
        uiState
        scope.launch {
            books.collect { refreshProgress(it) }
        }
        if (hasPermission.value) {
            rescan()
        }
    }

    /** Re-scans ALL watch folders: imports valid bundles, records per-bundle errors. Never throws. */
    fun rescan() {
        if (isScanning.value) return
        if (!isPermissionGranted()) {
            hasPermission.value = false
            return
        }
        hasPermission.value = true
        scope.launch(ioDispatcher) {
            isScanning.value = true
            try {
                val folders = try {
                    watchFolderStore.getWatchFolders()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors.value = listOf(
                        ImportError(
                            "<watch-folders>",
                            "watch folders: cannot read folder list: ${e.message}"
                        )
                    )
                    return@launch
                }
                val failures = mutableListOf<ImportError>()
                val allDirs = mutableListOf<String>()
                for (folder in folders) {
                    val root = WatchFolders.rootString(folder)
                    val dirs = try {
                        storage.listBundleDirs(root)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures += ImportError(
                            root, "$root: cannot list books folder: ${e.message}"
                        )
                        continue
                    }
                    for (dir in dirs) {
                        try {
                            val text = storage.readText(join(dir, MANIFEST_FILE))
                            val manifest = BundleParser.parseText(text).getOrElse { throw it }
                            val problems = BundleValidator.validate(dir, manifest, storage::exists)
                            if (problems.isNotEmpty()) {
                                failures += ImportError(dir, problems.joinToString("; "))
                                continue
                            }
                            val imported = libraryRepository.importBundle(dir, manifest)
                            if (imported.isFailure) {
                                val reason = imported.exceptionOrNull()?.message
                                    ?: "$dir: manifest.json: import failed"
                                failures += ImportError(dir, reason)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failures += ImportError(
                                dir, e.message ?: "$dir: manifest.json: import failed"
                            )
                        }
                    }
                    allDirs += dirs
                }
                try {
                    val missing = libraryRepository.refreshMissing(allDirs)
                    if (missing.isFailure) {
                        failures += ImportError(
                            "<watch-folders>",
                            missing.exceptionOrNull()?.message
                                ?: "could not refresh missing books"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += ImportError(
                        "<watch-folders>",
                        e.message ?: "could not refresh missing books"
                    )
                }
                errors.value = failures
            } finally {
                isScanning.value = false
            }
        }
    }

    /** Retry button in the no-permission state: re-runs the WP3 runtime prompt. */
    fun onRetryPermission() {
        requestPermission()
    }

    /** Called by the host activity with the WP3 permission launcher result. */
    fun onPermissionResult(granted: Boolean) {
        hasPermission.value = granted
        if (granted) {
            rescan()
        }
    }

    /** Row tap hook that WP7 builds the player screen on. */
    fun onBookSelected(bookId: String) {
        selectedBookId.value = bookId
    }

    /** Called by the player screen (WP7) once it takes over the selection. */
    fun clearSelection() {
        selectedBookId.value = null
    }

    private suspend fun refreshProgress(list: List<BookEntity>) {
        if (list.isEmpty()) {
            progressPositions.value = emptyMap()
            return
        }
        val positions = mutableMapOf<String, Long>()
        for (book in list) {
            val positionMs = try {
                progressRepository.load(book.id).getOrNull()?.positionMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (positionMs != null) {
                positions[book.id] = positionMs
            }
        }
        progressPositions.value = positions
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    companion object {
        private const val MANIFEST_FILE = "manifest.json"
    }
}

/**
 * Maps a saved position to a 0..1 progress fraction. No saved position (or a
 * non-positive total) means 0; positions past the end clamp to 1.
 */
internal fun progressFraction(durationMs: Long, positionMs: Long?): Float {
    if (positionMs == null || durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
