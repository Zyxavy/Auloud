package app.auloud.player.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.auloud.player.bundle.BundleParser
import app.auloud.player.data.ProgressRepository
import app.auloud.player.library.BookUiModel
import app.auloud.player.playback.PlaybackController
import app.auloud.player.playback.PlaybackIntents
import app.auloud.player.playback.StreamRoute
import app.auloud.player.settings.PrefsReaderModeStore
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * IN9: read-only book screen for unrendered (2.0 `none`/`partial`) books.
 *
 * No service, no controller, no audio controls: an [UnrenderedReaderViewModel]
 * loads one chapter's JSON at a time and saves the reading position as
 * chapter + sid. The shared [ReaderScreen] renders the text (mapped onto a
 * [ReaderState] in Read mode with no pending tap prompt); the mode switcher
 * keeps Read selected with Listen and Read + listen disabled plus the
 * render hint. The stored global mode is never written here.
 *
 * Chapter text paths resolve from the bundle manifest; a manifest read
 * failure means every chapter reports "text unavailable" (same RA10 rule as
 * the timed reader). [chapters] arrives from [BookScreen] (null = not
 * loaded yet); text paths load here once per book.
 *
 * API 24 safe: Compose + coroutines only.
 */
@Composable
fun UnrenderedBookScreen(
    book: BookUiModel,
    storage: BundleStorage,
    progress: ProgressRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<ChapterEntry>? = null,
    showChapters: Boolean = false,
    onOpenChapters: () -> Unit = {},
    onDismissChapters: () -> Unit = {},
    /**
     * ST7-fix: manifest chapter position to start streaming on open
     * ("Listen now" from the hub, null = read-only open as before).
     */
    autoPlayChapter: Int? = null
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val modeStore = remember(appContext) { PrefsReaderModeStore.fromContext(appContext) }
    var textPaths by remember(book.id) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(book.id) {
        textPaths = withContext(Dispatchers.IO) {
            try {
                val root = book.bundleDir.trimEnd('/')
                val raw = storage.readText("$root/manifest.json")
                BundleParser.parseText(raw).getOrThrow().chapters.map { chapter ->
                    "$root/${chapter.text}"
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val paths = textPaths
    val entries = chapters
    if (paths == null || entries == null) {
        UnrenderedLoading(onBack = onBack, modifier = modifier)
        return
    }
    UnrenderedContent(
        book = book,
        storage = storage,
        progress = progress,
        textPaths = paths,
        entries = entries,
        showChapters = showChapters,
        onOpenChapters = onOpenChapters,
        onDismissChapters = onDismissChapters,
        onBack = onBack,
        modifier = modifier,
        autoPlayChapter = autoPlayChapter
    )
}

@Composable
private fun UnrenderedContent(
    book: BookUiModel,
    storage: BundleStorage,
    progress: ProgressRepository,
    textPaths: List<String>,
    entries: List<ChapterEntry>,
    showChapters: Boolean,
    onOpenChapters: () -> Unit,
    onDismissChapters: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoPlayChapter: Int? = null
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val modeStore = remember(appContext) { PrefsReaderModeStore.fromContext(appContext) }
    // ST5: live audio attachment (dormant until the ST0 gate passes).
    val streamingAvailable = StreamRoute.GATE_PASSED
    val scope = rememberCoroutineScope()
    val controller = remember(appContext, book.id) { PlaybackController(appContext, scope) }
    LaunchedEffect(book.id, streamingAvailable) {
        if (streamingAvailable) controller.connect()
    }
    val live = remember(book.id, streamingAvailable) {
        if (!streamingAvailable) {
            null
        } else {
            LiveStream(
                sid = controller.state.map { it.streamSid }
                    .stateIn(scope, SharingStarted.Eagerly, null),
                restartAt = { index ->
                    controller.seekToSentence(index)
                    controller.play()
                },
                seekToChapter = { pos ->
                    appContext.startService(
                        PlaybackIntents.serviceIntent(appContext)
                            .putExtra(PlaybackIntents.EXTRA_BOOK_ID, book.id)
                            .putExtra(PlaybackIntents.EXTRA_STREAM_CHAPTER, pos)
                    )
                },
                nextChapter = controller::streamNextChapter,
                previousChapter = controller::streamPreviousChapter
            )
        }
    }
    val viewModel = remember(book.id) {
        UnrenderedReaderViewModel(
            bookId = book.id,
            storage = storage,
            textPathForChapter = { index -> textPaths.getOrNull(index) },
            chapterCount = entries.size,
            progress = progress,
            live = live
        )
    }
    DisposableEffect(book.id) {
        onDispose {
            viewModel.clear()
            controller.release()
        }
    }
    val unrendered by viewModel.state.collectAsState()
    val playerState by controller.state.collectAsState()
    // ST7-fix: one shared "start the stream here" for the live bar and
    // the mode row (the service auto-plays fresh loads and jumps, so
    // this is safe before the controller connects).
    fun playLive() {
        appContext.startService(
            PlaybackIntents.serviceIntent(appContext)
                .putExtra(PlaybackIntents.EXTRA_BOOK_ID, book.id)
                .putExtra(
                    PlaybackIntents.EXTRA_STREAM_CHAPTER,
                    unrendered.chapterIndex
                )
        )
        controller.play()
    }
    // ST7-fix: "Listen now" opens already playing (hub autoplay target).
    LaunchedEffect(book.id, autoPlayChapter, streamingAvailable) {
        if (streamingAvailable && autoPlayChapter != null) {
            appContext.startService(
                PlaybackIntents.serviceIntent(appContext)
                    .putExtra(PlaybackIntents.EXTRA_BOOK_ID, book.id)
                    .putExtra(PlaybackIntents.EXTRA_STREAM_CHAPTER, autoPlayChapter)
            )
            controller.play()
        }
    }
    // Reading prefs read once per session (same rule as the timed reader:
    // the session remounts when returning from Settings).
    val fontSize = remember(book.id) { modeStore.fontSize() }
    val marking = remember(book.id) { modeStore.dialogueMarking() }
    val keepOn = shouldKeepScreenOn(ReaderMode.Read, remember { modeStore.keepScreenOn() })
    val view = LocalView.current
    DisposableEffect(keepOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = previous }
    }
    if (showChapters) {
        ChapterListScreen(
            entries = entries,
            currentIndex = unrendered.chapterIndex,
            onJump = {
                viewModel.jumpToChapter(it)
                onDismissChapters()
            },
            onBack = onDismissChapters,
            modifier = modifier,
            // RN7 deferred wiring: read-only rows mark the current
            // chapter "Reading", never "Now playing".
            isListening = false
        )
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        // ST5: live voice bar (streaming only; the mode row below is
        // untouched, so gate-closed builds render exactly as before).
        if (streamingAvailable) {
            val sentences = unrendered.chapter?.sentencesInOrder().orEmpty()
            LiveListenBar(
                isPlaying = playerState.isPlaying,
                preparing = playerState.isPlaying && unrendered.liveSid == null,
                fraction = liveFractionOf(unrendered.liveSid, sentences),
                onPlay = ::playLive,
                onPause = controller::pause
            )
        }
        ReaderScreen(
            state = ReaderState(
                chapterIndex = unrendered.chapterIndex,
                chapter = unrendered.chapter,
                mode = ReaderMode.Read,
                follow = unrendered.follow,
                // ST5: the highlight follows the voice when live.
                currentSid = unrendered.liveSid ?: unrendered.currentSid,
                positionMs = 0L,
                isPlaying = streamingAvailable && playerState.isPlaying,
                isTextLoading = unrendered.isTextLoading,
                textError = unrendered.textError,
                pendingTapSid = null,
                textKind = unrendered.textKind
            ),
            onBack = onBack,
            fontSize = fontSize,
            onUserScroll = viewModel::onUserScrolled,
            onBackToNow = viewModel::onBackToNow,
            onSentenceTap = viewModel::onSentenceTap,
            onTopVisibleSentence = viewModel::onTopVisibleSid,
            onOpenChapters = onOpenChapters,
            onPreviousChapter = viewModel::previousChapter,
            onNextChapter = viewModel::nextChapter,
            canGoPrevious = unrendered.chapterIndex > 0,
            canGoNext = unrendered.chapterIndex < entries.size - 1,
            dialogueMarking = marking,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        ModeSwitcherRow(
            mode = ReaderMode.Read,
            // ST7-fix: the Listen buttons start the live voice (the reader
            // stays put: this screen IS read + listen for streams).
            onMode = { mode ->
                if (streamingAvailable &&
                    (mode == ReaderMode.Listen || mode == ReaderMode.ReadListen)
                ) {
                    playLive()
                }
            },
            listenEnabled = streamingAvailable,
            listenHint = if (streamingAvailable) "Live voice, not saved" else LISTEN_UNAVAILABLE_HINT
        )
    }
}

@Composable
private fun UnrenderedLoading(onBack: () -> Unit, modifier: Modifier = Modifier) {
    ReaderScreen(
        state = ReaderState(isTextLoading = true),
        onBack = onBack,
        modifier = modifier
    )
}

/**
 * ST5: live voice bar for unrendered chapters: play/pause, the "not
 * saved" label and the sentence fraction (the stream path has no time
 * seek bar). Shown only while streaming is available.
 */
@Composable
private fun LiveListenBar(
    isPlaying: Boolean,
    preparing: Boolean,
    fraction: Float?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (preparing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text(
                text = "Preparing live voice…",
                style = MaterialTheme.typography.bodyMedium
            )
            return
        }
        Button(onClick = { if (isPlaying) onPause() else onPlay() }) {
            Text(if (isPlaying) "Pause" else "Listen now")
        }
        Text(
            text = if (fraction == null) {
                "Live voice, not saved"
            } else {
                "Live voice, not saved - ${(fraction * 100).toInt()}%"
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
