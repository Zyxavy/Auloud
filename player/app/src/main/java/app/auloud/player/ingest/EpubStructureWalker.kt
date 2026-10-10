package app.auloud.player.ingest

import java.io.ByteArrayInputStream
import java.io.IOException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * IN4: jsoup HTML walker producing blocks per the ingestion rules (Slice 9).
 *
 * Ports `docs/ingestion-rules.md` sections 2-3 and 6-7 (element map,
 * footnote strips, whitespace/NFC rules, italic/bold spans) with
 * Scribe's `scribe/extract/epub.py` (`parse_html_blocks`,
 * `_text_and_spans`, `_strip_footnotes`) as the behavior reference.
 *
 * Walk rules (document order, one document at a time):
 *
 * - `script` and `style` are removed before walking and never produce
 *   text. Parsing is forgiving tag-soup HTML (jsoup, mirroring Scribe's
 *   lxml HTML parser): malformed markup never throws, it is skipped.
 * - `h1`-`h3` map to headings (level equals the digit); `h4`-`h6`
 *   clamp to level 3. Empty headings become empty heading blocks (the
 *   cleaner drops them as `dropped empty heading`; see DIVERGENCE-1 in
 *   [StructureCleaner]).
 * - `p` maps to a para, or to a break when the whole text matches the
 *   divider rule; image-only paragraphs yield empty text and fall to the
 *   empty-paragraph drop. Paragraphs inside `blockquote` are covered by
 *   the quote handler and emit nothing.
 * - `blockquote` becomes ONE quote block (flattened text, single-space
 *   separators); inner paragraphs are not separate blocks. An empty
 *   quote yields an empty para block (Scribe parity).
 * - `hr` is an unconditional break.
 * - `div`/`section`/`article` are a leaf-only fallback: containers with
 *   `p`/`h1`-`h6`/`blockquote` descendants, or inside
 *   `p`/`h1`-`h3`/`blockquote`, are skipped; empty ones are skipped
 *   silently; divider-matching text becomes a break.
 * - ALL `sup` elements are stripped and counted. A link is stripped and
 *   counted only when its `href` contains a footnote hint (`footnote`,
 *   `footnotes`, `fn`, `endnote`, `note`, `ref`, case-insensitive) AND
 *   its text matches the marker pattern or is at most 4 chars long.
 * - `em`/`i` give italic spans, `strong`/`b` bold spans (ancestor walk,
 *   so `<em><span>x</span></em>` is italic); nesting yields two
 *   independent spans. All other inline elements pass text through with
 *   no styling. Images contribute no text.
 *
 * Memory: [parseDocument] takes one spine document's bytes and returns
 * only blocks (never a DOM), so the pipeline holds one parsed document
 * at a time. No whole-book DOM is ever built.
 *
 * API 24 safe: jsoup plus `java.io`; desugaring for jsoup internals is
 * enabled at the app level (D-085). No `java.time`, no `java.nio`, no
 * Android classes, so this is JVM-testable. No dependency beyond jsoup.
 */
object EpubStructureWalker {

    /** Heading levels (`h4`-`h6` clamp to 3; the model allows 1-3 only). */
    internal val HEADING_LEVELS = mapOf(
        "h1" to 1,
        "h2" to 2,
        "h3" to 3,
        "h4" to 3,
        "h5" to 3,
        "h6" to 3
    )

    /** Footnote-hint substrings for link stripping (case-insensitive). */
    internal val FOOTNOTE_HREF_HINTS = listOf(
        "footnote", "footnotes", "fn", "endnote", "note", "ref"
    )

    /**
     * Footnote-marker text: optional open bracket, 1-3 digits, optional
     * close bracket (Scribe `_FOOTNOTE_TEXT_RE` parity).
     */
    internal val FOOTNOTE_TEXT_RE = Regex(
        "^[\\[\\(*\u2020\u2021\u00A7]?\\s*\\d{1,3}\\s*[\\]\\)*\u2020\u2021\u00A7]?$"
    )

    /** Raw-byte window scanned for `<img` (Scribe parity). */
    internal const val IMAGE_SCAN_BYTES = 200000

