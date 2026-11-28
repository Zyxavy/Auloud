package app.auloud.player.playback

/**
 * WP12: caps stream reloads. A stream failure reloads the book (a fresh
 * stream), so an engine that fails before speaking would loop forever;
 * [StreamCore.MAX_CONSECUTIVE_ERRORS] resets with each new stream. The
 * gate counts failures with no speech in between and refuses the reload
 * once [maxInARow] of them land in a row. [onSpoken] clears the count.
 *
 * API 24 safe: pure Kotlin.
 */
class StreamFailureGate(private val maxInARow: Int = 2) {

    private var inARow = 0

    /** A stream failed. True when a reload is allowed. */
    fun onFailure(): Boolean {
        inARow++
        return inARow < maxInARow
    }

    /** A user-initiated start is a fresh attempt, so the count starts over. */
    fun reset() {
        inARow = 0
    }

    /** A sentence was spoken: the stream is healthy, so the count resets. */
    fun onSpoken() {
        inARow = 0
    }
}
