package app.auloud.player.render

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.auloud.player.BuildConfig
import app.auloud.player.R
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.data.AuloudDatabase
import app.auloud.player.data.LibraryRepository
import app.auloud.player.data.ProgressRepository
import app.auloud.player.data.RoomLibraryRepository
import app.auloud.player.data.RoomProgressRepository
import app.auloud.player.playback.PlaybackQueue
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.BooksRootResolver
import app.auloud.player.storage.FileBundleStorage
import app.auloud.player.storage.FrameworkSafBackend
import app.auloud.player.storage.RoutingBundleStorage
import app.auloud.player.storage.SafBundleStorage
import app.auloud.player.storage.SafPaths
import app.auloud.player.tts.AndroidSystemTtsDriver
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.ModelPacks
import app.auloud.player.tts.PrefsTtsStore
import app.auloud.player.tts.SherpaPiperEngine
import app.auloud.player.tts.SystemTtsAdapter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * RN8: foreground render service (Slice 10).
 *
 * Renders an unrendered book chapter by chapter on the tablet: voice
 * passes spool sentences (RN4, resume skips finished sentences after a
 * kill), assembly streams into the platform AAC encoder (RN5/RN6),
 * finalize writes audio, then timed JSON, then the manifest (RN7
 * crash-safe order). A progress notification carries pause/cancel
 * actions; a partial wake lock is held only while actively rendering
 * (released on pause, finish, cancel and destroy).
 *
 * Start it with plain `startService()` (API 24 has no
 * `startForegroundService`; the service enters the foreground via
 * `startForeground()` with its own notification). Returns START_STICKY:
 * a system restart after a kill arrives with a null intent, which logs
 * and stops (restart never auto-starts; the persisted file is recovered
 * on the next explicit start through `RenderRecovery.recoverBook` plus
 * the RN3 INTERRUPTED path, and the spool resumes finished sentences).
 *
 * Task removal means pause plus persist ([RenderServicePolicy]) and the
 * service stays foreground-paused for an explicit resume. Charging-only
 * auto-pause/resume, the temperature guard and the low-storage pause all
 * run through the RN3 guard inputs; guard pauses auto-resume when the
 * signals clear, explicit pauses never do.
 *
 * Engine graph mirrors the voice-lab host (reads only, no TTS changes):
 * System TTS adapter, sherpa Piper when complete packs are present,
 * beep in debug builds. Spool lives under the app cache
 * (`render-spool/<bookId>`, not the book folder), so the validator never
 * sees it. SAF-tree books are refused shaped (no `java.io.File` meaning);
 * file-path books only until app-storage output lands.
 *
 * No UI screens (notification plus actions only; RN9 builds the
 * screens). No `java.time`. Needs-device-test items are listed in D-110.
 */