    /**
     * Parses one spine document's HTML bytes into blocks.
     *
     * The bytes are sniffed for charset (BOM/meta, UTF-8 default) and
     * parsed as forgiving HTML, never throwing on malformed markup.
     * Failure names the file and the rule; [EpubStructurePipeline]
     * records it as `failed to parse item` and skips the document.
     */
    fun parseDocument(html: ByteArray, sourceLabel: String): Result<ParsedSpineDocument> {
        return try {
            val doc = Jsoup.parse(ByteArrayInputStream(html), null, "")
            val root = doc.body() ?: doc
            root.select("script, style").remove()
            val footnoteStrips = stripFootnotes(root)
            val blocks = walkBlocks(root)
            Result.success(
                ParsedSpineDocument(
                    blocks = blocks,
                    footnoteStrips = footnoteStrips,
                    hasImages = hasImages(html)
                )
            )
        } catch (e: Exception) {
            Result.failure(
                IOException("$sourceLabel: failed to parse item (${e.message})", e)
            )
        }
    }

    /**
     * True when the raw bytes contain `<img` (ASCII case-insensitive)
     * in the first [IMAGE_SCAN_BYTES] bytes (Scribe parity).
     */
    fun hasImages(html: ByteArray): Boolean {
        val limit = minOf(html.size, IMAGE_SCAN_BYTES)
        var pos = 0
        while (pos + 4 <= limit) {
            if (html[pos] == '<'.code.toByte() &&
                (html[pos + 1] == 'i'.code.toByte() || html[pos + 1] == 'I'.code.toByte()) &&
                (html[pos + 2] == 'm'.code.toByte() || html[pos + 2] == 'M'.code.toByte()) &&
                (html[pos + 3] == 'g'.code.toByte() || html[pos + 3] == 'G'.code.toByte())
            ) {
                return true
            }
            pos++
        }
        return false
    }

    /** Removes footnote markers (`sup` plus short footnote links). */
    internal fun stripFootnotes(root: Element): Int {
        var removed = 0
        for (sup in root.select("sup")) {
            sup.remove()
            removed++
        }
        for (link in root.select("a")) {
            val href = link.attr("href").lowercase(java.util.Locale.ROOT)
            val linkText = link.text().trim { ch -> StructureCleaner.isEpubSpace(ch) }
            if (FOOTNOTE_HREF_HINTS.any { hint -> hint in href } &&
                (FOOTNOTE_TEXT_RE.matches(linkText) || linkText.length <= 4)
            ) {
                link.remove()
                removed++
            }
        }
        return removed
    }

