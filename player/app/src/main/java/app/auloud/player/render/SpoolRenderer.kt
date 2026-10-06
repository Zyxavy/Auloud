package app.auloud.player.render

import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence
import app.auloud.player.ingest.BLOCK_BREAK
import app.auloud.player.ingest.BLOCK_HEADING
import app.auloud.player.ingest.BLOCK_PARA
import app.auloud.player.ingest.BLOCK_QUOTE
import app.auloud.player.ingest.BlockSentences
import app.auloud.player.ingest.DialogueTagger
import app.auloud.player.ingest.IngestBlock
import app.auloud.player.ingest.IngestChapter
import app.auloud.player.ingest.IngestSentence
import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import app.auloud.player.tts.SynthesizedAudio
import app.auloud.player.tts.TtsEngine
import app.auloud.player.tts.TtsRole
import kotlinx.coroutines.CancellationException

/**
 * RN4: one spooled sentence (what RN5 assembles from).
 *
 * `sid` plus `role` (the kind encoding: narrator/dialogue) locate the
 * sentence in reading order; `sampleRateHz` plus `samples` size the 24 kHz
 * resample; `splitPair` links same-block split-pair halves (100 ms tag
 * pause downstream); `peak` is the sentence peak for the D-095 levels.
 * `skipped` marks resume hits (spooled false means synthesized this run).
 */
data class SpooledSentence(
    val sid: Int,
    val role: TtsRole,
    val file: String,
    val sampleRateHz: Int,
    val samples: Int,
    val splitPair: Int?,
    val peak: Float,
    val skipped: Boolean
)

/**
 * RN4: completed chapter spool (per-role levels included, gain excluded).
 *
 * `peakNarrator`/`peakDialogue` are the max sentence peaks per role over
 * the whole chapter (spooled plus resume-skipped): the D-095 book-gain
 * input. Gain is NOT applied here (spool bytes stay at engine level, so
 * RN5 can derive one book gain from the first chapter); 0 means the role
 * had no sentences. `retried` lists sids that needed the one retry;
 * `splitFallbackBlocks` lists block ids where the re-tagged sentence count
 * did not match the bundle (split flags left null there, normal pauses
 * apply); `taggerWarnings` carries the shared tagger's own notices.
 */
data class ChapterSpoolSummary(
    val chapterPos: Int,
    val chapterNumber: Int,
    val fingerprint: RenderFingerprint,
    val sentences: List<SpooledSentence>,
    val peakNarrator: Float,
    val peakDialogue: Float,
    val spooled: Int,
    val skipped: Int,
    val retried: List<Int>,
    val splitFallbackBlocks: List<Int>,
    val taggerWarnings: List<String>
)

/**
 * RN4: chapter spool outcome.
 *
 * `Failed` names the chapter (1-based manifest index) plus the sentence
 * id that defeated both attempts, so the report points at one line.
 * `Cancelled` is cooperative (checked between sentences) and keeps the
 * partial spool plus index, so resume continues where it stopped.
 */
sealed interface ChapterSpoolOutcome {
    data class Completed(val summary: ChapterSpoolSummary) : ChapterSpoolOutcome
    data class Failed(
        val chapterPos: Int,
        val chapterNumber: Int,
        val sid: Int,
        val reason: String
    ) : ChapterSpoolOutcome
    data class Cancelled(
        val chapterPos: Int,
        val chapterNumber: Int,
        val sidsDone: Int
    ) : ChapterSpoolOutcome
}

/**
 * RN4: job-level spool outcome for [SpoolRenderer.renderJobChapters].
 *
 * The job inside is advanced only through [RenderJobs.onChapterDone] (RN3
 * conventions reused, never a parallel tracker): `Finished` carries the
 * DONE job after the last chapter, `ChapterFailed` and `Cancelled` carry
 * the still-RUNNING job so the caller (RN8) transitions it to FAILED,
 * PAUSED or CANCELLED with the reason attached.
 */
