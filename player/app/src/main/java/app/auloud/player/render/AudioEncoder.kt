package app.auloud.player.render

import java.io.File
import java.io.IOException

/**
 * RN6: chapter audio encoder seam (Slice 10, D-092 plus D-093).
 *
 * The [ChapterAssembler] (RN5) streams 24 kHz mono float PCM into a
 * [ChapterEncoderSink]; this file defines the platform side of that seam
 * as a JVM-testable [AudioEncoder] plus all timestamp math, state handling
 * and error mapping. The real MediaCodec/MediaMuxer path lives in
 * `AndroidAudioEncoder.kt` (thin outer edge, `android.*` only there);
 * JVM tests use a fake encoder driven by the same [EncoderCore] rules.
 *
 * Data flow per chapter: assembly writes sentence audio plus silence
 * pauses chunk by chunk ([AudioEncoder.writePcm], which may block while
 * the platform drains output: that is the back-pressure contract, so
 * callers stream sentence by sentence and never hold the whole chapter),
 * then [AudioEncoder.finish] signals end of stream, drains the codec,
 * stops the muxer, checks the duration against the sample count and
 * renames the temp file into place (`.m4a.tmp` then rename). Failures are
 * shaped ([AudioEncoderException] subtypes naming the chapter), never raw
 * codec errors. RN7 owns manifest/JSON writing and applies
 * [EncoderOffset] there; this file only carries the measured offset value
 * ([EncoderConfig.encoderOffsetMs]) and the pure apply helper.
 *
 * API 24 safe: pure Kotlin plus `java.io.File` only. No `java.time`, no
 * Android types, no new dependency, no permission, no manifest change.
 */

/** Encoder settings for one device-rendered chapter (D-092). */
data class EncoderConfig(
    val sampleRateHz: Int = SAMPLE_RATE_HZ,
    val channelCount: Int = CHANNEL_COUNT,
    val bitRateBps: Int = BIT_RATE_BPS,
    /**
     * Measured encoder delay in milliseconds (D-093). Default 0 until
     * RN10 measures the Tab E constant; carried into [EncodedChapter] so
     * RN7 records the same value in the manifest and shifts timings with
     * [EncoderOffset] at write time. Readers need no correction.
     */
    val encoderOffsetMs: Int = 0
) {
    init {
        require(sampleRateHz > 0) {
            "encoder sample rate must be positive, got $sampleRateHz."
        }
        require(channelCount == 1) {
            "encoder supports mono only, got $channelCount."
        }
        require(bitRateBps > 0) {
            "encoder bit rate must be positive, got $bitRateBps."
        }
        require(encoderOffsetMs >= 0) {
            "encoder offset must be non-negative, got $encoderOffsetMs."
        }
    }

    companion object {
        /** Bundle-wide audio rate in Hz (matches [AssemblyMath]). */
        const val SAMPLE_RATE_HZ = 24000

        /** Device renders are mono (spec section 2). */
        const val CHANNEL_COUNT = 1

        /** Constrained target, about 64 kbps (spec section 2). */
        const val BIT_RATE_BPS = 64000

        /** Codec MIME selected at encoder creation. */
        const val MIME_TYPE = "audio/mp4a-latm"

        /**
         * AAC-LC profile selector. The platform file maps this to
         * `MediaCodecInfo.CodecProfileLevel.AACObjectLC` (same value 2);
         * the int lives here so this file stays free of `android.*`.
         */
        const val AAC_LC_PROFILE = 2
    }
}

/** Finished chapter audio: the renamed file plus its sample contract. */
data class EncodedChapter(
    /** Final path (after the `.tmp` rename, e.g. `audio/ch001.m4a`). */
    val filePath: String,
    /** Total PCM samples encoded (must equal the assembler count). */
    val sampleCount: Long,
    /** Chapter duration in ms (must equal `msForSamples(sampleCount)`). */
    val durationMs: Int,
    /** Offset carried from [EncoderConfig] for the RN7 manifest write. */
    val encoderOffsetMs: Int = 0
)

