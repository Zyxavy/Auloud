package app.auloud.player.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * RN9: debug-build-only render overlay (same gate shape as the beep
 * card): benchmark RTF range, current chapter, live sentence slot,
 * battery temperature, spool size. One `Text`, strings built inside
 * `remember` keyed on exactly the values shown, so it recomposes
 * independently of the rest on the slow Tab E.
 *
 * Release exclusion is by call-site gate (`if (BuildConfig.DEBUG)`):
 * the branch is never taken in release, so this composable is
 * unreachable there.
 */
@Composable
fun RenderDebugOverlay(
    chapterText: String,
    sentenceText: String,
    batteryTempC: Float?,
    spoolBytes: Long?,
    modifier: Modifier = Modifier
) {
    val line = remember(chapterText, sentenceText, batteryTempC, spoolBytes) {
        renderDebugText(
            rtfLow = RENDER_RTF_LOW,
            rtfHigh = RENDER_RTF_HIGH,
            chapterText = chapterText,
            sentenceText = sentenceText,
            batteryTempC = batteryTempC,
            spoolBytes = spoolBytes
        )
    }
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
    )
}