sealed interface JobSpoolOutcome {
    data class Finished(val job: RenderJob) : JobSpoolOutcome
    data class ChapterFailed(
        val job: RenderJob,
        val failure: ChapterSpoolOutcome.Failed
    ) : JobSpoolOutcome
    data class Cancelled(
        val job: RenderJob,
        val at: ChapterSpoolOutcome.Cancelled
    ) : JobSpoolOutcome
}

/**
 * RN4: split-pair recompute for one chapter (internal).
 *
 * The bundle carries no split-pair fields by design (D-087, IN7 drops
 * them on write), so the renderer recomputes them through the shared
 * [DialogueTagger] path (reused, never forked): each block's bundle
 * sentences join back into the paragraph IN6 saw (exact per the IN5
 * spacing guarantee) and the tagger re-splits it. [splitBySid] maps every
 * bundle sid to its split-pair id (null when the sentence needs no tag
 * pause); blocks whose re-tagged count mismatches the bundle land in
 * [fallbackBlocks] with null flags (normal pauses apply there).
 */
internal data class SplitRecompute(
    val splitBySid: Map<Int, Int?>,
    val fallbackBlocks: List<Int>,
    val warnings: List<String>
)

/**
 * RN4: voice-batched synthesis passes with spool (Slice 10 plan RN4, D-091).
 *
 * Per chapter: pass 1 synthesizes every narrator sentence in sid order,
 * pass 2 every dialogue sentence in sid order (pass 3 assembles in RN5),
 * so the engine and voice switch at most once per chapter instead of once
 * per sentence (about 800 ms per switch on System TTS). Each sentence
 * spools to its own 16-bit mono PCM file keyed by chapter, sid and voice
 * fingerprint; memory stays flat (one sentence in RAM at a time, the
 * per-sentence index is small metadata only, no large allocations in the
 * sentence loop beyond the sentence buffers themselves).
 *
 * Resume: spool files plus the incremental index survive a kill (RN3
 * INTERRUPTED jobs re-enter here); entries whose PCM exists under the
 * current fingerprint are skipped, a fingerprint mismatch invalidates the
 * chapter spool (stale files deleted best-effort, everything re-renders).
 * One retry per sentence, then the chapter fails naming chapter plus sid.
 * Cancellation is cooperative between sentences. Engines release between
 * passes only when the roles use distinct instances (two Piper models must
 * not sit loaded together); a shared engine stays loaded, and both stay
 * loaded after the chapter (the caller amortizes load cost across
 * chapters and releases when the job pauses or finishes).
 *
 * Pure Kotlin plus the injected seams ([SpoolIo], resolved voices,
 * chapter loader): JVM-testable with a fake engine. No service,
 * notification, wake lock, MediaCodec, encoder or UI code (the encoder
 * interface is RN6; this file only spools PCM). No `java.time`.
 */
object SpoolRenderer {

