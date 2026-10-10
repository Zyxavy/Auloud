package app.auloud.player.render

import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.TtsRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN4: [RenderVoices] resolution tests (pure JVM, fake engines plus store).
 */
class RenderVoicesTest {

    private fun engine(namespace: String, vararg voiceIds: String) =
        ScriptedSpoolEngine(namespace, voiceIds.toList())

    private fun resolve(
        engines: List<ScriptedSpoolEngine>,
        narratorId: String,
        dialogueId: String,
        narratorSpeed: Float = 1.0f,
        dialogueSpeed: Float = 1.05f,
        versions: Map<String, String> = mapOf("system" to "tab-e-1", "piper" to "pack-3")
    ) = RenderVoices.resolve(
        EngineRegistry(engines),
        MemRenderVoiceStore(narratorId, dialogueId, narratorSpeed, dialogueSpeed),
        versionOf = { versions[it] }
    )

    @Test
    fun resolve_okBuildsFingerprint() {
        val outcome = resolve(
            listOf(engine("system", "system:narr", "system:dial")),
            "system:narr", "system:dial"
        )
        assertTrue(outcome.isSuccess)
        val resolved = outcome.getOrThrow()
        assertEquals("system", resolved.fingerprint.engine)
        assertEquals("system:narr", resolved.fingerprint.voices["narrator"])
        assertEquals("system:dial", resolved.fingerprint.voices["dialogue"])
        assertEquals(1.0f, resolved.fingerprint.speeds["narrator"])
        assertEquals(1.05f, resolved.fingerprint.speeds["dialogue"])
        assertEquals("tab-e-1", resolved.fingerprint.engineVersions["system"])
        assertEquals(TtsRole.Narrator, resolved.narrator.role)
        assertEquals(TtsRole.Dialogue, resolved.dialogue.role)
    }

    @Test
    fun resolve_mixedEnginesJoinsEngineField() {
        val outcome = resolve(
            listOf(
                engine("system", "system:narr"),
                engine("piper", "piper:dial")
            ),
            "system:narr", "piper:dial"
        )
        assertTrue(outcome.isSuccess)
        val resolved = outcome.getOrThrow()
        assertEquals("piper+system", resolved.fingerprint.engine)
        assertEquals("tab-e-1", resolved.fingerprint.engineVersions["system"])
        assertEquals("pack-3", resolved.fingerprint.engineVersions["piper"])
    }

    @Test
    fun resolve_clampsSpeeds() {
        val outcome = resolve(
            listOf(engine("system", "system:narr", "system:dial")),
            "system:narr", "system:dial",
            narratorSpeed = 9.0f, dialogueSpeed = 0.1f
        )
        val resolved = outcome.getOrThrow()
        assertEquals(2.0f, resolved.narrator.speed)
        assertEquals(0.5f, resolved.dialogue.speed)
    }

    @Test
    fun resolve_blankVoiceNamesTheRole() {
        val outcome = resolve(
            listOf(engine("system", "system:dial")),
            "", "system:dial"
        )
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("narrator" in message)
    }

    @Test
    fun resolve_unknownNamespaceNamesEngineAndVoice() {
        val outcome = resolve(
            listOf(engine("system", "system:narr", "system:dial")),
            "system:narr", "piper:lessac"
        )
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("piper:lessac" in message)
        assertTrue("piper" in message)
    }

    @Test
    fun resolve_missingVoiceNamesVoiceAndModelPack() {
        val outcome = resolve(
            listOf(engine("piper", "piper:other")),
            "piper:lessac", "piper:other"
        )
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("piper:lessac" in message)
        assertTrue("model pack" in message)
    }

    @Test
    fun resolve_missingVersionNamesTheEngine() {
        val outcome = resolve(
            listOf(engine("system", "system:narr", "system:dial")),
            "system:narr", "system:dial",
            versions = emptyMap()
        )
        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message ?: ""
        assertTrue("system" in message)
    }
}
