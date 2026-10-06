package app.auloud.player.render

import app.auloud.player.bundle.ChapterText

/**
 * RN5: ordered assembly from the spool (Slice 10, D-094 plus D-095).
 *
 * Ports `scribe/audio/assemble.py` `assemble_chapter` exactly for pause
 * and timing arithmetic (250/500/800/1000/100 ms, integer pause samples,
 * half-even ms from running counts, end excludes pause, duration includes
 * the final pause, leading-break drop, sids 1..N, tag pauses from RN4
 * split-pair flags) plus `voice_peak_levels` and `gains_for_peaks` for
 * the book gain (silence gain 1.0, gain only, durations unchanged).
 * Resampling ports `scribe/tts/base.py` `resample_mono` via
 * [AssemblyMath].
 *
 * Inputs: block structure comes from the bundle chapter text
 * ([ChapterText] for spool chapters, vector blocks in tests); sentence
 * PCM comes from the RN4 spool (16-bit mono decoded to float at the
 * native rate) through a caller-supplied loader, so this file never
 * touches [SpoolIo] (which has no read op) and RN4 code is read, never
 * modified. Split-pair flags are the RN4 recomputed values from the
 * spool index (the bundle carries no split fields by design); tag pauses
 * apply only to adjacent same-block sentences sharing a non-null equal
 * flag, normal pauses everywhere else.
 *
 * Loudness (D-095): [BookGains.derive] builds one linear gain per role
 * from the first-rendered-chapter peaks (same formula as
 * `gains_for_peaks`: target over peak, silence gives 1.0); later chapters
 * apply those stored gains at assembly, then an attenuate-only chapter
 * cap ([AssemblyMath.capGainFor]) limits the assembled peak to the same
 * target. The cap is deliberately attenuate-only (unlike Scribe
 * `apply_loudness_gain`, which always normalizes): re-normalizing every
 * chapter would erase the first-chapter reference and reintroduce
 * night-to-night jumps. Gain only, durations unchanged. Readers ignore
 * the manifest `gain_db` (loudness is baked into the audio); RN7 stores
 * the derived decibel map.
 *
 * Memory: streaming assembly. Only one resampled sentence buffer plus
 * one pause chunk is held at a time; the whole-chapter float array is
 * never built. The cap needs the true assembled peak before streaming,
 * so assembly runs in two streaming passes over re-readable spool PCM
 * (measure, then stream with the cap): peak working set is one native
 * sentence plus one resampled sentence (typically well under 1 MB for
 * normal sentences, bounded by the longest sentence in the chapter)
 * plus one pause chunk (max 1000 ms, 24 k samples, 96 KB as float).
 * Tests use [CollectingEncoderSink], which counts samples and tracks
 * peak without holding audio.
 *
 * Encoder seam (minimal for RN6): [ChapterEncoderSink] takes 24 kHz mono
 * float chunks plus a finish handshake carrying the total sample count
 * and duration. RN6 implements the platform side (encoder config, buffer
 * feeding and back-pressure, end of stream, muxer track setup,
 * presentation times from sample counts, temp file then rename, duration
 * check against the sample count, encoder-delay hook, clear error when
 * no AAC encoder exists). `encoder_offset_ms` is applied at JSON write
 * time (RN7), not here: this file emits raw sample-count timings and the
 * fixture offset stays 0 until RN10 measures the Tab E constant.
 *
 * API 24 safe: pure Kotlin, no `java.time`, no Android types, no new
 * dependency, no permission, no manifest change.
 */

/** One assembly sentence: identity plus native shape (audio via loader). */
data class AssemblySentenceMeta(
    val sid: Int,
    val role: String,
    val nativeRateHz: Int,
    val nativeSamplesExpected: Int,
    val splitPair: Int?
)

/** One assembly block: type plus its sentences in order. */
data class AssemblyBlockMeta(
    val id: Int,
    val type: String,
    val sentences: List<AssemblySentenceMeta>
)

/** Timing for one sentence: `end_ms` excludes the trailing pause. */
data class AssemblySentenceTiming(
    val sid: Int,
    val startMs: Int,
    val endMs: Int
)

/**
 * Assembled chapter: timings in document order plus the chapter total.
 *
 * PCM itself is streamed to the sink, not held here. [bookGainsApplied]
 * maps each role seen to its linear gain (1.0 when no book gain was
 * passed); [capGain] is the attenuate-only chapter cap actually applied
 * (1.0 when the chapter already sat under the target);
 * [peakBeforeCap] is the true assembled peak after book gains and before
 * the cap (silence gives 0.0); [peakAfterCap] is the peak of what was
 * streamed.
 */
