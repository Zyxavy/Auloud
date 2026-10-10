package app.auloud.player.render

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * RN6: platform AAC encoder plus M4A muxer (Slice 10, D-092).
 *
 * Thin outer edge over [AudioEncoder]: AAC-LC mono 24000 Hz about
 * 64 kbps (constrained setting per the spec) via `MediaCodec`, written
 * into an M4A container via `MediaMuxer`. All timestamp math
 * ([EncoderTimestamps] from sample counts, never wall clock), state
 * handling ([EncoderCore]) and error mapping ([EncoderErrors]) live in
 * the JVM-testable `AudioEncoder.kt`; this file only feeds input buffers
 * from sink chunks, drains output buffers into the muxer, handles end of
 * stream and renames the temp file. Mixed books stay legal because each
 * chapter file carries its own `.m4a` extension plus probe (spec 2.0
 * part 2); the manifest `audio` object still describes the shared
 * channel/rate/bitrate params (RN7 writes it).
 *
 * Flow: the muxer writes [tmpPath] (`<final>.tmp`); [finish] queues an
 * empty end-of-stream input buffer, drains until the codec EOS flag,
 * stops the codec and muxer, then [EncoderFiles.atomicRename] moves the
 * temp into place. Any failure aborts (temp deleted best-effort) and
 * throws a shaped [AudioEncoderException] naming the chapter; after a
 * failure only [abort] is valid. A missing platform encoder surfaces as
 * [NoAacEncoderException] with a clear message.
 *
 * API 24 safe with no version guard: `MediaCodec` (API 16),
 * `MediaCodec.createEncoderByType`, `configure`, input/output buffers
 * (API 16), `MediaMuxer` plus `MUXER_OUTPUT_MPEG_4` (API 18),
 * `getInputBuffer`/`getOutputBuffer` (API 21) and `KEY_AAC_PROFILE`
 * (API 16) are all older than `minSdk 24`. No `java.time`, no new
 * dependency, no permission, no manifest change. No service,
 * notification, wake-lock or UI code.
 */
