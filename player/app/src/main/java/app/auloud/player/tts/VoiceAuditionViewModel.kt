package app.auloud.player.tts

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
 * PW8: voice settings + audition state (plain class like [ReaderViewModel]).
 *
 * Owns its coroutines (a [SupervisorJob] over [dispatcher]); call
 * [clear] when the screen is disposed. The store is the source of
 * truth: every change persists immediately, and the state mirrors it,
 * so killing the app mid-audition loses nothing. Preview synthesizes
 * a fixed sentence with the role's voice + speed and plays it on the
 * audition player (never the book session); a new preview or [stop]
 * cancels the running one.
 *
 * API 24 safe: coroutines only (framework hides behind the seams).
 */
class VoiceAuditionViewModel(
    private val registry: EngineRegistry,
    private val store: TtsVoiceStore,
    private val audio: AudioPlayer,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    data class UiState(
        val engines: List<String> = emptyList(),
        val selectedEngine: String = "",
        val recommendation: EngineRecommendation? = null,
        val engineVoices: List<TtsVoice> = emptyList(),
        val narratorVoiceId: String = "",
        val dialogueVoiceId: String = "",
        val narratorSpeed: Float = DEFAULT_TTS_SPEED,
        val dialogueSpeed: Float = DEFAULT_TTS_SPEED,
        val previewingRole: TtsRole? = null,
        val error: String? = null,
        /** ST6: level-match run in progress. */
        val calibrating: Boolean = false,
        /** ST6: one-line result of the last match (null when never run). */
        val levelNote: String? = null,
        /** ST6: true when both roles use system voices (the only matchable pair). */
        val canCalibrateLevels: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var previewJob: Job? = null
    private var calibrateJob: Job? = null

    init {
        refresh()
    }

    /** Re-read registry + store (engines appear after TTS init). */
    fun refresh() {
        val namespaces = registry.namespaces()
        val recommendation = recommendEngine(namespaces)
        val selected = storeSelectedEngine(namespaces)
        val selectedEngine = registry.namespaces().firstOrNull { it == selected }?.let {
            registryEngine(it)
        }
        _state.value = UiState(
            engines = namespaces,
            selectedEngine = selected,
            recommendation = recommendation,
            engineVoices = selectedEngine?.voices().orEmpty(),
            narratorVoiceId = store.voiceId(TtsRole.Narrator),
            dialogueVoiceId = store.voiceId(TtsRole.Dialogue),
            narratorSpeed = store.speed(TtsRole.Narrator),
            dialogueSpeed = store.speed(TtsRole.Dialogue),
            previewingRole = _state.value.previewingRole,
            error = null,
            calibrating = _state.value.calibrating,
            levelNote = _state.value.levelNote,
            canCalibrateLevels = canCalibrateLevels()
        )
    }

    /** ST6: both roles on system voices (streaming is system-only). */
    private fun canCalibrateLevels(): Boolean =
        isSystemVoiceId(store.voiceId(TtsRole.Narrator)) &&
            isSystemVoiceId(store.voiceId(TtsRole.Dialogue))

    private fun isSystemVoiceId(voiceId: String): Boolean =
        voiceId.startsWith(SystemTtsAdapter.SYSTEM_NAMESPACE + ":") &&
            voiceId.length > SystemTtsAdapter.SYSTEM_NAMESPACE.length + 1

    private fun storeSelectedEngine(namespaces: List<String>): String {
        val narratorEngine = TtsVoice.parse(store.voiceId(TtsRole.Narrator))?.engine
        if (narratorEngine != null && narratorEngine in namespaces) return narratorEngine
        val dialogueEngine = TtsVoice.parse(store.voiceId(TtsRole.Dialogue))?.engine
        if (dialogueEngine != null && dialogueEngine in namespaces) return dialogueEngine
        return recommendEngine(namespaces)?.namespace ?: ""
    }

    /** Switch engine: remap both roles, persist, refresh. */
    fun selectEngine(namespace: String) {
        if (namespace !in registry.namespaces()) return
        val engineNow = registryEngine(namespace) ?: return
        val currentNarrator = TtsVoice.parse(store.voiceId(TtsRole.Narrator))
        val currentDialogue = TtsVoice.parse(store.voiceId(TtsRole.Dialogue))
        VoiceMapper.mapVoice(currentNarrator, engineNow, TtsRole.Narrator)?.let {
            store.setVoiceId(TtsRole.Narrator, it.id)
        }
        VoiceMapper.mapVoice(currentDialogue, engineNow, TtsRole.Dialogue)?.let {
            store.setVoiceId(TtsRole.Dialogue, it.id)
        }
        refresh()
    }

    private fun registryEngine(namespace: String): TtsEngine? {
        val probe = registry.allVoices().firstOrNull { it.engine == namespace } ?: return null
        return registry.engineFor(probe.id)
    }

    fun selectVoice(role: TtsRole, voiceId: String) {
        val voice = TtsVoice.parse(voiceId) ?: return
        if (registry.engineFor(voice.id) == null) return
        store.setVoiceId(role, voice.id)
        refresh()
    }

    fun setSpeed(role: TtsRole, speed: Float) {
        store.setSpeed(role, speed)
        refresh()
    }

    fun preview(role: TtsRole) {
        previewJob?.cancel()
        audio.stop()
        _state.value = _state.value.copy(previewingRole = role, error = null)
        previewJob = scope.launch {
            try {
                val voiceId = store.voiceId(role)
                val voice = TtsVoice.parse(voiceId)
                    ?: throw IllegalArgumentException("no voice chosen for $role yet")
                val engine = registry.engineFor(voice.id)
                    ?: throw IllegalArgumentException("engine for $voiceId unavailable")
                val audioOut = engine.synthesize(AUDITION_TEXT, voice, store.speed(role))
                audio.play(audioOut)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = e.message ?: "preview failed")
            } finally {
                _state.value = _state.value.copy(previewingRole = null)
            }
        }
    }

    fun stop() {
        previewJob?.cancel()
        previewJob = null
        audio.stop()
        _state.value = _state.value.copy(previewingRole = null)
    }

    /**
     * ST6: match the two role levels for live streaming: synthesize the
     * standard sentence per role, store the relative volumes keyed by
     * voice id. System pair only (anything else refuses with the reason
     * in [UiState.error]); a voice change afterwards needs a re-run
     * (the stream plays full until the pair matches again).
     */
    fun calibrateLevels() {
        calibrateJob?.cancel()
        _state.value = _state.value.copy(calibrating = true, levelNote = null, error = null)
        calibrateJob = scope.launch {
            try {
                val narrator = systemVoiceOrThrow(TtsRole.Narrator)
                val dialogue = systemVoiceOrThrow(TtsRole.Dialogue)
                val engine = registry.engineFor(narrator.id)
                    ?: throw IllegalStateException("system engine unavailable")
                val volumes = StreamLeveling.calibrate(engine, narrator, dialogue)
                store.setStreamVolume(TtsRole.Narrator, volumes[TtsRole.Narrator] ?: 1.0f)
                store.setStreamVolumeVoice(TtsRole.Narrator, narrator.id)
                store.setStreamVolume(TtsRole.Dialogue, volumes[TtsRole.Dialogue] ?: 1.0f)
                store.setStreamVolumeVoice(TtsRole.Dialogue, dialogue.id)
                _state.value = _state.value.copy(
                    calibrating = false,
                    levelNote = StreamLeveling.describe(volumes)
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    calibrating = false,
                    error = e.message ?: "level match failed"
                )
            }
        }
    }

    private fun systemVoiceOrThrow(role: TtsRole): TtsVoice {
        val voice = TtsVoice.parse(store.voiceId(role))
        if (voice == null || !isSystemVoiceId(voice.id)) {
            throw IllegalArgumentException("level matching needs two system voices")
        }
        return voice
    }

    fun clear() {
        previewJob?.cancel()
        calibrateJob?.cancel()
        scope.cancel()
        audio.release()
    }

    companion object {
        /** Fixed audition line (self-made; Scribe uses its own sentence). */
        const val AUDITION_TEXT =
            "The old lighthouse keeper whispered a secret to the curious child."
    }
}
