package app.auloud.player.render

import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * VS3: gain re-derivation on voice change (D-120, builds on D-095/D-106).
 *
 * First-render rule (RN5/RN7, unchanged): one book gain per role derived
 * from the first rendered chapter ([BookGains.derive]), stored as manifest
 * `gain_db`, later chapters apply it plus an attenuate-only cap.
 * Already rendered chapters keep baked loudness.
 *
 * Re-render rule (this file): when a role voice or engine changes, that
 * role book gain is re-derived from the first chapter rendered with the
 * new voice. Roles whose voice did not change keep the old gain.
 * Already rendered chapters keep their baked audio (only new chapters
 * use the merged gains, and only the manifest `gain_db` for changed
 * roles is updated).
 *
 * Voice change means the namespaced voice id differs for a used role
 * (the id carries the engine namespace, so an engine switch is covered;
 * speed-only or version-only changes never re-derive here).
 *
 * Pure Kotlin, API 24 safe, no new dependency.
 */
object RerenderGains {

    /**
     * Used roles whose voice id differs between [old] and [new].
     * Only [usedRoles] compare; extra stored roles for unused parts are
     * ignored. A missing stored entry for a used role counts as changed
     * (unknown old, re-derive rather than pin to a stale reference).
     * Null [old] (no stored fingerprint) means every used role changed.
     */
    fun changedRoles(
        old: RenderFingerprint?,
        new: RenderFingerprint,
        usedRoles: Set<String>
    ): Set<String> {
        val out = LinkedHashSet<String>()
        for (role in usedRoles.sorted()) {
            if (role != SPEAKER_NARRATOR && role != SPEAKER_DIALOGUE) continue
            val newVoice = new.voices[role] ?: continue
            val oldVoice = old?.voices?.get(role)
            if (oldVoice == null || oldVoice != newVoice) {
                out.add(role)
            }
        }
        return out
    }

    /**
     * Merges freshly derived gains ([newDerivedDb], from
     * [BookGains.derive] on the first re-rendered chapter) into the
     * stored manifest gains ([oldGainDb]).
     *
     * Only [changedRoles] are replaced (and only when the new map has a
     * value for them); every other stored role keeps its old number.
     * Null or empty [oldGainDb] returns the new map as is (no reference
     * to preserve). Empty [changedRoles] returns the old map as is.
     */
    fun mergeGains(
        oldGainDb: Map<String, Double>?,
        newDerivedDb: Map<String, Double>,
        changedRoles: Set<String>
    ): Map<String, Double> {
        if (oldGainDb.isNullOrEmpty()) return newDerivedDb.toSortedMap()
        if (changedRoles.isEmpty()) return oldGainDb.toSortedMap()
        val merged = oldGainDb.toSortedMap().toMutableMap()
        for (role in changedRoles.sorted()) {
            val fresh = newDerivedDb[role] ?: continue
            if (!fresh.isFinite()) continue
            merged[role] = fresh
        }
        return merged.toSortedMap()
    }

    /**
     * Reads a manifest `gain_db` JsonObject into a plain map (null when
     * absent or malformed; malformed entries are skipped, never thrown).
     */
    fun gainDbFromJson(gainDb: JsonElement?): Map<String, Double>? {
        val obj = gainDb as? JsonObject ?: return null
        if (obj.isEmpty()) return null
        val out = LinkedHashMap<String, Double>()
        for ((role, value) in obj) {
            if (role != SPEAKER_NARRATOR && role != SPEAKER_DIALOGUE) continue
            val primitive = value as? JsonPrimitive ?: continue
            if (primitive.isString) continue
            val number = primitive.doubleOrNull?.takeIf { it.isFinite() } ?: continue
            out[role] = number
        }
        return if (out.isEmpty()) null else out.toSortedMap()
    }
}
