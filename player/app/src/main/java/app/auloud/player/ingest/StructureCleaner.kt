package app.auloud.player.ingest

import java.text.Normalizer
import java.util.Locale

/**
 * IN4: text rules, drop rules, titles and tiny-merge (Slice 9).
 *
 * Ports `docs/ingestion-rules.md` sections 3-6 (element-map consumers
 * live in [EpubStructureWalker]; this object owns normalization, the
 * break rule, boilerplate, drops, chapter titles and the tiny-merge).
 * Scribe's `scribe/extract/clean.py` is the behavior reference; where
 * the rules document and the code differ, this port follows the rules
 * document (each case is recorded in the IN4 report):
 *
 * - DIVERGENCE-1: an empty heading becomes an empty heading block and is
 *   dropped as `dropped empty heading`. Scribe's walker emits kind `para`
 *   for empty headings, so its `dropped empty heading` branch never fires
 *   on the EPUB path; the rules document requires the logging split.
 * - DIVERGENCE-2: boilerplate drops only `para` blocks in the rules
 *   document wording, but Scribe's check is not kind-gated (a quote
 *   carrying boilerplate text drops too). This port follows the code:
 *   any non-break, non-heading block with boilerplate text drops.
 *
 * API 24 safe: `java.text.Normalizer` (API 9), `java.util.Locale`,
 * plain loops. No `java.time`, no `java.nio`, no Android classes, so
 * this is JVM-testable.
 */
object StructureCleaner {

    /** Tiny-merge threshold (rules 5.2, Scribe `TINY_CHAPTER_WORDS`). */
    const val TINY_CHAPTER_WORDS = 200

    /** Boilerplate hints, case-insensitive substring matches (rule 4.3). */
    internal val BOILERPLATE_HINTS = listOf(
        "copyright",
        "\u00A9",
        "(c)",
        "all rights reserved",
        "isbn",
        "project gutenberg",
        "gutenberg ebook",
        "distributed proofreading",
        "transcriber's",
        "transcribers"
    )

    /**
     * True for whitespace in the EPUB sense (Python `str.isspace` parity).
     *
     * `Character.isWhitespace` alone misses no-break and fixed spaces, so
     * space separators count too. This keeps `&nbsp;` collapsing exactly
     * like Scribe instead of surviving as a stray character.
     */
    fun isEpubSpace(ch: Char): Boolean =
        Character.isWhitespace(ch) || Character.isSpaceChar(ch)

    /**
     * NFC-normalize, collapse every whitespace run to one ASCII space,
     * strip ends. Curly quotes are preserved, never straightened.
     * The collapse runs per segment during span extraction and again
     * over the whole block text here (rule 6.1-6.2).
     */
    fun normalizeText(text: String): String {
        val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
        val words = ArrayList<String>()
        val current = StringBuilder()
        for (ch in nfc) {
            if (isEpubSpace(ch)) {
                if (current.isNotEmpty()) {
                    words.add(current.toString())
                    current.clear()
                }
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) words.add(current.toString())
        return words.joinToString(" ")
    }

    /** True for copyright/ISBN/Gutenberg boilerplate paragraphs. */
    fun isBoilerplate(text: String): Boolean {
        val lowered = text.lowercase(Locale.ROOT)
        return BOILERPLATE_HINTS.any { hint -> hint in lowered }
    }

    /**
     * True when a whole-paragraph string is a scene-break divider.
     *
     * Exact rule 6.3: strip; reject empty or longer than 12 characters;
     * accept when every character is in the divider set and at least one
     * must-contain mark is present. A plain `---` or `___` line is NOT a
     * break (no must-contain mark); `* * *` and a lone dinkus are.
     */
    fun isBreakText(text: String): Boolean {
        val stripped = text.trim { ch -> isEpubSpace(ch) }
        if (stripped.isEmpty() || stripped.length > 12) return false
        if (!stripped.all { ch -> isDividerChar(ch) }) return false
        return stripped.any { ch -> isBreakMark(ch) }
    }

    /** Divider set (rule 6.3). Written as escapes, never literal marks. */
    internal fun isDividerChar(ch: Char): Boolean {
        if (isEpubSpace(ch)) return true
        return when (ch) {
            '*', '-', '~', '_' -> true
            '\u2042', '\u2014', '\u2013', '\u00B7', '\u2022', '\u25E6' -> true
            else -> false
        }
    }

    /** Must-contain marks (rule 6.3). */
    internal fun isBreakMark(ch: Char): Boolean {
        return when (ch) {
            '*', '\u2042', '\u2022', '\u25E6', '\u00B7' -> true
            else -> false
        }
    }

    /** Whitespace-separated word count (empty counts 0). */
    fun countWords(text: String): Int {
        val stripped = text.trim { ch -> isEpubSpace(ch) }
        if (stripped.isEmpty()) return 0
        var count = 0
        var inWord = false
        for (ch in stripped) {
            if (isEpubSpace(ch)) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                count++
            }
        }
        return count
    }

