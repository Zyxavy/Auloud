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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.auloud.player.BuildConfig
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.AuloudDatabase
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressRepository
import app.auloud.player.data.RoomLibraryRepository
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.reader.isDialogueSpeaker
import app.auloud.player.render.AssemblyMath
import app.auloud.player.render.ChapterMediaMap
import app.auloud.player.render.END_OF_RENDERED_MESSAGE
import app.auloud.player.render.JavaFileRenderIo
import app.auloud.player.render.RenderJobState
import app.auloud.player.render.RenderService
import app.auloud.player.render.RenderServicePolicy
import app.auloud.player.render.RenderStateStore
import app.auloud.player.render.RerenderFirstRender
import app.auloud.player.render.buildChapterMediaMap
import app.auloud.player.render.isEndOfRenderedPortion
import app.auloud.player.tts.AndroidStreamTtsDriver
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.StreamLeveling
import app.auloud.player.tts.StreamTtsDriver
import app.auloud.player.tts.TtsRole
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
 * One [MediaItem] per rendered manifest chapter (book title as artist, cover as
 * artwork) via `setMediaItems(items, chapterIndex, positionMs)`, starting
 * from the saved [app.auloud.player.data.ProgressEntity]. IN9: unrendered
 * (`none`) books are refused shaped in `prepareBook`
 * ([PlaybackQueue.gateFor]) and never reach `setMediaItems`. RN8:
 * partially rendered (`partial`) books load their rendered subset through
 * the explicit chapter-to-media mapping (saves convert back to manifest
 * chapter positions; the end of the rendered portion surfaces a stop
 * message instead of a finished save). The notification
 * (play/pause, previous, next) comes from Media3's default provider; there
 * is no custom notification code here.
 *
 * Start it with `startService()` carrying [PlaybackIntents.EXTRA_BOOK_ID] (API 24 has no
 * `startForegroundService`; the service enters the foreground via Media3's
 * notification once playing). An already-loaded book is a no-op; anything
 * unrecoverable (unknown book, bad manifest, unreadable storage) is logged
 * and stops the service without crashing.
 *
 * Progress (chapter index + position ms) is saved via [ProgressRepository]
 * every 5 s while playing, on pause, on chapter change
 * (`onMediaItemTransition`), on task-removed and in `onDestroy`. A corrupt
 * chapter file is skipped with a transient message (CP4, via
 * [SkipNoticeMonitor]); repeated back-to-back failures pause with a
 * "storage unavailable" message instead of looping the playlist. At the
 * end of the final chapter the finished position is saved and playback
 * stops.
 *
 * Depends on WP2 (parser), WP3 ([BundleStorage]) and WP4 (repositories)
 * as-is. Player-screen controls and the `MediaController` wrapper arrive in
 * WP7; speed control and the sleep timer are Slice 3.
 *
 * ST4/ST5: the service hosts the live-stream path through the stable
 * [StreamVoice] facade, whose implementation drives media3's unstable
 * player surface — so the whole service carries media3's `UnstableApi`
 * marker (one annotation; per-function markers would propagate to every
 * caller anyway). Nothing else changes about the rendered path.
 */
