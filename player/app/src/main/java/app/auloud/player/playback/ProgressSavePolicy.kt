package app.auloud.player.playback

/**
 * WP6: pure rules for when and what progress gets saved.
 *
 * No Android types and no clock reads (callers pass monotonic times such as
 * `SystemClock.uptimeMillis()`): unit-tested on plain JVM
 * (`ProgressSavePolicyTest`). The service (`PlaybackService`) only wires
 * player callbacks to these rules and performs the `ProgressRepository`
 * write.
 *
 * API 24 safe: pure Kotlin.
 */
object ProgressSavePolicy {

    /** Save cadence while playing (android-api24 skill rule). */
    const val SAVE_INTERVAL_MS = 5_000L

    /**
     * FP2: consecutive failed writes before the service surfaces a notice.
     * Three keeps one transient failure silent (a busy disk) while a stuck
     * store (full disk, dead SD) speaks up within ~15 s of periodic saves.
     */
    const val SAVE_FAILURE_NOTICE_AFTER = 3

    /** A persistable listening spot: book manifest `id` + 0-based playlist chapter + ms. */
    data class SavePoint(val bookId: String, val chapterIndex: Int, val positionMs: Long) {
        init {
            require(bookId.isNotBlank()) { "bookId must not be blank" }
            require(chapterIndex >= 0) { "chapterIndex must be >= 0, was $chapterIndex" }
            require(positionMs >= 0) { "positionMs must be >= 0, was $positionMs" }
        }
    }

    /**
     * Periodic-save rule: fire only while playing and only once the interval
     * since [lastSaveUptimeMs] has elapsed (boundary `>=` fires exactly at
     * the interval). Times must come from a monotonic clock so wall-clock
     * changes never trigger or suppress a save.
     */
    fun shouldSavePeriodic(
        lastSaveUptimeMs: Long,
        nowUptimeMs: Long,
        isPlaying: Boolean,
        intervalMs: Long = SAVE_INTERVAL_MS
    ): Boolean = isPlaying && nowUptimeMs - lastSaveUptimeMs >= intervalMs

    /**
     * What to save on pause, chapter change (`onMediaItemTransition`),
     * periodic tick and destroy/task-removed: the current spot. Returns null
     * when there is nothing loaded (unknown book, unset item index,
     * negative position) so the service skips the write.
     */
    fun pointOrNull(bookId: String?, chapterIndex: Int, positionMs: Long): SavePoint? {
        if (bookId.isNullOrBlank() || chapterIndex < 0 || positionMs < 0) return null
        return SavePoint(bookId, chapterIndex, positionMs)
    }

    /**
     * FP2: true once [consecutiveFailures] reaches the notice threshold. The
     * service resets its streak when this fires, so a stuck store
     * re-notifies every threshold instead of spamming every save.
     */
    fun shouldNotifySaveFailure(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= SAVE_FAILURE_NOTICE_AFTER

    /**
     * True when playback ended on the final playlist item: the book is
     * finished and the service must stop rather than advance.
     */
    fun isEndOfBook(chapterIndex: Int, chapterCount: Int, playbackEnded: Boolean): Boolean =
        playbackEnded && chapterCount > 0 && chapterIndex == chapterCount - 1

    /**
     * Finished state the UI can read: final chapter at its full duration.
     * Convention: a saved position with `chapterIndex` on the last chapter
     * and `positionMs >=` that chapter's duration means "book finished".
     */
    fun finishedPoint(bookId: String, chapterCount: Int, lastDurationMs: Long): SavePoint =
        SavePoint(
            bookId,
            (chapterCount - 1).coerceAtLeast(0),
            lastDurationMs.coerceAtLeast(0L)
        )
}
