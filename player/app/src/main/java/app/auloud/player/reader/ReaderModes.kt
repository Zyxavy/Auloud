package app.auloud.player.reader

/**
 * RA7: pure mode rules (the screens own the side effects).
 *
 * One saved position is shared across all modes by construction — the
 * position always comes from playback (or the saved progress it restores),
 * never from the mode — so switching modes only changes what plays and
 * what shows, never where you are.
 *
 * Finished-book detection lives with playback
 * ([app.auloud.player.playback.isFinishedBook], D-028).
 *
 * API 24 safe: pure Kotlin.
 */

/** Entering Read pauses the audio; leaving it resumes (the screens do this). */
fun shouldPauseForMode(mode: ReaderMode): Boolean = mode == ReaderMode.Read

/**
 * The keep-screen-on setting applies only while the reader is visible:
 * Read and Read + listen, never Listen (the screen may turn off there).
 */
fun shouldKeepScreenOn(mode: ReaderMode, setting: Boolean): Boolean =
    setting && mode != ReaderMode.Listen

/**
 * IN9: whether the Listen and Read + listen modes are available for a book.
 *
 * Rendered books (1.x, whose `render_state` is null, and 2.0 `complete`)
 * can listen; unrendered (`none`) and partially rendered (`partial`) books
 * are read-only until Slice 10 renders audio. Unknown values are treated
 * as unavailable (safe side: no audio assumed).
 */
fun isListenAvailable(renderState: String?): Boolean =
    renderState == null || renderState == "complete"

/**
 * IN9: hint shown with the unavailable Listen modes for unrendered books.
 * Slice 10 supplies the rendering that clears it. Kept lowercase verbatim
 * so the UI, the service log and the tests use one string.
 */
const val LISTEN_UNAVAILABLE_HINT = "render audio to listen"
