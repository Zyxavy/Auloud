package app.auloud.player.ingest

/**
 * IN6: sentence kinds for on-device EPUB ingestion (Slice 9).
 *
 * [TaggedSentence] carries text plus rebased spans plus the dialogue tag
 * (kind plus the reserved 2.0 speaker). IN5 [IngestSentence] stays
 * kind-free by design; this layer adds the tag keyed by sid, so this file
 * is the only place a kind field lives before IN7 writes chapters.
 */
const val KIND_NARRATION = "narration"

/** Sentence kind for quoted speech (renders with the dialogue voice). */
const val KIND_DIALOGUE = "dialogue"

/**
 * Reserved 2.0 speaker for narration.
 *
 * Mirrors `BundleValidator.RESERVED_SPEAKERS`: every narration sentence
 * uses this key, and `voices` always contains it.
 */
const val SPEAKER_NARRATOR = "narrator"

/** Reserved 2.0 speaker for quoted speech (renders with the dialogue voice). */
const val SPEAKER_DIALOGUE = "dialogue"

/**
 * One sentence plus its IN6 dialogue tag.
 *
 * [sid] runs consecutively from 1 across the whole chapter (renumbered by
 * [DialogueTagger.tagChapter] after mixed-sentence splits). [text] keeps
 * its stored spacing, so joining a block's sentences reproduces the block
 * text exactly (rule 10.1). [spans] are sentence-local offsets (rule 7.4).
 * [kind] is [KIND_NARRATION] or [KIND_DIALOGUE]; [speaker] is the matching
 * reserved key ([SPEAKER_NARRATOR] or [SPEAKER_DIALOGUE]). [quote] is the
 * 1-based dialogue-region number within the paragraph (null for
 * narration); [continued] marks a multi-paragraph continuation.
 * [splitPair] links the halves of one original sentence split at a quote
 * boundary (paragraph-local id, null elsewhere; Slice 10 reads it for the
 * short tag pause).
 */
data class TaggedSentence(
    val sid: Int,
    val chapter: Int,
    val block: Int,
    var text: String,
    var spans: List<IngestSpan>,
    var speaker: String,
    var kind: String,
    var quote: Int? = null,
    var continued: Boolean = false,
    var splitPair: Int? = null
) {
    /** Quote key for dialogue records, else null. */
    val key: Triple<Int, Int, Int>?
        get() = if (kind == KIND_DIALOGUE && quote != null) {
            Triple(chapter, block, quote as Int)
        } else {
            null
        }
}

/** One dialogue quote: stable key plus its stored text. */
data class QuoteInfo(
    val chapter: Int,
    val block: Int,
    val quote: Int,
    val text: String,
    val continued: Boolean = false
) {
    /** Stable anchor (chapter, block, quote) in open-quote order. */
    val key: Triple<Int, Int, Int>
        get() = Triple(chapter, block, quote)
}

/** Result of splitting one paragraph at quote boundaries. */
data class ParagraphDialogue(
    val sentences: List<TaggedSentence>,
    val quotes: List<QuoteInfo>,
    val outQuote: Boolean,
    val nextQuote: Int
)

/** One block's tagged sentences from [DialogueTagger.tagChapter]. */
data class TaggedBlock(
    val block: Int,
    val kind: String,
    val sentences: List<TaggedSentence>
)

/** Result of tagging one chapter: per-block sentences plus quote entries. */
data class ChapterDialogue(
    val chapter: Int,
    val blocks: List<TaggedBlock>,
    val quotes: List<QuoteInfo>
)

