package app.auloud.player.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPEN5: the gate watchdog's threshold and body. A gate that sits with
 * no error must name what it waits for, and only past the threshold.
 */
class StuckWaitTest {

    @Test
    fun belowThreshold_staysSilent() {
        assertNull(stuckWaitText(STUCK_AFTER_SECS - 1, listOf("chapter list: still waiting")))
    }

    @Test
    fun atThreshold_listsEveryWaitingPart() {
        val text = stuckWaitText(
            STUCK_AFTER_SECS,
            listOf(
                "chapter list: still waiting (no error)",
                "render panel: still loading",
                "folder: /books/x"
            )
        ) ?: error("expected a card body")

        assertTrue("was: $text", "after ${STUCK_AFTER_SECS}s" in text)
        assertTrue("was: $text", "chapter list: still waiting (no error)" in text)
        assertTrue("was: $text", "render panel: still loading" in text)
        assertTrue("was: $text", "folder: /books/x" in text)
    }

    @Test
    fun signals_distinguishNeverStartedFromNeverFinished() {
        assertEquals("the loader never started", loadSignalText(LoadSignals()))
        assertEquals(
            "the loader started 2 time(s), never finished",
            loadSignalText(LoadSignals(started = 2, finished = 0))
        )
        assertEquals(
            "the loader started 2 time(s), finished 1",
            loadSignalText(LoadSignals(started = 2, finished = 1))
        )
    }
}
