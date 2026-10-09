package app.auloud.player.reader

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import app.auloud.player.BuildConfig
import app.auloud.player.battery.BatteryPromptDialog
import app.auloud.player.battery.BatteryPromptLogic
import app.auloud.player.battery.BatterySettingsIntents
import app.auloud.player.battery.PrefsBatteryPromptStore
import app.auloud.player.bundle.BundleParser
import app.auloud.player.data.ProgressRepository
import app.auloud.player.library.BookUiModel
import app.auloud.player.playback.PlaybackController
import app.auloud.player.playback.PlaybackService
import app.auloud.player.playback.PlayerScreen
import app.auloud.player.playback.ReaderDebugOverlay
import app.auloud.player.playback.SleepOption
import app.auloud.player.playback.SleepTimerButton
import app.auloud.player.playback.SpeedButton
import app.auloud.player.playback.TransientNotice
import app.auloud.player.playback.cycleSleepOption
import app.auloud.player.playback.nextSpeed
import app.auloud.player.playback.readTotalPssMb
import app.auloud.player.playback.sendSleepOption
import app.auloud.player.settings.PrefsReaderModeStore
import app.auloud.player.settings.ReaderModeStore
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * RA7: one book's screen across all three modes.
 *
 * The mode is a persisted global setting (restored on launch, shared by
 * every book); the saved playback position is the single shared place
 * (never stored per mode), so switching modes only changes what plays and
 * what shows. Listen reuses the Slice 1 [PlayerScreen]; Read and
 * Read + listen share [ReaderSession] below.
 *
 * IN9: unrendered books (2.0 `none`/`partial`, detected from the manifest
 * `render_state` with the library chip map as the pre-load hint) open the
 * RN9 render hub ([PartialBookScreen]): the panel plus per-chapter Read
 * (read-only [UnrenderedBookScreen]) and Listen routing. No service, no
 * controller and no audio controls play before a chapter is chosen, and
 * the Listen modes stay disabled at book level with the render hint. The
 * stored global mode is left untouched, so returning to a rendered book
 * keeps its mode. [UnrenderedBookScreen] stays as the hub's Read
 * destination.
 */
@Composable
fun BookScreen(
    book: BookUiModel,
    storage: BundleStorage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    progress: ProgressRepository,
    onBookChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val modeStore = remember(appContext) { PrefsReaderModeStore.fromContext(appContext) }
    var mode by remember(book.id) { mutableStateOf(modeStore.mode()) }
    // CP3: shared chapter list for both branches (null = not loaded yet,
    // emptyList = manifest unreadable). Loaded once per book here; the
    // reader branch keeps its own textPaths load unchanged below.
    var chapters by remember(book.id) { mutableStateOf<List<ChapterEntry>?>(null) }
    // IN9: manifest render_state (null = not loaded yet). The library chip
    // map ([BookUiModel.renderState]) is the pre-load hint; the manifest is
    // truth once read (a stale map after a Slice 10 render must not keep a
    // now-complete book read-only).
    var manifestRenderState by remember(book.id) { mutableStateOf<String?>(null) }
    var manifestLoaded by remember(book.id) { mutableStateOf(false) }
    var showChapters by remember(book.id) { mutableStateOf(false) }
    // Hoisted persist + state: both branches route mode changes through
    // here, so the setting and the UI can never disagree.
    val changeMode: (ReaderMode) -> Unit = {
        modeStore.setMode(it)
        mode = it
    }
    LaunchedEffect(book.id) {
        val parsed = withContext(Dispatchers.IO) {
            try {
                val root = book.bundleDir.trimEnd('/')
                val raw = storage.readText("$root/manifest.json")
                BundleParser.parseText(raw).getOrThrow()
            } catch (_: Exception) {
                null
            }
        }
        chapters = parsed?.chapters?.let(::toChapterEntries) ?: emptyList()
        manifestRenderState = parsed?.renderState
        manifestLoaded = true
    }
    // IN9: read-only routing. The manifest wins once loaded; before that the
    // library map decides (a just-imported book shows read-only immediately
    // instead of flashing the player and starting a service the gate stops).
    val effectiveRenderState = if (manifestLoaded) manifestRenderState else book.renderState
    // RN9 fix: `none` (fresh imports) and `partial` books open the hub
    // (render panel plus per-chapter reader/player routing). Rendered
    // books (1.x null, 2.0 `complete`) fall through to the player/reader
    // below; unknown values fall through to the read-only screen.
    if (shouldOpenRenderHub(effectiveRenderState)) {
        PartialBookScreen(
            book = book,
            storage = storage,
            progress = progress,
            onBack = onBack,
            modifier = modifier,
            chapters = chapters,
            onBookChanged = onBookChanged
        )
        return
    }
    if (!isListenAvailable(effectiveRenderState)) {
        UnrenderedBookScreen(
            book = book,
            storage = storage,
            progress = progress,
            onBack = onBack,
            modifier = modifier,
            chapters = chapters,
            showChapters = showChapters,
            onOpenChapters = { showChapters = true },
            onDismissChapters = { showChapters = false }
        )
        return
    }
    if (mode == ReaderMode.Listen) {
        PlayerScreen(
            book = book,
            onBack = onBack,
            modifier = modifier,
            modeSwitcher = { ModeSwitcherRow(mode = mode, onMode = changeMode) },
            chapters = chapters,
            showChapters = showChapters,
            onOpenChapters = { showChapters = true },
            onDismissChapters = { showChapters = false }
        )
    } else {
        ReaderSession(
            book = book,
            mode = mode,
            modeStore = modeStore,
            storage = storage,
            onModeChange = changeMode,
            onBack = onBack,
            modifier = modifier,
            chapters = chapters,
            showChapters = showChapters,
            onOpenChapters = { showChapters = true },
            onDismissChapters = { showChapters = false }
        )
    }
}

