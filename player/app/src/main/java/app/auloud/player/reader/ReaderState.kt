package app.auloud.player.reader

import app.auloud.player.bundle.ChapterText

/**
 * RA3: reader UI state, derived from the playback controller state.
 *
 * One saved position is shared across all modes (RA7): [positionMs] and
 * [chapterIndex] always mirror playback; [currentSid] is the highlight
 * computed from them via [SentenceIndex]. The 200 ms reader ticker updates
 * [currentSid] only — never [positionMs] — so the flow stays silent between
 * sentence changes (see [ReaderViewModel]).
 *
 * [textError] is non-null when the chapter has no usable text (missing file,
 * PDF form, corrupt JSON): the reader shows "text unavailable" while the
 * controls stay usable (RA10). [isTextLoading] covers the load in flight.
 *
 * API 24 safe: pure Kotlin.
 */
enum class ReaderMode {
    Read,
    Listen,
    ReadListen
}

enum class FollowState {
    Following,
    Detached
}

data class ReaderState(
    val chapterIndex: Int = 0,
    val chapter: ChapterText? = null,
    val mode: ReaderMode = ReaderMode.ReadListen,
    val follow: FollowState = FollowState.Following,
    val currentSid: Int? = null,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isTextLoading: Boolean = false,
    val textError: String? = null,
    /**
     * CP3 follow-up: tapped sentence awaiting jump confirmation (null when no
     * prompt is open). The screen shows a confirm dialog instead of seeking
     * immediately, so accidental taps never move playback.
     */
    val pendingTapSid: Int? = null,
    /**
     * RA9: highlight lag (position at change minus sentence start), average
     * and max over the last [LagTracker.LAG_WINDOW] changes. Null until the
     * first change after a chapter load. Debug overlay only.
     */
    val lagAvgMs: Long? = null,
    val lagMaxMs: Long? = null,
    /**
     * RA10: why the text is absent (null when text is loaded or loading).
     * The screen renders per-kind messaging; the controls stay usable in
     * every case.
     */
    val textKind: TextKind? = null
)

/** Missing file, corrupt content, or a page-only PDF chapter (listening still works). */
enum class TextKind {
    Missing,
    Corrupt,
    PdfForm
}

/** Follow-state machine inputs (RA5 owns the scroll source; RA6 the jump). */
sealed interface FollowEvent {
    data object UserScrolled : FollowEvent
    data object BackToNow : FollowEvent
    data object ChapterChanged : FollowEvent
    data object TextJump : FollowEvent
}

/**
 * Pure follow transition. A chapter change always re-attaches at the new
 * chapter start; a manual scroll detaches; an explicit jump or back-to-now
 * re-attaches.
 */
fun reduceFollow(current: FollowState, event: FollowEvent): FollowState {
    return when (event) {
        FollowEvent.UserScrolled -> FollowState.Detached
        FollowEvent.BackToNow,
        FollowEvent.ChapterChanged,
        FollowEvent.TextJump -> FollowState.Following
    }
}