    /** Total words in heading/para/quote block texts (breaks count 0). */
    fun chapterWordCount(blocks: List<IngestBlock>): Int {
        var total = 0
        for (block in blocks) {
            if (block.kind == BLOCK_HEADING || block.kind == BLOCK_PARA || block.kind == BLOCK_QUOTE) {
                total += countWords(block.text)
            }
        }
        return total
    }

    /**
     * Applies the drop rules to one spine document; null drops the page.
     *
     * Nav pages drop whole even with text (4.1). Breaks are kept as-is.
     * Headings normalize (empty drops as `dropped empty heading`);
     * para/quote normalize, then drop when empty, convert divider
     * leftovers to breaks (4.5), drop boilerplate with a 60-char preview
     * (4.3). Spans are re-validated, keeping only
     * `0 <= start <= end <= len(text)` (6.2). A document with no
     * remaining text-carrying block drops whole: image-only wording when
     * [SpineDocInput.hasImages], else the empty-page wording (4.6).
     * The title prefers the TOC title, else the first heading (5.1).
     */
    fun cleanDocument(input: SpineDocInput, drops: MutableList<String>): RawIngestChapter? {
        val label = input.href
        if (input.isNav) {
            drops.add("$label: dropped nav/TOC page")
            return null
        }
        val kept = ArrayList<IngestBlock>()
        var firstHeading: String? = null
        for (block in input.blocks) {
            if (block.kind == BLOCK_BREAK) {
                kept.add(block)
                continue
            }
            val text = normalizeText(block.text)
            if (block.kind == BLOCK_HEADING) {
                if (text.isEmpty()) {
                    drops.add("$label: dropped empty heading")
                    continue
                }
                if (firstHeading == null) firstHeading = text
                kept.add(IngestBlock(kind = BLOCK_HEADING, text = text, level = block.level ?: 1))
                continue
            }
            if (text.isEmpty()) {
                drops.add("$label: dropped empty paragraph")
                continue
            }
            if (block.kind == BLOCK_PARA && isBreakText(text)) {
                kept.add(IngestBlock(kind = BLOCK_BREAK))
                continue
            }
            if (isBoilerplate(text)) {
                val preview = if (text.length > 60) text.substring(0, 60) + "\u2026" else text
                drops.add("$label: dropped boilerplate paragraph ('$preview')")
                continue
            }
            val spans = block.spans.filter { span ->
                span.start >= 0 && span.start <= span.end && span.end <= text.length
            }
            kept.add(IngestBlock(kind = block.kind, text = text, spans = spans))
        }
        val hasText = kept.any { block ->
            (block.kind == BLOCK_HEADING || block.kind == BLOCK_PARA || block.kind == BLOCK_QUOTE) &&
                block.text.trim { ch -> isEpubSpace(ch) }.isNotEmpty()
        }
        if (!hasText) {
            if (input.hasImages) {
                drops.add("$label: dropped image-only page (no text)")
            } else {
                drops.add("$label: dropped empty page (no text blocks)")
            }
            return null
        }
        val tocTitle = input.tocTitle?.trim { ch -> isEpubSpace(ch) }?.ifEmpty { null }
        return RawIngestChapter(href = label, title = tocTitle ?: firstHeading, blocks = kept)
    }

