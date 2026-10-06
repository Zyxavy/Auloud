package app.auloud.player.render

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence
import app.auloud.player.data.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * RN7: [RenderProgress] sentence-sid to millisecond conversion (D-099).
 *
 * Sid saves convert once, on the first render of their chapter, using the
 * new timings; millisecond saves pass through untouched, and sid saves
 * for still-unrendered chapters keep working.
 */
class RenderProgressTest {

    private fun timedChapter(): ChapterText = ChapterText(
        specVersion = "2.0",
        chapter = 1,
        title = "Ch 1",
        durationMs = 2000L,
        blocks = listOf(
            Block(
                id = 1,
                type = "para",
                sentences = listOf(
                    Sentence(sid = 1, speaker = "narrator", startMs = 0L, endMs = 500L, text = "Hello. "),
                    Sentence(sid = 2, speaker = "dialogue", startMs = 750L, endMs = 1250L, text = "Hi. ")
                )
            )
        )
    )

    private fun untimedChapter(): ChapterText = ChapterText(
        specVersion = "2.0",
        chapter = 2,
        title = "Ch 2",
        blocks = listOf(
            Block(
                id = 1,
                type = "para",
                sentences = listOf(
                    Sentence(sid = 1, speaker = "narrator", text = "Hello. ")
                )
            )
        )
    )

    @Test
    fun msForSid_returnsSentenceStart() {
        assertEquals(750L, RenderProgress.msForSid(timedChapter(), 2))
        assertEquals(0L, RenderProgress.msForSid(timedChapter(), 1))
    }

    @Test
    fun msForSid_missingSidOrUntimed_isNull() {
        assertNull(RenderProgress.msForSid(timedChapter(), 9))
        assertNull(RenderProgress.msForSid(untimedChapter(), 1))
    }

    @Test
    fun convert_sidSave_convertsToMsAndClearsSid() {
        val saved = ProgressEntity("b1", 0, 0L, 10L, sentenceSid = 2)

        val converted = RenderProgress.convertOnRender(saved, 0, timedChapter())

        assertEquals(ProgressEntity("b1", 0, 750L, 10L, sentenceSid = null), converted)
    }

    @Test
    fun convert_msSave_untouched() {
        val saved = ProgressEntity("b1", 0, 900L, 10L, sentenceSid = null)

        assertEquals(saved, RenderProgress.convertOnRender(saved, 0, timedChapter()))
    }

    @Test
    fun convert_otherChapter_untouched() {
        val saved = ProgressEntity("b1", 1, 0L, 10L, sentenceSid = 2)

        assertEquals(saved, RenderProgress.convertOnRender(saved, 0, timedChapter()))
    }

    @Test
    fun convert_sidSaveOnUntimedChapter_untouched() {
        val saved = ProgressEntity("b1", 1, 0L, 10L, sentenceSid = 1)

        assertEquals(saved, RenderProgress.convertOnRender(saved, 1, untimedChapter()))
    }

    @Test
    fun convert_missingSid_fallsBackToChapterStart() {
        val saved = ProgressEntity("b1", 0, 0L, 10L, sentenceSid = 9)

        val converted = RenderProgress.convertOnRender(saved, 0, timedChapter())

        assertEquals(ProgressEntity("b1", 0, 0L, 10L, sentenceSid = null), converted)
    }

    @Test
    fun convert_nullSaved_isNull() {
        assertNull(RenderProgress.convertOnRender(null, 0, timedChapter()))
    }
}
