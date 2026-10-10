package app.auloud.player.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import app.auloud.player.tts.PauseUtterance
import app.auloud.player.tts.SpeakUtterance
import app.auloud.player.tts.StreamCore
import app.auloud.player.tts.StreamSentence
import app.auloud.player.tts.StreamTtsDriver
import app.auloud.player.tts.StreamTtsListener
import app.auloud.player.tts.TtsRole
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ST4: live-stream player inside [PlaybackService]: one chapter at a
 * time, spoken by the system TTS engine, nothing saved.
 *
 * A [SimpleBasePlayer] subclass (confirmed in the pinned media3-common
 * 1.5.1 artifact, `docs/streaming.md` section 4), so the existing
 * `MediaSession`, notification, lock-screen and Bluetooth controls keep
 * working: play/pause/stop, chapter next/previous (via [StreamNavigator],
 * the service switches chapters), speed (re-queues at the new rate) and
 * volume (applies to subsequently spoken utterances, which is also the
 * sleep-timer fade path). Position is the 0-based sentence index and the
 * duration is unset: highlight and saves use [currentSid], never player
 * milliseconds (sentence-level design, D-133).
 *
 * Owns the [StreamCore] plus the driver feed: driver callbacks drive the
 * core synchronously (speak queuing never blocks, as in the ST0 spike)
 * and refill from it. Attempt ids make stale callbacks harmless. The
 * driver belongs to this player: [release] shuts it down.
 *
 * Device-only (framework + Media3); needs the tablet (ST7). Wake lock
 * rides the existing `WAKE_LOCK` permission; audio focus uses the
 * pre-26 stream-type form; any focus loss pauses (user resumes).
 *
 * Handler futures use media3's own Guava (`Futures.immediateFuture`,
 * D-135): the class ships with media3 at runtime, so this adds no
 * dependency and pins none.
 */