/** Base for every shaped encoder failure (messages name the chapter). */
open class AudioEncoderException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** The device has no AAC encoder (clear error, no fallback here). */
class NoAacEncoderException(
    chapterNumber: Int,
    cause: Throwable? = null
) : AudioEncoderException(
    "chapter $chapterNumber: no AAC encoder on this device " +
        "(device renders need AAC-LC in M4A from the platform encoder)",
    cause
)

/** Encoder configuration or start failed (format, profile, bitrate). */
class EncoderConfigException(
    chapterNumber: Int,
    detail: String,
    cause: Throwable? = null
) : AudioEncoderException("chapter $chapterNumber: encoder config failed ($detail)", cause)

/** Codec streaming or muxer failure (drain, track setup, write, stop). */
class MuxerException(
    chapterNumber: Int,
    detail: String,
    cause: Throwable? = null
) : AudioEncoderException("chapter $chapterNumber: muxer or codec failed ($detail)", cause)

/** Temp file, rename or output-dir failure. */
class EncoderIoException(
    chapterNumber: Int,
    path: String,
    detail: String,
    cause: Throwable? = null
) : AudioEncoderException(
    "chapter $chapterNumber: audio file failed for \"$path\" ($detail)",
    cause
)

/** Fed sample count or duration disagrees with the finish handshake. */
class DurationMismatchException(
    chapterNumber: Int,
    detail: String
) : AudioEncoderException("chapter $chapterNumber: duration check failed ($detail)")

/**
 * Shaped-error factories (JVM-testable error mapping).
 *
 * The platform encoder maps every `android.*` failure through these so
 * wording stays uniform and unit tests pin it without a device.
 */
object EncoderErrors {

    fun noEncoder(chapterNumber: Int, cause: Throwable? = null): NoAacEncoderException =
        NoAacEncoderException(chapterNumber, cause)

    fun config(chapterNumber: Int, detail: String, cause: Throwable? = null): EncoderConfigException =
        EncoderConfigException(chapterNumber, detail, cause)

    fun muxer(chapterNumber: Int, detail: String, cause: Throwable? = null): MuxerException =
        MuxerException(chapterNumber, detail, cause)

    fun io(
        chapterNumber: Int,
        path: String,
        detail: String,
        cause: Throwable? = null
    ): EncoderIoException = EncoderIoException(chapterNumber, path, detail, cause)

    fun mismatch(chapterNumber: Int, detail: String): DurationMismatchException =
        DurationMismatchException(chapterNumber, detail)
}

/**
 * Minimal encoder-input seam (RN5 defines the sink, RN6 implements it).
 *
 * Implementations accept any chunk split (one call per sentence or pause
 * is the current shape, never a contract). [writePcm] may block while the
 * platform drains output (back-pressure); callers keep streaming and never
 * buffer the chapter. [finish] signals end of stream exactly once, drains,
 * runs the duration check and renames the temp file, returning the final
 * file. After any failure only [abort] is valid. [abort] deletes the temp
 * file best-effort and is idempotent. No `android.*` in this interface.
 */
interface AudioEncoder {
    /** Bundle rate this encoder consumes (always 24000 here). */
    val sampleRateHz: Int

    /** One PCM chunk at [sampleRateHz], mono float (any length). */
    fun writePcm(chunk: FloatArray)

    /** End of chapter: total samples plus duration for the duration check. */
    fun finish(totalSamples: Long, durationMs: Int): EncodedChapter

    /** Drop the temp file and release the codec (idempotent). */
    fun abort()
}

/**
 * Presentation timestamps from sample counts (D-092: never wall clock).
 *
 * `offset * 1_000_000 / rate` in [Long] math, so 24 kHz maps exactly
 * (24000 samples give 1_000_000 us) and sub-millisecond offsets truncate
 * toward zero deterministically. Pure.
 */
object EncoderTimestamps {

    /** Microseconds for [sampleOffset] samples at [sampleRateHz]. */
    fun presentationTimeUs(sampleOffset: Long, sampleRateHz: Int): Long {
        require(sampleOffset >= 0) { "sample offset must be non-negative, got $sampleOffset." }
        require(sampleRateHz > 0) { "sample_rate must be positive, got $sampleRateHz." }
        return sampleOffset * 1_000_000L / sampleRateHz.toLong()
    }
}

