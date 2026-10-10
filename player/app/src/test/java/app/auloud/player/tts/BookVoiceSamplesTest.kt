package app.auloud.player.tts

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VS4: real-line sample selection from chapter text (first narration
 * sentence, first dialogue sentence, cheapest source one chapter at a
 * time).
 *
 * Pure JVM, no Android, no storage.
 */
class BookVoiceSamplesTest {

    private fun chapter(vararg sentences: Pair<String, String>): ChapterText {
        val list = sentences.mapIndexed { pos, (speaker, text) ->
            Sentence(sid = pos + 1, speaker = speaker, text = text)
        }
        return ChapterText(
            specVersion = "2.0",
            chapter = 1,
            title = "Ch 1",
            blocks = listOf(Block(id = 1, type = "para", sentences = list))
        )
    }

    @Test
    fun firstLines_narrationAndDialogue() {
        val first = chapter(
            "narrator" to "The night was cold. ",
            "dialogue" to "Are you coming? ",
            "narrator" to "He did not answer. "
        )
        assertEquals("The night was cold. ", BookVoiceSamplePicker.firstNarrationIn(first))
        assertEquals("Are you coming? ", BookVoiceSamplePicker.firstDialogueIn(first))
    }

    @Test
    fun blankSentences_skipped() {
        val first = chapter(
            "narrator" to "   ",
            "narrator" to "Real line. ",
            "dialogue" to ""
        )
        assertEquals("Real line. ", BookVoiceSamplePicker.firstNarrationIn(first))
        assertNull(BookVoiceSamplePicker.firstDialogueIn(first))
    }

    @Test
    fun noDialogue_sampleIsNull() {
        val first = chapter("narrator" to "Only narration here. ")
        val samples = BookVoiceSamplePicker.select(listOf(first))
        assertEquals("Only narration here. ", samples.narration)
        assertNull(samples.dialogue)
    }

    @Test
    fun accumulate_oneChapterAtATime() {
        val first = chapter("narrator" to "Chapter one opens. ")
        val second = chapter(
            "narrator" to "Chapter two opens. ",
            "dialogue" to "Second chapter speaks. "
        )
        var samples = BookVoiceSamples(narration = null, dialogue = null)
        samples = BookVoiceSamplePicker.accumulate(samples, first)
        assertEquals("Chapter one opens. ", samples.narration)
        assertNull(samples.dialogue)
        samples = BookVoiceSamplePicker.accumulate(samples, second)
        assertEquals("Chapter one opens. ", samples.narration)
        assertEquals("Second chapter speaks. ", samples.dialogue)
    }

    @Test
    fun scribeFallback_firstSentenceReadsAsNarration() {
        val scribe = chapter(
            "Raskolnikov" to "He thought for a while. ",
            "Sonia" to "Speak, she said. "
        )
        val samples = BookVoiceSamplePicker.select(listOf(scribe))
        assertNull(samples.narration)
        assertNull(samples.dialogue)
        val withFallback = BookVoiceSamplePicker.withScribeFallback(samples, listOf(scribe))
        assertEquals("He thought for a while. ", withFallback.narration)
        assertNull(withFallback.dialogue)
    }

    @Test
    fun scribeFallback_keepsRealSamples() {
        val first = chapter("narrator" to "Real narration. ")
        val samples = BookVoiceSamplePicker.select(listOf(first))
        val withFallback = BookVoiceSamplePicker.withScribeFallback(samples, listOf(first))
        assertEquals("Real narration. ", withFallback.narration)
    }

    @Test
    fun emptyBook_samplesAreNull() {
        val samples = BookVoiceSamplePicker.select(emptyList())
        assertNull(samples.narration)
        assertNull(samples.dialogue)
    }
}
