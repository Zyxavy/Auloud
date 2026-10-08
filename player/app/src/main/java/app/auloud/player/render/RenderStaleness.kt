package app.auloud.player.render

import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Manifest
import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.TtsVoice
import app.auloud.player.tts.clampTtsSpeed
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * VS2: voice staleness from chapter fingerprints (D-114, D-115).
 *
 * Plan names this `ChapterRenderState`; the type is called
 * [ChapterStaleState] here because RN9 already owns `ChapterRenderState`
 * for job progress (rendered, unrendered, rendering, paused, failed).
 * The two axes are independent: job progress says whether audio exists
 * and what the queue is doing, staleness says whether existing audio
 * still matches the book voices.
 *
 * States:
 * - CURRENT: rendered and the stored fingerprint matches the book voices
 *   for every role the chapter uses.
 * - STALE: rendered but a used role voice, speed or engine differs (or
 *   the fingerprint is missing, corrupt, or missing a used role). Needs
 *   re-render; old both-role fingerprints read as stale at worst, never
 *   current by accident, never a crash.
 * - OUTDATED: rendered and only engine version strings differ for used
 *   engines (for example after a system TTS update). Shown quietly and
 *   never re-rendered automatically.
 * - NOT_RENDERED: the chapter has no `duration_ms` (unrendered chapters
 *   omit audio and duration alike). The fingerprint is ignored.
 *
 * Roles used (cheapest reliable source): the chapter text sentences. A
 * scan of `ChapterText.sentencesInOrder` for `speaker == "dialogue"` is
 * O(sentences) with no audio, no timings and no extra IO beyond the one
 * chapter JSON the caller already loads (one chapter at a time per the
 * memory rule). Timed (rendered) and untimed (unrendered) chapters both
 * carry speakers, so the same scan works before and after render. The
 * caller loads one chapter JSON at a time and passes the boolean (or the
 * parsed chapter) in; this file never touches storage.
 *
 * Narrowing rule: only used roles compare. A chapter with no dialogue
 * sentences stays CURRENT when only the dialogue voice changes. The
 * narrator role counts as used when any narrator sentence, any unknown
 * speaker sentence, or no sentences at all are present (empty chapters
 * keep narrator as the conservative single role); chapters carrying only
 * non-reserved speakers (Scribe style) count as both roles (conservative:
 * any voice change stales them, and a missing fingerprint already stales
 * them as unknown).
 *
 * Estimates reuse the Slice 10 provisional constants
 * ([RenderEstimates] byte rates plus [RENDER_RTF_LOW]/[RENDER_RTF_HIGH]
 * plus the 30 min fallback); provisional numbers stay provisional until
 * RN11 retunes them. The stale set covers STALE chapters only (OUTDATED
 * never auto re-renders, NOT_RENDERED is planned separately).
 *
 * Pure Kotlin, API 24 safe: kotlinx.serialization only, no java.time,
 * no Android types, no new dependency, no permission.
 */
enum class ChapterStaleState {
    CURRENT,
    STALE,
    OUTDATED,
    NOT_RENDERED
}

/** One chapter input for the book summary (caller loads one JSON at a time). */
data class StaleChapterInput(
    val index: Int,
    val durationMs: Long?,
    val fingerprintJson: JsonElement?,
    val hasDialogue: Boolean,
    val hasNarrator: Boolean = true
)

/**
 * VS2: book-level staleness summary.
 *
 * Counts cover every input chapter. Audio plus time plus storage cover
 * the STALE set only (the chapters a re-render would redo). `swapBytes`
 * is old audio plus new audio plus spool (the old file stays playable
 * until the VS3 swap, so both sit on disk at once); `newBytes` is the
 * fresh audio plus spool alone. Both reuse [RenderEstimates] rates and
 * stay provisional.
 */