/**
 * IN6: dialogue detection port for on-device EPUB ingestion (Slice 9).
 *
 * Ports `docs/ingestion-rules.md` section 9 (dialogue detection) with
 * Scribe's `scribe/text/dialogue.py` (`split_paragraph_dialogue`,
 * `split_chapter_dialogue`) as the behavior reference. `spec/fixtures/
 * dialogue-cases.json` (23 vectors) pins the behavior; the rules document
 * is the oracle when they disagree.
 *
 * Rules ported as-is: double-quote mode when the paragraph holds any `"`,
 * U+201C or U+201D, else single-quote mode (9.1); apostrophes (word
 * character on both sides) never boundaries (9.2); inch marks (`"` after a
 * digit) never open or close (9.3); empty pairs never form regions (9.4);
 * multi-paragraph continuation with the opener kept as a marker, quoteless
 * continuation, `continued` flags, and the chapter-end flip of a trailing
 * unclosed chain back to narration (9.5); the scare-quote rule (closed
 * fragment with no terminal, no trailing comma, at most 2 words is
 * narration emphasis, 9.6); mixed-sentence splits re-split with
 * [SentenceSplitter] (reused, never forked) with paragraph-local
 * `splitPair` open/adopt/extend rules (9.7); orphan-mark reattach (9.8);
 * whitespace-gap attach/prefix so joins round-trip exactly (9.9, 10.1);
 * unbalanced/stray/unclosed warnings while intact pairs are still honored
 * (9.10); span rebasing to sentence-local offsets (9.11, rule 7.4).
 *
 * Pipeline position: IN5 [SentenceSplitter] splits each block into
 * [IngestSentence] entries; [tagChapter] rebuilds each paragraph by
 * joining (exact per the IN5 round-trip guarantee, mirroring Scribe's
 * rebuild-by-join, rule 1) and tags it. IN7 writes the chapter files from
 * [ChapterDialogue] (speaker is already the reserved 2.0 key; quote
 * numbers and split pairs ride along for Slice 10 rendering).
 *
 * One deliberate port difference: Scribe's flip at chapter end resets
 * kind/quote/continued only, because its speaker field is still the
 * undifferentiated block speaker at that stage. Here kind and speaker are
 * bound (2.0 allows only the two reserved keys), so the flip also resets
 * speaker to [SPEAKER_NARRATOR] (decision D-087).
 *
 * API 24 safe: plain loops, regex-free char tests, `java.text` only
 * through [SentenceSplitter]. No Android classes, JVM-testable. Warnings
 * go to the optional [warnings] list (Scribe logs them); IN7 presents
 * them in the import report.
 */
object DialogueTagger {

    /** Scare-quote filter: at most this many words without terminal punctuation. */
    internal const val SCARE_MAX_WORDS = 2

    internal const val LEFT_DOUBLE = '“'
    internal const val RIGHT_DOUBLE = '”'
    internal const val LEFT_SINGLE = '‘'
    internal const val RIGHT_SINGLE = '’'

    /** Double-quote characters selecting double-quote mode (rule 9.1). */
    internal val DOUBLE_CHARS = setOf('"', LEFT_DOUBLE, RIGHT_DOUBLE)

    /** Fragment terminals marking real speech, never scare quotes (rule 9.6). */
    internal val TERMINALS = setOf('.', '?', '!', '…')

    /** Characters that on their own carry no speech (rule 9.8 orphan marks). */
    internal val ORPHAN_MARKS = setOf('\'', '"', LEFT_DOUBLE, RIGHT_DOUBLE, LEFT_SINGLE, RIGHT_SINGLE)

    /** One balanced quote region as paragraph offsets (close exclusive). */
    internal data class Region(
        val start: Int,
        val end: Int,
        val continued: Boolean = false,
        val closed: Boolean = true
    )

    /** Scan result: regions plus the carry flag plus the stray flag. */
    internal data class Scan(val regions: List<Region>, val outQuote: Boolean, val stray: Boolean)

    /** Per para/quote block carry for the chapter-end flip (rule 9.5). */
    internal data class BlockInfo(
        val blockId: Int,
        val startedIn: Boolean,
        val endedOpen: Boolean,
        val trailing: Int?
    )

