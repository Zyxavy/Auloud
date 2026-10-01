package app.auloud.player.playback

import android.app.ActivityManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * WP9: debug-build-only overlay: current chapter, `positionMs`, player state,
 * last progress-save time (from the service via [PlaybackState.lastSaveWallMs],
 * never the UI clock).
 *
 * Release exclusion is by call-site gate (`if (BuildConfig.DEBUG)` in
 * `PlayerScreen`): the branch is never taken in release, so this composable
 * is unreachable there. It is NOT referenced from any release path.
 *
 * Narrow recomposition for the slow Tab E: only primitive slices in, one
 * `Text` out, strings built inside `remember` keyed on exactly the values
 * shown — the 500 ms position ticker recomposes this only when a displayed
 * value actually changed.
 *
 * DEVICE-TEST (user on the Tab E): overlay rendering and
 * overlay-matches-audio cannot be verified without the device.
 */
@Composable
fun DebugOverlay(
    chapterIndex: Int,
    chapterCount: Int,
    positionMs: Long,
    isPlaying: Boolean,
    isConnected: Boolean,
    lastSaveWallMs: Long,
    modifier: Modifier = Modifier
) {
    val line1 = remember(chapterIndex, chapterCount, positionMs, isPlaying, isConnected) {
        buildString {
            append("DBG ch ")
            if (chapterCount > 0) {
                append("${chapterIndex.coerceIn(0, chapterCount - 1) + 1}/$chapterCount")
            } else {
                append("-/0")
            }
            val pos = positionMs.coerceAtLeast(0L)
            append(" pos ${pos}ms (${formatDebugMs(pos)}) ")
            append(debugPlayerLabel(isConnected, isPlaying))
        }
    }
    val line2 = remember(lastSaveWallMs) { "saved ${formatDebugSaveTime(lastSaveWallMs)}" }
    Text(
        text = "$line1\n$line2",
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
    )
}

/**
 * RA9: reader sync section (same debug-only gating as [DebugOverlay];
 * shown in the reader session, not the Listen player).
 *
 * One `Text`, strings built inside `remember` keyed on exactly the values
 * shown: `sid` (current sentence), `lagAvgMs`/`lagMaxMs` (highlight lag
 * over the last window, null until the first change), `pssMb` (total PSS,
 * -1 when unreadable).
 */
@Composable
fun ReaderDebugOverlay(
    sid: Int?,
    lagAvgMs: Long?,
    lagMaxMs: Long?,
    pssMb: Int,
    modifier: Modifier = Modifier
) {
    val line = remember(sid, lagAvgMs, lagMaxMs, pssMb) {
        buildString {
            append("RDG sid ${sid ?: "-"}")
            if (lagAvgMs != null && lagMaxMs != null) {
                append(" lag avg ${lagAvgMs}ms max ${lagMaxMs}ms")
            } else {
                append(" lag -/-")
            }
            append(if (pssMb >= 0) " PSS ${pssMb}MB" else " PSS -")
        }
    }
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
    )
}

/** Total PSS in MB (-1 when unreadable). Call off the composition path. */
fun readTotalPssMb(context: Context): Int {
    return try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos = manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
        val kb = infos.firstOrNull()?.totalPss ?: -1
        if (kb < 0) -1 else kb / 1024
    } catch (_: Exception) {
        -1
    }
}
