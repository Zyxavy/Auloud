package app.auloud.player.reader

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.Sentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN9: dialogue marking pure rules (the Compose layer only applies the
 * color; these tests pin which sentences qualify).
 */
class DialogueMarkingTest {

    private fun sentence(sid: Int, speaker: String, text: String = "T. "): Sentence =
        Sentence(sid = sid, speaker = speaker, text = text)

    @Test
    fun speaker_dialogueOnly() {
        assertTrue(isDialogueSpeaker("dialogue"))
        assertFalse(isDialogueSpeaker("narrator"))
        assertFalse(isDialogueSpeaker("Ana"))
        assertFalse(isDialogueSpeaker(""))
    }

    @Test
    fun sids_mixedBlock_returnsDialogueOnly() {
        val block = Block(
            id = 1,
            type = "para",
            sentences = listOf(
                sentence(1, "narrator"),
                sentence(2, "dialogue"),
                sentence(3, "narrator"),
                sentence(4, "dialogue")
            )
        )

        assertEquals(setOf(2, 4), dialogueSids(block))
    }

    @Test
    fun sids_allNarration_isEmpty() {
        val block = Block(
            id = 1,
            type = "para",
            sentences = listOf(sentence(1, "narrator"), sentence(2, "narrator"))
        )

        assertTrue(dialogueSids(block).isEmpty())
    }

    @Test
    fun sids_legacyCharacterSpeaker_isNotDialogue() {
        val block = Block(
            id = 1,
            type = "para",
            sentences = listOf(sentence(1, "narrator"), sentence(2, "Ana"))
        )

        assertTrue(dialogueSids(block).isEmpty())
    }

    @Test
    fun ranges_mixedParagraph_coversDialogueSentenceOnly() {
        val block = Block(
            id = 2,
            type = "para",
            sentences = listOf(
                Sentence(sid = 1, speaker = "narrator", text = "One. "),
                Sentence(sid = 2, speaker = "dialogue", text = "\"Two.\" "),
                Sentence(sid = 3, speaker = "narrator", text = "Three.")
            )
        )
        val layout = layoutParagraph(block)
        val dialogue = layout.sentences.first { it.sid == 2 }

        val ranges = dialogueRanges(block, layout)

        assertEquals(1, ranges.size)
        assertEquals(dialogue.start, ranges[0].first)
        assertEquals(dialogue.end - 1, ranges[0].last)
    }

    @Test
    fun ranges_settingOff_isEmpty() {
        val block = Block(
            id = 2,
            type = "para",
            sentences = listOf(
                Sentence(sid = 1, speaker = "narrator", text = "One. "),
                Sentence(sid = 2, speaker = "dialogue", text = "\"Two.\" ")
            )
        )

        assertTrue(dialogueRanges(block, layoutParagraph(block), markingEnabled = false).isEmpty())
    }

    @Test
    fun ranges_headingNeverMarked() {
        val block = Block(
            id = 1,
            type = "heading",
            level = 1,
            sentences = listOf(Sentence(sid = 1, speaker = "dialogue", text = "Shout"))
        )

        assertTrue(dialogueRanges(block, layoutParagraph(block)).isEmpty())
    }

    @Test
    fun ranges_narrationOnly_isEmpty() {
        val block = Block(
            id = 2,
            type = "quote",
            sentences = listOf(
                Sentence(sid = 1, speaker = "narrator", text = "One. "),
                Sentence(sid = 2, speaker = "narrator", text = "Two. ")
            )
        )

        assertTrue(dialogueRanges(block, layoutParagraph(block)).isEmpty())
    }
}
