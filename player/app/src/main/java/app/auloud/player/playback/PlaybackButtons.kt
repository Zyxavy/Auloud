package app.auloud.player.playback

import android.content.Context
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * RA8: shared speed + sleep-timer buttons (Listen player and reader mode
 * bar show the same controls). All timer commands travel as service
 * intent extras ([PlaybackIntents.EXTRA_SLEEP_OPTION]); the countdown
 * itself lives in the service and its remaining time arrives via
 * [PlaybackState.sleepRemainingMs].
 */

/** Sends a sleep timer command to the service (never throws). */
fun sendSleepOption(context: Context, option: SleepOption) {
    try {
        context.startService(
            PlaybackIntents.serviceIntent(context)
                .putExtra(PlaybackIntents.EXTRA_SLEEP_OPTION, option.name)
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
        "Sleep ${formatMmSs(remainingMs)}"
    }
    TextButton(onClick = onClick, modifier = modifier) { Text(label) }
}

private const val TAG = "AuloudPlayer"
