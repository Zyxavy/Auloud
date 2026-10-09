package app.auloud.player.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.auloud.player.render.ChapterRenderState
import app.auloud.player.render.ChapterStaleState
import app.auloud.player.render.chapterStatusText
import app.auloud.player.render.showRowPlayButton
import app.auloud.player.render.staleBadgeText

/**
 * CP3: chapter list screen (titles, durations, current marker, tap to jump).
 *
 * Reachable from the reader and Listen screens via a "Chapters" button.
 * Tapping a non-current row calls [onJump] with that chapter index; tapping
 * the current row is a no-op. [onBack] closes the list.
 *
 * Narrow recompositions for the slow Tab E: rows receive only primitives
 * (index, title, duration, marker flag), never whole state objects.
 *
 * RN9: partial-book rows. [renderStateOf] maps a chapter position to its
 * render state (null keeps the legacy rows: marker plus jump only);
 * [markerListeningOf] maps a position to per-row listening (null uses
 * [isListening] for every row); [onRenderChapter] renders one unrendered
 * chapter; [onDeleteChapterAudio] deletes one rendered chapter's audio.
 *
 * VS5: stale-voice rows. [staleStateOf] maps a position to its VS2
 * staleness (null shows no stale badges); the badge line always carries
 * the `Voices:` prefix so it reads as a second axis, never as job
 * progress. [staleBannerText] plus [onRerenderStale] render the
 * mixed-voice banner with one-tap "finish re-rendering" (both null hides
 * it); [staleCount] plus [onRerenderStale] render the bulk "Re-render
 * stale" button only when there is no banner (the banner button already
 * does that job); [onRerenderChapter] re-renders from one stale chapter;
 * [onDeleteStaleAudio] deletes every stale chapter's audio (hub only:
 * complete books pass null); [onOpenVoices] opens the book voice screen
 * (the complete-book entry point; the hub passes null because its Voices
 * button already exists).
 *
 * UX1: [onPlayChapter] shows an explicit Play button on rendered rows
 * (same [onJump] path, so gating stays single-sourced); null shows no
 * Play button (complete books, which play from their own screen).
 */
@Composable
fun ChapterListScreen(
    entries: List<ChapterEntry>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isListening: Boolean = true,
    renderStateOf: ((Int) -> ChapterRenderState)? = null,
    markerListeningOf: ((Int) -> Boolean)? = null,
    onRenderChapter: ((Int) -> Unit)? = null,
    onDeleteChapterAudio: ((Int) -> Unit)? = null,
    staleStateOf: ((Int) -> ChapterStaleState)? = null,
    staleBannerText: String? = null,
    staleCount: Int = 0,
    onRerenderStale: ((Int) -> Unit)? = null,
    onRerenderChapter: ((Int) -> Unit)? = null,
    onDeleteStaleAudio: (() -> Unit)? = null,
    onOpenVoices: (() -> Unit)? = null,
    onPlayChapter: ((Int) -> Unit)? = null
) {
    ChapterListContent(
        entries = entries,
        currentIndex = currentIndex,
        onJump = onJump,
        onBack = onBack,
        modifier = modifier,
        isListening = isListening,
        renderStateOf = renderStateOf,
        markerListeningOf = markerListeningOf,
        onRenderChapter = onRenderChapter,
        onDeleteChapterAudio = onDeleteChapterAudio,
        staleStateOf = staleStateOf,
        staleBannerText = staleBannerText,
        staleCount = staleCount,
        onRerenderStale = onRerenderStale,
        onRerenderChapter = onRerenderChapter,
        onDeleteStaleAudio = onDeleteStaleAudio,
        onOpenVoices = onOpenVoices,
        onPlayChapter = onPlayChapter
    )
}

@Composable
private fun ChapterListContent(
    entries: List<ChapterEntry>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isListening: Boolean = true,
    renderStateOf: ((Int) -> ChapterRenderState)? = null,
    markerListeningOf: ((Int) -> Boolean)? = null,
    onRenderChapter: ((Int) -> Unit)? = null,
    onDeleteChapterAudio: ((Int) -> Unit)? = null,
    staleStateOf: ((Int) -> ChapterStaleState)? = null,
    staleBannerText: String? = null,
    staleCount: Int = 0,
    onRerenderStale: ((Int) -> Unit)? = null,
    onRerenderChapter: ((Int) -> Unit)? = null,
    onDeleteStaleAudio: (() -> Unit)? = null,
    onOpenVoices: (() -> Unit)? = null,
    onPlayChapter: ((Int) -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        // VS5: stale header (banner, bulk actions, voices entry). One
        // block for every book kind; nulls hide each piece.
        StaleListHeader(
            bannerText = staleBannerText,
            staleCount = staleCount,
            currentIndex = currentIndex,
            onRerenderStale = onRerenderStale,
            onDeleteStaleAudio = onDeleteStaleAudio,
            onOpenVoices = onOpenVoices
        )
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text(
                text = "No chapters",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.index }) { entry ->
                    ChapterRow(
                        index = entry.index,
                        title = entry.title,
                        durationMs = entry.durationMs,
                        pageRange = entry.pageRange,
                        isCurrent = isCurrentChapter(entry.index, currentIndex),
                        isListening = markerListeningOf?.invoke(entry.index) ?: isListening,
                        renderState = renderStateOf?.invoke(entry.index),
                        onJump = onJump,
                        onRenderChapter = onRenderChapter,
                        onDeleteChapterAudio = onDeleteChapterAudio,
                        staleState = staleStateOf?.invoke(entry.index),
                        onRerenderChapter = onRerenderChapter,
                        onPlayChapter = onPlayChapter
                    )
                }
            }
        }
    }
}

