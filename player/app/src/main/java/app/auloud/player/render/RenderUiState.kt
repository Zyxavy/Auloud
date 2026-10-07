package app.auloud.player.render

/**
 * RN9: render UI state plus formatting (Slice 10).
 *
 * Everything the render panel, library chips, chapter rows and debug
 * overlay show that is computable without Android: option mapping,
 * estimate math, plain-language errors, chip text, chapter states and
 * debug lines. The composables render these; the ViewModels load the
 * inputs. No service, notification, MediaCodec or storage code here.
 *
 * Estimates are always RANGES, never single numbers (plan section 8
 * risk table: System TTS speed varies run to run, so a single number
 * would be dishonest). Wall time derives from the measured benchmark
 * RTF range below; audio length and size are deterministic functions of
 * the estimated audio, marked rough while chapters are unmeasured.
 *
 * API 24 safe: pure Kotlin, no `java.time`, no Android types.
 */
object RenderUiState

/**
 * RN9: benchmark render real-time factor range (audio seconds per wall
 * second) from the Slice 10 plan section 2 measurements: System TTS
 * varied 2.56x then 0.86x run to run (noisy). The slow end ([RTF_LOW])
 * gives the long wall time, the fast end ([RTF_HIGH]) the short one.
 * RN11 retunes these from Tab E acceptance numbers.
 */
const val RENDER_RTF_LOW = 0.86
const val RENDER_RTF_HIGH = 2.56

/**
 * RN9: provisional audio milliseconds per English word for unrendered
 * chapters (150 words per minute adult narration, 400 ms per word).
 * Measured chapter durations (manifest `duration_ms`) always win when
 * present; this only sizes chapters that have no audio yet. RN11
 * retunes it against real renders.
 */
const val EST_MS_PER_WORD = 400L

/** RN9: render scope options the panel offers (plan decision 8). */
enum class RenderOption {
    WHOLE_BOOK,
    NEXT_N,
    FROM_HERE
}

/**
 * RN9: maps a panel [option] to a [RenderPlan] over [chapterCount]
 * chapters from [readingChapter], skipping rendered chapters.
 */
fun planForOption(
    bookId: String,
    chapterCount: Int,
    readingChapter: Int,
    option: RenderOption,
    nextN: Int,
    isRendered: (Int) -> Boolean,
    clock: () -> Long = System::currentTimeMillis
): RenderPlan {
    val scope = when (option) {
        RenderOption.WHOLE_BOOK -> RenderScope.WholeBook
        RenderOption.FROM_HERE -> RenderScope.FromHere
        RenderOption.NEXT_N -> RenderScope.NextN(nextN.coerceAtLeast(1))
    }
    return RenderPlanner.plan(
        bookId = bookId,
        chapterCount = chapterCount,
        readingChapter = readingChapter,
        scope = scope,
        isRendered = isRendered,
        clock = clock
    )
}

/**
 * RN9: one panel estimate for a finished plan.
 *
 * @param audioMs estimated audio milliseconds the plan needs.
 * @param sizeBytes encoded plus spool bytes for [audioMs].
 * @param wallFastMs wall time at the fast benchmark end ([RENDER_RTF_HIGH]).
 * @param wallSlowMs wall time at the slow benchmark end ([RENDER_RTF_LOW]).
 * @param unknownChapters plan chapters sized from the fallback (no
 * measured duration and no readable word count).
 */
data class RenderEstimateView(
    val audioMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val wallFastMs: Long = 0L,
    val wallSlowMs: Long = 0L,
    val unknownChapters: Int = 0
)

/**
 * RN9: estimates the plan in [ordered] (0-based chapter positions).
 *
 * @param audioMsByChapter estimated audio ms per position (measured
 * durations win; word-count estimates next; null means unknown and uses
 * the [RenderEstimates] fallback, counted in [RenderEstimateView.unknownChapters]).
 * @param rtfLow/rtfHigh benchmark ends (defaults [RENDER_RTF_LOW]/[RENDER_RTF_HIGH]).
 */
fun computeRenderEstimate(
    ordered: List<Int>,
    audioMsByChapter: Map<Int, Long?> = emptyMap(),
    rtfLow: Double = RENDER_RTF_LOW,
    rtfHigh: Double = RENDER_RTF_HIGH
): RenderEstimateView {
    require(rtfLow.isFinite() && rtfLow > 0.0) { "rtfLow must be finite and > 0, got $rtfLow." }
    require(rtfHigh.isFinite() && rtfHigh > 0.0) { "rtfHigh must be finite and > 0, got $rtfHigh." }
    val estimate = RenderEstimates.estimateForPlan(ordered, audioMsByChapter)
    return RenderEstimateView(
        audioMs = estimate.audioMs,
        sizeBytes = estimate.totalBytes,
        wallFastMs = (estimate.audioMs / rtfHigh).toLong(),
        wallSlowMs = (estimate.audioMs / rtfLow).toLong(),
        unknownChapters = estimate.unknownChapters
    )
}

