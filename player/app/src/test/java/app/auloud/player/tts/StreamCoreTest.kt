package app.auloud.player.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST2: stream core logic against direct calls (the fake driver is these
 * calls: start/done/error in any order the engine may produce).
 *
 * Fixture: 6 alternating-role sentences, pauses after sids 1 (250 ms),
 * 3 (500 ms) and 5 (100 ms). Pure JVM, no Android, no storage.
 */
class StreamCoreTest {

    private fun sentences() = listOf(
        StreamSentence(1, "One.", TtsRole.Narrator),
        StreamSentence(2, "Two?", TtsRole.Dialogue),
        StreamSentence(3, "Three.", TtsRole.Narrator),
        StreamSentence(4, "Four.", TtsRole.Dialogue),
        StreamSentence(5, "Five.", TtsRole.Narrator),
        StreamSentence(6, "Six.", TtsRole.Dialogue),
    )

    private fun pauses() = mapOf(1 to 250L, 3 to 500L, 5 to 100L)

    private fun volumes() = mapOf(TtsRole.Narrator to 1.0f, TtsRole.Dialogue to 0.8f)

    private fun core() = StreamCore(sentences(), volumes(), pauses())

    private fun speaks(plan: List<StreamUtterance>): List<SpeakUtterance> =
        plan.filterIsInstance<SpeakUtterance>()

    @Test
    fun startPlansAheadInOrderWithRolesVolumesAndPauses() {
        val plan = core().start(1)
        // 4 sentences ahead: s1 + pause, s2, s3 + pause, s4.
        assertEquals(6, plan.size)
        assertEquals(
            listOf(1, 2, 3, 4),
            speaks(plan).map { it.sid },
        )
        assertEquals("s0:1", plan[0].id)
        assertEquals("s0:1:p", plan[1].id)
        assertEquals(
            listOf(TtsRole.Narrator, TtsRole.Dialogue, TtsRole.Narrator, TtsRole.Dialogue),
            speaks(plan).map { it.role },
        )
        assertEquals(
            listOf(1.0f, 0.8f, 1.0f, 0.8f),
            speaks(plan).map { it.volume },
        )
        assertEquals(250L, (plan[1] as PauseUtterance).durationMs)
        assertEquals(500L, (plan[4] as PauseUtterance).durationMs)
    }

    @Test
    fun unknownStartSidStaysIdle() {
        val core = core()
        assertTrue(core.start(99).isEmpty())
        assertEquals(StreamCore.Status.Idle, core.status)
    }

    @Test
    fun highlightFollowsStartNeverDone() {
        val core = core()
        core.start(1)
        assertEquals(null, core.currentSid)
        core.onStart("s0:1")
        assertEquals(1, core.currentSid)
        core.onDone("s0:1")
        assertEquals(1, core.currentSid)
    }

