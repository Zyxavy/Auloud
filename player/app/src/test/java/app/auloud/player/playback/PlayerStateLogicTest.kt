package app.auloud.player.playback

import app.auloud.player.data.ProgressEntity
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP7: pure player logic on plain JVM (no Robolectric, no Media3):
 * controller-state mapping (snapshots -> PlaybackState incl. chapter
 * titles/durations), seek clamping, and restore-spot computation
 * (`ProgressEntity` -> start chapter/position incl. finished-book and
 * empty-progress cases). Turbine + fakes cover the [PlayerStateHolder] flow.
 */
class PlayerStateLogicTest {

    private fun snapshot(
        isPlaying: Boolean = false,
        chapterIndex: Int = 0,
        chapterTitle: String? = "Ch 1",
        bookTitle: String? = "Example Book",
        positionMs: Long = 0L,
        durationMs: Long = 600_000L,
        chapterCount: Int = 2,
        isConnected: Boolean = true
    ) = ControllerSnapshot(
        isPlaying = isPlaying,
        chapterIndex = chapterIndex,
        chapterTitle = chapterTitle,
        bookTitle = bookTitle,
        positionMs = positionMs,
        durationMs = durationMs,
        chapterCount = chapterCount,
        isConnected = isConnected
    )

    // Controller-state mapping.

    @Test
    fun mapping_carriesPlayingChapterTitlesAndDurations() {
        val state = snapshot(
            isPlaying = true,
            chapterIndex = 1,
            chapterTitle = "Ch 2",
            bookTitle = "Example Book",
            positionMs = 61_000L,
            durationMs = 400_000L,
            chapterCount = 2
        ).toPlaybackState()

        assertTrue(state.isPlaying)
        assertEquals(1, state.chapterIndex)
        assertEquals("Ch 2", state.chapterTitle)
        assertEquals("Example Book", state.bookTitle)
        assertEquals(61_000L, state.positionMs)
        assertEquals(400_000L, state.durationMs)
        assertEquals(2, state.chapterCount)
        assertTrue(state.isConnected)
    }

    @Test
    fun mapping_pausedStaysPaused() {
        val state = snapshot(isPlaying = false).toPlaybackState()

        assertFalse(state.isPlaying)
    }

    @Test
    fun mapping_positionBeyondDuration_clampsToDuration() {
        val state = snapshot(positionMs = 999_999L, durationMs = 600_000L).toPlaybackState()

        assertEquals(600_000L, state.positionMs)
    }

    @Test
    fun mapping_negativePosition_clampsToZero() {
        val state = snapshot(positionMs = -50L).toPlaybackState()

        assertEquals(0L, state.positionMs)
    }

    @Test
    fun mapping_negativeDuration_clampsToZero() {
        val state = snapshot(positionMs = 100L, durationMs = -5L).toPlaybackState()

        assertEquals(0L, state.durationMs)
        assertEquals(0L, state.positionMs)
    }

    @Test
    fun mapping_chapterBeyondEnd_clampsToLast() {
        val state = snapshot(chapterIndex = 7, chapterCount = 2).toPlaybackState()

        assertEquals(1, state.chapterIndex)
    }

    @Test
    fun mapping_negativeChapter_clampsToFirst() {
        val state = snapshot(chapterIndex = -3, chapterCount = 2).toPlaybackState()

        assertEquals(0, state.chapterIndex)
    }

    @Test
    fun mapping_emptyPlaylist_yieldsIndexZero() {
        val state = snapshot(chapterIndex = 4, chapterCount = 0).toPlaybackState()

        assertEquals(0, state.chapterIndex)
        assertEquals(0, state.chapterCount)
    }

    @Test
    fun mapping_blankTitles_becomeEmpty() {
        val state = snapshot(chapterTitle = "  ", bookTitle = null).toPlaybackState()

        assertEquals("", state.chapterTitle)
        assertEquals("", state.bookTitle)
    }

    // Seek-position clamping.

    @Test
    fun seek_normalRequest_passesThrough() {
        assertEquals(61_000L, clampSeekRequest(61_000L, 600_000L))
    }

