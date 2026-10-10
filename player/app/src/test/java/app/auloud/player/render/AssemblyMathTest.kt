package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN5: [AssemblyMath] unit tests (pure JVM, no fixtures).
 *
 * Pins the Scribe port rule by rule: pause constants, exact pause
 * samples, half-even ms (the `Math.round` trap), half-even resample
 * lengths, linear-interp values, peak and gain helpers, the
 * attenuate-only cap, db conversions and the pause table.
 */
class AssemblyMathTest {

    @Test
    fun pauses_matchScribeConstants() {
        assertEquals(250, AssemblyMath.PAUSE_SENTENCE_MS)
        assertEquals(500, AssemblyMath.PAUSE_PARA_MS)
        assertEquals(800, AssemblyMath.PAUSE_HEADING_MS)
        assertEquals(1000, AssemblyMath.PAUSE_BREAK_MS)
        assertEquals(100, AssemblyMath.PAUSE_TAG_MS)
        assertEquals(24000, AssemblyMath.SAMPLE_RATE_HZ)
    }

    @Test
    fun samplesForMs_isExactIntegerMath() {
        assertEquals(6000, AssemblyMath.samplesForMs(250))
        assertEquals(12000, AssemblyMath.samplesForMs(500))
        assertEquals(19200, AssemblyMath.samplesForMs(800))
        assertEquals(24000, AssemblyMath.samplesForMs(1000))
        assertEquals(2400, AssemblyMath.samplesForMs(100))
        assertEquals(0, AssemblyMath.samplesForMs(0))
    }

    @Test
    fun msForSamples_roundsHalfEvenNotHalfUp() {
        assertEquals(2000, AssemblyMath.msForSamples(48000L))
        assertEquals(100, AssemblyMath.msForSamples(2400L))
        // Exact .5 ms boundary: 2412 samples is 100.5 ms, half-even to 100.
        assertEquals(100, AssemblyMath.msForSamples(2412L))
        // 100.5 vs 101.5: even stays, odd rounds up (half-even proof).
        assertEquals(100, AssemblyMath.msForSamples(2412L))
        assertEquals(102, AssemblyMath.msForSamples(2436L))
    }

    @Test
    fun resampledLength_matchesScribeRoundHalfEven() {
        assertEquals(48000, AssemblyMath.resampledLength(48000, 24000))
        assertEquals(12000, AssemblyMath.resampledLength(8000, 16000))
        assertEquals(12000, AssemblyMath.resampledLength(11025, 22050))
        assertEquals(12000, AssemblyMath.resampledLength(22050, 44100))
        assertEquals(12000, AssemblyMath.resampledLength(4000, 8000))
        assertEquals(2412, AssemblyMath.resampledLength(2216, 22050))
        assertEquals(0, AssemblyMath.resampledLength(0, 24000))
    }

    @Test
    fun resampleMono_sameRateCopies() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out = AssemblyMath.resampleMono(input, 24000, 24000)
        assertEquals(3, out.size)
        assertTrue(out.contentEquals(input))
        assertTrue(out !== input)
    }

    @Test
    fun resampleMono_emptyStaysEmpty() {
        assertEquals(0, AssemblyMath.resampleMono(FloatArray(0), 16000).size)
    }

    @Test
    fun resampleMono_singleSampleFills() {
        val out = AssemblyMath.resampleMono(floatArrayOf(0.5f), 16000)
        assertEquals(AssemblyMath.resampledLength(1, 16000), out.size)
        for (value in out) assertEquals(0.5f, value)
    }

    @Test
    fun resampleMono_linearValuesMatchInterp() {
        val input = floatArrayOf(0f, 1f)
        val out = AssemblyMath.resampleMono(input, 2, 4)
        assertEquals(4, out.size)
        assertEquals(0f, out[0])
        assertEquals(1f, out[3])
        assertTrue(kotlin.math.abs(out[1] - 1f / 3f) < 1e-6f)
        assertTrue(kotlin.math.abs(out[2] - 2f / 3f) < 1e-6f)
    }

    @Test
    fun voicePeakLevels_skipsNonFiniteStandalone() {
        val peaks = AssemblyMath.voicePeakLevels(
            mapOf(
                "narrator" to listOf(floatArrayOf(0.5f, Float.NaN, Float.POSITIVE_INFINITY, -0.25f)),
                "dialogue" to listOf(floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY)),
                "empty" to emptyList()
            )
        )
        assertEquals(0.5f, peaks["narrator"]!!)
        assertEquals(0.0f, peaks["dialogue"]!!)
        assertEquals(0.0f, peaks["empty"]!!)
    }

    @Test
    fun gainsForPeaks_silenceGivesOne() {
        val gains = AssemblyMath.gainsForPeaks(mapOf("narrator" to 0f, "dialogue" to 0.5f))
        assertEquals(1.0, gains["narrator"]!!, 1e-12)
        assertEquals(AssemblyMath.PEAK_TARGET / 0.5, gains["dialogue"]!!, 1e-12)
    }

    @Test
    fun capGain_attenuatesOnly() {
        assertEquals(1.0, AssemblyMath.capGainFor(0.0), 1e-12)
        assertEquals(1.0, AssemblyMath.capGainFor(0.5), 1e-12)
        assertEquals(1.0, AssemblyMath.capGainFor(AssemblyMath.PEAK_TARGET), 1e-12)
        val over = AssemblyMath.PEAK_TARGET * 2.0
        assertEquals(0.5, AssemblyMath.capGainFor(over), 1e-12)
    }

    @Test
    fun dbConversions_roundTrip() {
        assertEquals(0.0, AssemblyMath.dbForGain(1.0), 1e-9)
        val gain = 2.0
        val db = AssemblyMath.dbForGain(gain)
        assertEquals(gain, AssemblyMath.gainForDb(db), 1e-9)
        assertEquals(-1.0, AssemblyMath.dbForGain(AssemblyMath.PEAK_TARGET), 1e-9)
    }

    @Test
    fun pauseAfterSentence_matchesTable() {
        assertEquals(250, AssemblyMath.pauseAfterSentence("para", false))
        assertEquals(500, AssemblyMath.pauseAfterSentence("para", true))
        assertEquals(250, AssemblyMath.pauseAfterSentence("quote", false))
        assertEquals(500, AssemblyMath.pauseAfterSentence("quote", true))
        assertEquals(800, AssemblyMath.pauseAfterSentence("heading", false))
        assertEquals(800, AssemblyMath.pauseAfterSentence("heading", true))
    }
}