    /**
     * Merges sub-threshold chapters forward, backward for a trailing run.
     *
     * Pending tiny chapters prepend their blocks to the next chapter at
     * or above threshold (keeping that chapter's href and title); a
     * trailing run folds into the last kept chapter; an all-tiny book
     * becomes one chapter titled with the last non-null title (else the
     * first). Single-chapter books skip merging. Each merge writes a
     * drop entry naming merged and target chapters with word counts.
     */
    fun mergeTiny(
        chapters: List<RawIngestChapter>,
        drops: MutableList<String>,
        tinyThreshold: Int = TINY_CHAPTER_WORDS
    ): List<RawIngestChapter> {
        if (chapters.size <= 1) return chapters
        val merged = ArrayList<RawIngestChapter>()
        val pending = ArrayList<RawIngestChapter>()
        for (chapter in chapters) {
            if (chapterWordCount(chapter.blocks) < tinyThreshold) {
                pending.add(chapter)
                continue
            }
            var target = chapter
            if (pending.isNotEmpty()) {
                val blocks = ArrayList<IngestBlock>()
                val names = ArrayList<String>()
                for (tiny in pending) {
                    blocks.addAll(tiny.blocks)
                    names.add("'${tiny.title ?: tiny.href}' (${chapterWordCount(tiny.blocks)} words)")
                }
                blocks.addAll(chapter.blocks)
                drops.add(
                    "merged tiny chapter(s) ${names.joinToString(", ")} " +
                        "into next chapter '${chapter.title ?: chapter.href}'"
                )
                target = RawIngestChapter(href = chapter.href, title = chapter.title, blocks = blocks)
                pending.clear()
            }
            merged.add(target)
        }
        if (pending.isNotEmpty()) {
            if (merged.isNotEmpty()) {
                val target = merged.removeAt(merged.size - 1)
                val blocks = ArrayList<IngestBlock>(target.blocks)
                val names = ArrayList<String>()
                for (tiny in pending) {
                    blocks.addAll(tiny.blocks)
                    names.add("'${tiny.title ?: tiny.href}' (${chapterWordCount(tiny.blocks)} words)")
                }
                drops.add(
                    "merged trailing tiny chapter(s) ${names.joinToString(", ")} " +
                        "into previous chapter '${target.title ?: target.href}'"
                )
                merged.add(RawIngestChapter(href = target.href, title = target.title, blocks = blocks))
            } else {
                val blocks = ArrayList<IngestBlock>()
                for (tiny in pending) blocks.addAll(tiny.blocks)
                val title = pending.last().title ?: pending.first().title
                drops.add("all chapters tiny; combined into a single chapter")
                merged.add(RawIngestChapter(href = pending.first().href, title = title, blocks = blocks))
            }
        }
        return merged
    }

    /**
     * Renumbers merged chapters 1..K, applying the `Chapter N` fallback.
     *
     * Untitled chapters take `Chapter N` with the FINAL 1-based index
     * (rule 5.1: an untitled third chapter becomes `Chapter 3`).
     * [IngestChapter.wordCount] is computed over the final blocks.
     */
    fun assemble(merged: List<RawIngestChapter>): List<IngestChapter> {
        return merged.mapIndexed { pos, raw ->
            val index = pos + 1
            IngestChapter(
                index = index,
                title = raw.title ?: "Chapter $index",
                href = raw.href,
                blocks = raw.blocks.toList(),
                wordCount = chapterWordCount(raw.blocks)
            )
        }
    }
}