data class BookStalenessSummary(
    val current: Int,
    val stale: Int,
    val outdated: Int,
    val notRendered: Int,
    val staleAudioMs: Long,
    val staleHours: Double,
    val wallFastMs: Long,
    val wallSlowMs: Long,
    val newBytes: Long,
    val swapBytes: Long
)

object RenderStaleness {

    /**
     * True when the parsed chapter carries at least one dialogue
     * sentence (the cheapest reliable roles-used signal; see file KDoc).
     */
    fun hasDialogueSentences(chapter: ChapterText): Boolean {
        for (sentence in chapter.sentencesInOrder()) {
            if (sentence.speaker == SPEAKER_DIALOGUE) return true
        }
        return false
    }

    /**
     * Used roles for explicit presence flags. Empty chapters (neither
     * role present) fall back to narrator only so a dialogue-only change
     * never stales them while a narrator change still does.
     */
    fun usedRolesFor(hasNarrator: Boolean, hasDialogue: Boolean): Set<String> {
        if (!hasNarrator && !hasDialogue) return setOf(SPEAKER_NARRATOR)
        val out = LinkedHashSet<String>()
        if (hasNarrator) out.add(SPEAKER_NARRATOR)
        if (hasDialogue) out.add(SPEAKER_DIALOGUE)
        return out
    }

    /**
     * Used roles for a parsed chapter. Narrator counts as used on any
     * narrator sentence, any non-reserved speaker sentence, or an empty
     * chapter; dialogue counts as used on any dialogue sentence or any
     * non-reserved speaker sentence (Scribe-style chapters conservatively
     * use both roles).
     */
    fun rolesUsedInChapter(chapter: ChapterText): Set<String> {
        var hasNarrator = false
        var hasDialogue = false
        var hasOther = false
        var any = false
        for (sentence in chapter.sentencesInOrder()) {
            any = true
            when (sentence.speaker) {
                SPEAKER_NARRATOR -> hasNarrator = true
                SPEAKER_DIALOGUE -> hasDialogue = true
                else -> hasOther = true
            }
        }
        if (!any) return setOf(SPEAKER_NARRATOR)
        if (hasOther && !hasNarrator && !hasDialogue) {
            return setOf(SPEAKER_NARRATOR, SPEAKER_DIALOGUE)
        }
        val out = LinkedHashSet<String>()
        if (hasNarrator || hasOther) out.add(SPEAKER_NARRATOR)
        if (hasDialogue || hasOther) out.add(SPEAKER_DIALOGUE)
        if (out.isEmpty()) out.add(SPEAKER_NARRATOR)
        return out
    }

    /**
     * Expected fingerprint for [usedRoles] from the book voices plus live
     * engine versions. Dialogue resolves through
     * [BookVoices.resolvedDialogueVoiceId] (unset means same as
     * narrator). Speeds clamp. Returns null when the expectation cannot
     * be built (blank or namespaceless voice, blank version): callers
     * treat null as STALE (unknown, never current).
     */
    fun expectedFingerprint(
        bookVoices: BookVoices,
        versionOf: (namespace: String) -> String?,
        usedRoles: Set<String>
    ): RenderFingerprint? {
        if (usedRoles.isEmpty()) return null
        for (role in usedRoles) {
            if (role != SPEAKER_NARRATOR && role != SPEAKER_DIALOGUE) return null
        }
        val voices = LinkedHashMap<String, String>()
        val speeds = LinkedHashMap<String, Float>()
        for (role in usedRoles.sorted()) {
            val id = if (role == SPEAKER_NARRATOR) {
                bookVoices.narratorVoiceId
            } else {
                bookVoices.resolvedDialogueVoiceId()
            }
            if (id.isBlank()) return null
            if (TtsVoice.parse(id) == null) return null
            voices[role] = id
            val speed = if (role == SPEAKER_NARRATOR) {
                bookVoices.narratorSpeed
            } else {
                bookVoices.dialogueSpeed
            }
            speeds[role] = clampTtsSpeed(speed)
        }
        val namespaces = voices.values.mapNotNull { TtsVoice.parse(it)?.engine }.toSortedSet()
        if (namespaces.isEmpty()) return null
        val versions = LinkedHashMap<String, String>()
        for (namespace in namespaces) {
            val version = versionOf(namespace)
            if (version.isNullOrBlank()) return null
            versions[namespace] = version
        }
        val engine = if (namespaces.size == 1) namespaces.first() else namespaces.joinToString("+")
        return RenderFingerprint(
            engine = engine,
            voices = voices,
            speeds = speeds,
            engineVersions = versions
        )
    }

