package app.auloud.player.playback

import androidx.media3.common.PlaybackException
import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CP4: runtime-resilience contracts on plain JVM (no Robolectric, no
 * Media3 player): the [SkipNoticeMonitor] consume-once transport, the
 * holder merge/consume assembly, and the [PlaybackErrorPolicy]
 * skip-vs-pause decision.
 */
class SkipNoticeTest {

    @Before
    fun setUp() {
        SkipNoticeMonitor.clear()
    }

    @After
    fun tearDown() {
        SkipNoticeMonitor.clear()
    }

    private fun snapshot(
        skipNotice: String? = null,
        isPlaying: Boolean = false,
        positionMs: Long = 0L
    ) = ControllerSnapshot(
        isPlaying = isPlaying,
        chapterIndex = 0,
        chapterTitle = "Ch 1",
        bookTitle = "Example Book",
        positionMs = positionMs,
        durationMs = 600_000L,
        chapterCount = 2,
        isConnected = true,
        skipNotice = skipNotice
    )

    // Monitor transport.

    @Test
    fun monitor_publishConsumeOnce() {
        SkipNoticeMonitor.publish("hello")
        assertTrue(SkipNoticeMonitor.hasPending)
        assertEquals("hello", SkipNoticeMonitor.consume())
        assertFalse(SkipNoticeMonitor.hasPending)
        assertNull(SkipNoticeMonitor.consume())
    }

    @Test
    fun monitor_notifySkippedNamesChapter() {
        SkipNoticeMonitor.notifySkipped("The Chase")
        val message = SkipNoticeMonitor.consume()
        assertTrue("must name chapter, got: $message", message?.contains("The Chase") == true)
        assertTrue("must say skipped, got: $message", message?.contains("Skipped") == true)
    }

    @Test
    fun monitor_notifyStorageUnavailableMessage() {
        SkipNoticeMonitor.notifyStorageUnavailable()
        assertEquals("Storage unavailable - playback paused", SkipNoticeMonitor.consume())
    }

    @Test
    fun monitor_clearDropsPending() {
        SkipNoticeMonitor.publish("stale")
        SkipNoticeMonitor.clear()
        assertFalse(SkipNoticeMonitor.hasPending)
        assertNull(SkipNoticeMonitor.consume())
    }

    // Error policy.

    @Test
    fun policy_firstMissingFile_skips() {
        assertEquals(
            PlayerErrorDecision.Skip,
            PlaybackErrorPolicy.decide(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, 0)
        )
    }

    @Test
    fun policy_secondConsecutiveMissingFile_storageLoss() {
        assertEquals(
            PlayerErrorDecision.StorageLoss,
            PlaybackErrorPolicy.decide(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, 1)
        )
    }

    @Test
    fun policy_noPermissionBehavesLikeMissing() {
        assertEquals(
            PlayerErrorDecision.Skip,
            PlaybackErrorPolicy.decide(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, 0)
        )
        assertEquals(
            PlayerErrorDecision.StorageLoss,
            PlaybackErrorPolicy.decide(PlaybackException.ERROR_CODE_IO_NO_PERMISSION, 1)
        )
    }

    @Test
    fun policy_unknownCode_needsThreeInARow() {
        val unknown = 999_999
        assertFalse(PlaybackErrorPolicy.isMissingFile(unknown))
        assertEquals(PlayerErrorDecision.Skip, PlaybackErrorPolicy.decide(unknown, 0))
        assertEquals(PlayerErrorDecision.Skip, PlaybackErrorPolicy.decide(unknown, 1))
        assertEquals(PlayerErrorDecision.StorageLoss, PlaybackErrorPolicy.decide(unknown, 2))
    }

    @Test
    fun policy_isMissingFile_pinsCodes() {
        assertTrue(PlaybackErrorPolicy.isMissingFile(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertTrue(PlaybackErrorPolicy.isMissingFile(PlaybackException.ERROR_CODE_IO_NO_PERMISSION))
    }

    // Holder assembly.

    @Test
    fun holder_retainsNoticeAcrossNullSnapshots() = runBlocking {
        val holder = PlayerStateHolder()
        holder.state.test {
            assertEquals(PlaybackState(), awaitItem())
            holder.onSnapshot(snapshot(skipNotice = "Skipped Ch 1 - file unreadable"))
            assertEquals("Skipped Ch 1 - file unreadable", awaitItem().skipNotice)
            // Idle tick with no fresh notice: displayed notice survives.
            holder.onSnapshot(snapshot(skipNotice = null, positionMs = 1_000L))
            val retained = awaitItem()
            assertEquals("Skipped Ch 1 - file unreadable", retained.skipNotice)
            assertEquals(1_000L, retained.positionMs)
        }
    }

    @Test
    fun holder_freshNoticeReplacesDisplayed() = runBlocking {
        val holder = PlayerStateHolder()
        holder.state.test {
            assertEquals(PlaybackState(), awaitItem())
            holder.onSnapshot(snapshot(skipNotice = "first"))
            assertEquals("first", awaitItem().skipNotice)
            holder.onSnapshot(snapshot(skipNotice = "second"))
            assertEquals("second", awaitItem().skipNotice)
        }
    }

    @Test
    fun holder_sameFreshNoticeTwice_emitsOnce() = runBlocking {
        val holder = PlayerStateHolder()
        holder.state.test {
            assertEquals(PlaybackState(), awaitItem())
            holder.onSnapshot(snapshot(skipNotice = "same"))
            assertEquals("same", awaitItem().skipNotice)
            holder.onSnapshot(snapshot(skipNotice = "same"))
            expectNoEvents()
        }
    }

    @Test
    fun holder_clearSkipNotice_dismisses() = runBlocking {
        val holder = PlayerStateHolder()
        holder.state.test {
            assertEquals(PlaybackState(), awaitItem())
            holder.onSnapshot(snapshot(skipNotice = "shown"))
            assertEquals("shown", awaitItem().skipNotice)
            holder.clearSkipNotice()
            assertNull(awaitItem().skipNotice)
            // A later idle tick must not resurrect it.
            holder.onSnapshot(snapshot(skipNotice = null))
            expectNoEvents()
        }
    }

    @Test
    fun mapping_carriesSkipNotice() {
        val state = snapshot(skipNotice = "Storage unavailable - playback paused").toPlaybackState()
        assertEquals("Storage unavailable - playback paused", state.skipNotice)
    }

    @Test
    fun mapping_nullNoticeStaysNull() {
        assertNull(snapshot(skipNotice = null).toPlaybackState().skipNotice)
    }
}
