package app.auloud.player.ingest

import java.text.BreakIterator
import java.util.Locale

/**
 * IN5: sentence splitting for on-device EPUB ingestion (Slice 9).
 *
 * Ports `docs/ingestion-rules.md` section 8 (sentence splitting) with
 * Scribe's `scribe/text/sentences.py` (`split_paragraph`, `split_chapter`)
 * as the behavior reference, per decision D-080: the platform sentence
 * iterator (`java.text.BreakIterator.getSentenceInstance` with an English
 * locale) proposes candidates, then Scribe's post-fixes apply unchanged
 * (abbreviation/initial merges with quote/bracket anchors, ellipsis rules
 * including the ellipsis-plus-quote lowercase continuation, `No.`/`etc.`
 * exclusions, quote attachment up to 3 sentences with splitting above
 * that, unbalanced-quote tolerance, headings as one sentence, exact round
 * trip, span rebasing, sids from 1 across the chapter), plus one
 * BreakIterator compensation with no Scribe counterpart: single-quoted
 * chunks are glued back together (pysbd never splits inside them, the
 * platform iterator does; apostrophes guarded per rule 9.2).
 *
 * Pipeline position: IN4 ([EpubStructurePipeline]) outputs [IngestChapter]
 * blocks with text plus spans; this object splits each block into
 * [IngestSentence] entries. IN6 (dialogue) consumes the sentences and tags
 * each sid with narration/dialogue; IN7 writes the chapter files.
 *
 * Sentence shape for IN6: [IngestSentence] carries text plus spans only,
 * no kind/speaker fields. Dialogue tagging lives in IN6's own types keyed
 * by sid, so this file never grows a kind field.
 *
 * API 24 safe: `java.text.BreakIterator` (API 1), regex, plain loops. No
 * `java.time`, no `java.nio`, no Android classes, so this is JVM-testable.
 * A fresh BreakIterator is built per call (it is not thread-safe).
 */
object SentenceSplitter {

    /** A quoted region holds more than this many sentences before splitting inside. */
    internal const val MAX_QUOTE_SENTENCES = 3

    /** Title abbreviations that never end a sentence (rule 8.1, Scribe `_ABBREV_RE`). */
    internal val ABBREV_RE = Regex(
        "(?:^|[\\s\"'(\\[{‘“])(?:Mr|Mrs|Ms|Dr|St|Sr|Jr|Prof|Rev|[Vv]s|e\\.g\\.|i\\.e\\.)\\.$",
        RegexOption.IGNORE_CASE
    )

    /** Single-capital initials that never end a sentence (rule 8.2, Scribe `_INITIAL_RE`). */
    internal val INITIAL_RE = Regex("(?:^|[\\s\"'(\\[{‘“])[A-Z]\\.$")

    /** Closing quote characters stripped before the ellipsis check (Scribe `_QUOTE_TAIL`). */
    internal fun isQuoteTail(ch: Char): Boolean =
        ch == '"' || ch == '\'' || ch == '“' || ch == '”' || ch == '‘' || ch == '’'

    /** True when a candidate piece ends mid-sentence and must glue to the next piece. */
    internal fun needsMerge(piece: String): Boolean {
        if (piece.endsWith("...") || piece.endsWith("…")) return true
        return ABBREV_RE.containsMatchIn(piece) || INITIAL_RE.containsMatchIn(piece)
    }

    /**
     * True when [prev] ends with ellipsis plus closing quote(s) and the next
     * piece visibly continues the same breath (lowercase start, for example
     * a dialogue tag in `"Wait..." she whispered`).
     *
     * A closed quote followed by a capitalised sentence (`He said "Wait..."
     * Then he left.`) stays split: the quoted unit ended.
     */
    internal fun continuesEllipsisQuote(prev: String, nextPiece: String): Boolean {
        var coreEnd = prev.length
        while (coreEnd > 0 && isQuoteTail(prev[coreEnd - 1])) coreEnd--
        if (coreEnd == prev.length) return false
        val core = prev.substring(0, coreEnd)
        if (!core.endsWith("...") && !core.endsWith("…")) return false
        val stripped = nextPiece.trim { ch -> StructureCleaner.isEpubSpace(ch) }
        if (stripped.isEmpty()) return false
        val first = stripped[0]
        return first in 'a'..'z'
    }

    /** Platform sentence candidates as (start, end) offsets tiling [0, text.length). */
    internal fun baselineBounds(text: String): List<Pair<Int, Int>> {
        val iterator = BreakIterator.getSentenceInstance(Locale.ENGLISH)
        iterator.setText(text)
        val bounds = ArrayList<Pair<Int, Int>>()
        var start = iterator.first()
        while (true) {
            val end = iterator.next()
            if (end == BreakIterator.DONE) break
            bounds.add(Pair(start, end))
            start = end
        }
        return bounds
    }

    /** True when every character in [text] is EPUB whitespace (blank gap). */
    internal fun isBlankGap(text: String): Boolean =
        text.all { ch -> StructureCleaner.isEpubSpace(ch) }