    /**
     * True when a closed quoted fragment is emphasis, not dialogue.
     *
     * [inner] is the text between the outer quote marks (no markers).
     * Empty fragments count as scare (callers additionally warn). Exact
     * rule 9.6: no terminal punctuation (`.`, `?`, `!`, U+2026 or the
     * three-char `...`), no trailing comma, at most [SCARE_MAX_WORDS]
     * whitespace-separated words.
     */
    fun isScareQuote(inner: String): Boolean {
        val stripped = inner.trim { ch -> StructureCleaner.isEpubSpace(ch) }
        if (stripped.isEmpty()) return true
        if ("..." in stripped) return false
        if (stripped.any { ch -> ch in TERMINALS }) return false
        if (stripped.endsWith(",")) return false
        return stripped.split(Regex("\\s+")).size <= SCARE_MAX_WORDS
    }

    internal fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    /** Double-quote mode scan; returns regions, the out-carry and the stray flag. */
    internal fun scanDoubles(text: String, inQuote: Boolean): Scan {
        val regions = ArrayList<Region>()
        var stray = false
        var inQ = inQuote
        var depth = if (inQuote) 1 else 0
        var openIdx = if (inQuote) 0 else -1
        var continued = inQuote
        var scanFrom = 0
        if (inQuote) {
            val stripped = text.trimStart { ch -> StructureCleaner.isEpubSpace(ch) }
            val lead = text.length - stripped.length
            scanFrom = if (stripped.isNotEmpty() && (stripped[0] == '"' || stripped[0] == LEFT_DOUBLE)) {
                lead + 1
            } else {
                0
            }
        }
        for (i in scanFrom until text.length) {
            val char = text[i]
            if (char == LEFT_DOUBLE) {
                if (!inQ) {
                    inQ = true
                    depth = 1
                    openIdx = i
                    continued = false
                } else {
                    depth++
                }
            } else if (char == RIGHT_DOUBLE) {
                if (!inQ) {
                    stray = true
                } else if (depth > 1) {
                    depth--
                } else {
                    regions.add(Region(openIdx, i + 1, continued, true))
                    inQ = false
                    depth = 0
                    openIdx = -1
                    continued = false
                }
            } else if (char == '"') {
                val prev = if (i > 0) text[i - 1] else ' '
                val next = if (i + 1 < text.length) text[i + 1] else null
                if (prev.isDigit()) {
                    if (!inQ) stray = true
                    continue
                }
                if (!inQ) {
                    if (next != null && !StructureCleaner.isEpubSpace(next)) {
                        inQ = true
                        depth = 0
                        openIdx = i
                        continued = false
                    } else {
                        stray = true
                    }
                } else {
                    regions.add(Region(openIdx, i + 1, continued, true))
                    inQ = false
                    depth = 0
                    openIdx = -1
                    continued = false
                }
            }
        }
        val outQuote = inQ
        if (inQ) regions.add(Region(openIdx, text.length, continued, false))
        return Scan(regions, outQuote, stray)
    }

    /** Single-quote mode scan (no doubles in the paragraph). Apostrophes skip. */
    internal fun scanSingles(text: String, inQuote: Boolean): Scan {
        val regions = ArrayList<Region>()
        var stray = false
        var inQ = inQuote
        var depth = if (inQuote) 1 else 0
        var openIdx = if (inQuote) 0 else -1
        var continued = inQuote
        var scanFrom = 0
        if (inQuote) {
            val stripped = text.trimStart { ch -> StructureCleaner.isEpubSpace(ch) }
            val lead = text.length - stripped.length
            scanFrom = if (stripped.isNotEmpty() && (stripped[0] == '\'' || stripped[0] == LEFT_SINGLE)) {
                lead + 1
            } else {
                0
            }
        }
        for (i in scanFrom until text.length) {
            val char = text[i]
            if (char != '\'' && char != LEFT_SINGLE && char != RIGHT_SINGLE) continue
            val prev = if (i > 0) text[i - 1] else ' '
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            if (isWordChar(prev) && isWordChar(next)) continue
            if (char == LEFT_SINGLE) {
                if (!inQ) {
                    inQ = true
                    depth = 1
                    openIdx = i
                    continued = false
                } else {
                    depth++
                }
            } else if (char == RIGHT_SINGLE) {
                if (!inQ) {
                    stray = true
                } else if (depth > 1) {
                    depth--
                } else {
                    regions.add(Region(openIdx, i + 1, continued, true))
                    inQ = false
                    depth = 0
                    openIdx = -1
                    continued = false
                }
            } else {
                if (!inQ) {
                    if (i + 1 < text.length && !StructureCleaner.isEpubSpace(next)) {
                        inQ = true
                        depth = 1
                        openIdx = i
                        continued = false
                    } else {
                        stray = true
                    }
                } else {
                    regions.add(Region(openIdx, i + 1, continued, true))
                    inQ = false
                    depth = 0
                    openIdx = -1
                    continued = false
                }
            }
        }
        val outQuote = inQ
        if (inQ) regions.add(Region(openIdx, text.length, continued, false))
        return Scan(regions, outQuote, stray)
    }

