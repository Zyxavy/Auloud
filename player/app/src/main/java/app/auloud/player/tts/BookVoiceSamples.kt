package app.auloud.player.tts

import app.auloud.player.bundle.ChapterText
import app.auloud.player.ingest.SPEAKER_DIALOGUE
import app.auloud.player.ingest.SPEAKER_NARRATOR

/**
 * VS4: real-line audition samples taken from the book itself.
 *
 * The narration sample is the first narration sentence in reading order,
 * the dialogue sample the first dialogue sentence. Both are plain book
 * text (never the fixed audition sentence): the listener hears what the
 * voices will actually render.
 *
 * Cheapest source: the chapter JSON the caller already loads, one at a
 * time. The view-model scans chapters sequentially and keeps the first
 * hit per role, so at most one chapter JSON is held (the memory rule).
 * This file never touches storage; callers supply parsed chapters.
 *
 * Scribe (PC) books carry per-character speakers instead of the two
 * reserved roles: with no reserved sentences the narration sample falls
 * back to the first sentence of the book and the dialogue sample stays
 * null (nothing honest to audition as dialogue).
 *
 * Pure Kotlin, API 24 safe, no new dependency.
 */
data class BookVoiceSamples(
    val narration: String?,
    val dialogue: String?
)

object BookVoiceSamplePicker {

    /** First non-blank narration sentence in [chapter], or null. */
    fun firstNarrationIn(chapter: ChapterText): String? {
        for (sentence in chapter.sentencesInOrder()) {
            if (sentence.speaker == SPEAKER_NARRATOR && sentence.text.isNotBlank()) {
                return sentence.text
            }
        }
        return null
    }

    /** First non-blank dialogue sentence in [chapter], or null. */
    fun firstDialogueIn(chapter: ChapterText): String? {
        for (sentence in chapter.sentencesInOrder()) {
            if (sentence.speaker == SPEAKER_DIALOGUE && sentence.text.isNotBlank()) {
                return sentence.text
            }
        }
        return null
    }

    /** First non-blank sentence of any speaker in [chapter], or null. */
    fun firstSentenceIn(chapter: ChapterText): String? {
        for (sentence in chapter.sentencesInOrder()) {
            if (sentence.text.isNotBlank()) return sentence.text
        }
        return null
    }

    /**
     * Merges one chapter into the running [current] samples: fills each
     * null slot with the chapter first hit, keeps existing hits.
     */
    fun accumulate(current: BookVoiceSamples, chapter: ChapterText): BookVoiceSamples {
        val narration = current.narration ?: firstNarrationIn(chapter)
        val dialogue = current.dialogue ?: firstDialogueIn(chapter)
        return BookVoiceSamples(narration = narration, dialogue = dialogue)
    }

    /**
     * Selects samples over [chapters] in order (pure helper for tests;
     * the view-model uses [accumulate] one chapter at a time).
     */
    fun select(chapters: List<ChapterText>): BookVoiceSamples {
        var current = BookVoiceSamples(narration = null, dialogue = null)
        for (chapter in chapters) {
            current = accumulate(current, chapter)
            if (current.narration != null && current.dialogue != null) break
        }
        return current
    }

    /**
     * Applies the Scribe fallback: when no reserved narration sentence
     * was found, the first sentence of any speaker reads as narration.
     */
    fun withScribeFallback(samples: BookVoiceSamples, chapters: List<ChapterText>): BookVoiceSamples {
        if (samples.narration != null) return samples
        for (chapter in chapters) {
            val first = firstSentenceIn(chapter)
            if (first != null) return samples.copy(narration = first)
        }
        return samples
    }
}
