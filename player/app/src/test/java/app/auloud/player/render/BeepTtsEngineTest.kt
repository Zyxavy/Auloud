package app.auloud.player.render

import app.auloud.player.tts.TtsVoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN10: [BeepTtsEngine] unit tests (pure JVM).
 *
 * Pins the debug-engine contract: determinism (same input gives
 * byte-identical PCM), known tone lengths, distinct frequencies per
 * sentence index, voice/capability shape, and input validation.
 */
class BeepTtsEngineTest {

    private fun engine(): BeepTtsEngine = BeepTtsEngine()

    private fun narrator(): TtsVoice =
        TtsVoice(id = BeepTtsEngine.NARRATOR_VOICE_ID, engine = BeepTtsEngine.NAMESPACE)

    // Tone shape: known length, known rate, distinct frequencies.

    @Test
    fun toneLength_is500msAt24kHz(): Unit = runBlocking {
        val audio = engine().synthesize("Beep 1", narrator(), 1.0f)
        assertEquals(24000, audio.sampleRateHz)
        assertEquals(500 * 24000 / 1000, audio.samples.size)
    }

    @Test
    fun frequencies_step110HzFrom440(): Unit = runBlocking {
        assertEquals(440.0, BeepTtsEngine.frequencyForSid(1), 0.0)
        assertEquals(550.0, BeepTtsEngine.frequencyForSid(2), 0.0)
        assertEquals(770.0, BeepTtsEngine.frequencyForSid(4), 0.0)
    }

    @Test
    fun synth_matchesToneHelperForSid(): Unit = runBlocking {
        val fromEngine = engine().synthesize("Beep 3", narrator(), 1.0f)
        assertTrue(fromEngine.samples.contentEquals(BeepTtsEngine.toneSamples(3)))
    }

    @Test
    fun adjacentSentences_soundDifferent(): Unit = runBlocking {
        val first = engine().synthesize("Beep 1", narrator(), 1.0f)
        val second = engine().synthesize("Beep 2", narrator(), 1.0f)
        assertTrue(
            "sentence 1 and 2 must differ (distinct frequency per index)",
            !first.samples.contentEquals(second.samples)
        )
    }

    @Test
    fun foreignText_fallsBackToSentenceOneTone(): Unit = runBlocking {
        val audio = engine().synthesize("Hello world", narrator(), 1.0f)
        assertTrue(audio.samples.contentEquals(BeepTtsEngine.toneSamples(1)))
    }

    // Determinism: same input gives byte-identical PCM.

    @Test
    fun sameInput_isByteIdentical(): Unit = runBlocking {
        val first = engine().synthesize("Beep 2", narrator(), 1.0f)
        val second = engine().synthesize("Beep 2", narrator(), 1.0f)
        assertTrue(first.samples.contentEquals(second.samples))
    }

    @Test
    fun sameInput_isByteIdenticalAcrossInstances(): Unit = runBlocking {
        val first = engine().synthesize("Beep 4", narrator(), 1.0f)
        val second = BeepTtsEngine().synthesize("Beep 4", narrator(), 1.0f)
        assertTrue(first.samples.contentEquals(second.samples))
    }

    // Seam shape: voices, capabilities, validation.

    @Test
    fun voices_offerNarratorAndDialogue(): Unit = runBlocking {
        val voices = engine().voices()
        assertEquals(
            listOf(BeepTtsEngine.NARRATOR_VOICE_ID, BeepTtsEngine.DIALOGUE_VOICE_ID),
            voices.map { it.id }
        )
        assertTrue(voices.all { it.engine == BeepTtsEngine.NAMESPACE })
    }

    @Test
    fun capabilities_areFreeAndAtBundleRate(): Unit = runBlocking {
        val caps = engine().capabilities()
        assertEquals(24000, caps.sampleRateHz)
        assertEquals(0, caps.loadCostMb)
        assertTrue(caps.multiSpeaker)
    }

    @Test
    fun blankText_fails(): Unit = runBlocking {
        try {
            engine().synthesize("  ", narrator(), 1.0f)
            fail("blank text must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun foreignVoice_fails(): Unit = runBlocking {
        try {
            engine().synthesize("Beep 1", TtsVoice(id = "system:x", engine = "system"), 1.0f)
            fail("foreign voice must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun frequency_rejectsSidZero(): Unit = runBlocking {
        try {
            BeepTtsEngine.frequencyForSid(0)
            fail("sid 0 must fail")
        } catch (_: IllegalArgumentException) {
        }
    }
}