    /** Index of the opening quote mark, or null for a quoteless continuation. */
    internal fun openerIndex(text: String, region: Region): Int? {
        if (!region.continued) return region.start
        val rest = text.substring(region.start).trimStart { ch -> StructureCleaner.isEpubSpace(ch) }
        if (rest.isNotEmpty() && (rest[0] == '"' || rest[0] == '\'' || rest[0] == LEFT_DOUBLE || rest[0] == LEFT_SINGLE)) {
            return text.length - rest.length
        }
        return null
    }

    /** Text between the outer quote marks of a region (no markers). */
    internal fun innerText(text: String, region: Region): String {
        val opener = openerIndex(text, region)
        if (!region.closed) {
            if (opener == null) return text.substring(region.start)
            return text.substring(opener + 1)
        }
        if (opener == null) return text.substring(region.start, region.end - 1)
        return text.substring(opener + 1, region.end - 1)
    }

    /** Spans intersecting [low, high), rebased to segment-local offsets. */
    internal fun sliceSpans(spans: List<IngestSpan>, low: Int, high: Int): List<IngestSpan> {
        val rebased = ArrayList<IngestSpan>()
        for (span in spans) {
            val start = maxOf(span.start, low)
            val end = minOf(span.end, high)
            if (start < end) rebased.add(IngestSpan(start = start - low, end = end - low, style = span.style))
        }
        return rebased
    }

    /**
     * True when a narration segment continues the previous sentence.
     *
     * First non-space character lowercase means the tag runs on from the
     * dialogue (`"Hi," she said`); uppercase means a new sentence
     * (`"Hi." She smiled`). Known limitation, kept: a lowercase start
     * after terminal punctuation (`"Hi." she left`) misreads as a tag.
     */
    internal fun startsContinuation(segment: String): Boolean {
        val stripped = segment.trimStart { ch -> StructureCleaner.isEpubSpace(ch) }
        return stripped.isNotEmpty() && stripped[0].isLowerCase()
    }

    /**
     * True when a narration segment runs into the following quote.
     *
     * A trailing comma or colon (`She said, `, `He shouted: `) hands the
     * sentence to the quote; terminal punctuation means the tag (if any)
     * is behind, not ahead.
     */
    internal fun endsContinuation(segment: String): Boolean {
        val stripped = segment.trimEnd { ch -> StructureCleaner.isEpubSpace(ch) }
        return stripped.endsWith(",") || stripped.endsWith(":")
    }

    /** True when [text] holds only quote marks/whitespace (reattach it). */
    internal fun isQuoteOrphan(text: String): Boolean {
        val stripped = text.trim { ch -> StructureCleaner.isEpubSpace(ch) }
        return stripped.isNotEmpty() && stripped.all { ch -> ch in ORPHAN_MARKS }
    }