data class AssembledChapterResult(
    val timings: List<AssemblySentenceTiming>,
    val durationMs: Int,
    val sampleCount: Long,
    val sampleRateHz: Int,
    val bookGainsApplied: Map<String, Double>,
    val capGain: Double,
    val peakBeforeCap: Double,
    val peakAfterCap: Double
)

/**
 * Minimal encoder-input seam (RN5 defines, RN6 implements).
 *
 * The assembler streams 24 kHz mono float PCM in document order
 * (sentence audio plus silence pauses, already gain-scaled) and then
 * calls [finish] exactly once with the total sample count and duration
 * for the RN6 duration check. Implementations must accept any chunk
 * split (one call per sentence or pause is the current shape, never a
 * contract) and must fail loudly on IO or codec errors naming the
 * chapter. Back-pressure, EOS handling, muxer setup and the
 * temp-then-rename live on the RN6 side.
 */
interface ChapterEncoderSink {
    /** Bundle rate the sink consumes (always 24000 here). */
    val sampleRateHz: Int

    /** One PCM chunk at [sampleRateHz], mono float (any length). */
    fun writePcm(chunk: FloatArray)

    /** End of chapter: total samples plus duration for the RN6 check. */
    fun finish(totalSamples: Long, durationMs: Int)
}

/**
 * Test-only sink: counts samples and tracks peak, holds no audio.
 *
 * Proves the streaming shape without keeping the chapter in memory.
 */
class CollectingEncoderSink(
    override val sampleRateHz: Int = AssemblyMath.SAMPLE_RATE_HZ
) : ChapterEncoderSink {
    var totalSamples: Long = 0L
        private set
    var peak: Double = 0.0
        private set
    var chunks: Int = 0
        private set
    var finished: Boolean = false
        private set
    var finishSamples: Long = -1L
        private set
    var finishDurationMs: Int = -1
        private set

    override fun writePcm(chunk: FloatArray) {
        for (value in chunk) {
            val magnitude = if (value < 0f) -value.toDouble() else value.toDouble()
            if (magnitude > peak) peak = magnitude
        }
        totalSamples += chunk.size.toLong()
        chunks += 1
    }

    override fun finish(totalSamples: Long, durationMs: Int) {
        finished = true
        finishSamples = totalSamples
        finishDurationMs = durationMs
    }
}

/**
 * Book-level gain derivation (D-095, pure for JVM tests; RN7 stores it).
 *
 * From the first-rendered-chapter role peaks (RN4 per-role maxima at
 * engine level, pauses excluded): one linear gain per role,
 * `target / peak`, silence (0.0) gives 1.0. The decibel map
 * (`20*log10`, 1.0 maps to 0.0 dB) is the manifest `gain_db` shape
 * (keys a subset of narrator/dialogue, finite numbers; readers ignore
 * it). Unknown or blank roles fail fast (2.0 allows only the reserved
 * keys, so silent mis-leveling is worse than a named failure).
 */
object BookGains {

    /** Linear gains plus the manifest-ready decibel map. */
    data class Result(
        val linear: Map<String, Double>,
        val db: Map<String, Double>
    )

    fun derive(
        firstPeaks: Map<String, Float>,
        targetPeak: Double = AssemblyMath.PEAK_TARGET
    ): Result {
        require(targetPeak.isFinite() && targetPeak > 0.0) {
            "target_peak must be finite and positive, got $targetPeak."
        }
        val linear = LinkedHashMap<String, Double>()
        val db = LinkedHashMap<String, Double>()
        for ((role, peak) in firstPeaks.toSortedMap()) {
            require(role == "narrator" || role == "dialogue") {
                "book gain role must be narrator or dialogue, got \"$role\"."
            }
            val level = peak.toDouble()
            require(level.isFinite() && level >= 0.0) {
                "role \"$role\": peak must be finite and non-negative, got $peak."
            }
            val gain = if (level > 0.0) targetPeak / level else 1.0
            linear[role] = gain
            db[role] = AssemblyMath.dbForGain(gain)
        }
        return Result(linear = linear, db = db)
    }
}

/**
 * Ordered chapter assembly with streaming output.
 *
 * See the file KDoc for the port rules, loudness shape, memory bound
 * and encoder seam. All failure messages name the chapter and, where
 * applicable, the sentence id.
 */
object ChapterAssembler {

