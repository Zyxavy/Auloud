package app.auloud.player.playback

import app.auloud.player.bundle.ChapterInfo
import app.auloud.player.bundle.Manifest
import app.auloud.player.data.ProgressEntity

/**
 * WP6: one playlist entry, in manifest chapter order.
 *
 * URIs are plain strings so this mapping stays plain-JVM-testable (no
 * `android.net.Uri`, no Media3). The service converts them with
 * `Uri.parse` when building `MediaItem`s.
 *
 * [chapterIndex] is the 0-based position in manifest chapter order, which is
 * also the ExoPlayer media-item index and the [ProgressEntity.chapterIndex]
 * convention (NOT the 1-based `ChapterInfo.index` from the manifest).
 */
data class QueueItem(
    val chapterIndex: Int,
    /** Manifest-relative audio path, e.g. `audio/ch001.mp3` (kept for log messages). */
    val audioRelPath: String,
    /** Resolved audio URI string (via `BundleStorage.audioUri`). */
    val audioUri: String,
    val title: String,
    /** Book title, shown as the track artist. */
    val artist: String,
    /** Resolved cover URI string, or null when the book has no readable cover. */
    val artworkUri: String?,
    val durationMs: Long
)

/** WP6: where playback of a loaded book starts. */
data class StartPosition(val chapterIndex: Int, val positionMs: Long)

/**
 * WP6: pure Manifest-to-playlist mapping plus saved-position mapping.
 *
 * No Android or Media3 types: unit-tested on plain JVM
 * (`PlaybackQueueTest`). The service (`PlaybackService`) only wires the
 * result into ExoPlayer.
 */
object PlaybackQueue {

    /**
     * One [QueueItem] per manifest chapter, sorted by manifest `index`
     * ascending. [audioUriOf] resolves `(bundleDir, relPath)` exactly like
     * `BundleStorage.audioUri` (the service passes that method); tests pass
     * a fake. The same [artworkUri] is attached to every item.
     */
    fun build(
        manifest: Manifest,
        bundleDir: String,
        audioUriOf: (bundleDir: String, relPath: String) -> String,
        artworkUri: String?
    ): List<QueueItem> =
        manifest.chapters.sortedBy { it.index }.mapIndexed { position, chapter ->
            QueueItem(
                chapterIndex = position,
                audioRelPath = chapter.audio,
                audioUri = audioUriOf(bundleDir, chapter.audio),
                title = chapter.title,
                artist = manifest.title,
                artworkUri = artworkUri,
                // IN1: unrendered 2.0 chapters carry no duration (null);
                // playback of unrendered books is gated in IN9, this keeps
                // the rendered path compiling with a 0 fallback.
                durationMs = chapter.durationMs ?: 0L
            )
        }

    /**
     * Maps a saved [ProgressEntity] to a playlist start. Null progress starts
     * at the beginning. Out-of-range chapters clamp into `[0, chapterCount)`,
     * negative positions clamp to 0, and positions past the chapter's
     * duration clamp to it (via [durations], empty when unknown). An empty
     * book yields `(0, 0)`; the service refuses to load it.
     */
    fun startFrom(
        progress: ProgressEntity?,
        chapterCount: Int,
        durations: List<Long> = emptyList()
    ): StartPosition {
        if (chapterCount <= 0) return StartPosition(0, 0)
        if (progress == null) return StartPosition(0, 0)
        val chapter = progress.chapterIndex.coerceIn(0, chapterCount - 1)
        val maxPosition = durations.getOrNull(chapter)?.coerceAtLeast(0L) ?: Long.MAX_VALUE
        return StartPosition(chapter, progress.positionMs.coerceIn(0L, maxPosition))
    }

    /**
     * IN9: a chapter is rendered when it carries both an audio path and a
     * positive duration. Unrendered 2.0 chapters omit both (absent, never
     * null); a blank path or a missing/non-positive duration means there is
     * no audio to play.
     */
    fun isRenderedChapter(chapter: ChapterInfo): Boolean =
        chapter.audio.isNotBlank() &&
            chapter.durationMs != null &&
            chapter.durationMs > 0L

    /**
     * IN9: playback gate for a manifest.
     *
     * [PlaybackGate.Playable] only when every listed chapter is rendered
     * (all 1.x books and 2.0 `complete` books). Anything else (`none`,
     * `partial`, or an invalid entry) is [PlaybackGate.NeedsRender]: the
     * service refuses to load and the reader shows the listen-unavailable
     * hint instead of building an ExoPlayer item from an empty path.
     */
    enum class PlaybackGate {
        Playable,
        NeedsRender
    }

    fun gateFor(manifest: Manifest): PlaybackGate {
        if (manifest.chapters.isEmpty()) return PlaybackGate.NeedsRender
        return if (manifest.chapters.all(::isRenderedChapter)) {
            PlaybackGate.Playable
        } else {
            PlaybackGate.NeedsRender
        }
    }

    /**
     * IN9: queue items only for rendered chapters, in manifest `index`
     * order with 0-based positions.
     *
     * For fully rendered books this is identical to [build] (same order,
     * same positions); for books with unrendered chapters it skips the
     * ones with no audio instead of building an item from an empty path.
     * The service gates on [gateFor] first, so a `none` book never reaches
     * `setMediaItems` with an empty list silently: it fails shaped with
     * [NEEDS_RENDER_MESSAGE].
     */
    fun buildPlayable(
        manifest: Manifest,
        bundleDir: String,
        audioUriOf: (bundleDir: String, relPath: String) -> String,
        artworkUri: String?
    ): List<QueueItem> =
        manifest.chapters.sortedBy { it.index }
            .filter(::isRenderedChapter)
            .mapIndexed { position, chapter ->
                QueueItem(
                    chapterIndex = position,
                    audioRelPath = chapter.audio,
                    audioUri = audioUriOf(bundleDir, chapter.audio),
                    title = chapter.title,
                    artist = manifest.title,
                    artworkUri = artworkUri,
                    durationMs = chapter.durationMs ?: 0L
                )
            }

    /**
     * IN9: graceful outcome when [gateFor] says [PlaybackGate.NeedsRender].
     * Shown in the reader mode switcher and logged by the service; Slice 10
     * supplies the rendering that clears it.
     */
    const val NEEDS_RENDER_MESSAGE = "render audio to listen"
}
