package app.auloud.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ST4: chapter routing plus stream save validation on plain JVM.
 *
 * Expected values are literals from the fixture, not from the code.
 */
class StreamRouteTest {

    // Chapters 0 and 2 rendered, chapter 1 unrendered.
    private val rendered = listOf(true, false, true)

    @Test
    fun gateOpenByOwnerVerdict() {
        // ST0 measured NO-GO (D-137); owner ordered GO anyway (D-138:
        // half-second gaps plus 10 s start accepted as "listen now").
        // Flipping back is its own deliberate diff.
        assertEquals(true, StreamRoute.GATE_PASSED)
    }

    @Test
    fun targetMatrix() {
        assertEquals(StreamRoute.Target.Rendered, StreamRoute.targetFor(true, true))
        assertEquals(StreamRoute.Target.Streamed, StreamRoute.targetFor(false, true))
        assertEquals(StreamRoute.Target.Unavailable, StreamRoute.targetFor(false, false))
    }

    @Test
    fun advanceWithStreaming_stopsAtEveryChapter() {
        assertEquals(
            StreamRoute.Advance(1, StreamRoute.Target.Streamed),
            StreamRoute.advance(0, rendered, true, 1),
        )
        assertEquals(
            StreamRoute.Advance(2, StreamRoute.Target.Rendered),
            StreamRoute.advance(1, rendered, true, 1),
        )
        assertEquals(
            StreamRoute.Advance(0, StreamRoute.Target.Rendered),
            StreamRoute.advance(1, rendered, true, -1),
        )
    }

    @Test
    fun advanceWithoutStreaming_skipsUnrendered() {
        assertEquals(
            StreamRoute.Advance(2, StreamRoute.Target.Rendered),
            StreamRoute.advance(0, rendered, false, 1),
        )
        assertEquals(
            StreamRoute.Advance(0, StreamRoute.Target.Rendered),
            StreamRoute.advance(2, rendered, false, -1),
        )
    }

    @Test
    fun advancePastEndsIsNull() {
        assertNull(StreamRoute.advance(2, rendered, true, 1))
        assertNull(StreamRoute.advance(0, rendered, true, -1))
        assertNull(StreamRoute.advance(2, rendered, false, 1))
        assertNull(StreamRoute.advance(0, listOf(false), false, 1))
    }

    @Test
    fun streamSaveNeedsBookChapterAndPositiveSid() {
        assertEquals(
            StreamRoute.StreamSave("book", 1, 7),
            StreamRoute.streamSaveOrNull("book", 1, 7),
        )
        assertNull(StreamRoute.streamSaveOrNull(null, 1, 7))
        assertNull(StreamRoute.streamSaveOrNull("  ", 1, 7))
        assertNull(StreamRoute.streamSaveOrNull("book", -1, 7))
        assertNull(StreamRoute.streamSaveOrNull("book", 1, null))
        assertNull(StreamRoute.streamSaveOrNull("book", 1, 0))
    }

    @Test
    fun systemVoiceNameStripsNamespace() {
        assertEquals("en-us-x-sfg#female", StreamRoute.systemVoiceNameOrNull("system:en-us-x-sfg#female"))
        assertNull(StreamRoute.systemVoiceNameOrNull("piper:en_US-lessac-low"))
        assertNull(StreamRoute.systemVoiceNameOrNull("system:"))
        assertNull(StreamRoute.systemVoiceNameOrNull("nosuchprefix"))
    }

    @Test
    fun streamVoiceNameOrDefault_blankFallsBackNonSystemRefuses() {
        assertEquals("en-default", StreamRoute.streamVoiceNameOrDefault("", "en-default"))
        assertEquals("en-default", StreamRoute.streamVoiceNameOrDefault("  ", "en-default"))
        assertNull(StreamRoute.streamVoiceNameOrDefault("", null))
        assertNull(StreamRoute.streamVoiceNameOrDefault("", ""))
        assertEquals("en-picked", StreamRoute.streamVoiceNameOrDefault("system:en-picked", "en-default"))
        assertNull(StreamRoute.streamVoiceNameOrDefault("piper:en-low", "en-default"))
    }
}
