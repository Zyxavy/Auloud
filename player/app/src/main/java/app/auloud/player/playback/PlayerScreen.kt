package app.auloud.player.playback

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.auloud.player.BuildConfig
import app.auloud.player.R
import app.auloud.player.battery.BatteryPromptDialog
import app.auloud.player.battery.BatteryPromptLogic
import app.auloud.player.battery.BatterySettingsIntents
import app.auloud.player.battery.PrefsBatteryPromptStore
import app.auloud.player.library.BookUiModel
import coil.compose.AsyncImage

/**
 * WP7: player screen for one book.
 *
 * Owns its [PlaybackController]: on open it starts the WP6 [PlaybackService]
 * with [PlaybackService.EXTRA_BOOK_ID] (the service reads the WP4
 * `ProgressEntity` and prepares the book paused at the saved spot -- the
 * screen reuses that input exactly and never computes or writes progress),
 * then connects the controller. [DisposableEffect] releases the controller;
 * the service session survives, so leaving and returning reconnects to the
 * same spot. System back returns to the library (no navigation library).
 *
 * A finished book reopens staying at the end, paused (the service prepares
 * the saved finished spot as-is); Play restarts it from chapter 1 (D-028,
 * decided in RA7).
 *
 * Narrow recompositions for the slow Tab E: [PlayerContent] passes only
 * primitive slices to children, so the 500 ms position ticker recomposes just
 * the seek bar + position text; cover and titles skip while unchanged.
 *
 * Controls stay disabled until the controller connects: pre-connect taps
 * would otherwise vanish silently. Manual verification of play/seek/chapter
 * buttons assumes a connected session (the "Connecting…" row is gone).
 */
@Composable
fun PlayerScreen(
    book: BookUiModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val scope = rememberCoroutineScope()
    val controller = remember(book.id) { PlaybackController(appContext, scope) }
    val state by controller.state.collectAsState()

    // WP8: first-playback battery prompt. Fires once, on the tap that starts
    // playback, and only when the exemption is not already granted (the
    // settings screen reopens the same dialog on demand). Either dialog
    // button marks the store so the auto-dialog never repeats. Playback still
    // starts underneath the dialog.
    //
    // DEVICE-TEST (user on the Tab E): confirm the dialog appears on the
    // first Play tap only, and that "Open settings" lands on a real Samsung
    // battery screen -- dialog-once behavior and Samsung menus cannot be
    // verified without the device.
    val batteryStore = remember(appContext) { PrefsBatteryPromptStore.fromContext(appContext) }
    var showBatteryDialog by remember(book.id) { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(book.id) {
        val intent = Intent(appContext, PlaybackService::class.java)
            .putExtra(PlaybackService.EXTRA_BOOK_ID, book.id)
        appContext.startService(intent)
        controller.connect()
    }
    DisposableEffect(book.id) {
        onDispose { controller.release() }
    }

    PlayerContent(
        book = book,
        state = state,
        onPlayPause = {
            if (!state.isPlaying &&
                BatteryPromptLogic.shouldShowPrompt(
                    batteryStore.wasShown(),
                    BatterySettingsIntents.isExemptionGranted(appContext)
                )
            ) {
                showBatteryDialog = true
            }
            controller.playOrRestart(state)
        },
        onSeek = controller::seekTo,
        onNext = controller::nextChapter,
        onPrevious = controller::previousChapter,
        onBack = onBack,
        modifier = modifier
    )

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

@Composable
private fun PlayerContent(
    book: BookUiModel,
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(8.dp))
        PlayerCover(coverPath = book.coverPath, title = book.title)
        Spacer(Modifier.height(16.dp))
        PlayerBookTitle(title = book.title, author = book.author)
        Spacer(Modifier.height(8.dp))
        PlayerChapterTitle(
            chapterTitle = state.chapterTitle,
            chapterIndex = state.chapterIndex,
            chapterCount = state.chapterCount,
            isConnected = state.isConnected
        )
        Spacer(Modifier.height(16.dp))
        PlayerSeekBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeek = onSeek
        )
        Spacer(Modifier.height(4.dp))
        PlayerPositionText(positionMs = state.positionMs, durationMs = state.durationMs)
        Spacer(Modifier.height(16.dp))
        PlayerControls(
            isPlaying = state.isPlaying,
            controlsEnabled = state.isConnected,
            canPrevious = state.chapterCount > 0 && state.chapterIndex > 0,
            canNext = state.chapterCount > 0 && state.chapterIndex < state.chapterCount - 1,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious
        )
        if (!state.isConnected) {
            Spacer(Modifier.height(16.dp))
            Text("Connecting…", style = MaterialTheme.typography.bodySmall)
        }
        // WP9: debug-build-only overlay (chapter, positionMs, player state,
        // service-recorded last save time). Gated by the BuildConfig.DEBUG
        // constant so release builds never execute this path. Primitive
        // slices only, so it recomposes independently of the rest.
        //
        // DEVICE-TEST (user on the Tab E): overlay rendering and
        // overlay-matches-audio cannot be verified without the device.
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(8.dp))
            DebugOverlay(
                chapterIndex = state.chapterIndex,
                chapterCount = state.chapterCount,
                positionMs = state.positionMs,
                isPlaying = state.isPlaying,
                isConnected = state.isConnected,
                lastSaveWallMs = state.lastSaveWallMs
            )
        }
    }
}

