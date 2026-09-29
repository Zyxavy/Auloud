package app.auloud.player.playback

/**
 * WP7: UI state for one book's playback.
 *
 * All fields are primitives/Strings so Compose can skip unchanged scopes on
 * the slow Tab E. [chapterIndex] follows the cross-WP convention: 0-based
 * playlist position = ExoPlayer item index = `ProgressEntity.chapterIndex`.
 * Finished = last chapter at full duration (see [ProgressSavePolicy]).
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
    val isConnected: Boolean = false
)
