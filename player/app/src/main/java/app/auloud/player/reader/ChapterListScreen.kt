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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * CP3: chapter list screen (titles, durations, current marker, tap to jump).
 *
 * Reachable from the reader and Listen screens via a "Chapters" button.
 * Tapping a non-current row calls [onJump] with that chapter index; tapping
 * the current row is a no-op. [onBack] closes the list.
 *
 * Narrow recompositions for the slow Tab E: rows receive only primitives
 * (index, title, duration, marker flag), never whole state objects.
 */
@Composable
fun ChapterListScreen(
    entries: List<ChapterEntry>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    ChapterListContent(
        entries = entries,
        currentIndex = currentIndex,
        onJump = onJump,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun ChapterListContent(
    entries: List<ChapterEntry>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = onBack) { Text("Back") }
        }
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
                        onJump = onJump
                    )
                }
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
    pageRange: String? = null
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
        }
        if (isCurrent) {
            Text(
                text = "Now playing",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp)
            )
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