    internal fun trimGap(text: String): String =
        text.trim { ch -> StructureCleaner.isEpubSpace(ch) }

    /**
     * Maps baseline segments back to (start, end) content spans in [text].
     *
     * Ordered substring search mirroring Scribe's `_locate_contents`: a
     * segment that cannot be found in order, or a gap between segments
     * holding non-whitespace, returns null and the caller keeps the
     * paragraph whole, so text is never lost or reordered.
     */
    internal fun locateContents(text: String, raws: List<String>): List<Pair<Int, Int>>? {
        val spans = ArrayList<Pair<Int, Int>>()
        var cursor = 0
        for (raw in raws) {
            val content = trimGap(raw)
            if (content.isEmpty()) continue
            val pos = text.indexOf(content, cursor)
            if (pos < 0 || !isBlankGap(text.substring(cursor, pos))) return null
            spans.add(Pair(pos, pos + content.length))
            cursor = pos + content.length
        }
        if (spans.isEmpty()) return null
        if (!isBlankGap(text.substring(cursor))) return null
        return spans
    }

    /** Drops boundaries after abbreviation/initial/ellipsis pieces (chains re-check). */
    internal fun mergeContents(text: String, spans: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val merged = ArrayList<Pair<Int, Int>>()
        merged.add(spans[0])
        for (pos in 1 until spans.size) {
            val (start, end) = spans[pos]
            val prevText = trimGap(text.substring(merged.last().first, merged.last().second))
            val nextText = trimGap(text.substring(start, end))
            if (needsMerge(prevText) || continuesEllipsisQuote(prevText, nextText)) {
                val last = merged.removeAt(merged.size - 1)
                merged.add(Pair(last.first, end))
            } else {
                merged.add(Pair(start, end))
            }
        }
        return merged
    }

    /** Baseline split plus post-fix merges as content spans; null when unalignable. */
    internal fun plainSpans(text: String): List<Pair<Int, Int>>? {
        val bounds = baselineBounds(text)
        val raws = bounds.map { (start, end) -> text.substring(start, end) }
        val located = locateContents(text, raws) ?: return null
        return mergeContents(text, located)
    }

    /**
     * Balanced double-quote regions as (open, close-exclusive) offsets.
     *
     * Straight quotes pair sequentially; curly quotes pair each opener with
     * the next closer with depth counting, so same-type nesting collapses
     * to the outer region. Double-quote regions follow the attach-or-split
     * rule; single quotes never form regions here (apostrophe ambiguity;
     * [singleRegions] handles their BreakIterator glue separately and
     * dialogue splitting proper is IN6). The flag is false when
     * quotes are left over (odd straights, unmatched curlies): intact
     * regions are still returned and honored, only the strays are ignored.
     */
    internal fun quoteRegions(text: String): Pair<List<Pair<Int, Int>>, Boolean> {
        val regions = ArrayList<Pair<Int, Int>>()
        var balanced = true
        val straight = ArrayList<Int>()
        for (pos in text.indices) {
            if (text[pos] == '"') straight.add(pos)
        }
        if (straight.size % 2 != 0) balanced = false
        for (pos in straight.indices step 2) {
            if (pos + 1 < straight.size) regions.add(Pair(straight[pos], straight[pos + 1] + 1))
        }
        var depth = 0
        var openAt = -1
        for (pos in text.indices) {
            when (text[pos]) {
                '“' -> {
                    if (depth == 0) openAt = pos
                    depth++
                }
                '”' -> {
                    if (depth == 0) {
                        balanced = false
                    } else {
                        depth--
                        if (depth == 0) regions.add(Pair(openAt, pos + 1))
                    }
                }
            }
        }
        if (depth != 0) balanced = false
        regions.sortWith(compareBy({ it.first }, { it.second }))
        return Pair(regions, balanced)
    }

