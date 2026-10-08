package app.auloud.player.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.auloud.player.R
import app.auloud.player.render.renderChipText
import coil.compose.AsyncImage

/**
 * WP5: library screen. Narrow recomposition scopes for the slow Tab E: rows
 * take a stable [BookUiModel] plus a callback, and Lazy items are keyed by
 * book id. Covers load through Coil [AsyncImage] with a placeholder/error
 * drawable -- never `SubcomposeAsyncImage` in the scrolling list.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onRescan: () -> Unit,
    onRetryPermission: () -> Unit,
    onBookSelected: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onImportEpub: () -> Unit = {},
    onDeleteBook: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!state.hasPermission) {
        NoPermissionState(onRetryPermission, modifier)
        return
    }
    // IN8: delete confirmation target (dialog state lives here; the
    // ViewModel only sees the confirmed id).
    var pendingDelete by remember { mutableStateOf<BookUiModel?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Library", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // WP8: minimal settings affordance (battery-optimization entry
                // only; a full settings screen is out of scope).
                TextButton(onClick = onOpenSettings) {
                    Text("Settings")
                }
                // IN8: on-device EPUB import (system picker, no permission).
                TextButton(onClick = onImportEpub) {
                    Text("Import EPUB")
                }
                Button(onClick = onRescan, enabled = !state.isScanning) {
                    Text(if (state.isScanning) "Scanning…" else "Rescan")
                }
            }
        }
        if (state.isScanning && state.books.isEmpty() && state.errors.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Scanning books folder…")
            }
            return
        }
        if (state.books.isEmpty() && state.errors.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No books yet.")
                Spacer(Modifier.height(8.dp))
                Text("Copy a bundle into the books folder, then rescan.")
            }
            return
        }
        LibraryList(
            state,
            onBookSelected,
            onDeleteRequest = { pendingDelete = it },
            Modifier.weight(1f)
        )
        pendingDelete?.let { book ->
            DeleteConfirmDialog(
                title = book.title,
                onConfirm = {
                    onDeleteBook(book.id)
                    pendingDelete = null
                },
                onDismiss = { pendingDelete = null }
            )
        }
    }
}

@Composable
private fun LibraryList(
    state: LibraryUiState,
    onBookSelected: (String) -> Unit,
    onDeleteRequest: (BookUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(state.books, key = { it.id }) { book ->
            BookRow(book, onBookSelected, onDeleteRequest)
        }
        if (state.errors.isNotEmpty()) {
            item(key = "skipped-header") {
                Text(
                    text = "Skipped bundles",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                )
            }
            // Index keys: error labels are display names (two folders can
            // share a tail like "Auloud"), so the dir alone is not unique.
            items(state.errors.size, key = { index -> "error:$index" }) { index ->
                ImportErrorRow(state.errors[index])
            }
        }
    }
}

@Composable
private fun BookRow(
    book: BookUiModel,
    onBookSelected: (String) -> Unit,
    onDeleteRequest: (BookUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onBookSelected(book.id) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BookCover(coverPath = book.coverPath, title = book.title)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(text = book.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = book.author ?: "Unknown author",
                style = MaterialTheme.typography.bodyMedium
            )
            // RN9: render chip ("Rendering 42%", "Paused at 42%",
            // "Render failed", "Partially rendered", "Not rendered";
            // rendered books show nothing).
            renderChipText(book.renderState, book.renderJob)?.let { chip ->
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = { onBookSelected(book.id) },
                    label = { Text(chip) }
                )
            }
            if (book.isMissing) {
                Text(
                    text = "Unavailable — folder not found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { book.progressFraction })
            }
        }
        TextButton(onClick = { onDeleteRequest(book) }) {
            Text("Delete")
        }
    }
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        },
        title = { Text("Delete this book?") },
        text = { Text("“$title” and its folder will be removed from this device.") },
        modifier = modifier
    )
}

@Composable
private fun BookCover(coverPath: String?, title: String, modifier: Modifier = Modifier) {
    val coverModifier = modifier.size(56.dp)
    val placeholder = painterResource(R.drawable.ic_book_placeholder)
    // CP4 cover policy: covers resolve at import through
    // `BundleStorage.coverUri` — file books store the file path, SAF books
    // store a `content://` document URI string — and both load through Coil
    // here with placeholder/error fallback. Legacy `<tree>|<rel>` tokens from
    // older builds cannot load, so they fall through Coil's error drawable
    // (same placeholder), never a crash.
    if (coverPath != null) {
        AsyncImage(
            model = coverPath,
            contentDescription = "Cover of $title",
            modifier = coverModifier,
            placeholder = placeholder,
            error = placeholder
        )
    } else {
        Image(
            painter = placeholder,
            contentDescription = "No cover for $title",
            modifier = coverModifier
        )
    }
}

@Composable
private fun ImportErrorRow(error: ImportError, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = error.bundleDir,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = error.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun NoPermissionState(onRetryPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Auloud needs storage access to find your books.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetryPermission) {
            Text("Grant access")
        }
    }
}
