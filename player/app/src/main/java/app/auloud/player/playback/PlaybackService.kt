package app.auloud.player.playback

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.auloud.player.BuildConfig
import app.auloud.player.bundle.BundleParser
import app.auloud.player.data.AuloudDatabase
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressRepository
import app.auloud.player.data.RoomLibraryRepository
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.FileBundleStorage
import app.auloud.player.storage.FrameworkSafBackend
import app.auloud.player.storage.RoutingBundleStorage
import app.auloud.player.storage.SafBundleStorage
import app.auloud.player.storage.SafPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * WP6: foreground playback service, the core of Slice 1.
 *
 * One [MediaItem] per manifest chapter (book title as artist, cover as
 * artwork) via `setMediaItems(items, chapterIndex, positionMs)`, starting
 * from the saved [app.auloud.player.data.ProgressEntity]. The notification
 * (play/pause, previous, next) comes from Media3's default provider; there
 * is no custom notification code here.
 *
 * Start it with `startService()` carrying [EXTRA_BOOK_ID] (API 24 has no
 * `startForegroundService`; the service enters the foreground via Media3's
 * notification once playing). An already-loaded book is a no-op; anything
 * unrecoverable (unknown book, bad manifest, unreadable storage) is logged
 * and stops the service without crashing.
 *
 * Progress (chapter index + position ms) is saved via [ProgressRepository]
 * every 5 s while playing, on pause, on chapter change
 * (`onMediaItemTransition`), on task-removed and in `onDestroy`. A corrupt
 * chapter file is skipped with a log message, never a crash. At the end of
 * the final chapter the finished position is saved and playback stops.
 *
 * Depends on WP2 (parser), WP3 ([BundleStorage]) and WP4 (repositories)
 * as-is. Player-screen controls and the `MediaController` wrapper arrive in
 * WP7; speed control and the sleep timer are Slice 3.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    // A Service is a lifecycle owner (like a ViewModel), so owning this
    // scope is the structured-concurrency exception to the "no stored
    // scopes" rule: it is cancelled in onDestroy, completing the ownership.
    // The final progress save is launched with NonCancellable BEFORE that
    // cancel (see onDestroy), which detaches it from the scope's Job so
    // teardown cannot kill it.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    // Hand-built graph (no DI framework in Slice 1), mirroring MainActivity.
    // WP3/WP5 refinement: routing storage so books imported from SAF watch
    // folders (bundle paths are `<treeUri>|<rel>` tokens) read manifests and
    // resolve document-URI audio exactly like file-path books. No playback
    // logic changes: queue building, progress saves and controls are as-is.
    private val storage: BundleStorage by lazy {
        RoutingBundleStorage(FileBundleStorage()) { treeUri ->
            SafBundleStorage(treeUri, FrameworkSafBackend(contentResolver, treeUri))
        }
    }
    private val database: AuloudDatabase by lazy { AuloudDatabase.open(this) }
    private val libraryRepository: LibraryRepository by lazy {
        RoomLibraryRepository(database.bookDao(), storage)
    }
    private val progressRepository: ProgressRepository by lazy {
        RoomProgressRepository(database.progressDao())
    }

    private var currentBookId: String? = null
    private var loadGeneration = 0
    private var chapterDurations: List<Long> = emptyList()
    private var lastSaveUptimeMs: Long = 0L

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveProgressNow("chapter-change")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // RA7: a seek (tap-to-jump, slider, Read-mode settle) persists
            // immediately — a paused seek has no other save trigger.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                saveProgressNow("seek")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val player = session?.player ?: return
            // Pause (not end-of-playlist): persist the spot immediately.
            if (!isPlaying && player.playbackState != Player.STATE_ENDED) {
                saveProgressNow("pause")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) saveFinishedNow()
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = session?.player ?: return
            val index = player.currentMediaItemIndex
            Log.w(TAG, "chapter $index unreadable (${error.errorCodeName}); skipping")
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                player.stop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        // Stop at the end of the book: never loop or shuffle chapters.
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.shuffleModeEnabled = false
        // Notification through Media3's default provider (play/pause,
        // previous, next derived from the playlist commands). No custom
        // notification code.
        // v3: notification-channel setup for API 26+ goes here; API 24 has
        // no channels and the default provider handles both.
        player.addListener(listener)
        session = MediaSession.Builder(this, player).build()
        lastSaveUptimeMs = SystemClock.uptimeMillis()
        // 5 s save cadence while playing. The loop itself allocates nothing
        // per tick; only an actual save launches a write coroutine.
        ticker = serviceScope.launch {
            while (isActive) {
                delay(ProgressSavePolicy.SAVE_INTERVAL_MS)
                val now = SystemClock.uptimeMillis()
                val current = session?.player ?: continue
                if (ProgressSavePolicy.shouldSavePeriodic(lastSaveUptimeMs, now, current.isPlaying)) {
                    saveProgressNow("periodic")
                }
            }
        }
    }

    override fun onGetSession(info: MediaSession.ControllerInfo) = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // WP9: log EVERY start at entry -- a system restart after a
        // Samsung kill arrives with a null intent (or no book extra), which
        // is exactly the case this logging exists to diagnose. Branching
        // below is unchanged.
        Log.i(
            TAG,
            "onStartCommand action=${intent?.action} hasBookExtra=${intent?.hasExtra(EXTRA_BOOK_ID)} " +
                "flags=$flags startId=$startId"
        )
        val bookId = intent?.getStringExtra(EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
        if (bookId != null) {
            if (bookId != currentBookId) {
                Log.i(TAG, "onStartCommand loading bookId=$bookId")
                loadBook(bookId)
            } else {
                Log.i(TAG, "onStartCommand already loaded bookId=$bookId")
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved")
        saveProgressNow("task-removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        // 1. Capture synchronously while the player is still alive.
        val player = session?.player
        val finalPoint = ProgressSavePolicy.pointOrNull(
            currentBookId,
            player?.currentMediaItemIndex ?: C.INDEX_UNSET,
            (player?.currentPosition ?: 0L).coerceAtLeast(0L)
        )
        // 2. Stop the ticker so no periodic save can interleave with teardown.
        ticker?.cancel()
        ticker = null
        if (finalPoint != null) {
            // 3. Launch BEFORE the scope cancel with NonCancellable: this
            // detaches the write from the scope's Job, so cancel() below
            // cannot kill it. Residual window: if the process itself is
            // killed mid-write the save is lost; the 5 s cadence bounds
            // that loss. Inherent to process kill, accepted for Slice 1.
            lastSaveUptimeMs = SystemClock.uptimeMillis()
            launchSave(finalPoint, "destroy", NonCancellable)
        }
        // 4. Complete scope ownership; then release per the Slice 1 plan.
        serviceScope.cancel()
        session?.player?.release()
        session?.release()
        session = null
        super.onDestroy()
    }

    /** Loads [bookId] from storage and prepares it paused at the saved spot. */
    private fun loadBook(bookId: String) {
        if (session?.player == null) return
        val generation = ++loadGeneration
        currentBookId = bookId
        serviceScope.launch {
            val prepared = try {
                withContext(Dispatchers.IO) { prepareBook(bookId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loadBook: $bookId failed: ${e.message}")
                null
            }
            // A newer request superseded this one; leave the newer load alone.
            if (generation != loadGeneration) return@launch
            val player = session?.player ?: return@launch
            if (prepared == null) {
                currentBookId = null
                stopSelf()
                return@launch
            }
            chapterDurations = prepared.durations
            player.setMediaItems(
                prepared.mediaItems,
                prepared.start.chapterIndex,
                prepared.start.positionMs
            )
            player.prepare()
            // Start paused at the saved spot; WP7 takes over controls.
            player.playWhenReady = false
            lastSaveUptimeMs = SystemClock.uptimeMillis()
            Log.i(
                TAG,
                "loadBook: $bookId chapters=${prepared.mediaItems.size} " +
                    "start=${prepared.start.chapterIndex}@${prepared.start.positionMs}"
            )
        }
    }

    /**
     * Blocking half of [loadBook]: book lookup, manifest read/parse,
     * progress read and MediaItem mapping. Runs on IO; touches no player
     * state. Returns null only via thrown exceptions (handled by the
     * caller); an empty playlist is a failure too.
     */
    private suspend fun prepareBook(bookId: String): PreparedBook {
        val book = libraryRepository.books().first().firstOrNull { it.id == bookId }
            ?: throw IllegalStateException("unknown bookId=$bookId")
        val manifestText = storage.readText(joinPath(book.bundlePath, MANIFEST_FILE))
        val manifest = BundleParser.parseText(manifestText).getOrThrow()
        val saved = progressRepository.load(bookId).getOrThrow()
        val items = PlaybackQueue.build(
            manifest = manifest,
            bundleDir = book.bundlePath,
            audioUriOf = { dir, rel -> storage.audioUri(dir, rel).toString() },
            // EXPLICIT cover policy for SAF books: their `coverPath` is an
            // opaque `<tree>|<rel>` token no art loader can resolve, so
            // artwork is intentionally null (system placeholder) rather than
            // a bogus file:// URI. Documented limitation, not a silent
            // failure — follow-up: resolve SAF covers to document URIs
            // (needs a `BundleStorage.coverUri` seam plus storing the
            // resolved URI at import time; deliberately out of this slice).
            artworkUri = book.coverPath
                ?.takeIf { !SafPaths.isSafPath(it) }
                ?.let { coverToUri(it).toString() }
        )
        if (items.isEmpty()) throw IllegalStateException("no chapters listed for $bookId")
        val mediaItems = items.map { item ->
            MediaItem.Builder()
                .setUri(Uri.parse(item.audioUri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setArtworkUri(item.artworkUri?.let(Uri::parse))
                        .build()
                )
                .build()
        }
        val durations = items.map { it.durationMs }
        return PreparedBook(mediaItems, PlaybackQueue.startFrom(saved, items.size, durations), durations)
    }

    /** Persists the current spot; skipped when nothing playable is loaded. */
    private fun saveProgressNow(reason: String) {
        val player = session?.player ?: return
        val point = ProgressSavePolicy.pointOrNull(
            currentBookId,
            player.currentMediaItemIndex,
            player.currentPosition.coerceAtLeast(0L)
        ) ?: return
        lastSaveUptimeMs = SystemClock.uptimeMillis()
        launchSave(point, reason)
    }

    /**
     * Writes [point] in the service scope. [context] defaults to inheriting
     * scope cancellation; callers that must survive teardown (onDestroy)
     * pass [NonCancellable].
     *
     * WP9: the single choke point for ALL save initiations (periodic, pause,
     * chapter-change, task-removed, destroy, end-of-book), so every save
     * point logs in one consistent format under [SAVE_TAG] and records the
     * wall-clock time for the debug overlay. The tracker write is gated by
     * `BuildConfig.DEBUG`, so release behavior is unchanged.
     */
    private fun launchSave(
        point: ProgressSavePolicy.SavePoint,
        reason: String,
        context: CoroutineContext = EmptyCoroutineContext,
        finished: Boolean = false
    ): Job {
        if (BuildConfig.DEBUG) DebugSaveTracker.recordSave(System.currentTimeMillis())
        return serviceScope.launch(context) {
            val result = progressRepository.save(point.bookId, point.chapterIndex, point.positionMs)
            if (result.isSuccess) {
                Log.i(
                    SAVE_TAG,
                    "progress saved reason=$reason book=${point.bookId} " +
                        "chapter=${point.chapterIndex} pos=${point.positionMs}" +
                        if (finished) " finished" else ""
                )
            } else {
                Log.w(SAVE_TAG, "progress save failed reason=$reason: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    /** At the end of the final chapter: mark finished (last chapter, full duration). */
    private fun saveFinishedNow() {
        val player = session?.player ?: return
        val bookId = currentBookId ?: return
        val count = player.mediaItemCount
        if (!ProgressSavePolicy.isEndOfBook(player.currentMediaItemIndex, count, playbackEnded = true)) {
            return
        }
        val point = ProgressSavePolicy.finishedPoint(
            bookId, count, chapterDurations.lastOrNull() ?: 0L
        )
        lastSaveUptimeMs = SystemClock.uptimeMillis()
        launchSave(point, "end-of-book", finished = true)
    }

    /**
     * `file://` URI for an absolute cover path, built from the string so
     * `java.io.File` never leaves the storage layer ([Uri.Builder] encodes
     * the path).
     */
    private fun coverToUri(coverPath: String): Uri =
        Uri.Builder().scheme("file").path(coverPath).build()

    private fun joinPath(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    private data class PreparedBook(
        val mediaItems: List<MediaItem>,
        val start: StartPosition,
        val durations: List<Long>
    )

    companion object {
        /** `startService()` extra: manifest `id` of the book to load. */
        const val EXTRA_BOOK_ID = "app.auloud.player.extra.BOOK_ID"

        private const val MANIFEST_FILE = "manifest.json"

        private const val TAG = "AuloudPlayback"

        /**
         * WP9: progress-save log tag (log-tag convention: `Auloud*`, <= 23
         * chars). Lifecycle (create/destroy/task-removed/load) stays on
         * [TAG] so service kills filter separately from save cadence.
         * Filter both with:
         * `adb logcat -s AuloudPlayback:V AuloudProgress:V`
         */
        private const val SAVE_TAG = "AuloudProgress"
    }
}
