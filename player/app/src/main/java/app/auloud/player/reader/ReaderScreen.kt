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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.key
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
 * IN9: `para` and `quote` sentences with the reserved `dialogue` speaker
 * draw in `MaterialTheme.colorScheme.tertiary` when [dialogueMarking] is on
 * (headings keep the headline style); 1.x books have no dialogue speakers.
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
    onSentenceTap: (Int) -> Unit = {},
    onTopVisibleSentence: (Int) -> Unit = {},
    onOpenChapters: () -> Unit = {},
    onConfirmTapJump: () -> Unit = {},
    onDismissTapJump: () -> Unit = {},
    /**
     * IN9: dialogue marking (dialogue sentences in the tertiary accent).
     * Defaults to on; 1.x books carry no `dialogue` speakers so they render
     * exactly as before either way.
     */
    dialogueMarking: Boolean = true
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = onOpenChapters) { Text("Chapters") }
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
                    text = if (state.textKind == TextKind.PdfForm) {
                        "Page-only chapter - listening still works"
                    } else {
                        "Text unavailable for this chapter"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> {
                // Fresh list state per chapter (scroll, results, visibility).
                key(state.chapter) {
                    ChapterContent(
                        state = state,
                        fontSize = fontSize,
                        onUserScroll = onUserScroll,
                        onBackToNow = onBackToNow,
                        onSentenceTap = onSentenceTap,
                        onTopVisibleSentence = onTopVisibleSentence,
                        dialogueMarking = dialogueMarking,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // CP3 follow-up: a tap arms the jump; the audio moves only on
                // confirm, so accidental taps never lose the place. The
                // excerpt is hoisted out of the dialog so the sentence list
                // is not rebuilt on every recomposition.
                val pending = state.pendingTapSid
                val pendingExcerpt = remember(state.chapter, pending) {
                    state.chapter
                        ?.sentencesInOrder()
                        ?.firstOrNull { it.sid == pending }
                        ?.text
                }
                if (pending != null) {
                    AlertDialog(
                        onDismissRequest = onDismissTapJump,
                        confirmButton = {
                            TextButton(onClick = onConfirmTapJump) { Text("Jump") }
                        },
                        dismissButton = {
                            TextButton(onClick = onDismissTapJump) { Text("Cancel") }
                        },
                        title = { Text("Jump to this line?") },
                        text = pendingExcerpt?.let { { Text(it) } }
                    )
                }
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
    onTopVisibleSentence: (Int) -> Unit,
    dialogueMarking: Boolean = true,
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
    // IN9: dialogue sentences in the tertiary accent (off via the reading
    // setting). 1.x chapters carry no dialogue speakers, so they render
    // exactly as before.
    val dialogueColor = MaterialTheme.colorScheme.tertiary
    // Latest text-layout result per block id. Written from onTextLayout
    // (layout phase, never composition), read by the auto-scroll only.
    val layoutResults = remember(chapter) { mutableMapOf<Int, TextLayoutResult>() }
    // Visible block range, tracked off the composition path. The same flow
    // also reports the top visible sentence (RA7 Read-mode position): the
    // layout hit test at the top edge resolves the sentence, so long
    // paragraphs report their actual top sentence, not the block start.
    var visibleBlocks by remember { mutableStateOf(0..0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size
        }.collect { (first, count) ->
            visibleBlocks = first until first + count
        }
    }
    // FP3: the top-visible hit test feeds Read-mode position only (the
    // ViewModel ignores it elsewhere), so the effect does not run outside
    // Read mode: no layout hit tests whose results would die.
    if (state.mode == ReaderMode.Read) {
        LaunchedEffect(listState, chapter) {
            snapshotFlow {
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            }.collect { (blockIndex, scrollPx) ->
                val block = chapter.blocks.getOrNull(blockIndex) ?: return@collect
                val layout = layouts.getOrNull(blockIndex) ?: return@collect
                if (block.type != "para" && block.type != "quote") {
                    block.sentences.firstOrNull()?.let { onTopVisibleSentence(it.sid) }
                    return@collect
                }
                val offset = layoutResults[block.id]?.let { result ->
                    try {
                        result.getOffsetForPosition(Offset(100f, scrollPx + 4f))
                    } catch (_: Exception) {
                        0
                    }
                } ?: 0
                sidAtOffset(layout.sentences, offset)?.let { onTopVisibleSentence(it) }
            }
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
                        dialogueColor = dialogueColor,
                        dialogueMarking = dialogueMarking,
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
    dialogueColor: androidx.compose.ui.graphics.Color,
    dialogueMarking: Boolean,
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
    // IN9: dialogue ranges (empty when the setting is off or the block has
    // no dialogue sentences; 1.x blocks always yield empty).
    val dialogueSpans = remember(block, layout, dialogueMarking) {
        dialogueRanges(block, layout, dialogueMarking)
    }
    val annotated = remember(layout, highlightRange, highlightColor, dialogueSpans, dialogueColor) {
        buildAnnotatedString {
            append(layout.text)
            layout.italics.forEach { range ->
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), range.first, range.last + 1)
            }
            layout.bolds.forEach { range ->
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
            }
            dialogueSpans.forEach { range ->
                addStyle(SpanStyle(color = dialogueColor), range.first, range.last + 1)
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
