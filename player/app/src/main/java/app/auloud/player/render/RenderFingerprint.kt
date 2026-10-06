package app.auloud.player.render

import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * RN4: per-chapter render fingerprint (Slice 10, D-100 shape).
 *
 * The fingerprint is the voice-batched spool key one level up from Scribe's
 * sentence cache: Scribe keys cached audio on (text, voice, speed, pitch,
 * engine version); the chapter fingerprint keys spool files and the manifest
 * entry on (engine, voice ids, speeds, engine versions). Any change flips
 * the key, so a voice change invalidates spool files (RN4) and Slice 11 can
 * mark the chapter stale without re-rendering to find out.
 *
 * Shape matches spec 2.0 part 2 section 3 exactly
 * (`render_fingerprint: {engine, voices, speeds, engine_versions}`), so RN7
 * writes [toJsonObject] into the manifest verbatim instead of rebuilding
 * the object. `voices` holds the full namespaced voice ids from settings
 * (for example `system:en-us-x-sfg#female`), `speeds` the clamped per-role
 * multipliers, `engineVersions` one non-blank version string per engine
 * namespace used. Readers ignore all of it (loudness and timings are baked
 * into the audio); absent means unknown, never up to date.
 *
 * The `engine` field is the single namespace when both roles share one
 * engine (the common case) and the sorted namespaces joined with `+`
 * (for example `piper+system`) when they differ. The validator only needs
 * a non-blank string, so mixed-engine chapters stay spec-valid while still
 * invalidating on any engine change.
 *
 * Spool file names carry only [fileTag] (first 8 hex of the SHA-256 of
 * [canonicalString]), never the raw fingerprint: the full object lives in
 * the per-chapter spool index, where RN5 reads it for assembly. Speeds hash
 * by raw float bits ([Float.toBits]), so `1.0f` from settings and from the
 * index compare equal without decimal formatting drift.
 *
 * API 24 safe: pure Kotlin plus `java.security` (present since API 1) and
 * kotlinx.serialization. No `java.time`, no Android types.
 */
data class RenderFingerprint(
    val engine: String,
    val voices: Map<String, String>,
    val speeds: Map<String, Float>,
    val engineVersions: Map<String, String>
) {

    /**
     * Manifest-ready JSON in the spec 2.0 part 2 shape. Maps sort by role
     * and namespace so the bytes are deterministic for a given value.
     */
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("engine", engine)
        putJsonObject("voices") {
            for ((role, voice) in voices.toSortedMap()) put(role, voice)
        }
        putJsonObject("speeds") {
            for ((role, speed) in speeds.toSortedMap()) {
                put(role, JsonPrimitive(speed.toDouble()))
            }
        }
        putJsonObject("engine_versions") {
            for ((namespace, version) in engineVersions.toSortedMap()) {
                put(namespace, version)
            }
        }
    }

    /**
     * Stable canonical string for hashing and equality across processes.
     * Speeds use raw float bits; everything else sorts by key.
     */
    fun canonicalString(): String {
        val body = StringBuilder(engine).append('\n')
        for ((role, voice) in voices.toSortedMap()) {
            body.append(role).append('=').append(voice).append('\n')
        }
        for ((role, speed) in speeds.toSortedMap()) {
            body.append(role).append('=').append(speed.toBits().toString(16)).append('\n')
        }
        for ((namespace, version) in engineVersions.toSortedMap()) {
            body.append(namespace).append('=').append(version).append('\n')
        }
        return body.toString()
    }

    /** First 8 hex chars of SHA-256 over [canonicalString] (file-name tag). */
    fun fileTag(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(canonicalString().toByteArray(Charsets.UTF_8))
        return hash.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {

        /**
         * The `engine` field for a narrator/dialogue engine pair: the one
         * namespace when both roles share it, else the sorted pair joined
         * with `+` (spec-valid non-blank string either way).
         */
        fun combineEngine(narratorNamespace: String, dialogueNamespace: String): String =
            if (narratorNamespace == dialogueNamespace) {
                narratorNamespace
            } else {
                sortedSetOf(narratorNamespace, dialogueNamespace).joinToString("+")
            }

        /**
         * Parses a fingerprint back from JSON (spool index reads). Returns
         * null on any malformed shape instead of throwing: a corrupt index
         * means "fingerprint unknown", which invalidates the spool, never a
         * crash. Requires both reserved roles (mirrors the validator, which
         * needs narrator plus dialogue with non-blank voices).
         */
        fun fromJsonObject(obj: JsonObject): RenderFingerprint? {
            val engine = (obj["engine"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() } ?: return null
            val voicesObj = obj["voices"] as? JsonObject ?: return null
            val voices = LinkedHashMap<String, String>()
            for (role in listOf(SPEAKER_NARRATOR, SPEAKER_DIALOGUE)) {
                val voice = (voicesObj[role] as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() } ?: return null
                voices[role] = voice
            }
            val speedsObj = obj["speeds"] as? JsonObject ?: return null
            val speeds = LinkedHashMap<String, Float>()
            for (role in listOf(SPEAKER_NARRATOR, SPEAKER_DIALOGUE)) {
                val speed = (speedsObj[role] as? JsonPrimitive)
                    ?.takeIf { !it.isString }?.doubleOrNull
                    ?.takeIf { it.isFinite() && it > 0.0 } ?: return null
                speeds[role] = speed.toFloat()
            }
            val versionsRaw = obj["engine_versions"] as? JsonObject ?: return null
            if (versionsRaw.isEmpty()) return null
            val versions = LinkedHashMap<String, String>()
            for ((namespace, value) in versionsRaw) {
                val version = (value as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotBlank() } ?: return null
                if (namespace.isBlank()) return null
                versions[namespace] = version
            }
            return RenderFingerprint(
                engine = engine,
                voices = voices,
                speeds = speeds,
                engineVersions = versions
            )
        }
    }
}
