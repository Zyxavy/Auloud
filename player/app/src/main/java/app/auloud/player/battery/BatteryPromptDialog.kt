package app.auloud.player.battery

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * WP8: first-playback dialog. Explains why the exemption matters (Samsung
 * kills background playback otherwise) and deep-links to settings.
 *
 * Stateless with primitive callbacks only, so the slow Tab E recomposes just
 * this dialog. Either button counts as "shown" -- the caller marks the
 * [BatteryPromptStore] on both paths.
 */
@Composable
fun BatteryPromptDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep playing with the screen off") },
        text = {
            Text(
                "Auloud plays in the background, but battery optimization " +
                    "can stop playback when the screen is off. Exempt Auloud " +
                    "so your book keeps playing."
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Open settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
        modifier = modifier
    )
}
