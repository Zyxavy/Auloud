package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS3: gain re-derivation on voice change (D-120).
 *
 * Pure JVM: fingerprints plus gain maps, no Android, no storage.
 */
class RerenderGainsTest {

    private fun fp(
        narratorVoice: String = "system:narr",
        dialogueVoice: String = "system:dial",
        narratorSpeed: Float = 1.0f,
        dialogueSpeed: Float = 1.0f,
        version: String = "v1"
    ) = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to narratorVoice, "dialogue" to dialogueVoice),
        speeds = mapOf("narrator" to narratorSpeed, "dialogue" to dialogueSpeed),
        engineVersions = mapOf("system" to version)
    )

    private fun narratorOnlyFp(voice: String = "system:narr") = RenderFingerprint(
        engine = "system",
        voices = mapOf("narrator" to voice),
        speeds = mapOf("narrator" to 1.0f),
        engineVersions = mapOf("system" to "v1")
    )

    @Test
    fun narratorVoiceChange_marksNarratorOnly() {
        val old = fp()
        val new = fp(narratorVoice = "system:other")
        assertEquals(setOf("narrator"), RerenderGains.changedRoles(old, new, setOf("narrator", "dialogue")))
        assertEquals(setOf("narrator"), RerenderGains.changedRoles(old, new, setOf("narrator")))
        assertEquals(emptySet<String>(), RerenderGains.changedRoles(old, new, setOf("dialogue")))
    }

    @Test
    fun engineChangeViaVoiceId_marksRole() {
        val old = fp(dialogueVoice = "system:dial")
        val new = RenderFingerprint(
            engine = "piper+system",
            voices = mapOf("narrator" to "system:narr", "dialogue" to "piper:dial"),
            speeds = mapOf("narrator" to 1.0f, "dialogue" to 1.0f),
            engineVersions = mapOf("system" to "v1", "piper" to "pack-3")
        )
        assertEquals(setOf("dialogue"), RerenderGains.changedRoles(old, new, setOf("narrator", "dialogue")))
    }

    @Test
    fun speedOnlyChange_marksNothing() {
        val old = fp(narratorSpeed = 1.0f)
        val new = fp(narratorSpeed = 1.5f)
        assertTrue(RerenderGains.changedRoles(old, new, setOf("narrator", "dialogue")).isEmpty())
    }

    @Test
    fun versionOnlyChange_marksNothing() {
        val old = fp(version = "v1")
        val new = fp(version = "v2")
        assertTrue(RerenderGains.changedRoles(old, new, setOf("narrator", "dialogue")).isEmpty())
    }

    @Test
    fun missingOld_meansEveryUsedRoleChanged() {
        val new = narratorOnlyFp()
        assertEquals(setOf("narrator"), RerenderGains.changedRoles(null, new, setOf("narrator")))
    }

    @Test
    fun missingStoredEntryForUsedRole_countsAsChanged() {
        val old = narratorOnlyFp()
        val new = fp()
        assertEquals(setOf("dialogue"), RerenderGains.changedRoles(old, new, setOf("dialogue")))
    }

    @Test
    fun merge_replacesChangedKeepsRest() {
        val old = mapOf("narrator" to 0.0, "dialogue" to 1.0)
        val fresh = mapOf("narrator" to 2.5, "dialogue" to 9.0)
        val merged = RerenderGains.mergeGains(old, fresh, setOf("narrator"))
        assertEquals(2.5, merged["narrator"] ?: Double.NaN, 0.0)
        assertEquals(1.0, merged["dialogue"] ?: Double.NaN, 0.0)
    }

    @Test
    fun merge_emptyChanged_keepsOld() {
        val old = mapOf("narrator" to 0.0)
        val merged = RerenderGains.mergeGains(old, mapOf("narrator" to 5.0), emptySet())
        assertEquals(0.0, merged["narrator"] ?: Double.NaN, 0.0)
    }

    @Test
    fun merge_noOld_returnsFresh() {
        val fresh = mapOf("narrator" to 1.5)
        assertEquals(fresh, RerenderGains.mergeGains(null, fresh, setOf("narrator")))
        assertEquals(fresh, RerenderGains.mergeGains(emptyMap(), fresh, setOf("narrator")))
    }
}
