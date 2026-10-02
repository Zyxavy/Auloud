package app.auloud.player.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RA6: offset-to-sentence mapping on plain JVM, incl. range boundaries.
 */
class ReaderTapTest {

    private val ranges = listOf(
        SentenceRange(1, 0, 7),
        SentenceRange(2, 7, 15),
        SentenceRange(3, 15, 22)
    )

    @Test
    fun offsetInsideSentence_selectsIt() {
        assertEquals(1, sidAtOffset(ranges, 0))
        assertEquals(1, sidAtOffset(ranges, 6))
        assertEquals(2, sidAtOffset(ranges, 10))
    }

    @Test
    fun boundaryOffset_selectsNextSentence() {
        // Ranges are end-exclusive: offset 7 is inside sentence 2.
        assertEquals(2, sidAtOffset(ranges, 7))
        assertEquals(3, sidAtOffset(ranges, 15))
    }

    @Test
    fun pastTheEnd_landsOnLastSentence() {
        assertEquals(3, sidAtOffset(ranges, 21))
        assertEquals(3, sidAtOffset(ranges, 22))
        assertEquals(3, sidAtOffset(ranges, 999))
    }

    @Test
    fun negativeOffset_landsOnFirstSentence() {
        assertEquals(1, sidAtOffset(ranges, -1))
    }

    @Test
    fun emptyRanges_yieldNull() {
        assertNull(sidAtOffset(emptyList(), 0))
    }
}