    @Test
    fun seek_negativeRequest_clampsToZero() {
        assertEquals(0L, clampSeekRequest(-100L, 600_000L))
    }

    @Test
    fun seek_beyondDuration_clampsToDuration() {
        assertEquals(600_000L, clampSeekRequest(999_999L, 600_000L))
    }

    @Test
    fun seek_zeroDuration_clampsToZero() {
        assertEquals(0L, clampSeekRequest(50L, 0L))
    }

    @Test
    fun seek_negativeDuration_clampsToZero() {
        assertEquals(0L, clampSeekRequest(50L, -5L))
    }

    // Restore-spot computation (ProgressEntity -> start).

    @Test
    fun restore_nullProgress_startsAtBeginning() {
        assertEquals(StartPosition(0, 0), restoreStart(null, 2, listOf(600_000L, 400_000L)))
    }

    @Test
    fun restore_savedSpot_mapsChapterAndPosition() {
        val saved = ProgressEntity("book-1", 1, 61_000L, 0L)

        assertEquals(
            StartPosition(1, 61_000L),
            restoreStart(saved, 2, listOf(600_000L, 400_000L))
        )
    }

    @Test
    fun restore_finishedBook_staysAtLastChapterFullDuration() {
        // Finished = last chapter at full duration (cross-WP convention).
        val saved = ProgressEntity("book-1", 1, 400_000L, 0L)

        assertEquals(
            StartPosition(1, 400_000L),
            restoreStart(saved, 2, listOf(600_000L, 400_000L))
        )
    }

    @Test
    fun restore_positionBeyondDuration_clampsToDuration() {
        val saved = ProgressEntity("book-1", 0, 999_999L, 0L)

        assertEquals(
            StartPosition(0, 600_000L),
            restoreStart(saved, 2, listOf(600_000L, 400_000L))
        )
    }

    @Test
    fun restore_chapterBeyondEnd_clampsToLastChapter() {
        val saved = ProgressEntity("book-1", 7, 10L, 0L)

        assertEquals(
            StartPosition(1, 10L),
            restoreStart(saved, 2, listOf(600_000L, 400_000L))
        )
    }

    @Test
    fun restore_emptyProgressBook_returnsZero() {
        val saved = ProgressEntity("book-1", 1, 61_000L, 0L)

        assertEquals(StartPosition(0, 0), restoreStart(saved, 0))
    }

    // Holder flow (Turbine + fakes).

    @Test
    fun holder_emitsMappedSnapshot() = runBlocking {
        val holder = PlayerStateHolder()

        holder.state.test {
            assertEquals(PlaybackState(), awaitItem())
            holder.onSnapshot(snapshot(isPlaying = true, positionMs = 5_000L))
            val next = awaitItem()
            assertTrue(next.isPlaying)
            assertEquals(5_000L, next.positionMs)
            assertEquals("Ch 1", next.chapterTitle)
            assertEquals(600_000L, next.durationMs)
        }
    }

    @Test
    fun holder_duplicateSnapshot_emitsNothing() = runBlocking {
        val holder = PlayerStateHolder()
        val snap = snapshot(isPlaying = false, positionMs = 0L)

        holder.state.test {
            assertEquals(PlaybackState(isConnected = false), awaitItem())
            holder.onSnapshot(snap)
            val connected = awaitItem()
            assertTrue(connected.isConnected)
            // Idle ticker tick with identical values: no new emission.
            holder.onSnapshot(snap)
            expectNoEvents()
        }
    }

    @Test
    fun holder_disconnect_clearsPlayingAndConnection() = runBlocking {
        val holder = PlayerStateHolder()

        holder.state.test {
            assertEquals(PlaybackState(), awaitItem())
            holder.onSnapshot(snapshot(isPlaying = true, positionMs = 1_000L))
            val playing = awaitItem()
            assertTrue(playing.isPlaying)
            holder.onDisconnected()
            val idle = awaitItem()
            assertFalse(idle.isPlaying)
            assertFalse(idle.isConnected)
        }
    }
}
