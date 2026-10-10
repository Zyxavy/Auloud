package app.auloud.player.reader

import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence

/**
 * IN9: reader UI state for unrendered (2.0 `none`/`partial`) books.
 *
 * There are no milliseconds, so the position is chapter index + sentence
 * sid (spec section 8); the 200 ms audio ticker, lag stats and seek logic
 * of [ReaderState] do not run here. [currentSid] is the reading position:
 * restored from progress on open, moved by chapter jumps, taps and the
 * debounced top-visible report, saved through the progress repository.
 *
 * [chapterCount] is the manifest chapter total (jump validation);
 * [chapter] holds the loaded chapter text (null while loading or on
 * error); [follow] reuses the shared follow machine (manual scroll
 * detaches, back-to-now re-attaches at [currentSid]).
 *
 * API 24 safe: pure Kotlin.
 */
data class UnrenderedReaderState(
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapter: ChapterText? = null,
    val currentSid: Int? = null,
    val isTextLoading: Boolean = false,
    val textError: String? = null,
    val textKind: TextKind? = null,
    val follow: FollowState = FollowState.Following,
    /**
     * ST5: spoken sentence sid (null when no stream is speaking). The
     * screen highlights this over [currentSid]; the reading position
     * (saves, back-to-now anchor) stays in [currentSid]. Never saved
     * from here (the service saves the stream spot on its own cadence).
     */
    val liveSid: Int? = null
)

/**
 * ST5: chapter progress by sentence fraction for live mode (the stream
 * path has no milliseconds). Null when the voice position is unknown or
 * off-chapter; 1.0 at the last sentence. Pure.
 */
fun liveFractionOf(liveSid: Int?, sentences: List<Sentence>): Float? {
    if (liveSid == null || sentences.isEmpty()) return null
    val index = sentences.indexOfFirst { it.sid == liveSid }
    if (index < 0) return null
    return (index + 1).toFloat() / sentences.size.toFloat()
}

/**
 * IN9: restores the reading sid for a chapter.
 *
 * Returns [savedSid] when it names a sentence in [sentences]; otherwise the
 * first sid (a re-imported or changed book whose saved sid is gone still
 * opens at the chapter start, never null when sentences exist). Empty
 * chapters restore to null. Pure so the missing-sid fallback is
 * JVM-testable.
 */
fun restoreReadingSid(savedSid: Int?, sentences: List<Sentence>): Int? {
    if (sentences.isEmpty()) return null
    if (savedSid != null && sentences.any { it.sid == savedSid }) return savedSid
    return sentences.first().sid
}
