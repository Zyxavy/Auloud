package app.auloud.player.tts

import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.data.ProgressRepository
import app.auloud.player.render.ChapterStaleState
import app.auloud.player.render.RenderFileIo
import app.auloud.player.render.RenderJob
import app.auloud.player.render.RenderJobState
import app.auloud.player.render.RenderStaleness
import app.auloud.player.render.RenderStateStore
import app.auloud.player.render.RerenderGuards
import app.auloud.player.render.RerenderMode
import app.auloud.player.render.RerenderPlanner
import app.auloud.player.render.StaleChapterInput
import app.auloud.player.storage.BundleStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * VS4: book-scoped voice settings view-model (D-113, D-121, D-122).
 *
 * The book screen edits [BookVoices] (per-book manifest entries with
 * global fallback); the global Settings screen keeps editing the shared
 * [TtsVoiceStore]. A "use as default for new books" action promotes the
 * book choice to globals ([promoteToDefaults]); changing globals never
 * touches existing books.
 *
 * What it owns:
 * - Working copy [edited] over the manifest [original]; [hasChanges]
 *   compares the two (voice ids plus speeds).
 * - Real-line audition: the narration and dialogue samples are the first
 *   book sentences per role ([BookVoiceSamplePicker]), scanned one
 *   chapter JSON at a time (never held: the one-chapter memory rule
 *   counts held chapters, not a counting pass). Preview synthesizes the
 *   sample with the role voice plus speed and plays it on the audition
 *   player (never the book session). A/B compare auditions an alternate
 *   voice per role ([setAlternateVoice] plus [preview] with alternate).
 * - Engine picker with the benchmark category ([EngineBenchmark]) and
 *   the slow-engine warning: [selectEngine] stages a pending engine with
 *   the VS1 mapping preview ([BookVoices.previewEngineSwitch]) plus the
 *   per-hour warning when slow; [confirmEngineSwitch] applies it,
 *   [cancelEngineSwitch] drops it.
 * - Apply flow with the impact dialog (D-121): [requestApply] computes
 *   the stale set under the edited voices (chapters, hours of audio,
 *   estimated wall-time range from the Slice 10 constants, swap storage)
 *   and, when a render job is active, pauses it plus recomputes the plan
 *   ([RerenderPlanner.onVoiceChange], the VS3 ask state) so the dialog
 *   shows added minus removed chapters. [confirmApply] then persists the
 *   manifest and either starts the stale-only re-render reading-position
 *   forward first (NOW), or only persists with no start (LATER and KEEP,
 *   device verdict 2026-10-09: KEEP saves without rendering).
 * - Scribe (PC) books: voices visible, every edit refused with the plain
 *   model message ([BookVoices.READ_ONLY_MESSAGE]); audition is disabled
 *   too, because the book voices are PC voice ids the device engines do
 *   not offer, so a preview would only fail confusingly.
 *
 * VS6: apply-time guards ([RerenderGuards]) before any persist or start:
 * edited voices must be offered by the live [registry] (missing engine,
 * voice or model pack refuses naming the piece plus the voice-screen
 * fix path), and a NOW start with stale audio must fit the swap
 * ([freeBytes] vs old plus new per the VS2 math; LATER and KEEP skip the
 * storage check and defer it to the service). Every refusal sets
 * [UiState.error] and persists nothing, leaving manifest plus audio
 * byte-identical. A voice change on an unrendered book (no stale
 * audio) persists with no start and no crash; the first render later
 * uses the saved voices.
 *
 * Plain class like [VoiceAuditionViewModel]: owns its coroutines, call
 * [clear] when the screen is disposed. Everything Android (service
 * intents, rescan) arrives as callbacks, so states plus the apply flow
 * are plain-JVM-testable with fakes.
 *
 * API 24 safe: coroutines only (framework hides behind the seams).
 */
