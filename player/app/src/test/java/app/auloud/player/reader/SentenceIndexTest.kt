package app.auloud.player.reader

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RA2: index lookups on plain JVM. Boundaries, gaps, before-first,
 * after-last, single-sentence and empty chapters, plus the sid tables.
 */
class SentenceIndexTest {

    private fun sentence(sid: Int, start: Long, end: Long): Sentence {
        return Sentence(sid, "narrator", start, end, "Sentence $sid. ")
    }

    /** Two blocks: sids 1-2 (0-1000, 1250-2000, gap 1000-1250), sid 3 (2800-3000). */
    private fun twoBlocks(): List<Block> {
        return listOf(
            Block(1, "para", sentences = listOf(sentence(1, 0, 1000), sentence(2, 1250, 2000))),
            Block(2, "para", sentences = listOf(sentence(3, 2800, 3000)))
        )
    }

    @Test
    fun exactStarts_selectRespectiveSentence() {
        val index = SentenceIndex(twoBlocks())
        assertEquals(1, index.currentSid(0))
        assertEquals(2, index.currentSid(1250))
        assertEquals(3, index.currentSid(2800))
    }

    @Test
    fun insideSentence_selectsIt_endIsExclusive() {
        val index = SentenceIndex(twoBlocks())
        assertEquals(1, index.currentSid(999))
        // pos == end with no next start: gap rule keeps the previous one.
        assertEquals(1, index.currentSid(1000))
    }

    @Test
    fun insideGap_keepsPreviousHighlight() {
        val index = SentenceIndex(twoBlocks())
        assertEquals(1, index.currentSid(1100))
        assertEquals(2, index.currentSid(2500))
    }

    @Test
    fun beforeFirstStart_returnsFirstSentence() {
        val index = SentenceIndex(twoBlocks())
        assertEquals(1, index.currentSid(-500))
    }

    @Test
    fun afterLastEnd_returnsLastSentence() {
        val index = SentenceIndex(twoBlocks())
        assertEquals(3, index.currentSid(3000))
        assertEquals(3, index.currentSid(999_999))
    }

    @Test
    fun singleSentence_alwaysReturnsIt() {
        val index = SentenceIndex(listOf(Block(1, "para", sentences = listOf(sentence(1, 0, 500)))))
        assertEquals(1, index.size)
        assertEquals(1, index.currentSid(-1))
        assertEquals(1, index.currentSid(0))
        assertEquals(1, index.currentSid(10_000))
    }

    @Test
    fun emptyChapter_answersNullEverywhere() {
        val index = SentenceIndex(emptyList())
        assertEquals(0, index.size)
        assertNull(index.currentSid(0))
        assertNull(index.currentSid(1000))
        assertNull(index.startMsOf(1))
        assertNull(index.locationOf(1))
    }

    @Test
    fun startMsOf_knownAndUnknownSids() {
        val index = SentenceIndex(twoBlocks())
        assertEquals(0L, index.startMsOf(1))
        assertEquals(1250L, index.startMsOf(2))
        assertEquals(2800L, index.startMsOf(3))
        assertNull(index.startMsOf(99))
    }

    @Test
    fun locationOf_mapsAcrossBlocks() {
        val index = SentenceIndex(twoBlocks())
        assertEquals(SentenceIndex.SentenceLocation(0, 0), index.locationOf(1))
        assertEquals(SentenceIndex.SentenceLocation(0, 1), index.locationOf(2))
        assertEquals(SentenceIndex.SentenceLocation(1, 0), index.locationOf(3))
        assertNull(index.locationOf(99))
    }
}