/**
 * Read / Read + listen: owns the [PlaybackController] (same lifecycle as
 * [PlayerScreen]'s) plus a [ReaderViewModel] on the controller's state.
 * Chapter text paths resolve from the bundle manifest; the manifest read
 * failure just means every chapter reports "text unavailable" (RA10).
 */
@Composable
private fun ReaderSession(
    book: BookUiModel,
    mode: ReaderMode,
    modeStore: ReaderModeStore,
    storage: BundleStorage,
    onModeChange: (ReaderMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * CP3: shared chapter list from BookScreen (null = not loaded yet).
     * Jump uses this session's controller ([PlaybackController.seekToChapter]);
     * the reader ViewModel reloads text on snapshot chapter change.
     */
    chapters: List<ChapterEntry>? = null,
    showChapters: Boolean = false,
    onOpenChapters: () -> Unit = {},
    onDismissChapters: () -> Unit = {}
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val controller = remember(book.id) { PlaybackController(appContext, scope) }
    val playbackState by controller.state.collectAsState()

    var textPaths by remember(book.id) { mutableStateOf<List<String>?>(null) }
    val viewModel = remember(book.id) {
        ReaderViewModel(
            playback = controller.state,
            storage = storage,
            textPathForChapter = { index -> textPaths?.getOrNull(index) },
            onSeekTo = controller::seekTo
        )
    }
    val readerState by viewModel.state.collectAsState()
    // RA10: font size is a persisted setting, read once per session (the
    // session remounts when returning from Settings, so changes apply).
    val fontSize = remember(book.id) { modeStore.fontSize() }
    // IN9: dialogue marking follows the same rule (1.x books have no
    // dialogue speakers, so the default-on setting changes nothing there).
    val dialogueMarking = remember(book.id) { modeStore.dialogueMarking() }
    val batteryStore = remember(appContext) { PrefsBatteryPromptStore.fromContext(appContext) }
    var showBatteryDialog by remember(book.id) { mutableStateOf(false) }
    // RA8: speed + sleep timer (same controls as the Listen player).
    val speedStore = remember(appContext) { PrefsReaderModeStore.fromContext(appContext) }
    var speed by remember(book.id) { mutableStateOf(speedStore.playbackSpeed()) }
    var sleepOption by remember(book.id) { mutableStateOf(SleepOption.Off) }

    LaunchedEffect(book.id) {
        val intent = Intent(appContext, PlaybackService::class.java)
            .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
        appContext.startService(intent)
        controller.connect()
    }
    DisposableEffect(book.id) {
        onDispose {
            controller.release()
            viewModel.clear()
        }
    }
    // Manifest chapter text paths (IO-bound, once per book). A late arrival
    // retries the current chapter: the first load attempt runs before this
    // finishes and reports "no text listed" until then.
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
    LaunchedEffect(textPaths) {
        if (textPaths != null) viewModel.retryCurrentChapter()
    }

    // Keep-screen-on while the reader is visible (never in Listen, which
    // uses PlayerScreen). No toggle UI until RA10; the default is on.
    val keepScreenOnSetting = remember { modeStore.keepScreenOn() }
    val keepOn = shouldKeepScreenOn(mode, keepScreenOnSetting)
    val view = LocalView.current
    DisposableEffect(keepOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = previous }
    }

    fun selectMode(next: ReaderMode) {
        if (next == mode) return
        onModeChange(next)
        if (shouldPauseForMode(next)) {
            controller.pause()
        } else {
            resumePlayback(
                isPlaying = playbackState.isPlaying,
                batteryShown = batteryStore.wasShown(),
                exemptionGranted = BatterySettingsIntents.isExemptionGranted(appContext),
                onShowBatteryDialog = { showBatteryDialog = true },
                play = { controller.playOrRestart(controller.state.value) }
            )
        }
    }

    // Entering the session from Listen in Read mode: pause (the reader never
    // auto-plays on mount; Read + listen keeps whatever was playing).
    LaunchedEffect(Unit) {
        if (shouldPauseForMode(mode)) controller.pause()
    }

    // CP3: chapter list overlays the reader; this session still owns the
    // controller, so jumps keep the shared position rules and the
    // ReaderViewModel reloads text on the snapshot chapter change.
    if (showChapters) {
        ChapterListScreen(
            entries = chapters ?: emptyList(),
            currentIndex = playbackState.chapterIndex,
            onJump = {
                controller.seekToChapter(it)
                onDismissChapters()
            },
            onBack = onDismissChapters,
            modifier = modifier
        )
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            // CP4: transient skip/storage notice, shared with the Listen
            // branch (PlayerContent shows the same state field). Null most
            // ticks, so this skips recomposition on the slow Tab E.
            TransientNotice(
                message = playbackState.skipNotice,
                onDismiss = controller::clearSkipNotice
            )
            ReaderScreen(
                state = readerState,
                onBack = onBack,
                fontSize = fontSize,
                onUserScroll = viewModel::onUserScrolled,
                onBackToNow = viewModel::onBackToNow,
            onSentenceTap = viewModel::onSentenceTap,
            onTopVisibleSentence = viewModel::onTopVisibleSid,
            onConfirmTapJump = viewModel::confirmTapJump,
            onDismissTapJump = viewModel::dismissTapJump,
                onOpenChapters = onOpenChapters,
                dialogueMarking = dialogueMarking,
                modifier = Modifier.weight(1f)
            )
        // RA9: debug-build-only sync section (sid, highlight lag, PSS).
        // Gated like PlayerScreen's overlay: unreachable in release builds.
        if (BuildConfig.DEBUG) {
            var pssMb by remember(book.id) { mutableIntStateOf(-1) }
            LaunchedEffect(book.id) {
                while (true) {
                    pssMb = readTotalPssMb(appContext)
                    delay(3_000L)
                }
            }
            ReaderDebugOverlay(
                sid = readerState.currentSid,
                lagAvgMs = readerState.lagAvgMs,
                lagMaxMs = readerState.lagMaxMs,
                pssMb = pssMb
            )
        }
        ModeBar(
            mode = mode,
            isPlaying = playbackState.isPlaying,
            onMode = ::selectMode,
            onPlayPause = {
                if (!playbackState.isPlaying &&
                    BatteryPromptLogic.shouldShowPrompt(
                        batteryStore.wasShown(),
                        BatterySettingsIntents.isExemptionGranted(appContext)
                    )
                ) {
                    showBatteryDialog = true
                }
                controller.playOrRestart(playbackState)
            },
            speed = speed,
            onSpeed = {
                val next = nextSpeed(speed)
                controller.setSpeed(next)
                speed = next
            },
            sleepRemainingMs = playbackState.sleepRemainingMs,
            onSleep = {
                sleepOption = cycleSleepOption(sleepOption)
                sendSleepOption(appContext, sleepOption)
            }
            )
        }
    }

    if (showBatteryDialog) {
        BatteryPromptDialog(
            onOpenSettings = {
                batteryStore.markShown()
                showBatteryDialog = false
                BatterySettingsIntents.openBatterySettings(appContext)
            },
            onDismiss = {
                batteryStore.markShown()
                showBatteryDialog = false
            }
        )
    }
}