class BookVoiceViewModel(
    private val bookId: String,
    private val bundleDir: String,
    private val storage: BundleStorage,
    private val progress: ProgressRepository,
    private val globals: TtsVoiceStore,
    private val registry: EngineRegistry,
    private val audio: AudioPlayer,
    private val versionOf: (namespace: String) -> String? = { null },
    private val fileIo: RenderFileIo,
    private val onStartRerender: (readingChapter: Int, mode: RerenderMode) -> Unit =
        { _, _ -> },
    private val onPauseRender: () -> Unit = {},
    private val onBookChanged: () -> Unit = {},
    private val freeBytes: () -> Long = { Long.MAX_VALUE },
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    /** Apply-flow choices from the impact dialog (D-121). */
    enum class ApplyChoice {
        NOW,
        LATER,
        KEEP
    }

    /** Impact numbers for the dialog (stale set under the edited voices). */
    data class ImpactView(
        val staleChapters: Int,
        val staleAudioMs: Long,
        val wallFastMs: Long,
        val wallSlowMs: Long,
        val newBytes: Long,
        val swapBytes: Long,
        val jobWasPaused: Boolean,
        val added: List<Int> = emptyList(),
        val removed: List<Int> = emptyList()
    )

    data class UiState(
        val isLoading: Boolean = true,
        val title: String = "",
        val chapterCount: Int = 0,
        val readOnly: Boolean = false,
        val manifestError: String? = null,
        val narratorVoiceId: String = "",
        val dialogueVoiceId: String? = null,
        val narratorSpeed: Float = DEFAULT_TTS_SPEED,
        val dialogueSpeed: Float = DEFAULT_TTS_SPEED,
        val engines: List<String> = emptyList(),
        val selectedEngine: String = "",
        val engineVoices: List<TtsVoice> = emptyList(),
        val pendingEngine: String? = null,
        val mappingPreview: EngineSwitchPreview? = null,
        val pendingWarning: EngineSpeedInfo? = null,
        val narrationSample: String? = null,
        val dialogueSample: String? = null,
        val alternateNarratorVoiceId: String? = null,
        val alternateDialogueVoiceId: String? = null,
        val previewingRole: TtsRole? = null,
        val previewingAlternate: Boolean = false,
        val hasChanges: Boolean = false,
        val showImpact: Boolean = false,
        val impact: ImpactView? = null,
        val jobState: RenderJobState? = null,
        val error: String? = null,
        val notice: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var previewJob: Job? = null
    private val noSleep: (Long) -> Unit = {}

    private var original: BookVoices? = null
    private var edited: BookVoices? = null
    private var inputs: List<StaleChapterInput> = emptyList()
    private var readingChapter: Int = 0
    private var currentJob: RenderJob? = null

    init {
        reload()
    }

    fun clear() {
        previewJob?.cancel()
        scope.cancel()
        audio.release()
    }

    /** Re-reads the manifest, samples, staleness inputs and job file. */
    fun refresh() {
        reload()
    }

    /** Narrator or dialogue voice change (refused on read-only books). */
    fun selectVoice(role: TtsRole, voiceId: String) {
        val current = edited ?: return
        if (current.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        val voice = TtsVoice.parse(voiceId) ?: return
        if (registry.engineFor(voice.id) == null) return
        val updated = if (role == TtsRole.Narrator) {
            current.withNarratorVoice(voice.id)
        } else {
            current.withDialogueVoice(voice.id)
        }.getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return
        }
        edited = updated
        syncEdits(clearImpact = true)
    }

    /** Clears the dialogue voice to same-as-narrator (single role). */
    fun clearDialogueVoice() {
        val current = edited ?: return
        if (current.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        edited = current.withDialogueVoice(null).getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return
        }
        syncEdits(clearImpact = true)
    }

    /** Per-role speed stepper (clamped, refused on read-only books). */
    fun setSpeed(role: TtsRole, speed: Float) {
        val current = edited ?: return
        edited = current.withSpeed(role, speed).getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return
        }
        syncEdits(clearImpact = true)
    }

    /**
     * Stages an engine switch: the mapping preview plus the slow-engine
     * warning show before confirmation. Unknown namespaces are ignored.
     */
    fun selectEngine(namespace: String) {
        val current = edited ?: return
        if (current.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        if (namespace !in registry.namespaces()) return
        val target = engineFor(namespace) ?: return
        val preview = current.previewEngineSwitch(target) ?: run {
            _state.value = _state.value.copy(
                error = "Engine \"$namespace\" offers no voices (cannot switch)"
            )
            return
        }
        _state.value = _state.value.copy(
            pendingEngine = namespace,
            mappingPreview = preview,
            pendingWarning = EngineBenchmark.slowWarningFor(namespace),
            error = null
        )
    }

    /** Confirms the staged engine switch (both roles remapped). */
    fun confirmEngineSwitch() {
        val current = edited ?: return
        val pending = _state.value.pendingEngine ?: return
        if (current.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        val target = engineFor(pending) ?: run {
            _state.value = _state.value.copy(pendingEngine = null, mappingPreview = null)
            return
        }
        val (switched, preview) = current.switchEngine(target).getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return
        }
        edited = switched
        _state.value = _state.value.copy(
            pendingEngine = null,
            pendingWarning = null,
            mappingPreview = preview,
            error = null
        )
        syncEdits(clearImpact = true)
    }

    /** Drops the staged engine switch without changing voices. */
    fun cancelEngineSwitch() {
        _state.value = _state.value.copy(
            pendingEngine = null,
            mappingPreview = null,
            pendingWarning = null
        )
    }

    /** A/B compare voice per role (null clears back to single preview). */
    fun setAlternateVoice(role: TtsRole, voiceId: String?) {
        if (voiceId != null) {
            val voice = TtsVoice.parse(voiceId) ?: return
            if (registry.engineFor(voice.id) == null) return
        }
        _state.value = if (role == TtsRole.Narrator) {
            _state.value.copy(alternateNarratorVoiceId = voiceId)
        } else {
            _state.value.copy(alternateDialogueVoiceId = voiceId)
        }
    }

    /**
     * Auditions the role sample (narration sample for narrator, dialogue
     * sample for dialogue) with the current or the A/B alternate voice.
     * Disabled on read-only books (see file KDoc).
     */
    fun preview(role: TtsRole, alternate: Boolean = false) {
        val current = edited
        if (current != null && current.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        previewJob?.cancel()
        audio.stop()
        _state.value = _state.value.copy(
            previewingRole = role,
            previewingAlternate = alternate,
            error = null
        )
        previewJob = scope.launch {
            try {
                val snapshot = _state.value
                val voiceId = if (alternate) {
                    if (role == TtsRole.Narrator) {
                        snapshot.alternateNarratorVoiceId
                    } else {
                        snapshot.alternateDialogueVoiceId
                    } ?: throw IllegalArgumentException("no compare voice chosen for $role yet")
                } else {
                    if (role == TtsRole.Narrator) {
                        snapshot.narratorVoiceId
                    } else {
                        snapshot.dialogueVoiceId ?: snapshot.narratorVoiceId
                    }
                }
                val voice = TtsVoice.parse(voiceId)
                    ?: throw IllegalArgumentException("no voice chosen for $role yet")
                val engine = registry.engineFor(voice.id)
                    ?: throw IllegalArgumentException("engine for $voiceId unavailable")
                val text = if (role == TtsRole.Narrator) {
                    snapshot.narrationSample
                } else {
                    snapshot.dialogueSample ?: snapshot.narrationSample
                } ?: VoiceAuditionViewModel.AUDITION_TEXT
                val speed = if (role == TtsRole.Narrator) {
                    snapshot.narratorSpeed
                } else {
                    snapshot.dialogueSpeed
                }
                val audioOut = engine.synthesize(text, voice, speed)
                audio.play(audioOut)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = e.message ?: "preview failed")
            } finally {
                _state.value = _state.value.copy(previewingRole = null, previewingAlternate = false)
            }
        }
    }

    fun stop() {
        previewJob?.cancel()
        previewJob = null
        audio.stop()
        _state.value = _state.value.copy(previewingRole = null, previewingAlternate = false)
    }

    /**
     * Computes the impact dialog content under the edited voices and,
     * when a render job is active, pauses it plus recomputes the plan
     * (VS3 ask state) so the dialog shows added minus removed chapters.
     *
     * VS6: edited voices must be offered by the live registry first;
     * a missing engine, voice or model pack refuses here (naming the
     * piece plus the fix path) with no pause and no dialog.
     */
    fun requestApply() {
        val base = original ?: return
        val next = edited ?: return
        if (base.readOnly || next.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        if (next == base) {
            _state.value = _state.value.copy(error = "No voice changes to apply.")
            return
        }
        RerenderGuards.checkVoicesAvailable(next, registry).onFailure { e ->
            _state.value = _state.value.copy(error = e.message)
            return
        }
        val summary = RenderStaleness.summarizeChapters(inputs, next, versionOf)
        val activeJob = currentJob?.takeIf {
            it.state == RenderJobState.RUNNING ||
                it.state == RenderJobState.PAUSED ||
                it.state == RenderJobState.QUEUED ||
                it.state == RenderJobState.INTERRUPTED
        }
        var paused = false
        var added: List<Int> = emptyList()
        var removed: List<Int> = emptyList()
        if (activeJob != null) {
            val newOrdered = RerenderPlanner.select(
                chapterCount = inputs.size,
                readingChapter = readingChapter,
                mode = RerenderMode.STALE_ONLY,
                staleState = { pos -> staleAt(pos, next) }
            )
            val replan = RerenderPlanner.onVoiceChange(activeJob, newOrdered)
            currentJob = replan.pausedJob
            added = replan.added
            removed = replan.removed
            if (activeJob.state == RenderJobState.RUNNING) {
                onPauseRender()
                paused = true
            }
        }
        _state.value = _state.value.copy(
            showImpact = true,
            impact = ImpactView(
                staleChapters = summary.stale,
                staleAudioMs = summary.staleAudioMs,
                wallFastMs = summary.wallFastMs,
                wallSlowMs = summary.wallSlowMs,
                newBytes = summary.newBytes,
                swapBytes = summary.swapBytes,
                jobWasPaused = paused,
                added = added,
                removed = removed
            ),
            jobState = currentJob?.state,
            error = null
        )
    }

    /** Dismisses the impact dialog without applying. */
    fun dismissImpact() {
        _state.value = _state.value.copy(showImpact = false)
    }

    /**
     * Applies the dialog choice: NOW persists plus starts the stale-only
     * re-render reading-position forward first; LATER and KEEP only persist
     * with no start (the mixed-voice state stays legal per D-119, VS5 badges
     * show it; old audio keeps playing until a manual re-render).
     * KEEP and LATER are behavior-identical: no queue or schedule flag,
     * no planned job (device verdict 2026-10-09).
     *
     * VS6: voices re-validate before any persist (an engine removed
     * between dialog and confirm refuses with the fix path, manifest
     * untouched); NOW with stale audio pre-checks the swap storage
     * (needed vs free numbers, manifest untouched on refusal). LATER and
     * KEEP skip the storage check (the service re-checks at render time).
     * Unrendered books (no stale audio) persist with no start.
     */
    fun confirmApply(choice: ApplyChoice) {
        val base = original ?: return
        val next = edited ?: return
        if (base.readOnly || next.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        RerenderGuards.checkVoicesAvailable(next, registry).onFailure { e ->
            _state.value = _state.value.copy(error = e.message)
            return
        }
        val preSummary = RenderStaleness.summarizeChapters(inputs, next, versionOf)
        if (choice == ApplyChoice.NOW && preSummary.stale > 0 && preSummary.staleAudioMs > 0) {
            val free = try {
                freeBytes()
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            RerenderGuards.checkSwapStorage(free, preSummary.staleAudioMs).onFailure { e ->
                _state.value = _state.value.copy(error = e.message)
                return
            }
        }
        val raw = try {
            storage.readText(join(bundleDir, MANIFEST_FILE))
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "manifest unreadable (${e.message})")
            return
        }
        val updated = BookVoicePersist.writeBookVoices(raw, next).getOrElse {
            _state.value = _state.value.copy(error = it.message)
            return
        }
        try {
            storage.writeText(join(bundleDir, MANIFEST_FILE), updated)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "voices not saved (${e.message})")
            return
        }
        original = next
        onBookChanged()
        val stale = _state.value.impact?.staleChapters
            ?: RenderStaleness.summarizeChapters(inputs, next, versionOf).stale
        _state.value = _state.value.copy(showImpact = false, impact = null, error = null)
        if (choice == ApplyChoice.NOW && stale > 0) {
            onStartRerender(readingChapter, RerenderMode.STALE_ONLY)
            _state.value = _state.value.copy(notice = null)
        } else if (choice == ApplyChoice.NOW) {
            _state.value = _state.value.copy(notice = "Voices saved. Every chapter already matches.")
        } else {
            _state.value = _state.value.copy(notice = "Voices saved. Re-render later to use them.")
        }
        syncEdits(clearImpact = false)
        reloadJobOnly()
    }

    /**
     * Promotes the book choice to global defaults for new books (D-113).
     * The narrator role always promotes; the dialogue role promotes only
     * when set (an unset dialogue keeps the book single-role without
     * collapsing the global dialogue voice).
     */
    fun promoteToDefaults() {
        val next = edited ?: return
        if (next.readOnly) {
            _state.value = _state.value.copy(error = BookVoices.READ_ONLY_MESSAGE)
            return
        }
        globals.setVoiceId(TtsRole.Narrator, next.narratorVoiceId)
        globals.setSpeed(TtsRole.Narrator, next.narratorSpeed)
        if (!next.dialogueVoiceId.isNullOrBlank()) {
            globals.setVoiceId(TtsRole.Dialogue, next.resolvedDialogueVoiceId())
            globals.setSpeed(TtsRole.Dialogue, next.dialogueSpeed)
        }
        _state.value = _state.value.copy(notice = "Saved as default for new books.", error = null)
    }

    /** Dismisses the error line. */
    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Dismisses the notice line. */
    fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    private fun syncEdits(clearImpact: Boolean) {
        val base = original
        val next = edited
        val snapshot = _state.value
        val namespaces = registry.namespaces()
        val selected = selectedEngineFor(next, namespaces)
        _state.value = snapshot.copy(
            narratorVoiceId = next?.narratorVoiceId ?: "",
            dialogueVoiceId = next?.dialogueVoiceId,
            narratorSpeed = next?.narratorSpeed ?: DEFAULT_TTS_SPEED,
            dialogueSpeed = next?.dialogueSpeed ?: DEFAULT_TTS_SPEED,
            engines = namespaces,
            selectedEngine = selected,
            engineVoices = engineFor(selected)?.voices().orEmpty(),
            hasChanges = base != null && next != null && next != base,
            impact = if (clearImpact) null else snapshot.impact,
            showImpact = if (clearImpact) false else snapshot.showImpact,
            error = null
        )
    }

    private fun selectedEngineFor(voices: BookVoices?, namespaces: List<String>): String {
        val narratorEngine = voices?.narratorVoiceId
            ?.takeIf { it.isNotBlank() }
            ?.let { TtsVoice.parse(it)?.engine }
        if (narratorEngine != null && narratorEngine in namespaces) return narratorEngine
        val dialogueEngine = voices?.resolvedDialogueVoiceId()
            ?.takeIf { it.isNotBlank() }
            ?.let { TtsVoice.parse(it)?.engine }
        if (dialogueEngine != null && dialogueEngine in namespaces) return dialogueEngine
        return recommendEngine(namespaces)?.namespace ?: ""
    }

    private fun engineFor(namespace: String): TtsEngine? {
        if (namespace.isBlank()) return null
        val probe = registry.allVoices().firstOrNull { it.engine == namespace } ?: return null
        return registry.engineFor(probe.id)
    }

    private fun staleAt(pos: Int, voices: BookVoices): ChapterStaleState {
        val input = inputs.getOrNull(pos) ?: return ChapterStaleState.NOT_RENDERED
        return RenderStaleness.classifyChapter(
            durationMs = input.durationMs,
            storedJson = input.fingerprintJson,
            bookVoices = voices,
            versionOf = versionOf,
            usedRoles = RenderStaleness.usedRolesFor(input.hasNarrator, input.hasDialogue)
        )
    }

    private fun reload() {
        val keptAlternates = _state.value
        _state.value = keptAlternates.copy(isLoading = true)
        scope.launch {
            val rawManifest = try {
                storage.readText(join(bundleDir, MANIFEST_FILE))
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    manifestError = "manifest unreadable (${e.message})"
                )
                return@launch
            }
            val manifest = BundleParser.parseText(rawManifest).getOrElse {
                _state.value = _state.value.copy(
                    isLoading = false,
                    manifestError = "manifest unreadable (${it.message})"
                )
                return@launch
            }
            val bookVoices = BookVoices.read(manifest, globals)
            original = bookVoices
            edited = bookVoices
            val sorted = manifest.chapters.sortedBy { it.index }
            val built = ArrayList<StaleChapterInput>(sorted.size)
            var samples = BookVoiceSamples(narration = null, dialogue = null)
            var fallbackFirst: String? = null
            for (entry in sorted) {
                var hasDialogue = false
                var hasNarrator = true
                try {
                    val raw = storage.readText(join(bundleDir, entry.text))
                    val chapter = ChapterTextLoader.parse(entry.text, raw).getOrNull()
                    if (chapter != null) {
                        val roles = RenderStaleness.rolesUsedInChapter(chapter)
                        hasDialogue = "dialogue" in roles
                        hasNarrator = "narrator" in roles
                        samples = BookVoiceSamplePicker.accumulate(samples, chapter)
                        if (fallbackFirst == null) {
                            fallbackFirst = BookVoiceSamplePicker.firstSentenceIn(chapter)
                        }
                    }
                } catch (_: Exception) {
                }
                built.add(
                    StaleChapterInput(
                        index = entry.index,
                        durationMs = entry.durationMs,
                        fingerprintJson = entry.renderFingerprint,
                        hasDialogue = hasDialogue,
                        hasNarrator = hasNarrator
                    )
                )
            }
            val narration = samples.narration ?: fallbackFirst
            val saved = try {
                progress.load(bookId).getOrNull()
            } catch (_: Exception) {
                null
            }
            readingChapter = if (sorted.isEmpty()) {
                0
            } else {
                (saved?.chapterIndex ?: 0).coerceIn(0, sorted.size - 1)
            }
            val job = loadJob()
            currentJob = job
            val namespaces = registry.namespaces()
            val selected = selectedEngineFor(bookVoices, namespaces)
            _state.value = UiState(
                isLoading = false,
                title = manifest.title,
                chapterCount = sorted.size,
                readOnly = bookVoices.readOnly,
                narratorVoiceId = bookVoices.narratorVoiceId,
                dialogueVoiceId = bookVoices.dialogueVoiceId,
                narratorSpeed = bookVoices.narratorSpeed,
                dialogueSpeed = bookVoices.dialogueSpeed,
                engines = namespaces,
                selectedEngine = selected,
                engineVoices = engineFor(selected)?.voices().orEmpty(),
                narrationSample = narration,
                dialogueSample = samples.dialogue,
                alternateNarratorVoiceId = keptAlternates.alternateNarratorVoiceId,
                alternateDialogueVoiceId = keptAlternates.alternateDialogueVoiceId,
                hasChanges = false,
                jobState = job?.state
            )
            inputs = built
        }
    }

    private fun reloadJobOnly() {
        scope.launch {
            val job = loadJob()
            currentJob = job
            _state.value = _state.value.copy(jobState = job?.state)
        }
    }

    private fun loadJob(): RenderJob? {
        return try {
            RenderStateStore.load(bundleDir, fileIo, noSleep).getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun join(dir: String, rel: String): String =
        dir.trimEnd('/') + '/' + rel.trimStart('/')

    companion object {
        private const val MANIFEST_FILE = "manifest.json"
    }
}