@UnstableApi
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
    /**
     * RN8: chapter-to-media mapping for the loaded book (D-099 hook).
     * Identity for fully rendered books (saves pass through untouched);
     * sparse for partial books (media indexes convert to manifest chapter
     * positions on save, and the end of the rendered portion surfaces the
     * stop message instead of a finished save). Null while nothing is loaded.
     */
    private var currentMap: ChapterMediaMap? = null
    private var loadGeneration = 0
    private var chapterDurations: List<Long> = emptyList()
    private var lastSaveUptimeMs: Long = 0L
    /**
     * FP2: back-to-back progress-write failures with no intervening success
     * (see launchSave). At the [ProgressSavePolicy] threshold the service
     * publishes [SAVE_FAILED_MESSAGE] through the notice channel and the
     * streak resets, so a stuck store re-notifies instead of spamming.
     */
    private var consecutiveSaveFailures = 0
    /**
     * ST4: the rendered player, kept across session switches (the session
     * attaches exactly one player at a time; the other idles detached).
     */
    private var exoPlayer: ExoPlayer? = null
    /**
     * ST4: live-stream path state (null when the rendered path is
     * attached). [streamChapterPos] is the manifest chapter position;
     * [streamPausedRender] records a D-134 pause for the render resume.
     */
    private var streamPlayer: StreamVoice? = null
    private var streamChapterPos = -1
    private var streamChapterSize = 0
    private var streamPausedRender = false
    /** Book the active stream belongs to (render-resume scoping, D-134). */
    private var streamBookId: String? = null
    /**
     * ST7-fix: chapter requested through the stream extra while its book
     * loads (fresh Tap-to-Listen races the async load; the jump branch
     * only runs when the book is already loaded). Consumed once by
     * [loadBook], cleared on every use.
     */
    private var pendingStreamChapter: Int? = null
    private var currentManifest: Manifest? = null
    private var currentBundlePath: String? = null
    /**
     * CP4: back-to-back onPlayerError count with no intervening
     * STATE_READY (see PlaybackErrorPolicy). Reset on READY and after a
     * storage-loss pause so the next user Play retries naturally.
     */
    private var consecutiveErrors = 0

    /** RA8: sleep countdown (service-owned, survives UI closes). */
    private val sleepTimer = SleepTimer()
    private var sleepJob: Job? = null

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
            // ST4: a finished rendered chapter advances first (a streaming
            // next chapter takes over); only a true book end saves finished.
            if (playbackState == Player.STATE_ENDED && !advanceChapter(1)) {
                saveFinishedNow()
            }
            // CP4: a chapter that reaches READY actually loads, so the
            // back-to-back error streak is over.
            if (playbackState == Player.STATE_READY) consecutiveErrors = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            val player = session?.player ?: return
            val index = player.currentMediaItemIndex
            val label = player.currentMediaItem?.mediaMetadata?.title?.toString()
                ?.takeIf { it.isNotBlank() } ?: "chapter ${index + 1}"
            when (PlaybackErrorPolicy.decide(error.errorCode, consecutiveErrors)) {
                PlayerErrorDecision.StorageLoss -> {
                    consecutiveErrors = 0
                    Log.w(TAG, "storage unavailable (${error.errorCodeName}); pausing")
                    SkipNoticeMonitor.notifyStorageUnavailable()
                    try {
                        player.pause()
                    } catch (e: Exception) {
                        Log.w(TAG, "storage pause: ${e.message}")
                    }
                }
                PlayerErrorDecision.Skip -> {
                    consecutiveErrors += 1
                    Log.w(TAG, "chapter $index unreadable (${error.errorCodeName}); skipping")
                    SkipNoticeMonitor.notifySkipped(label)
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                        player.prepare()
                    } else {
                        player.stop()
                    }
                }
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
        exoPlayer = player
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
            "onStartCommand action=${intent?.action} hasBookExtra=${intent?.hasExtra(PlaybackIntents.EXTRA_BOOK_ID)} " +
                "flags=$flags startId=$startId"
        )
        val bookId = intent?.getStringExtra(PlaybackIntents.EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
        if (bookId != null) {
            if (bookId != currentBookId) {
                Log.i(TAG, "onStartCommand loading bookId=$bookId")
                loadBook(bookId)
            } else {
                Log.i(TAG, "onStartCommand already loaded bookId=$bookId")
            }
        }
        // RA8: sleep timer command (independent of the book extra, so the
        // timer can be set/cancelled without touching playback).
        intent?.getStringExtra(PlaybackIntents.EXTRA_SLEEP_OPTION)?.let { applySleepOption(it) }
        // ST5: live chapter jump inside the loaded book (reader chapter
        // list while streaming); ST7-fix: a jump for a loading book is
        // stashed for loadBook (fresh Listen-now races the async load).
        if (intent?.hasExtra(PlaybackIntents.EXTRA_STREAM_CHAPTER) == true && bookId != null) {
            val requested = intent.getIntExtra(PlaybackIntents.EXTRA_STREAM_CHAPTER, -1)
            pendingStreamChapter = requested.takeIf { it >= 0 }
            if (bookId == currentBookId) {
                jumpStreamToChapter(requested)
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
        // 1. Capture synchronously while the players are still alive. RN8:
        // the playlist index converts to a manifest chapter position, so
        // a sparse book never saves the wrong chapter (unmapped skips).
        // ST4: the stream path saves its sentence sid instead (position 0
        // plus the IN1 column); exactly one path is attached.
        val player = session?.player
        val attachedStream = streamPlayer?.takeIf { it.asPlayer() === player }
        val streamSave = attachedStream?.let {
            StreamRoute.streamSaveOrNull(currentBookId, streamChapterPos, it.currentSid.value)
        }
        val finalPoint = destroySavePointOrNull(
            currentBookId,
            player?.currentMediaItemIndex ?: C.INDEX_UNSET,
            (player?.currentPosition ?: 0L).coerceAtLeast(0L),
            currentMap
        )
        // 2. Stop the ticker so no periodic save can interleave with teardown.
        ticker?.cancel()
        ticker = null
        if (streamSave != null) {
            launchSave(
                ProgressSavePolicy.SavePoint(streamSave.bookId, streamSave.chapterPos, 0L),
                "destroy",
                NonCancellable,
                sentenceSid = streamSave.sid
            )
        } else if (finalPoint != null) {
            // 3. Launch BEFORE the scope cancel with NonCancellable: this
            // detaches the write from the scope's Job, so cancel() below
            // cannot kill it. Residual window: if the process itself is
            // killed mid-write the save is lost; the 5 s cadence bounds
            // that loss. Inherent to process kill, accepted for Slice 1.
            launchSave(finalPoint, "destroy", NonCancellable)
        }
        // 4. Complete scope ownership; then release per the Slice 1 plan.
        // ST4: the session's player plus whichever path idles detached.
        // A stream-paused render job stays paused here (its notification
        // resumes it); destroy never auto-resumes render work (D-134).
        serviceScope.cancel()
        session?.release()
        session = null
        try {
            player?.release()
        } catch (e: Exception) {
            Log.w(TAG, "player release: ${e.message}")
        }
        try {
            exoPlayer?.takeIf { it !== player }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "exo release: ${e.message}")
        }
        try {
            streamPlayer?.takeIf { it.asPlayer() !== player }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "stream release: ${e.message}")
        }
        exoPlayer = null
        streamPlayer = null
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
                currentMap = null
                currentManifest = null
                currentBundlePath = null
                stopSelf()
                return@launch
            }
            chapterDurations = prepared.durations
            currentMap = prepared.map
            currentManifest = prepared.manifest
            currentBundlePath = prepared.bundlePath
            // ST4: stream-only books (no playable audio) start on the
            // stream path when the gate allows it.
            if (prepared.mediaItems.isEmpty()) {
                val ordered = prepared.manifest.chapters.sortedBy { it.index }
                val count = ordered.size
                val requested = pendingStreamChapter?.takeIf { it in 0 until count }
                pendingStreamChapter = null
                val pos = requested
                    ?: (prepared.savedChapter
                        ?.coerceIn(0, (count - 1).coerceAtLeast(0)))
                    ?: 0
                val sid =
                    if (requested != null && requested != prepared.savedChapter) null
                    else prepared.savedSid
                startStreamChapter(bookId, pos, sid, generation, stopOnRefusal = true)
                return@launch
            }
            // ST4: a saved unrendered chapter streams instead of falling
            // back to the first rendered item. ST7-fix: a requested
            // chapter (fresh Listen-now) wins over the saved one.
            val savedPos = prepared.savedChapter
            val requestedPos = pendingStreamChapter?.takeIf {
                it in 0 until prepared.manifest.chapters.size
            }
            pendingStreamChapter = null
            val streamPos = requestedPos ?: savedPos
            if (streamingAvailable() && streamPos != null &&
                !isRenderedAt(prepared.manifest, streamPos)
            ) {
                val sid = if (streamPos == savedPos) prepared.savedSid else null
                startStreamChapter(bookId, streamPos, sid, generation, stopOnRefusal = true)
                return@launch
            }
            // A render paused for another book's stream stays paused (its
            // notification resumes it); same-book handoffs resume it.
            stopStreamPath(resumeRender = streamBookId == null || streamBookId == bookId)
            val exo = exoPlayer ?: player
            setSessionPlayer(exo)
            exo.setMediaItems(
                prepared.mediaItems,
                prepared.start.chapterIndex,
                prepared.start.positionMs
            )
            exo.prepare()
            // Start paused at the saved spot; WP7 takes over controls.
            exo.playWhenReady = false
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
        // RN8 (D-099 service partial path): the IN9 refusal stays for books
        // with no playable audio at all, but a partially rendered book now
        // loads its rendered subset. `buildPlayable` carries the manifest
        // chapter position (not the filtered position) and `startFromMap`
        // converts the saved chapter through the explicit mapping, so saved
        // progress can never land on the wrong chapter. Fully rendered
        // books keep the identical-to-before identity path.
        val map = buildChapterMediaMap(manifest)
        if (map.entries.isEmpty() &&
            !(streamingAvailable() && manifest.chapters.isNotEmpty())
        ) {
            PlaybackQueue.requirePlayable(manifest).getOrThrow()
        }
        val saved = progressRepository.load(bookId).getOrThrow()
        val items = PlaybackQueue.buildPlayable(
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
        if (items.isEmpty() &&
            !(streamingAvailable() && manifest.chapters.isNotEmpty())
        ) {
            throw IllegalStateException("no chapters listed for $bookId")
        }
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
        // RN8: identity books keep the exact pre-partial start mapping;
        // sparse books convert the saved chapter through the map, so an
        // unrendered saved chapter falls back to the first rendered item.
        val start = if (map.isIdentity) {
            PlaybackQueue.startFrom(saved, items.size, durations)
        } else {
            val durationsByPos = items.associate { it.chapterIndex to it.durationMs }
            PlaybackQueue.startFromMap(saved, map, durationsByPos)
        }
        return PreparedBook(
            mediaItems, start, durations, map,
            manifest, book.bundlePath,
            saved?.chapterIndex, saved?.sentenceSid
        )
    }

    // -- ST4 live-stream path ------------------------------------------------

    /**
     * Streaming is off until the ST0 gate passes ([StreamRoute.GATE_PASSED]);
     * the offline-voice half is proven per chapter at stream start by the
     * driver, which refuses before any silence.
     */
    private fun streamingAvailable(): Boolean = StreamRoute.GATE_PASSED

    private fun isStreamActive(): Boolean =
        streamPlayer?.let { session?.player === it.asPlayer() } ?: false

    private fun isRenderedAt(manifest: Manifest, chapterPos: Int): Boolean {
        val chapter = manifest.chapters.sortedBy { it.index }.getOrNull(chapterPos)
            ?: return false
        return PlaybackQueue.isRenderedChapter(chapter)
    }

    /** Rebuild the session around [player] (exactly one audible path). */
    private fun setSessionPlayer(player: Player) {
        session?.release()
        session = MediaSession.Builder(this, player).build()
    }

    private data class BuiltStream(
        val driver: StreamTtsDriver,
        val input: StreamChapterInput,
    )

    /**
     * Start streaming [chapterPos] (manifest position) at [startSid] (null
     * means the chapter start). Refusals publish a message and return
     * without touching the attached player unless [stopOnRefusal].
     */
    private fun startStreamChapter(
        bookId: String,
        chapterPos: Int,
        startSid: Int?,
        generation: Int,
        stopOnRefusal: Boolean,
    ) {
        stopStreamPath(resumeRender = false)
        serviceScope.launch {
            val bundlePath = currentBundlePath
            val manifest = currentManifest
            if (bundlePath == null || manifest == null) return@launch
            val built = withContext(Dispatchers.IO) {
                buildStreamChapter(bundlePath, manifest, chapterPos, startSid)
            }
            if (generation != loadGeneration) {
                try {
                    built?.driver?.shutdown()
                } catch (_: Exception) {
                }
                return@launch
            }
            if (built == null) {
                if (stopOnRefusal) {
                    currentBookId = null
                    currentMap = null
                    currentManifest = null
                    currentBundlePath = null
                    stopSelf()
                }
                return@launch
            }
            pauseRenderForStream(bundlePath)
            exoPlayer?.pause()
            val player = StreamVoice(
                this@PlaybackService,
                built.driver,
                object : StreamNavigator {
                    override fun onNextChapter() {
                        advanceChapter(1)
                    }

                    override fun onPreviousChapter() {
                        advanceChapter(-1)
                    }
                },
                object : StreamPlayerCallbacks {
                    override fun onChapterDone() {
                        onStreamChapterDone()
                    }

                    override fun onStreamFailed() {
                        onStreamFailed()
                    }

                    override fun onSidChanged(sid: Int) {
                        val book = currentBookId ?: return
                        StreamSidMonitor.publish(book, streamChapterPos, sid)
                    }
                }
            )
            streamPlayer = player
            streamChapterPos = chapterPos
            streamChapterSize = built.input.sentences.size
            streamBookId = bookId
            setSessionPlayer(player.asPlayer())
            if (!player.load(built.input)) {
                refuseStream("stream unavailable (chapter failed to load)")
                stopStreamPath(resumeRender = false)
                if (stopOnRefusal) stopSelf()
                return@launch
            }
            lastSaveUptimeMs = SystemClock.uptimeMillis()
            Log.i(TAG, "stream start book=$bookId chapter=$chapterPos sid=$startSid")
            player.play()
        }
    }

    /**
     * Blocking stream build (IO): text, sentences with roles, rendered-rule
     * pauses, system role voices, ready offline driver. Null means refused
     * (message already published). ST6 calibrates [volumes]; until then
     * roles play full.
     */
    private suspend fun buildStreamChapter(
        bundlePath: String,
        manifest: Manifest,
        chapterPos: Int,
        startSid: Int?,
    ): BuiltStream? {
        val ordered = manifest.chapters.sortedBy { it.index }
        val chapter = ordered.getOrNull(chapterPos)
            ?: return refuseStream("stream unavailable (unknown chapter)")
        val text = ChapterTextLoader.load(storage, joinPath(bundlePath, chapter.text))
            .getOrNull() ?: return refuseStream("stream unavailable (chapter text unreadable)")
        val sentences = mutableListOf<app.auloud.player.tts.StreamSentence>()
        val pauses = mutableMapOf<Int, Long>()
        text.blocks.forEach { block ->
            block.sentences.forEachIndexed { index, sentence ->
                if (sentence.text.isBlank()) return@forEachIndexed
                val role = if (isDialogueSpeaker(sentence.speaker)) {
                    TtsRole.Dialogue
                } else {
                    TtsRole.Narrator
                }
                sentences += app.auloud.player.tts.StreamSentence(sentence.sid, sentence.text, role)
                val pauseMs = try {
                    AssemblyMath.pauseAfterSentence(block.type, index == block.sentences.size - 1)
                } catch (_: IllegalArgumentException) {
                    0
                }
                if (pauseMs > 0) pauses[sentence.sid] = pauseMs.toLong()
            }
        }
        if (sentences.isEmpty()) return refuseStream("stream unavailable (no sentences)")
        val store = PrefsTtsStore.fromContext(this)
        val voices = try {
            RerenderFirstRender.readBookVoices(manifest, store)
        } catch (_: Exception) {
            null
        } ?: return refuseStream("stream unavailable (voice settings unreadable)")
        val driver = AndroidStreamTtsDriver(this)
        var waited = 0
        while (!driver.isReady && waited < STREAM_TTS_READY_WAIT_MS) {
            delay(100L)
            waited += 100
        }
        // ST7-fix: blank voice ids (never chosen) fall back to the engine
        // default, so "Listen now" works out of the box; non-system ids
        // still refuse.
        val narrator = StreamRoute.streamVoiceNameOrDefault(
            voices.narratorVoiceId, driver.defaultVoiceName()
        ) ?: run {
            try {
                driver.shutdown()
            } catch (_: Exception) {
            }
            return refuseStream("streaming needs a system voice")
        }
        val dialogue = StreamRoute.streamVoiceNameOrDefault(
            voices.resolvedDialogueVoiceId(), driver.defaultVoiceName()
        ) ?: run {
            try {
                driver.shutdown()
            } catch (_: Exception) {
            }
            return refuseStream("streaming needs a system voice")
        }
        val installed = driver.offlineVoiceNames()
        if (narrator !in installed || dialogue !in installed) {
            try {
                driver.shutdown()
            } catch (_: Exception) {
            }
            return refuseStream("stream unavailable (no offline system voice)")
        }
        val firstSid = sentences.firstOrNull { it.sid == startSid }?.sid
            ?: sentences.first().sid
        return BuiltStream(
            driver,
            StreamChapterInput(
                sentences = sentences,
                startSid = firstSid,
                voiceNames = mapOf(TtsRole.Narrator to narrator, TtsRole.Dialogue to dialogue),
                speeds = mapOf(
                    TtsRole.Narrator to voices.narratorSpeed,
                    TtsRole.Dialogue to voices.dialogueSpeed
                ),
                // ST6: stored level match when the pair still uses the
                // measured voices, else full (recalibrate after changes).
                volumes = StreamLeveling.volumesFor(
                    currentVoices = mapOf(
                        TtsRole.Narrator to voices.narratorVoiceId,
                        TtsRole.Dialogue to voices.resolvedDialogueVoiceId()
                    ),
                    calibratedVoices = mapOf(
                        TtsRole.Narrator to store.streamVolumeVoice(TtsRole.Narrator),
                        TtsRole.Dialogue to store.streamVolumeVoice(TtsRole.Dialogue)
                    ),
                    calibratedVolumes = mapOf(
                        TtsRole.Narrator to store.streamVolume(TtsRole.Narrator),
                        TtsRole.Dialogue to store.streamVolume(TtsRole.Dialogue)
                    )
                ),
                pausesAfterSid = pauses,
                title = text.title.ifBlank { chapter.title },
                bookTitle = manifest.title
            )
        )
    }

    private fun refuseStream(message: String): Nothing? {
        Log.w(TAG, "stream refused: $message")
        SkipNoticeMonitor.publish(message)
        return null
    }

    /** D-134: pause a RUNNING render job for the stream (no-op otherwise). */
    private fun pauseRenderForStream(bundlePath: String) {
        streamPausedRender = false
        try {
            val job = RenderStateStore.load(bundlePath, JavaFileRenderIo()).getOrNull()
            if (job?.state == RenderJobState.RUNNING) {
                sendRenderAction(RenderService.ACTION_PAUSE)
                streamPausedRender = true
                Log.i(TAG, "render paused for stream")
            }
        } catch (e: Exception) {
            Log.w(TAG, "render pause check: ${e.message}")
        }
    }

    private fun sendRenderAction(action: String) {
        val bookId = currentBookId ?: return
        try {
            startService(
                Intent(this, RenderService::class.java)
                    .setAction(action)
                    .putExtra(RenderService.EXTRA_BOOK_ID, bookId)
            )
        } catch (e: Exception) {
            Log.w(TAG, "render action $action: ${e.message}")
        }
    }

    /**
     * Leave the stream path. [resumeRender] resumes a D-134-paused job;
     * callers staying inside streamed chapters pass false (the job stays
     * paused across them), callers returning to rendered listening pass
     * true. Always saves the sentence spot first.
     */
    private fun stopStreamPath(resumeRender: Boolean) {
        val player = streamPlayer ?: return
        val chapterPos = streamChapterPos
        val bookId = currentBookId
        streamPlayer = null
        streamChapterPos = -1
        streamChapterSize = 0
        streamBookId = null
        StreamSidMonitor.clear()
        val sid = try {
            player.currentSid.value
        } catch (_: Exception) {
            null
        }
        // sid survives release: save by value, not via the player.
        if (bookId != null) {
            StreamRoute.streamSaveOrNull(bookId, chapterPos, sid)?.let { save ->
                launchSave(
                    ProgressSavePolicy.SavePoint(save.bookId, save.chapterPos, 0L),
                    "stream-stop",
                    sentenceSid = save.sid
                )
            }
        }
        try {
            player.release()
        } catch (e: Exception) {
            Log.w(TAG, "stream release: ${e.message}")
        }
        if (resumeRender && streamPausedRender &&
            RenderServicePolicy.resumeAfterStream(pausedByStream = true)
        ) {
            sendRenderAction(RenderService.ACTION_RESUME)
        }
        streamPausedRender = false
    }

    /**
     * Step one chapter in [direction] across the audible path boundary.
     * True when the chapter changed (callers skip their own end handling);
     * false at the book ends or when the target refuses.
     */
    private fun advanceChapter(direction: Int): Boolean {
        val manifest = currentManifest ?: return false
        val ordered = manifest.chapters.sortedBy { it.index }
        val streaming = streamingAvailable()
        val currentPos = if (isStreamActive()) {
            streamChapterPos
        } else {
            val media = session?.player?.currentMediaItemIndex ?: C.INDEX_UNSET
            if (media == C.INDEX_UNSET) 0
            else currentMap?.chapterPosOf(media) ?: 0
        }
        val rendered = ordered.map { PlaybackQueue.isRenderedChapter(it) }
        val next = try {
            StreamRoute.advance(currentPos, rendered, streaming, direction)
        } catch (_: IllegalArgumentException) {
            null
        } ?: return false
        val bookId = currentBookId ?: return false
        when (next.target) {
            StreamRoute.Target.Rendered -> {
                stopStreamPath(resumeRender = true)
                if (!playRenderedChapter(next.chapterPos)) return false
            }
            StreamRoute.Target.Streamed -> {
                try {
                    exoPlayer?.pause()
                } catch (e: Exception) {
                    Log.w(TAG, "exo pause for stream: ${e.message}")
                }
                startStreamChapter(bookId, next.chapterPos, null, ++loadGeneration, stopOnRefusal = false)
            }
            StreamRoute.Target.Unavailable -> {
                SkipNoticeMonitor.publish(PlaybackQueue.NEEDS_RENDER_MESSAGE)
                return false
            }
        }
        return true
    }

    /** Seek the rendered path at a manifest chapter (false when unmapped). */
    private fun playRenderedChapter(chapterPos: Int): Boolean {
        val media = currentMap?.mediaIndexOf(chapterPos) ?: return false
        val exo = exoPlayer ?: return false
        setSessionPlayer(exo)
        exo.seekTo(media, 0L)
        exo.prepare()
        exo.play()
        return true
    }

    /**
     * ST5: reader chapter jump inside the loaded book. Unrendered chapters
     * move the stream; rendered chapters move the rendered path; anything
     * else (out of range, no manifest) is ignored.
     */
    private fun jumpStreamToChapter(chapterPos: Int) {
        val manifest = currentManifest ?: return
        if (chapterPos < 0 || chapterPos >= manifest.chapters.size) return
        pendingStreamChapter = null
        val bookId = currentBookId ?: return
        if (isRenderedAt(manifest, chapterPos)) {
            stopStreamPath(resumeRender = true)
            playRenderedChapter(chapterPos)
            return
        }
        if (!streamingAvailable()) return
        stopStreamPath(resumeRender = false)
        startStreamChapter(bookId, chapterPos, null, ++loadGeneration, stopOnRefusal = false)
    }

    /** A streamed chapter played to its last sentence: keep going. */
    private fun onStreamChapterDone() {
        // Driver callbacks land on binder threads; player and session
        // work stays on the service scope (Main).
        serviceScope.launch {
            if (!advanceChapter(1)) {
                saveStreamFinishedNow()
            }
        }
    }

    /** The engine failed mid-chapter: message, save the spot, fall back. */
    private fun onStreamFailed() {
        Log.w(TAG, "stream failed")
        SkipNoticeMonitor.publish(STREAM_FAILED_MESSAGE)
        serviceScope.launch {
            val bookId = currentBookId
            stopStreamPath(resumeRender = true)
            if (bookId != null) {
                loadBook(bookId)
            }
        }
    }

    /** Finished save for a book whose last chapter streamed (sid-based). */
    private fun saveStreamFinishedNow() {
        val bookId = currentBookId ?: return
        val manifest = currentManifest ?: return
        val count = manifest.chapters.size
        if (count <= 0 || streamChapterPos != count - 1) return
        val player = streamPlayer ?: return
        val sid = try {
            player.currentSid.value
        } catch (_: Exception) {
            null
        } ?: return
        stopStreamPath(resumeRender = true)
        launchSave(
            ProgressSavePolicy.SavePoint(bookId, count - 1, 0L),
            "end-of-book",
            finished = true,
            sentenceSid = sid
        )
    }

    /** Persists the current spot; skipped when nothing playable is loaded. */
    private fun saveProgressNow(reason: String) {
        val player = session?.player ?: return
        // ST4: the stream path saves its sentence sid (position 0 plus
        // the IN1 column), never milliseconds.
        val stream = streamPlayer?.takeIf { it.asPlayer() === player }
        if (stream != null) {
            val save = StreamRoute.streamSaveOrNull(
                currentBookId, streamChapterPos, stream.currentSid.value
            ) ?: return
            launchSave(
                ProgressSavePolicy.SavePoint(save.bookId, save.chapterPos, 0L),
                "stream-$reason",
                sentenceSid = save.sid
            )
            return
        }
        // RN8 (media-to-chapter save conversion): the playlist index is a
        // media index, but the store is keyed by manifest chapter position.
        // Unmapped indexes save nothing instead of the wrong chapter.
        val chapterPos = RenderServicePolicy.chapterPosForMedia(
            player.currentMediaItemIndex,
            currentMap
        ) ?: run {
            Log.w(TAG, "save skipped: media ${player.currentMediaItemIndex} has no chapter")
            return
        }
        val point = ProgressSavePolicy.pointOrNull(
            currentBookId,
            chapterPos,
            player.currentPosition.coerceAtLeast(0L)
        ) ?: return
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
        finished: Boolean = false,
        sentenceSid: Int? = null
    ): Job {
        if (BuildConfig.DEBUG) DebugSaveTracker.recordSave(System.currentTimeMillis())
        return serviceScope.launch(context) {
            val result = progressRepository.save(point.bookId, point.chapterIndex, point.positionMs, sentenceSid)
            if (result.isSuccess) {
                // FP2: the throttle advances only on write success, so a
                // failed save stays armed and the next tick retries it.
                lastSaveUptimeMs = SystemClock.uptimeMillis()
                consecutiveSaveFailures = 0
                Log.i(
                    SAVE_TAG,
                    "progress saved reason=$reason book=${point.bookId} " +
                        "chapter=${point.chapterIndex} pos=${point.positionMs}" +
                        (if (sentenceSid != null) " sid=$sentenceSid" else "") +
                        if (finished) " finished" else ""
                )
            } else {
                Log.w(SAVE_TAG, "progress save failed reason=$reason: ${result.exceptionOrNull()?.message}")
                consecutiveSaveFailures += 1
                if (ProgressSavePolicy.shouldNotifySaveFailure(consecutiveSaveFailures)) {
                    consecutiveSaveFailures = 0
                    SkipNoticeMonitor.publish(SAVE_FAILED_MESSAGE)
                }
            }
        }
    }

    /**
     * RA8: sleep timer command from the UI ([PlaybackIntents.EXTRA_SLEEP_OPTION], a
     * [SleepOption] name). Runs a 1 s countdown job: fades the volume over
     * the last seconds, then pauses and saves. Cancelling restores full
     * volume. Survives UI closes (service-owned); the remaining time is
     * published via [SleepTimerMonitor] for the controller to copy.
     */
    private fun applySleepOption(name: String) {
        val option = try {
            SleepOption.valueOf(name)
        } catch (_: Exception) {
            Log.w(TAG, "sleep timer: unknown option $name")
            return
        }
        sleepJob?.cancel()
        sleepJob = null
        val player = session?.player
        player?.volume = 1f
        if (option == SleepOption.Off || player == null) {
            sleepTimer.cancel()
            SleepTimerMonitor.publish(active = false, remainingMs = null)
            Log.i(TAG, "sleep timer: off")
            return
        }
        // FP1: refuse End-of-chapter on streams (sentence index is not ms).
        if (isStreamEndOfChapterRefused(option, isStreamActive())) {
            sleepTimer.cancel()
            SleepTimerMonitor.publish(active = false, remainingMs = null)
            SkipNoticeMonitor.publish(SLEEP_END_OF_CHAPTER_ON_STREAM)
            Log.i(TAG, "sleep timer: end of chapter refused on stream")
            return
        }
        sleepTimer.start(option, SystemClock.uptimeMillis())
        Log.i(TAG, "sleep timer: $name")
        sleepJob = serviceScope.launch {
            while (isActive) {
                delay(1_000L)
                val current = session?.player ?: break
                val stream = streamPlayer?.takeIf { it.asPlayer() === current }
                // FP1: a timer set on rendered audio that moves onto a
                // stream refuses instead of fading on sentence counts.
                if (isStreamEndOfChapterRefused(sleepTimer.option, stream != null)) {
                    current.volume = 1f
                    sleepTimer.cancel()
                    SleepTimerMonitor.publish(active = false, remainingMs = null)
                    SkipNoticeMonitor.publish(SLEEP_END_OF_CHAPTER_ON_STREAM)
                    Log.i(TAG, "sleep timer: end of chapter refused on stream")
                    break
                }
                // ST4: streamed chapters count sentences, not milliseconds;
                // position is the sentence index by the same convention.
                // Minute options ignore both (wall-clock deadlines); only
                // End-of-chapter reads them, and streams never reach it.
                val chapter = stream?.let { streamChapterPos }
                    ?: current.currentMediaItemIndex
                val duration = stream?.let { streamChapterSize.toLong() }
                    ?: chapterDurations.getOrElse(chapter) { 0L }
                val position = current.currentPosition.coerceAtLeast(0L)
                val now = SystemClock.uptimeMillis()
                if (sleepTimer.isExpired(now, position, duration)) {
                    current.volume = 1f
                    try {
                        current.pause()
                    } catch (e: Exception) {
                        Log.w(TAG, "sleep timer pause: ${e.message}")
                    }
                    saveProgressNow("sleep-timer")
                    sleepTimer.cancel()
                    SleepTimerMonitor.publish(active = false, remainingMs = null)
                    Log.i(TAG, "sleep timer: expired, paused")
                    break
                }
                current.volume = sleepTimer.fadeVolume(now, position, duration)
                SleepTimerMonitor.publish(
                    active = true,
                    remainingMs = sleepTimer.remainingMs(now, position, duration)
                )
            }
            sleepJob = null
        }
    }

    /** At the end of the final chapter: mark finished (last chapter, full duration). */
    private fun saveFinishedNow() {
        val player = session?.player ?: return
        val bookId = currentBookId ?: return
        // RN8 (end-of-portion message): a partial book stopping at the end
        // of its rendered portion is not finished, so it surfaces the stop
        // message through the notice channel and never writes a finished
        // save. Fully rendered books behave exactly as before.
        if (!RenderServicePolicy.finishedSaveAllowed(currentMap)) {
            val map = currentMap
            if (map != null && isEndOfRenderedPortion(player.currentMediaItemIndex, map)) {
                // ST4: the rendered portion ends but the book goes on
                // streaming: switch instead of stopping with a message.
                if (streamingAvailable() && advanceChapter(1)) return
                Log.i(TAG, "end of rendered portion: message surfaced")
                SkipNoticeMonitor.publish(END_OF_RENDERED_MESSAGE)
            }
            return
        }
        val count = player.mediaItemCount
        if (!ProgressSavePolicy.isEndOfBook(player.currentMediaItemIndex, count, playbackEnded = true)) {
            return
        }
        val point = ProgressSavePolicy.finishedPoint(
            bookId, count, chapterDurations.lastOrNull() ?: 0L
        )
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
        val durations: List<Long>,
        val map: ChapterMediaMap,
        val manifest: Manifest,
        val bundlePath: String,
        val savedChapter: Int?,
        val savedSid: Int?
    )

    companion object {
        private const val MANIFEST_FILE = "manifest.json"

        /** ST4: engine-failure notice (saves stand, rendered audio untouched). */
        const val STREAM_FAILED_MESSAGE = "live voice failed"

        /**
         * FP2: consecutive progress writes failed (stuck store: full disk,
         * dead SD). Playback continues; the position is at risk until a
         * write lands.
         */
        const val SAVE_FAILED_MESSAGE = "Progress not saving - storage unavailable"

        /** FP1: End-of-chapter sleep refusal on the stream path. */
        const val SLEEP_END_OF_CHAPTER_ON_STREAM =
            "End-of-chapter sleep needs rendered audio."

        /** ST4: how long stream start waits for the TTS engine init. */
        private const val STREAM_TTS_READY_WAIT_MS = 5_000

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

/**
 * RN8: destroy-path save point (JVM-testable half of `PlaybackService.onDestroy`).
 *
 * Converts the playlist [mediaIndex] to a manifest chapter position through
 * [RenderServicePolicy.chapterPosForMedia] and builds the save point, exactly
 * mirroring `saveProgressNow`. Returns null when unmapped (skip the save
 * instead of writing the wrong chapter) or when there is nothing loaded.
 * Null [map] passes through (service not loaded yet); identity maps pass
 * through untouched, so rendered books behave exactly as before.
 */
internal fun destroySavePointOrNull(
    bookId: String?,
    mediaIndex: Int,
    positionMs: Long,
    map: ChapterMediaMap?
): ProgressSavePolicy.SavePoint? {
    val chapterPos = RenderServicePolicy.chapterPosForMedia(mediaIndex, map) ?: return null
    return ProgressSavePolicy.pointOrNull(bookId, chapterPos, positionMs.coerceAtLeast(0L))
}
