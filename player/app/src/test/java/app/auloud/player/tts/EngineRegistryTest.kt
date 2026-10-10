package app.auloud.player.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PW5: voice-id parsing and registry routing on plain JVM.
 */
class EngineRegistryTest {

    private val kokoro = FakeTtsEngine(
        namespace = "kokoro",
        voiceIds = listOf("kokoro:af_heart", "kokoro:am_onyx")
    )
    private val piper = FakeTtsEngine(
        namespace = "piper",
        voiceIds = listOf("piper:en_US-lessac-low")
    )
    private val system = FakeTtsEngine(
        namespace = "system",
        voiceIds = listOf("system:en-us-x-sfg#female")
    )

    private val registry = EngineRegistry(listOf(kokoro, piper, system))

    @Test
    fun parse_namespacedId_splitsOnFirstColon() {
        assertEquals(TtsVoice(id = "kokoro:af_heart", engine = "kokoro"), TtsVoice.parse("kokoro:af_heart"))
        // System voice names contain colons and hashes; only the first colon splits.
        assertEquals(
            TtsVoice(id = "system:en-us-x-sfg#female", engine = "system"),
            TtsVoice.parse("system:en-us-x-sfg#female")
        )
    }

    @Test
    fun parse_malformedIds_returnNull() {
        assertNull(TtsVoice.parse(""))
        assertNull(TtsVoice.parse("no-namespace"))
        assertNull(TtsVoice.parse(":no-namespace"))
        assertNull(TtsVoice.parse("namespace:"))
    }

    @Test
    fun engineFor_routesToOwningEngine() {
        assertTrue(registry.engineFor("kokoro:af_heart") === kokoro)
        assertTrue(registry.engineFor("piper:en_US-lessac-low") === piper)
        assertTrue(registry.engineFor("system:en-us-x-sfg#female") === system)
    }

    @Test
    fun engineFor_unknownNamespaceOrMalformed_returnsNull() {
        assertNull(registry.engineFor("espeak:default"))
        assertNull(registry.engineFor("af_heart"))
        assertNull(registry.engineFor(""))
    }

    @Test
    fun allVoices_listsEveryEngineVoice() {
        val ids = registry.allVoices().map { it.id }
        assertEquals(
            listOf(
                "kokoro:af_heart",
                "kokoro:am_onyx",
                "piper:en_US-lessac-low",
                "system:en-us-x-sfg#female"
            ),
            ids.sorted()
        )
    }

    @Test
    fun namespaces_listsSortedNamespaces() {
        assertEquals(listOf("kokoro", "piper", "system"), registry.namespaces())
    }

    @Test
    fun synthesize_recordsContract() = runBlocking {
        val voice = TtsVoice(id = "kokoro:af_heart", engine = "kokoro")
        val audio = kokoro.synthesize("Hello world.", voice, 1.25f)
        assertEquals(24_000, audio.sampleRateHz)
        assertEquals(240, audio.samples.size)
        assertEquals(
            listOf(FakeTtsEngine.Call("Hello world.", voice, 1.25f)),
            kokoro.calls
        )
    }
}
