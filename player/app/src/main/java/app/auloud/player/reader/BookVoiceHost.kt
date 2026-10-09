package app.auloud.player.reader

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.auloud.player.BuildConfig
import app.auloud.player.data.ProgressRepository
import app.auloud.player.library.BookUiModel
import app.auloud.player.render.DebugRenderEngines
import app.auloud.player.render.JavaFileRenderIo
import app.auloud.player.render.RenderService
import app.auloud.player.render.rerenderModeName
import app.auloud.player.storage.BundleStorage
import app.auloud.player.tts.AndroidSystemTtsDriver
import app.auloud.player.tts.AudioTrackAudioPlayer
import app.auloud.player.tts.BookVoiceScreen
import app.auloud.player.tts.BookVoiceViewModel
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.SherpaPiperEngine
import app.auloud.player.tts.SystemTtsAdapter
import app.auloud.player.tts.bookVoiceVersionOf
import app.auloud.player.tts.scanAppModelPacks
import java.io.File
import kotlinx.coroutines.delay

/**
 * VS4: book voice host (moved to its own file in VS5 so complete books
 * share it). Builds the TTS graph by hand like the Settings voice-lab
 * host (System adapter, sherpa Piper when complete packs are present,
 * beep in debug builds) and tears it down on dispose. The book
 * view-model reads per-book voices from the manifest with globals as
 * fallback; the re-render start rides the VS3 intent, pause the plain
 * render action. Renders and voice saves refresh the caller through
 * [onBookChanged].
 */
@Composable
internal fun BookVoiceHost(
    book: BookUiModel,
    storage: BundleStorage,
    progress: ProgressRepository,
    onBack: () -> Unit,
    onBookChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val sherpaHolder = remember(book.id) { arrayOfNulls<SherpaPiperEngine>(1) }
    val viewModel = remember(book.id) {
        val scratch = File(appContext.cacheDir, "book-voice-audition")
        val driver = AndroidSystemTtsDriver(appContext)
        val adapter = SystemTtsAdapter(driver, scratch)
        val packs = scanAppModelPacks(appContext)
        val sherpa = SherpaPiperEngine(packs).takeIf { it.voices().isNotEmpty() }
        sherpaHolder[0] = sherpa
        val registry = EngineRegistry(
            listOfNotNull(
                adapter,
                sherpa,
                DebugRenderEngines.beepEngineIfDebug(BuildConfig.DEBUG)
            )
        )
        BookVoiceViewModel(
            bookId = book.id,
            bundleDir = book.bundleDir,
            storage = storage,
            progress = progress,
            globals = PrefsTtsStore.fromContext(appContext),
            registry = registry,
            audio = AudioTrackAudioPlayer(),
            versionOf = bookVoiceVersionOf(appContext),
            fileIo = JavaFileRenderIo(),
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
            onPauseRender = { sendRenderAction(appContext, RenderService.ACTION_PAUSE) },
            onBookChanged = onBookChanged,
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
            viewModel.clear()
            sherpaHolder[0]?.release()
            sherpaHolder[0] = null
        }
    }
    val state by viewModel.state.collectAsState()
    // Re-poll once the async TTS init lands (cheap: registry plus prefs read).
    LaunchedEffect(book.id) {
        delay(2_000L)
        viewModel.refresh()
    }
    BookVoiceScreen(
        state = state,
        onBack = onBack,
        onSelectEngine = viewModel::selectEngine,
        onConfirmEngineSwitch = viewModel::confirmEngineSwitch,
        onCancelEngineSwitch = viewModel::cancelEngineSwitch,
        onSelectVoice = viewModel::selectVoice,
        onClearDialogueVoice = viewModel::clearDialogueVoice,
        onSetSpeed = viewModel::setSpeed,
        onSetAlternateVoice = viewModel::setAlternateVoice,
        onPreview = viewModel::preview,
        onStop = viewModel::stop,
        onRequestApply = viewModel::requestApply,
        onConfirmApply = viewModel::confirmApply,
        onDismissImpact = viewModel::dismissImpact,
        onPromoteToDefaults = viewModel::promoteToDefaults,
        onDismissError = viewModel::dismissError,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier
    )
}

/** VS4: plain `startService` render action (pause, resume, cancel). */
internal fun sendRenderAction(appContext: Context, action: String) {
    try {
        appContext.startService(Intent(appContext, RenderService::class.java).setAction(action))
    } catch (e: Exception) {
        Log.w(TAG, "render $action failed: ${e.message}")
    }
}

private const val TAG = "AuloudRender"
