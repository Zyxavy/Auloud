package app.auloud.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import app.auloud.player.storage.BooksFolderStore
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.FileBundleStorage
import app.auloud.player.storage.PrefsBooksFolderStore

/** WP1 shell; WP5 wires the library screen. Player screen arrives in WP7. */
class MainActivity : ComponentActivity() {

    // WP3 minimal hook: runtime prompt for READ_EXTERNAL_STORAGE (needed on
    // Android 6+ to read bundles off the microSD card). WP5 forwards the
    // result to the LibraryViewModel so the no-permission state can retry it.
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "READ_EXTERNAL_STORAGE granted=$granted")
        if (::libraryViewModel.isInitialized) {
            libraryViewModel.onPermissionResult(granted)
        }
    }

    // WP3 books-folder setting (persisted; settings UI that edits it arrives in
    // WP5). Lazy so it is only built if something reads it.
    val booksFolderStore: BooksFolderStore by lazy {
        PrefsBooksFolderStore.fromContext(applicationContext)
    }

    // WP5: single-activity graph built by hand (no navigation-compose in
    // Slice 1). The ViewModel survives config changes via ViewModelProvider;
    // row taps set `selectedBookId`, which WP7 builds the player screen on.
    private lateinit var libraryViewModel: LibraryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        libraryViewModel = ViewModelProvider(
            this, LibraryFactory(applicationContext)
        )[LibraryViewModel::class.java]
        requestStoragePermissionIfNeeded()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by libraryViewModel.uiState.collectAsState()
                    LibraryScreen(
                        state = state,
                        onRescan = libraryViewModel::rescan,
                        onRetryPermission = libraryViewModel::onRetryPermission,
                        onBookSelected = libraryViewModel::onBookSelected
                    )
                }
            }
        }
    }

    private fun requestStoragePermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
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
            val storage: BundleStorage = FileBundleStorage()
            val database = AuloudDatabase.open(appContext)
            val libraryRepository: LibraryRepository =
                RoomLibraryRepository(database.bookDao(), storage)
            val progressRepository: ProgressRepository =
                RoomProgressRepository(database.progressDao())
            return LibraryViewModel(
                storage = storage,
                booksFolderStore = booksFolderStore,
                libraryRepository = libraryRepository,
                progressRepository = progressRepository,
                isPermissionGranted = {
                    checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
                },
                requestPermission = {
                    storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            ) as T
        }
    }
}