/** RN9: short duration for estimate lines (`45 s`, `20 min`, `2 h 5 min`). */
fun formatShortDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = safe / 1_000L
    if (totalSeconds < 60L) return "$totalSeconds s"
    val totalMinutes = totalSeconds / 60L
    if (totalMinutes < 60L) return "$totalMinutes min"
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (minutes == 0L) "$hours h" else "$hours h $minutes min"
}

/** RN9: approximate audio length line (`about 20 min of audio`). */
fun formatAudioLength(audioMs: Long): String {
    if (audioMs < 60_000L) return "less than a minute of audio"
    return "about ${formatShortDuration(audioMs)} of audio"
}

/** RN9: approximate size line (`about 104 MB`, `about 900 KB`). */
fun formatSizeBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1_048_576L) return "about ${safe / 1_024L} KB"
    val mb = safe / 1_048_576.0
    val rounded = (mb * 10.0 + 0.5).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "about ${rounded.toLong()} MB"
    } else {
        "about $rounded MB"
    }
}

/**
 * RN9: wall-time RANGE line, never a single number
 * (`about 20 min to 1 h of rendering`).
 */
fun formatWallRange(fastMs: Long, slowMs: Long): String {
    val fast = fastMs.coerceAtLeast(0L)
    val slow = slowMs.coerceAtLeast(fast)
    if (slow < 60_000L) return "less than a minute of rendering"
    return "about ${formatShortDuration(fast)} to ${formatShortDuration(slow)} of rendering"
}

/** RN9: honesty note under the estimate (rough while chapters are unmeasured). */
fun estimateNote(unknownChapters: Int): String {
    return if (unknownChapters > 0) {
        "Rough estimate: $unknownChapters chapter(s) have no measured length yet."
    } else {
        "Estimate from chapter lengths."
    }
}

/**
 * RN9: plain-language render failure for a service or guard reason.
 *
 * Technical detail is kept after a plain headline (support still sees
 * the file-plus-rule shape); unknown reasons keep their text verbatim
 * behind a plain prefix instead of being swallowed.
 */
fun renderErrorText(raw: String?): String {
    if (raw.isNullOrBlank()) {
        return "Rendering stopped for an unknown reason. Try again."
    }
    val lower = raw.lowercase()
    if ("nothing to render" in lower || "nothing pending" in lower || "already has audio" in lower) {
        return "There is nothing to render. Every planned chapter already has audio."
    }
    if ("voice is not set" in lower || "choose one in voice settings" in lower ||
        "has no engine namespace" in lower
    ) {
        return "A voice is missing. Choose narrator and dialogue voices in Settings, Voices. ($raw)"
    }
    if ("engine not installed" in lower || "needs engine" in lower) {
        return "A speech engine is missing. Install it or pick another voice in Settings, Voices. ($raw)"
    }
    if ("not available from engine" in lower || "missing model pack" in lower) {
        return "A voice model is missing. Copy the voice model pack into /Auloud/models/ and try again. ($raw)"
    }
    if ("has no version string" in lower) {
        return "The speech engine did not report a version. Try again. ($raw)"
    }
    if ("low storage" in lower || "free space" in lower || "free some storage" in lower) {
        return "Not enough free space. Free some storage and try again. ($raw)"
    }
    if ("letting the battery cool" in lower || "too warm" in lower || "temperature" in lower) {
        return "The tablet is too warm. Rendering continues when it cools down. ($raw)"
    }
    if ("connect the charger" in lower || "charger" in lower) {
        return "Rendering paused. Connect the charger to continue. ($raw)"
    }
    if ("app-storage output" in lower || "picked folder" in lower || "saf" in lower) {
        return "This book lives in a picked folder, which rendering cannot write to yet. " +
            "Move it into the Auloud folder to render it. ($raw)"
    }
    if ("manifest unreadable" in lower || "cannot read manifest" in lower) {
        return "The book file is unreadable. Re-import the book and try again. ($raw)"
    }
    if ("cancelled" in lower) {
        return "Rendering cancelled."
    }
    if ("no aac encoder" in lower || "no audio encoder" in lower || "encoder" in lower &&
        ("missing" in lower || "unavailable" in lower)
    ) {
        return "This tablet has no audio encoder for rendering. ($raw)"
    }
    return "Rendering stopped: $raw"
}

/** RN9: per-chapter render state for chapter rows. */
enum class ChapterRenderState {
    RENDERED,
    UNRENDERED,
    RENDERING,
    PAUSED,
    FAILED
}

/**
 * RN9: state of one chapter at [pos] (0-based manifest position).
 *
 * Rendered chapters are always RENDERED; the job only colors pending
 * work (the current chapter renders, other pending chapters wait or
 * pause with the job, a failed job marks its current chapter).
 */
fun chapterStateFor(pos: Int, isRendered: Boolean, job: RenderJob?): ChapterRenderState {
    if (isRendered) return ChapterRenderState.RENDERED
    if (job == null) return ChapterRenderState.UNRENDERED
    val pending = pos in job.plan.orderedChapters && pos !in job.completedChapters
    if (!pending) return ChapterRenderState.UNRENDERED
    return when (job.state) {
        RenderJobState.RUNNING ->
            if (pos == job.currentChapter) ChapterRenderState.RENDERING
            else ChapterRenderState.UNRENDERED
        RenderJobState.PAUSED,
        RenderJobState.INTERRUPTED,
        RenderJobState.QUEUED -> ChapterRenderState.PAUSED
        RenderJobState.FAILED ->
            if (pos == job.currentChapter) ChapterRenderState.FAILED
            else ChapterRenderState.UNRENDERED
        RenderJobState.CANCELLED,
        RenderJobState.DONE -> ChapterRenderState.UNRENDERED
    }
}

