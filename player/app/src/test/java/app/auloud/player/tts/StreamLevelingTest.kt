package app.auloud.player.tts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST6: live-stream leveling math on plain JVM.
 *
 * Expected values are hand-computed literals, not the code under test.
 */
class StreamLevelingTest {

    private fun volume(volumes: Map<TtsRole, Float>, role: TtsRole): Float =
        requireNotNull(volumes[role])

    @Test
    fun peak_ignoresSignAndSkipsNonFinite() {
        assertEquals(0.5f, StreamLeveling.peakAmplitude(floatArrayOf(0.1f, -0.5f, 0.3f)), 0f)
        assertEquals(0.0f, StreamLeveling.peakAmplitude(floatArrayOf()), 0f)
        assertEquals(
            0.2f,
            StreamLeveling.peakAmplitude(floatArrayOf(Float.NaN, 0.2f, Float.POSITIVE_INFINITY)),
            0f
        )
    }

    @Test
    fun calibrationVolumes_attenuateLouderToQuieter() {
        val volumes = StreamLeveling.calibrationVolumes(0.8f, 0.4f)
        assertEquals(0.5f, volume(volumes, TtsRole.Narrator), 0.0001f)
        assertEquals(1.0f, volume(volumes, TtsRole.Dialogue), 0f)
    }

    @Test
    fun calibrationVolumes_quieterNarrator() {
        val volumes = StreamLeveling.calibrationVolumes(0.3f, 0.9f)
        assertEquals(1.0f, volume(volumes, TtsRole.Narrator), 0f)
        assertEquals(0.3f / 0.9f, volume(volumes, TtsRole.Dialogue), 0.0001f)
    }

    @Test
    fun calibrationVolumes_silenceReadsFull() {
        val silentNarrator = StreamLeveling.calibrationVolumes(0.0f, 0.6f)
        assertEquals(1.0f, volume(silentNarrator, TtsRole.Narrator), 0f)
        assertEquals(1.0f, volume(silentNarrator, TtsRole.Dialogue), 0f)
        val bothSilent = StreamLeveling.calibrationVolumes(0.0f, -1.0f)
        assertEquals(1.0f, volume(bothSilent, TtsRole.Narrator), 0f)
        assertEquals(1.0f, volume(bothSilent, TtsRole.Dialogue), 0f)
    }

    @Test
    fun calibrate_synthesizesStandardSentencePerRole() = runBlocking {
        val engine = FakeTtsEngine(
            namespace = "system",
            voiceIds = listOf("system:narr", "system:dial"),
            peakByVoice = mapOf("system:narr" to 0.8f, "system:dial" to 0.4f)
        )
        val volumes = StreamLeveling.calibrate(
            engine,
            TtsVoice("system:narr", "system"),
            TtsVoice("system:dial", "system")
        )
        assertEquals(
            listOf(StreamLeveling.CALIBRATION_SENTENCE, StreamLeveling.CALIBRATION_SENTENCE),
            engine.calls.map { it.text }
        )
        assertEquals(0.5f, volume(volumes, TtsRole.Narrator), 0.0001f)
        assertEquals(1.0f, volume(volumes, TtsRole.Dialogue), 0f)
    }

    @Test
    fun volumesFor_appliesOnlyOnVoiceMatch() {
        val calibrated = mapOf(TtsRole.Narrator to 0.5f, TtsRole.Dialogue to 1.0f)
        val voices = mapOf(TtsRole.Narrator to "system:n", TtsRole.Dialogue to "system:d")
        assertEquals(
            calibrated,
            StreamLeveling.volumesFor(voices, voices, calibrated)
        )
        // One role changed: full until recalibrated (never half-matched).
        val changed = mapOf(TtsRole.Narrator to "system:other", TtsRole.Dialogue to "system:d")
        assertEquals(
            mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 1.0f),
            StreamLeveling.volumesFor(changed, voices, calibrated)
        )
        assertEquals(
            mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 1.0f),
            StreamLeveling.volumesFor(voices, emptyMap(), calibrated)
        )
    }

    @Test
    fun describe_reportsPercents() {
        assertEquals(
            "Voices already level",
            StreamLeveling.describe(mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 1.0f))
        )
        assertEquals(
            "Levels matched (narrator 100%, dialogue 80%)",
            StreamLeveling.describe(mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 0.8f))
        )
        assertTrue(
            StreamLeveling.describe(mapOf(TtsRole.Narrator to 0.5f, TtsRole.Dialogue to 1.0f))
                .contains("narrator 50%")
        )
    }
}