    /**
     * Assembles one chapter from re-readable native PCM.
     *
     * @param chapterNumber 1-based manifest index (messages only).
     * @param blocks block metas in document order (break blocks carry
     * no sentences; their silence is standalone, except leading breaks,
     * which are dropped so the first start is 0).
     * @param pcmFor returns the native-rate mono float PCM for a
     * sentence meta (must be re-readable: it is called twice, once per
     * pass; must return a non-empty array of exactly
     * [AssemblySentenceMeta.nativeSamplesExpected] finite samples).
     * @param bookGainsLinear per-role linear gains (missing roles read
     * as 1.0; pass [BookGains.derive] output for rendered chapters or
     * empty for the ungauged vector shape).
     * @param targetPeak chapter peak target (default -1 dBFS linear).
     * @param sink streaming PCM destination (must advertise 24000 Hz).
     */
    fun assemble(
        chapterNumber: Int,
        blocks: List<AssemblyBlockMeta>,
        pcmFor: (AssemblySentenceMeta) -> FloatArray,
        bookGainsLinear: Map<String, Double> = emptyMap(),
        targetPeak: Double = AssemblyMath.PEAK_TARGET,
        sink: ChapterEncoderSink
    ): AssembledChapterResult {
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        require(targetPeak.isFinite() && targetPeak > 0.0) {
            "target_peak must be finite and positive, got $targetPeak."
        }
        require(sink.sampleRateHz == AssemblyMath.SAMPLE_RATE_HZ) {
            "chapter $chapterNumber: sink rate ${sink.sampleRateHz} " +
                "is not ${AssemblyMath.SAMPLE_RATE_HZ}."
        }
        for ((role, gain) in bookGainsLinear) {
            require(role == "narrator" || role == "dialogue") {
                "chapter $chapterNumber: book gain role must be narrator or dialogue, got \"$role\"."
            }
            require(gain.isFinite() && gain > 0.0) {
                "chapter $chapterNumber: role \"$role\" gain must be finite and positive, got $gain."
            }
        }

        val ordered = flattenAndValidate(chapterNumber, blocks)
        if (ordered.isEmpty()) {
            throw IllegalArgumentException(
                "chapter $chapterNumber: no audio assembled (no sentences)."
            )
        }

        val peakBeforeCap = measurePeak(chapterNumber, ordered, pcmFor, bookGainsLinear)
        val capGain = AssemblyMath.capGainFor(peakBeforeCap, targetPeak)
        return streamChapter(
            chapterNumber, blocks, ordered, pcmFor, bookGainsLinear, capGain, peakBeforeCap, sink
        )
    }

    /**
     * Spool-backed assembly helper (reads RN4 types, modifies none).
     *
     * Builds block metas from [chapter] (types and order) plus [index]
     * (rate, split pair and role per sid) and streams through [assemble]
     * with [loadPcm] decoding each spool entry (16-bit mono via
     * `SpoolPcm.decodePcm16` in production, map lookup in tests). Fails
     * naming chapter plus sid when a spool entry is missing, the role
     * disagrees with the chapter speaker, or the decoded length drifts
     * from the index.
     */
    fun assembleFromSpool(
        chapterNumber: Int,
        chapter: ChapterText,
        index: SpoolChapterIndex,
        loadPcm: (SpoolSentenceEntry) -> FloatArray,
        bookGainsLinear: Map<String, Double> = emptyMap(),
        targetPeak: Double = AssemblyMath.PEAK_TARGET,
        sink: ChapterEncoderSink
    ): AssembledChapterResult {
        val bySid = index.sentences.associateBy { it.sid }
        val blocks = ArrayList<AssemblyBlockMeta>(chapter.blocks.size)
        for (block in chapter.blocks) {
            if (block.type == "break") {
                blocks.add(AssemblyBlockMeta(id = block.id, type = block.type, sentences = emptyList()))
                continue
            }
            val metas = ArrayList<AssemblySentenceMeta>(block.sentences.size)
            for (sentence in block.sentences) {
                val entry = bySid[sentence.sid] ?: throw IllegalArgumentException(
                    "chapter $chapterNumber sentence ${sentence.sid}: " +
                        "missing spool entry (render the chapter first)"
                )
                if (entry.role != sentence.speaker) {
                    throw IllegalArgumentException(
                        "chapter $chapterNumber sentence ${sentence.sid}: " +
                            "spool role \"${entry.role}\" disagrees with " +
                            "chapter speaker \"${sentence.speaker}\""
                    )
                }
                metas.add(
                    AssemblySentenceMeta(
                        sid = sentence.sid,
                        role = entry.role,
                        nativeRateHz = entry.sampleRateHz,
                        nativeSamplesExpected = entry.samples,
                        splitPair = entry.splitPair
                    )
                )
            }
            blocks.add(AssemblyBlockMeta(id = block.id, type = block.type, sentences = metas))
        }
        val pcmBySid = HashMap<Int, FloatArray>()
        return assemble(
            chapterNumber = chapterNumber,
            blocks = blocks,
            pcmFor = { meta ->
                pcmBySid[meta.sid] ?: run {
                    val entry = bySid[meta.sid] ?: throw IllegalArgumentException(
                        "chapter $chapterNumber sentence ${meta.sid}: missing spool entry"
                    )
                    val pcm = loadPcm(entry)
                    if (pcm.size != entry.samples) {
                        throw IllegalArgumentException(
                            "chapter $chapterNumber sentence ${meta.sid}: " +
                                "spool PCM has ${pcm.size} samples, index says ${entry.samples}"
                        )
                    }
                    pcmBySid[meta.sid] = pcm
                    pcm
                }
            },
            bookGainsLinear = bookGainsLinear,
            targetPeak = targetPeak,
            sink = sink
        )
    }