class RenderService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderLoop: Job? = null

    @Volatile
    private var pauseRequested = false
    @Volatile
    private var cancelRequested = false
    @Volatile
    private var autoPaused = false
    @Volatile
    private var pauseReason: RenderGuardDecision? = null

    private var currentBookId: String? = null
    private var currentBundleDir: String? = null
    private var currentJob: RenderJob? = null
    private var bookGainsLinear: Map<String, Double> = emptyMap()

    private var planTotal = 0
    private var planDone = 0
    private var chapterLabel = ""
    private var chapterSentences = 0
    private var chapterSentenceDone = 0

    private var wakeLock: PowerManager.WakeLock? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var systemDriver: AndroidSystemTtsDriver? = null
    private var sherpaEngine: SherpaPiperEngine? = null

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
    private val fileIo = JavaFileRenderIo()
    private val spoolIo = JavaFileSpoolIo()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock unavailable: ${e.message}")
            wakeLock = null
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                serviceScope.launch { evaluateAutoGuards() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            registerReceiver(receiver, filter)
            powerReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "power receiver not registered: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(
            TAG,
            "onStartCommand action=${intent?.action} " +
                "book=${intent?.getStringExtra(EXTRA_BOOK_ID)} flags=$flags startId=$startId"
        )
        when (intent?.action) {
            ACTION_PAUSE -> userPause()
            ACTION_RESUME -> userResume()
            ACTION_CANCEL -> cancelRender()
            ACTION_START, null -> {
                val bookId = intent?.getStringExtra(EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }
                if (bookId != null) {
                    startRender(
                        bookId = bookId,
                        readingChapter = intent.getIntExtra(EXTRA_READING_CHAPTER, 0),
                        scopeName = intent.getStringExtra(EXTRA_SCOPE) ?: SCOPE_WHOLE_BOOK,
                        nextN = intent.getIntExtra(EXTRA_NEXT_N, RenderPlanner.DEFAULT_NEXT_N)
                    )
                } else {
                    Log.i(TAG, "restart with null intent: waiting for explicit start (no auto-resume)")
                    if (currentBookId == null) stopSelf()
                }
            }
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: pause plus persist")
        val job = currentJob
        val bundleDir = currentBundleDir
        if (job != null && bundleDir != null && job.state == RenderJobState.RUNNING) {
            pauseRequested = true
            autoPaused = false
            try {
                val paused = RenderServicePolicy.onTaskRemoved(job) { System.currentTimeMillis() }
                currentJob = paused
                RenderStateStore.save(bundleDir, paused, fileIo)
            } catch (e: Exception) {
                Log.w(TAG, "task-removed persist failed: ${e.message}")
            }
            releaseWakeLock("task-removed")
            refreshNotification(pausedText("Swiped away - rendering paused"))
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        renderLoop?.cancel()
        renderLoop = null
        try {
            val job = currentJob
            val bundleDir = currentBundleDir
            if (job != null && bundleDir != null && job.state == RenderJobState.RUNNING) {
                val paused = RenderServicePolicy.onTaskRemoved(job) { System.currentTimeMillis() }
                currentJob = paused
                RenderStateStore.save(bundleDir, paused, fileIo)
            }
        } catch (e: Exception) {
            Log.w(TAG, "destroy persist failed: ${e.message}")
        }
        releaseWakeLock("destroy")
        try {
            powerReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        powerReceiver = null
        try {
            systemDriver?.shutdown()
        } catch (_: Exception) {
        }
        systemDriver = null
        try {
            sherpaEngine?.release()
        } catch (_: Exception) {
        }
        sherpaEngine = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /** Explicit start (or re-start) for [bookId]; refuses while another book renders. */
    private fun startRender(bookId: String, readingChapter: Int, scopeName: String, nextN: Int) {
        if (renderLoop?.isActive == true) {
            if (bookId == currentBookId) {
                Log.i(TAG, "startRender already rendering bookId=$bookId")
            } else {
                Log.w(TAG, "startRender refused: $currentBookId is rendering (single runner)")
            }
            return
        }
        val job = currentJob
        if (job != null && job.bookId == bookId &&
            (job.state == RenderJobState.PAUSED || job.state == RenderJobState.INTERRUPTED)
        ) {
            userResume()
            return
        }
        currentBookId = bookId
        pauseRequested = false
        cancelRequested = false
        autoPaused = false
        pauseReason = null
        enterForeground(buildNotification("Preparing render", "Starting..."))
        Log.i(TAG, "startRender bookId=$bookId chapter=$readingChapter scope=$scopeName")
        renderLoop = serviceScope.launch {
            withContext(Dispatchers.IO) {
                runBook(bookId, readingChapter, scopeName, nextN)
            }
        }
    }

    /** Explicit user pause: never auto-resumes. */
    private fun userPause() {
        Log.i(TAG, "userPause")
        if (currentJob?.state == RenderJobState.RUNNING && renderLoop?.isActive == true) {
            autoPaused = false
            pauseRequested = true
        } else {
            Log.i(TAG, "userPause with no running render")
        }
    }

    /** Explicit resume (notification action or next start): re-enters the loop. */
    private fun userResume() {
        Log.i(TAG, "userResume")
        val bookId = currentBookId ?: run {
            Log.w(TAG, "userResume with no book")
            return
        }
        if (renderLoop?.isActive == true) {
            Log.i(TAG, "userResume already running")
            return
        }
        autoPaused = false
        pauseRequested = false
        cancelRequested = false
        pauseReason = null
        enterForeground(buildNotification("Resuming render", "Checking..."))
        renderLoop = serviceScope.launch {
            withContext(Dispatchers.IO) {
                runBook(bookId, 0, SCOPE_WHOLE_BOOK, RenderPlanner.DEFAULT_NEXT_N)
            }
        }
    }

    /** Explicit cancel: stops after the current sentence and marks CANCELLED. */
    private fun cancelRender() {
        Log.i(TAG, "cancelRender")
        if (renderLoop?.isActive == true) {
            cancelRequested = true
            pauseRequested = true
        } else {
            val job = currentJob
            val bundleDir = currentBundleDir
            if (job != null && bundleDir != null) {
                try {
                    val cancelled = RenderJobs.transition(
                        job,
                        RenderJobState.CANCELLED,
                        clock = { System.currentTimeMillis() }
                    )
                    currentJob = cancelled
                    RenderStateStore.save(bundleDir, cancelled, fileIo)
                } catch (e: Exception) {
                    Log.w(TAG, "cancel persist failed: ${e.message}")
                }
            }
            stopSelf()
        }
    }

    /** Power/battery signal: pause on a new guard trip, resume a guard pause when clear. */
    private suspend fun evaluateAutoGuards() {
        val job = currentJob ?: return
        val bundleDir = currentBundleDir ?: return
        if (job.state != RenderJobState.RUNNING && !autoPaused) return
        val remaining = RenderJobs.firstPending(job)?.let { listOf(it) } ?: emptyList()
        val decision = guardDecision(bundleDir, remaining)
        if (decision != RenderGuardDecision.PROCEED && job.state == RenderJobState.RUNNING) {
            Log.i(TAG, "guard trip ${decision.name}: auto-pausing")
            autoPaused = true
            pauseReason = decision
            pauseRequested = true
        } else if (RenderServicePolicy.shouldAutoResume(autoPaused, decision)) {
            Log.i(TAG, "guards clear: auto-resuming")
            autoPaused = false
            pauseReason = null
            pauseRequested = false
            if (renderLoop?.isActive != true) {
                currentBookId?.let { book ->
                    renderLoop = serviceScope.launch {
                        withContext(Dispatchers.IO) {
                            runBook(book, 0, SCOPE_WHOLE_BOOK, RenderPlanner.DEFAULT_NEXT_N)
                        }
                    }
                }
            }
        }
    }

    /** Full per-book render: recover, load-or-plan the job, then chapter by chapter. */
    private suspend fun runBook(bookId: String, readingChapter: Int, scopeName: String, nextN: Int) {
        val bundleDir = resolveBundleDir(bookId) ?: run {
            notifyError("Render failed", "Book not found")
            stopSelf()
            return
        }
        if (SafPaths.isSafPath(bundleDir)) {
            Log.w(TAG, "SAF book refused: app-storage output is not in RN8")
            notifyError("Render unavailable", "Books in picked folders need app-storage output (later)")
            stopSelf()
            return
        }
        currentBundleDir = bundleDir
        val spoolDir = RenderServicePolicy.spoolDirFor(cacheDir.absolutePath, bookId)
        try {
            File(spoolDir).mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "spool dir unavailable: ${e.message}")
        }
        val recovery = RenderRecovery.recoverBook(bundleDir, fileIo, spoolDir, spoolIo)
        recovery.onSuccess { report ->
            Log.i(
                TAG,
                "recoverBook swept=${report.sweptTemps.size} spool=${report.sweptSpool.size} " +
                    "repairs=${report.repairs.size} manifest=${report.manifestRewritten} " +
                    "job=${report.jobAfter?.state} readable=${report.manifestReadable}"
            )
            for (repair in report.repairs) {
                Log.i(TAG, "repair chapter ${repair.chapterNumber} ${repair.kind}: ${repair.detail}")
            }
            if (report.jobError != null) Log.w(TAG, "recover job: ${report.jobError}")
        }.onFailure { e ->
            Log.w(TAG, "recoverBook failed: ${e.message}")
        }
        var job = loadOrPlanJob(bookId, bundleDir, readingChapter, scopeName, nextN) ?: run {
            stopSelf()
            return
        }
        currentJob = job
        planTotal = job.plan.orderedChapters.size
        planDone = job.completedChapters.size
        val remaining = remainingOf(job)
        val preDecision = guardDecision(bundleDir, remaining)
        if (preDecision != RenderGuardDecision.PROCEED) {
            Log.i(TAG, "pre-start guard ${preDecision.name}: pausing before first chapter")
            autoPaused = true
            pauseReason = preDecision
            pauseJob(job, bundleDir)
            return
        }
        acquireWakeLock()
        val voices = resolveVoices() ?: run {
            failJob(job, bundleDir, "voice setup failed (choose narrator and dialogue voices)")
            return
        }
        bookGainsLinear = readManifestGains(bundleDir)
        while (true) {
            if (cancelRequested) {
                cancelJob(job, bundleDir)
                return
            }
            if (pauseRequested) {
                pauseJob(job, bundleDir)
                return
            }
            val pos = RenderJobs.firstPending(job)
            if (pos == null) {
                finishJob(job, bundleDir)
                return
            }
            val step = guardDecision(bundleDir, remainingOf(job))
            if (step != RenderGuardDecision.PROCEED) {
                Log.i(TAG, "guard trip ${step.name} at chapter pos $pos: pausing")
                autoPaused = true
                pauseReason = step
                pauseJob(job, bundleDir)
                return
            }
            when (val outcome = renderOneChapter(bookId, bundleDir, spoolDir, job, pos, voices)) {
                is ChapterStep.Done -> {
                    job = outcome.job
                    currentJob = job
                }
                is ChapterStep.Stop -> return
            }
        }
    }

    private sealed interface ChapterStep {
        data class Done(val job: RenderJob) : ChapterStep
        data object Stop : ChapterStep
    }

    /** Renders, encodes and finalizes one plan chapter; advances the job on success. */
    private suspend fun renderOneChapter(
        bookId: String,
        bundleDir: String,
        spoolDir: String,
        job: RenderJob,
        pos: Int,
        voices: ResolvedRenderVoices
    ): ChapterStep {
        val manifest = readManifest(bundleDir) ?: run {
            failJob(job, bundleDir, "manifest unreadable")
            return ChapterStep.Stop
        }
        val sorted = manifest.chapters.sortedBy { it.index }
        val entry = sorted.getOrNull(pos) ?: run {
            failJob(job, bundleDir, "plan chapter $pos is out of range")
            return ChapterStep.Stop
        }
        val number = entry.index
        val textRel = entry.text
        Log.i(TAG, "chapter start book=$bookId number=$number pos=$pos")
        chapterLabel = "Chapter $number"
        chapterSentences = 0
        chapterSentenceDone = 0
        refreshNotification(progressText())
        val rawChapter = try {
            storage.readText(joinPath(bundleDir, textRel))
        } catch (e: Exception) {
            failJob(job, bundleDir, "chapter $number: cannot read text (${e.message})")
            return ChapterStep.Stop
        }
        val chapter = ChapterTextLoader.parse(textRel, rawChapter).getOrElse { e ->
            failJob(job, bundleDir, "chapter $number: text unreadable (${e.message})")
            return ChapterStep.Stop
        }
        chapterSentences = chapter.sentencesInOrder().size
        val spooled = SpoolRenderer.renderChapter(
            chapterPos = pos,
            chapterNumber = number,
            chapter = chapter,
            voices = voices,
            spoolDir = spoolDir,
            io = spoolIo,
            shouldCancel = { pauseRequested || cancelRequested },
            onReleasePass = { },
            onSentenceDone = {
                chapterSentenceDone++
                refreshNotification(progressText())
            }
        )
        when (spooled) {
            is ChapterSpoolOutcome.Failed -> {
                failJob(job, bundleDir, spooled.reason)
                return ChapterStep.Stop
            }
            is ChapterSpoolOutcome.Cancelled -> {
                if (cancelRequested) cancelJob(job, bundleDir) else pauseJob(job, bundleDir)
                return ChapterStep.Stop
            }
            is ChapterSpoolOutcome.Completed -> {
                val summary = spooled.summary
                Log.i(
                    TAG,
                    "chapter $number spooled=${summary.spooled} skipped=${summary.skipped} " +
                        "retried=${summary.retried.size}"
                )
                if (bookGainsLinear.isEmpty()) {
                    try {
                        val derived = BookGains.derive(
                            mapOf(
                                "narrator" to summary.peakNarrator,
                                "dialogue" to summary.peakDialogue
                            )
                        )
                        bookGainsLinear = derived.linear
                        Log.i(TAG, "book gains derived: ${derived.db}")
                    } catch (e: Exception) {
                        Log.w(TAG, "book gain derive failed: ${e.message}")
                    }
                }
                val audioRel = RenderFinalize.deviceAudioRel(number)
                val encoded = assembleAndEncode(bundleDir, spoolDir, number, chapter, audioRel)
                    ?: run {
                        failJob(job, bundleDir, "chapter $number: assembly or encode failed")
                        return ChapterStep.Stop
                    }
                val gainDb = try {
                    BookGains.derive(
                        mapOf(
                            "narrator" to summary.peakNarrator,
                            "dialogue" to summary.peakDialogue
                        )
                    ).db
                } catch (_: Exception) {
                    emptyMap()
                }
                val finalized = RenderFinalize.finalizeChapter(
                    bundleDir = bundleDir,
                    chapterNumber = number,
                    audioRel = audioRel,
                    timings = encoded.first.timings,
                    durationMs = encoded.first.durationMs,
                    fingerprint = voices.fingerprint,
                    gainDb = gainDb,
                    encoderOffsetMs = 0,
                    io = fileIo
                ).getOrElse { e ->
                    failJob(job, bundleDir, "chapter $number: finalize failed (${e.message})")
                    return ChapterStep.Stop
                }
                convertProgress(bookId, bundleDir, pos, number)
                sweepChapterSpool(spoolDir, number)
                val advanced = try {
                    RenderJobs.onChapterDone(job, pos)
                } catch (e: Exception) {
                    failJob(job, bundleDir, "chapter $number: job advance failed (${e.message})")
                    return ChapterStep.Stop
                }
                persistJob(bundleDir, advanced)
                planDone = advanced.completedChapters.size
                Log.i(
                    TAG,
                    "chapter done book=$bookId number=$number " +
                        "duration=${finalized.durationMs}ms done=$planDone/$planTotal"
                )
                refreshNotification(progressText())
                return ChapterStep.Done(advanced)
            }
        }
    }

    /** Assembly plus platform encode for one chapter (null on any failure). */
    private fun assembleAndEncode(
        bundleDir: String,
        spoolDir: String,
        number: Int,
        chapter: app.auloud.player.bundle.ChapterText,
        audioRel: String
    ): Pair<AssembledChapterResult, EncodedChapter>? {
        val encoder = try {
            AndroidAudioEncoder(number, joinPath(bundleDir, audioRel))
        } catch (e: Exception) {
            Log.w(TAG, "chapter $number: ${e.message}")
            return null
        }
        val adapter = AudioEncoderSinkAdapter(encoder, number)
        return try {
            val indexRaw = spoolIo.readText(SpoolFiles.indexPath(spoolDir, number))
            val index = SpoolIndex.parse(indexRaw)
                ?: throw IllegalStateException("chapter $number: spool index unreadable")
            val assembled = ChapterAssembler.assembleFromSpool(
                chapterNumber = number,
                chapter = chapter,
                index = index,
                loadPcm = { entry ->
                    val bytes = try {
                        File(SpoolFiles.path(spoolDir, entry.file)).readBytes()
                    } catch (e: Exception) {
                        throw IllegalArgumentException(
                            "chapter $number sentence ${entry.sid}: " +
                                "spool file ${entry.file} unreadable (${e.message})"
                        )
                    }
                    SpoolPcm.decodePcm16(bytes)
                },
                bookGainsLinear = bookGainsLinear,
                sink = adapter
            )
            val encoded = adapter.encoded
                ?: throw IllegalStateException("chapter $number: encoder produced no file")
            if (encoded.sampleCount != assembled.sampleCount ||
                encoded.durationMs != assembled.durationMs
            ) {
                throw IllegalStateException("chapter $number: encoder file disagrees with assembly")
            }
            assembled to encoded
        } catch (e: Exception) {
            try {
                encoder.abort()
            } catch (_: Exception) {
            }
            Log.w(TAG, "chapter $number: assembly or encode failed (${e.message})")
            null
        }
    }

    /** Best-effort sid-to-ms progress conversion after a chapter renders (RN7 hook). */
    private suspend fun convertProgress(bookId: String, bundleDir: String, pos: Int, number: Int) {
        try {
            val saved = progressRepository.load(bookId).getOrNull() ?: return
            val timedRaw = fileIo.readText(joinPath(bundleDir, RenderFinalize.chapterTextRel(number)))
            val timed = ChapterTextLoader.parse(RenderFinalize.chapterTextRel(number), timedRaw)
                .getOrNull() ?: return
            val converted = RenderProgress.convertOnRender(saved, pos, timed)
            if (converted != null && converted != saved) {
                progressRepository.save(converted.bookId, converted.chapterIndex, converted.positionMs)
                Log.i(TAG, "progress converted book=$bookId chapter=$pos to ${converted.positionMs}ms")
            }
        } catch (e: Exception) {
            Log.w(TAG, "progress conversion skipped: ${e.message}")
        }
    }

    /** Loads the persisted job or plans a fresh one; null when there is nothing to run. */
    private suspend fun loadOrPlanJob(
        bookId: String,
        bundleDir: String,
        readingChapter: Int,
        scopeName: String,
        nextN: Int
    ): RenderJob? {
        val stored = RenderStateStore.load(bundleDir, fileIo).getOrElse { e ->
            Log.w(TAG, "job load failed: ${e.message}")
            null
        }
        if (stored != null && stored.bookId == bookId) {
            val resumed: RenderJob = when (stored.state) {
                RenderJobState.RUNNING,
                RenderJobState.PAUSED,
                RenderJobState.INTERRUPTED,
                RenderJobState.QUEUED -> try {
                    RenderJobs.transition(
                        stored,
                        RenderJobState.RUNNING,
                        clock = { System.currentTimeMillis() }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "resume failed: ${e.message}")
                    return null
                }
                RenderJobState.FAILED,
                RenderJobState.CANCELLED -> try {
                    val queued = RenderJobs.transition(
                        stored,
                        RenderJobState.QUEUED,
                        clock = { System.currentTimeMillis() }
                    )
                    RenderJobs.transition(
                        queued,
                        RenderJobState.RUNNING,
                        clock = { System.currentTimeMillis() }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "retry failed: ${e.message}")
                    return null
                }
                RenderJobState.DONE -> {
                    Log.i(TAG, "job already done book=$bookId")
                    notifyDone("Render complete", "All planned chapters are rendered")
                    return null
                }
            }
            if (RenderJobs.firstPending(resumed) == null) {
                val done = try {
                    RenderJobs.transition(
                        resumed,
                        RenderJobState.DONE,
                        clock = { System.currentTimeMillis() }
                    )
                } catch (_: Exception) {
                    resumed
                }
                persistJob(bundleDir, done)
                currentJob = done
                notifyDone("Render complete", "All planned chapters are rendered")
                return null
            }
            persistJob(bundleDir, resumed)
            currentJob = resumed
            Log.i(TAG, "job resumed book=$bookId state=${resumed.state} pending=${RenderJobs.firstPending(resumed)}")
            return resumed
        }
        val manifest = readManifest(bundleDir) ?: run {
            notifyError("Render failed", "Manifest unreadable")
            return null
        }
        val sorted = manifest.chapters.sortedBy { it.index }
        val scope = when (scopeName) {
            SCOPE_FROM_HERE -> RenderScope.FromHere
            SCOPE_NEXT_N -> RenderScope.NextN(nextN.coerceAtLeast(1))
            else -> RenderScope.WholeBook
        }
        val plan = RenderPlanner.plan(
            bookId = bookId,
            chapterCount = sorted.size,
            readingChapter = readingChapter,
            scope = scope,
            isRendered = { pos -> sorted.getOrNull(pos)?.let(PlaybackQueue::isRenderedChapter) == true }
        )
        if (plan.orderedChapters.isEmpty()) {
            Log.i(TAG, "plan empty book=$bookId (nothing unrendered)")
            notifyDone("Nothing to render", "Every planned chapter already has audio")
            return null
        }
        val now = System.currentTimeMillis()
        val fresh = RenderJob(
            bookId = bookId,
            state = RenderJobState.QUEUED,
            plan = plan,
            createdAt = now,
            updatedAt = now
        )
        val running = try {
            RenderJobs.transition(fresh, RenderJobState.RUNNING, clock = { now })
        } catch (e: Exception) {
            Log.w(TAG, "job start failed: ${e.message}")
            return null
        }
        persistJob(bundleDir, running)
        currentJob = running
        Log.i(TAG, "job planned book=$bookId chapters=${plan.orderedChapters}")
        return running
    }

    private fun remainingOf(job: RenderJob): List<Int> {
        val done = job.completedChapters.toSet()
        return job.plan.orderedChapters.filter { it !in done }
    }

    private fun guardDecision(bundleDir: String, remaining: List<Int>): RenderGuardDecision {
        val policy = try {
            RenderPolicyPrefs.load(this)
        } catch (_: Exception) {
            RenderPolicy()
        }
        val required = RenderEstimates.requiredBytes(remaining)
        val signals = AndroidRenderSignals(this, bundleDir)
        val decision = try {
            RenderGuards.decideInputs(signals, policy, required)
        } catch (e: Exception) {
            Log.w(TAG, "guard read failed: ${e.message}")
            return RenderGuardDecision.PROCEED
        }
        if (decision != RenderGuardDecision.PROCEED) {
            Log.i(
                TAG,
                "guard ${decision.name} free=${signals.freeBytes()} required=$required " +
                    "charging=${signals.isCharging()} temp=${signals.batteryTempC()}"
            )
        }
        return decision
    }

    private fun pauseJob(job: RenderJob, bundleDir: String) {
        try {
            val paused = if (job.state == RenderJobState.RUNNING) {
                RenderJobs.transition(
                    job,
                    RenderJobState.PAUSED,
                    clock = { System.currentTimeMillis() }
                )
            } else {
                job
            }
            currentJob = paused
            persistJob(bundleDir, paused)
        } catch (e: Exception) {
            Log.w(TAG, "pause persist failed: ${e.message}")
        }
        val reason = pauseReason?.let(RenderServicePolicy::pauseMessage)
            ?: "Rendering paused"
        Log.i(TAG, "pause: $reason")
        releaseWakeLock("pause")
        refreshNotification(reason)
    }

    private fun cancelJob(job: RenderJob, bundleDir: String) {
        try {
            val cancelled = RenderJobs.transition(
                job,
                RenderJobState.CANCELLED,
                clock = { System.currentTimeMillis() }
            )
            currentJob = cancelled
            persistJob(bundleDir, cancelled)
        } catch (e: Exception) {
            Log.w(TAG, "cancel persist failed: ${e.message}")
        }
        Log.i(TAG, "cancelled book=${job.bookId}")
        releaseWakeLock("cancel")
        refreshNotification("Render cancelled")
        stopSelf()
    }

    private fun failJob(job: RenderJob, bundleDir: String, reason: String) {
        try {
            val failed = RenderJobs.transition(
                job,
                RenderJobState.FAILED,
                clock = { System.currentTimeMillis() },
                error = reason
            )
            currentJob = failed
            persistJob(bundleDir, failed)
        } catch (e: Exception) {
            Log.w(TAG, "fail persist failed: ${e.message}")
        }
        Log.w(TAG, "failed book=${job.bookId}: $reason")
        releaseWakeLock("fail")
        notifyError("Render failed", reason)
        stopSelf()
    }

    private fun finishJob(job: RenderJob, bundleDir: String) {
        val done = if (job.state == RenderJobState.DONE) job else try {
            RenderJobs.transition(
                job,
                RenderJobState.DONE,
                clock = { System.currentTimeMillis() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "finish transition failed: ${e.message}")
            job
        }
        currentJob = done
        persistJob(bundleDir, done)
        Log.i(TAG, "done book=${job.bookId} chapters=$planDone/$planTotal")
        releaseWakeLock("finish")
        notifyDone("Render complete", "Rendered $planDone of $planTotal planned chapters")
        stopSelf()
    }

    private fun persistJob(bundleDir: String, job: RenderJob) {
        RenderStateStore.save(bundleDir, job, fileIo).onFailure { e ->
            Log.w(TAG, "job persist failed: ${e.message}")
        }
    }

    private suspend fun resolveBundleDir(bookId: String): String? {
        return try {
            libraryRepository.books().first().firstOrNull { it.id == bookId }?.bundlePath
        } catch (e: Exception) {
            Log.w(TAG, "book lookup failed: ${e.message}")
            null
        }
    }

    private fun readManifest(bundleDir: String): app.auloud.player.bundle.Manifest? {
        return try {
            BundleParser.parseText(storage.readText(joinPath(bundleDir, MANIFEST_FILE))).getOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "manifest read failed: ${e.message}")
            null
        }
    }

    /** Manifest `gain_db` back to linear gains (empty when absent; RN7 first-wins on write). */
    private fun readManifestGains(bundleDir: String): Map<String, Double> {
        val manifest = readManifest(bundleDir) ?: return emptyMap()
        val obj = manifest.gainDb as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for ((role, value) in obj) {
            if (role != "narrator" && role != "dialogue") continue
            val db = (value as? JsonPrimitive)
                ?.takeIf { !it.isString }?.doubleOrNull
                ?.takeIf { it.isFinite() } ?: continue
            out[role] = AssemblyMath.gainForDb(db)
        }
        return out
    }

    /** Engine graph for one render run (fresh instances; released when the run ends). */
    private fun resolveVoices(): ResolvedRenderVoices? {
        val driver = AndroidSystemTtsDriver(this)
        systemDriver = driver
        val scratch = File(cacheDir, "tts-render")
        val adapter = SystemTtsAdapter(driver, scratch)
        val packs = try {
            val internal = File(BooksRootResolver.defaultBooksRoot(this))
            val removable = try {
                BooksRootResolver.findRemovableRoot(this)
            } catch (_: Exception) {
                null
            }
            ModelPacks.scan(ModelPacks.roots(internal, removable))
        } catch (_: Exception) {
            emptyList()
        }
        val sherpa = SherpaPiperEngine(packs).takeIf { it.voices().isNotEmpty() }
        sherpaEngine = sherpa
        val registry = EngineRegistry(
            listOfNotNull(
                adapter,
                sherpa,
                DebugRenderEngines.beepEngineIfDebug(BuildConfig.DEBUG)
            )
        )
        val store = PrefsTtsStore.fromContext(this)
        return RenderVoices.resolve(registry, store, ::engineVersion).getOrElse { e ->
            Log.w(TAG, "voice resolve failed: ${e.message}")
            null
        }
    }

    /**
     * Provisional per-engine version strings (RN10 records the real Tab E
     * strings). Non-blank by contract: a blank version fails the render
     * instead of fingerprinting audio that later updates cannot invalidate.
     */
    private fun engineVersion(namespace: String): String? = when (namespace) {
        SystemTtsAdapter.SYSTEM_NAMESPACE -> systemTtsVersion()
        SherpaPiperEngine.PIPER_NAMESPACE -> "sherpa-1.13.8"
        BeepTtsEngine.NAMESPACE -> BeepTtsEngine.VERSION
        else -> null
    }

    private fun systemTtsVersion(): String {
        return try {
            val engine = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_SYNTH
            )
            val info = packageManager.getPackageInfo(engine, 0)
            @Suppress("DEPRECATION")
            info.versionName?.takeIf { !it.isNullOrBlank() } ?: "system"
        } catch (_: Exception) {
            "system"
        }
    }

    private fun sweepChapterSpool(spoolDir: String, number: Int) {
        try {
            for (pcm in spoolIo.listFiles(spoolDir, SpoolFiles.chapterPrefix(number), ".pcm")) {
                spoolIo.deleteIfExists(pcm)
            }
            spoolIo.deleteIfExists(SpoolFiles.indexPath(spoolDir, number))
        } catch (e: Exception) {
            Log.w(TAG, "spool sweep failed: ${e.message}")
        }
    }

    /**
     * Acquires the partial wake lock for active rendering.
     *
     * The `WakelockTimeout` lint check wants an acquire timeout, but a
     * fixed timeout cannot cover multi-hour renders: the lock is instead
     * held only while the loop runs and released on every exit path
     * (pause, finish, cancel, error, task-removed, destroy), each logged.
     */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
                Log.i(TAG, "wake lock acquired")
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock(reason: String) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "wake lock released ($reason)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake release failed: ${e.message}")
        }
    }

    private fun progressText(): String {
        val sentence = if (chapterSentences > 0) {
            ", sentence $chapterSentenceDone/$chapterSentences"
        } else {
            ""
        }
        return "$chapterLabel ($planDone/$planTotal chapters$sentence)"
    }

    private fun pausedText(reason: String): String = reason

    private fun pendingAction(action: String, bookId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(this, RenderService::class.java).setAction(action)
        if (bookId != null) intent.putExtra(EXTRA_BOOK_ID, bookId)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val paused = currentJob?.state == RenderJobState.PAUSED || autoPaused
        val bookId = currentBookId
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(this)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (planTotal > 0) builder.setProgress(planTotal, planDone, false)
        if (paused) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Resume",
                    pendingAction(ACTION_RESUME, bookId, REQUEST_RESUME)
                ).build()
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Pause",
                    pendingAction(ACTION_PAUSE, bookId, REQUEST_PAUSE)
                ).build()
            )
        }
        builder.addAction(
            Notification.Action.Builder(
                null,
                "Cancel",
                pendingAction(ACTION_CANCEL, bookId, REQUEST_CANCEL)
            ).build()
        )
        if (Build.VERSION.SDK_INT >= 26) {
            // v3: create the render notification channel here and switch to
            // Builder(this, CHANNEL_ID); API 24 has no channels.
        }
        return builder.build()
    }

    /**
     * Enters the foreground with [notification].
     *
     * The `ForegroundServiceType` lint check wants a manifest
     * `foregroundServiceType` attribute, which is an API 29+ concept the
     * plan forbids (like `startForegroundService` and notification
     * channels, it does not exist on the API 24 target). Suppressed at
     * this single choke point, not at the call sites.
     */
    @android.annotation.SuppressLint("ForegroundServiceType")
    private fun enterForeground(notification: Notification) {
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * Posts or updates the service notification.
     *
     * The `NotificationPermission` lint check wants POST_NOTIFICATIONS,
     * which the plan forbids (an API 33+ runtime permission that does not
     * exist on the API 24 target). This posts only the foreground
     * service's own notification plus its progress updates, so the
     * suppression lives at this single choke point, not at the callers.
     */
    @android.annotation.SuppressLint("NotificationPermission")
    private fun postNotification(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun refreshNotification(text: String) {
        try {
            postNotification(buildNotification("Rendering audiobook", text))
        } catch (e: Exception) {
            Log.w(TAG, "notification refresh failed: ${e.message}")
        }
    }

    private fun notifyError(title: String, text: String) {
        releaseWakeLock("error")
        try {
            postNotification(buildNotification(title, text))
        } catch (e: Exception) {
            Log.w(TAG, "error notification failed: ${e.message}")
        }
    }

    private fun notifyDone(title: String, text: String) {
        try {
            @Suppress("DEPRECATION")
            val done = Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(false)
                .setOnlyAlertOnce(true)
                .build()
            if (Build.VERSION.SDK_INT >= 26) {
                // v3: create the render notification channel here and switch
                // to Builder(this, CHANNEL_ID); API 24 has no channels.
            }
            postNotification(done)
        } catch (e: Exception) {
            Log.w(TAG, "done notification failed: ${e.message}")
        }
    }

    private fun joinPath(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    companion object {
        /** Start (or re-enter) a render. API 24: deliver with plain `startService`. */
        const val ACTION_START = "app.auloud.player.render.action.START"

        /** Cooperative pause after the current sentence. */
        const val ACTION_PAUSE = "app.auloud.player.render.action.PAUSE"

        /** Resume a paused or interrupted job. */
        const val ACTION_RESUME = "app.auloud.player.render.action.RESUME"

        /** Stop after the current sentence and mark CANCELLED. */
        const val ACTION_CANCEL = "app.auloud.player.render.action.CANCEL"

        /** Manifest `id` of the book to render. */
        const val EXTRA_BOOK_ID = "app.auloud.player.render.extra.BOOK_ID"

        /** 0-based reading chapter the plan starts from. */
        const val EXTRA_READING_CHAPTER = "app.auloud.player.render.extra.READING_CHAPTER"

        /** Plan scope name (WHOLE_BOOK, FROM_HERE, NEXT_N). */
        const val EXTRA_SCOPE = "app.auloud.player.render.extra.SCOPE"

        /** Chapter count for the NEXT_N scope. */
        const val EXTRA_NEXT_N = "app.auloud.player.render.extra.NEXT_N"

        const val SCOPE_WHOLE_BOOK = "WHOLE_BOOK"
        const val SCOPE_FROM_HERE = "FROM_HERE"
        const val SCOPE_NEXT_N = "NEXT_N"

        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_PAUSE = 2001
        private const val REQUEST_RESUME = 2002
        private const val REQUEST_CANCEL = 2003
        private const val MANIFEST_FILE = "manifest.json"
        private const val WAKE_TAG = "Auloud:Render"

        private const val TAG = "AuloudRender"

        /** Start intent for RN9 (and tests on device): book plus plan scope. */
        fun startIntent(
            context: Context,
            bookId: String,
            readingChapter: Int = 0,
            scope: String = SCOPE_WHOLE_BOOK,
            nextN: Int = RenderPlanner.DEFAULT_NEXT_N
        ): Intent = Intent(context, RenderService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_READING_CHAPTER, readingChapter)
            .putExtra(EXTRA_SCOPE, scope)
            .putExtra(EXTRA_NEXT_N, nextN)
    }
}
