package app.auloud.player.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.auloud.player.render.formatAudioLength
import app.auloud.player.render.formatSizeBytes
import app.auloud.player.render.formatWallRange
import app.auloud.player.tts.BookVoiceViewModel.ApplyChoice
import app.auloud.player.tts.BookVoiceViewModel.ImpactView
import app.auloud.player.tts.BookVoiceViewModel.UiState

/**
 * VS4: book-scoped voice screen (D-113, D-121, D-122).
 *
 * Edits this book voices (narrator plus dialogue plus speeds plus
 * engine) with real-line audition from the book text, A/B compare per
 * role, the engine benchmark category plus slow-engine warning, the
 * mapping preview on engine switch, and the apply flow with the impact
 * dialog. Pure content: state in, callbacks out, like the audition
 * screen. One scroll column and narrow params keep recompositions cheap
 * on the Tab E.
 *
 * Scribe books show their voices with editing disabled plus the plain
 * refusal text. The global Settings screen keeps editing the shared
 * defaults; nothing here writes globals except the explicit
 * "Save as default for new books" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookVoiceScreen(
    state: UiState,
    onBack: () -> Unit,
    onSelectEngine: (String) -> Unit,
    onConfirmEngineSwitch: () -> Unit,
    onCancelEngineSwitch: () -> Unit,
    onSelectVoice: (TtsRole, String) -> Unit,
    onClearDialogueVoice: () -> Unit,
    onSetSpeed: (TtsRole, Float) -> Unit,
    onSetAlternateVoice: (TtsRole, String?) -> Unit,
    onPreview: (TtsRole, Boolean) -> Unit,
    onStop: () -> Unit,
    onRequestApply: () -> Unit,
    onConfirmApply: (ApplyChoice) -> Unit,
    onDismissImpact: () -> Unit,
    onPromoteToDefaults: () -> Unit,
    onDismissError: () -> Unit,
    onDismissNotice: () -> Unit,
    onReloadVoices: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = if (state.title.isBlank()) "Voices" else "Voices for ${state.title}",
                style = MaterialTheme.typography.headlineSmall
            )
        }
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            return
        }
        state.manifestError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            return
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (state.readOnly) {
                Text(
                    text = BookVoices.READ_ONLY_MESSAGE,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Voice editing is disabled for this book.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(16.dp))
            }
            EngineSection(
                state = state,
                onSelectEngine = onSelectEngine,
                onConfirmEngineSwitch = onConfirmEngineSwitch,
                onCancelEngineSwitch = onCancelEngineSwitch
            )
            Spacer(Modifier.height(16.dp))
            BookRoleCard(
                role = TtsRole.Narrator,
                title = "Narrator",
                voiceId = state.narratorVoiceId,
                speed = state.narratorSpeed,
                voices = state.engineVoices,
                enginesReady = state.engines.isNotEmpty(),
                sample = state.narrationSample,
                sampleFallback = "No narration lines found in this book yet.",
                alternateVoiceId = state.alternateNarratorVoiceId,
                previewing = state.previewingRole == TtsRole.Narrator && !state.previewingAlternate,
                previewingAlternate = state.previewingRole == TtsRole.Narrator && state.previewingAlternate,
                editingEnabled = !state.readOnly,
                showSameAsNarrator = false,
                onSelectVoice = onSelectVoice,
                onClearDialogueVoice = onClearDialogueVoice,
                onSetSpeed = onSetSpeed,
                onSetAlternateVoice = onSetAlternateVoice,
                onPreview = onPreview,
                onStop = onStop,
                onReloadVoices = onReloadVoices
            )
            Spacer(Modifier.height(16.dp))
            BookRoleCard(
                role = TtsRole.Dialogue,
                title = "Dialogue",
                voiceId = state.dialogueVoiceId ?: state.narratorVoiceId,
                speed = state.dialogueSpeed,
                voices = state.engineVoices,
                enginesReady = state.engines.isNotEmpty(),
                sample = state.dialogueSample,
                sampleFallback = "No dialogue lines in this book. Dialogue changes leave dialogue-free chapters current.",
                alternateVoiceId = state.alternateDialogueVoiceId,
                previewing = state.previewingRole == TtsRole.Dialogue && !state.previewingAlternate,
                previewingAlternate = state.previewingRole == TtsRole.Dialogue && state.previewingAlternate,
                editingEnabled = !state.readOnly,
                showSameAsNarrator = state.dialogueVoiceId == null,
                onSelectVoice = onSelectVoice,
                onClearDialogueVoice = onClearDialogueVoice,
                onSetSpeed = onSetSpeed,
                onSetAlternateVoice = onSetAlternateVoice,
                onPreview = onPreview,
                onStop = onStop,
                onReloadVoices = onReloadVoices
            )
            Spacer(Modifier.height(16.dp))
            if (!state.readOnly) {
                Button(
                    onClick = onRequestApply,
                    enabled = state.hasChanges
                ) { Text("Apply") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onPromoteToDefaults) { Text("Save as default for new books") }
            }
            state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(text = error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onDismissError) { Text("Dismiss") }
            }
            state.notice?.let { notice ->
                Spacer(Modifier.height(8.dp))
                Text(text = notice, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onDismissNotice) { Text("Dismiss") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (state.showImpact && state.impact != null) {
        ImpactDialog(
            impact = state.impact,
            onChoice = onConfirmApply,
            onDismiss = onDismissImpact
        )
    }
}

@Composable
private fun EngineSection(
    state: UiState,
    onSelectEngine: (String) -> Unit,
    onConfirmEngineSwitch: () -> Unit,
    onCancelEngineSwitch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "Engine", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (state.engines.isEmpty()) {
            Text(
                text = "No speech engine is ready yet. System voices appear after the TTS service starts.",
                style = MaterialTheme.typography.bodyMedium
            )
            return
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.engines.forEach { namespace ->
                val label = engineLabel(namespace, state.selectedEngine == namespace)
                if (namespace == state.selectedEngine) {
                    Button(onClick = { onSelectEngine(namespace) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onSelectEngine(namespace) }) { Text(label) }
                }
            }
        }
        state.pendingEngine?.let { pending ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Switch to $pending?",
                style = MaterialTheme.typography.bodyMedium
            )
            state.mappingPreview?.let { preview ->
                for (role in listOf(TtsRole.Narrator, TtsRole.Dialogue)) {
                    val mapping = preview.mappings[role] ?: continue
                    Text(
                        text = "${roleName(role)}: ${mapping.fromVoiceId} to ${mapping.toVoiceId} (${ruleName(mapping.rule)})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            state.pendingWarning?.let { warning ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Slow engine: ${formatWallRange(warning.wallFastMsPerHour, warning.wallSlowMsPerHour)} per hour of audio.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConfirmEngineSwitch,
                    enabled = !state.readOnly
                ) { Text("Switch engine") }
                OutlinedButton(onClick = onCancelEngineSwitch) { Text("Keep") }
            }
        }
    }
}

/** Engine button label with the benchmark category for slow engines. */
private fun engineLabel(namespace: String, selected: Boolean): String {
    if (selected) return namespace
    return when (EngineBenchmark.categoryFor(namespace)) {
        EngineSpeedCategory.TOO_SLOW -> "$namespace (Too slow)"
        EngineSpeedCategory.BACKGROUND -> "$namespace (Background)"
        EngineSpeedCategory.STANDARD -> namespace
    }
}

