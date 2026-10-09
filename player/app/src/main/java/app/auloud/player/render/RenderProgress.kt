package app.auloud.player.render

import app.auloud.player.bundle.ChapterText
import app.auloud.player.data.ProgressEntity

/**
 * RN7: saved sentence-sid progress converts to milliseconds (Slice 10, D-099).
 *
 * Unrendered books save chapter index plus sentence sid (no milliseconds
 * exist yet). When that chapter renders, the first render converts the
 * saved sid to the sentence `start_ms` from the new timings, so playback
 * resumes where reading stopped. Millisecond positions on
 * already-rendered chapters pass through untouched, and sid saves for
 * chapters that are still unrendered keep working (the RN9 reader path).
 *
 * VS3 (D-117): re-rendered chapters save milliseconds from the old
 * audio. Before the swap the old position converts old milliseconds to
 * sentence id (via the old timings) to new milliseconds (via the new
 * timings) through [convertOnRerender], which bridges through
 * [convertOnRender] so the sid step stays single-sourced.
 *
 * Pure plus JVM-testable: the caller (RN8, after finalize) supplies the
 * saved row plus the freshly timed chapter JSON. API 24 safe: pure
 * Kotlin, no `java.time`, no Android types.
 */
object RenderProgress {

    /**
     * `start_ms` of [sid] in the timed [chapter], or null when the chapter
     * is untimed or names no such sentence.
     */
    fun msForSid(chapter: ChapterText, sid: Int): Long? =
        chapter.sentencesInOrder().firstOrNull { it.sid == sid }?.startMs

    /**
     * Converts [saved] after the chapter at [renderedChapterPos] (0-based
     * manifest position) renders with [timedChapter] (the JSON just
     * written by finalize).
     *
     * - null saved, a saved row for a different chapter, or a
     *   millisecond save (`sentenceSid` null) returns [saved] unchanged:
     *   ms positions on already-rendered chapters are never rewritten.
     * - a sid save for this chapter while [timedChapter] is still untimed
     *   returns [saved] unchanged (the chapter did not actually render;
     *   unrendered-book sid saves keep working).
     * - a sid save for this chapter with a timed [timedChapter] returns a
     *   millisecond row at the sentence start (`sentenceSid` cleared). A
     *   sid the new chapter no longer names (re-imported text) falls back
     *   to the chapter start (0), never null and never a crash.
     */
    fun convertOnRender(
        saved: ProgressEntity?,
        renderedChapterPos: Int,
        timedChapter: ChapterText
    ): ProgressEntity? {
        if (saved == null) return null
        if (saved.chapterIndex != renderedChapterPos) return saved
        val sid = saved.sentenceSid ?: return saved
        if (timedChapter.durationMs == null) return saved
        val positionMs = msForSid(timedChapter, sid) ?: 0L
        return saved.copy(positionMs = positionMs, sentenceSid = null)
    }

    /**
     * VS3 (D-117): sentence id of the sentence containing [positionMs]
     * in a timed chapter (playback rule: the last sentence whose
     * `start_ms` is at or before the position; gaps belong to the
     * previous sentence). Null when the chapter is untimed or empty.
     */
    fun sidForMs(chapter: ChapterText, positionMs: Long): Int? {
        if (chapter.durationMs == null) return null
        var current: Int? = null
        for (sentence in chapter.sentencesInOrder()) {
            val start = sentence.startMs ?: return null
            if (positionMs >= start) {
                current = sentence.sid
            } else {
                break
            }
        }
        return current
    }

    /**
     * VS3 (D-117): converts [saved] before the re-render swap.
     *
     * Old milliseconds bridge through sentence id: the old position
     * finds its sentence in [oldChapter], then [convertOnRender] maps
     * that sid into [newTimedChapter]. Call before the manifest switch
     * so the old timings are still available; cancel or failure skips
     * this call entirely and the old position stays valid on old audio.
     *
     * - null saved or a row for a different chapter returns [saved].
     * - a sid save delegates to [convertOnRender] with the new chapter
     *   (unrendered-book rows keep working).
     * - a ms save with an untimed old or new chapter returns [saved]
     *   (nothing rendered to convert into).
     * - a ms save whose old position names no sentence falls back to
     *   chapter start (0) via the [convertOnRender] missing-sid rule.
     */
    fun convertOnRerender(
        saved: ProgressEntity?,
        renderedChapterPos: Int,
        oldChapter: ChapterText,
        newTimedChapter: ChapterText
    ): ProgressEntity? {
        if (saved == null) return null
        if (saved.chapterIndex != renderedChapterPos) return saved
        val sid = saved.sentenceSid
        if (sid != null) {
            return convertOnRender(saved, renderedChapterPos, newTimedChapter)
        }
        if (oldChapter.durationMs == null) return saved
        if (newTimedChapter.durationMs == null) return saved
        val oldSid = sidForMs(oldChapter, saved.positionMs) ?: return saved.copy(
            positionMs = 0L,
            sentenceSid = null
        )
        val asSidSave = saved.copy(positionMs = 0L, sentenceSid = oldSid)
        return convertOnRender(asSidSave, renderedChapterPos, newTimedChapter)
    }
}