class AndroidAudioEncoder(
    private val chapterNumber: Int,
    private val finalPath: String,
    private val config: EncoderConfig = EncoderConfig(),
    private val fileIo: EncoderFileIo = JavaFileEncoderIo()
) : AudioEncoder {

    override val sampleRateHz: Int = config.sampleRateHz

    private val tmpPath: String = EncoderFiles.tmpPathFor(finalPath)
    private val core = EncoderCore(chapterNumber, config)
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private var aborted: Boolean = false

    init {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        require(finalPath.isNotBlank()) { "chapter $chapterNumber: output path is blank." }
        try {
            fileIo.ensureParentDirs(tmpPath)
        } catch (e: Exception) {
            throw EncoderErrors.io(chapterNumber, tmpPath, "cannot create output dir (${e.message})", e)
        }
        val format = MediaFormat.createAudioFormat(
            EncoderConfig.MIME_TYPE,
            config.sampleRateHz,
            config.channelCount
        )
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, config.bitRateBps)
        val startedCodec: MediaCodec = try {
            MediaCodec.createEncoderByType(EncoderConfig.MIME_TYPE)
        } catch (e: Exception) {
            throw EncoderErrors.noEncoder(chapterNumber, e)
        }
        try {
            startedCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            startedCodec.start()
        } catch (e: Exception) {
            releaseCodecQuietly(startedCodec)
            throw EncoderErrors.config(
                chapterNumber,
                "AAC-LC mono ${config.sampleRateHz} Hz ${config.bitRateBps} bps (${e.message})",
                e
            )
        }
        codec = startedCodec
        try {
            muxer = MediaMuxer(tmpPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            abort()
            throw EncoderErrors.muxer(
                chapterNumber,
                "cannot open M4A output \"$tmpPath\" (${e.message})",
                e
            )
        }
    }

    /**
     * Feeds one float PCM chunk (back-pressure: blocks up to the buffer
     * timeouts while [drain] makes room, so callers stream and never hold
     * the chapter). Converts to PCM-16, queues input sub-chunks with PTS
     * from cumulative sample counts and drains after each queue.
     */
    override fun writePcm(chunk: FloatArray) {
        if (aborted) {
            throw IllegalStateException("chapter $chapterNumber: encoder aborted, writes rejected.")
        }
        val activeCodec = codec
            ?: throw EncoderErrors.config(chapterNumber, "encoder released", null)
        if (chunk.isEmpty()) return
        for (value in chunk) {
            if (!value.isFinite()) {
                throw IllegalArgumentException(
                    "chapter $chapterNumber: PCM has non-finite sample " +
                        "(NaN or inf from the engine)."
                )
            }
        }
        core.onWrite(chunk.size)
        val bytes = Pcm16.encodeFloat(chunk)
        val baseOffset = core.acceptedSamples - chunk.size.toLong()
        var bytePos = 0
        try {
            while (bytePos < bytes.size) {
                val inIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inIndex < 0) {
                    drain(terminal = false)
                    continue
                }
                val inBuf = activeCodec.getInputBuffer(inIndex)
                    ?: throw EncoderErrors.muxer(
                        chapterNumber,
                        "codec input buffer missing",
                        null
                    )
                inBuf.clear()
                var take = minOf(inBuf.remaining(), bytes.size - bytePos)
                take -= take % 2
                val ptsUs = EncoderTimestamps.presentationTimeUs(
                    baseOffset + bytePos / 2,
                    config.sampleRateHz
                )
                if (take <= 0) {
                    activeCodec.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
                    drain(terminal = false)
                    continue
                }
                inBuf.put(bytes, bytePos, take)
                activeCodec.queueInputBuffer(inIndex, 0, take, ptsUs, 0)
                bytePos += take
                drain(terminal = false)
            }
        } catch (e: AudioEncoderException) {
            core.markFailed()
            throw e
        } catch (e: Exception) {
            core.markFailed()
            throw EncoderErrors.muxer(chapterNumber, "codec feed failed (${e.message})", e)
        }
    }

    /**
     * Signals end of stream, drains until the codec EOS flag, stops the
     * codec and muxer, runs the duration check and renames the temp file.
     * The empty EOS input buffer (API 16 path) carries the PTS of the
     * total sample count.
     */
    override fun finish(totalSamples: Long, durationMs: Int): EncodedChapter {
        if (aborted) {
            throw IllegalStateException("chapter $chapterNumber: encoder aborted, finish rejected.")
        }
        val activeCodec = codec
            ?: throw EncoderErrors.config(chapterNumber, "encoder released", null)
        try {
            core.onFinish(totalSamples, durationMs)
        } catch (e: Exception) {
            abort()
            throw e
        }
        try {
            val eosPtsUs = EncoderTimestamps.presentationTimeUs(totalSamples, config.sampleRateHz)
            var queuedEos = false
            var polls = 0
            while (!queuedEos) {
                if (polls++ > EOS_QUEUE_MAX_POLLS) {
                    throw EncoderErrors.muxer(
                        chapterNumber,
                        "codec would not take end of stream",
                        null
                    )
                }
                val inIndex = activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                if (inIndex < 0) {
                    drain(terminal = false)
                    continue
                }
                activeCodec.queueInputBuffer(
                    inIndex,
                    0,
                    0,
                    eosPtsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
                queuedEos = true
            }
            drain(terminal = true)
        } catch (e: AudioEncoderException) {
            core.markFailed()
            abort()
            throw e
        } catch (e: Exception) {
            core.markFailed()
            abort()
            throw EncoderErrors.muxer(chapterNumber, "finish failed (${e.message})", e)
        }
        try {
            activeCodec.stop()
        } catch (e: Exception) {
            abort()
            throw EncoderErrors.muxer(chapterNumber, "codec stop failed (${e.message})", e)
        }
        try {
            activeCodec.release()
        } catch (_: Exception) {
        }
        codec = null
        val activeMuxer = muxer
        try {
            if (muxerStarted) activeMuxer?.stop()
        } catch (e: Exception) {
            abort()
            throw EncoderErrors.muxer(chapterNumber, "muxer stop failed (${e.message})", e)
        }
        try {
            activeMuxer?.release()
        } catch (_: Exception) {
        }
        muxer = null
        muxerStarted = false
        val renamed = EncoderFiles.atomicRename(tmpPath, finalPath, fileIo)
        if (renamed.isFailure) {
            val cause = renamed.exceptionOrNull()
            throw EncoderErrors.io(
                chapterNumber,
                finalPath,
                "cannot move finished audio into place (${cause?.message})",
                cause
            )
        }
        return EncodedChapter(
            filePath = finalPath,
            sampleCount = totalSamples,
            durationMs = durationMs,
            encoderOffsetMs = config.encoderOffsetMs
        )
    }

    /** Stops and releases the codec and muxer, deletes the temp file. */
    override fun abort() {
        aborted = true
        val stoppingCodec = codec
        codec = null
        if (stoppingCodec != null) {
            try {
                stoppingCodec.stop()
            } catch (_: Exception) {
            }
            try {
                stoppingCodec.release()
            } catch (_: Exception) {
            }
        }
        val stoppingMuxer = muxer
        muxer = null
        if (stoppingMuxer != null) {
            try {
                if (muxerStarted) stoppingMuxer.stop()
            } catch (_: Exception) {
            }
            try {
                stoppingMuxer.release()
            } catch (_: Exception) {
            }
        }
        muxerStarted = false
        try {
            fileIo.deleteIfExists(tmpPath)
        } catch (_: Exception) {
        }
    }

    /**
     * Drains available codec output into the muxer.
     *
     * Non-terminal calls return on `TRY_AGAIN_LATER` (input still open, so
     * more output may arrive later); the terminal call keeps polling until
     * the EOS flag, capped at [TERMINAL_DRAIN_MAX_POLLS] so a stuck codec
     * fails loudly instead of hanging the render. Codec-config bytes ride
     * the format change and are never written; the first format change
     * starts the muxer.
     */
    private fun drain(terminal: Boolean) {
        val activeCodec = codec
            ?: throw EncoderErrors.config(chapterNumber, "encoder released", null)
        val activeMuxer = muxer
            ?: throw EncoderErrors.muxer(chapterNumber, "muxer released", null)
        val info = MediaCodec.BufferInfo()
        var polls = 0
        while (true) {
            if (terminal && polls++ > TERMINAL_DRAIN_MAX_POLLS) {
                throw EncoderErrors.muxer(
                    chapterNumber,
                    "codec did not finish (EOS wait timed out)",
                    null
                )
            }
            val outIndex = try {
                activeCodec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (e: Exception) {
                throw EncoderErrors.muxer(chapterNumber, "codec output failed (${e.message})", e)
            }
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (terminal) continue else return
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw EncoderErrors.muxer(chapterNumber, "codec changed format twice", null)
                }
                try {
                    trackIndex = activeMuxer.addTrack(activeCodec.outputFormat)
                    activeMuxer.start()
                } catch (e: Exception) {
                    throw EncoderErrors.muxer(
                        chapterNumber,
                        "muxer track setup failed (${e.message})",
                        e
                    )
                }
                muxerStarted = true
                continue
            }
            if (outIndex < 0) continue
            val outBuf = activeCodec.getOutputBuffer(outIndex)
                ?: throw EncoderErrors.muxer(chapterNumber, "codec output buffer missing", null)
            try {
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // Codec-config bytes ride the format change, never the muxer.
                } else if (info.size > 0) {
                    if (!muxerStarted) {
                        throw EncoderErrors.muxer(
                            chapterNumber,
                            "codec produced data before track setup",
                            null
                        )
                    }
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    try {
                        activeMuxer.writeSampleData(trackIndex, outBuf, info)
                    } catch (e: Exception) {
                        throw EncoderErrors.muxer(
                            chapterNumber,
                            "muxer write failed (${e.message})",
                            e
                        )
                    }
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            } finally {
                try {
                    activeCodec.releaseOutputBuffer(outIndex, false)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun releaseCodecQuietly(startedCodec: MediaCodec) {
        try {
            startedCodec.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Input/output buffer wait per poll (microseconds). */
        const val INPUT_TIMEOUT_US = 10_000L

        /** Output buffer wait per poll (microseconds). */
        const val OUTPUT_TIMEOUT_US = 10_000L

        /** Cap on EOS-queue polls (fail loudly instead of spinning). */
        const val EOS_QUEUE_MAX_POLLS = 500

        /** Cap on terminal drain polls (about 10 s at the output timeout). */
        const val TERMINAL_DRAIN_MAX_POLLS = 1000
    }
}