    @Test
    fun doneRefillsOneSentenceAhead() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        val refill = core.onDone("s0:1")
        // s5 plus its pause; s6 waits for the next done.
        assertEquals(listOf(5), speaks(refill).map { it.sid })
        assertEquals("s0:5", refill[0].id)
        assertEquals(100L, (refill[1] as PauseUtterance).durationMs)
    }

    @Test
    fun duplicateDoneIgnored() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        core.onDone("s0:1")
        assertTrue(core.onDone("s0:1").isEmpty())
        // No double advance: the next refill still starts at s6.
        core.onStart("s0:2")
        val refill = core.onDone("s0:2")
        assertEquals(listOf(6), speaks(refill).map { it.sid })
    }

    @Test
    fun staleEventsAfterPauseIgnored() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        assertEquals(1, core.pause())
        assertTrue(core.onDone("s0:1").isEmpty())
        core.onStart("s0:2")
        assertEquals(1, core.currentSid)
        val resumed = core.resume()
        assertEquals(listOf(1, 2, 3, 4), speaks(resumed).map { it.sid })
        assertEquals("s1:1", resumed[0].id)
    }

    @Test
    fun missingDoneToleratedLateDoneIgnored() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        // Engine skips s1's done and starts s2: highlight follows.
        core.onStart("s0:2")
        assertEquals(2, core.currentSid)
        assertTrue(core.onDone("s0:1").isEmpty())
        assertEquals(2, core.currentSid)
        val refill = core.onDone("s0:2")
        // s1 never completed, so two slots are free: s5 plus s6.
        assertEquals(listOf(5, 6), speaks(refill).map { it.sid })
    }

    @Test
    fun errorSkipsSentenceAndResetsOnSuccess() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        assertTrue(core.onError("s0:1").isEmpty())
        assertEquals(1, core.consecutiveErrors)
        // s1 skipped: the queue continues, highlight still on s1 until
        // the next voice starts.
        core.onStart("s0:2")
        assertEquals(2, core.currentSid)
        core.onDone("s0:2")
        assertEquals(0, core.consecutiveErrors)
    }

    @Test
    fun threeConsecutiveErrorsFail() {
        val core = core()
        core.start(1)
        core.onError("s0:1")
        core.onError("s0:1:p")
        assertTrue(core.onError("s0:2").isEmpty())
        assertEquals(StreamCore.Status.Failed, core.status)
        assertTrue(core.onDone("s0:3").isEmpty())
        assertTrue(core.seek(1).isEmpty())
    }

    @Test
    fun seekRestartsAtSidAndInvalidatesOld() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        core.onDone("s0:1")
        val jumped = core.seek(4)
        assertEquals(listOf(4, 5, 6), speaks(jumped).map { it.sid })
        assertEquals("s1:4", jumped[0].id)
        assertTrue(core.onDone("s0:2").isEmpty())
        core.onStart("s1:4")
        assertEquals(4, core.currentSid)
    }

    @Test
    fun unknownSeekIgnored() {
        val core = core()
        core.start(1)
        assertTrue(core.seek(99).isEmpty())
        assertEquals(StreamCore.Status.Playing, core.status)
        core.onStart("s0:1")
        assertEquals(1, core.currentSid)
    }

    @Test
    fun requeueRestartsCurrentSentenceMidSpeech() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        val requeued = core.requeue()
        assertEquals(listOf(1, 2, 3, 4), speaks(requeued).map { it.sid })
        assertEquals("s1:1", requeued[0].id)
        assertTrue(core.onDone("s0:1").isEmpty())
    }

    @Test
    fun pauseMidSentenceResumesSameSentence() {
        val core = core()
        core.start(1)
        core.onStart("s0:2")
        assertEquals(2, core.pause())
        val resumed = core.resume()
        assertEquals(2, speaks(resumed)[0].sid)
    }

    @Test
    fun pauseBetweenSentencesResumesNext() {
        val core = core()
        core.start(1)
        core.onStart("s0:1")
        core.onDone("s0:1")
        assertEquals(2, core.pause())
        val resumed = core.resume()
        assertEquals(listOf(2, 3, 4, 5), speaks(resumed).map { it.sid })
    }

    @Test
    fun noPauseAfterLastSentence() {
        val core = core()
        val plan = core.start(5)
        assertEquals(listOf(5, 6), speaks(plan).map { it.sid })
        assertEquals(3, plan.size)
        assertEquals("s0:5:p", plan[1].id)
    }

    @Test
    fun fullChapterEndsDone() {
        val core = core()
        val worklist = core.start(1).toMutableList()
        val sids = mutableListOf<Int>()
        while (worklist.isNotEmpty() && core.status == StreamCore.Status.Playing) {
            val next = worklist.removeAt(0)
            if (next is SpeakUtterance) {
                core.onStart(next.id)
                sids += next.sid
            }
            worklist += core.onDone(next.id)
        }
        assertTrue(worklist.isEmpty())
        assertEquals(StreamCore.Status.Done, core.status)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), sids)
        assertEquals(6, core.currentSid)
    }

    @Test
    fun defaultVolumesAreFull() {
        val plan = StreamCore(sentences()).start(1)
        assertTrue(speaks(plan).all { it.volume == 1.0f })
    }

    @Test
    fun requeueAndSeekNeedActiveStream() {
        val core = core()
        assertTrue(core.requeue().isEmpty())
        assertTrue(core.seek(1).isEmpty())
        assertTrue(core.resume().isEmpty())
    }
}
