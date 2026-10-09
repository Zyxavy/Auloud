package app.auloud.player.tts

/**
 * ST0: gap statistics for the live-streaming measurement gate (Slice 13
 * decision 1).
 *
 * The gate measures the silence *between* utterances on a live `speak()`
 * path (start[n+1] minus done[n]), not file-synthesis RTF: D-131 numbers
 * come from `synthesizeToFile` and do not transfer. Thresholds are the
 * plan proposals: median gap 150 ms or less, 95th percentile 400 ms or
 * less, no gap over 1 s across the run, at 1.0x and 1.5x.
 *
 * Pure Kotlin, no Android imports: JVM-testable. The spike app mirrors
 * this math inline (throwaway module, no dependency on app code).
 */
object StreamGapStats {

    /** Go threshold: median inter-utterance gap, ms. */
    const val MEDIAN_GAP_MS = 150L

    /** Go threshold: 95th-percentile gap, ms. */
    const val P95_GAP_MS = 400L

    /** Go threshold: no single gap may exceed this, ms. */
    const val MAX_GAP_MS = 1_000L

    /** Gap summary for one bench run (empty runs have no summary). */
    data class Summary(
        val count: Int,
        val medianMs: Long,
        val p95Ms: Long,
        val maxMs: Long,
    )

    /**
     * Summarize [gapsMs] (each start[n+1] minus done[n], ms; negative
     * values mean overlap and are kept as measured). Null when empty.
     * Median is the upper middle of the sorted gaps; p95 is nearest-rank.
     */
    fun summarize(gapsMs: List<Long>): Summary? {
        if (gapsMs.isEmpty()) return null
        val sorted = gapsMs.sorted()
        val n = sorted.size
        return Summary(
            count = n,
            medianMs = sorted[n / 2],
            p95Ms = sorted[((95 * n + 99) / 100 - 1).coerceIn(0, n - 1)],
            maxMs = sorted[n - 1],
        )
    }

    /** True when [summary] meets all three gate thresholds (equal passes). */
    fun passes(summary: Summary): Boolean =
        summary.medianMs <= MEDIAN_GAP_MS &&
            summary.p95Ms <= P95_GAP_MS &&
            summary.maxMs <= MAX_GAP_MS
}
