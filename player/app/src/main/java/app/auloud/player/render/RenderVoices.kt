package app.auloud.player.render

import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.TtsEngine
import app.auloud.player.tts.TtsRole
import app.auloud.player.tts.TtsVoice
import app.auloud.player.tts.TtsVoiceStore
import app.auloud.player.tts.clampTtsSpeed

/**
 * RN4: narrator plus dialogue engine/voice binding for one render.
 *
 * The Slice 8 registry owns engines and the role store owns settings; this
 * type is the resolved pair the spool passes synthesize with. Speeds are
 * clamped through [clampTtsSpeed] (the store already clamps on read, this
 * covers hand-rolled stores in tests the same way).
 */
data class RoleBinding(
    val role: TtsRole,
    val engine: TtsEngine,
    val voice: TtsVoice,
    val speed: Float
)

/**
 * RN4: resolved voices for a chapter render: both role bindings plus the
 * fingerprint the spool files and (via RN7) the manifest entry carry.
 */
data class ResolvedRenderVoices(
    val narrator: RoleBinding,
    val dialogue: RoleBinding,
    val fingerprint: RenderFingerprint
)

/**
 * RN4: up-front voice resolution (Slice 10 plan RN4, D-091).
 *
 * Reads how engines and voices resolve today (Slice 8 seam: voice ids are
 * namespaced, the engine is implied by the id, the registry routes on the
 * namespace prefix) and validates availability BEFORE any synthesis, so a
 * missing engine or model pack fails fast with a message naming what is
 * missing instead of dying mid-chapter:
 *
 * - blank role setting: names the role (`narrator voice is not set ...`).
 * - id without a namespace: names the voice id.
 * - namespace with no engine in the registry: names the voice id and the
 *   missing engine (`needs engine "piper" (engine not installed)`).
 * - engine present but the exact voice id not offered (model pack missing,
 *   voice removed, System TTS not ready): names the voice id and the engine
 *   (`is not available from engine "piper" (missing model pack or voice)`).
 * - blank engine version from [versionOf]: names the engine namespace.
 *
 * Engine versions come from [versionOf] (one string per namespace used).
 * [TtsEngine] exposes no version, so the caller supplies the strings the
 * synth cache keys on; RN8 wires the real Tab E strings (RN10 records
 * them), tests supply fakes. A blank version fails: a placeholder would
 * validate but never invalidate after an engine update, which is exactly
 * the correctness bug D-100 warns about.
 *
 * Pure Kotlin (registry, store and version lambda are all JVM-fakeable),
 * API 24 safe, no storage access.
 */
object RenderVoices {

    /**
     * Resolves both roles or fails with a message naming the missing piece.
     * Success carries bindings plus the fingerprint built from them.
     */
    fun resolve(
        registry: EngineRegistry,
        store: TtsVoiceStore,
        versionOf: (namespace: String) -> String? = { null }
    ): Result<ResolvedRenderVoices> {
        val narrator = bindRole(TtsRole.Narrator, SPEAKER_NARRATOR, registry, store)
            .getOrElse { return Result.failure(it) }
        val dialogue = bindRole(TtsRole.Dialogue, SPEAKER_DIALOGUE, registry, store)
            .getOrElse { return Result.failure(it) }
        val namespaces = sortedSetOf(
            narrator.engine.namespace,
            dialogue.engine.namespace
        )
        val versions = LinkedHashMap<String, String>()
        for (namespace in namespaces) {
            val version = versionOf(namespace)
            if (version.isNullOrBlank()) {
                return Result.failure(
                    IllegalStateException(
                        "engine \"$namespace\" has no version string " +
                            "(cannot fingerprint the render)"
                    )
                )
            }
            versions[namespace] = version
        }
        val fingerprint = RenderFingerprint(
            engine = RenderFingerprint.combineEngine(
                narrator.engine.namespace,
                dialogue.engine.namespace
            ),
            voices = mapOf(
                SPEAKER_NARRATOR to narrator.voice.id,
                SPEAKER_DIALOGUE to dialogue.voice.id
            ),
            speeds = mapOf(
                SPEAKER_NARRATOR to narrator.speed,
                SPEAKER_DIALOGUE to dialogue.speed
            ),
            engineVersions = versions
        )
        return Result.success(ResolvedRenderVoices(narrator, dialogue, fingerprint))
    }

    private fun bindRole(
        role: TtsRole,
        roleKey: String,
        registry: EngineRegistry,
        store: TtsVoiceStore
    ): Result<RoleBinding> {
        val id = store.voiceId(role)
        if (id.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "$roleKey voice is not set (choose one in voice settings)"
                )
            )
        }
        val parsed = TtsVoice.parse(id)
            ?: return Result.failure(
                IllegalStateException(
                    "$roleKey voice \"$id\" has no engine namespace " +
                        "(expected \"engine:voice\")"
                )
            )
        val engine = registry.engineFor(id)
            ?: return Result.failure(
                IllegalStateException(
                    "$roleKey voice \"$id\" needs engine \"${parsed.engine}\" " +
                        "(engine not installed)"
                )
            )
        val voice = engine.voices().firstOrNull { it.id == id }
            ?: return Result.failure(
                IllegalStateException(
                    "$roleKey voice \"$id\" is not available from " +
                        "engine \"${parsed.engine}\" (missing model pack or voice)"
                )
            )
        return Result.success(
            RoleBinding(
                role = role,
                engine = engine,
                voice = voice,
                speed = clampTtsSpeed(store.speed(role))
            )
        )
    }
}
