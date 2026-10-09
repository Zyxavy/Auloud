package app.auloud.player.reader

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.auloud.player.BuildConfig
import app.auloud.player.data.ProgressRepository
import app.auloud.player.library.BookUiModel
import app.auloud.player.playback.PlayerScreen
import app.auloud.player.render.ChapterOpenTarget
import app.auloud.player.render.ChapterRenderState
import app.auloud.player.render.ChapterStaleState
import app.auloud.player.render.DebugRenderEngines
import app.auloud.player.render.JavaFileRenderIo
import app.auloud.player.render.RenderDebugOverlay
import app.auloud.player.render.RenderPanel
import app.auloud.player.render.RenderPanelViewModel
import app.auloud.player.render.RenderPolicyPrefs
import app.auloud.player.render.RenderService
import app.auloud.player.render.RenderServicePolicy
import app.auloud.player.render.bannerFor
import app.auloud.player.render.buildChapterMediaMap
import app.auloud.player.render.isChapterListeningEnabled
import app.auloud.player.render.partialChapterTarget
import app.auloud.player.render.rerenderModeName
import app.auloud.player.settings.isRenderDebugAvailable
import app.auloud.player.storage.BundleStorage
import app.auloud.player.tts.AndroidSystemTtsDriver
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.SherpaPiperEngine
import app.auloud.player.tts.SystemTtsAdapter
import app.auloud.player.tts.bookVoiceVersionOf
import app.auloud.player.tts.scanAppModelPacks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RN9: hub for partially rendered (2.0 `partial`) books (Slice 10).
 *
 * A partial book is neither fully playable nor read-only: rendered
 * chapters listen, unrendered chapters read. This screen owns the
 * [RenderPanelViewModel] (render panel: estimate, options, charging
 * toggle, voices, start, delete) plus a chapter list with per-chapter
 * states and actions. Tapping a chapter saves the position, then opens
 * the right screen: rendered chapters listen in [PlayerScreen] (the RN8
 * sparse playlist starts through the chapter map, and the
 * end-of-rendered-portion message surfaces through its notice line),
 * unrendered chapters read in [UnrenderedBookScreen]. Back always
 * returns here, so Listen is never a dead end.
 *
 * [chapters] arrives from [BookScreen] (null = not loaded yet).
 *
 * VS4: the "Choose voices" panel link plus the hub "Voices" button open
 * the book-scoped voice screen ([BookVoiceScreen]) hosted here, not the
 * global Settings screen. The old Settings landing (panel to hub to book
 * to MainActivity to Settings) is removed: this book's voices live in
 * the manifest, globals stay in Settings.
 *
 * API 24 safe: Compose + coroutines only (service contact is plain
 * `startService`, the API 24 entry the render service documents).
 */
