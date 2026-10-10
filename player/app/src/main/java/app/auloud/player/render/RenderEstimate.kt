package app.auloud.player.render

/**
 * RN8: render storage-estimate math (Slice 10).
 *
 * Before a render starts (and before each chapter), the service checks
 * free disk space against the bytes the remaining work needs: encoded
 * audio plus temp spool. Both rates are injectable so JVM tests pin the
 * math and RN11 can retune from Tab E numbers; the service supplies the
 * live free-bytes reading through [RenderSignalInputs].
 *
 * Rate sources (plan section 8 plus D-104):
 * - audio: AAC-LC mono 24 kHz about 64 kbps, which is 8000 bytes per
 *   second, or 8 bytes per audio millisecond (container overhead ignored
 *   and documented: M4A framing is well under 1% of a chapter).
 * - spool: about 86 MB of 16-bit mono PCM per 30 minutes of audio
 *   (plan figure), which is 86 MiB per 1.8M ms, about 50.09 bytes/ms.
 *   Spool is deleted per chapter after finalize, so the check covers the
 *   worst case (whole remaining plan spooled at once) rather than the
 *   steady state.
 *
 * Unknown chapter durations (RN9 owns measured durations plus the RTF
 * range; the service has none yet) fall back to [DEFAULT_CHAPTER_AUDIO_MS]
 * and are counted in [StorageEstimate.unknownChapters], so the service can
 * log how rough the number is. The default is a conservative 30 minutes:
 * over-estimating pauses early (safe), under-estimating fills the disk.
 *
 * API 24 safe: pure Kotlin, no Android types, no `java.time`.
 */
object RenderEstimates {

    /** Audio bytes per audio millisecond at 64 kbps (8000 B/s). */
    const val AUDIO_BYTES_PER_MS = 8.0

    /**
     * Spool bytes per audio millisecond (86 MiB per 30 min:
     * 86 * 1024 * 1024 / 1_800_000, about 50.09).
     */
    const val SPOOL_BYTES_PER_MS = 50.09

    /** 30 minutes in milliseconds (fallback chapter length, see file KDoc). */
    const val DEFAULT_CHAPTER_AUDIO_MS = 1_800_000L

    /** One storage estimate: what the remaining work needs. */
    data class StorageEstimate(
        /** Audio milliseconds covered (known plus fallback). */
        val audioMs: Long,
        /** Encoded-audio bytes for [audioMs]. */
        val audioBytes: Long,
        /** Temp-spool bytes for [audioMs]. */
        val spoolBytes: Long,
        /** Total bytes the work needs ([audioBytes] plus [spoolBytes]). */
        val totalBytes: Long,
        /** Chapters whose duration was unknown (fallback used). */
        val unknownChapters: Int
    )

    /**
     * Estimates one span of [audioMs] milliseconds of audio.
     *
     * @param bytesPerMs audio bytes per ms (default [AUDIO_BYTES_PER_MS]).
     * @param spoolBytesPerMs spool bytes per ms (default [SPOOL_BYTES_PER_MS]).
     */
    fun estimateForAudioMs(
        audioMs: Long,
        bytesPerMs: Double = AUDIO_BYTES_PER_MS,
        spoolBytesPerMs: Double = SPOOL_BYTES_PER_MS
    ): StorageEstimate {
        require(audioMs >= 0) { "audioMs must be >= 0, got $audioMs." }
        require(bytesPerMs.isFinite() && bytesPerMs >= 0.0) {
            "bytesPerMs must be finite and >= 0, got $bytesPerMs."
        }
        require(spoolBytesPerMs.isFinite() && spoolBytesPerMs >= 0.0) {
            "spoolBytesPerMs must be finite and >= 0, got $spoolBytesPerMs."
        }
        val audioBytes = (audioMs * bytesPerMs).toLong()
        val spoolBytes = (audioMs * spoolBytesPerMs).toLong()
        return StorageEstimate(
            audioMs = audioMs,
            audioBytes = audioBytes,
            spoolBytes = spoolBytes,
            totalBytes = audioBytes + spoolBytes,
            unknownChapters = 0
        )
    }

    /**
     * Estimates the remaining plan chapters.
     *
     * @param ordered 0-based chapter positions left to render, in order.
     * @param audioMsByChapter known audio milliseconds per position (null
     * or absent means unknown and uses [defaultAudioMs]).
     */
    fun estimateForPlan(
        ordered: List<Int>,
        audioMsByChapter: Map<Int, Long?> = emptyMap(),
        bytesPerMs: Double = AUDIO_BYTES_PER_MS,
        spoolBytesPerMs: Double = SPOOL_BYTES_PER_MS,
        defaultAudioMs: Long = DEFAULT_CHAPTER_AUDIO_MS
    ): StorageEstimate {
        require(defaultAudioMs >= 0) { "defaultAudioMs must be >= 0, got $defaultAudioMs." }
        var audioMs = 0L
        var unknown = 0
        for (pos in ordered) {
            val known = audioMsByChapter[pos]
            if (known != null && known >= 0) {
                audioMs += known
            } else {
                audioMs += defaultAudioMs
                unknown++
            }
        }
        return estimateForAudioMs(audioMs, bytesPerMs, spoolBytesPerMs)
            .copy(unknownChapters = unknown)
    }

    /**
     * Bytes the remaining work needs (the `requiredBytes` the guard
     * compares against free space). Thin alias over [estimateForPlan] so
     * the service passes estimate inputs without mapping fields by hand.
     */
    fun requiredBytes(
        ordered: List<Int>,
        audioMsByChapter: Map<Int, Long?> = emptyMap(),
        bytesPerMs: Double = AUDIO_BYTES_PER_MS,
        spoolBytesPerMs: Double = SPOOL_BYTES_PER_MS,
        defaultAudioMs: Long = DEFAULT_CHAPTER_AUDIO_MS
    ): Long = estimateForPlan(ordered, audioMsByChapter, bytesPerMs, spoolBytesPerMs, defaultAudioMs)
        .totalBytes
}
