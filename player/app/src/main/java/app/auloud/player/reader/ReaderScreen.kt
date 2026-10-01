package app.auloud.player.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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
    fontSize: ReaderFontSize = ReaderFontSize.Medium,
    onUserScroll: () -> Unit = {},
    onBackToNow: () -> Unit = {},
    onSentenceTap: (Int) -> Unit = {}
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
                    onUserScroll = onUserScroll,
                    onBackToNow = onBackToNow,
                    onSentenceTap = onSentenceTap,
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
    onUserScroll: () -> Unit,
    onBackToNow: () -> Unit,
    onSentenceTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val chapter = state.chapter ?: return
    val listState = rememberLazyListState()
    val index = remember(chapter) { SentenceIndex(chapter.blocks) }
    // Paragraph layouts computed once per chapter (shared by the items and
    // the auto-scroll below — no per-frame allocation).
    val layouts = remember(chapter) { chapter.blocks.map { layoutParagraph(it) } }
    // Stable reference: items read .value only inside derivedStateOf, so the
    // highlight change recomposes just the flipped blocks (RA0 pattern).
    val highlightSid: State<Int?> = rememberUpdatedState(state.currentSid)
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    // Latest text-layout result per block id. Written from onTextLayout
    // (layout phase, never composition), read by the auto-scroll only.
    val layoutResults = remember(chapter) { mutableMapOf<Int, TextLayoutResult>() }
    // Visible block range, tracked off the composition path.
    var visibleBlocks by remember { mutableStateOf(0..0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size
        }.collect { (first, count) ->
            visibleBlocks = first until first + count
        }
    }
    // Drag source only: finger drags detach, programmatic auto-scrolls never
    // do, so no flag and no jitter loop.
    val dragDetector = remember(onUserScroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) onUserScroll()
                return Offset.Zero
            }
        }
    }
    // Auto-scroll while following: the current sentence lands in the upper
    // third (sentence line via the text layout for long paragraphs, block
    // top when the block was never laid out). Restarting on follow change
    // cancels an in-flight scroll the moment the user takes over.
    LaunchedEffect(state.currentSid, state.follow, chapter) {
        if (state.follow != FollowState.Following) return@LaunchedEffect
        val sid = state.currentSid ?: return@LaunchedEffect
        val location = index.locationOf(sid) ?: return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (location.blockIndex !in 0 until total) return@LaunchedEffect
        val block = chapter.blocks[location.blockIndex]
        val sentenceStart = layouts[location.blockIndex].sentences
            .getOrNull(location.indexInBlock)?.start ?: 0
        val lineTop = layoutResults[block.id]?.let { result ->
            try {
                result.getLineTop(result.getLineForOffset(sentenceStart))
            } catch (_: Exception) {
                0f
            }
        } ?: 0f
        val viewportH = listState.layoutInfo.viewportSize.height
        listState.animateScrollToItem(
            location.blockIndex,
            scrollOffsetForLine(lineTop, viewportH)
        )
    }
    val currentBlock = state.currentSid?.let { index.locationOf(it)?.blockIndex }
    val showBackToNow = shouldShowBackToNow(state.follow, currentBlock, visibleBlocks)
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .padding(horizontal = 16.dp)
                .nestedScroll(dragDetector)
        ) {
            items(
                count = chapter.blocks.size,
                key = { itemIndex -> chapter.blocks[itemIndex].id }
            ) { itemIndex ->
                val block = chapter.blocks[itemIndex]
                when (block.type) {
                    "heading" -> HeadingBlock(block = block, fontSize = fontSize)
                    "para", "quote" -> ParagraphBlock(
                        block = block,
                        layout = layouts[itemIndex],
                        highlightSid = highlightSid,
                        highlightColor = highlightColor,
                        indented = block.type == "quote",
                        fontSize = fontSize,
                        onTextLayout = { layoutResults[block.id] = it },
                        onSentenceTap = onSentenceTap
                    )
                    else -> BreakBlock()
                }
            }
        }
        if (showBackToNow) {
            Button(
                onClick = onBackToNow,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                Text("Back to now")
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
    layout: ParagraphLayout,
    highlightSid: State<Int?>,
    highlightColor: androidx.compose.ui.graphics.Color,
    indented: Boolean,
    fontSize: ReaderFontSize,
    onTextLayout: (TextLayoutResult) -> Unit,
    onSentenceTap: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (layout.text.isBlank()) return
    // Retained for tap hit-testing (the auto-scroll keeps its own copy in
    // the results map; this one never triggers recomposition on write).
    var hitLayout by remember(block) { mutableStateOf<TextLayoutResult?>(null) }
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
        onTextLayout = {
            hitLayout = it
            onTextLayout(it)
        },
        modifier = modifier
            .fillMaxWidth()
            .tapToSentence(layout = layout, layoutResult = { hitLayout }, onSentenceTap = onSentenceTap)
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

/** Tap-to-jump (RA6): tap position to character offset to sentence. */
private fun Modifier.tapToSentence(
    layout: ParagraphLayout,
    layoutResult: () -> TextLayoutResult?,
    onSentenceTap: (Int) -> Unit
): Modifier {
    return pointerInput(layout, onSentenceTap) {
        detectTapGestures { position ->
            val result = layoutResult() ?: return@detectTapGestures
            val offset = try {
                result.getOffsetForPosition(position)
            } catch (_: Exception) {
                return@detectTapGestures
            }
            val sid = sidAtOffset(layout.sentences, offset) ?: return@detectTapGestures
            onSentenceTap(sid)
        }
    }
}

private val ReaderFontSize.bodySp: Float
    get() = when (this) {
        ReaderFontSize.Small -> 14f
        ReaderFontSize.Medium -> 16f
        ReaderFontSize.Large -> 19f
        ReaderFontSize.ExtraLarge -> 22f
    }
