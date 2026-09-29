package app.auloud.player.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * WP7: `MediaController` wrapper bound to the WP6 [PlaybackService].
 *
 * Exposes [state] (`isPlaying`, `chapterIndex`, `chapterTitle`, `positionMs`,
 * `durationMs`, plus book title/count/connection for the UI). Progress saving
 * stays entirely in the service; this controller never writes progress.
 *
 * Scope ownership: the caller owns [scope] (e.g. `rememberCoroutineScope`);
 * the controller only holds the ticker [Job], cancelled in [release]. Call
 * [connect] once the service has been started with
 * [PlaybackService.EXTRA_BOOK_ID] (the Player screen does that; the service
 * then loads the book paused at its saved spot), and [release] when the
 * screen is disposed (`DisposableEffect`) or cleared. Leaving the screen
 * releases the controller but the service session (playlist + position)
 * survives, so returning reconnects to the same spot.
 *
 * Position ticker: ~500 ms, main-safe reads of the controller. [PlayerStateHolder]
 * suppresses emissions when nothing changed, so idle ticks allocate nothing
 * and recompose nothing on the slow Tab E.
 *
 * Stale-callback safety: `buildAsync()` resolves asynchronously, so [connect]
 * captures a [ConnectGuard] token and the listener re-checks it before and
 * after `get()`; [release] invalidates pending tokens, making a
 * late-resolving connect a no-op (no resurrected controller, no ticker).
 *
 * API 24 safe: no `java.time`, no `java.io.File`, no `Context.mainExecutor`
 * (API 28); callbacks run on the Guava direct executor (the caller connects
 * from the main thread).
 */
class PlaybackController(
    private val appContext: Context,
    private val scope: CoroutineScope
) {

    private val holder = PlayerStateHolder()
    val state: StateFlow<PlaybackState> = holder.state

    private val connectGuard = ConnectGuard()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refresh("isPlaying")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            refresh("transition")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refresh("playbackState")
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refresh("timeline")
        }
    }

    /** Connects to the service session. Idempotent; safe to call once per screen. */
    fun connect() {
        if (controllerFuture != null) return
        val token = connectGuard.beginConnect()
        val appToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, appToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                // Stale (release ran first): no-op. release() already covered
                // this future, so neither assign the controller nor touch the
                // holder nor start the ticker.
                if (!connectGuard.shouldResolve(token)) return@addListener
                val resolved = try {
                    future.get()
                } catch (e: Exception) {
                    Log.w(TAG, "connect failed: ${e.message}")
                    null
                }
                // Re-check after get(): release may have landed while resolving.
                if (!connectGuard.shouldResolve(token)) return@addListener
                if (resolved == null) {
                    holder.onDisconnected()
                    return@addListener
                }
                controller = resolved
                resolved.addListener(listener)
                refresh("connect")
                startTicker()
            },
            MoreExecutors.directExecutor()
        )
    }

    /** Cancels the ticker, detaches the listener and releases the controller future. */
    fun release() {
        // Invalidate first so a late-resolving connect listener is a no-op.
        connectGuard.release()
        ticker?.cancel()
        ticker = null
        val resolved = controller
        if (resolved != null) {
            try {
                resolved.removeListener(listener)
            } catch (e: Exception) {
                Log.w(TAG, "release removeListener: ${e.message}")
            }
        }
        controller = null
        controllerFuture?.let { future ->
            try {
                MediaController.releaseFuture(future)
            } catch (e: Exception) {
                Log.w(TAG, "release future: ${e.message}")
            }
        }
        controllerFuture = null
        holder.onDisconnected()
    }

    fun play() {
        controller?.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /**
     * Seeks within the current chapter, clamped via [clampSeekRequest].
     *
     * Pushes one immediate refresh: the ticker only fires while playing, so
     * without this a paused seek would display stale position until the next
     * play. Single allocation-light update; the holder suppresses no-ops.
     */
    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        c.seekTo(clampSeekRequest(positionMs, chapterDurationOf(c)))
        refresh("seek")
    }

    fun nextChapter() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
    }

    fun previousChapter() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
    }

    private fun startTicker() {
        if (ticker != null) return
        // Caller-owned scope; only this Job is cancelled in release().
        ticker = scope.launch {
            while (isActive) {
                delay(TICKER_MS)
                val c = controller ?: continue
                // Cheap read; holder drops the update when nothing changed,
                // so a paused screen costs no allocations/recompositions.
                if (c.isPlaying) refresh("tick")
            }
        }
    }

    private fun refresh(reason: String) {
        val c = controller ?: return
        holder.onSnapshot(
            ControllerSnapshot(
                isPlaying = c.isPlaying,
                chapterIndex = c.currentMediaItemIndex,
                chapterTitle = c.currentMediaItem?.mediaMetadata?.title?.toString(),
                bookTitle = c.currentMediaItem?.mediaMetadata?.artist?.toString(),
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = chapterDurationOf(c),
                chapterCount = c.mediaItemCount.coerceAtLeast(0),
                isConnected = true
            )
        )
    }

    private fun chapterDurationOf(c: MediaController): Long {
        val d = c.duration
        return if (d == C.TIME_UNSET) 0L else d.coerceAtLeast(0L)
    }

    companion object {
        private const val TICKER_MS = 500L
        private const val TAG = "AuloudPlayer"
    }
}
