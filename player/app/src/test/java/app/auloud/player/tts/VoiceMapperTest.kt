package app.auloud.player.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PW8: engine switching maps both roles deterministically.
 */
class VoiceMapperTest {

    private val kokoro = FakeTtsEngine(
        namespace = "kokoro",
        voiceIds = listOf("kokoro:af_heart", "kokoro:am_onyx")
    )
    private val piper = FakeTtsEngine(
        namespace = "piper",
        voiceIds = listOf("piper:en_US-lessac-low", "piper:en_US-ryan-low")
    )

    @Test
    fun keepVoice_whenTargetOffersIt() {
        val current = TtsVoice(id = "kokoro:am_onyx", engine = "kokoro")
        assertEquals(current, VoiceMapper.mapVoice(current, kokoro, TtsRole.Narrator))
    }

    @Test
    fun sameLocalId_winsOverDefault() {
        // Same model name on both engines (contrived, but the rule is ranked).
        val bothKokoro = FakeTtsEngine("kokoro", listOf("kokoro:shared"))
        val bothPiper = FakeTtsEngine("piper", listOf("piper:shared", "piper:other"))
        val current = TtsVoice(id = "kokoro:shared", engine = "kokoro")
        assertEquals(
            TtsVoice(id = "piper:shared", engine = "piper"),
            VoiceMapper.mapVoice(current, bothPiper, TtsRole.Dialogue)
        )
        assertEquals(current, VoiceMapper.mapVoice(current, bothKokoro, TtsRole.Narrator))
    }

    @Test
    fun unknownVoice_fallsBackToFirstSorted() {
        val current = TtsVoice(id = "kokoro:gone", engine = "kokoro")
        assertEquals(
            TtsVoice(id = "piper:en_US-lessac-low", engine = "piper"),
            VoiceMapper.mapVoice(current, piper, TtsRole.Narrator)
        )
    }

    @Test
    fun nullCurrent_fallsBackToFirstSorted() {
        assertEquals(
            TtsVoice(id = "kokoro:af_heart", engine = "kokoro"),
            VoiceMapper.mapVoice(null, kokoro, TtsRole.Dialogue)
        )
    }

    @Test
    fun emptyTarget_returnsNull() {
        val empty = FakeTtsEngine("empty", emptyList())
        assertNull(VoiceMapper.mapVoice(null, empty, TtsRole.Narrator))
    }

    @Test
    fun recommendEngine_prefersSystem() {
        assertEquals(
            "system",
            recommendEngine(listOf("piper", "system", "kokoro"))?.namespace
        )
    }

    @Test
    fun recommendEngine_withoutSystem_picksFirstSorted() {
        val recommendation = recommendEngine(listOf("piper", "kokoro"))
        assertEquals("kokoro", recommendation?.namespace)
        assertTrue(recommendation?.reason?.isNotBlank() == true)
    }

    @Test
    fun recommendEngine_empty_returnsNull() {
        assertNull(recommendEngine(emptyList()))
    }
}
