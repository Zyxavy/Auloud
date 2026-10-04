package app.auloud.player.playback

/**
 * CP4: service-to-UI snapshot for corrupt/missing-chapter skips and
 * storage loss (same-process, SleepTimerMonitor/WP9 precedent).
 *
 * Playback errors live in [PlaybackService] (survives UI closes); the
 * controller copies a pending message into [PlaybackState.skipNotice] on
 * its regular refresh via [consume], so each skip surfaces exactly once
 * with no polling or IPC. Volatile reads only.
 *
 * The UI owns dismissal (tap or ~6 s timeout) and calls back into the
 * controller to clear the state field, so rotation cannot resurrect it.
 *
 * API 24 safe: pure Kotlin.
 */
object SkipNoticeMonitor {
    @Volatile
    private var pending: String? = null

    /** True when an unconsumed notice is waiting (ticker keep-alive read). */
    val hasPending: Boolean
        get() = pending != null

    /** Publishes a raw notice, replacing any unconsumed one. */
    fun publish(message: String) {
        pending = message
    }

    /** Skip path: names the chapter that was skipped over. */
    fun notifySkipped(label: String) {
        publish("Skipped $label - file unreadable")
    }

    /** Storage path: microSD ejected or watch folder gone, playback paused. */
    fun notifyStorageUnavailable() {
        publish("Storage unavailable - playback paused")
    }

    /**
     * Takes the pending notice and clears the slot. Returns null when
     * nothing is pending. Each skip therefore surfaces exactly once.
     */
    fun consume(): String? {
        val message = pending
        pending = null
        return message
    }

    /** Drops any pending notice (tests, teardown). */
    fun clear() {
        pending = null
    }
}
