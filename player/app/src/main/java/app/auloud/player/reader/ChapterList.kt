package app.auloud.player.reader

import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.PageMark

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
    val durationMs: Long,
    /**
     * CP7: printable page range for PDF text-path chapters (e.g. "pp. 3-5"),
     * null when the chapter carries no pages (all EPUB chapters). Defaults
     * null so EPUB behavior is byte-identical; the list row shows it next to
     * the duration only when present.
     */
    val pageRange: String? = null
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
 * CP7: printable page range for a chapter's `pages` marks.
 *
 * Null or empty (EPUB, or a chapter without pages) stays null so the row
 * shows the duration alone. One page renders as "p. 3", a span as
 * "pp. 3-5" (min to max, so unsorted or gapped marks still read sanely).
 * Non-positive pages are ignored; when none are valid the answer is null.
 * Pure so the row subtitle is JVM-testable. API 24 safe.
 */
fun formatPageRange(pages: List<PageMark>?): String? {
    if (pages.isNullOrEmpty()) return null
    var min = Int.MAX_VALUE
    var max = Int.MIN_VALUE
    for (mark in pages) {
        if (mark.page < 1) continue
        if (mark.page < min) min = mark.page
        if (mark.page > max) max = mark.page
    }
    if (min == Int.MAX_VALUE) return null
    return if (min == max) "p. $min" else "pp. $min-$max"
}

/**
 * CP7: chapter row subtitle - duration plus the page range when present
 * (e.g. "1:05 - pp. 3-5"). Blank ranges fall back to the duration alone,
 * so EPUB rows render exactly as before.
 */
fun formatChapterSubtitle(durationMs: Long, pageRange: String?): String {
    val duration = formatChapterDuration(durationMs)
    val range = pageRange?.trim()
    if (range.isNullOrEmpty()) return duration
    return "$duration - $range"
}

/**
 * CP7: attaches a printable page range to an entry (copy; the original is
 * untouched). Manifest mapping ([toChapterEntries]) leaves [pageRange]
 * null; a future pages-enrichment pass can apply this per chapter without
 * touching EPUB rows.
 */
fun ChapterEntry.withPageRange(pages: List<PageMark>?): ChapterEntry =
    copy(pageRange = formatPageRange(pages))

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
        // IN1: unrendered 2.0 chapters carry no duration (null); the row
        // shows 0:00 until Slice 10 renders audio.
        ChapterEntry(index = position, title = chapter.title, durationMs = chapter.durationMs ?: 0L)
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

/**
 * RN7 (#14 cosmetic): marker text for the current chapter row.
 *
 * Null when the row is not current (no marker). The current row of a
 * listening session reads "Now playing"; the current row of a read-only
 * (unrendered) session reads "Reading", since no audio plays there. Pure
 * so the wording is JVM-testable; RN9 wires it into the chapter rows
 * (which still hard-code "Now playing" today).
 */
fun rowMarkerText(isCurrent: Boolean, isListening: Boolean): String? {
    if (!isCurrent) return null
    return if (isListening) "Now playing" else "Reading"
}
