package app.auloud.player.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * IN8: EPUB import progress + result screen (Slice 9).
 *
 * Pure content like [VoiceAuditionScreen]: state in, callbacks out, no
 * ViewModel owned here. The caller shows this while the import state is
 * not [ImportUiState.Idle] and hides it on dismiss. Keep scrolling simple
 * (one scroll column; the Tab E is slow).
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            ImportUiState.Idle -> Unit
            is ImportUiState.Copying -> CopyingContent(state.displayName, onCancel)
            is ImportUiState.Importing -> ImportingContent(state, onCancel)
            is ImportUiState.Succeeded -> SucceededContent(state, onDismiss)
            is ImportUiState.AlreadyInLibrary -> AlreadyInLibraryContent(onDismiss)
            is ImportUiState.Failed -> FailedContent(state, onDismiss)
            ImportUiState.Cancelled -> CancelledContent(onDismiss)
        }
    }
}

@Composable
private fun CopyingContent(displayName: String, onCancel: () -> Unit) {
    Text(text = "Copying $displayName…", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(16.dp))
    Button(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun ImportingContent(state: ImportUiState.Importing, onCancel: () -> Unit) {
    Text(
        text = "Importing ${state.displayName}…",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(16.dp))
    if (state.totalChapters > 0) {
        LinearProgressIndicator(
            progress = {
                state.chaptersDone.toFloat() / state.totalChapters.toFloat()
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Chapter ${state.chaptersDone} of ${state.totalChapters}: ${state.chapterTitle}",
            style = MaterialTheme.typography.bodyMedium
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(text = "Starting…", style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun SucceededContent(state: ImportUiState.Succeeded, onDismiss: () -> Unit) {
    val report = state.report
    Text(
        text = "Imported ${report.title}",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${report.chapters} chapters, ${report.words} words",
        style = MaterialTheme.typography.bodyMedium
    )
    if (report.drops.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Dropped during cleaning (${report.drops.size}):",
            style = MaterialTheme.typography.titleSmall
        )
        report.drops.forEach { drop ->
            Text(text = drop, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (report.warnings.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Warnings (${report.warnings.size}):",
            style = MaterialTheme.typography.titleSmall
        )
        report.warnings.forEach { warning ->
            Text(text = warning, style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onDismiss) { Text("Done") }
}

@Composable
private fun AlreadyInLibraryContent(onDismiss: () -> Unit) {
    Text(
        text = "Already in your library.",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "This book was imported before; nothing was added.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onDismiss) { Text("Done") }
}

@Composable
private fun FailedContent(state: ImportUiState.Failed, onDismiss: () -> Unit) {
    Text(
        text = "Import failed",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(8.dp))
    Text(text = state.headline, style = MaterialTheme.typography.bodyMedium)
    if (state.details.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        state.details.forEach { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onDismiss) { Text("Close") }
}

@Composable
private fun CancelledContent(onDismiss: () -> Unit) {
    Text(text = "Import cancelled", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Nothing was added to your library.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onDismiss) { Text("Close") }
}
