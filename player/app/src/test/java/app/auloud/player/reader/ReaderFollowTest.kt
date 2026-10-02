package app.auloud.player.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RA5: follow/scroll rules on plain JVM.
 */
class ReaderFollowTest {

    @Test
    fun button_hiddenWhileFollowing() {
        assertFalse(shouldShowBackToNow(FollowState.Following, 5, 0..3))
    }

    @Test
    fun button_showsWhenDetachedAndCurrentOffScreen() {
        assertTrue(shouldShowBackToNow(FollowState.Detached, 9, 0..3))
    }

    @Test
    fun button_hiddenWhenDetachedButCurrentVisible() {
        assertFalse(shouldShowBackToNow(FollowState.Detached, 2, 0..3))
    }

    @Test
    fun button_showsWhenDetachedAndBlockUnknown() {
        assertTrue(shouldShowBackToNow(FollowState.Detached, null, 0..3))
    }

    @Test
    fun offset_lineInTopThird_snapsToBlockTop() {
        // Line at 100 px, viewport 900 (third = 300): already above the line.
        assertEquals(0, scrollOffsetForLine(100f, 900))
    }

    @Test
    fun offset_landsLineInUpperThird() {
        // Line at 700 px, viewport 900: offset 400 puts the line at 300.
        assertEquals(400, scrollOffsetForLine(700f, 900))
    }

    @Test
    fun offset_degenerateInputs_yieldZero() {
        assertEquals(0, scrollOffsetForLine(500f, 0))
        assertEquals(0, scrollOffsetForLine(0f, 900))
        assertEquals(0, scrollOffsetForLine(-10f, 900))
    }
}
