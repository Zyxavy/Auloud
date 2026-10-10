package app.auloud.player.render

import kotlin.math.abs

/**
 * RN10: encoder-offset measurement math (Slice 10 plan RN10, D-093).
 *
 * The beep chapter renders tones at known positions ([BeepSelfCheck]
 * timings from sample counts). After encoding, the tone onsets are
 * measured back out of the audio; the per-sentence difference
 * (measured minus known) is the encoder delay plus any drift. When the
 * delay is a stable constant, that constant becomes the manifest
 * `encoder_offset_ms` RN7 records and [EncoderOffset] applies at JSON
 * write time, so readers need no correction.
 *
 * Pure Kotlin, JVM-testable with synthetic data. The REAL Tab E
 * measurement is the owner's RN11 job, not this file: this file only
 * proves the computation on known inputs.
 *
 * API 24 safe: pure Kotlin plus `kotlin.math` only.
 */
object BeepOffset {

    /**
     * Offset diagnosis for one beep render.
     *
     * [offsetMs] is the median of the per-sentence differences (stable
     * constant delay); [perSentenceDiffMs] keeps every difference in
     * sentence order for the drift check; [maxAbsDeviationMs] is the
     * largest distance from any difference to the offset (small means a
     * constant, large means drift the offset alone cannot fix).
     */
    data class Diagnosis(
        val offsetMs: Int,
        val perSentenceDiffMs: List<Int>,
        val maxAbsDeviationMs: Int
    )

    /**
     * Diagnoses the offset from known vs measured tone starts.
     *
     * Both lists are sentence-ordered milliseconds, same non-empty size.
     * The offset is the median difference with round-half-even
     * (`Math.rint`, the assembly rounding rule); even counts averaging to
     * x.5 round to the even neighbor. A negative median fails: encoder
     * delay cannot move audio earlier, so measured-before-known means
     * the measurement itself is wrong.
     */
    fun diagnose(
        knownStartsMs: List<Int>,
        measuredStartsMs: List<Int>
    ): Diagnosis {
        require(knownStartsMs.isNotEmpty()) {
            "beep offset needs at least one known tone start (got none)."
        }
        require(knownStartsMs.size == measuredStartsMs.size) {
            "beep offset needs paired starts " +
                "(known ${knownStartsMs.size}, measured ${measuredStartsMs.size})."
        }
        for (start in knownStartsMs) {
            require(start >= 0) { "known tone start must be non-negative, got $start." }
        }
        for (start in measuredStartsMs) {
            require(start >= 0) { "measured tone start must be non-negative, got $start." }
        }
        val diffs = knownStartsMs.indices.map { i -> measuredStartsMs[i] - knownStartsMs[i] }
        val sorted = diffs.sorted()
        val middle = sorted.size / 2
        val median: Double = if (sorted.size % 2 == 1) {
            sorted[middle].toDouble()
        } else {
            (sorted[middle - 1].toDouble() + sorted[middle].toDouble()) / 2.0
        }
        val offset = Math.rint(median).toInt()
        require(offset >= 0) {
            "beep offset median is $offset ms " +
                "(measured audio precedes the known tones; re-measure)."
        }
        val deviation = diffs.maxOf { diff -> abs(diff - offset) }
        return Diagnosis(
            offsetMs = offset,
            perSentenceDiffMs = diffs,
            maxAbsDeviationMs = deviation
        )
    }

    /**
     * Offset constant from known vs measured tone starts.
     *
     * Convenience over [diagnose] when only the constant is needed
     * (RN7 records it as manifest `encoder_offset_ms`).
     */
    fun estimateOffsetMs(
        knownStartsMs: List<Int>,
        measuredStartsMs: List<Int>
    ): Int = diagnose(knownStartsMs, measuredStartsMs).offsetMs

    /**
     * Finds tone onsets in decoded mono PCM.
     *
     * Returns the sample index of the first above-threshold sample of
     * each tone (rising edge). After an onset the detector re-arms only
     * after [minGapMs] of continuous below-threshold signal, so one
     * tone's own sine cycles never re-trigger it; only real silence
     * gaps do. Beep tones start at phase 0 (sample value 0) and rise
     * fast, so the first crossing lands within a couple of samples of
     * the true onset (well under 1 ms at 24 kHz with the default
     * threshold). Convert to ms with [AssemblyMath.msForSamples].
     *
     * @param threshold amplitude floor separating tone from silence
     * (beep tones peak at 0.5, pauses are digital silence, so 0.08 sits
     * far from both; encoder noise stays far below it).
     * @param minGapMs continuous silence needed to re-arm after an
     * onset (must sit between one sine period, about 2 ms at 440 Hz,
     * and the shortest pause, 250 ms for the beep chapter).
     */
    fun detectOnsets(
        samples: FloatArray,
        sampleRateHz: Int,
        threshold: Float = 0.08f,
        minGapMs: Int = 200
    ): List<Int> {
        require(sampleRateHz > 0) { "sample_rate must be positive, got $sampleRateHz." }
        require(threshold > 0f) { "onset threshold must be positive, got $threshold." }
        require(minGapMs >= 0) { "onset gap must be non-negative, got $minGapMs." }
        val gapSamples = (minGapMs.toLong() * sampleRateHz.toLong()) / 1000L
        val onsets = ArrayList<Int>()
        var silenceRun = gapSamples
        for (i in samples.indices) {
            val value = samples[i]
            if (!value.isFinite()) continue
            val magnitude = if (value < 0f) -value else value
            if (magnitude >= threshold) {
                if (silenceRun >= gapSamples) {
                    onsets.add(i)
                }
                silenceRun = 0L
            } else {
                silenceRun++
            }
        }
        return onsets
    }
}