    /**
     * Spools one chapter through both voice passes.
     *
     * @param chapterPos 0-based position in the render plan (job tracking,
     * mirroring [RenderJobs.onChapterDone]).
     * @param chapterNumber 1-based manifest chapter index (spool names plus
     * failure messages; for contiguous device books this is `chapterPos + 1`).
     * @param chapter the parsed `text/chNNN.json` (untimed 2.0 sentences).
     * @param voices resolved plus validated up front via [RenderVoices].
     * @param spoolDir spool workspace dir (RN8 owns the location).
     * @param shouldCancel cooperative cancel, checked between sentences.
     * @param onReleasePass releases a pass engine (production releases the
     * Piper instance; tests record the call order against synth calls).
     * @param onSentenceDone per-finished-sentence hook (RN8 progress).
     */
    suspend fun renderChapter(
        chapterPos: Int,
        chapterNumber: Int,
        chapter: ChapterText,
        voices: ResolvedRenderVoices,
        spoolDir: String,
        io: SpoolIo,
        shouldCancel: () -> Boolean = { false },
        onReleasePass: (TtsEngine) -> Unit = {},
        onSentenceDone: (Int) -> Unit = {}
    ): ChapterSpoolOutcome {
        val ordered = chapter.sentencesInOrder()
        for (sentence in ordered) {
            if (sentence.speaker != SPEAKER_NARRATOR && sentence.speaker != SPEAKER_DIALOGUE) {
                return ChapterSpoolOutcome.Failed(
                    chapterPos = chapterPos,
                    chapterNumber = chapterNumber,
                    sid = sentence.sid,
                    reason = "chapter $chapterNumber sentence ${sentence.sid} " +
                        "has speaker \"${sentence.speaker}\" " +
                        "(need narrator or dialogue)"
                )
            }
            if (sentence.text.isBlank()) {
                return ChapterSpoolOutcome.Failed(
                    chapterPos = chapterPos,
                    chapterNumber = chapterNumber,
                    sid = sentence.sid,
                    reason = "chapter $chapterNumber sentence ${sentence.sid} " +
                        "has blank text (nothing to synthesize)"
                )
            }
        }

        val recompute = recomputeSplitPairs(chapterNumber, chapter)
        val narratorSids = ordered
            .filter { it.speaker == SPEAKER_NARRATOR }
            .map { it.sid }
        val dialogueSids = ordered
            .filter { it.speaker == SPEAKER_DIALOGUE }
            .map { it.sid }
        val bySid = ordered.associateBy { it.sid }

        val fpTag = voices.fingerprint.fileTag()
        val indexPath = SpoolFiles.indexPath(spoolDir, chapterNumber)
        val entries = LinkedHashMap<Int, SpoolSentenceEntry>()
        val skippedSids = LinkedHashSet<Int>()
        val retried = ArrayList<Int>()
        var spooled = 0
        var skipped = 0

        val prior = readUsableIndex(io, indexPath, chapterNumber, voices.fingerprint)
        if (prior != null) {
            for (entry in prior.sentences) {
                if (entry.sid !in bySid) continue
                val path = SpoolFiles.path(spoolDir, entry.file)
                if (entry.file == SpoolFiles.pcmName(chapterNumber, entry.sid, fpTag) &&
                    io.exists(path)
                ) {
                    entries[entry.sid] = entry
                }
            }
        } else {
            invalidateChapterSpool(io, spoolDir, chapterNumber, indexPath)
        }

        suspend fun persist(): Boolean {
            return try {
                io.writeText(indexPath, SpoolIndex.render(currentIndex(chapterNumber, voices, entries)))
                true
            } catch (_: Exception) {
                false
            }
        }

        suspend fun pass(role: TtsRole, sids: List<Int>): ChapterSpoolOutcome? {
            val binding = if (role == TtsRole.Narrator) voices.narrator else voices.dialogue
            for (sid in sids) {
                if (shouldCancel()) {
                    persist()
                    return ChapterSpoolOutcome.Cancelled(chapterPos, chapterNumber, entries.size)
                }
                val carried = entries[sid]
                if (carried != null) {
                    skipped++
                    skippedSids.add(sid)
                    onSentenceDone(sid)
                    continue
                }
                val sentence = bySid[sid]
                    ?: return ChapterSpoolOutcome.Failed(
                        chapterPos, chapterNumber, sid,
                        "chapter $chapterNumber sentence $sid is missing " +
                            "(internal error: sid not in chapter)"
                    )
                val audio = try {
                    synthWithRetry(binding, sentence.text, onRetried = { retried.add(sid) })
                } catch (e: CancellationException) {
                    persist()
                    throw e
                } catch (e: SentenceSpoolException) {
                    persist()
                    return ChapterSpoolOutcome.Failed(
                        chapterPos, chapterNumber, sid,
                        "chapter $chapterNumber sentence $sid: " +
                            "synthesis failed after 1 retry (${e.cause?.message})"
                    )
                }
                if (audio.sampleRateHz <= 0 || audio.samples.isEmpty()) {
                    persist()
                    return ChapterSpoolOutcome.Failed(
                        chapterPos, chapterNumber, sid,
                        "chapter $chapterNumber sentence $sid: engine returned " +
                            "${audio.samples.size} samples at ${audio.sampleRateHz} Hz " +
                            "(nothing to spool)"
                    )
                }
                val path = SpoolFiles.pcmPath(spoolDir, chapterNumber, sid, fpTag)
                try {
                    io.writeBytes(path, SpoolPcm.encodeFloatToPcm16(audio.samples))
                } catch (e: Exception) {
                    persist()
                    return ChapterSpoolOutcome.Failed(
                        chapterPos, chapterNumber, sid,
                        "chapter $chapterNumber sentence $sid: " +
                            "cannot write spool file (${e.message})"
                    )
                }
                entries[sid] = SpoolSentenceEntry(
                    sid = sid,
                    role = if (role == TtsRole.Narrator) SPEAKER_NARRATOR else SPEAKER_DIALOGUE,
                    file = SpoolFiles.pcmName(chapterNumber, sid, fpTag),
                    sampleRateHz = audio.sampleRateHz,
                    samples = audio.samples.size,
                    splitPair = recompute.splitBySid[sid],
                    peak = SpoolPcm.peakOf(audio.samples)
                )
                spooled++
                if (!persist()) {
                    return ChapterSpoolOutcome.Failed(
                        chapterPos, chapterNumber, sid,
                        "chapter $chapterNumber sentence $sid: " +
                            "cannot write spool index (resume bookkeeping lost)"
                    )
                }
                onSentenceDone(sid)
            }
            return null
        }

        pass(TtsRole.Narrator, narratorSids)?.let { return it }
        if (voices.dialogue.engine !== voices.narrator.engine) {
            try {
                onReleasePass(voices.narrator.engine)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        pass(TtsRole.Dialogue, dialogueSids)?.let { return it }
        if (!persist()) {
            val lastSid = ordered.lastOrNull()?.sid ?: 0
            return ChapterSpoolOutcome.Failed(
                chapterPos, chapterNumber, lastSid,
                "chapter $chapterNumber: cannot write final spool index " +
                    "(resume bookkeeping lost)"
            )
        }
        return ChapterSpoolOutcome.Completed(
            buildSummary(
                chapterPos, chapterNumber, voices, bySid, entries,
                skippedSids, retried, recompute, spooled, skipped
            )
        )
    }

    /**
     * Spools every pending plan chapter of a RUNNING [job] in order.
     *
     * Chapter text arrives through [loadChapter] (keyed by 1-based manifest
     * index; RN8 reads plus parses the chapter JSON, tests hand a map), and
     * [chapterNumberOf] maps the 0-based plan position to that index
     * (default `pos + 1` for contiguous device books). Each finished
     * chapter advances the job through [RenderJobs.onChapterDone] (the RN3
     * convention, never a parallel tracker); the first failure or cancel
     * stops the loop and returns the still-RUNNING job for the caller to
     * transition. Requires a RUNNING job (chapter finish needs it).
     */
    suspend fun renderJobChapters(
        job: RenderJob,
        voices: ResolvedRenderVoices,
        spoolDir: String,
        io: SpoolIo,
        chapterNumberOf: (chapterPos: Int) -> Int = { it + 1 },
        loadChapter: (chapterNumber: Int) -> ChapterText,
        shouldCancel: () -> Boolean = { false },
        onReleasePass: (TtsEngine) -> Unit = {},
        onSentenceDone: (chapterPos: Int, sid: Int) -> Unit = { _, _ -> }
    ): JobSpoolOutcome {
        require(job.state == RenderJobState.RUNNING) {
            "render job ${job.bookId}: spooling needs RUNNING, was ${job.state}"
        }
        var current = job
        while (true) {
            val pos = RenderJobs.firstPending(current)
                ?: return JobSpoolOutcome.Finished(current)
            val number = chapterNumberOf(pos)
            val chapter = try {
                loadChapter(number)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return JobSpoolOutcome.ChapterFailed(
                    current,
                    ChapterSpoolOutcome.Failed(
                        chapterPos = pos,
                        chapterNumber = number,
                        sid = 0,
                        reason = "chapter $number: cannot load text (${e.message})"
                    )
                )
            }
            when (val outcome = renderChapter(
                chapterPos = pos,
                chapterNumber = number,
                chapter = chapter,
                voices = voices,
                spoolDir = spoolDir,
                io = io,
                shouldCancel = shouldCancel,
                onReleasePass = onReleasePass,
                onSentenceDone = { sid -> onSentenceDone(pos, sid) }
            )) {
                is ChapterSpoolOutcome.Completed ->
                    current = RenderJobs.onChapterDone(current, pos)
                is ChapterSpoolOutcome.Failed ->
                    return JobSpoolOutcome.ChapterFailed(current, outcome)
                is ChapterSpoolOutcome.Cancelled ->
                    return JobSpoolOutcome.Cancelled(current, outcome)
            }
        }
    }

    /**
     * Recomputes split-pair flags through the shared [DialogueTagger] path.
     *
     * Each block's bundle sentences join back into the paragraph IN6 saw
     * (exact per the IN5 spacing guarantee) and [DialogueTagger.tagChapter]
     * re-splits it: the same invocation shape IN6 uses in
     * [app.auloud.player.ingest.IngestPipeline] (split chapter, tag
     * chapter), with empty spans (span offsets never affect quote
     * detection, only rebasing). Unknown block types with sentences read as
     * paragraphs (forward-compatible); empty ones read as breaks.
     */
    internal fun recomputeSplitPairs(chapterNumber: Int, chapter: ChapterText): SplitRecompute {
        val blocks = ArrayList<IngestBlock>(chapter.blocks.size)
        val split = ArrayList<BlockSentences>(chapter.blocks.size)
        for (block in chapter.blocks) {
            val kind = when (block.type) {
                BLOCK_HEADING -> BLOCK_HEADING
                BLOCK_PARA -> BLOCK_PARA
                BLOCK_QUOTE -> BLOCK_QUOTE
                BLOCK_BREAK -> BLOCK_BREAK
                else -> if (block.sentences.isEmpty()) BLOCK_BREAK else BLOCK_PARA
            }
            if (kind == BLOCK_BREAK) {
                blocks.add(IngestBlock(kind = kind, text = ""))
                split.add(BlockSentences(block = block.id, sentences = emptyList()))
                continue
            }
            if (kind == BLOCK_HEADING) {
                val headline = block.text
                    ?: block.sentences.joinToString("") { it.text }
                blocks.add(IngestBlock(kind = kind, text = headline, level = block.level))
            } else {
                blocks.add(IngestBlock(kind = kind, text = ""))
            }
            split.add(
                BlockSentences(
                    block = block.id,
                    sentences = block.sentences.map { sentence ->
                        IngestSentence(
                            sid = sentence.sid,
                            text = sentence.text,
                            spans = emptyList()
                        )
                    }
                )
            )
        }
        val ingestChapter = IngestChapter(
            index = chapterNumber,
            title = chapter.title,
            href = "",
            blocks = blocks,
            wordCount = 0
        )
        val warnings = ArrayList<String>()
        val tagged = DialogueTagger.tagChapter(ingestChapter, split, warnings)
        val byBlock = tagged.blocks.associateBy { it.block }
        val map = HashMap<Int, Int?>()
        val fallback = ArrayList<Int>()
        for (block in chapter.blocks) {
            if (block.sentences.isEmpty()) continue
            val re = byBlock[block.id]?.sentences.orEmpty()
            if (re.size != block.sentences.size) {
                fallback.add(block.id)
                for (sentence in block.sentences) map[sentence.sid] = null
                continue
            }
            for (index in block.sentences.indices) {
                map[block.sentences[index].sid] = re[index].splitPair
            }
        }
        return SplitRecompute(
            splitBySid = map,
            fallbackBlocks = fallback,
            warnings = warnings.toList()
        )
    }

    private fun readUsableIndex(
        io: SpoolIo,
        indexPath: String,
        chapterNumber: Int,
        fingerprint: RenderFingerprint
    ): SpoolChapterIndex? {
        val raw = try {
            if (!io.exists(indexPath)) return null
            io.readText(indexPath)
        } catch (_: Exception) {
            return null
        }
        val parsed = SpoolIndex.parse(raw) ?: return null
        if (parsed.chapter != chapterNumber) return null
        if (parsed.fingerprint != fingerprint) return null
        return parsed
    }

    private fun invalidateChapterSpool(
        io: SpoolIo,
        spoolDir: String,
        chapterNumber: Int,
        indexPath: String
    ) {
        try {
            val stale = io.listFiles(spoolDir, SpoolFiles.chapterPrefix(chapterNumber), ".pcm")
            for (path in stale) io.deleteIfExists(path)
        } catch (_: Exception) {
        }
        try {
            io.deleteIfExists(indexPath)
        } catch (_: Exception) {
        }
    }

    private fun currentIndex(
        chapterNumber: Int,
        voices: ResolvedRenderVoices,
        entries: Map<Int, SpoolSentenceEntry>
    ): SpoolChapterIndex {
        var peakNarrator = 0f
        var peakDialogue = 0f
        for (entry in entries.values) {
            if (entry.role == SPEAKER_NARRATOR) {
                if (entry.peak > peakNarrator) peakNarrator = entry.peak
            } else {
                if (entry.peak > peakDialogue) peakDialogue = entry.peak
            }
        }
        return SpoolChapterIndex(
            chapter = chapterNumber,
            fingerprint = voices.fingerprint,
            sentences = entries.values.sortedBy { it.sid },
            peaks = mapOf(
                SPEAKER_NARRATOR to peakNarrator,
                SPEAKER_DIALOGUE to peakDialogue
            )
        )
    }

    private fun buildSummary(
        chapterPos: Int,
        chapterNumber: Int,
        voices: ResolvedRenderVoices,
        bySid: Map<Int, Sentence>,
        entries: Map<Int, SpoolSentenceEntry>,
        skippedSids: Set<Int>,
        retried: List<Int>,
        recompute: SplitRecompute,
        spooled: Int,
        skipped: Int
    ): ChapterSpoolSummary {
        var peakNarrator = 0f
        var peakDialogue = 0f
        val out = ArrayList<SpooledSentence>(entries.size)
        for (sid in bySid.keys.sorted()) {
            val entry = entries[sid] ?: continue
            val role = if (entry.role == SPEAKER_NARRATOR) TtsRole.Narrator else TtsRole.Dialogue
            if (role == TtsRole.Narrator) {
                if (entry.peak > peakNarrator) peakNarrator = entry.peak
            } else {
                if (entry.peak > peakDialogue) peakDialogue = entry.peak
            }
            out.add(
                SpooledSentence(
                    sid = sid,
                    role = role,
                    file = entry.file,
                    sampleRateHz = entry.sampleRateHz,
                    samples = entry.samples,
                    splitPair = entry.splitPair,
                    peak = entry.peak,
                    skipped = sid in skippedSids
                )
            )
        }
        return ChapterSpoolSummary(
            chapterPos = chapterPos,
            chapterNumber = chapterNumber,
            fingerprint = voices.fingerprint,
            sentences = out,
            peakNarrator = peakNarrator,
            peakDialogue = peakDialogue,
            spooled = spooled,
            skipped = skipped,
            retried = retried.toList(),
            splitFallbackBlocks = recompute.fallbackBlocks,
            taggerWarnings = recompute.warnings
        )
    }

    private suspend fun synthWithRetry(
        binding: RoleBinding,
        text: String,
        onRetried: () -> Unit
    ): SynthesizedAudio {
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                val audio = binding.engine.synthesize(text, binding.voice, binding.speed)
                if (attempt == 1) onRetried()
                return audio
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw SentenceSpoolException(last)
    }

    /** One sentence defeated both attempts (cause is the last error). */
    private class SentenceSpoolException(cause: Exception?) : Exception(cause)
}
