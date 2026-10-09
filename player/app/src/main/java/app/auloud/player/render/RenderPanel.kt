package app.auloud.player.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * RN9: book render panel (Slice 10).
 *
 * Estimate (audio length, size, wall-time RANGE), options (whole book,
 * next N, from here), voices summary with a link to voice settings,
 * start/pause/resume/cancel, job progress, plain errors, and
 * whole-book delete with confirm. Pure content: state in,
 * callbacks out, like the voice lab screen. Narrow params keep
 * recompositions cheap on the Tab E.
 *
 * UX1 (2026-10-10, owner-ordered): the charging-only toggle is gone;
 * renders run unplugged.
 */
@Composable
fun RenderPanel(
    state: RenderPanelState,
    onSelectOption: (RenderOption) -> Unit,
    onSetNextN: (Int) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDeleteAllAudio: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDelete by remember(state.title) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = "Render audio", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (state.isLoading) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            return
        }
        state.manifestError?.let { error ->
            Text(text = error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            return
        }
        Text(
            text = renderSummaryLine(state),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        if (!state.isFileBook) {
            Text(
                text = renderErrorText("picked folder (SAF): app-storage output is not available"),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            return
        }
        if (state.unrenderedPositions.isEmpty()) {
            Text(
                text = "Every chapter already has audio.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            EstimateBlock(state = state)
            Spacer(Modifier.height(8.dp))
            OptionRow(
                option = state.option,
                onSelectOption = onSelectOption
            )
            if (state.option == RenderOption.NEXT_N) {
                Spacer(Modifier.height(4.dp))
                NextNStepper(
                    nextN = state.nextN,
                    chapterCount = state.chapterCount,
                    onSetNextN = onSetNextN
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(text = state.voicesLine, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpenVoiceSettings) { Text("Choose voices") }
        Spacer(Modifier.height(8.dp))
        JobBlock(
            state = state,
            onStart = onStart,
            onPause = onPause,
            onResume = onResume,
            onCancel = onCancel
        )
        state.error?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(text = error, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDismissError) { Text("Dismiss") }
        }
        state.jobError?.let { raw ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = renderErrorText(raw),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (state.renderedCount > 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete audio") }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                OutlinedButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteAllAudio()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = false }) { Text("Keep") }
            },
            title = { Text("Delete rendered audio?") },
            text = { Text("Every rendered chapter returns to unrendered. This cannot be undone.") }
        )
    }
}

/** RN9: `5 of 12 chapters have audio` plus the plan size for the option. */
private fun renderSummaryLine(state: RenderPanelState): String {
    val base = "${state.renderedCount} of ${state.chapterCount} chapters have audio"
    return if (state.unrenderedPositions.isEmpty()) {
        "$base."
    } else {
        "$base. This renders ${state.planSize} chapter(s)."
    }
}

@Composable
private fun EstimateBlock(state: RenderPanelState, modifier: Modifier = Modifier) {
    val estimate = state.estimate
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = formatAudioLength(estimate.audioMs),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = formatSizeBytes(estimate.sizeBytes),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = formatWallRange(estimate.wallFastMs, estimate.wallSlowMs),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = estimateNote(estimate.unknownChapters),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun OptionRow(
    option: RenderOption,
    onSelectOption: (RenderOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OptionButton("Whole book", option == RenderOption.WHOLE_BOOK) {
            onSelectOption(RenderOption.WHOLE_BOOK)
        }
        OptionButton("Next N", option == RenderOption.NEXT_N) {
            onSelectOption(RenderOption.NEXT_N)
        }
        OptionButton("From here", option == RenderOption.FROM_HERE) {
            onSelectOption(RenderOption.FROM_HERE)
        }
    }
}

@Composable
private fun OptionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun NextNStepper(
    nextN: Int,
    chapterCount: Int,
    onSetNextN: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "Chapters", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onSetNextN(nextN - 1) },
                enabled = nextN > 1
            ) { Text("-") }
            Text(
                text = "$nextN",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(
                onClick = { onSetNextN(nextN + 1) },
                enabled = nextN < chapterCount.coerceAtLeast(1)
            ) { Text("+") }
        }
    }
}

@Composable
private fun JobBlock(
    state: RenderPanelState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val jobState = state.jobState
    Column(modifier = modifier.fillMaxWidth()) {
        when (jobState) {
            RenderJobState.RUNNING -> {
                Text(
                    text = jobProgressLine(state),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPause) { Text("Pause") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            RenderJobState.PAUSED,
            RenderJobState.INTERRUPTED,
            RenderJobState.QUEUED -> {
                Text(
                    text = jobProgressLine(state),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onResume) { Text("Resume") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            RenderJobState.FAILED -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart) { Text("Retry") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            RenderJobState.CANCELLED,
            RenderJobState.DONE,
            null -> {
                if (state.unrenderedPositions.isNotEmpty()) {
                    Button(onClick = onStart) { Text("Start rendering") }
                }
            }
        }
    }
}

/** RN9: `Rendering 2 of 5 chapters` (percent lives on the library chip). */
private fun jobProgressLine(state: RenderPanelState): String {
    val verb = when (state.jobState) {
        RenderJobState.RUNNING -> "Rendering"
        else -> "Paused"
    }
    return if (state.jobTotal > 0) {
        "$verb ${state.jobDone} of ${state.jobTotal} chapters"
    } else {
        verb
    }
}