/**
 * VS5: chapter-list stale header. The mixed-voice banner (with
 * "Finish re-rendering") wins when present; otherwise the bulk
 * "Re-render stale (N)" button shows while chapters are stale. Delete
 * and Voices buttons ride alongside when their callbacks are set.
 */
@Composable
private fun StaleListHeader(
    bannerText: String?,
    staleCount: Int,
    currentIndex: Int,
    onRerenderStale: ((Int) -> Unit)?,
    onDeleteStaleAudio: (() -> Unit)?,
    onOpenVoices: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val showBulk = bannerText == null && staleCount > 0 && onRerenderStale != null
    val showDelete = staleCount > 0 && onDeleteStaleAudio != null
    if (bannerText == null && !showBulk && !showDelete && onOpenVoices == null) return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (bannerText != null) {
            Text(text = bannerText, style = MaterialTheme.typography.bodyMedium)
            if (onRerenderStale != null) {
                OutlinedButton(onClick = { onRerenderStale(currentIndex) }) {
                    Text("Finish re-rendering")
                }
            }
        } else if (showBulk) {
            val label = if (staleCount == 1) {
                "Re-render stale (1 chapter)"
            } else {
                "Re-render stale ($staleCount chapters)"
            }
            // showBulk implies a non-null callback (see above).
            OutlinedButton(onClick = { onRerenderStale?.invoke(currentIndex) }) { Text(label) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            if (showDelete) {
                // showDelete implies a non-null callback (see above).
                OutlinedButton(onClick = { onDeleteStaleAudio?.invoke() }) {
                    Text("Delete stale audio")
                }
            }
            if (onOpenVoices != null) {
                OutlinedButton(onClick = onOpenVoices) { Text("Voices") }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    durationMs: Long,
    isCurrent: Boolean,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageRange: String? = null,
    isListening: Boolean = true,
    renderState: ChapterRenderState? = null,
    onRenderChapter: ((Int) -> Unit)? = null,
    onDeleteChapterAudio: ((Int) -> Unit)? = null,
    staleState: ChapterStaleState? = null,
    onRerenderChapter: ((Int) -> Unit)? = null,
    onPlayChapter: ((Int) -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isCurrent) { onJump(index) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(
                text = formatChapterSubtitle(durationMs, pageRange),
                style = MaterialTheme.typography.bodyMedium
            )
            if (renderState != null) {
                Text(
                    text = chapterStatusText(renderState),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // VS5: second axis (never merged with the job line above).
            if (staleState != null) {
                Text(
                    text = staleBadgeText(staleState),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ChapterRowActions(
                index = index,
                renderState = renderState,
                onRenderChapter = onRenderChapter,
                onDeleteChapterAudio = onDeleteChapterAudio,
                staleState = staleState,
                onRerenderChapter = onRerenderChapter,
                onPlayChapter = onPlayChapter
            )
        }
        // RN7 deferred wiring: the marker reads "Now playing" for a
        // listening session and "Reading" for a read-only one.
        rowMarkerText(isCurrent, isListening)?.let { marker ->
            Text(
                text = marker,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun ChapterRowActions(
    index: Int,
    renderState: ChapterRenderState?,
    onRenderChapter: ((Int) -> Unit)?,
    onDeleteChapterAudio: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
    staleState: ChapterStaleState? = null,
    onRerenderChapter: ((Int) -> Unit)? = null,
    onPlayChapter: ((Int) -> Unit)? = null
) {
    if (renderState == null && staleState == null) return
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        // UX1: rendered rows listen, so they get an explicit Play button
        // on the same jump path; unrendered rows keep tap-to-read only.
        if (showRowPlayButton(renderState) && onPlayChapter != null) {
            OutlinedButton(onClick = { onPlayChapter(index) }) { Text("Play") }
        }
        if (renderState != null) {
            if (renderState != ChapterRenderState.RENDERED &&
                renderState != ChapterRenderState.RENDERING &&
                onRenderChapter != null
            ) {
                OutlinedButton(onClick = { onRenderChapter(index) }) { Text("Render") }
            }
            if (renderState == ChapterRenderState.RENDERED && onDeleteChapterAudio != null) {
                OutlinedButton(onClick = { onDeleteChapterAudio(index) }) { Text("Delete audio") }
            }
        }
        // VS5: stale rows offer re-render (STALE only; OUTDATED never
        // auto re-renders, CURRENT and NOT_RENDERED have nothing stale).
        if (staleState == ChapterStaleState.STALE && onRerenderChapter != null) {
            OutlinedButton(onClick = { onRerenderChapter(index) }) { Text("Re-render") }
        }
    }
}

/** Preview with 3 fake entries (same fake-data pattern as ReaderPreviewScreen). */
@Preview
@Composable
private fun ChapterListPreview() {
    val entries = listOf(
        ChapterEntry(index = 0, title = "Chapter 1: The Beginning", durationMs = 61_000L),
        ChapterEntry(index = 1, title = "Chapter 2: The Middle", durationMs = 3_661_000L),
        ChapterEntry(index = 2, title = "Chapter 3: The End", durationMs = 125_000L)
    )
    ChapterListScreen(
        entries = entries,
        currentIndex = 1,
        onJump = {},
        onBack = {}
    )
}