    /**
     * Single-quote regions as (open, close-exclusive) offsets.
     *
     * BreakIterator compensation with no Scribe counterpart: pysbd never
     * proposes boundaries inside `'...'` or curly U+2018/U+2019 pairs, so
     * Scribe's "singles never form regions" (rule 8.5) costs nothing
     * there. The platform iterator does split inside them, so the port
     * pairs singles itself and [splitParagraph] glues those boundaries
     * back, keeping single-quoted chunks attached whatever their length
     * (unlike double quotes there is no 3-sentence split: Scribe never
     * splits singles either).
     *
     * Apostrophes are never marks: a quote character with a word
     * character (letter, digit or `_`, rule 9.2) on BOTH sides (`man's`,
     * curly `Jack's`) is skipped. Straight singles pair sequentially;
     * curly openers pair with the next closer with depth counting. A
     * leftover stray is ignored while intact pairs are still honored.
     */
    internal fun singleRegions(text: String): List<Pair<Int, Int>> {
        val regions = ArrayList<Pair<Int, Int>>()
        fun isWord(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'
        fun isApostropheAt(pos: Int): Boolean {
            if (pos <= 0 || pos >= text.length - 1) return false
            return isWord(text[pos - 1]) && isWord(text[pos + 1])
        }
        val straight = ArrayList<Int>()
        for (pos in text.indices) {
            if (text[pos] == '\'' && !isApostropheAt(pos)) straight.add(pos)
        }
        for (pos in straight.indices step 2) {
            if (pos + 1 < straight.size) regions.add(Pair(straight[pos], straight[pos + 1] + 1))
        }
        var depth = 0
        var openAt = -1
        for (pos in text.indices) {
            when (text[pos]) {
                '‘' -> {
                    if (depth == 0) openAt = pos
                    depth++
                }
                '’' -> {
                    if (isApostropheAt(pos)) continue
                    if (depth > 0) {
                        depth--
                        if (depth == 0) regions.add(Pair(openAt, pos + 1))
                    }
                }
            }
        }
        regions.sortWith(compareBy({ it.first }, { it.second }))
        return regions
    }

    /** Spans intersecting [low, high), rebased to sentence-local offsets. */
    internal fun rebase(spans: List<IngestSpan>, low: Int, high: Int): List<IngestSpan> {
        val rebased = ArrayList<IngestSpan>()
        for (span in spans) {
            val start = maxOf(span.start, low)
            val end = minOf(span.end, high)
            if (start < end) rebased.add(IngestSpan(start = start - low, end = end - low, style = span.style))
        }
        return rebased
    }

    /**
     * Splits one paragraph into sentences with rebased spans.
     *
     * Sentence `text` keeps its original spacing: the gap after a sentence
     * is stored as trailing whitespace of that sentence, so joining the
     * sentence texts reproduces [text] exactly (rule 10.1). Sids run from
     * [startSid]. Blank input yields no sentences. When candidates cannot
     * be aligned, the whole paragraph is kept as one sentence and a warning
     * is recorded in [warnings] when non-null (Scribe logs this case).
     */
    fun splitParagraph(
        text: String,
        spans: List<IngestSpan> = emptyList(),
        startSid: Int = 1,
        warnings: MutableList<String>? = null
    ): List<IngestSentence> {
        if (trimGap(text).isEmpty()) return emptyList()
        val contents = plainSpans(text)
        if (contents == null) {
            warnings?.add("sentence split failed to align; keeping paragraph whole")
            return listOf(IngestSentence(sid = startSid, text = text, spans = rebase(spans, 0, text.length)))
        }
        val boundaries = sortedSetOf(0, text.length)
        for (pos in 1 until contents.size) boundaries.add(contents[pos].first)
        val (regions, _) = quoteRegions(text)
        for ((openAt, closeAt) in regions) {
            val inner = plainSpans(text.substring(openAt + 1, closeAt - 1))
            if (inner == null || inner.size <= MAX_QUOTE_SENTENCES) {
                val doomed = boundaries.filter { edge -> openAt < edge && edge < closeAt }
                boundaries.removeAll(doomed.toSet())
            } else {
                val base = openAt + 1
                for (pos in 1 until inner.size) boundaries.add(base + inner[pos].first)
            }
        }
        for ((openAt, closeAt) in singleRegions(text)) {
            val doomed = boundaries.filter { edge -> openAt < edge && edge < closeAt }
            boundaries.removeAll(doomed.toSet())
        }
        val ordered = boundaries.toList()
        return ordered.indices
            .take(ordered.size - 1)
            .map { pos ->
                IngestSentence(
                    sid = startSid + pos,
                    text = text.substring(ordered[pos], ordered[pos + 1]),
                    spans = rebase(spans, ordered[pos], ordered[pos + 1])
                )
            }
    }

    /**
     * Splits every para/quote block of a chapter; sids run consecutively
     * from 1 across the whole chapter in block order (rule 8.8, bundle
     * contract). Headings become one sentence each with text kept as-is;
     * breaks carry no sentences. The result parallels [chapter.blocks]:
     * entry [pos] holds block id `pos + 1` and its sentences.
     *
     * Pass a [warnings] list to collect alignment-fallback notices for the
     * IN7 import report; unbalanced quotes stay silent here (Scribe logs
     * those at info level and still honors intact pairs).
     */
    fun splitChapter(
        chapter: IngestChapter,
        warnings: MutableList<String>? = null
    ): List<BlockSentences> {
        val out = ArrayList<BlockSentences>()
        var sid = 1
        for ((pos, block) in chapter.blocks.withIndex()) {
            val sentences = when (block.kind) {
                BLOCK_HEADING -> {
                    if (block.text.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(IngestSentence(sid = sid, text = block.text, spans = emptyList()))
                    }
                }
                BLOCK_PARA, BLOCK_QUOTE -> splitParagraph(block.text, block.spans, sid, warnings)
                else -> emptyList()
            }
            sid += sentences.size
            out.add(BlockSentences(block = pos + 1, sentences = sentences))
        }
        return out
    }
}
