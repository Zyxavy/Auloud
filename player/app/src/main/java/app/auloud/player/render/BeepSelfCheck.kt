package app.auloud.player.render

import app.auloud.player.bundle.AudioInfo
import app.auloud.player.bundle.Block
import app.auloud.player.bundle.BundleParser
import app.auloud.player.bundle.BundleValidator
import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Manifest
import app.auloud.player.bundle.Sentence
import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import app.auloud.player.tts.EngineRegistry
import app.auloud.player.tts.TtsRole
import app.auloud.player.tts.TtsVoiceStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RN10: debug beep self-check (Slice 10 plan RN10, D-093).
 *
 * Renders a short test chapter with the [BeepTtsEngine] through the
 * real production chain: [RenderVoices.resolve] plus
 * [SpoolRenderer.renderChapter] (RN4) plus
 * [ChapterAssembler.assembleFromSpool] (RN5) plus an [AudioEncoder]
 * (RN6: a fake on the JVM, [AndroidAudioEncoder] on the device). The
 * output chapter then validates against the RN1 spec rules through
 * [BundleValidator], the same validator the import path uses.
 *
 * Debug-only: [run] refuses when [isDebugBuild] is false, so a
 * mis-wired release call site fails loudly instead of rendering beeps
 * into a real book. Production callers pass `BuildConfig.DEBUG`.
 *
 * Spool bytes arrive through [readSpoolBytes] (file name to PCM-16
 * bytes) instead of a new [SpoolIo] read op: the spool seam is
 * write-only by RN4 design, and the assembler already takes a loader
 * lambda for exactly this reason. No RN3-RN6 file is modified here.
 *
 * API 24 safe: pure Kotlin plus kotlinx.serialization only (the encoder
 * itself is injected). No `java.time`, no Android types, no new
 * dependency, no permission, no manifest change.
 */
object BeepSelfCheck {

    /** Stable id of the synthetic test book. */
    const val BOOK_ID = "beep-self-check"

    /** Title of the synthetic test book. */
    const val BOOK_TITLE = "Beep self-check"

    /** 1-based manifest index of the single test chapter. */
    const val CHAPTER_NUMBER = 1

    /** Default sentence count of the test chapter. */
    const val SENTENCE_COUNT = 4

    /** Chapter text file path inside the synthetic bundle. */
    const val CHAPTER_TEXT_PATH = "text/ch001.json"

    /** Chapter audio file path inside the synthetic bundle. */
    const val CHAPTER_AUDIO_PATH = "audio/ch001.m4a"

    /** Sentence text for a 1-based sid (the beep engine reads the trailing index). */
    fun textForSid(sid: Int): String {
        require(sid >= 1) { "sid must be 1-based, got $sid." }
        return "Beep $sid"
    }

    /**
     * Untimed 2.0 test chapter: one para block per sentence, narrator
     * and dialogue alternating (so both voice passes run), sids 1..N in
     * order. Single-sentence blocks keep the RN4 split recompute exact
     * (no split-pair flags, normal pauses).
     */
    fun buildChapter(sentenceCount: Int = SENTENCE_COUNT): ChapterText {
        require(sentenceCount >= 1) { "beep chapter needs sentences, got $sentenceCount." }
        val blocks = ArrayList<Block>(sentenceCount)
        for (sid in 1..sentenceCount) {
            val speaker = if (sid % 2 == 1) SPEAKER_NARRATOR else SPEAKER_DIALOGUE
            blocks.add(
                Block(
                    id = sid,
                    type = "para",
                    sentences = listOf(
                        Sentence(sid = sid, speaker = speaker, text = textForSid(sid))
                    )
                )
            )
        }
        return ChapterText(
            specVersion = "2.0",
            chapter = CHAPTER_NUMBER,
            title = "Beep chapter",
            blocks = blocks
        )
    }

    /** One finished beep render with its spec artifacts. */
    data class BeepBundle(
        val manifest: Manifest,
        val chapterJson: String,
        val timings: List<AssemblySentenceTiming>,
        val durationMs: Int,
        val encoded: EncodedChapter,
        val fingerprint: RenderFingerprint,
        val validationErrors: List<String>
    )