@Composable
private fun PlayerCover(coverPath: String?, title: String, modifier: Modifier = Modifier) {
    val coverModifier = modifier.size(200.dp)
    val placeholder = painterResource(R.drawable.ic_book_placeholder)
    if (coverPath != null) {
        AsyncImage(
            model = coverPath,
            contentDescription = "Cover of $title",
            modifier = coverModifier,
            placeholder = placeholder,
            error = placeholder
        )
    } else {
        Image(
            painter = placeholder,
            contentDescription = "No cover for $title",
            modifier = coverModifier
        )
    }
}

@Composable
private fun PlayerBookTitle(title: String, author: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = author ?: "Unknown author",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PlayerChapterTitle(
    chapterTitle: String,
    chapterIndex: Int,
    chapterCount: Int,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val text = when {
        !isConnected || chapterCount == 0 -> "Loading chapters…"
        chapterTitle.isNotBlank() -> chapterTitle
        else -> "Chapter ${chapterIndex + 1} of $chapterCount"
    }
    Text(text = text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

@Composable
private fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local drag state: while dragging, the ticker must not fight the thumb.
    // Seek fires once on release (cheap on the Tab E, kind to CBR MP3 seeks).
    var dragMs by remember { mutableStateOf<Long?>(null) }
    val duration = durationMs.coerceAtLeast(0L)
    val displayMs = dragMs ?: positionMs.coerceIn(0L, duration)
    val rangeMax = if (duration > 0L) duration.toFloat() else 1f
    Slider(
        value = displayMs.toFloat().coerceIn(0f, rangeMax),
        onValueChange = { dragMs = it.toLong().coerceIn(0L, duration) },
        valueRange = 0f..rangeMax,
        onValueChangeFinished = {
            dragMs?.let { onSeek(it) }
            dragMs = null
        },
        enabled = duration > 0L,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun PlayerPositionText(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Text(
        text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
    )
}

@Composable
private fun PlayerControls(
    isPlaying: Boolean,
    controlsEnabled: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onPrevious, enabled = controlsEnabled && canPrevious) { Text("Prev") }
        Button(onClick = onPlayPause, enabled = controlsEnabled) {
            Text(if (isPlaying) "Pause" else "Play")
        }
        Button(onClick = onNext, enabled = controlsEnabled && canNext) { Text("Next") }
    }
}

/** mm:ss, API 24 safe (no java.time). */
private fun formatMs(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1_000L).coerceAtMost(599_999L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${if (seconds < 10L) "0$seconds" else "$seconds"}"
}
