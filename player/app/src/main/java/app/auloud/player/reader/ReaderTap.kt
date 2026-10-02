package app.auloud.player.reader

/**
 * RA6: tap offset to sentence (pure; the hit test lives in [ReaderScreen]).
 *
 * The text layout turns a tap position into a character offset; this maps
 * the offset onto the precomputed [SentenceRange]s. Ranges tile the
 * paragraph contiguously, so every offset resolves: a tap on padding past
 * the last character lands on the last sentence.
 *
 * API 24 safe: pure Kotlin.
 */
fun sidAtOffset(sentences: List<SentenceRange>, offset: Int): Int? {
    if (sentences.isEmpty()) return null
    if (offset < 0) return sentences.first().sid
    for (range in sentences) {
        if (offset < range.end) return range.sid
    }
    return sentences.last().sid
}
