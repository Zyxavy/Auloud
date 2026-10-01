package app.auloud.player.reader

/**
 * RA5: pure follow/scroll rules (the Compose wiring lives in [ReaderScreen]).
 *
 * User scrolls are detected by touch source (drag), never by flag: only a
 * finger drag detaches, so programmatic auto-scrolls cannot cause a
 * jitter loop. These functions decide what the wiring does.
 *
 * API 24 safe: pure Kotlin.
 */

/**
 * The "back to now" button shows only when detached AND the current
 * sentence's block is off screen (unknown block counts as off screen —
 * safer to offer the way back).
 */
fun shouldShowBackToNow(
    follow: FollowState,
    currentBlockIndex: Int?,
    visibleBlocks: IntRange
): Boolean {
    if (follow != FollowState.Detached) return false
    if (currentBlockIndex == null) return true
    return currentBlockIndex !in visibleBlocks
}

/**
 * Pixel offset for `animateScrollToItem(blockIndex, scrollOffset)` that
 * lands a sentence line in the upper third of the viewport: the item's top
 * ends `scrollOffset` pixels above the viewport top, so offset = lineTop −
 * viewport/3. Clamped to 0 (block top) for lines already in the top third.
 */
fun scrollOffsetForLine(lineTopPx: Float, viewportHeightPx: Int): Int {
    if (viewportHeightPx <= 0 || lineTopPx <= 0f) return 0
    return (lineTopPx - viewportHeightPx / 3f).toInt().coerceAtLeast(0)
}
