package app.auloud.player.playback

/**
 * ST5: service-to-UI snapshot of the live voice position
 * (same-process, WP9/SleepTimerMonitor precedent).
 *
 * The stream player publishes every started sentence here; the
 * controller copies it into [PlaybackState.streamSid] on its regular
 * refresh, so no polling or IPC exists. Volatile writes only. Cleared
 * whenever the stream path stops, so a rendered book never reads a
 * stale sid (in-memory: process death clears it too).
 *
 * API 24 safe: pure Kotlin.
 */
object StreamSidMonitor {
    @Volatile
    var bookId: String? = null
        private set

    @Volatile
    var chapterPos: Int = -1
        private set

    @Volatile
    var sid: Int? = null
        private set

    fun publish(bookId: String, chapterPos: Int, sid: Int) {
        this.bookId = bookId
        this.chapterPos = chapterPos
        this.sid = sid
    }

    fun clear() {
        bookId = null
        chapterPos = -1
        sid = null
    }
}
