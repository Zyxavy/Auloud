package app.auloud.player.ingest

/**
 * IN4: structure/cleaning models (Slice 9).
 *
 * Blocks carry block-level text plus italic/bold spans only. Sentence
 * splitting is IN5 and dialogue detection is IN6: nothing here splits
 * sentences or detects dialogue.
 *
 * Handoff: [EpubStructurePipeline.ingest] returns [StructureResult].
 * IN5 reads each [IngestChapter.blocks] entry (text plus spans) and
 * splits it into sentences. IN6 reuses the spans when it splits mixed
 * sentences. IN7 writes chapters (block id is the 1-based position in
 * [IngestChapter.blocks], ids restart at 1 per chapter) and presents
 * [StructureResult.drops] in the import report.
 *
 * API 24 safe: pure Kotlin data classes, no Android dependencies.
 */

/** Block kind for headings (`h1`-`h6`, clamped to levels 1-3). */
const val BLOCK_HEADING = "heading"

/** Block kind for paragraphs (`p` and leaf `div`/`section`/`article`). */
const val BLOCK_PARA = "para"

/** Block kind for block quotes (one block per `blockquote` element). */
const val BLOCK_QUOTE = "quote"

/** Block kind for scene breaks (`hr` and divider paragraphs). */
const val BLOCK_BREAK = "break"

/** Span style for `em`/`i` content. */
const val SPAN_ITALIC = "italic"

/** Span style for `strong`/`b` content. */
const val SPAN_BOLD = "bold"

/**
 * One italic/bold run as character offsets into its block's text.
 *
 * Offsets count UTF-16 code units (Kotlin [String] indices). They match
 * Scribe's Python offsets for all BMP text; astral characters (emoji,
 * rare in books) count as two units here and one code point there.
 */
data class IngestSpan(
    val start: Int,
    val end: Int,
    val style: String
)

/**
 * One structural block from a spine document.
 *
 * [kind] is one of [BLOCK_HEADING], [BLOCK_PARA], [BLOCK_QUOTE],
 * [BLOCK_BREAK]. [text] is the NFC-normalized, whitespace-collapsed block
 * text (empty for breaks and for blocks dropped later as empty).
 * [level] is the heading level 1-3 (null for non-headings). [spans] holds
 * the italic/bold runs sorted by (start, end); headings carry none.
 */
data class IngestBlock(
    val kind: String,
    val text: String = "",
    val level: Int? = null,
    val spans: List<IngestSpan> = emptyList()
)

/**
 * One spine document's parsed output (IN4 walker result, before drops).
 *
 * [blocks] is in document order. [footnoteStrips] counts removed `sup`
 * elements plus footnote-like links (reported once per document as
 * `stripped N footnote marker(s)`). [hasImages] mirrors Scribe: true
 * when the raw bytes contain `<img` (case-insensitive) in the first
 * 200000 bytes; feeds the image-only-page drop rule.
 */
data class ParsedSpineDocument(
    val blocks: List<IngestBlock>,
    val footnoteStrips: Int,
    val hasImages: Boolean
)

/**
 * One spine document's cleaning input (walker output plus TOC/nav flags).
 *
 * [tocTitle] is the flattened TOC title from IN3
 * ([EpubSpineDocument.tocTitle]) or null. [isNav] marks nav/TOC pages
 * dropped whole. [hasImages] and [blocks] come from
 * [ParsedSpineDocument]; [footnoteStrips] is reported by the pipeline,
 * not the cleaner.
 */
data class SpineDocInput(
    val href: String,
    val tocTitle: String?,
    val isNav: Boolean,
    val hasImages: Boolean,
    val blocks: List<IngestBlock>,
    val footnoteStrips: Int
)

/**
 * One spine document after drops, before tiny-merge and renumbering.
 *
 * [title] is the TOC title or first heading text, null when neither
 * exists (numbering applies `Chapter N` later with the final index).
 */
data class RawIngestChapter(
    val href: String,
    val title: String?,
    val blocks: List<IngestBlock>
)

/**
 * One final chapter after tiny-merge and renumbering.
 *
 * [index] is the final 1-based chapter number. [title] is never null or
 * blank (`Chapter N` fallback applied). [blocks] is in document order;
 * the block id is the 1-based position (ids restart at 1 per chapter).
 * [wordCount] counts whitespace-separated tokens over heading, para and
 * quote block texts (breaks contribute 0).
 */
data class IngestChapter(
    val index: Int,
    val title: String,
    val href: String,
    val blocks: List<IngestBlock>,
    val wordCount: Int
)

/**
 * IN5: sentence models (Slice 9).
 *
 * [IngestSentence] carries text plus rebased italic/bold spans only: no
 * kind/speaker fields. Dialogue tagging is IN6's own layer keyed by sid,
 * so this file must never grow a kind field here. Offsets count UTF-16
 * code units (Kotlin [String] indices), like [IngestSpan].
 *
 * The spacing contract (rules 10.1-10.4): the gap after a sentence is
 * stored as trailing whitespace of that sentence, so joining the
 * sentence texts reproduces the block text exactly. Span offsets are
 * measured over the stored string, trailing spacing included.
 */

/**
 * One sentence of a para/quote block.
 *
 * [sid] runs consecutively from 1 across the whole chapter (all blocks;
 * headings count, breaks hold none). [text] keeps its original spacing
 * (trailing gap stored, never trimmed). [spans] holds the italic/bold
 * runs intersecting this sentence, rebased to sentence-local offsets; a
 * span crossing a boundary is clipped into each side.
 */
data class IngestSentence(
    val sid: Int,
    val text: String,
    val spans: List<IngestSpan> = emptyList()
)

/**
 * One block's sentences from [SentenceSplitter.splitChapter].
 *
 * [block] is the 1-based position in [IngestChapter.blocks] (block id).
 * Headings hold exactly one sentence (or none when blank); breaks hold
 * none; para/quote blocks hold one or more.
 */
data class BlockSentences(
    val block: Int,
    val sentences: List<IngestSentence>
)

/**
 * IN4 top-level output: final chapters plus the human-readable drop log.
 *
 * [drops] is one chronological channel: container warnings first, then
 * per-document notices in spine order (non-linear skips, unreadable
 * items, parse failures, footnote strips), then per-document cleaning
 * drops in spine order, then tiny-merge entries. IN7 presents this list
 * in the import report. Every entry names the file and the reason.
 */
data class StructureResult(
    val chapters: List<IngestChapter>,
    val drops: List<String>
)
