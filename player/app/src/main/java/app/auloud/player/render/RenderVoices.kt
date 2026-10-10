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
 *
 * Kept for the both-role render path (service plus spool still use both
 * roles in VS2; VS3 wires narrowing). Single-role renders use
 * [NarrowedResolvedVoices] via [RenderVoices.resolveForRoles].
 */
data class ResolvedRenderVoices(
    val narrator: RoleBinding,
    val dialogue: RoleBinding,
    val fingerprint: RenderFingerprint
)

/**
 * VS2 (D-114): resolved voices narrowed to the roles a chapter uses.
 *
 * `bindings` holds exactly the used roles (one or both of `narrator`
 * plus `dialogue`); `fingerprint` carries the same role set, so a
 * dialogue-free chapter fingerprints narrator only and never invalidates
 * on a dialogue-only voice change. Old both-role fingerprints still parse
 * and compare as stale at worst (see `RenderStaleness`).
 */
data class NarrowedResolvedVoices(
    val bindings: Map<String, RoleBinding>,
    val fingerprint: RenderFingerprint
) {
    val narrator: RoleBinding? get() = bindings[SPEAKER_NARRATOR]
    val dialogue: RoleBinding? get() = bindings[SPEAKER_DIALOGUE]
}

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
     *
     * VS2: delegates to [resolveForRoles] with both roles; the service
     * keeps calling this (both-role behavior unchanged) until VS3 wires
     * per-chapter narrowing.
     */
    fun resolve(
        registry: EngineRegistry,
        store: TtsVoiceStore,
        versionOf: (namespace: String) -> String? = { null }
    ): Result<ResolvedRenderVoices> {
        val narrowed = resolveForRoles(
            registry = registry,
            store = store,
            versionOf = versionOf,
            roles = setOf(SPEAKER_NARRATOR, SPEAKER_DIALOGUE)
        ).getOrElse { return Result.failure(it) }
        val narrator = narrowed.bindings[SPEAKER_NARRATOR]
            ?: return Result.failure(
                IllegalStateException("narrator voice is not set (choose one in voice settings)")
            )
        val dialogue = narrowed.bindings[SPEAKER_DIALOGUE]
            ?: return Result.failure(
                IllegalStateException("dialogue voice is not set (choose one in voice settings)")
            )
        return Result.success(ResolvedRenderVoices(narrator, dialogue, narrowed.fingerprint))
    }

    /**
     * VS2 (D-114): resolves exactly [roles] (one or both of `narrator`
     * plus `dialogue`) or fails naming the missing piece.
     *
     * Only the used roles are bound and fingerprinted: a dialogue-free
     * chapter resolves narrator only, so a blank or missing dialogue
     * setting never fails it and the fingerprint carries narrator only.
     * Versions cover only the engine namespaces used. An empty set, an
     * unknown role, or a blank version fails; callers treat failure as
     * stale (never current, never a crash).
     */
    fun resolveForRoles(
        registry: EngineRegistry,
        store: TtsVoiceStore,
        versionOf: (namespace: String) -> String? = { null },
        roles: Set<String>
    ): Result<NarrowedResolvedVoices> {
        val allowed = setOf(SPEAKER_NARRATOR, SPEAKER_DIALOGUE)
        if (roles.isEmpty()) {
            return Result.failure(
                IllegalStateException("no voice roles requested (need narrator and/or dialogue)")
            )
        }
        for (role in roles) {
            if (role !in allowed) {
                return Result.failure(
                    IllegalStateException("unknown voice role \"$role\" (need narrator and/or dialogue)")
                )
            }
        }
        val bindings = LinkedHashMap<String, RoleBinding>()
        for (role in roles.sorted()) {
            val ttsRole = if (role == SPEAKER_NARRATOR) TtsRole.Narrator else TtsRole.Dialogue
            val bound = bindRole(ttsRole, role, registry, store)
                .getOrElse { return Result.failure(it) }
            bindings[role] = bound
        }
        val namespaces = bindings.values.map { it.engine.namespace }.toSortedSet()
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
        val engineField = if (namespaces.size == 1) {
            namespaces.first()
        } else {
            namespaces.joinToString("+")
        }
        val fingerprint = RenderFingerprint(
            engine = engineField,
            voices = bindings.mapValues { it.value.voice.id },
            speeds = bindings.mapValues { it.value.speed },
            engineVersions = versions
        )
        return Result.success(NarrowedResolvedVoices(bindings, fingerprint))
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