class StreamPlayer(
    appContext: Context,
    private val driver: StreamTtsDriver,
    private val navigator: StreamNavigator,
    private val callbacks: StreamPlayerCallbacks,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    /** One streamable chapter with everything the feed needs. */
    data class ChapterInput(
        val sentences: List<StreamSentence>,
        val startSid: Int,
        val voiceNames: Map<TtsRole, String>,
        val speeds: Map<TtsRole, Float>,
        val volumes: Map<TtsRole, Float>,
        val pausesAfterSid: Map<Int, Long>,
        val title: String,
        val bookTitle: String,
    )

    private val appContext = appContext.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wakeLock: PowerManager.WakeLock = (
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        ).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Auloud:stream").apply {
        setReferenceCounted(false)
    }

    private var core: StreamCore? = null
    private var input: ChapterInput? = null
    private var playing = false
    private var ended = false
    private var volume = 1.0f
    private var defaultRate = 1.0f
    private var positionIndex = 0L
    private var released = false

    private val _currentSid = MutableStateFlow<Int?>(null)
    /** Follows the voice (ST5 highlight, service saves); null when idle. */
    val currentSid: StateFlow<Int?> = _currentSid

    /** Sentences in the loaded chapter (0 when nothing loaded). */
    val chapterSize: Int get() = input?.sentences?.size ?: 0

    private val focusListener = AudioManager.OnAudioFocusChangeListener {
        if (it != AudioManager.AUDIOFOCUS_GAIN) pauseForInterruption("focus-loss")
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pauseForInterruption("noisy-audio")
        }
    }
    private var noisyRegistered = false

    init {
        driver.setListener(object : StreamTtsListener {
            override fun onStart(utteranceId: String) {
                core?.onStart(utteranceId)
                core?.currentSid?.let {
                    _currentSid.value = it
                    positionIndex = (input?.sentences?.indexOfFirst { s -> s.sid == it }
                        ?: -1).coerceAtLeast(0).toLong()
                }
            }

            override fun onDone(utteranceId: String) {
                val refill = core?.onDone(utteranceId).orEmpty()
                feed(refill)
                if (core?.status == StreamCore.Status.Done) onChapterDone()
            }

            override fun onError(utteranceId: String) {
                val refill = core?.onError(utteranceId).orEmpty()
                feed(refill)
                if (core?.status == StreamCore.Status.Failed) onStreamFailed()
            }
        })
    }

    // -- loading ----------------------------------------------------------

    /**
     * Load a chapter paused at [ChapterInput.startSid] (unknown sids
     * refuse: returns false and loads nothing).
     */
    fun load(chapter: ChapterInput): Boolean {
        if (released) return false
        driver.stop()
        val fresh = StreamCore(chapter.sentences, chapter.volumes, chapter.pausesAfterSid)
        if (fresh.start(chapter.startSid).isEmpty() && chapter.sentences.isNotEmpty()) {
            return false
        }
        input = chapter
        core = fresh
        playing = false
        ended = false
        positionIndex = 0L
        _currentSid.value = null
        registerNoisy()
        invalidateState()
        return true
    }

    // -- Player handlers --------------------------------------------------

    override fun getState(): State {
        val chapter = input
        val item = MediaItemData.Builder(ITEM_UID).setMediaItem(
            MediaItem.Builder()
                .setMediaId(ITEM_UID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter?.title ?: "")
                        .setArtist(chapter?.bookTitle ?: "")
                        .build()
                )
                .build()
        ).build()
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .add(COMMAND_PLAY_PAUSE)
                    .add(COMMAND_STOP)
                    .add(COMMAND_SEEK_TO_NEXT)
                    .add(COMMAND_SEEK_TO_PREVIOUS)
                    .add(COMMAND_SET_SPEED_AND_PITCH)
                    .add(COMMAND_SET_VOLUME)
                    .add(COMMAND_GET_VOLUME)
                    .add(COMMAND_GET_TIMELINE)
                    .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
                    .build()
            )
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    chapter == null -> STATE_IDLE
                    ended -> STATE_ENDED
                    else -> STATE_READY
                }
            )
            .setPlaybackParameters(PlaybackParameters(defaultRate, 1.0f))
            .setVolume(volume)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                object : PositionSupplier {
                    override fun get(): Long = positionIndex
                }
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (released) return Futures.immediateFuture(null)
        if (playWhenReady) startSpeaking() else pauseSpeaking()
        return Futures.immediateFuture(null)
    }

    override fun handleStop(): ListenableFuture<*> {
        if (!released) pauseSpeaking()
        return Futures.immediateFuture(null)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (released) return Futures.immediateFuture(null)
        when (seekCommand) {
            COMMAND_SEEK_TO_NEXT -> navigator.onNextChapter()
            COMMAND_SEEK_TO_PREVIOUS -> navigator.onPreviousChapter()
            else -> {
                val chapter = input
                val target = chapter?.sentences
                    ?.getOrNull(positionMs.toInt().coerceAtLeast(0))
                if (chapter != null && target != null) {
                    driver.stop()
                    feed(core?.seek(target.sid).orEmpty())
                    if (!playing) startSpeaking()
                }
            }
        }
        return Futures.immediateFuture(null)
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        if (released) return Futures.immediateFuture(null)
        defaultRate = playbackParameters.speed
        // Speed lives in the driver feed, so re-queue at the new rate
        // (same rule as the core's speed change).
        if (playing) {
            driver.stop()
            feed(core?.requeue().orEmpty())
        }
        invalidateState()
        return Futures.immediateFuture(null)
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        if (!released) {
            this.volume = volume.coerceIn(0.0f, 1.0f)
            invalidateState()
        }
        return Futures.immediateFuture(null)
    }

    override fun handleRelease(): ListenableFuture<*> {
        cleanup()
        return Futures.immediateFuture(null)
    }

    // -- feed -------------------------------------------------------------

    private fun startSpeaking() {
        val chapter = input
        val stream = core
        if (chapter == null || stream == null) return
        if (ended) {
            // Play after the chapter end restarts it from the top.
            load(chapter.copy(startSid = chapter.sentences.firstOrNull()?.sid ?: 0))
            return startSpeaking()
        }
        requestFocus()
        wakeLock.acquire(10 * 60 * 1000L)
        val first = when (stream.status) {
            StreamCore.Status.Paused -> stream.resume()
            StreamCore.Status.Playing -> stream.requeue()
            else -> emptyList()
        }
        playing = true
        ended = false
        feed(first)
        invalidateState()
    }

    private fun pauseSpeaking() {
        playing = false
        driver.stop()
        core?.pause()
        abandonFocus()
        if (wakeLock.isHeld) wakeLock.release()
        invalidateState()
    }

    private fun pauseForInterruption(reason: String) {
        if (!playing) return
        Log.i(TAG, "pausing ($reason)")
        pauseSpeaking()
    }

    private fun feed(plan: List<app.auloud.player.tts.StreamUtterance>) {
        val chapter = input ?: return
        plan.forEach { utterance ->
            when (utterance) {
                is SpeakUtterance -> {
                    val ok = driver.speak(
                        utterance.text,
                        voiceFor(utterance.role),
                        speedFor(utterance.role),
                        (utterance.volume * volume).coerceIn(0.0f, 1.0f),
                        utterance.id,
                    )
                    if (!ok) feed(core?.onError(utterance.id).orEmpty())
                }
                is PauseUtterance -> {
                    if (!driver.playSilence(utterance.durationMs, utterance.id)) {
                        feed(core?.onError(utterance.id).orEmpty())
                    }
                }
            }
        }
    }

    private fun voiceFor(role: TtsRole): String =
        input?.voiceNames?.get(role) ?: ""

    private fun speedFor(role: TtsRole): Float =
        (input?.speeds?.get(role) ?: 1.0f) * defaultRate

    private fun onChapterDone() {
        playing = false
        ended = true
        abandonFocus()
        if (wakeLock.isHeld) wakeLock.release()
        invalidateState()
        callbacks.onChapterDone()
    }

    private fun onStreamFailed() {
        playing = false
        abandonFocus()
        if (wakeLock.isHeld) wakeLock.release()
        invalidateState()
        callbacks.onStreamFailed()
    }

    // -- platform glue ----------------------------------------------------

    private fun requestFocus() {
        @Suppress("DEPRECATION")
        audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun abandonFocus() {
        @Suppress("DEPRECATION")
        audioManager.abandonAudioFocus(focusListener)
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        try {
            appContext.registerReceiver(
                noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            noisyRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun cleanup() {
        released = true
        playing = false
        try {
            driver.stop()
        } catch (_: Exception) {
        }
        try {
            driver.shutdown()
        } catch (_: Exception) {
        }
        abandonFocus()
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) {
        }
        if (noisyRegistered) {
            try {
                appContext.unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {
            }
            noisyRegistered = false
        }
    }

    companion object {
        private const val TAG = "AuloudStream"
        private const val ITEM_UID = "stream-chapter"
    }
}

/**
 * ST4: chapter stepping for the stream player (the service switches
 * players at the boundary; the player only asks).
 */
interface StreamNavigator {
    fun onNextChapter()
    fun onPreviousChapter()
}

/** ST4: service-side stream outcomes (advance or message, then save). */
interface StreamPlayerCallbacks {
    fun onChapterDone()
    fun onStreamFailed()
}
