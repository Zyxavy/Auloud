package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RN8: [RenderEstimates] math on plain JVM.
 *
 * Rates are the plan figures (64 kbps audio, about 86 MB spool per
 * 30 min); every function takes them as params so RN11 can retune
 * without touching the tests.
 */
class RenderEstimateTest {

    @Test
    fun audioRate_is8BytesPerMs() {
        assertEquals(8.0, RenderEstimates.AUDIO_BYTES_PER_MS, 0.0)
    }

    @Test
    fun spoolRate_matches86MiBPer30Min() {
        val expected = 86.0 * 1024 * 1024 / 1_800_000.0
        assertEquals(expected, RenderEstimates.SPOOL_BYTES_PER_MS, 0.01)
    }

    @Test
    fun estimateForAudioMs_sumsAudioPlusSpool() {
        val estimate = RenderEstimates.estimateForAudioMs(
            audioMs = 1_000L,
            bytesPerMs = 8.0,
            spoolBytesPerMs = 50.0
        )

        assertEquals(1_000L, estimate.audioMs)
        assertEquals(8_000L, estimate.audioBytes)
        assertEquals(50_000L, estimate.spoolBytes)
        assertEquals(58_000L, estimate.totalBytes)
        assertEquals(0, estimate.unknownChapters)
    }

    @Test
    fun estimateForAudioMs_zeroStaysZero() {
        val estimate = RenderEstimates.estimateForAudioMs(0L)

        assertEquals(0L, estimate.audioBytes)
        assertEquals(0L, estimate.spoolBytes)
        assertEquals(0L, estimate.totalBytes)
    }

    @Test
    fun estimateForPlan_sumsKnownChapters() {
        val estimate = RenderEstimates.estimateForPlan(
            ordered = listOf(0, 1),
            audioMsByChapter = mapOf(0 to 60_000L, 1 to 120_000L),
            bytesPerMs = 8.0,
            spoolBytesPerMs = 50.0
        )

        assertEquals(180_000L, estimate.audioMs)
        assertEquals(180_000L * 58L, estimate.totalBytes)
        assertEquals(0, estimate.unknownChapters)
    }

    @Test
    fun estimateForPlan_unknownChaptersUseFallbackAndCount() {
        val estimate = RenderEstimates.estimateForPlan(
            ordered = listOf(0, 1, 2),
            audioMsByChapter = mapOf(0 to 60_000L, 1 to null),
            bytesPerMs = 8.0,
            spoolBytesPerMs = 50.0,
            defaultAudioMs = 100_000L
        )

        assertEquals(60_000L + 100_000L + 100_000L, estimate.audioMs)
        assertEquals(2, estimate.unknownChapters)
    }

    @Test
    fun estimateForPlan_emptyPlanNeedsNothing() {
        val estimate = RenderEstimates.estimateForPlan(emptyList())

        assertEquals(0L, estimate.totalBytes)
        assertEquals(0, estimate.unknownChapters)
    }

    @Test
    fun requiredBytes_matchesEstimateTotal() {
        val required = RenderEstimates.requiredBytes(
            ordered = listOf(0),
            audioMsByChapter = mapOf(0 to 1_000L),
            bytesPerMs = 8.0,
            spoolBytesPerMs = 50.0
        )

        assertEquals(58_000L, required)
    }

    @Test
    fun defaultFallback_is30Minutes() {
        assertEquals(1_800_000L, RenderEstimates.DEFAULT_CHAPTER_AUDIO_MS)
        val estimate = RenderEstimates.estimateForPlan(listOf(0))
        assertTrue(estimate.totalBytes > 0L)
        assertEquals(1, estimate.unknownChapters)
    }
}
