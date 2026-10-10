package app.auloud.player.reader

import app.auloud.player.bundle.Block

/**
 * IN9: dialogue marking for the reader (fed by the IN6 kind/speaker tags).
 *
 * A sentence is dialogue when its `speaker` is the reserved 2.0 key
 * `dialogue` (spec section 3); narration (`narrator`) and every 1.x speaker
 * keep the body color. Rendered 1.x books carry no `dialogue` speakers, so
 * marking is a no-op for them by construction.
 *
 * The composable ([ReaderScreen]) joins these sids onto the precomputed
 * [ParagraphLayout] ranges and draws them in `MaterialTheme.colorScheme`
 * `tertiary`; [dialogueRanges] is the pure join so the color rule is
 * JVM-testable without Compose. Headings are not marked (titles keep the
 * headline style); only `para` and `quote` blocks are passed in.
 *
 * API 24 safe: pure Kotlin.
 */
fun isDialogueSpeaker(speaker: String): Boolean = speaker == "dialogue"

/** Sids in [block] spoken as dialogue (empty when none). */
fun dialogueSids(block: Block): Set<Int> =
    block.sentences.filter { isDialogueSpeaker(it.speaker) }.map { it.sid }.toSet()

/**
 * Character ranges in [layout] to draw in the dialogue accent color.
 * Joins [dialogueSids] of [block] onto the layout ranges by sid; ranges
 * tile the paragraph, so the result is a sublist of [layout] sentences.
 * When [markingEnabled] is false the answer is always empty (setting off).
 */
fun dialogueRanges(
    block: Block,
    layout: ParagraphLayout,
    markingEnabled: Boolean = true
): List<IntRange> {
    if (!markingEnabled) return emptyList()
    if (block.type != "para" && block.type != "quote") return emptyList()
    val wanted = dialogueSids(block)
    if (wanted.isEmpty()) return emptyList()
    return layout.sentences.filter { it.sid in wanted }.map { IntRange(it.start, it.end - 1) }
        .filter { !it.isEmpty() }
}
