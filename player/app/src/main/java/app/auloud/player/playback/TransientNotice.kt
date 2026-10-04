package app.auloud.player.playback

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * CP4: transient skip/storage notice (all three modes).
 *
 * Shows [message] with a Dismiss action; auto-dismisses after ~6 s,
 * whichever comes first. Never blocks controls (a plain row, no dialog
 * or scrim). The caller owns dismissal via [onDismiss] (wired to
 * `PlaybackController.clearSkipNotice`), which is what keeps rotation
 * from resurrecting it.
 *
 * Narrow recomposition for the slow Tab E: only the nullable message
 * string reaches this scope, so position ticks skip it while unchanged.
 *
 * API 24 safe: Compose + coroutines only.
 */
@Composable
fun TransientNotice(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(NOTICE_TIMEOUT_MS)
        onDismiss()
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** Auto-dismiss timeout for the transient notice. */
const val NOTICE_TIMEOUT_MS = 6_000L
