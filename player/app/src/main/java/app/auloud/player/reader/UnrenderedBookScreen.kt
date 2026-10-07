package app.auloud.player.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import app.auloud.player.bundle.BundleParser
import app.auloud.player.data.ProgressRepository
import app.auloud.player.library.BookUiModel
import app.auloud.player.settings.PrefsReaderModeStore
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.Dispatchers
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
    onDismissChapters: () -> Unit = {}
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
        modifier = modifier
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val modeStore = remember(appContext) { PrefsReaderModeStore.fromContext(appContext) }
    val viewModel = remember(book.id) {
        UnrenderedReaderViewModel(
            bookId = book.id,
            storage = storage,
            textPathForChapter = { index -> textPaths.getOrNull(index) },
            chapterCount = entries.size,
            progress = progress
        )
    }
    DisposableEffect(book.id) {
        onDispose { viewModel.clear() }
    }
    val unrendered by viewModel.state.collectAsState()
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
        ReaderScreen(
            state = ReaderState(
                chapterIndex = unrendered.chapterIndex,
                chapter = unrendered.chapter,
                mode = ReaderMode.Read,
                follow = unrendered.follow,
                currentSid = unrendered.currentSid,
                positionMs = 0L,
                isPlaying = false,
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
            dialogueMarking = marking,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        ModeSwitcherRow(
            mode = ReaderMode.Read,
            onMode = {},
            listenEnabled = false,
            listenHint = LISTEN_UNAVAILABLE_HINT
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
