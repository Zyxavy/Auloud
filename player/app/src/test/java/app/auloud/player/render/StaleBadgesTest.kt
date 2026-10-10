package app.auloud.player.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VS5: stale badges, chips, banner rules and re-render targets on plain
 * JVM (see `StaleBadges`).
 *
 * Covers the brief Verify: state-to-badge mapping, counts, banner
 * show/hide rules and per-chapter action wiring (intent mode/scope as
 * the pure [RerenderStart] plus the service mode-name mapping). No
 * Android, no Robolectric.
 */
class StaleBadgesTest {

    private fun summary(
        current: Int = 0,
        stale: Int = 0,
        outdated: Int = 0,
        notRendered: Int = 0
    ) = BookStalenessSummary(
        current = current,
        stale = stale,
        outdated = outdated,
        notRendered = notRendered,
        staleAudioMs = 0L,
        staleHours = 0.0,
        wallFastMs = 0L,
        wallSlowMs = 0L,
        newBytes = 0L,
        swapBytes = 0L
    )

    // State-to-badge mapping (badges always carry the `Voices:` prefix so
    // the stale axis never reads as job progress).

    @Test
    fun badge_current() {
        assertEquals("Voices: current", staleBadgeText(ChapterStaleState.CURRENT))
    }

    @Test
    fun badge_stale() {
        assertEquals(
            "Voices: changed - needs re-render",
            staleBadgeText(ChapterStaleState.STALE)
        )
    }

    @Test
    fun badge_outdated() {
        assertEquals(
            "Voices: outdated (engine updated)",
            staleBadgeText(ChapterStaleState.OUTDATED)
        )
    }

    @Test
    fun badge_notRendered() {
        assertEquals("Voices: not rendered", staleBadgeText(ChapterStaleState.NOT_RENDERED))
    }

    @Test
    fun badge_neverMatchesJobProgressLine() {
        val badges = ChapterStaleState.values().map(::staleBadgeText)
        val jobLines = ChapterRenderState.values().map(::chapterStatusText)
        for (badge in badges) {
            assertFalse("badge must stay distinct, was: $badge", badge in jobLines)
        }
    }

    // Library chip counts.

    @Test
    fun chip_zeroOrNegative_hides() {
        assertNull(staleChipText(0))
        assertNull(staleChipText(-3))
    }

    @Test
    fun chip_one_singular() {
        assertEquals("1 chapter needs re-render", staleChipText(1))
    }

    @Test
    fun chip_many_plural() {
        assertEquals("3 chapters need re-render", staleChipText(3))
    }

    // Mixed-voice banner show/hide rules (D-119; also surfaces the VS4
    // LATER state, which is exactly stale-plus-current with no job).

    @Test
    fun banner_mixedStaleAndCurrent_shows() {
        val banner = bannerFor(summary(current = 3, stale = 2))
        assertTrue(banner != null)
        assertEquals(2, banner!!.stale)
        assertEquals(5, banner.total)
        assertEquals("Mixed voices: 2 of 5 chapters use older voices.", banner.text)
    }

    @Test
    fun banner_singleStale_singular() {
        val banner = bannerFor(summary(current = 4, stale = 1))
        assertTrue(banner != null)
        assertEquals("Mixed voices: 1 of 5 chapters uses older voices.", banner!!.text)
    }

    @Test
    fun banner_stalePlusOutdated_shows() {
        // Outdated chapters carry the book voices (version string only),
        // so stale-plus-outdated still mixes audible voices.
        assertTrue(bannerFor(summary(stale = 1, outdated = 2)) != null)
    }

    @Test
    fun banner_allCurrent_hides() {
        assertNull(bannerFor(summary(current = 5)))
    }

    @Test
    fun banner_allStale_hides() {
        // Nothing on the new voices yet: a bulk re-render button covers
        // this, not the mixed banner.
        assertNull(bannerFor(summary(stale = 4)))
        assertFalse(isMixedVoice(summary(stale = 4)))
    }

    @Test
    fun banner_nothingRendered_hides() {
        assertNull(bannerFor(summary(notRendered = 4)))
    }

    @Test
    fun banner_outdatedOnly_hides() {
        assertNull(bannerFor(summary(outdated = 3)))
    }

    @Test
    fun banner_stalePlusUnrenderedOnly_hides() {
        // Unrendered chapters are not "old voice" audio.
        assertNull(bannerFor(summary(stale = 2, notRendered = 3)))
    }

    @Test
    fun banner_totalCountsEveryChapter() {
        val banner = bannerFor(summary(current = 1, stale = 1, outdated = 1, notRendered = 1))
        assertTrue(banner != null)
        assertEquals(4, banner!!.total)
    }

    // Per-chapter action wiring (intent mode/scope as pure targets; the
    // host calls `startRerenderIntent` with these).

    @Test
    fun perChapter_startsStaleOnlyAtChapter() {
        assertEquals(
            RerenderStart(readingChapter = 4, mode = RerenderMode.STALE_ONLY),
            perChapterRerender(4)
        )
    }

    @Test
    fun bulk_startsStaleOnlyFromReadingPosition() {
        assertEquals(
            RerenderStart(readingChapter = 1, mode = RerenderMode.STALE_ONLY),
            staleOnlyRerender(1)
        )
    }

    @Test
    fun modeNames_matchServiceIntentModes() {
        assertEquals(RenderService.RERENDER_STALE_ONLY, rerenderModeName(RerenderMode.STALE_ONLY))
        assertEquals(RenderService.RERENDER_FROM_HERE, rerenderModeName(RerenderMode.FROM_HERE))
        assertEquals(RenderService.RERENDER_ALL, rerenderModeName(RerenderMode.ALL))
    }
}
