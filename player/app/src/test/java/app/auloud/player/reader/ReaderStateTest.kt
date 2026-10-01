package app.auloud.player.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RA3: pure follow-state machine (no coroutines, no Android).
 */
class ReaderStateTest {

    @Test
    fun userScroll_detachesFromFollowing() {
        assertEquals(FollowState.Detached, reduceFollow(FollowState.Following, FollowEvent.UserScrolled))
    }

    @Test
    fun userScroll_staysDetached() {
        assertEquals(FollowState.Detached, reduceFollow(FollowState.Detached, FollowEvent.UserScrolled))
    }

    @Test
    fun backToNow_reattaches() {
        assertEquals(FollowState.Following, reduceFollow(FollowState.Detached, FollowEvent.BackToNow))
    }

    @Test
    fun chapterChange_resetsToFollowing() {
        assertEquals(FollowState.Following, reduceFollow(FollowState.Detached, FollowEvent.ChapterChanged))
        assertEquals(FollowState.Following, reduceFollow(FollowState.Following, FollowEvent.ChapterChanged))
    }

    @Test
    fun textJump_reattaches() {
        assertEquals(FollowState.Following, reduceFollow(FollowState.Detached, FollowEvent.TextJump))
    }
}