/**
 * Measured encoder-delay hook (D-093).
 *
 * The RN10 beep chapter measures one constant per encoder; RN7 records it
 * as manifest `encoder_offset_ms` and shifts every written timing by it,
 * so readers need no per-format correction. This object is the pure apply
 * side: `raw + offset` with order and gaps preserved. Offset 0 is the
 * identity (fixture value until RN10 measures the Tab E constant).
 */
object EncoderOffset {

    /** Shifts one millisecond timing by [offsetMs] (both non-negative). */
    fun apply(rawMs: Int, offsetMs: Int): Int {
        require(rawMs >= 0) { "timing must be non-negative, got $rawMs." }
        require(offsetMs >= 0) { "encoder offset must be non-negative, got $offsetMs." }
        return rawMs + offsetMs
    }

    /**
     * Shifts a chapter's timings plus duration by [offsetMs].
     *
     * Returns the shifted sentence timings with the shifted duration;
     * gaps, order and `start < end` are preserved because every value
     * moves by the same constant. RN7 calls this at JSON write time.
     */
    fun applyToTimings(
        timings: List<AssemblySentenceTiming>,
        durationMs: Int,
        offsetMs: Int
    ): Pair<List<AssemblySentenceTiming>, Int> {
        require(durationMs >= 0) { "duration must be non-negative, got $durationMs." }
        require(offsetMs >= 0) { "encoder offset must be non-negative, got $offsetMs." }
        val shifted = timings.map { timing ->
            timing.copy(startMs = timing.startMs + offsetMs, endMs = timing.endMs + offsetMs)
        }
        return shifted to (durationMs + offsetMs)
    }
}

/** Float PCM to little-endian 16-bit mono bytes (codec input form). */
object Pcm16 {

    /** Converts [-1, 1] float to PCM-16 bytes (clamps, 2 bytes per sample). */
    fun encodeFloat(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            var value = samples[i]
            if (!value.isFinite()) value = 0f
            value = value.coerceIn(-1f, 1f)
            var code = (value * 32767f).toInt()
            if (code > 32767) code = 32767
            if (code < -32768) code = -32768
            out[i * 2] = (code and 0xFF).toByte()
            out[i * 2 + 1] = ((code shr 8) and 0xFF).toByte()
        }
        return out
    }
}

/**
 * Encoder state plus validation shared by the fake and the platform path.
 *
 * Counts accepted samples, computes chunk-start timestamps from counts
 * and enforces the finish handshake: fed samples must equal the declared
 * total, and the declared duration must equal
 * [AssemblyMath.msForSamples] of that total (same function the assembler
 * used, so agreement is exact, not tolerant). After [markFailed] (codec or
 * muxer broke) only [abort]-equivalent cleanup is valid: writes and
 * finishes throw. Pure and JVM-testable.
 */
