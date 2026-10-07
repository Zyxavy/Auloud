package app.auloud.player.render

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.ProgressRepository
import app.auloud.player.playback.PlaybackQueue
import app.auloud.player.storage.BundleStorage
import app.auloud.player.storage.SafPaths
import app.auloud.player.tts.TtsRole
import app.auloud.player.tts.TtsVoiceStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * RN9: render panel state for one book (Slice 10).
 *
 * Plain class like [UnrenderedReaderViewModel]: owns its coroutines (a
 * [SupervisorJob] over [dispatcher]); call [clear] when the screen is
 * disposed. Everything Android (service intents, prefs writes, rescan)
 * arrives as callbacks, so the option mapping, estimate ranges, error
 * mapping and delete flow are plain-JVM-testable with fakes.
 *
 * Loading reads the manifest plus one chapter JSON per unrendered
 * chapter (sequential, counts only, never held: the one-chapter memory
 * rule counts held chapters, not a counting pass). Measured manifest
 * durations always win; word counts size the rest at [EST_MS_PER_WORD];
 * unreadable chapters fall back to the [RenderEstimates] default and are
 * counted as unknown (the panel says the estimate is rough).
 */
class RenderPanelViewModel(
    private val bookId: String,
    private val bundleDir: String,
    private val storage: BundleStorage,
    private val progress: ProgressRepository,
    private val voices: TtsVoiceStore,
    private val fileIo: RenderFileIo,
    policy: RenderPolicy,
    private val onPolicyChange: (RenderPolicy) -> Unit = {},
    private val onStartRender: (readingChapter: Int, scope: String, nextN: Int) -> Unit =
        { _, _, _ -> },
    private val onPauseRender: () -> Unit = {},
    private val onResumeRender: () -> Unit = {},
    private val onCancelRender: () -> Unit = {},
    private val onBookChanged: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(
        RenderPanelState(chargingOnly = policy.chargingOnly)
    )
    val state: kotlinx.coroutines.flow.StateFlow<RenderPanelState> = _state

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var currentPolicy: RenderPolicy = policy
    private var audioMsByChapter: Map<Int, Long?> = emptyMap()
    private var noSleep: (Long) -> Unit = {}

    init {
        reload()
    }

    /** Cancels loads (screen disposed). */
    fun clear() {
        scope.cancel()
    }

    /** Re-reads the manifest, word counts and job file (after renders and deletes). */
    fun refresh() {
        reload()
    }

    /** Panel option (whole book, next N, from here); the estimate recomputes. */
    fun selectOption(option: RenderOption) {
        val current = _state.value
        if (current.option == option) return
        _state.value = current.copy(option = option, error = null)
        recomputeEstimate()
    }

    /** Next-N window size, clamped to 1..chapterCount. */
    fun setNextN(nextN: Int) {
        val current = _state.value
        val clamped = nextN.coerceIn(1, current.chapterCount.coerceAtLeast(1))
        if (current.nextN == clamped) return
        _state.value = current.copy(nextN = clamped, error = null)
        recomputeEstimate()
    }

    /** Charging-only toggle (persisted by the host through [onPolicyChange]). */
    fun setChargingOnly(enabled: Boolean) {
        currentPolicy = currentPolicy.copy(chargingOnly = enabled)
        onPolicyChange(currentPolicy)
        _state.value = _state.value.copy(chargingOnly = enabled)
    }

    /**
     * Starts the render with the current option.
     *
     * An empty plan is a plain-language error, never a service start.
     * The start itself goes through [onStartRender] (the host sends the
     * [RenderService] intent); the service owns planning from there, so
     * this plan is only the estimate the panel shows.
     */
    fun start() {
        val current = _state.value
        if (current.isLoading || current.manifestError != null) return
        val plan = currentPlan()
        if (plan.orderedChapters.isEmpty()) {
            _state.value = current.copy(
                error = renderErrorText(
                    "Nothing to render: every planned chapter already has audio"
                )
            )
            return
        }
        _state.value = current.copy(error = null)
        onStartRender(current.readingChapter, scopeName(current.option), current.nextN)
        reloadJobOnly()
    }

    /** Per-chapter render action: this chapter plus nothing else. */
    fun renderChapter(chapterPos: Int) {
        val current = _state.value
        if (current.isLoading || current.manifestError != null) return
        if (chapterPos !in 0 until current.chapterCount) return
        _state.value = current.copy(error = null)
        onStartRender(chapterPos, RenderService.SCOPE_NEXT_N, 1)
        reloadJobOnly()
    }

    fun pause() {
        onPauseRender()
        reloadJobOnly()
    }

    fun resume() {
        onResumeRender()
        reloadJobOnly()
    }

    fun cancel() {
        onCancelRender()
        reloadJobOnly()
    }

    /** Deletes one rendered chapter's audio, then reloads and rescans. */
    fun deleteChapterByPos(chapterPos: Int) {
        val current = _state.value
        val number = current.sortedNumbers.getOrNull(chapterPos) ?: return
        val outcome = RenderAudioDelete.deleteChapterAudio(bundleDir, number, fileIo, noSleep)
        outcome.fold(
            onSuccess = {
                onBookChanged()
                reload()
            },
            onFailure = { e ->
                _state.value = _state.value.copy(error = renderErrorText(e.message))
            }
        )
    }

    /** Deletes the whole book's rendered audio, then reloads and rescans. */
    fun deleteAllAudio() {
        val outcome = RenderAudioDelete.deleteBookAudio(bundleDir, fileIo, noSleep)
        outcome.fold(
            onSuccess = {
                onBookChanged()
                reload()
            },
            onFailure = { e ->
                _state.value = _state.value.copy(error = renderErrorText(e.message))
            }
        )
    }

    /** Dismisses the error line (tap or retry). */
    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun reload() {
        val kept = _state.value
        _state.value = kept.copy(isLoading = true)
        scope.launch {
            val loaded = loadBlocking()
            _state.value = loaded.copy(
                option = _state.value.option,
                nextN = _state.value.nextN,
                chargingOnly = currentPolicy.chargingOnly
            )
            recomputeEstimate()
        }
    }

    private fun reloadJobOnly() {
        scope.launch {
            val job = loadJob()
            val current = _state.value
            val renderedSet = current.renderedPositions.toSet()
            _state.value = current.copy(
                jobState = job?.state,
                jobDone = job?.completedChapters?.size ?: 0,
                jobTotal = job?.plan?.orderedChapters?.size ?: 0,
                jobError = job?.error,
                jobCurrentNumber = job?.currentChapter?.let { current.sortedNumbers.getOrNull(it) },
                chapterStates = current.sortedNumbers.indices.associateWith { pos ->
                    chapterStateFor(pos, pos in renderedSet, job)
                }
            )
        }
    }

    private suspend fun loadBlocking(): RenderPanelState {
        val rawManifest = try {
            storage.readText(join(bundleDir, MANIFEST_FILE))
        } catch (e: Exception) {
            return RenderPanelState(
                isLoading = false,
                manifestError = renderErrorText("manifest unreadable (${e.message})"),
                chargingOnly = currentPolicy.chargingOnly
            )
        }
        val manifest = BundleParser.parseText(rawManifest).getOrElse {
            return RenderPanelState(
                isLoading = false,
                manifestError = renderErrorText("manifest unreadable (${it.message})"),
                chargingOnly = currentPolicy.chargingOnly
            )
        }
        val sorted = manifest.chapters.sortedBy { it.index }
        val rendered = HashSet<Int>()
        for ((pos, chapter) in sorted.withIndex()) {
            if (PlaybackQueue.isRenderedChapter(chapter)) rendered.add(pos)
        }
        val saved = try {
            progress.load(bookId).getOrNull()
        } catch (_: Exception) {
            null
        }
        val readingChapter = if (sorted.isEmpty()) {
            0
        } else {
            (saved?.chapterIndex ?: 0).coerceIn(0, sorted.size - 1)
        }
        val audioMs = HashMap<Int, Long?>()
        for ((pos, chapter) in sorted.withIndex()) {
            if (pos in rendered) {
                audioMs[pos] = chapter.durationMs
                continue
            }
            audioMs[pos] = wordsFor(chapter.text)?.let(::audioMsForWords)
        }
        audioMsByChapter = audioMs
        val job = loadJob()
        val renderedSet = rendered.toSet()
        return RenderPanelState(
            isLoading = false,
            title = manifest.title,
            chapterCount = sorted.size,
            renderedCount = rendered.size,
            renderedPositions = rendered.sorted(),
            unrenderedPositions = sorted.indices.filter { it !in rendered },
            sortedNumbers = sorted.map { it.index },
            chapterStates = sorted.indices.associateWith { pos ->
                chapterStateFor(pos, pos in renderedSet, job)
            },
            readingChapter = readingChapter,
            manifest = manifest,
            jobState = job?.state,
            jobDone = job?.completedChapters?.size ?: 0,
            jobTotal = job?.plan?.orderedChapters?.size ?: 0,
            jobError = job?.error,
            jobCurrentNumber = job?.currentChapter?.let { pos -> sorted.getOrNull(pos)?.index },
            voicesLine = voicesSummary(
                narratorId = voices.voiceId(TtsRole.Narrator),
                dialogueId = voices.voiceId(TtsRole.Dialogue),
                narratorSpeed = voices.speed(TtsRole.Narrator),
                dialogueSpeed = voices.speed(TtsRole.Dialogue)
            ),
            isFileBook = !SafPaths.isSafPath(bundleDir),
            chargingOnly = currentPolicy.chargingOnly
        )
    }

    private fun wordsFor(textRel: String): Int? {
        if (textRel.isBlank()) return null
        return try {
            val raw = storage.readText(join(bundleDir, textRel))
            val chapter = ChapterTextLoader.parse(textRel, raw).getOrNull() ?: return null
            chapter.sentencesInOrder().sumOf { countWords(it.text) }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadJob(): RenderJob? {
        if (SafPaths.isSafPath(bundleDir)) return null
        return try {
            RenderStateStore.load(bundleDir, fileIo, noSleep).getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun currentPlan(): RenderPlan {
        val current = _state.value
        return planForOption(
            bookId = bookId,
            chapterCount = current.chapterCount,
            readingChapter = current.readingChapter,
            option = current.option,
            nextN = current.nextN,
            isRendered = { pos -> pos in current.renderedPositions }
        )
    }

    private fun recomputeEstimate() {
        val current = _state.value
        if (current.isLoading || current.manifestError != null) return
        val plan = currentPlan()
        val estimate = computeRenderEstimate(plan.orderedChapters, audioMsByChapter)
        _state.value = current.copy(
            estimate = estimate,
            planSize = plan.orderedChapters.size
        )
    }

    private fun scopeName(option: RenderOption): String = when (option) {
        RenderOption.WHOLE_BOOK -> RenderService.SCOPE_WHOLE_BOOK
        RenderOption.FROM_HERE -> RenderService.SCOPE_FROM_HERE
        RenderOption.NEXT_N -> RenderService.SCOPE_NEXT_N
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    companion object {
        private const val MANIFEST_FILE = "manifest.json"
    }
}

/**
 * RN9: render panel state (one immutable snapshot per load or option change).
 *
 * [sortedNumbers] maps 0-based positions to 1-based manifest `index`
 * values (file names, spool names, delete calls); [manifest] backs the
 * hub chapter map. [planSize] is the current option's chapter count.
 * [jobError] is the raw service reason (the panel maps it through
 * [renderErrorText] at display time); [error] is already plain.
 */
data class RenderPanelState(
    val isLoading: Boolean = true,
    val title: String = "",
    val chapterCount: Int = 0,
    val renderedCount: Int = 0,
    val renderedPositions: List<Int> = emptyList(),
    val unrenderedPositions: List<Int> = emptyList(),
    val sortedNumbers: List<Int> = emptyList(),
    val readingChapter: Int = 0,
    val option: RenderOption = RenderOption.WHOLE_BOOK,
    val nextN: Int = RenderPlanner.DEFAULT_NEXT_N,
    /** Per-chapter row states (positions to [ChapterRenderState]). */
    val chapterStates: Map<Int, ChapterRenderState> = emptyMap(),
    val chargingOnly: Boolean = true,
    val estimate: RenderEstimateView = RenderEstimateView(),
    val planSize: Int = 0,
    val voicesLine: String = "",
    val jobState: RenderJobState? = null,
    val jobDone: Int = 0,
    val jobTotal: Int = 0,
    val jobError: String? = null,
    /**
     * 1-based manifest `index` of the chapter now rendering (null when
     * idle; the job file knows chapters, not sentences, so the overlay
     * points at the live notification for sentences).
     */
    val jobCurrentNumber: Int? = null,
    val error: String? = null,
    val manifestError: String? = null,
    val manifest: Manifest? = null,
    val isFileBook: Boolean = true
)