    private data class OrderedSentence(
        val blockId: Int,
        val blockType: String,
        val meta: AssemblySentenceMeta
    )

    private fun flattenAndValidate(
        chapterNumber: Int,
        blocks: List<AssemblyBlockMeta>
    ): List<OrderedSentence> {
        val ordered = ArrayList<OrderedSentence>()
        val seenSids = ArrayList<Int>()
        for (block in blocks) {
            if (block.type == "break") continue
            if (block.type != "heading" && block.type != "para" && block.type != "quote") {
                throw IllegalArgumentException(
                    "chapter $chapterNumber: unknown block type '${block.type}' (block ${block.id})."
                )
            }
            for (meta in block.sentences) {
                require(meta.sid >= 1) {
                    "chapter $chapterNumber: sid must be 1-based, got ${meta.sid}."
                }
                require(meta.nativeRateHz > 0) {
                    "chapter $chapterNumber sentence ${meta.sid}: " +
                        "native rate must be positive, got ${meta.nativeRateHz}."
                }
                require(meta.nativeSamplesExpected > 0) {
                    "chapter $chapterNumber sentence ${meta.sid}: " +
                        "native length must be positive, got ${meta.nativeSamplesExpected}."
                }
                if (meta.role != "narrator" && meta.role != "dialogue") {
                    throw IllegalArgumentException(
                        "chapter $chapterNumber sentence ${meta.sid} " +
                            "has role \"${meta.role}\" (need narrator or dialogue)"
                    )
                }
                ordered.add(OrderedSentence(block.id, block.type, meta))
                seenSids.add(meta.sid)
            }
        }
        if (seenSids != (1..seenSids.size).toList()) {
            throw IllegalArgumentException(
                "chapter $chapterNumber: sids out of order: ${seenSids.take(8)}" +
                    "${if (seenSids.size > 8) "..." else ""} (need 1..${seenSids.size})."
            )
        }
        return ordered
    }

    private fun loadChecked(
        chapterNumber: Int,
        meta: AssemblySentenceMeta,
        pcmFor: (AssemblySentenceMeta) -> FloatArray
    ): FloatArray {
        val pcm = pcmFor(meta)
        if (pcm.size != meta.nativeSamplesExpected) {
            throw IllegalArgumentException(
                "chapter $chapterNumber sentence ${meta.sid}: " +
                    "PCM has ${pcm.size} samples, expected ${meta.nativeSamplesExpected}"
            )
        }
        if (pcm.isEmpty()) {
            throw IllegalArgumentException(
                "chapter $chapterNumber: sentence ${meta.sid} has empty audio; " +
                    "empty sentences cannot take timings (need start_ms < end_ms)."
            )
        }
        for (value in pcm) {
            if (!value.isFinite()) {
                throw IllegalArgumentException(
                    "chapter $chapterNumber: sentence ${meta.sid} audio is not finite " +
                        "(NaN or inf from the engine)."
                )
            }
        }
        return pcm
    }

    private fun measurePeak(
        chapterNumber: Int,
        ordered: List<OrderedSentence>,
        pcmFor: (AssemblySentenceMeta) -> FloatArray,
        bookGainsLinear: Map<String, Double>
    ): Double {
        var peak = 0.0
        for (item in ordered) {
            val native = loadChecked(chapterNumber, item.meta, pcmFor)
            val resampled = AssemblyMath.resampleMono(native, item.meta.nativeRateHz)
            val gain = bookGainsLinear[item.meta.role] ?: 1.0
            for (sample in resampled) {
                val gained = (sample.toDouble() * gain).toFloat()
                if (!gained.isFinite()) {
                    throw IllegalArgumentException(
                        "chapter $chapterNumber: sentence ${item.meta.sid} " +
                            "gain overflows to non-finite."
                    )
                }
                val magnitude = if (gained < 0f) -gained.toDouble() else gained.toDouble()
                if (magnitude > peak) peak = magnitude
            }
        }
        return peak
    }