    /** Walks block elements in document order (see class KDoc). */
    internal fun walkBlocks(root: Element): List<IngestBlock> {
        val blocks = ArrayList<IngestBlock>()
        val elements = root.allElements
        for (pos in 1 until elements.size) {
            val el = elements[pos]
            when (el.tagName()) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (el.closest("blockquote") != null) continue
                    val text = StructureCleaner.normalizeText(joinedWholeText(el))
                    if (text.isEmpty()) {
                        blocks.add(IngestBlock(kind = BLOCK_HEADING, text = ""))
                        continue
                    }
                    blocks.add(
                        IngestBlock(
                            kind = BLOCK_HEADING,
                            text = text,
                            level = HEADING_LEVELS[el.tagName()] ?: 3
                        )
                    )
                }
                "p" -> {
                    if (el.closest("blockquote") != null) continue
                    val (text, spans) = textAndSpans(el)
                    if (text.isEmpty()) {
                        blocks.add(IngestBlock(kind = BLOCK_PARA, text = ""))
                        continue
                    }
                    if (StructureCleaner.isBreakText(text)) {
                        blocks.add(IngestBlock(kind = BLOCK_BREAK))
                        continue
                    }
                    blocks.add(IngestBlock(kind = BLOCK_PARA, text = text, spans = spans))
                }
                "blockquote" -> {
                    val (text, spans) = textAndSpans(el)
                    if (text.isEmpty()) {
                        blocks.add(IngestBlock(kind = BLOCK_PARA, text = ""))
                        continue
                    }
                    blocks.add(IngestBlock(kind = BLOCK_QUOTE, text = text, spans = spans))
                }
                "hr" -> {
                    blocks.add(IngestBlock(kind = BLOCK_BREAK))
                }
                "div", "section", "article" -> {
                    if (el.select("p, h1, h2, h3, h4, h5, h6, blockquote").isNotEmpty()) continue
                    if (el.closest("blockquote") != null) continue
                    val parent = el.parent()
                    if (parent is Element && (
                        parent.tagName() == "p" || parent.tagName() == "h1" ||
                            parent.tagName() == "h2" || parent.tagName() == "h3" ||
                            parent.tagName() == "blockquote"
                        )
                    ) {
                        continue
                    }
                    val (text, spans) = textAndSpans(el)
                    if (text.isEmpty()) continue
                    if (StructureCleaner.isBreakText(text)) {
                        blocks.add(IngestBlock(kind = BLOCK_BREAK))
                        continue
                    }
                    blocks.add(IngestBlock(kind = BLOCK_PARA, text = text, spans = spans))
                }
            }
        }
        return blocks
    }

    /**
     * Builds cleaned text plus italic/bold spans for one block element
     * (Scribe `_text_and_spans` parity).
     *
     * Each text node is NFC-normalized before offset tracking so
     * combining-mark length changes never shift later span offsets.
     * Whitespace runs collapse to one space inheriting the PRECEDING
     * character's style (the following run's own style at text start),
     * so `<em>foo bar</em>` stays one span. Each maximal styled run
     * becomes one span with edge whitespace trimmed; spans sort by
     * (start, end).
     */
    internal fun textAndSpans(el: Element): Pair<String, List<IngestSpan>> {
        val texts = ArrayList<String>()
        val italics = ArrayList<Boolean>()
        val bolds = ArrayList<Boolean>()
        collectSegments(el, texts, italics, bolds)

        val chars = StringBuilder()
        val charItals = ArrayList<Boolean>()
        val charBolds = ArrayList<Boolean>()
        for (pos in texts.indices) {
            for (ch in texts[pos]) {
                chars.append(ch)
                charItals.add(italics[pos])
                charBolds.add(bolds[pos])
            }
        }

        val outChars = StringBuilder()
        val outItals = ArrayList<Boolean>()
        val outBolds = ArrayList<Boolean>()
        var pendingSpace = false
        var pendingItal = false
        var pendingBold = false
        for (pos in 0 until chars.length) {
            val ch = chars[pos]
            val ital = charItals[pos]
            val bold = charBolds[pos]
            if (StructureCleaner.isEpubSpace(ch)) {
                if (outChars.isNotEmpty() || !pendingSpace) {
                    if (!pendingSpace) {
                        pendingSpace = true
                        pendingItal = if (outChars.isNotEmpty()) outItals.last() else ital
                        pendingBold = if (outChars.isNotEmpty()) outBolds.last() else bold
                    }
                }
                continue
            }
            if (pendingSpace) {
                if (outChars.isNotEmpty()) {
                    outChars.append(' ')
                    outItals.add(pendingItal)
                    outBolds.add(pendingBold)
                }
                pendingSpace = false
                pendingItal = false
                pendingBold = false
            }
            outChars.append(ch)
            outItals.add(ital)
            outBolds.add(bold)
        }

        val text = outChars.toString()
        val spans = ArrayList<IngestSpan>()
        collectRuns(text, outItals, SPAN_ITALIC, spans)
        collectRuns(text, outBolds, SPAN_BOLD, spans)
        spans.sortWith(compareBy({ it.start }, { it.end }))
        return Pair(text, spans)
    }

    /** Collects per-text-node NFC text plus ancestor styles. */
    private fun collectSegments(
        el: Element,
        texts: MutableList<String>,
        italics: MutableList<Boolean>,
        bolds: MutableList<Boolean>
    ) {
        for (node in descendantTextNodes(el)) {
            val raw = node.wholeText
            if (raw.isEmpty()) continue
            var italic = false
            var bold = false
            var parent: Node? = node.parent()
            while (parent != null) {
                if (parent is Element) {
                    val tag = parent.tagName()
                    if (tag == "em" || tag == "i") italic = true
                    if (tag == "strong" || tag == "b") bold = true
                }
                parent = parent.parent()
            }
            texts.add(java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC))
            italics.add(italic)
            bolds.add(bold)
        }
    }

    /** All descendant text nodes in document order. */
    private fun descendantTextNodes(el: Element): List<TextNode> {
        val out = ArrayList<TextNode>()
        fun visit(node: Node) {
            for (child in node.childNodes()) {
                if (child is TextNode) {
                    out.add(child)
                } else if (child is Element) {
                    visit(child)
                }
            }
        }
        visit(el)
        return out
    }

    /** Descendant whole texts joined with one space (heading parity). */
    private fun joinedWholeText(el: Element): String {
        return descendantTextNodes(el).joinToString(" ") { it.wholeText }
    }

    /** Turns maximal styled runs into trimmed spans. */
    private fun collectRuns(
        text: String,
        flags: List<Boolean>,
        style: String,
        spans: MutableList<IngestSpan>
    ) {
        var pos = 0
        while (pos < text.length) {
            if (!flags[pos]) {
                pos++
                continue
            }
            var end = pos + 1
            while (end < text.length && flags[end]) end++
            var start = pos
            while (start < end && StructureCleaner.isEpubSpace(text[start])) start++
            while (end > start && StructureCleaner.isEpubSpace(text[end - 1])) end--
            if (end > start) spans.add(IngestSpan(start = start, end = end, style = style))
            pos = end
        }
    }
}
