package app.auloud.player.tts

/**
 * KT3: Kitten benchmark math (pure Kotlin, no Android, no native code).
 *
 * The spike app measures on device (load ms, per-sentence wall ms plus
 * audio seconds, PSS before/after) and pastes the lines into
 * `docs/test-log.md`; this file turns raw runs into warmed RTF, the
 * thread-sweep winner, and the report lines, so the numbers mean the
 * same thing everywhere. First sentence of every run is warmup and is
 * skipped, matching the spike's Piper/Kokoro benches.
 *
 * The Kitten category stays [EngineSpeedCategory.BACKGROUND] (unknown
 * until measured) until KT5 re-tables the recommendation from the KT3
 * numbers plus the KT4 listening result.
 *
 * API 24 safe: pure Kotlin.
 */
data class KittenBenchRun(
    val threadCount: Int,
    val loadMs: Long,
    /** Wall ms per sentence, first entry warmup. */
    val wallMs: List<Long>,
    /** Audio seconds per sentence, parallel to [wallMs]. */
    val audioSec: List<Double>,
    val pssBeforeMb: Long,
    val pssAfterMb: Long
)

/**
 * Warmed real-time factor (audio seconds per wall second, higher is
 * better), skipping the first warmup sentence. Null when fewer than two
 * sentences ran or the warmed audio is not positive.
 */
fun warmedRtf(wallMs: List<Long>, audioSec: List<Double>): Double? {
    // Zipped after the warmup drop so positions stay paired; a ragged
    // tail falls off instead of mixing unrelated entries.
    val warmed = wallMs.drop(1).zip(audioSec.drop(1))
    if (warmed.size < 1) return null
    val wallSec = warmed.sumOf { it.first } / 1000.0
    val audio = warmed.sumOf { it.second }
    if (wallSec <= 0.0 || audio <= 0.0) return null
    return audio / wallSec
}

/** Warmed RTF for a full [KittenBenchRun] (null when unmeasurable). */
fun warmedRtf(run: KittenBenchRun): Double? = warmedRtf(run.wallMs, run.audioSec)

/**
 * Thread-sweep winner: the thread count with the best warmed RTF;
 * ties go to fewer threads (less CPU for the same speed). Null when no
 * run measured.
 */
fun bestThreads(runs: List<KittenBenchRun>): Int? {
    return runs.mapNotNull { run ->
        warmedRtf(run)?.let { run.threadCount to it }
    }.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        .firstOrNull()?.first
}

/**
 * Report lines for one run, in the spike's line style (`name=value`,
 * two decimals on RTF). [batteryPct], [tempC] and [variant] name the
 * run; unmeasurable runs report `rtf=unmeasured` instead of a number.
 */
fun formatKittenReport(
    variant: String,
    run: KittenBenchRun,
    batteryPct: Int,
    tempC: Double
): List<String> {
    val rtf = warmedRtf(run)
    val audio = run.audioSec.drop(1).sum()
    val wallSec = run.wallMs.drop(1).sum() / 1000.0
    val head = "kitten=$variant threads=${run.threadCount} " +
        "loadMs=${run.loadMs} pssBeforeMb=${run.pssBeforeMb} pssAfterMb=${run.pssAfterMb}"
    val body = if (rtf == null) {
        "kittenWarmedRtf=unmeasured"
    } else {
        "kittenWarmedRtf=%.2f audioSec=%.1f wallSec=%.1f".format(rtf, audio, wallSec)
    }
    return listOf(head, "$body battery=$batteryPct% temp=${tempC}C")
}
