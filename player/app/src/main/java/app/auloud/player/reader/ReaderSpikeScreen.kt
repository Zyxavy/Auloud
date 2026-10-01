package app.auloud.player.reader

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * RA0 THROWAWAY spike — answers "is Compose fast enough on the Tab E" and
 * nothing else. Do not build on this file: RA4 writes the real reader with
 * JVM tests. Delete this file (and its Settings/MainActivity hooks) once the
 * RA0 decision is logged in `docs/DECISIONS.md`.
 *
 * Renders 5,000 synthetic sentences as a [LazyColumn] of paragraph [Text]s
 * (one item per block, keyed by block id). A fake highlight advances every
 * 400 ms; auto-scroll keeps the current block at the top. Narrow updates:
 * each item derives only whether the current sid falls inside its own range,
 * so a tick re-evaluates cheap range checks and recomposes just the two
 * blocks whose highlight state flips.
 */
private const val SPIKE_SENTENCES = 5000
private const val SENTENCES_PER_PARA = 8
private const val TICK_MS = 400L
private const val PSS_TICK_MS = 3000L

private data class SpikeSentence(val sid: Int, val text: String, val italic: Boolean)

private data class SpikeBlock(val id: Int, val sentences: List<SpikeSentence>) {
    val firstSid: Int = sentences.first().sid
    val lastSid: Int = sentences.last().sid
    /** Full paragraph text with each sentence's char range precomputed. */
    val paragraph: String = sentences.joinToString(separator = "") { it.text }
    val ranges: List<IntRange> = run {
        var offset = 0
        sentences.map { sentence ->
            val range = offset until offset + sentence.text.length
            offset += sentence.text.length
            range
        }
    }
}

private val SPIKE_WORDS = listOf(
    "river", "lamp", "garden", "window", "road", "cat", "bell", "forest",
    "quiet", "amber", "mossy", "distant", "warm", "hollow", "patient", "grey"
)

private fun spikeSentenceText(index: Int): String {
    val w = SPIKE_WORDS
    return "The ${w[index % w.size]} ${w[(index / 2) % w.size]} " +
        "rested near the ${w[(index + 3) % w.size]} ${w[(index + 5) % w.size]}. "
}

private fun buildSpikeChapter(): List<SpikeBlock> {
    val blocks = ArrayList<SpikeBlock>((SPIKE_SENTENCES / SENTENCES_PER_PARA) + 1)
    var sid = 1
    var id = 1
    while (sid <= SPIKE_SENTENCES) {
        val sentences = ArrayList<SpikeSentence>(SENTENCES_PER_PARA)
        repeat(SENTENCES_PER_PARA) {
            if (sid > SPIKE_SENTENCES) return@repeat
            sentences.add(
                SpikeSentence(
                    sid = sid,
                    text = spikeSentenceText(sid),
                    italic = sid % 7 == 0
                )
            )
            sid++
        }
        if (sentences.isNotEmpty()) blocks.add(SpikeBlock(id++, sentences))
    }
    return blocks
}

private fun readTotalPssMb(context: Context): Int {
    return try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos = manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
        val kb = infos.firstOrNull()?.totalPss ?: -1
        if (kb < 0) -1 else kb / 1024
    } catch (_: Exception) {
        -1
    }
}

@Composable
fun ReaderSpikeScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val blocks = remember { buildSpikeChapter() }
    // sid -> block index for the auto-scroll (plain array, not state).
    val blockIndexOfSid = remember(blocks) {
        IntArray(SPIKE_SENTENCES + 1).also { map ->
            blocks.forEachIndexed { index, block ->
                for (sid in block.firstSid..block.lastSid) map[sid] = index
            }
        }
    }
    // Single stable State for the ticker AND paragraph items: items read
    // .value only inside their own derivedStateOf, so ticks recompose just
    // the two blocks whose highlight flips.
    val currentSidState = remember { mutableIntStateOf(1) }
    val currentSid by currentSidState
    var follow by remember { mutableStateOf(true) }
    var pssMb by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()

    // Fake playback ticker: 400 ms per sentence, matching the RA0a long tone.
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            if (currentSidState.intValue < SPIKE_SENTENCES) currentSidState.intValue++
        }
    }
    // PSS readout every few seconds (RA0 memory question).
    LaunchedEffect(Unit) {
        while (true) {
            pssMb = readTotalPssMb(appContext)
            delay(PSS_TICK_MS)
        }
    }
    // Auto-scroll: keep the current block at the top while following.
    LaunchedEffect(currentSid, follow) {
        if (!follow) return@LaunchedEffect
        val index = blockIndexOfSid.getOrElse(currentSid) { -1 }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = "RA0 spike (throwaway)",
                style = MaterialTheme.typography.titleSmall
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "sid $currentSid/$SPIKE_SENTENCES" +
                    (if (pssMb >= 0) "  PSS ${pssMb} MB" else ""),
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { follow = !follow }) {
                Text(if (follow) "Following: on" else "Following: off")
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
        ) {
            items(
                count = blocks.size,
                key = { index -> blocks[index].id }
            ) { index ->
                SpikeParagraph(
                    block = blocks[index],
                    currentSid = currentSidState,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SpikeParagraph(
    block: SpikeBlock,
    currentSid: State<Int>,
    modifier: Modifier = Modifier
) {
    // Only this block's highlight membership is derived: every tick
    // re-evaluates a cheap range check in all items, but recomposition
    // fires only where the result flips (old block + new block).
    val highlightedRange by remember(block) {
        derivedStateOf {
            val sid = currentSid.value
            if (sid < block.firstSid || sid > block.lastSid) {
                null
            } else {
                block.ranges[sid - block.firstSid]
            }
        }
    }
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val annotated = remember(block, highlightedRange) {
        buildAnnotatedString {
            append(block.paragraph)
            block.sentences.forEachIndexed { i, sentence ->
                if (sentence.italic) {
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), block.ranges[i].first, block.ranges[i].last + 1)
                }
            }
            highlightedRange?.let { range ->
                addStyle(
                    SpanStyle(background = highlightColor),
                    range.first,
                    range.last + 1
                )
            }
        }
    }
    Text(text = annotated, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
}