    /**
     * Splits one paragraph at quote boundaries and tags each record.
     *
     * Returns sentence records (sids from [startSid]), quote entries
     * (numbers from [startQuote]), the [ParagraphDialogue.outQuote] carry
     * for the next paragraph, and [ParagraphDialogue.nextQuote]. Joining
     * the sentence texts reproduces [text] exactly (rule 10.1).
     */
    fun splitParagraph(
        text: String,
        spans: List<IngestSpan> = emptyList(),
        chapter: Int = 1,
        block: Int = 1,
        startSid: Int = 1,
        startQuote: Int = 1,
        inQuote: Boolean = false,
        warnings: MutableList<String>? = null
    ): ParagraphDialogue {
        if (SentenceSplitter.isBlankGap(text)) {
            return ParagraphDialogue(emptyList(), emptyList(), inQuote, startQuote)
        }
        val useSingles = DOUBLE_CHARS.none { ch -> ch in text }
        val scan = if (useSingles) scanSingles(text, inQuote) else scanDoubles(text, inQuote)
        val dialogue = ArrayList<Pair<Region, Int>>()
        var quoteNo = startQuote
        var sawEmpty = false
        for (region in scan.regions) {
            if (!region.closed) {
                dialogue.add(Pair(region, quoteNo))
                quoteNo++
                continue
            }
            val inner = innerText(text, region)
            if (SentenceSplitter.trimGap(inner).isEmpty()) {
                sawEmpty = true
                continue
            }
            if (isScareQuote(inner)) continue
            dialogue.add(Pair(region, quoteNo))
            quoteNo++
        }
        if (dialogue.isEmpty()) {
            if (scan.stray || sawEmpty) {
                warnings?.add(
                    "chapter $chapter block $block: unbalanced quotes; " +
                        "treating paragraph as narration: ${text.take(60)}"
                )
            }
            val sentences = SentenceSplitter.splitParagraph(text, spans, startSid, warnings)
            return ParagraphDialogue(
                sentences.map { sent ->
                    TaggedSentence(
                        sid = sent.sid,
                        chapter = chapter,
                        block = block,
                        text = sent.text,
                        spans = sent.spans.toList(),
                        speaker = SPEAKER_NARRATOR,
                        kind = KIND_NARRATION
                    )
                },
                emptyList(),
                scan.outQuote,
                quoteNo
            )
        }
        if (scan.stray || sawEmpty) {
            warnings?.add(
                "chapter $chapter block $block: stray quote characters ignored; " +
                    "intact quotes honored: ${text.take(60)}"
            )
        }
        val quotes = dialogue.map { (region, number) ->
            QuoteInfo(
                chapter = chapter,
                block = block,
                quote = number,
                text = text.substring(region.start, region.end),
                continued = region.continued
            )
        }
        val bounds = sortedSetOf(0, text.length)
        for ((region, _) in dialogue) {
            bounds.add(region.start)
            bounds.add(region.end)
        }
        val regionEndAt = HashMap<Int, Region>()
        val regionStartAt = HashMap<Int, Region>()
        for ((region, _) in dialogue) {
            regionEndAt[region.end] = region
            regionStartAt[region.start] = region
        }
        val tagged = ArrayList<TaggedSentence>()
        var nextSid = startSid
        var pendingLeading = ""
        var carryForward = ""
        var pairSeq = 0
        var pendingPair: Int? = null
        val edges = bounds.toList()
        for (e in 0 until edges.size - 1) {
            val low = edges[e]
            val high = edges[e + 1]
            if (high <= low) continue
            var seg = text.substring(low, high)
            var active: Pair<Region, Int>? = null
            for ((region, number) in dialogue) {
                if (region.start <= low && low < region.end) {
                    active = Pair(region, number)
                    break
                }
            }
            if (SentenceSplitter.isBlankGap(seg)) {
                if (tagged.isNotEmpty()) {
                    tagged[tagged.size - 1].text += seg
                } else {
                    pendingLeading += seg
                }
                continue
            }
            var segSpans = sliceSpans(spans, low, high)
            if (pendingLeading.isNotEmpty()) {
                val shift = pendingLeading.length
                seg = pendingLeading + seg
                segSpans = segSpans.map { span ->
                    IngestSpan(span.start + shift, span.end + shift, span.style)
                }
                pendingLeading = ""
            }
            val pieces = SentenceSplitter.splitParagraph(seg, segSpans, 1, warnings)
            if (pieces.isEmpty()) {
                if (tagged.isNotEmpty()) {
                    tagged[tagged.size - 1].text += seg
                } else {
                    pendingLeading += seg
                }
                continue
            }
            var firstPair: Int? = null
            if (active != null) {
                firstPair = pendingPair
                pendingPair = null
            } else if (pendingPair != null) {
                pendingPair = null
            } else if (regionEndAt.containsKey(low) && startsContinuation(seg)) {
                val prev = tagged.lastOrNull()
                if (prev != null && prev.kind == KIND_DIALOGUE) {
                    if (prev.splitPair != null) {
                        firstPair = prev.splitPair
                    } else {
                        pairSeq++
                        firstPair = pairSeq
                        prev.splitPair = pairSeq
                    }
                }
            }
            val base = tagged.size
            for (sent in pieces) {
                val recordKind = if (active != null) KIND_DIALOGUE else KIND_NARRATION
                val recordQuote = active?.second
                val recordContinued = active?.first?.continued ?: false
                if (isQuoteOrphan(sent.text)) {
                    val last = tagged.lastOrNull()
                    if (last != null && last.kind == recordKind && last.quote == recordQuote) {
                        val baseLen = last.text.length
                        last.text += sent.text
                        last.spans = last.spans + sent.spans.map { span ->
                            IngestSpan(span.start + baseLen, span.end + baseLen, span.style)
                        }
                    } else {
                        carryForward += sent.text
                    }
                    continue
                }
                val prefix = carryForward
                val textOut = prefix + sent.text
                val spansOut = sent.spans.map { span ->
                    IngestSpan(span.start + prefix.length, span.end + prefix.length, span.style)
                }
                carryForward = ""
                val sid = nextSid
                nextSid++
                tagged.add(
                    TaggedSentence(
                        sid = sid,
                        chapter = chapter,
                        block = block,
                        text = textOut,
                        spans = spansOut,
                        speaker = if (recordKind == KIND_DIALOGUE) SPEAKER_DIALOGUE else SPEAKER_NARRATOR,
                        kind = recordKind,
                        quote = recordQuote,
                        continued = recordContinued,
                        splitPair = if (tagged.size == base) firstPair else null
                    )
                )
            }
            if (active == null && tagged.size > base) {
                if (regionStartAt.containsKey(high) && endsContinuation(seg)) {
                    if (firstPair == null) {
                        pairSeq++
                        firstPair = pairSeq
                        tagged[base].splitPair = pairSeq
                    }
                    tagged[tagged.size - 1].splitPair = firstPair
                    pendingPair = firstPair
                }
            }
            if (carryForward.isNotEmpty()) {
                if (tagged.isNotEmpty()) {
                    tagged[tagged.size - 1].text += carryForward
                } else {
                    pendingLeading += carryForward
                }
                carryForward = ""
            }
        }
        if (carryForward.isNotEmpty() && tagged.isNotEmpty()) {
            tagged[tagged.size - 1].text += carryForward
        }
        return ParagraphDialogue(tagged, quotes, scan.outQuote, quoteNo)
    }

