package app.auloud.player.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Slice 20 TR1: Receive-from-PC screen. Shows the address plus pairing
 * code the PC needs, starts listening on open and stops on leave (plus
 * an explicit Stop). Progress is states, never percents: waiting,
 * receiving, and the last result line.
 *
 * Thin by rule: all state arrives in [state], all work leaves through
 * callbacks (same shape as the other screens).
 */
@Composable
fun ReceiveScreen(
    state: ReceiveUiState,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { onOpen() }
    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Receive via Wi-Fi", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Same Wi-Fi network on both devices. On the PC, send the " +
                "bundle to the address below with the code.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        if (!state.listening && state.error == null) {
            Text("Starting…", style = MaterialTheme.typography.bodyLarge)
        } else if (state.error != null) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        } else {
            Text(
                text = state.ip?.let { "$it:${state.port}" } ?: "No Wi-Fi address found",
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(16.dp))
            Text(text = "Code", style = MaterialTheme.typography.labelLarge)
            Text(text = state.code, style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(16.dp))
            if (state.busy) {
                Text("Receiving…", style = MaterialTheme.typography.bodyLarge)
            } else if (state.lastResult != null) {
                Text(
                    text = state.lastResult,
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    text = "Waiting for the PC…",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onStop) {
                Text("Stop")
            }
        }
    }
}