    /** Beep render outcome (failures name the chapter, sentence or rule). */
    sealed interface Outcome {
        data class Success(val bundle: BeepBundle) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    /**
     * Renders the beep chapter end to end.
     *
     * @param spoolDir spool workspace dir (RN8 owns the location on device).
     * @param spoolIo spool write seam (in-memory fake on the JVM).
     * @param readSpoolBytes spool PCM-16 bytes by file name (the caller
     * reads them back: memory map on the JVM, `java.io.File` on device).
     * @param encoder chapter encoder (fake on the JVM, platform encoder
     * on the device; its finish handshake must agree with the assembly).
     * @param isDebugBuild debug gate (pass `BuildConfig.DEBUG`; false
     * refuses immediately, so release builds never render beeps).
     * @param bundleDirPath bundle-dir label used only to join validator
     * paths (no files are read through it; chapter JSON arrives via the
     * in-memory seam).
     */
    suspend fun run(
        spoolDir: String,
        spoolIo: SpoolIo,
        readSpoolBytes: (fileName: String) -> ByteArray,
        encoder: AudioEncoder,
        isDebugBuild: Boolean,
        chapterNumber: Int = CHAPTER_NUMBER,
        sentenceCount: Int = SENTENCE_COUNT,
        bundleDirPath: String = BOOK_ID
    ): Outcome {
        if (!isDebugBuild) {
            return Outcome.Failure(
                "beep self-check is debug-only (release builds never render beeps)"
            )
        }
        require(chapterNumber >= 1) { "chapterNumber must be 1-based, got $chapterNumber." }
        val engine = BeepTtsEngine()
        val registry = EngineRegistry(listOf(engine))
        val store = beepVoiceStore()
        val resolved = RenderVoices.resolve(registry, store) { BeepTtsEngine.VERSION }
        if (resolved.isFailure) {
            return Outcome.Failure(
                "beep voices do not resolve (${resolved.exceptionOrNull()?.message})"
            )
        }
        val voices = resolved.getOrThrow()
        val chapter = buildChapter(sentenceCount)
        when (val spooled = SpoolRenderer.renderChapter(
            chapterPos = 0,
            chapterNumber = chapterNumber,
            chapter = chapter,
            voices = voices,
            spoolDir = spoolDir,
            io = spoolIo
        )) {
            is ChapterSpoolOutcome.Failed -> return Outcome.Failure(
                "chapter $chapterNumber sentence ${spooled.sid}: ${spooled.reason}"
            )
            is ChapterSpoolOutcome.Cancelled -> return Outcome.Failure(
                "chapter $chapterNumber: beep spool cancelled after ${spooled.sidsDone} sentences"
            )
            is ChapterSpoolOutcome.Completed -> {
                return finishCompleted(
                    chapterNumber = chapterNumber,
                    chapter = chapter,
                    summary = spooled.summary,
                    voices = voices,
                    readSpoolBytes = readSpoolBytes,
                    encoder = encoder,
                    bundleDirPath = bundleDirPath
                )
            }
        }
    }