    /**
     * Tags every para/quote block of a chapter at quote boundaries.
     *
     * [split] is IN5's [SentenceSplitter.splitChapter] output parallel to
     * [chapter].[IngestChapter.blocks] (block id is the 1-based position);
     * each paragraph is rebuilt by joining its sentence texts, which
     * round-trips exactly per the IN5 spacing guarantee, so dialogue sees
     * whole paragraphs, never pre-split fragments (rule 1). Headings stay
     * single narration sentences with text kept as-is; breaks carry none.
     * Sids renumber consecutively from 1 across the chapter; block ids are
     * preserved. Quote numbers restart at 1 per paragraph. A chain still
     * open at chapter end is warned about and flipped to narration
     * (rule 9.5), including the speaker reset (decision D-087).
     */
    fun tagChapter(
        chapter: IngestChapter,
        split: List<BlockSentences>,
        warnings: MutableList<String>? = null
    ): ChapterDialogue {
        require(split.size == chapter.blocks.size) {
            "tagChapter needs IN5 sentences parallel to chapter blocks " +
                "(got ${split.size} for ${chapter.blocks.size} blocks)"
        }
        val outBlocks = ArrayList<TaggedBlock>()
        val quotesAll = ArrayList<QuoteInfo>()
        val taggedAll = ArrayList<TaggedSentence>()
        var sid = 1
        var inQuote = false
        val blockInfos = ArrayList<BlockInfo>()
        for ((pos, block) in chapter.blocks.withIndex()) {
            val blockId = pos + 1
            when (block.kind) {
                BLOCK_HEADING -> {
                    val sentences = if (block.text.isEmpty()) {
                        emptyList()
                    } else {
                        val tagged = TaggedSentence(
                            sid = sid,
                            chapter = chapter.index,
                            block = blockId,
                            text = block.text,
                            spans = emptyList(),
                            speaker = SPEAKER_NARRATOR,
                            kind = KIND_NARRATION
                        )
                        sid++
                        taggedAll.add(tagged)
                        listOf(tagged)
                    }
                    outBlocks.add(TaggedBlock(blockId, block.kind, sentences))
                }
                BLOCK_PARA, BLOCK_QUOTE -> {
                    val paragraph = StringBuilder()
                    val globalSpans = ArrayList<IngestSpan>()
                    for (sent in split[pos].sentences) {
                        val base = paragraph.length
                        paragraph.append(sent.text)
                        for (span in sent.spans) {
                            globalSpans.add(
                                IngestSpan(base + span.start, base + span.end, span.style)
                            )
                        }
                    }
                    val startedIn = inQuote
                    val result = splitParagraph(
                        paragraph.toString(),
                        globalSpans,
                        chapter.index,
                        blockId,
                        sid,
                        1,
                        startedIn,
                        warnings
                    )
                    inQuote = result.outQuote
                    val trailing = if (result.quotes.isNotEmpty() && result.outQuote) {
                        result.quotes.last().quote
                    } else {
                        null
                    }
                    blockInfos.add(BlockInfo(blockId, startedIn, result.outQuote, trailing))
                    sid += result.sentences.size
                    taggedAll.addAll(result.sentences)
                    quotesAll.addAll(result.quotes)
                    outBlocks.add(TaggedBlock(blockId, block.kind, result.sentences))
                }
                else -> outBlocks.add(TaggedBlock(blockId, block.kind, emptyList()))
            }
        }
        if (inQuote) {
            warnings?.add(
                "unclosed quote at chapter end; treating trailing speech " +
                    "as narration (chapter ${chapter.index})"
            )
            val tail = ArrayList<Pair<Int, Int>>()
            for (info in blockInfos.asReversed()) {
                if (!info.endedOpen || info.trailing == null) break
                tail.add(Pair(info.blockId, info.trailing))
                if (!info.startedIn) break
            }
            val dropKeys = tail.toSet()
            for (tagged in taggedAll) {
                if (tagged.kind == KIND_DIALOGUE &&
                    tagged.quote != null &&
                    Pair(tagged.block, tagged.quote as Int) in dropKeys
                ) {
                    tagged.kind = KIND_NARRATION
                    tagged.quote = null
                    tagged.continued = false
                    tagged.speaker = SPEAKER_NARRATOR
                }
            }
            quotesAll.removeAll { Pair(it.block, it.quote) in dropKeys }
        }
        return ChapterDialogue(chapter.index, outBlocks, quotesAll)
    }
}
