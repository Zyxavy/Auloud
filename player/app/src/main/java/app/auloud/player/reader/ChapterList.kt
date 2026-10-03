package app.auloud.player.reader

import app.auloud.player.bundle.ChapterInfo

/**
 * CP3: chapter list state (pure Kotlin, zero Android deps).
 *
 * Backs the chapter navigation screen reachable from the reader and Listen
 * screens. [ChapterEntry.index] is the 0-based playlist position in sorted
 * manifest order (matches ExoPlayer item index and
 * PlaybackController.seekToChapter, NOT the 1-based manifest `index`);
 * formatting and jump validation live here so
 * they are JVM-testable without Android.
 *
 * API 24 safe: pure Kotlin, no java.time, no java.nio.
 */
data class ChapterEntry(
    val index: Int,
    val title: String,
    val durationMs: Long
)

/**
 * CP3: formats a chapter duration for the list row.
 *
 * `m:ss` under an hour (e.g. 0:00, 1:05), `h:mm:ss` at or over an hour
 * (e.g. 1:00:00, 1:01:05). Negative input coerces to 0. API 24 safe.
 */
fun formatChapterDuration(ms: Long): String {
    val safeMs = if (ms < 0L) 0L else ms
    val totalSeconds = safeMs / 1_000L
    val seconds = (totalSeconds % 60L).toInt()
    val totalMinutes = totalSeconds / 60L
    if (totalSeconds >= 3_600L) {
        val hours = totalMinutes / 60L
        val minutes = (totalMinutes % 60L).toInt()
        return "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
    }
    return "$totalMinutes:${twoDigits(seconds)}"
}

private fun twoDigits(value: Int): String {
    return if (value < 10) "0$value" else "$value"
}

/**
 * CP3: validates a chapter jump target against the playlist size.
 *
 * Returns the index unchanged when it is a valid playlist position,
 * else `null` (empty playlist or out-of-range index). The caller
 * (PlaybackController.seekToChapter) treats `null` as no-op, keeping the
 * shared position rules (seek to chapter start, paused state unchanged).
 */
fun coerceChapterJump(index: Int, count: Int): Int? {
    if (count <= 0) return null
    if (index < 0 || index >= count) return null
    return index
}

/**
 * CP3: manifest chapters to playlist positions.
 *
 * Sorts by manifest `index` and numbers entries 0-based in that order, so
 * `ChapterEntry.index` always matches the ExoPlayer item index even when the
 * manifest uses 1-based (or ragged) numbering. Pure so the mapping that
 * seekToChapter and the "Now playing" marker depend on is JVM-testable.
 */
fun toChapterEntries(chapters: List<ChapterInfo>): List<ChapterEntry> {
    return chapters.sortedBy { it.index }.mapIndexed { position, chapter ->
        ChapterEntry(index = position, title = chapter.title, durationMs = chapter.durationMs)
    }
}

/**
 * CP3: whether the row for [index] shows the "Now playing" marker.
 *
 * Extracted pure so the marker rule is JVM-testable; the list screen passes
 * only primitives into rows to keep recompositions narrow on the Tab E.
 */
fun isCurrentChapter(index: Int, currentIndex: Int): Boolean {
    return index == currentIndex
}