    @Suppress("LoopToCallChain")
    private fun streamChapter(
        chapterNumber: Int,
        blocks: List<AssemblyBlockMeta>,
        ordered: List<OrderedSentence>,
        pcmFor: (AssemblySentenceMeta) -> FloatArray,
        bookGainsLinear: Map<String, Double>,
        capGain: Double,
        peakBeforeCap: Double,
        sink: ChapterEncoderSink
    ): AssembledChapterResult {
        val timings = ArrayList<AssemblySentenceTiming>(ordered.size)
        var offset = 0L
        var peakAfterCap = 0.0
        val gainsApplied = LinkedHashMap<String, Double>()

        for (block in blocks) {
            if (block.type == "break") {
                if (timings.isEmpty()) continue
                val pauseSamples = AssemblyMath.samplesForMs(AssemblyMath.PAUSE_BREAK_MS)
                if (pauseSamples > 0) {
                    sink.writePcm(FloatArray(pauseSamples))
                }
                offset += pauseSamples.toLong()
                continue
            }
            for (pos in block.sentences.indices) {
                val meta = block.sentences[pos]
                val gain = bookGainsLinear[meta.role] ?: 1.0
                gainsApplied[meta.role] = gain
                val native = loadChecked(chapterNumber, meta, pcmFor)
                val resampled = AssemblyMath.resampleMono(native, meta.nativeRateHz)
                for (i in resampled.indices) {
                    var value = (resampled[i].toDouble() * gain).toFloat()
                    if (capGain != 1.0) {
                        value = (value.toDouble() * capGain).toFloat()
                    }
                    resampled[i] = value
                    val magnitude = if (value < 0f) -value.toDouble() else value.toDouble()
                    if (magnitude > peakAfterCap) peakAfterCap = magnitude
                }
                val startMs = AssemblyMath.msForSamples(offset)
                offset += resampled.size.toLong()
                val endMs = AssemblyMath.msForSamples(offset)
                if (!(startMs < endMs)) {
                    throw IllegalArgumentException(
                        "chapter $chapterNumber: sentence ${meta.sid} timing " +
                            "[$startMs, $endMs] is degenerate (sub-millisecond audio)."
                    )
                }
                timings.add(AssemblySentenceTiming(sid = meta.sid, startMs = startMs, endMs = endMs))
                sink.writePcm(resampled)
                val pauseMs = if (pos + 1 < block.sentences.size) {
                    val following = block.sentences[pos + 1]
                    if (meta.splitPair != null && meta.splitPair == following.splitPair) {
                        AssemblyMath.PAUSE_TAG_MS
                    } else {
                        AssemblyMath.pauseAfterSentence(block.type, false)
                    }
                } else {
                    AssemblyMath.pauseAfterSentence(block.type, true)
                }
                val pauseSamples = AssemblyMath.samplesForMs(pauseMs)
                if (pauseSamples > 0) {
                    sink.writePcm(FloatArray(pauseSamples))
                }
                offset += pauseSamples.toLong()
            }
        }

        if (timings.isEmpty() || offset == 0L) {
            throw IllegalArgumentException(
                "chapter $chapterNumber: no audio assembled (no sentences)."
            )
        }
        val durationMs = AssemblyMath.msForSamples(offset)
        var prevEnd: Int? = null
        var prevSid = 0
        for (timing in timings) {
            if (timing.startMs < 0 || !(timing.startMs < timing.endMs && timing.endMs <= durationMs)) {
                throw IllegalArgumentException(
                    "chapter $chapterNumber: sentence ${timing.sid} timing " +
                        "[${timing.startMs}, ${timing.endMs}] outside duration $durationMs."
                )
            }
            if (prevEnd != null && timing.startMs < prevEnd) {
                throw IllegalArgumentException(
                    "chapter $chapterNumber: sentence ${timing.sid} overlaps sentence $prevSid."
                )
            }
            prevEnd = timing.endMs
            prevSid = timing.sid
        }
        sink.finish(offset, durationMs)
        return AssembledChapterResult(
            timings = timings,
            durationMs = durationMs,
            sampleCount = offset,
            sampleRateHz = AssemblyMath.SAMPLE_RATE_HZ,
            bookGainsApplied = gainsApplied,
            capGain = capGain,
            peakBeforeCap = peakBeforeCap,
            peakAfterCap = peakAfterCap
        )
    }
}
