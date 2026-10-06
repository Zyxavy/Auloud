package app.auloud.player.render

import app.auloud.player.bundle.Manifest
import app.auloud.player.playback.PlaybackQueue

/**
 * RN7: explicit chapter-to-media-item mapping for partial books (Slice 10, D-099).
 *
 * Rendered chapters may be non-contiguous, so a playlist position is not a
 * chapter index. This type converts between them so saved progress (keyed by
 * manifest chapter, the stable spec key) never lands on the wrong chapter
 * after filtering.
 *
 * Conventions (same as [PlaybackQueue] and the progress store):
 * - [ChapterMediaEntry.chapterPos] is the 0-based position in manifest
 *   chapter order (`sortedBy { it.index }`), NOT the 1-based manifest
 *   `index` field.
 * - [ChapterMediaEntry.chapterNumber] is the 1-based manifest `index`
 *   (file names `chNNN`, spool names, failure messages).
 * - [ChapterMediaEntry.mediaIndex] is the 0-based ExoPlayer playlist
 *   position among rendered chapters only.
 *
 * Rendered books use the identity mapping (`mediaIndex == chapterPos` for
 * every entry), so the gated queue path behaves exactly as before
 * (regression-tested). Only [PlaybackQueue.buildPlayable] plus the new
 * [PlaybackQueue.startFromMap] read this type; the ungated legacy
 * [PlaybackQueue.build] is deprecated and never consults it.
 *
 * API 24 safe: pure Kotlin, no `java.time`, no Android types.
 */
data class ChapterMediaEntry(
    /** 0-based position in manifest chapter order. */
    val chapterPos: Int,
    /** 1-based manifest `index` (file and spool naming). */
    val chapterNumber: Int,
    /** 0-based playlist position among rendered chapters. */
    val mediaIndex: Int
)

/**
 * Ordered rendered-chapter mapping plus the manifest chapter total (for
 * clamping saved positions that predate a render).
 */
data class ChapterMediaMap(
    val entries: List<ChapterMediaEntry>,
    /** Total manifest chapters (rendered plus unrendered). */
    val chapterCount: Int
) {
    /** Playlist position for a manifest chapter position, or null when unrendered. */
    fun mediaIndexOf(chapterPos: Int): Int? =
        entries.firstOrNull { it.chapterPos == chapterPos }?.mediaIndex

    /** Manifest chapter position for a playlist position, or null when out of range. */
    fun chapterPosOf(mediaIndex: Int): Int? =
        entries.firstOrNull { it.mediaIndex == mediaIndex }?.chapterPos

    /** True when the manifest chapter at [chapterPos] has audio. */
    fun isRendered(chapterPos: Int): Boolean = mediaIndexOf(chapterPos) != null

    /**
     * True when every entry maps onto itself (`mediaIndex == chapterPos`).
     * Holds for every fully rendered book, including all 1.x books.
     */
    val isIdentity: Boolean
        get() = entries.all { it.mediaIndex == it.chapterPos }
}

/**
 * Builds the mapping for [manifest]: rendered chapters only, in manifest
 * `index` order, numbered 0-based in that order.
 *
 * Rendered means [PlaybackQueue.isRenderedChapter] (non-blank audio plus
 * positive duration), the same predicate the queue builder uses, so the
 * map and the playlist can never disagree about which chapters play.
 */
fun buildChapterMediaMap(manifest: Manifest): ChapterMediaMap {
    val sorted = manifest.chapters.sortedBy { it.index }
    val entries = ArrayList<ChapterMediaEntry>()
    var mediaIndex = 0
    for ((pos, chapter) in sorted.withIndex()) {
        if (PlaybackQueue.isRenderedChapter(chapter)) {
            entries.add(
                ChapterMediaEntry(
                    chapterPos = pos,
                    chapterNumber = chapter.index,
                    mediaIndex = mediaIndex
                )
            )
            mediaIndex++
        }
    }
    return ChapterMediaMap(entries = entries, chapterCount = sorted.size)
}

/**
 * RN7: per-chapter listening gate (D-099).
 *
 * Listen and Read + listen are enabled per chapter from the mapping: a
 * chapter plays exactly when it is rendered. The book-level
 * `isListenAvailable` rule is unchanged (RN8/RN9 wire this per-chapter
 * gate into the service plus the chapter rows).
 */
fun isChapterListeningEnabled(chapterPos: Int, map: ChapterMediaMap): Boolean =
    map.isRendered(chapterPos)

/**
 * RN7: end-of-rendered-portion rule (D-099).
 *
 * True when [mediaIndex] is the last playable item: playback stops here
 * with [END_OF_RENDERED_MESSAGE] instead of advancing into unrendered
 * chapters (there is no media item for them). Empty maps never report the
 * end (there is nothing playing to stop).
 */
fun isEndOfRenderedPortion(mediaIndex: Int, map: ChapterMediaMap): Boolean =
    map.entries.isNotEmpty() && mediaIndex == map.entries.size - 1

/**
 * RN7: plain-language stop message at the end of the rendered portion.
 *
 * Shown when playback reaches the last rendered chapter of a partial book;
 * the saved position stays at that spot (the service saves on pause, which
 * RN8 wires through the mapping). No crash, no auto-skip into silence.
 */
const val END_OF_RENDERED_MESSAGE =
    "End of the rendered part. Render more chapters to keep listening."
