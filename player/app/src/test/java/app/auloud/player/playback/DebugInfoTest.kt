package app.auloud.player.playback

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP9: debug plumbing on plain JVM (no Robolectric): the service-side save
 * timestamp tracker, overlay formatting/label mapping, and the
 * snapshot -> [PlaybackState] carriage of the save time (incl. the holder's
 * emit/suppress behavior for it).
 *
 * NOT covered here (verified on device instead): overlay rendering and
 * logcat output.
 */
class DebugInfoTest {

    // Save-timestamp tracker.

    @Test
    fun tracker_startsAtZero() {
        DebugSaveTracker.reset()

        assertEquals(0L, DebugSaveTracker.lastSaveWallMs)
    }

    @Test
    fun tracker_recordSave_updatesValue() {
        try {
            DebugSaveTracker.recordSave(1_700_000_000_000L)

            assertEquals(1_700_000_000_000L, DebugSaveTracker.lastSaveWallMs)
        } finally {
            DebugSaveTracker.reset()
        }
    }

    @Test
    fun tracker_recordSave_coercesNonPositiveToZero() {
        try {
            DebugSaveTracker.recordSave(-50L)

            assertEquals(0L, DebugSaveTracker.lastSaveWallMs)
        } finally {
            DebugSaveTracker.reset()
        }
    }

    @Test
    fun tracker_reset_clearsValue() {
        DebugSaveTracker.recordSave(1_700_000_000_000L)
        DebugSaveTracker.reset()

        assertEquals(0L, DebugSaveTracker.lastSaveWallMs)
    }

    // Save-time formatting.

    @Test
    fun formatSaveTime_zeroAndNegative_showNever() {
        assertEquals("never", formatDebugSaveTime(0L))
        assertEquals("never", formatDebugSaveTime(-1L))
    }

    @Test
    fun formatSaveTime_nonZero_showsHhMmSs() {
        // Timezone-independent assertion: only the shape is pinned.
        assertTrue(formatDebugSaveTime(1_700_000_000_000L).matches(Regex("\\d{2}:\\d{2}:\\d{2}")))
    }

    // Player-state label.

    @Test
    fun playerLabel_disconnected_showsConnecting() {
        assertEquals("connecting", debugPlayerLabel(isConnected = false, isPlaying = false))
        assertEquals("connecting", debugPlayerLabel(isConnected = false, isPlaying = true))
    }

    @Test
    fun playerLabel_connected_showsPlayingOrPaused() {
        assertEquals("playing", debugPlayerLabel(isConnected = true, isPlaying = true))
        assertEquals("paused", debugPlayerLabel(isConnected = true, isPlaying = false))
    }

    // Overlay position formatting.

    @Test
    fun formatMs_zero_showsZero() {
        assertEquals("0:00", formatDebugMs(0L))
    }

    @Test
    fun formatMs_minutePlusSeconds() {
        assertEquals("1:01", formatDebugMs(61_000L))
    }

    @Test
    fun formatMs_negative_clampsToZero() {
        assertEquals("0:00", formatDebugMs(-100L))
    }

    // Mapping + holder carriage of the service save time.

    @Test
    fun mapping_carriesLastSaveWallMs() {
        val state = ControllerSnapshot(
            isPlaying = true,
            chapterIndex = 0,
            chapterTitle = "Ch 1",
            bookTitle = "Book",
            positionMs = 1_000L,
            durationMs = 600_000L,
            chapterCount = 2,
            isConnected = true,
            lastSaveWallMs = 1_700_000_000_000L
        ).toPlaybackState()

        assertEquals(1_700_000_000_000L, state.lastSaveWallMs)
    }

    @Test
    fun mapping_negativeLastSave_coercesToZero() {
        val state = ControllerSnapshot(
            isPlaying = false,
            chapterIndex = 0,
            chapterTitle = null,
            bookTitle = null,
            positionMs = 0L,
            durationMs = 0L,
            chapterCount = 0,
            isConnected = false,
            lastSaveWallMs = -5L
        ).toPlaybackState()

        assertEquals(0L, state.lastSaveWallMs)
    }

    @Test
    fun holder_saveTimeChange_emitsOnce() = runBlocking {
        val holder = PlayerStateHolder()
        val base = ControllerSnapshot(
            isPlaying = true,
            chapterIndex = 0,
            chapterTitle = "Ch 1",
            bookTitle = "Book",
            positionMs = 5_000L,
            durationMs = 600_000L,
            chapterCount = 2,
            isConnected = true,
            lastSaveWallMs = 0L
        )

        holder.state.test {
            assertEquals(PlaybackState(), awaitItem())
            holder.onSnapshot(base)
            assertEquals(0L, awaitItem().lastSaveWallMs)
            // Only the save time advanced (a periodic save fired): one emission.
            holder.onSnapshot(base.copy(lastSaveWallMs = 1_700_000_000_000L))
            assertEquals(1_700_000_000_000L, awaitItem().lastSaveWallMs)
            // Identical repeat: suppressed, no recomposition on the Tab E.
            holder.onSnapshot(base.copy(lastSaveWallMs = 1_700_000_000_000L))
            expectNoEvents()
        }
    }
}
