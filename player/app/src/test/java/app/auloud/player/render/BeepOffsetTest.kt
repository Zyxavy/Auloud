package app.auloud.player.render

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN10: [BeepOffset] unit tests (pure JVM, synthetic data).
 *
 * Pins the measurement computation the owner runs on the Tab E in
 * RN11: constant-offset estimation, zero identity, median rounding,
 * jitter diagnosis, input validation, and onset detection on synthetic
 * beeps with silence gaps.
 */
class BeepOffsetTest {

    // Offset estimation from known vs measured tone starts.

    @Test
    fun constantOffset_isRecoveredExactly() {
        val known = listOf(0, 1000, 2000, 3000)
        val measured = listOf(25, 1025, 2025, 3025)
        val diagnosis = BeepOffset.diagnose(known, measured)
        assertEquals(25, diagnosis.offsetMs)
        assertEquals(listOf(25, 25, 25, 25), diagnosis.perSentenceDiffMs)
        assertEquals(0, diagnosis.maxAbsDeviationMs)
        assertEquals(25, BeepOffset.estimateOffsetMs(known, measured))
    }

    @Test
    fun zeroOffset_isIdentity() {
        val known = listOf(0, 1000, 2000, 3000)
        assertEquals(0, BeepOffset.estimateOffsetMs(known, known))
    }

    @Test
    fun evenCountHalfMedian_roundsHalfEven() {
        // Diffs [24, 25]: median 24.5 rounds to 24 (even), not 25.
        assertEquals(24, BeepOffset.estimateOffsetMs(listOf(0, 1000), listOf(24, 1025)))
    }

    @Test
    fun jitter_reportsOffsetPlusDeviation() {
        val diagnosis = BeepOffset.diagnose(
            listOf(0, 1000, 2000, 3000),
            listOf(25, 1024, 2026, 3025)
        )
        assertEquals(25, diagnosis.offsetMs)
        assertEquals(1, diagnosis.maxAbsDeviationMs)
    }

    @Test
    fun mismatchedSizes_fail() {
        try {
            BeepOffset.estimateOffsetMs(listOf(0, 1000), listOf(25))
            fail("mismatched sizes must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun emptyInputs_fail() {
        try {
            BeepOffset.estimateOffsetMs(emptyList(), emptyList())
            fail("empty inputs must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun measuredBeforeKnown_fails() {
        try {
            BeepOffset.estimateOffsetMs(listOf(100, 200), listOf(90, 190))
            fail("negative delay must fail (re-measure)")
        } catch (_: IllegalArgumentException) {
        }
    }

    // Onset detection on synthetic beeps.

    private fun syntheticChapter(): FloatArray {
        val tone1 = BeepTtsEngine.toneSamples(1)
        val tone2 = BeepTtsEngine.toneSamples(2)
        val gap = FloatArray(12000)
        return tone1 + gap + tone2 + gap
    }

    @Test
    fun onsets_foundAtToneStarts() {
        val onsets = BeepOffset.detectOnsets(syntheticChapter(), 24000)
        assertEquals(2, onsets.size)
        assertTrue("first onset near 0, got ${onsets[0]}", onsets[0] <= 3)
        assertTrue(
            "second onset near 24000, got ${onsets[1]}",
            abs(onsets[1] - 24000) <= 3
        )
    }

    @Test
    fun onsets_convertToKnownMillisecondStarts() {
        val onsets = BeepOffset.detectOnsets(syntheticChapter(), 24000)
        val startsMs = onsets.map { AssemblyMath.msForSamples(it.toLong()) }
        assertEquals(listOf(0, 1000), startsMs)
    }

    @Test
    fun onsets_silenceOnlyIsEmpty() {
        assertEquals(emptyList<Int>(), BeepOffset.detectOnsets(FloatArray(48000), 24000))
    }

    @Test
    fun onsets_rejectBadInputs() {
        try {
            BeepOffset.detectOnsets(FloatArray(10), 0)
            fail("zero rate must fail")
        } catch (_: IllegalArgumentException) {
        }
        try {
            BeepOffset.detectOnsets(FloatArray(10), 24000, threshold = 0f)
            fail("zero threshold must fail")
        } catch (_: IllegalArgumentException) {
        }
    }
}
