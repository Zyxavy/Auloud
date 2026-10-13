package app.auloud.player

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import app.auloud.player.BuildConfig
import app.auloud.player.data.AuloudDatabase
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressRepository
import app.auloud.player.data.RoomLibraryRepository
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.ingest.IngestPipeline
import app.auloud.player.library.ImportScreen
import app.auloud.player.library.ImportUiState
import app.auloud.player.library.ImportViewModel
import app.auloud.player.library.LibraryScreen
import app.auloud.player.library.LibraryViewModel
import app.auloud.player.reader.BookScreen
import app.auloud.player.reader.ReaderPreviewScreen
import app.auloud.player.render.JavaFileRenderIo
import app.auloud.player.render.RenderJobProgress
import app.auloud.player.render.RenderStateStore
import app.auloud.player.render.StaleBookScan
import app.auloud.player.settings.SettingsScreen
import app.auloud.player.storage.BooksRootResolver
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.DefaultFolderEnsurer
import app.auloud.player.storage.FileBundleStorage
import app.auloud.player.storage.FrameworkSafBackend
import app.auloud.player.storage.PrefsWatchFolderStore
import app.auloud.player.storage.RoutingBundleStorage
import app.auloud.player.storage.SafBundleStorage
import app.auloud.player.storage.StoragePermissions
import app.auloud.player.storage.WatchFolder
import app.auloud.player.storage.WatchFolderGrants
import app.auloud.player.storage.WatchFolderIntents
import app.auloud.player.storage.WatchFolderStore
import app.auloud.player.storage.WatchFolders
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.bookVoiceVersionOf
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** WP1 shell; WP5 wires the library screen. WP7 adds the player screen. */
class MainActivity : ComponentActivity() {

