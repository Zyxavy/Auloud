package app.auloud.player.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * RA8: shared speed + sleep-timer buttons (Listen player and reader mode
 * bar show the same controls). All timer commands travel as service
 * intent extras ([PlaybackService.EXTRA_SLEEP_OPTION]); the countdown
 * itself lives in the service and its remaining time arrives via
 * [PlaybackState.sleepRemainingMs].
 */

/** Sends a sleep timer command to the service (never throws). */
fun sendSleepOption(context: Context, option: SleepOption) {
    try {
        context.startService(
            Intent(context, PlaybackService::class.java)
                .putExtra(PlaybackService.EXTRA_SLEEP_OPTION, option.name)
        )
    } catch (e: Exception) {
        Log.w(TAG, "sleep option $option: ${e.message}")
    }
}

@Composable
fun SpeedButton(
    speed: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onClick, modifier = modifier) { Text(formatSpeed(speed)) }
}

@Composable
fun SleepTimerButton(
    remainingMs: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = if (remainingMs == null) {
        "Sleep: off"
    } else {
        "Sleep ${formatCountdown(remainingMs)}"
    }
    TextButton(onClick = onClick, modifier = modifier) { Text(label) }
}

/** mm:ss countdown, API 24 safe (no java.time). */
private fun formatCountdown(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L).coerceAtMost(599_999L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${if (seconds < 10L) "0$seconds" else "$seconds"}"
}

private const val TAG = "AuloudPlayer"
