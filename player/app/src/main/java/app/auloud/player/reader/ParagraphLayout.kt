package app.auloud.player.reader

import app.auloud.player.bundle.Block

/**
 * RA4: pure paragraph layout — one block's sentences joined into a single
 * text with precomputed character ranges (the composable applies spans to
 * these ranges, so no offset math happens during composition).
 *
 * Spacing rule (checked against `spec/fixtures/scribe-golden/`): Scribe
 * stores sentence text with its trailing space, so sentences concatenate
 * exactly as stored. A sentence without trailing whitespace (hand-made
 * bundle) gets one inserted space, unless it ends the block.
 *
 * Span offsets in the bundle are relative to their own sentence; they are
 * rebased onto the paragraph here and clamped to the sentence range.
 * Unknown styles are dropped (only `italic` and `bold` per the spec).
 *
 * API 24 safe: pure Kotlin.
 */
data class SentenceRange(val sid: Int, val start: Int, val end: Int)

data class ParagraphLayout(
    val text: String,
    val sentences: List<SentenceRange>,
    val italics: List<IntRange>,
    val bolds: List<IntRange>
)

fun layoutParagraph(block: Block): ParagraphLayout {
    val text = StringBuilder()
    val sentences = ArrayList<SentenceRange>(block.sentences.size)
    val italics = ArrayList<IntRange>()
    val bolds = ArrayList<IntRange>()
    block.sentences.forEachIndexed { index, sentence ->
        val start = text.length
        text.append(sentence.text)
        // Trimmed-source fallback: keep words apart without touching
        // Scribe output (which already carries its trailing space).
        if (index < block.sentences.size - 1 &&
            sentence.text.isNotEmpty() &&
            !sentence.text.last().isWhitespace()
        ) {
            text.append(' ')
        }
        val end = text.length
        sentences.add(SentenceRange(sentence.sid, start, end))
        sentence.spans.forEach { span ->
            val from = (start + span.start).coerceIn(start, end)
            val to = (start + span.end).coerceIn(from, end)
            if (to <= from) return@forEach
            when (span.style) {
                "italic" -> italics.add(from until to)
                "bold" -> bolds.add(from until to)
                else -> Unit
            }
        }
    }
    return ParagraphLayout(text.toString(), sentences, italics, bolds)
}
