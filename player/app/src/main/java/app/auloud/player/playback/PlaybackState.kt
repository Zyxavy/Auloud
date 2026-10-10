package app.auloud.player.playback

/**
 * WP7: UI state for one book's playback.
 *
 * All fields are primitives/Strings so Compose can skip unchanged scopes on
 * the slow Tab E. [chapterIndex] follows the cross-WP convention: 0-based
 * playlist position = ExoPlayer item index = `ProgressEntity.chapterIndex`.
 * Finished = last chapter at full duration (see [ProgressSavePolicy]).
 *
 * [lastSaveWallMs] is WP9 debug-only: wall-clock time of the last progress
 * save as recorded by the service ([DebugSaveTracker]). Always 0 in release
 * builds (the controller only copies the tracker under `BuildConfig.DEBUG`);
 * the debug overlay shows "never" for 0.
 *
 * API 24 safe: pure Kotlin.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val bookTitle: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val chapterCount: Int = 0,
    val isConnected: Boolean = false,
    val lastSaveWallMs: Long = 0L,
    /** RA8: sleep timer remaining ms (null = off). Copied from the service snapshot. */
    val sleepRemainingMs: Long? = null,
    /** ST5: live voice sentence sid (null when no stream is speaking). */
    val streamSid: Int? = null,
    /**
     * CP4: transient skip/storage notice ("Skipped ..." or
     * "Storage unavailable - playback paused"). Null when nothing is
     * showing. The UI dismisses it on tap or after ~6 s via
     * `clearSkipNotice()`; it never blocks controls.
     */
    val skipNotice: String? = null
)
