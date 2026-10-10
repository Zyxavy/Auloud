package app.auloud.player.playback

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WP9: debug-only save-time plumbing and overlay formatting.
 *
 * The service ([PlaybackService]) is the only writer of progress, so the
 * overlay's "last save" time must come from it, not the UI clock. On every
 * save initiation the service records the wall-clock time in
 * [DebugSaveTracker] (the call site is gated by `BuildConfig.DEBUG`, so
 * release builds never touch this object). [PlaybackController] copies the
 * value into [PlaybackState.lastSaveWallMs] on each refresh, and the debug
 * overlay displays it.
 *
 * Pure Kotlin + `java.text` (API 1, plain-JVM-testable); no Android types.
 * API 24 safe: no `java.time`.
 */
object DebugSaveTracker {
    @Volatile
    var lastSaveWallMs: Long = 0L
        private set

    /** Records a save; non-positive inputs coerce to 0 ("never saved"). */
    fun recordSave(wallMs: Long) {
        lastSaveWallMs = wallMs.coerceAtLeast(0L)
    }

    fun reset() {
        lastSaveWallMs = 0L
    }
}

/**
 * Wall-clock save time for the overlay: `"never"` before the first save,
 * else `HH:mm:ss` in the device locale zone.
 */
fun formatDebugSaveTime(wallMs: Long): String {
    if (wallMs <= 0L) return "never"
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(wallMs))
}

/** Player-state word for the overlay. */
fun debugPlayerLabel(isConnected: Boolean, isPlaying: Boolean): String = when {
    !isConnected -> "connecting"
    isPlaying -> "playing"
    else -> "paused"
}

/**
 * mm:ss for the overlay position (the shared [formatMmSs]; kept as a thin
 * alias so existing call sites read as overlay code).
 */
fun formatDebugMs(ms: Long): String = formatMmSs(ms)