    /**
     * Classifies one rendered chapter from parsed fingerprints. Null
     * stored or null expected reads as STALE (unknown, never current).
     * Only [usedRoles] compare: extra stored roles for unused parts are
     * ignored, but a missing stored entry for a used role is STALE.
     * Voices plus speeds compare first (any difference is STALE,
     * including the engine namespace inside the voice id); versions for
     * used engines compare last (differences alone are OUTDATED).
     */
    fun classifyRendered(
        stored: RenderFingerprint?,
        expected: RenderFingerprint?,
        usedRoles: Set<String>
    ): ChapterStaleState {
        if (stored == null || expected == null) return ChapterStaleState.STALE
        if (usedRoles.isEmpty()) return ChapterStaleState.STALE
        for (role in usedRoles) {
            val storedVoice = stored.voices[role] ?: return ChapterStaleState.STALE
            val expectedVoice = expected.voices[role] ?: return ChapterStaleState.STALE
            if (storedVoice != expectedVoice) return ChapterStaleState.STALE
            val storedSpeed = stored.speeds[role] ?: return ChapterStaleState.STALE
            val expectedSpeed = expected.speeds[role] ?: return ChapterStaleState.STALE
            if (storedSpeed != expectedSpeed) return ChapterStaleState.STALE
        }
        val namespaces = LinkedHashSet<String>()
        for (role in usedRoles) {
            val voice = expected.voices[role] ?: return ChapterStaleState.STALE
            namespaces.add(TtsVoice.parse(voice)?.engine ?: return ChapterStaleState.STALE)
        }
        var versionDiffers = false
        for (namespace in namespaces) {
            val storedVersion = stored.engineVersions[namespace] ?: return ChapterStaleState.STALE
            val expectedVersion = expected.engineVersions[namespace] ?: return ChapterStaleState.STALE
            if (storedVersion != expectedVersion) versionDiffers = true
        }
        return if (versionDiffers) ChapterStaleState.OUTDATED else ChapterStaleState.CURRENT
    }

    /**
     * Classifies one chapter from its manifest entry fields. Null
     * `durationMs` reads as NOT_RENDERED (fingerprint ignored, even when
     * present). Otherwise the stored JSON parses via
     * [RenderFingerprint.fromJsonObject] (corrupt reads as STALE) and the
     * expectation builds from [bookVoices] plus [versionOf] (unbuildable
     * reads as STALE).
     */
    fun classifyChapter(
        durationMs: Long?,
        storedJson: JsonElement?,
        bookVoices: BookVoices,
        versionOf: (namespace: String) -> String?,
        usedRoles: Set<String>
    ): ChapterStaleState {
        if (durationMs == null) return ChapterStaleState.NOT_RENDERED
        val stored = (storedJson as? JsonObject)?.let { RenderFingerprint.fromJsonObject(it) }
            ?: return ChapterStaleState.STALE
        val expected = expectedFingerprint(bookVoices, versionOf, usedRoles)
            ?: return ChapterStaleState.STALE
        return classifyRendered(stored, expected, usedRoles)
    }

    /** Classifies one chapter from a parsed chapter text (roles derived). */
    fun classifyChapter(
        durationMs: Long?,
        storedJson: JsonElement?,
        bookVoices: BookVoices,
        versionOf: (namespace: String) -> String?,
        chapter: ChapterText
    ): ChapterStaleState = classifyChapter(
        durationMs = durationMs,
        storedJson = storedJson,
        bookVoices = bookVoices,
        versionOf = versionOf,
        usedRoles = rolesUsedInChapter(chapter)
    )