class EncoderCore(
    private val chapterNumber: Int,
    private val config: EncoderConfig = EncoderConfig()
) {
    var acceptedSamples: Long = 0L
        private set
    var finished: Boolean = false
        private set
    var failed: Boolean = false
        private set

    /**
     * Records one chunk (validates, advances, returns its start PTS).
     *
     * Empty chunks are a no-op returning the current PTS. Callers check
     * finiteness before calling (non-finite PCM is a caller bug reported
     * at the write site, not codec state).
     */
    fun onWrite(count: Int): Long {
        if (failed) {
            throw EncoderErrors.muxer(chapterNumber, "encoder already failed, writes rejected", null)
        }
        if (finished) {
            throw EncoderErrors.mismatch(chapterNumber, "write after finish (encoder sink closed)")
        }
        require(count >= 0) { "chapter $chapterNumber: chunk size must be non-negative, got $count." }
        val ptsUs = EncoderTimestamps.presentationTimeUs(acceptedSamples, config.sampleRateHz)
        acceptedSamples += count.toLong()
        return ptsUs
    }

    /** Validates the finish handshake (throws [DurationMismatchException]). */
    fun onFinish(totalSamples: Long, durationMs: Int) {
        if (failed) {
            throw EncoderErrors.muxer(chapterNumber, "encoder already failed, finish rejected", null)
        }
        if (finished) {
            throw EncoderErrors.mismatch(chapterNumber, "finish called twice")
        }
        if (totalSamples <= 0L) {
            throw EncoderErrors.mismatch(chapterNumber, "no samples encoded (total $totalSamples)")
        }
        if (totalSamples != acceptedSamples) {
            throw EncoderErrors.mismatch(
                chapterNumber,
                "sample-count mismatch (fed $acceptedSamples, finish says $totalSamples)"
            )
        }
        val expectedMs = AssemblyMath.msForSamples(totalSamples, config.sampleRateHz)
        if (durationMs != expectedMs) {
            throw EncoderErrors.mismatch(
                chapterNumber,
                "duration mismatch (finish says $durationMs ms, " +
                    "$totalSamples samples at ${config.sampleRateHz} Hz need $expectedMs ms)"
            )
        }
        finished = true
    }

    /** Marks codec/muxer failure (writes and finishes throw from here on). */
    fun markFailed() {
        failed = true
    }
}

/** File seam for the temp-then-rename (JVM-testable, `java.io` in prod). */
interface EncoderFileIo {
    fun exists(path: String): Boolean
    fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean
    fun deleteIfExists(path: String)
    fun ensureParentDirs(path: String)
}

/** Production [EncoderFileIo] over `java.io.File` (API 24 safe). */
class JavaFileEncoderIo : EncoderFileIo {
    override fun exists(path: String): Boolean = File(path).exists()

    override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean =
        File(tmpPath).renameTo(File(targetPath))

    override fun deleteIfExists(path: String) {
        try {
            val file = File(path)
            if (file.isFile) file.delete()
        } catch (_: Exception) {
        }
    }

    override fun ensureParentDirs(path: String) {
        File(path).parentFile?.mkdirs()
    }
}

/**
 * Temp naming plus atomic rename for finished chapter audio.
 *
 * The muxer writes `<final>.tmp` (e.g. `audio/ch001.m4a.tmp`); only after
 * the codec and muxer stop cleanly does the temp rename to the final
 * path, so readers never see a half-written chapter. The retry loop is the
 * RN3 pattern (20 x 50 ms, delete-target fallback for the Windows
 * `renameTo`-returns-false case). Pure apart from the injected [io].
 */
object EncoderFiles {

    /** Temp suffix for in-progress chapter audio. */
    const val TMP_SUFFIX = ".tmp"

    /** Write retry budget (RN3 parity: 20 x 50 ms). */
    const val WRITE_RETRIES = 20

    /** Write retry delay in millis. */
    const val WRITE_RETRY_DELAY_MS = 50L

    /** Temp path for [finalPath] (`audio/ch001.m4a` gives `audio/ch001.m4a.tmp`). */
    fun tmpPathFor(finalPath: String): String {
        require(finalPath.isNotBlank()) { "encoder output path is blank." }
        return finalPath + TMP_SUFFIX
    }

    /** Canonical chapter file name (`1` gives `ch001.m4a`). */
    fun chapterFileName(chapterNumber: Int): String {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        return "ch%03d.m4a".format(chapterNumber)
    }

