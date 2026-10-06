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
 * [chapterIndex] is the 0-based position in manifest chapter order, matching
 * the [ProgressEntity.chapterIndex] convention (NOT the 1-based
 * `ChapterInfo.index` from the manifest). On fully rendered books this is
 * also the ExoPlayer media-item index; on sparse books the media-item index
 * is the item order in the gated list (see `ChapterMediaMap`), while
 * [chapterIndex] stays the manifest position so progress saves land on the
 * right chapter.
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
     * Legacy ungated mapping (WP6 shape, kept for the IN9 gate test that
     * documents what the gate prevents).
     *
     * RN7 (#14 item 1): deprecated. It returns items with blank audio paths
     * for unrendered chapters, so any future caller bypasses the gate. Use
     * [gateFor] plus [buildPlayable] instead (same signature, gated).
     */
    @Deprecated(
        "Ungated: returns blank-path items for unrendered chapters. " +
            "Use gateFor plus buildPlayable instead."
    )
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
     * RN7: service refusal for books with no playable audio (IN9 gate,
     * #14 item 2).
     *
     * Returns success when [gateFor] says [PlaybackGate.Playable], else a
     * shaped failure carrying the exact message `prepareBook` throws, so
     * the refusal logic is unit-testable on plain JVM without starting the
     * service. The service delegates to this (same code path, same string).
     */
    fun requirePlayable(manifest: Manifest): Result<Unit> =
        if (gateFor(manifest) == PlaybackGate.Playable) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "manifest.json: book has no playable audio " +
                        "($NEEDS_RENDER_MESSAGE)"
                )
            )
        }

    /**
     * Maps a saved [ProgressEntity] to a playlist start. Null progress starts
     * at the beginning. Out-of-range chapters clamp into `[0, chapterCount)`,
     * negative positions clamp to 0, and positions past the chapter's
     * duration clamp to it (via [durations], empty when unknown). An empty
     * book yields `(0, 0)`; the service refuses to load it.
     *
     * RN7 (#14 item 3): this position-based form is correct only when the
     * playlist position IS the manifest chapter position (fully rendered
     * books, where the mapping is the identity). Partial books must use
     * [startFromMap], which converts through the explicit chapter-to-media
     * mapping instead of reusing a filtered position as a chapter index.
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
     * RN7: queue items only for rendered chapters, in manifest `index`
     * order with 0-based playlist positions.
     *
     * [QueueItem.chapterIndex] is the 0-based manifest chapter position
     * (matching [ProgressEntity.chapterIndex]), NOT the filtered playlist
     * position: on sparse books the two differ, and reusing the filtered
     * position as the chapter index would land saved progress on the wrong
     * chapter (#14 item 3, fixed via the explicit mapping in
     * `app.auloud.player.render`). The playlist position is the item order
     * in the returned list. For fully rendered books this is identical to
     * [build] (same order, same positions).
     *
     * The service gates on [gateFor] (or [requirePlayable]) first, so a
     * `none` book never reaches `setMediaItems` with an empty list
     * silently: it fails shaped with [NEEDS_RENDER_MESSAGE].
     */
    fun buildPlayable(
        manifest: Manifest,
        bundleDir: String,
        audioUriOf: (bundleDir: String, relPath: String) -> String,
        artworkUri: String?
    ): List<QueueItem> {
        val sorted = manifest.chapters.sortedBy { it.index }
        return sorted.mapIndexedNotNull { pos, chapter ->
            if (!isRenderedChapter(chapter)) return@mapIndexedNotNull null
            QueueItem(
                chapterIndex = pos,
                audioRelPath = chapter.audio,
                audioUri = audioUriOf(bundleDir, chapter.audio),
                title = chapter.title,
                artist = manifest.title,
                artworkUri = artworkUri,
                durationMs = chapter.durationMs ?: 0L
            )
        }
    }

    /**
     * RN7: mapping-aware playlist start for partial books (D-099).
     *
     * Converts a manifest-keyed [ProgressEntity] through [map] (built by
     * `buildChapterMediaMap` from the same manifest): the returned
     * [StartPosition.chapterIndex] is the MEDIA index for `setMediaItems`,
     * and the position clamps to the saved chapter's duration (via
     * [durationsByPos] keyed by manifest chapter position, empty when
     * unknown). Saved sid positions must be converted to milliseconds
     * first (see `RenderProgress`: by playback time every rendered-chapter
     * save is millisecond-based).
     *
     * Rules: null progress starts at the first rendered item; a saved
     * chapter that is rendered starts at its media index (position
     * clamped); a saved chapter that is NOT rendered (or out of range)
     * falls back to the first rendered item at 0, so playback never opens
     * on silence. An empty map yields `(0, 0)`; callers refuse to load it.
     */
    fun startFromMap(
        progress: ProgressEntity?,
        map: app.auloud.player.render.ChapterMediaMap,
        durationsByPos: Map<Int, Long> = emptyMap()
    ): StartPosition {
        if (map.entries.isEmpty()) return StartPosition(0, 0)
        if (progress == null) return StartPosition(0, 0)
        val chapterCount = map.chapterCount.coerceAtLeast(1)
        val pos = progress.chapterIndex.coerceIn(0, chapterCount - 1)
        val media = map.mediaIndexOf(pos) ?: return StartPosition(0, 0)
        val maxPosition = durationsByPos[pos]?.coerceAtLeast(0L) ?: Long.MAX_VALUE
        return StartPosition(media, progress.positionMs.coerceIn(0L, maxPosition))
    }

    /**
     * IN9: graceful outcome when [gateFor] says [PlaybackGate.NeedsRender].
     * Shown in the reader mode switcher and logged by the service; Slice 10
     * supplies the rendering that clears it.
     */
    const val NEEDS_RENDER_MESSAGE = "render audio to listen"
}