/** RN9: one-line chapter row status (plain words, no codes). */
fun chapterStatusText(state: ChapterRenderState): String = when (state) {
    ChapterRenderState.RENDERED -> "Ready to listen"
    ChapterRenderState.UNRENDERED -> "Not rendered yet"
    ChapterRenderState.RENDERING -> "Rendering now"
    ChapterRenderState.PAUSED -> "Paused"
    ChapterRenderState.FAILED -> "Render failed"
}

/**
 * RN9: library chip progress for one book (the job file as last seen;
 * null when no job file was read).
 */
data class RenderJobProgress(
    val done: Int,
    val total: Int,
    val state: RenderJobState
)

/**
 * RN9: library chip text, or null for no chip.
 *
 * An active job wins (`Rendering 42%`, `Paused at 42%`, `Render
 * failed`); otherwise the manifest `render_state` decides
 * (`Partially rendered`, `Not rendered`, rendered books show nothing).
 */
fun renderChipText(renderState: String?, job: RenderJobProgress?): String? {
    if (job != null && job.total > 0) {
        val percent = (job.done.coerceAtLeast(0) * 100 / job.total).coerceIn(0, 100)
        when (job.state) {
            RenderJobState.RUNNING -> return "Rendering $percent%"
            RenderJobState.PAUSED,
            RenderJobState.INTERRUPTED,
            RenderJobState.QUEUED -> return "Paused at $percent%"
            RenderJobState.FAILED -> return "Render failed"
            RenderJobState.CANCELLED,
            RenderJobState.DONE -> {
                // Terminal: fall through to the render_state chip below.
            }
        }
    }
    return when (renderState) {
        "partial" -> "Partially rendered"
        "none" -> "Not rendered"
        else -> null
    }
}

/** RN9: where tapping a partial-book chapter leads (D-099 per-chapter gate). */
enum class ChapterOpenTarget {
    LISTEN,
    READ
}

/** RN9: rendered chapters listen, unrendered chapters read. */
fun partialChapterTarget(chapterPos: Int, map: ChapterMediaMap): ChapterOpenTarget =
    if (map.isRendered(chapterPos)) ChapterOpenTarget.LISTEN else ChapterOpenTarget.READ

/**
 * RN9: one-line voices summary for the panel
 * (`Narrator system:en-us-x at 1.00x. Dialogue system:en-gb-y at 1.00x.`).
 * Blank ids read as not chosen (plain words, never an empty line).
 */
fun voicesSummary(
    narratorId: String,
    dialogueId: String,
    narratorSpeed: Float,
    dialogueSpeed: Float
): String {
    val narrator = narratorId.takeIf { it.isNotBlank() } ?: "not chosen"
    val dialogue = dialogueId.takeIf { it.isNotBlank() } ?: "not chosen"
    return "Narrator $narrator at ${"%.2f".format(narratorSpeed)}x. " +
        "Dialogue $dialogue at ${"%.2f".format(dialogueSpeed)}x."
}

/** RN9: whitespace word count for estimate sizing (blank text counts 0). */
fun countWords(text: String): Int {
    if (text.isBlank()) return 0
    var count = 0
    var inWord = false
    for (char in text) {
        if (char.isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            inWord = true
            count++
        }
    }
    return count
}

/** RN9: estimated audio ms for [words] words at [EST_MS_PER_WORD]. */
fun audioMsForWords(words: Int): Long =
    words.coerceAtLeast(0).toLong() * EST_MS_PER_WORD

/**
 * RN9: debug overlay text (debug builds only; same gate shape as the
 * beep card). Four lines: benchmark RTF range, current chapter (the
 * job file knows chapters, not sentences; live sentences show in the
 * render notification), battery temperature, spool size.
 */
fun renderDebugText(
    rtfLow: Double,
    rtfHigh: Double,
    chapterText: String,
    sentenceText: String,
    batteryTempC: Float?,
    spoolBytes: Long?
): String {
    val temp = if (batteryTempC != null && batteryTempC.isFinite()) {
        "%.1f C".format(batteryTempC)
    } else {
        "-"
    }
    val spool = if (spoolBytes != null && spoolBytes >= 0L) {
        formatDebugBytes(spoolBytes)
    } else {
        "-"
    }
    return "RTF ${"%.2f".format(rtfLow)}x to ${"%.2f".format(rtfHigh)}x\n" +
        "chapter $chapterText\n" +
        "sentence $sentenceText\n" +
        "battery $temp spool $spool"
}

/** RN9: compact byte size for the debug overlay (`12 KB`, `3 MB`). */
fun formatDebugBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1_048_576L) return "${safe / 1_024L} KB"
    return "${safe / 1_048_576L} MB"
}