private fun roleName(role: TtsRole): String = when (role) {
    TtsRole.Narrator -> "Narrator"
    TtsRole.Dialogue -> "Dialogue"
}

private fun ruleName(rule: VoiceMappingRule): String = when (rule) {
    VoiceMappingRule.KEPT -> "kept"
    VoiceMappingRule.SAME_LOCALE -> "same voice name"
    VoiceMappingRule.FIRST_SORTED -> "first available"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookRoleCard(
    role: TtsRole,
    title: String,
    voiceId: String,
    speed: Float,
    voices: List<TtsVoice>,
    enginesReady: Boolean,
    sample: String?,
    sampleFallback: String,
    alternateVoiceId: String?,
    previewing: Boolean,
    previewingAlternate: Boolean,
    editingEnabled: Boolean,
    showSameAsNarrator: Boolean,
    onSelectVoice: (TtsRole, String) -> Unit,
    onClearDialogueVoice: () -> Unit,
    onSetSpeed: (TtsRole, Float) -> Unit,
    onSetAlternateVoice: (TtsRole, String?) -> Unit,
    onPreview: (TtsRole, Boolean) -> Unit,
    onStop: () -> Unit,
    onReloadVoices: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(role, voices) { mutableStateOf(false) }
    var altExpanded by remember(role, voices) { mutableStateOf(false) }
    // UX1: an empty voice list means the engines are still starting
    // (async System TTS init) or truly have no voices; the field and
    // the menu both say which, so tapping never silently does nothing.
    val voicesEmpty = voices.isEmpty()
    val emptyReason = if (!enginesReady) "Loading voices..." else "No voices available"
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (showSameAsNarrator) {
            Text(
                text = "Same as narrator",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it && editingEnabled }
        ) {
            OutlinedTextField(
                value = if (voicesEmpty && voiceId.isEmpty()) emptyReason
                else voiceId.ifEmpty { "No voice chosen" },
                onValueChange = {},
                readOnly = true,
                enabled = editingEnabled,
                label = { Text("Voice") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (voicesEmpty) {
                    DropdownMenuItem(
                        text = { Text(emptyReason) },
                        onClick = { expanded = false },
                        enabled = false
                    )
                } else {
                    voices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.id) },
                            onClick = {
                                onSelectVoice(role, voice.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        if (voicesEmpty && editingEnabled) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (!enginesReady) {
                    "$emptyReason The TTS service is still starting."
                } else {
                    "$emptyReason Tap Reload to try again."
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (enginesReady) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onReloadVoices) { Text("Reload") }
            }
        }
        if (role == TtsRole.Dialogue && editingEnabled) {
            OutlinedButton(onClick = onClearDialogueVoice) { Text("Same as narrator") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = sample ?: sampleFallback,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 4
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Speed", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onSetSpeed(role, speed - 0.25f) },
                    enabled = editingEnabled && speed > MIN_TTS_SPEED + 0.001f
                ) { Text("-") }
                Text(
                    text = "%.2fx".format(speed),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = { onSetSpeed(role, speed + 0.25f) },
                    enabled = editingEnabled && speed < MAX_TTS_SPEED - 0.001f
                ) { Text("+") }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (previewing) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            Button(
                onClick = { onPreview(role, false) },
                enabled = editingEnabled && voiceId.isNotEmpty()
            ) { Text("Preview $title") }
        }
        Spacer(Modifier.height(4.dp))
        Text(text = "Compare with", style = MaterialTheme.typography.bodySmall)
        ExposedDropdownMenuBox(
            expanded = altExpanded,
            onExpandedChange = { altExpanded = it && editingEnabled }
        ) {
            OutlinedTextField(
                value = alternateVoiceId ?: "No compare voice",
                onValueChange = {},
                readOnly = true,
                enabled = editingEnabled,
                label = { Text("Compare voice") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = altExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = altExpanded,
                onDismissRequest = { altExpanded = false }
            ) {
                if (voicesEmpty) {
                    DropdownMenuItem(
                        text = { Text(emptyReason) },
                        onClick = { altExpanded = false },
                        enabled = false
                    )
                } else {
                    voices.filter { it.id != voiceId }.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.id) },
                            onClick = {
                                onSetAlternateVoice(role, voice.id)
                                altExpanded = false
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (previewingAlternate) {
            Button(onClick = onStop) { Text("Stop compare") }
        } else {
            OutlinedButton(
                onClick = { onPreview(role, true) },
                enabled = editingEnabled && alternateVoiceId != null
            ) { Text("Preview compare") }
        }
    }
}

@Composable
private fun ImpactDialog(
    impact: ImpactView,
    onChoice: (ApplyChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onChoice(ApplyChoice.NOW) }) { Text("Re-render now") }
        },
        dismissButton = {
            OutlinedButton(onClick = { onChoice(ApplyChoice.LATER) }) { Text("Later") }
        },
        title = { Text("Apply voice changes?") },
        text = {
            Column(modifier = modifier.fillMaxWidth()) {
                if (impact.staleChapters == 0) {
                    Text(text = "Every chapter already matches these voices.")
                } else {
                    Text(
                        text = "${impact.staleChapters} chapter(s) with " +
                            "${formatAudioLength(impact.staleAudioMs)} need re-rendering."
                    )
                    Text(
                        text = formatWallRange(impact.wallFastMs, impact.wallSlowMs) + "."
                    )
                    Text(
                        text = "Needs ${formatSizeBytes(impact.swapBytes)} during swap (old plus new audio)."
                    )
                }
                if (impact.jobWasPaused) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = "The running render was paused.")
                }
                if (impact.added.isNotEmpty() || impact.removed.isNotEmpty()) {
                    Text(
                        text = "Plan changed: ${impact.added.size} added, ${impact.removed.size} no longer needed."
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onChoice(ApplyChoice.KEEP) }) { Text("Keep old audio") }
            }
        }
    )
}
