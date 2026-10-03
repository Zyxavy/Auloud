package app.auloud.player.reader

import app.auloud.player.bundle.ChapterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP3: chapter list pure logic on plain JVM.
 *
 * Covers duration formatting boundaries, jump validation and the
 * "Now playing" marker rule.
 */
class ChapterListTest {

    @Test
    fun format_zero_isZeroMinutes() {
        assertEquals("0:00", formatChapterDuration(0L))
    }

    @Test
    fun format_59s_staysUnderAMinute() {
        assertEquals("0:59", formatChapterDuration(59_000L))
    }

    @Test
    fun format_60s_isOneMinute() {
        assertEquals("1:00", formatChapterDuration(60_000L))
    }

    @Test
    fun format_61s_isOneMinuteOneSecond() {
        assertEquals("1:01", formatChapterDuration(61_000L))
    }

    @Test
    fun format_justUnderAnHour_isMinutesSeconds() {
        assertEquals("59:59", formatChapterDuration(3_599_000L))
    }

    @Test
    fun format_exactHour_isHmmss() {
        assertEquals("1:00:00", formatChapterDuration(3_600_000L))
    }

    @Test
    fun format_61m_isHmmss() {
        assertEquals("1:01:00", formatChapterDuration(3_660_000L))
    }

    @Test
    fun format_overHour_padsMinutesAndSeconds() {
        assertEquals("1:01:01", formatChapterDuration(3_661_000L))
    }

    @Test
    fun format_negative_coercesToZero() {
        assertEquals("0:00", formatChapterDuration(-5_000L))
    }

    @Test
    fun coerce_emptyPlaylist_isNull() {
        assertNull(coerceChapterJump(0, 0))
    }

    @Test
    fun coerce_negativeCount_isNull() {
        assertNull(coerceChapterJump(0, -1))
    }

    @Test
    fun coerce_negativeIndex_isNull() {
        assertNull(coerceChapterJump(-1, 3))
    }

    @Test
    fun coerce_pastEnd_isNull() {
        assertNull(coerceChapterJump(3, 3))
    }

    @Test
    fun coerce_validIndices_passThrough() {
        assertEquals(0, coerceChapterJump(0, 3))
        assertEquals(1, coerceChapterJump(1, 3))
        assertEquals(2, coerceChapterJump(2, 3))
    }

    @Test
    fun marker_currentIndex_matchesOnly() {
        assertTrue(isCurrentChapter(1, 1))
        assertFalse(isCurrentChapter(0, 1))
        assertFalse(isCurrentChapter(2, 1))
    }

    @Test
    fun mapping_oneBasedManifest_becomesZeroBasedPositions() {
        val entries = toChapterEntries(
            listOf(
                ChapterInfo(index = 1, title = "One", audio = "a", text = "t", durationMs = 60_000L),
                ChapterInfo(index = 2, title = "Two", audio = "b", text = "u", durationMs = 120_000L)
            )
        )
        assertEquals(
            listOf(
                ChapterEntry(index = 0, title = "One", durationMs = 60_000L),
                ChapterEntry(index = 1, title = "Two", durationMs = 120_000L)
            ),
            entries
        )
    }

    @Test
    fun mapping_sortsByManifestIndex() {
        val entries = toChapterEntries(
            listOf(
                ChapterInfo(index = 2, title = "Two", audio = "b", text = "u", durationMs = 2L),
                ChapterInfo(index = 1, title = "One", audio = "a", text = "t", durationMs = 1L)
            )
        )
        assertEquals(listOf("One", "Two"), entries.map { it.title })
        assertEquals(listOf(0, 1), entries.map { it.index })
    }

    @Test
    fun mapping_emptyManifest_isEmpty() {
        assertEquals(emptyList<ChapterEntry>(), toChapterEntries(emptyList()))
    }
}