    // WP3/WP5 refinement: READ + WRITE together (shared-internal `/Auloud`
    // needs WRITE on Android 6+). WP5 forwards the result to the
    // LibraryViewModel so the no-permission state can retry it.
    private val storagePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = StoragePermissions.allGranted { grants[it] == true }
        Log.i(TAG, "storage granted=$granted")
        if (granted) {
            ensureDefaultFolder()
        }
        if (::libraryViewModel.isInitialized) {
            libraryViewModel.onPermissionResult(granted)
        }
    }

    // WP3/WP5 refinement: system folder picker for watch folders (SD card
    // included). A persistable grant is taken FIRST and the folder is stored
    // only when the grant succeeds, so no broken entries can persist; the
    // store then refreshes the hoisted Settings state and triggers a rescan.
    // Cancel / missing-picker / no-URI outcomes surface as library notices,
    // never just a log line.
    //
    // DEVICE-TEST (user on the Tab E): pick internal `Auloud/`, the SD-card
    // `Auloud/`, and a nested folder; abandon the picker; reboot and confirm
    // the folders still list without re-picking.
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Log.i(TAG, "folder picker cancelled")
            notice("Book folders", "No folder was added.")
            return@registerForActivityResult
        }
        val uri: Uri = result.data?.data ?: run {
            Log.w(TAG, "folder picker returned no URI")
            notice("Book folders", "The picked folder returned no location — nothing was added.")
            return@registerForActivityResult
        }
        onFolderPicked(uri)
    }

    // WP3/WP5 refinement: persisted watch-folder list. Default entry is the
    // auto-created shared-internal `/Auloud`; the legacy single
    // `books_folder` value migrates on first read.
    val watchFolderStore: WatchFolderStore by lazy {
        PrefsWatchFolderStore.fromContext(applicationContext)
    }

    // Routing storage shared by the ViewModel and the repositories: file
    // paths via FileBundleStorage, picked trees via SafBundleStorage.
    // `java.io.File` still never leaves the file branch.
    val routingStorage: BundleStorage by lazy {
        RoutingBundleStorage(FileBundleStorage()) { treeUri ->
            SafBundleStorage(
                treeUri,
                FrameworkSafBackend(applicationContext.contentResolver, treeUri)
            )
        }
    }

    // IN8: single repository graph shared by the library ViewModel and the
    // import driver (built once here so both see the same rows).
    private val database by lazy { AuloudDatabase.open(applicationContext) }
    private val libraryRepository: LibraryRepository by lazy {
        RoomLibraryRepository(
            database.bookDao(),
            routingStorage,
            progressDao = database.progressDao(),
            // FP2: book plus progress rows fall in one Room transaction.
            inTransaction = { block -> database.withTransaction { block() } }
        )
    }
    private val progressRepository: ProgressRepository by lazy {
        RoomProgressRepository(database.progressDao())
    }

    // IN8: EPUB import driver (plain class, cleared in onDestroy). The
    // picker hands a document URI; the copy lambda materializes it as a
    // cache file the importer owns and deletes afterwards.
    private val importViewModel: ImportViewModel by lazy {
        ImportViewModel(
            runImport = { file, onProgress ->
                IngestPipeline.importEpub(
                    epubFile = file,
                    booksRoot = BooksRootResolver.defaultBooksRoot(applicationContext),
                    storage = routingStorage,
                    library = libraryRepository,
                    onProgress = onProgress
                )
            }
        )
    }

    // IN8: system document picker for EPUBs (ACTION_OPEN_DOCUMENT needs no
    // new permission). A null URI is a picker cancel and stays silent.
    private val epubPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            Log.i(TAG, "epub picker cancelled")
            return@registerForActivityResult
        }
        onEpubPicked(uri)
    }

    // WP5: single-activity graph built by hand (no navigation-compose in
    // Slice 1). The ViewModel survives config changes via ViewModelProvider;
    // row taps set `selectedBookId`, which WP7 builds the player screen on.
    private lateinit var libraryViewModel: LibraryViewModel

    // Hoisted watch-folder list for Settings: activity-owned so picker
    // results, removals and startup pruning can refresh it directly (a local
    // `remember` inside setContent would go stale until Settings reopened).
    private val folderState = mutableStateOf<List<WatchFolder>>(emptyList())

    private fun refreshFolderState() {
        try {
            folderState.value = watchFolderStore.getWatchFolders()
        } catch (e: Exception) {
            Log.w(TAG, "watch folders unreadable: ${e.message}")
        }
    }

    /** Library notices channel (ViewModel may not exist yet at call time). */
    private fun notice(dir: String, reason: String) {
        if (::libraryViewModel.isInitialized) {
            libraryViewModel.addNotice(dir, reason)
        } else {
            Log.w(TAG, "notice before ViewModel ready: $dir: $reason")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libraryViewModel = ViewModelProvider(
            this, LibraryFactory(applicationContext)
        )[LibraryViewModel::class.java]
        ensureDefaultFolder()
        pruneLostGrants()
        refreshFolderState()
        requestStoragePermissionIfNeeded()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by libraryViewModel.uiState.collectAsState()
                    val importState by importViewModel.state.collectAsState()
                    // WP8: settings sits above the library/player switch and
                    // returns via Back. WP3/WP5 refinement: Settings also
                    // hosts the watch-folder list (add via picker, remove).
                    var showSettings by remember { mutableStateOf(false) }
                    // RA0 throwaway: spike screen above everything, deleted
                    // with the spike once the RA0 decision is logged.
                    var showSpike by remember { mutableStateOf(false) }
                    // Hoisted activity state (see folderState): narrow reads
                    // keep recompositions cheap on the Tab E.
                    val watchFolders by folderState
                    // WP7: two-screen switch, no navigation library. A row tap
                    // sets selectedBookId (WP5 hook); the player screen takes
                    // over, back clears the selection. The service session
                    // survives the switch, so return reconnects to the spot.
                    val selectedBook = state.books.firstOrNull { it.id == state.selectedBookId }
                    // CP8: the spike screen is debug-only; the second conjunct
                    // is a constant false in release, so R8 drops the path.
                    // IN8: the import screen sits above everything while an
                    // import runs or its result is showing.
                    if (importState != ImportUiState.Idle) {
                        ImportScreen(
                            state = importState,
                            onCancel = importViewModel::cancel,
                            onDismiss = ::dismissImport
                        )
                    } else if (showSpike && BuildConfig.DEBUG) {
                        ReaderPreviewScreen(onBack = { showSpike = false })
                    } else if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            folders = watchFolders,
                            onAddFolder = ::launchFolderPicker,
                            onRemoveFolder = ::removeWatchFolder,
                            onOpenSpike = { showSpike = true }
                        )
                    } else if (state.selectedBookId != null && selectedBook != null) {
                        // RA7: one book screen across all modes (Listen shows
                        // the Slice 1 player, Read/ReadListen the reader).
                        // IN9: unrendered books show the read-only screen
                        // (progress repo supplies the sid position).
                        // RN9: partial books show the render hub (panel plus
                        // per-chapter reader/player routing); renders and
                        // deletes rescan so the library chips follow.
                        BookScreen(
                            book = selectedBook,
                            storage = routingStorage,
                            onBack = libraryViewModel::clearSelection,
                            progress = progressRepository,
                            onBookChanged = libraryViewModel::rescan
                        )
                    } else {
                        LibraryScreen(
                            state = state,
                            onRescan = libraryViewModel::rescan,
                            onRetryPermission = libraryViewModel::onRetryPermission,
                            onBookSelected = libraryViewModel::onBookSelected,
                            onOpenSettings = {
                                refreshFolderState()
                                showSettings = true
                            },
                            onImportEpub = ::launchEpubPicker,
                            onDeleteBook = libraryViewModel::deleteBook
                        )
                    }
                }
            }
        }
    }

    /**
     * Auto-creates the shared-internal `/Auloud` default. A failed creation
     * surfaces in the library error list (existing notices channel), never
     * just a log line and never a crash.
     */
    private fun ensureDefaultFolder() {
        val defaultPath = try {
            BooksRootResolver.defaultBooksRoot(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "default books root unavailable: ${e.message}")
            notice("Book folders", "The default books folder is unavailable: ${e.message}")
            return
        }
        val dir = File(defaultPath)
        val result = DefaultFolderEnsurer.ensure(
            exists = try {
                dir.exists()
            } catch (e: Exception) {
                false
            },
            mkdirs = { dir.mkdirs() },
            pathForMessage = defaultPath
        )
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "could not create books folder"
            Log.w(TAG, "default folder unavailable: $message")
            notice(
                WatchFolders.displayPath(defaultPath),
                "$defaultPath: $message — check storage permission and free space."
            )
        }
    }

    /**
     * Startup reconciliation: drops picked trees whose persistable grant is
     * gone (reboot/revocation survival). The drop is surfaced as a library
     * notice naming each folder — never silent — so the user knows to
     * re-add it in Settings.
     */
    private fun pruneLostGrants() {
        val granted = try {
            contentResolver.persistedUriPermissions.map { it.uri.toString() }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "persisted grants unreadable: ${e.message}")
            return
        }
        val folders = try {
            watchFolderStore.getWatchFolders()
        } catch (e: Exception) {
            Log.w(TAG, "watch folders unreadable: ${e.message}")
            return
        }
        val pruned = WatchFolderGrants.prune(folders, granted)
        if (pruned.dropped.isEmpty()) return
        try {
            watchFolderStore.setWatchFolders(pruned.kept)
        } catch (e: Exception) {
            Log.w(TAG, "pruned folders not saved: ${e.message}")
        }
        for (dropped in pruned.dropped) {
            val label = WatchFolders.displayName(dropped)
            Log.i(TAG, "dropped folder without persisted grant: $label")
            notice(
                label,
                "$label: folder permission was lost — please re-add it in Settings."
            )
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        if (!StoragePermissions.allGranted { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        ) {
            storagePermissions.launch(StoragePermissions.required())
        }
    }

    private fun launchFolderPicker() {
        try {
            folderPicker.launch(WatchFolderIntents.newIntent())
        } catch (e: Exception) {
            Log.w(TAG, "folder picker unavailable: ${e.message}")
            notice("Book folders", "The folder picker is unavailable on this device.")
        }
    }

    /**
     * IN8: launches the system EPUB picker (no permission needed; the
     * picked bytes are copied to cache by [copyUriToCache] and imported
     * from there).
     */
    private fun launchEpubPicker() {
        try {
            epubPicker.launch(arrayOf("application/epub+zip"))
        } catch (e: Exception) {
            Log.w(TAG, "epub picker unavailable: ${e.message}")
            notice("Import", "The file picker is unavailable on this device.")
        }
    }

    /**
     * IN8: hands the picked document to the import driver. The display
     * name is best-effort (the picker label on the progress screen); the
     * copy lambda streams the document into a cache file the importer
     * owns. A second pick while an import runs is refused with a notice
     * instead of mixing two books into one progress screen.
     */
    private fun onEpubPicked(uri: Uri) {
        val displayName = displayNameFor(uri)
        val started = importViewModel.startImport(displayName) {
            copyUriToCache(uri, displayName)
        }
        if (!started) {
            Log.i(TAG, "import already running, picker result ignored")
            notice("Import", "An import is already running - please wait for it to finish.")
        }
    }

    /**
     * IN8: result-screen dismiss. A fresh import (and only that) triggers
     * a rescan so the new row plus its "Not rendered" chip appear;
     * duplicates, failures and cancels leave nothing behind and need
     * none. The scratch cache file is already deleted by the importer.
     */
    private fun dismissImport() {
        val succeeded = importViewModel.state.value is ImportUiState.Succeeded
        importViewModel.reset()
        if (succeeded) {
            libraryViewModel.rescan()
        }
    }

    private fun displayNameFor(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } ?: "book.epub"
        } catch (e: Exception) {
            Log.w(TAG, "display name unreadable: ${e.message}")
            "book.epub"
        }
    }

    /**
     * IN8: streams the picked document into a cache scratch file (8 KB
     * chunks, cancellable per chunk so the cancel button works while
     * copying). Throws file-plus-rule `IOException` on failure; the
     * importer shapes it into the copy-failure message.
     */
    private suspend fun copyUriToCache(uri: Uri, displayName: String): File {
        val target = File(cacheDir, "import-${System.currentTimeMillis()}.epub")
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("$displayName: picked document cannot be opened")
            input.use { inputStream ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = inputStream.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            try {
                target.delete()
            } catch (_: Exception) {
            }
            if (e is java.io.IOException) throw e
            throw java.io.IOException("$displayName: cannot read picked file (${e.message})", e)
        }
        return target
    }

    /**
     * Persists the picked folder ONLY after `takePersistableUriPermission`
     * succeeds — a failed grant surfaces a notice and stores nothing, so no
     * broken entries can accumulate. The hoisted Settings state refreshes
     * right after the store write (a later rescan cannot change the list),
     * then rescan imports from the new folder.
     */
    private fun onFolderPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, WatchFolderIntents.persistFlags()
            )
        } catch (e: Exception) {
            Log.w(TAG, "persistable grant failed for $uri: ${e.message}")
            notice(
                "Book folders",
                "Could not keep access to the picked folder — it was not added."
            )
            return
        }
        try {
            watchFolderStore.addFolder(WatchFolder.TreeUri(uri.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "watch folder not saved: ${e.message}")
            notice("Book folders", "The picked folder could not be saved — it was not added.")
            return
        }
        refreshFolderState()
        libraryViewModel.rescan()
    }

    /**
     * Removes a watch folder and releases its persistable grant (tree URIs
     * only; file paths hold no grant). Grant release is best-effort — an
     * already-released grant throws and is fine — then the store, the
     * hoisted state and a rescan follow.
     */
    private fun removeWatchFolder(folder: WatchFolder) {
        if (folder is WatchFolder.TreeUri) {
            try {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(folder.uriString), WatchFolderIntents.persistFlags()
                )
            } catch (e: SecurityException) {
                Log.i(TAG, "grant already released for ${WatchFolders.displayName(folder)}")
            } catch (e: Exception) {
                Log.w(TAG, "grant release failed: ${e.message}")
            }
        }
        try {
            watchFolderStore.removeFolder(folder)
        } catch (e: Exception) {
            Log.w(TAG, "remove folder failed: ${e.message}")
            notice("Book folders", "The folder could not be removed — please retry.")
            return
        }
        refreshFolderState()
        libraryViewModel.rescan()
    }

    override fun onDestroy() {
        importViewModel.clear()
        super.onDestroy()
    }

    /**
     * RN9: render job progress for one bundle dir (library chips).
     * Best effort and never throwing (a throw reads as no job): SAF
     * tokens have no `java.io.File` meaning, so they read as no job.
     */
    private fun readRenderJobProgress(bundleDir: String): RenderJobProgress? {        return try {
            val job = RenderStateStore.load(bundleDir, JavaFileRenderIo()).getOrNull()
                ?: return null
            val total = job.plan.orderedChapters.size
            if (total <= 0) return null
            RenderJobProgress(
                done = job.completedChapters.size,
                total = total,
                state = job.state
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "AuloudMain"
    }

    /**
     * VS5: stale chapter count for one imported book (library chips).
     * Best effort and never throwing (null reads as no chip):
     * read-only books and books with nothing rendered scan as null, and
     * rendered chapter texts stream one at a time through the routing
     * storage (file and picked-folder books alike).
     */
    private fun readStaleCount(
        manifest: app.auloud.player.bundle.Manifest,
        bundleDir: String
    ): Int? {
        return try {
            val root = bundleDir.trimEnd('/')
            StaleBookScan.scan(
                manifest = manifest,
                readChapterText = { rel ->
                    try {
                        routingStorage.readText("$root/$rel")
                    } catch (_: Exception) {
                        null
                    }
                },
                globals = PrefsTtsStore.fromContext(applicationContext),
                versionOf = bookVoiceVersionOf(applicationContext)
            )?.summary?.stale
        } catch (_: Exception) {
            null
        }
    }

    /**
     * WP5: hand-written factory (no DI framework in Slice 1). Reuses the
     * activity-owned repository graph (IN8: shared with the import driver)
     * and wires the WP3 permission launcher into the ViewModel's retry
     * action.
     */
    private inner class LibraryFactory(
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val storage: BundleStorage = routingStorage
            return LibraryViewModel(
                storage = storage,
                watchFolderStore = watchFolderStore,
                libraryRepository = libraryRepository,
                progressRepository = progressRepository,
                isPermissionGranted = {
                    StoragePermissions.allGranted { permission ->
                        checkSelfPermission(permission) ==
                            PackageManager.PERMISSION_GRANTED
                    }
                },
                requestPermission = {
                    storagePermissions.launch(StoragePermissions.required())
                },
                // RN9: render job progress for the library chips
                // (`render-job.json` per book; best effort, never throws).
                renderJobReader = { bundleDir ->
                    readRenderJobProgress(bundleDir)
                },
                // VS5: stale chapter counts for the library chips
                // (`StaleBookScan` per book; best effort, never throws).
                staleReader = { manifest, bundleDir ->
                    readStaleCount(manifest, bundleDir)
                }
            ) as T
        }
    }
}