private fun resumePlayback(
    isPlaying: Boolean,
    batteryShown: Boolean,
    exemptionGranted: Boolean,
    onShowBatteryDialog: () -> Unit,
    play: () -> Unit
) {
    if (!isPlaying && BatteryPromptLogic.shouldShowPrompt(batteryShown, exemptionGranted)) {
        onShowBatteryDialog()
    }
    play()
}

/** Mode switcher + Play/Pause + speed + sleep (compact; text stays maximal). */
@Composable
private fun ModeBar(
    mode: ReaderMode,
    isPlaying: Boolean,
    onMode: (ReaderMode) -> Unit,
    onPlayPause: () -> Unit,
    speed: Float,
    onSpeed: () -> Unit,
    sleepRemainingMs: Long?,
    onSleep: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onPlayPause) { Text(if (isPlaying) "Pause" else "Play") }
            SpeedButton(speed = speed, onClick = onSpeed)
            SleepTimerButton(remainingMs = sleepRemainingMs, onClick = onSleep)
        }
        ModeSwitcherRow(mode = mode, onMode = onMode)
    }
}

/** Shared Read/Listen/Read+listen row (reader mode bar and Listen player).
 *
 * IN9: [listenEnabled] false disables the Listen and Read + listen buttons
 * and shows [listenHint] below (unrendered books); the defaults render
 * exactly as before.
 */
@Composable
internal fun ModeSwitcherRow(
    mode: ReaderMode,
    onMode: (ReaderMode) -> Unit,
    modifier: Modifier = Modifier,
    listenEnabled: Boolean = true,
    listenHint: String? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeButton(label = "Read", selected = mode == ReaderMode.Read, onClick = { onMode(ReaderMode.Read) })
            ModeButton(
                label = "Listen",
                selected = mode == ReaderMode.Listen,
                onClick = { onMode(ReaderMode.Listen) },
                enabled = listenEnabled
            )
            ModeButton(
                label = "Read + listen",
                selected = mode == ReaderMode.ReadListen,
                onClick = { onMode(ReaderMode.ReadListen) },
                enabled = listenEnabled
            )
        }
        if (!listenEnabled && listenHint != null) {
            Text(
                text = listenHint,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled) { Text(label) }
    } else {
        TextButton(onClick = onClick, enabled = enabled) { Text(label) }
    }
}
