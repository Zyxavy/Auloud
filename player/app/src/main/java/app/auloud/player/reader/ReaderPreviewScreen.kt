package app.auloud.player.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.auloud.player.bundle.Block
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence
import app.auloud.player.bundle.Span
import kotlinx.coroutines.delay

/**
 * TEMPORARY debug preview of [ReaderScreen] (replaces the RA0 spike, whose
 * Compose-vs-Views question is decided in D-027). Feeds the real rendering
 * path a synthetic chapter with a fake advancing highlight so the Tab E
 * look-check can happen before RA5-RA7 wire playback, scrolling and modes.
 * Deleted (or repointed at real state) once the reader is reachable from
 * the player screen.
 */
private const val PREVIEW_SENTENCES = 5000
private const val PREVIEW_PER_PARA = 8
private const val PREVIEW_TICK_MS = 400L

private val PREVIEW_WORDS = listOf(
    "river", "lamp", "garden", "window", "road", "cat", "bell", "forest",
    "quiet", "amber", "mossy", "distant", "warm", "hollow", "patient", "grey"
)

private fun previewText(index: Int): String {
    val w = PREVIEW_WORDS
    return "The ${w[index % w.size]} ${w[(index / 2) % w.size]} " +
        "rested near the ${w[(index + 3) % w.size]} ${w[(index + 5) % w.size]}. "
}

private fun buildPreviewChapter(): ChapterText {
    val blocks = ArrayList<Block>()
    var sid = 1
    var id = 1
    blocks.add(
        Block(
            id = id++,
            type = "heading",
            level = 1,
            sentences = listOf(
                Sentence(sid++, "narrator", 0, 400, "Preview chapter ")
            )
        )
    )
    while (sid <= PREVIEW_SENTENCES) {
        val sentences = ArrayList<Sentence>()
        repeat(PREVIEW_PER_PARA) {
            if (sid > PREVIEW_SENTENCES) return@repeat
            val spans = if (sid % 9 == 0) {
                listOf(Span(4, 12, "italic"))
            } else if (sid % 13 == 0) {
                listOf(Span(0, 3, "bold"))
            } else {
                emptyList()
            }
            sentences.add(Sentence(sid, "narrator", 0, 400, previewText(sid), spans))
            sid++
        }
        if (sentences.isNotEmpty()) blocks.add(Block(id++, "para", sentences = sentences))
    }
    return ChapterText("1.0", 1, "Preview chapter", 2_000_000L, blocks)
}

@Composable
fun ReaderPreviewScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val chapter = remember { buildPreviewChapter() }
    var currentSid by remember { mutableIntStateOf(1) }
    var follow by remember { mutableStateOf(FollowState.Following) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(PREVIEW_TICK_MS)
            if (currentSid < PREVIEW_SENTENCES) currentSid++
        }
    }
    ReaderScreen(
        state = ReaderState(
            chapterIndex = 0,
            chapter = chapter,
            mode = ReaderMode.ReadListen,
            follow = follow,
            currentSid = currentSid,
            positionMs = 0L,
            isPlaying = true
        ),
        onBack = onBack,
        onUserScroll = { follow = FollowState.Detached },
        onBackToNow = { follow = FollowState.Following },
        onSentenceTap = { tapped ->
            currentSid = tapped
            follow = FollowState.Following
        },
        modifier = modifier
    )
}