    /** Classifies one manifest chapter with an explicit dialogue flag. */
    fun classifyChapter(
        entry: ChapterInfo,
        bookVoices: BookVoices,
        versionOf: (namespace: String) -> String?,
        hasDialogue: Boolean,
        hasNarrator: Boolean = true
    ): ChapterStaleState = classifyChapter(
        durationMs = entry.durationMs,
        storedJson = entry.renderFingerprint,
        bookVoices = bookVoices,
        versionOf = versionOf,
        usedRoles = usedRolesFor(hasNarrator, hasDialogue)
    )

    /**
     * Summarizes one book over [inputs] (one per chapter). Counts cover
     * every input; audio plus wall plus storage cover the STALE set only
     * (measured durations; chapters without durations are NOT_RENDERED by
     * construction, so no fallback counts arise here). Wall times reuse
     * the benchmark RTF range; storage reuses [RenderEstimates] rates.
     * All estimate numbers stay provisional until retuned from real
     * renders.
     */
    fun summarizeChapters(
        inputs: List<StaleChapterInput>,
        bookVoices: BookVoices,
        versionOf: (namespace: String) -> String?
    ): BookStalenessSummary {
        var current = 0
        var stale = 0
        var outdated = 0
        var notRendered = 0
        var staleAudioMs = 0L
        for (input in inputs) {
            val state = classifyChapter(
                durationMs = input.durationMs,
                storedJson = input.fingerprintJson,
                bookVoices = bookVoices,
                versionOf = versionOf,
                usedRoles = usedRolesFor(input.hasNarrator, input.hasDialogue)
            )
            when (state) {
                ChapterStaleState.CURRENT -> current++
                ChapterStaleState.STALE -> {
                    stale++
                    val known = input.durationMs
                    if (known != null && known > 0) staleAudioMs += known
                }
                ChapterStaleState.OUTDATED -> outdated++
                ChapterStaleState.NOT_RENDERED -> notRendered++
            }
        }
        val estimate = RenderEstimates.estimateForAudioMs(staleAudioMs)
        val wallFastMs = (staleAudioMs / RENDER_RTF_HIGH).toLong()
        val wallSlowMs = (staleAudioMs / RENDER_RTF_LOW).toLong()
        return BookStalenessSummary(
            current = current,
            stale = stale,
            outdated = outdated,
            notRendered = notRendered,
            staleAudioMs = staleAudioMs,
            staleHours = staleAudioMs / 3_600_000.0,
            wallFastMs = wallFastMs,
            wallSlowMs = wallSlowMs,
            newBytes = estimate.totalBytes,
            swapBytes = estimate.audioBytes + estimate.totalBytes
        )
    }

    /**
     * Summarizes a manifest book. Dialogue presence arrives via
     * [hasDialogueByIndex] (1-based chapter index to dialogue flag);
     * missing entries read as narrator-only (conservative: a dialogue
     * change never stales an unknown chapter, a narrator change still
     * does). Pure; the caller supplies the flags from one-at-a-time
     * chapter reads.
     */
    fun summarizeBook(
        manifest: Manifest,
        bookVoices: BookVoices,
        versionOf: (namespace: String) -> String?,
        hasDialogueByIndex: Map<Int, Boolean> = emptyMap()
    ): BookStalenessSummary {
        val inputs = manifest.chapters.map { entry ->
            StaleChapterInput(
                index = entry.index,
                durationMs = entry.durationMs,
                fingerprintJson = entry.renderFingerprint,
                hasDialogue = hasDialogueByIndex[entry.index] ?: false,
                hasNarrator = true
            )
        }
        return summarizeChapters(inputs, bookVoices, versionOf)
    }
}