@Composable
fun PartialBookScreen(
    book: BookUiModel,
    storage: BundleStorage,
    progress: ProgressRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<ChapterEntry>? = null,
    onBookChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val panelSherpaHolder = remember(book.id) { arrayOfNulls<SherpaPiperEngine>(1) }
    val panelVm = remember(book.id) {
        val scratch = File(appContext.cacheDir, "panel-tts-probe")
        val driver = AndroidSystemTtsDriver(appContext)
        val adapter = SystemTtsAdapter(driver, scratch)
        val packs = scanAppModelPacks(appContext)
        val sherpa = SherpaPiperEngine(packs).takeIf { it.voices().isNotEmpty() }
        panelSherpaHolder[0] = sherpa
        val registry = EngineRegistry(
            listOfNotNull(
                adapter,
                sherpa,
                DebugRenderEngines.beepEngineIfDebug(BuildConfig.DEBUG)
            )
        )
        RenderPanelViewModel(
            bookId = book.id,
            bundleDir = book.bundleDir,
            storage = storage,
            progress = progress,
            voices = PrefsTtsStore.fromContext(appContext),
            fileIo = JavaFileRenderIo(),
            policy = RenderPolicyPrefs.load(appContext),
            onPolicyChange = { RenderPolicyPrefs.save(appContext, it) },
            onStartRender = { chapter, scopeName, nextN ->
                try {
                    appContext.startService(
                        RenderService.startIntent(appContext, book.id, chapter, scopeName, nextN)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "render start failed: ${e.message}")
                }
            },
            onPauseRender = { sendRenderAction(appContext, RenderService.ACTION_PAUSE) },
            onResumeRender = { sendRenderAction(appContext, RenderService.ACTION_RESUME) },
            onCancelRender = { sendRenderAction(appContext, RenderService.ACTION_CANCEL) },
            onBookChanged = onBookChanged,
            versionOf = bookVoiceVersionOf(appContext),
            onStartRerender = { chapter, mode ->
                try {
                    appContext.startService(
                        RenderService.startRerenderIntent(
                            appContext,
                            book.id,
                            chapter,
                            rerenderModeName(mode)
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "re-render start failed: ${e.message}")
                }
            },
            registry = registry,
            freeBytes = {
                try {
                    File(book.bundleDir).usableSpace
                } catch (_: Exception) {
                    0L
                }
            }
        )
    }
    DisposableEffect(book.id) {
        onDispose {
            panelVm.clear()
            panelSherpaHolder[0]?.release()
            panelSherpaHolder[0] = null
        }
    }
    val panelState by panelVm.state.collectAsState()
    val map = remember(panelState.manifest) {
        panelState.manifest?.let(::buildChapterMediaMap)
    }
    var listenAt by remember(book.id) { mutableStateOf<Int?>(null) }
    var readAt by remember(book.id) { mutableStateOf<Int?>(null) }
    var showChapters by remember(book.id) { mutableStateOf(false) }
    var showBookVoices by remember(book.id) { mutableStateOf(false) }
    var pendingDelete by remember(book.id) { mutableStateOf<Int?>(null) }
    // VS5: bulk delete-stale-audio confirm (the per-chapter confirm
    // above stays untouched).
    var pendingDeleteStale by remember(book.id) { mutableStateOf(false) }

    fun openChapter(pos: Int) {
        val target = map?.let { partialChapterTarget(pos, it) } ?: return
        scope.launch(Dispatchers.IO) {
            try {
                progress.save(book.id, pos, 0L)
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                when (target) {
                    ChapterOpenTarget.LISTEN -> listenAt = pos
                    ChapterOpenTarget.READ -> readAt = pos
                }
            }
        }
    }

    if (listenAt != null) {
        PlayerScreen(
            book = book,
            onBack = {
                listenAt = null
                panelVm.refresh()
            },
            modifier = modifier,
            chapters = remember(panelState.manifest, map) {
                renderedMediaEntries(panelState)
            },
            showChapters = showChapters,
            onOpenChapters = { showChapters = true },
            onDismissChapters = { showChapters = false }
        )
        return
    }
    if (readAt != null) {
        UnrenderedBookScreen(
            book = book,
            storage = storage,
            progress = progress,
            onBack = {
                readAt = null
                panelVm.refresh()
            },
            modifier = modifier
        )
        return
    }
    if (showBookVoices) {
        BookVoiceHost(
            book = book,
            storage = storage,
            progress = progress,
            onBack = {
                showBookVoices = false
                panelVm.refresh()
            },
            onBookChanged = {
                panelVm.refresh()
                onBookChanged()
            },
            modifier = modifier
        )
        return
    }
    val entries = chapters
    if (entries == null || map == null) {
        ReaderScreen(
            state = ReaderState(isTextLoading = true),
            onBack = onBack,
            modifier = modifier
        )
        return
    }
    if (showChapters) {
        // VS5: stale actions ride the panel state (badges for every
        // chapter, re-render only on file books with editable voices;
        // the hub keeps its own Voices button, so no header Voices here).
        val staleSummary = panelState.staleSummary
        val staleActions = panelState.isFileBook && !panelState.staleReadOnly &&
            staleSummary != null
        val staleCount = staleSummary?.stale ?: 0
        ChapterListScreen(
            entries = entries,
            currentIndex = panelState.readingChapter,
            onJump = {
                showChapters = false
                openChapter(it)
            },
            onBack = { showChapters = false },
            modifier = modifier,
            renderStateOf = { pos ->
                panelState.chapterStates[pos] ?: ChapterRenderState.UNRENDERED
            },
            markerListeningOf = { pos -> map?.let { isChapterListeningEnabled(pos, it) } ?: (pos in panelState.renderedPositions) },
            onRenderChapter = panelVm::renderChapter,
            onDeleteChapterAudio = { pendingDelete = it },
            staleStateOf = if (staleSummary != null) {
                { pos -> panelState.staleStates[pos] ?: ChapterStaleState.NOT_RENDERED }
            } else {
                null
            },
            staleBannerText = staleSummary?.let(::bannerFor)?.text,
            staleCount = staleCount,
            onRerenderStale = if (staleActions) {
                { _ -> panelVm.rerenderStale() }
            } else {
                null
            },
            onRerenderChapter = if (staleActions) {
                panelVm::rerenderChapterStale
            } else {
                null
            },
            onDeleteStaleAudio = if (staleActions && staleCount > 0) {
                { pendingDeleteStale = true }
            } else {
                null
            },
            onOpenVoices = null
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                Text(
                    text = panelState.title.ifBlank { book.title },
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            RenderPanel(
                state = panelState,
                onSelectOption = panelVm::selectOption,
                onSetNextN = panelVm::setNextN,
                onSetChargingOnly = panelVm::setChargingOnly,
                onStart = panelVm::start,
                onPause = panelVm::pause,
                onResume = panelVm::resume,
                onCancel = panelVm::cancel,
                onDeleteAllAudio = panelVm::deleteAllAudio,
                onOpenVoiceSettings = { showBookVoices = true },
                onDismissError = panelVm::dismissError
            )
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                TextButton(onClick = { showChapters = true }) { Text("Chapters") }
                TextButton(onClick = { showBookVoices = true }) { Text("Voices") }
            }
            Spacer(Modifier.height(8.dp))
            if (isRenderDebugAvailable(BuildConfig.DEBUG)) {
                RenderDebugSection(
                    bookId = book.id,
                    panelVm = panelVm,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    pendingDelete?.let { pos ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        panelVm.deleteChapterByPos(pos)
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
            title = { Text("Delete this chapter's audio?") },
            text = { Text("The chapter returns to unrendered. This cannot be undone.") }
        )
    }
    // VS5: bulk delete-stale-audio confirm. Stale audio is the current
    // playable audio, so every stale chapter returns to unrendered and
    // the next render recreates it with the current voices.
    if (pendingDeleteStale) {
        AlertDialog(
            onDismissRequest = { pendingDeleteStale = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteStale = false
                        panelVm.deleteStaleAudio()
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteStale = false }) { Text("Keep") }
            },
            title = { Text("Delete stale audio?") },
            text = {
                Text(
                    "Stale chapters lose their audio and return to unrendered. " +
                        "Rendering them again uses the current voices. " +
                        "This cannot be undone."
                )
            }
        )
    }
}

/**
 * RN9: rendered-only chapter entries with playlist (media) indexes.
 *
 * The player playlist holds rendered chapters only, so its rows carry
 * media indexes (not manifest positions): jumps and the current marker
 * stay correct on sparse books. Titles and durations come from the
 * manifest; unrendered chapters have no row here (the hub list owns
 * them).
 */
private fun renderedMediaEntries(state: app.auloud.player.render.RenderPanelState): List<ChapterEntry> {
    val manifest = state.manifest ?: return emptyList()
    val map = buildChapterMediaMap(manifest)
    val sorted = manifest.chapters.sortedBy { it.index }
    return sorted.mapIndexedNotNull { pos, chapter ->
        val media = map.mediaIndexOf(pos) ?: return@mapIndexedNotNull null
        ChapterEntry(
            index = media,
            title = chapter.title,
            durationMs = chapter.durationMs ?: 0L
        )
    }
}

/**
 * RN9: debug overlay section for the hub (debug builds only).
 *
 * Battery temperature reads the sticky battery broadcast; spool size
 * walks the render spool dir; both refresh every 3 s off the
 * composition path. The job file knows chapters, not sentences, so the
 * sentence slot points at the live render notification.
 */
@Composable
private fun RenderDebugSection(
    bookId: String,
    panelVm: RenderPanelViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val panelState by panelVm.state.collectAsState()
    var batteryTempC by remember(bookId) { mutableStateOf<Float?>(null) }
    var spoolBytes by remember(bookId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(bookId) {
        while (true) {
            batteryTempC = readBatteryTempC(appContext)
            spoolBytes = withContext(Dispatchers.IO) { readSpoolBytes(appContext, bookId) }
            delay(3_000L)
        }
    }
    val chapterText = remember(panelState.jobCurrentNumber, panelState.jobDone, panelState.jobTotal) {
        val current = panelState.jobCurrentNumber?.let { "ch $it" } ?: "idle"
        if (panelState.jobTotal > 0) {
            "$current (${panelState.jobDone} of ${panelState.jobTotal} done)"
        } else {
            current
        }
    }
    RenderDebugOverlay(
        chapterText = chapterText,
        sentenceText = "live in notification",
        batteryTempC = batteryTempC,
        spoolBytes = spoolBytes,
        modifier = modifier
    )
}

/** RN9: sticky-broadcast battery temperature in Celsius (null when absent). */
private fun readBatteryTempC(appContext: Context): Float? {
    return try {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) null else tenths / 10f
    } catch (_: Exception) {
        null
    }
}

/** RN9: render spool size for [bookId] (null when the spool dir is absent). */
private fun readSpoolBytes(appContext: Context, bookId: String): Long? {
    return try {
        val dir = File(RenderServicePolicy.spoolDirFor(appContext.cacheDir.absolutePath, bookId))
        if (!dir.isDirectory) return null
        var total = 0L
        for (file in dir.walkTopDown()) {
            if (file.isFile) total += file.length()
        }
        total
    } catch (_: Exception) {
        null
    }
}

private const val TAG = "AuloudRender"
