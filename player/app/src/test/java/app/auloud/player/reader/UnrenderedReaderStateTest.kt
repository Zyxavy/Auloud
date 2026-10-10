package app.auloud.player.reader

import app.auloud.player.bundle.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IN9: sid restore fallback (re-imported or changed books whose saved sid
 * is gone still open at the chapter start).
 */
class UnrenderedReaderStateTest {

    private fun sentence(sid: Int): Sentence =
        Sentence(sid = sid, speaker = "narrator", text = "T. ")

    @Test
    fun restore_savedPresent_returnsSaved() {
        val sentences = listOf(sentence(1), sentence(2), sentence(3))

        assertEquals(2, restoreReadingSid(2, sentences))
    }

    @Test
    fun restore_savedMissing_returnsFirst() {
        val sentences = listOf(sentence(1), sentence(2), sentence(3))

        assertEquals(1, restoreReadingSid(99, sentences))
    }

    @Test
    fun restore_savedNull_returnsFirst() {
        val sentences = listOf(sentence(1), sentence(2))

        assertEquals(1, restoreReadingSid(null, sentences))
    }

    @Test
    fun restore_emptyChapter_returnsNull() {
        assertNull(restoreReadingSid(1, emptyList()))
        assertNull(restoreReadingSid(null, emptyList()))
    }
}
