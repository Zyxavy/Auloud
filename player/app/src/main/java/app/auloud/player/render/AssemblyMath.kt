package app.auloud.player.render

import kotlin.math.log10
import kotlin.math.pow

/**
 * RN5: assembly sample math (Slice 10, D-094 port).
 *
 * Exact Kotlin port of the Scribe arithmetic the RN0 finding names:
 * `scribe/audio/assemble.py` (pause table, [samplesForMs], [msForSamples],
 * [gainsForPeaks] plus silence gain 1.0) and `scribe/tts/base.py`
 * [resampleMono] (15 lines linear interp). Do not invent variants here;
 * the RN2 vectors pin every rule and fail loudly on drift.
 *
 * Rules ported verbatim:
 * - pauses 250 sentence, 500 para, 800 heading, 1000 break, 100 tag
 *   (quote blocks pause exactly like para; end excludes the pause,
 *   duration includes the final pause; leading-break silence dropped).
 * - pause samples by exact integer math (`ms * rate // 1000`).
 * - ms from running sample counts with round-half-even (Python `round`,
 *   JVM `Math.rint`; Kotlin `Math.round` is half-up and fails the
 *   rounding-half-even vector, so it must never be used here).
 * - resample lengths by `round(n * dst / src)` half-even on doubles.
 * - silence peaks give gain 1.0; gain only, durations unchanged.
 *
 * API 24 safe: pure Kotlin plus `kotlin.math` and `java.lang.Math`
 * only. No `java.time`, no Android types, no new dependency.
 */
object AssemblyMath {

    /** Bundle-wide audio rate in Hz (Scribe `SAMPLE_RATE`, spec contract). */
    const val SAMPLE_RATE_HZ = 24000

    /** Pause after a mid-block sentence (para/quote internal boundary). */
    const val PAUSE_SENTENCE_MS = 250

    /** Pause after the last sentence of a para/quote block. */
    const val PAUSE_PARA_MS = 500

    /** Pause after each sentence of a heading block. */
    const val PAUSE_HEADING_MS = 800

    /** Silence for a break block (scene divider, carries no sentences). */
    const val PAUSE_BREAK_MS = 1000

    /** Pause between split-pair halves of one quote-split sentence. */
    const val PAUSE_TAG_MS = 100

    /** Peak loudness target in dBFS (Scribe `PEAK_TARGET_DBFS`). */
    const val PEAK_TARGET_DBFS = -1.0

    /** Linear peak target: 10^(-1/20), about 0.89125. */
    val PEAK_TARGET: Double = 10.0.pow(PEAK_TARGET_DBFS / 20.0)

    /**
     * Exact sample count for a pause length (Scribe `samples_for_ms`).
     *
     * Integer math `ms * rate // 1000`, no rounding loss. Uses [Long] for
     * the product so large chapter offsets never overflow [Int].
     */
    fun samplesForMs(durationMs: Int, sampleRateHz: Int = SAMPLE_RATE_HZ): Int {
        require(durationMs >= 0) { "pause must be non-negative, got $durationMs." }
        require(sampleRateHz > 0) { "sample_rate must be positive, got $sampleRateHz." }
        return ((durationMs.toLong() * sampleRateHz.toLong()) / 1000L).toInt()
    }

    /**
     * Integer milliseconds for a sample offset (Scribe `ms_for_samples`).
     *
     * Deterministic round-half-even via `Math.rint` (NOT `Math.round`,
     * which is half-up and rounds 100.5 to 101 instead of 100). At 24 kHz
     * 1 ms is exactly 24 samples, so pause boundaries map exactly and
     * only sub-sample lengths exercise the rounding.
     */
    fun msForSamples(samples: Long, sampleRateHz: Int = SAMPLE_RATE_HZ): Int {
        require(samples >= 0) { "samples must be non-negative, got $samples." }
        require(sampleRateHz > 0) { "sample_rate must be positive, got $sampleRateHz." }
        return Math.rint(samples.toDouble() * 1000.0 / sampleRateHz.toDouble()).toInt()
    }

    /**
     * Committed length at [dstRateHz] for [nativeSamples] at [srcRateHz
     * ] (Scribe `resample_mono` length half).
     *
     * `round(n * dst / src)` half-even on doubles via `Math.rint`.
     * Non-empty input commits at least 1 sample (Scribe clamps
     * `max(dst_len, 1)` for the interp output, so a 0 rounding still
     * yields 1 sample rather than an empty sentence).
     */
    fun resampledLength(nativeSamples: Int, srcRateHz: Int, dstRateHz: Int = SAMPLE_RATE_HZ): Int {
        require(nativeSamples >= 0) { "samples must be non-negative, got $nativeSamples." }
        require(srcRateHz > 0) { "src_rate must be positive, got $srcRateHz." }
        require(dstRateHz > 0) { "dst_rate must be positive, got $dstRateHz." }
        if (nativeSamples == 0) return 0
        val rounded = Math.rint(nativeSamples.toDouble() * dstRateHz.toDouble() / srcRateHz.toDouble()).toInt()
        return maxOf(rounded, 1)
    }

