package app.auloud.player.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.auloud.player.bundle.Block

/**
 * RA4: chapter rendering on the RA0 pattern (D-027).
 *
 * One [LazyColumn] item per block, keyed by block id. A paragraph is one
 * [Text] with an [AnnotatedString]: italic/bold spans applied, the current
 * sentence given a background span. The highlight sid reaches each item as
 * a stable [State] read only inside `derivedStateOf`, so a sentence change
 * recomposes just the blocks whose highlight flips. Sentence ranges come
 * from [layoutParagraph] (pure, JVM-tested).
 *
 * Blocks: headings (level 1 = headline, deeper = title), paras and quotes
 * (quotes indented), breaks (divider). Scroll position, tap-to-jump and
 * mode chrome arrive in RA5-RA7; this screen only renders [state].
 *
 * [fontSize] defaults to medium; RA10 wires it to the settings screen.
 */
enum class ReaderFontSize {
    Small,
    Medium,
    Large,
    ExtraLarge
}

@Composable
fun ReaderScreen(
    state: ReaderState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: ReaderFontSize = ReaderFontSize.Medium
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(
                text = state.chapter?.title ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
        when {
            state.isTextLoading || (state.chapter == null && state.textError == null) -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.textError != null -> {
                Text(
                    text = "Text unavailable for this chapter",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> {
                ChapterContent(
                    state = state,
                    fontSize = fontSize,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ChapterContent(
    state: ReaderState,
    fontSize: ReaderFontSize,
    modifier: Modifier = Modifier
) {
    val chapter = state.chapter ?: return
    // Stable reference: items read .value only inside derivedStateOf, so the
    // highlight change recomposes just the flipped blocks (RA0 pattern).
    val highlightSid: State<Int?> = rememberUpdatedState(state.currentSid)
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    LazyColumn(modifier = modifier.padding(horizontal = 16.dp)) {
        items(
            count = chapter.blocks.size,
            key = { index -> chapter.blocks[index].id }
        ) { index ->
            val block = chapter.blocks[index]
            when (block.type) {
                "heading" -> HeadingBlock(block = block, fontSize = fontSize)
                "para", "quote" -> ParagraphBlock(
                    block = block,
                    highlightSid = highlightSid,
                    highlightColor = highlightColor,
                    indented = block.type == "quote",
                    fontSize = fontSize
                )
                else -> BreakBlock()
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: Block, fontSize: ReaderFontSize, modifier: Modifier = Modifier) {
    val text = block.sentences.joinToString(separator = "") { it.text }.trim()
    if (text.isEmpty()) return
    Text(
        text = text,
        style = if (block.level == 1) {
            MaterialTheme.typography.headlineSmall
        } else {
            MaterialTheme.typography.titleLarge
        },
        fontSize = (fontSize.bodySp * 1.4f).sp,
        modifier = modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ParagraphBlock(
    block: Block,
    highlightSid: State<Int?>,
    highlightColor: androidx.compose.ui.graphics.Color,
    indented: Boolean,
    fontSize: ReaderFontSize,
    modifier: Modifier = Modifier
) {
    val layout = remember(block) { layoutParagraph(block) }
    if (layout.text.isBlank()) return
    val highlightRange by remember(block) {
        derivedStateOf {
            val sid = highlightSid.value
            layout.sentences.firstOrNull { it.sid == sid }
        }
    }
    val annotated = remember(layout, highlightRange, highlightColor) {
        buildAnnotatedString {
            append(layout.text)
            layout.italics.forEach { range ->
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), range.first, range.last + 1)
            }
            layout.bolds.forEach { range ->
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
            }
            highlightRange?.let { range ->
                addStyle(SpanStyle(background = highlightColor), range.start, range.end)
            }
        }
    }
    Text(
        text = annotated,
        fontSize = fontSize.bodySp.sp,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (indented) 16.dp else 0.dp,
                top = 2.dp,
                bottom = 6.dp
            )
    )
}

@Composable
private fun BreakBlock(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.fillMaxWidth().padding(vertical = 10.dp))
}

private val ReaderFontSize.bodySp: Float
    get() = when (this) {
        ReaderFontSize.Small -> 14f
        ReaderFontSize.Medium -> 16f
        ReaderFontSize.Large -> 19f
        ReaderFontSize.ExtraLarge -> 22f
    }