    /** Moves [tmpPath] to [targetPath] with the RN3 retry loop. */
    fun atomicRename(
        tmpPath: String,
        targetPath: String,
        io: EncoderFileIo,
        sleeper: (Long) -> Unit = Thread::sleep
    ): Result<Unit> {
        var attempt = 0
        while (true) {
            val renamed = try {
                io.renameTempToTarget(tmpPath, targetPath)
            } catch (_: Exception) {
                false
            }
            if (renamed) {
                io.deleteIfExists(tmpPath)
                return Result.success(Unit)
            }
            if (io.exists(targetPath)) io.deleteIfExists(targetPath)
            attempt++
            if (attempt >= WRITE_RETRIES) {
                io.deleteIfExists(tmpPath)
                return Result.failure(
                    IOException(
                        "$targetPath: cannot move temp file into place " +
                            "(finished audio kept nowhere)"
                    )
                )
            }
            try {
                sleeper(WRITE_RETRY_DELAY_MS)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Assembly-to-encoder adapter (the RN5 seam wired to RN6).
 *
 * Implements [ChapterEncoderSink] by forwarding to an [AudioEncoder]
 * while counting written samples, so a short assembler handshake fails
 * loudly before the codec finish runs. The finished [EncodedChapter] is
 * captured in [encoded] (null until [finish] succeeds).
 */
class AudioEncoderSinkAdapter(
    private val encoder: AudioEncoder,
    private val chapterNumber: Int
) : ChapterEncoderSink {
    override val sampleRateHz: Int
        get() = encoder.sampleRateHz

    var encoded: EncodedChapter? = null
        private set

    private var writtenSamples: Long = 0L
    private var finished: Boolean = false

    override fun writePcm(chunk: FloatArray) {
        check(!finished) { "chapter $chapterNumber: write after finish (encoder sink closed)." }
        encoder.writePcm(chunk)
        writtenSamples += chunk.size.toLong()
    }

    override fun finish(totalSamples: Long, durationMs: Int) {
        check(!finished) { "chapter $chapterNumber: finish called twice (encoder sink closed)." }
        if (writtenSamples != totalSamples) {
            throw EncoderErrors.mismatch(
                chapterNumber,
                "sink got $writtenSamples samples, assembler says $totalSamples"
            )
        }
        encoded = encoder.finish(totalSamples, durationMs)
        finished = true
    }
}

/**
 * One-call assembly-to-encoder path (what RN7/RN8 drive per chapter).
 *
 * Runs [ChapterAssembler.assemble] with an [AudioEncoderSinkAdapter]
 * around [encoder]: PCM streams sentence by sentence (no whole-chapter
 * hold beyond RN5's bound of one native plus one resampled sentence plus
 * one pause chunk) and the encoder's `.m4a.tmp`-then-rename flow produces
 * the final file. Aborts the encoder when assembly or encoding throws
 * before the finish handshake; writes no manifest or JSON (RN7 owns it).
 * Returns the assembly result (timings source) with the encoded file.
 */
object ChapterEncode {

    fun encode(
        chapterNumber: Int,
        blocks: List<AssemblyBlockMeta>,
        pcmFor: (AssemblySentenceMeta) -> FloatArray,
        bookGainsLinear: Map<String, Double> = emptyMap(),
        targetPeak: Double = AssemblyMath.PEAK_TARGET,
        encoder: AudioEncoder
    ): Pair<AssembledChapterResult, EncodedChapter> {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        require(encoder.sampleRateHz == AssemblyMath.SAMPLE_RATE_HZ) {
            "chapter $chapterNumber: encoder rate ${encoder.sampleRateHz} " +
                "is not ${AssemblyMath.SAMPLE_RATE_HZ}."
        }
        val adapter = AudioEncoderSinkAdapter(encoder, chapterNumber)
        try {
            val assembled = ChapterAssembler.assemble(
                chapterNumber = chapterNumber,
                blocks = blocks,
                pcmFor = pcmFor,
                bookGainsLinear = bookGainsLinear,
                targetPeak = targetPeak,
                sink = adapter
            )
            val encoded = adapter.encoded ?: throw EncoderErrors.mismatch(
                chapterNumber,
                "encoder produced no file (finish handshake missing)"
            )
            if (encoded.sampleCount != assembled.sampleCount ||
                encoded.durationMs != assembled.durationMs
            ) {
                throw EncoderErrors.mismatch(
                    chapterNumber,
                    "encoder file disagrees with assembly " +
                        "(audio ${encoded.sampleCount}/${encoded.durationMs} ms, " +
                        "assembly ${assembled.sampleCount}/${assembled.durationMs} ms)"
                )
            }
            return assembled to encoded
        } catch (e: Exception) {
            if (adapter.encoded == null) {
                try {
                    encoder.abort()
                } catch (_: Exception) {
                }
            }
            throw e
        }
    }
}
