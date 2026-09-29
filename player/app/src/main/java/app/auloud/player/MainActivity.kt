package app.auloud.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auloud.player.storage.BooksFolderStore
import app.auloud.player.storage.PrefsBooksFolderStore

/** WP1 placeholder: proves the app installs and launches. Player UI arrives in WP5/WP7. */
class MainActivity : ComponentActivity() {

    // WP3 minimal hook: runtime prompt for READ_EXTERNAL_STORAGE (needed on
    // Android 6+ to read bundles off the microSD card). Full permission UI
    // (rationale, empty/error states) arrives in WP5.
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.i(TAG, "READ_EXTERNAL_STORAGE granted=$granted")
    }

    // WP3 books-folder setting (persisted; settings UI that edits it arrives in
    // WP5). Lazy so it is only built if something reads it.
    val booksFolderStore: BooksFolderStore by lazy {
        PrefsBooksFolderStore.fromContext(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStoragePermissionIfNeeded()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Auloud — WP1 empty app",
                        modifier = Modifier.padding(24.dp)
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
}