    private fun finishCompleted(
        chapterNumber: Int,
        chapter: ChapterText,
        summary: ChapterSpoolSummary,
        voices: ResolvedRenderVoices,
        readSpoolBytes: (fileName: String) -> ByteArray,
        encoder: AudioEncoder,
        bundleDirPath: String
    ): Outcome {
        val entries = summary.sentences.map { spooled ->
            SpoolSentenceEntry(
                sid = spooled.sid,
                role = if (spooled.role == TtsRole.Narrator) SPEAKER_NARRATOR else SPEAKER_DIALOGUE,
                file = spooled.file,
                sampleRateHz = spooled.sampleRateHz,
                samples = spooled.samples,
                splitPair = spooled.splitPair,
                peak = spooled.peak
            )
        }
        val index = SpoolChapterIndex(
            chapter = chapterNumber,
            fingerprint = voices.fingerprint,
            sentences = entries,
            peaks = mapOf(
                SPEAKER_NARRATOR to summary.peakNarrator,
                SPEAKER_DIALOGUE to summary.peakDialogue
            )
        )
        val adapter = AudioEncoderSinkAdapter(encoder, chapterNumber)
        val assembled = try {
            ChapterAssembler.assembleFromSpool(
                chapterNumber = chapterNumber,
                chapter = chapter,
                index = index,
                loadPcm = { entry ->
                    val bytes = try {
                        readSpoolBytes(entry.file)
                    } catch (e: Exception) {
                        throw IllegalArgumentException(
                            "chapter $chapterNumber sentence ${entry.sid}: " +
                                "spool file ${entry.file} unreadable (${e.message})"
                        )
                    }
                    SpoolPcm.decodePcm16(bytes)
                },
                sink = adapter
            )
        } catch (e: Exception) {
            return Outcome.Failure(
                "chapter $chapterNumber: assembly failed (${e.message})"
            )
        }
        val encoded = adapter.encoded
            ?: return Outcome.Failure(
                "chapter $chapterNumber: encoder produced no file (finish handshake missing)"
            )
        if (encoded.sampleCount != assembled.sampleCount ||
            encoded.durationMs != assembled.durationMs
        ) {
            return Outcome.Failure(
                "chapter $chapterNumber: encoder file disagrees with assembly " +
                    "(audio ${encoded.sampleCount}/${encoded.durationMs} ms, " +
                    "assembly ${assembled.sampleCount}/${assembled.durationMs} ms)"
            )
        }
        val bySid = assembled.timings.associateBy { it.sid }
        val timedBlocks = chapter.blocks.map { block ->
            block.copy(
                sentences = block.sentences.map { sentence ->
                    val timing = bySid[sentence.sid]
                        ?: return Outcome.Failure(
                            "chapter $chapterNumber sentence ${sentence.sid}: " +
                                "assembly produced no timing"
                        )
                    sentence.copy(
                        startMs = timing.startMs.toLong(),
                        endMs = timing.endMs.toLong()
                    )
                }
            )
        }
        val timedChapter = chapter.copy(
            durationMs = assembled.durationMs.toLong(),
            blocks = timedBlocks
        )
        val chapterJson = BundleParser.json.encodeToString(
            ChapterText.serializer(),
            timedChapter
        )
        val manifest = Manifest(
            specVersion = "2.0",
            id = BOOK_ID,
            title = BOOK_TITLE,
            type = "epub",
            chapters = listOf(
                ChapterInfo(
                    index = chapterNumber,
                    title = "Beep chapter",
                    text = CHAPTER_TEXT_PATH,
                    audio = CHAPTER_AUDIO_PATH,
                    durationMs = assembled.durationMs.toLong(),
                    renderFingerprint = voices.fingerprint.toJsonObject()
                )
            ),
            audio = AudioInfo(
                format = "m4a",
                channels = 1,
                sampleRate = 24000,
                bitrateKbps = 64,
                cbr = true
            ),
            renderState = "complete",
            encoderOffsetMs = JsonPrimitive(0),
            voices = mapOf(
                SPEAKER_NARRATOR to buildJsonObject {
                    put("engine", BeepTtsEngine.NAMESPACE)
                    put("voice", BeepTtsEngine.NARRATOR_VOICE_ID)
                },
                SPEAKER_DIALOGUE to buildJsonObject {
                    put("engine", BeepTtsEngine.NAMESPACE)
                    put("voice", BeepTtsEngine.DIALOGUE_VOICE_ID)
                }
            )
        )
        val validationErrors = BundleValidator.validate(
            bundleDirPath,
            manifest,
            exists = { path ->
                path.endsWith(CHAPTER_TEXT_PATH) || path.endsWith(CHAPTER_AUDIO_PATH)
            },
            readText = { path ->
                if (path.endsWith(CHAPTER_TEXT_PATH)) chapterJson else null
            }
        )
        return Outcome.Success(
            BeepBundle(
                manifest = manifest,
                chapterJson = chapterJson,
                timings = assembled.timings,
                durationMs = assembled.durationMs,
                encoded = encoded,
                fingerprint = voices.fingerprint,
                validationErrors = validationErrors
            )
        )
    }

    /** Fixed beep voice settings (both roles on the beep engine at 1.0x). */
    private fun beepVoiceStore(): TtsVoiceStore = object : TtsVoiceStore {
        override fun voiceId(role: TtsRole): String =
            if (role == TtsRole.Narrator) {
                BeepTtsEngine.NARRATOR_VOICE_ID
            } else {
                BeepTtsEngine.DIALOGUE_VOICE_ID
            }

        override fun setVoiceId(role: TtsRole, voiceId: String) {
            throw UnsupportedOperationException("beep self-check voices are fixed")
        }

        override fun speed(role: TtsRole): Float = 1.0f

        override fun setSpeed(role: TtsRole, speed: Float) {
            throw UnsupportedOperationException("beep self-check speeds are fixed")
        }
    }
}