    /**
     * Minimal linear-interp resample for mono float audio (Scribe
     * `resample_mono` value half, no scipy dep).
     *
     * Same-rate input returns a copy. Empty input returns empty.
     * Otherwise the output length is [resampledLength] and values are
     * linear interpolation over `linspace(0, n-1)` in double precision,
     * matching `numpy.interp` on the shared range (no out-of-range
     * branch is reachable since both grids span exactly `[0, n-1]`).
     * Single-sample input fills the output with that sample (both grids
     * collapse to `[0.0]`, avoiding a divide by zero).
     */
    fun resampleMono(samples: FloatArray, srcRateHz: Int, dstRateHz: Int = SAMPLE_RATE_HZ): FloatArray {
        require(srcRateHz > 0) { "src_rate must be positive, got $srcRateHz." }
        require(dstRateHz > 0) { "dst_rate must be positive, got $dstRateHz." }
        if (srcRateHz == dstRateHz) return samples.copyOf()
        if (samples.isEmpty()) return FloatArray(0)
        val outLen = resampledLength(samples.size, srcRateHz, dstRateHz)
        if (samples.size == 1) {
            return FloatArray(outLen) { samples[0] }
        }
        val src = DoubleArray(samples.size) { samples[it].toDouble() }
        val out = FloatArray(outLen)
        if (outLen == 1) {
            out[0] = samples[0]
            return out
        }
        val step = (samples.size - 1).toDouble() / (outLen - 1).toDouble()
        for (j in 0 until outLen) {
            val pos = j.toDouble() * step
            val lo = pos.toInt()
            val hi = minOf(lo + 1, samples.size - 1)
            val frac = pos - lo.toDouble()
            out[j] = (src[lo] * (1.0 - frac) + src[hi] * frac).toFloat()
        }
        return out
    }

    /**
     * Representative loudness per voice: peak absolute sample
     * (Scribe `voice_peak_levels`).
     *
     * Measured over sentence audio only (pauses are added after
     * leveling, so padding never skews the measurement). Empty input
     * measures 0.0. Non-finite samples are skipped (spool PCM encodes
     * them as silence for the same reason).
     */
    fun voicePeakLevels(audiosByVoice: Map<String, List<FloatArray>>): Map<String, Float> {
        val peaks = LinkedHashMap<String, Float>()
        for ((voice, chunks) in audiosByVoice) {
            var peak = 0f
            for (chunk in chunks) {
                for (value in chunk) {
                    if (!value.isFinite()) continue
                    val magnitude = if (value < 0f) -value else value
                    if (magnitude > peak) peak = magnitude
                }
            }
            peaks[voice] = peak
        }
        return peaks
    }

    /**
     * One gain per voice: `target / peak`, silence peaks give 1.0
     * (Scribe `gains_for_peaks`).
     *
     * Peak, not RMS, per D-037: the same metric as the chapter peak cap
     * end to end, one deterministic pass, no silence threshold to tune.
     * Gain only, durations unchanged. Same peaks always give identical
     * gains (pure division, no randomness).
     */
    fun gainsForPeaks(
        peaks: Map<String, Float>,
        targetPeak: Double = PEAK_TARGET
    ): Map<String, Double> {
        require(targetPeak.isFinite() && targetPeak > 0.0) {
            "target_peak must be finite and positive, got $targetPeak."
        }
        val gains = LinkedHashMap<String, Double>()
        for ((voice, peak) in peaks) {
            val level = peak.toDouble()
            require(level.isFinite()) { "voice \"$voice\": peak is not finite." }
            gains[voice] = if (level > 0.0) targetPeak / level else 1.0
        }
        return gains
    }

    /**
     * Attenuate-only chapter peak cap gain (RN5 choice, D-095).
     *
     * Scribe always normalizes the whole buffer to the target
     * (`apply_loudness_gain`), which is correct for single-chapter PC
     * leveling but would defeat the book gain on device (every later
     * chapter would re-normalize to the target instead of keeping the
     * first-chapter reference). The device cap therefore only turns the
     * volume down: 1.0 when the assembled peak is at or under the
     * target (or non-positive/non-finite, treated as silence), else
     * `target / peak`. Gain only, durations unchanged.
     */
    fun capGainFor(assembledPeak: Double, targetPeak: Double = PEAK_TARGET): Double {
        require(targetPeak.isFinite() && targetPeak > 0.0) {
            "target_peak must be finite and positive, got $targetPeak."
        }
        if (!assembledPeak.isFinite() || assembledPeak <= 0.0) return 1.0
        if (assembledPeak <= targetPeak) return 1.0
        return targetPeak / assembledPeak
    }

    /** Linear gain to decibels for the manifest `gain_db` (20*log10). */
    fun dbForGain(gainLinear: Double): Double {
        require(gainLinear.isFinite() && gainLinear > 0.0) {
            "gain must be finite and positive, got $gainLinear."
        }
        return 20.0 * log10(gainLinear)
    }

    /** Decibels back to linear gain (10^(db/20)). */
    fun gainForDb(gainDb: Double): Double {
        require(gainDb.isFinite()) { "gain_db must be finite, got $gainDb." }
        return 10.0.pow(gainDb / 20.0)
    }

    /**
     * Pause in ms after one sentence of [blockType] (Scribe
     * `pause_after_sentence`).
     *
     * Heading sentences always take the heading pause; para/quote take
     * the sentence pause internally and the paragraph pause at block end.
     * Break blocks carry no sentences and throw (they contribute
     * standalone silence in the assembler, never a per-sentence pause).
     */
    fun pauseAfterSentence(blockType: String, isLastInBlock: Boolean): Int {
        if (blockType == "heading") return PAUSE_HEADING_MS
        if (blockType == "para" || blockType == "quote") {
            return if (isLastInBlock) PAUSE_PARA_MS else PAUSE_SENTENCE_MS
        }
        throw IllegalArgumentException(
            "block type '$blockType' carries no sentences; " +
                "break blocks contribute silence without timings."
        )
    }
}
