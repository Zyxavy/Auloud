package app.auloud.player.render

import app.auloud.player.bundle.ChapterTextLoader
import app.auloud.player.bundle.Manifest
import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR
import app.auloud.player.tts.BookVoices
import app.auloud.player.tts.TtsVoiceStore

/**
 * VS5: per-book staleness scan for badges, chips and banners (D-114, D-115).
 *
 * Classifies every manifest chapter through VS2 [RenderStaleness] so the
 * library chip, the hub chapter list and the complete-book chapter list
 * all read the same states. Wiring over existing logic; no new pipeline.
 *
 * Inputs: the already-parsed [Manifest] (the caller owns the manifest
 * read), one chapter text per RENDERED chapter through [readChapterText]
 * (unrendered chapters classify NOT_RENDERED with no read), the globals
 * for [BookVoices.read], and live engine versions for [versionOf].
 * Chapter texts stream one at a time: only role flags are kept, never the
 * text, so the one-chapter memory rule holds (same counting-pass shape as
 * the panel word-count load).
 *
 * Honesty rules (each covered by a JVM test):
 * - Read-only books (Scribe PC audio, legacy 1.x) return null: their
 *   chapters carry no device fingerprints, so every one would read STALE
 *   by the unknown-means-stale rule, and re-render is blocked for them
 *   anyway (VS6). No chip, no badges, no banner.
 * - Books with zero rendered chapters return null with NO chapter read:
 *   there is nothing stale, and the job-progress badges already say so.
 * - An unreadable or unparsable chapter text falls back to both roles
 *   (conservative: any voice change stales it, and a missing fingerprint
 *   already stales it as unknown). Unknown never reads CURRENT, so a
 *   failure can only prompt a re-render, never silently mismatch.
 *
 * Pure apart from the injected [readChapterText]; API 24 safe, no new
 * dependency, no permission. Callers catch around [scan] (fail-open:
 * null means "no stale UI", never a crash); the chapter loop itself
 * never throws for per-chapter problems.
 */
data class StaleBookData(
    /** 0-based manifest position to staleness (every chapter, incl. unrendered). */
    val states: Map<Int, ChapterStaleState>,
    val summary: BookStalenessSummary
)

object StaleBookScan {

    /**
     * Scans one book. Returns null for read-only books and for books
     * with nothing rendered (see file KDoc); otherwise per-chapter
     * states plus the book summary.
     *
     * @param readChapterText raw chapter JSON for a manifest `text` rel,
     * or null when unreadable (the chapter reads stale-ward, never
     * current).
     */
    fun scan(
        manifest: Manifest,
        readChapterText: (textRel: String) -> String?,
        globals: TtsVoiceStore,
        versionOf: (namespace: String) -> String?
    ): StaleBookData? {
        if (BookVoices.isReadOnly(manifest)) return null
        val sorted = manifest.chapters.sortedBy { it.index }
        if (sorted.none { it.durationMs != null }) return null
        val bookVoices = BookVoices.read(manifest, globals)
        val states = LinkedHashMap<Int, ChapterStaleState>(sorted.size)
        val inputs = ArrayList<StaleChapterInput>(sorted.size)
        for ((pos, entry) in sorted.withIndex()) {
            if (entry.durationMs == null) {
                states[pos] = ChapterStaleState.NOT_RENDERED
                inputs.add(
                    StaleChapterInput(
                        index = entry.index,
                        durationMs = null,
                        fingerprintJson = entry.renderFingerprint,
                        hasDialogue = false,
                        hasNarrator = true
                    )
                )
                continue
            }
            val roles = rolesFor(entry.text, readChapterText)
            states[pos] = RenderStaleness.classifyChapter(
                durationMs = entry.durationMs,
                storedJson = entry.renderFingerprint,
                bookVoices = bookVoices,
                versionOf = versionOf,
                usedRoles = roles
            )
            inputs.add(
                StaleChapterInput(
                    index = entry.index,
                    durationMs = entry.durationMs,
                    fingerprintJson = entry.renderFingerprint,
                    hasDialogue = SPEAKER_DIALOGUE in roles,
                    hasNarrator = SPEAKER_NARRATOR in roles
                )
            )
        }
        val summary = RenderStaleness.summarizeChapters(inputs, bookVoices, versionOf)
        return StaleBookData(states = states, summary = summary)
    }

    /**
     * Used roles for one rendered chapter text rel. Parse success reads
     * the exact VS2 roles; any failure (missing file, bad JSON) reads
     * both roles so the chapter goes stale-ward (see file KDoc).
     */
    private fun rolesFor(
        textRel: String,
        readChapterText: (textRel: String) -> String?
    ): Set<String> {
        val both = setOf(SPEAKER_NARRATOR, SPEAKER_DIALOGUE)
        val raw = try {
            readChapterText(textRel)
        } catch (_: Exception) {
            null
        } ?: return both
        val chapter = try {
            ChapterTextLoader.parse(textRel, raw).getOrNull()
        } catch (_: Exception) {
            null
        } ?: return both
        return try {
            RenderStaleness.rolesUsedInChapter(chapter)
        } catch (_: Exception) {
            both
        }
    }
}
