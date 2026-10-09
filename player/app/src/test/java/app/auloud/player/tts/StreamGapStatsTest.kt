package app.auloud.player.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST0: gap statistics for the live-streaming gate.
 *
 * Pure JVM, no Android, no storage. Expected values are literals
 * computed by hand from the inputs, not from the code under test.
 */
class StreamGapStatsTest {

    @Test
    fun empty_hasNoSummary() {
        assertNull(StreamGapStats.summarize(emptyList()))
    }

    @Test
    fun singleGap_summaryIsThatGap() {
        val summary = requireNotNull(StreamGapStats.summarize(listOf(120L)))
        assertEquals(1, summary.count)
        assertEquals(120L, summary.medianMs)
        assertEquals(120L, summary.p95Ms)
        assertEquals(120L, summary.maxMs)
    }

    @Test
    fun oddCount_medianIsMiddle() {
        // Sorted: 10 80 90 100 900; median 90, p95 nearest-rank 900.
        val summary = requireNotNull(
            StreamGapStats.summarize(listOf(100L, 10L, 900L, 90L, 80L))
        )
        assertEquals(5, summary.count)
        assertEquals(90L, summary.medianMs)
        assertEquals(900L, summary.p95Ms)
        assertEquals(900L, summary.maxMs)
    }

    @Test
    fun evenCount_medianIsUpperMiddle() {
        // Sorted: 10 20 30 40; upper middle 30.
        val summary = requireNotNull(
            StreamGapStats.summarize(listOf(40L, 10L, 30L, 20L))
        )
        assertEquals(30L, summary.medianMs)
        assertEquals(40L, summary.maxMs)
    }

    @Test
    fun twentyGaps_p95IsNineteenth() {
        // Sorted 10..200 step 10; median index 10 = 110, p95 index 18 = 190.
        val gaps = (1..20).map { it * 10L }
        val summary = requireNotNull(StreamGapStats.summarize(gaps))
        assertEquals(20, summary.count)
        assertEquals(110L, summary.medianMs)
        assertEquals(190L, summary.p95Ms)
        assertEquals(200L, summary.maxMs)
    }

    @Test
    fun negativeGaps_keptAsMeasured() {
        // Overlap (next starts before previous done) stays negative.
        val summary = requireNotNull(
            StreamGapStats.summarize(listOf(50L, -20L, 60L))
        )
        assertEquals(50L, summary.medianMs)
    }

    @Test
    fun withinThresholds_passes() {
        val summary = requireNotNull(
            StreamGapStats.summarize(listOf(100L, 150L, 120L, 400L, 90L))
        )
        assertTrue(StreamGapStats.passes(summary))
    }

    @Test
    fun overMedian_fails() {
        val summary = requireNotNull(
            StreamGapStats.summarize(listOf(200L, 210L, 190L))
        )
        assertEquals(200L, summary.medianMs)
        assertFalse(StreamGapStats.passes(summary))
    }

    @Test
    fun singleLongGap_fails() {
        val summary = requireNotNull(
            StreamGapStats.summarize(listOf(50L, 60L, 1_200L))
        )
        assertFalse(StreamGapStats.passes(summary))
    }

    @Test
    fun boundaryValues_pass() {
        val summary = StreamGapStats.Summary(
            count = 3,
            medianMs = StreamGapStats.MEDIAN_GAP_MS,
            p95Ms = StreamGapStats.P95_GAP_MS,
            maxMs = StreamGapStats.MAX_GAP_MS,
        )
        assertTrue(StreamGapStats.passes(summary))
    }
}
