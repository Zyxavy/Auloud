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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
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
import app.auloud.player.tts.VoiceAuditionViewModel.UiState

/**
 * PW8: voice settings + audition screen (settings entry pushes here).
 *
 * Engine row (recommended marked), one card per role (voice dropdown
 * of the selected engine, speed stepper, preview/stop), error line.
 * Pure content: state in, callbacks out — previewable, nothing to mock.
 * Keep scrolling simple (one scroll column; the Tab E is slow).
 *
 * DEVICE-TEST (user on the Tab E): pick voices for both roles, preview
 * each, switch engine (voices remap), confirm choices survive a restart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAuditionScreen(
    state: UiState,
    onBack: () -> Unit,
    onSelectEngine: (String) -> Unit,
    onSelectVoice: (TtsRole, String) -> Unit,
    onSetSpeed: (TtsRole, Float) -> Unit,
    onPreview: (TtsRole) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    beepCheckAvailable: Boolean = false,
    beepStatus: String? = null,
    onRunBeepCheck: (() -> Unit)? = null,
    onCalibrateLevels: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(text = "Voices", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text(text = "Engine", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (state.engines.isEmpty()) {
                Text(
                    text = "No speech engine is ready yet. System voices appear after the TTS service starts.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.engines.forEach { namespace ->
                        val recommended = namespace == state.recommendation?.namespace
                        val label = if (recommended) "$namespace (recommended)" else namespace
                        if (namespace == state.selectedEngine) {
                            Button(onClick = { onSelectEngine(namespace) }) { Text(label) }
                        } else {
                            TextButton(onClick = { onSelectEngine(namespace) }) { Text(label) }
                        }
                    }
                }
                state.recommendation?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(text = it.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            RoleCard(
                role = TtsRole.Narrator,
                title = "Narrator",
                voiceId = state.narratorVoiceId,
                speed = state.narratorSpeed,
                voices = state.engineVoices,
                previewing = state.previewingRole == TtsRole.Narrator,
                onSelectVoice = onSelectVoice,
                onSetSpeed = onSetSpeed,
                onPreview = onPreview,
                onStop = onStop
            )
            Spacer(Modifier.height(16.dp))
            RoleCard(
                role = TtsRole.Dialogue,
                title = "Dialogue",
                voiceId = state.dialogueVoiceId,
                speed = state.dialogueSpeed,
                voices = state.engineVoices,
                previewing = state.previewingRole == TtsRole.Dialogue,
                onSelectVoice = onSelectVoice,
                onSetSpeed = onSetSpeed,
                onPreview = onPreview,
                onStop = onStop
            )
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            // ST6: level match for live streaming (system pair only; the
            // stream plays full volume until the pair matches).
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCalibrateLevels,
                enabled = state.canCalibrateLevels && !state.calibrating
            ) {
                Text(if (state.calibrating) "Matching..." else "Match voice levels")
            }
            state.levelNote?.let {
                Spacer(Modifier.height(4.dp))
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
            // RN10: debug-only beep self-check (release builds never see
            // this card: the host passes false there).
            if (beepCheckAvailable && onRunBeepCheck != null) {
                Spacer(Modifier.height(16.dp))
                BeepSelfCheckCard(
                    status = beepStatus,
                    onRun = onRunBeepCheck
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * RN10: debug-only beep self-check card (voice lab bottom section).
 *
 * Minimal trigger only: one button rendering the 4-tone test chapter
 * through the real render chain, one status line with the result. Full
 * render UI is RN9. The host hides this card in release builds.
 */
@Composable
private fun BeepSelfCheckCard(
    status: String?,
    onRun: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "Beep self-check (debug)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Renders a 4-tone test chapter through the real render " +
                "chain and validates it. Used to measure encoder delay.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRun) { Text("Render beep chapter") }
        status?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleCard(
    role: TtsRole,
    title: String,
    voiceId: String,
    speed: Float,
    voices: List<TtsVoice>,
    previewing: Boolean,
    onSelectVoice: (TtsRole, String) -> Unit,
    onSetSpeed: (TtsRole, Float) -> Unit,
    onPreview: (TtsRole) -> Unit,
    onStop: () -> Unit
) {
    var expanded by remember(role, voices) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it && voices.isNotEmpty() }
        ) {
            OutlinedTextField(
                value = voiceId.ifEmpty { "No voice chosen" },
                onValueChange = {},
                readOnly = true,
                label = { Text("Voice") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
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
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Speed", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onSetSpeed(role, speed - 0.25f) },
                    enabled = speed > MIN_TTS_SPEED + 0.001f
                ) { Text("-") }
                Text(
                    text = "%.2fx".format(speed),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = { onSetSpeed(role, speed + 0.25f) },
                    enabled = speed < MAX_TTS_SPEED - 0.001f
                ) { Text("+") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (previewing) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            Button(onClick = { onPreview(role) }, enabled = voiceId.isNotEmpty()) {
                Text("Preview $title")
            }
        }
    }
}
