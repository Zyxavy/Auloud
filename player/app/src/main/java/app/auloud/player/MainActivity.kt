package app.auloud.player

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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
import app.auloud.player.data.AuloudDatabase
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressRepository
import app.auloud.player.data.RoomLibraryRepository
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.library.LibraryScreen
import app.auloud.player.library.LibraryViewModel
import app.auloud.player.playback.PlayerScreen
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
import java.io.File

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
                    // WP8: settings sits above the library/player switch and
                    // returns via Back. WP3/WP5 refinement: Settings also
                    // hosts the watch-folder list (add via picker, remove).
                    var showSettings by remember { mutableStateOf(false) }
                    // Hoisted activity state (see folderState): narrow reads
                    // keep recompositions cheap on the Tab E.
                    val watchFolders by folderState
                    // WP7: two-screen switch, no navigation library. A row tap
                    // sets selectedBookId (WP5 hook); the player screen takes
                    // over, back clears the selection. The service session
                    // survives the switch, so return reconnects to the spot.
                    val selectedBook = state.books.firstOrNull { it.id == state.selectedBookId }
                    if (showSettings) {
                        SettingsScreen(
                            onBack = { showSettings = false },
                            folders = watchFolders,
                            onAddFolder = ::launchFolderPicker,
                            onRemoveFolder = ::removeWatchFolder
                        )
                    } else if (state.selectedBookId != null && selectedBook != null) {
                        PlayerScreen(
                            book = selectedBook,
                            onBack = libraryViewModel::clearSelection
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
                            }
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

    companion object {
        private const val TAG = "AuloudMain"
    }

    /**
     * WP5: hand-written factory (no DI framework in Slice 1). Builds the
     * storage + repository graph from WP3/WP4 pieces and wires the WP3
     * permission launcher into the ViewModel's retry action.
     */
    private inner class LibraryFactory(
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val storage: BundleStorage = routingStorage
            val database = AuloudDatabase.open(appContext)
            val libraryRepository: LibraryRepository =
                RoomLibraryRepository(database.bookDao(), storage)
            val progressRepository: ProgressRepository =
                RoomProgressRepository(database.progressDao())
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
                }
            ) as T
        }
    }
}
