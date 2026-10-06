# Ingestion Rules (Slice 9, IN0)

Language-neutral reference for on-device EPUB ingestion. The Kotlin port
(IN4-IN6) implements this document, not the Python code directly. Every rule
below is distilled from Scribe behavior; the traceability table in section 14
points at the exact module, function and test behind each rule.

Sources read for this document (Scribe, read-only, unchanged by IN0):

- `scribe/extract/epub.py` (spine reading, HTML walk)
- `scribe/extract/clean.py` (drops, titles, tiny-merge, normalization)
- `scribe/text/sentences.py` (sentence splitting)
- `scribe/text/dialogue.py` (dialogue detection and mixed-sentence splitting)
- `scribe/draft.py` (pipeline order, book id scheme, work-folder artifacts)
- Tests: `scribe/tests/test_extract_epub.py`, `test_sentences.py`,
  `test_dialogue.py`
- Player spacing consumer: `player/.../reader/ParagraphLayout.kt`

Out of scope for this document (owned by later packages, not decided here):
spec 2.0 unrendered-book shape (IN1), shared test vectors (IN2), zip safety
limits and DRM refusal (IN3, plan decision 9), the pipeline writer and
duplicate detection (IN7), UI and reader (IN8-IN9).

Character notes: curly double quotes are U+201C (open) and U+201D (close);
curly single quotes are U+2018 (open) and U+201D-style U+2019 (close, also the
apostrophe); ellipsis is U+2026; the dinkus mark is U+2042. No rule in this
document uses the em dash character; where Scribe matches it inside divider
text, section 6 gives the pattern with a Unicode escape.

## 1. Pipeline order

```
spine documents in order -> HTML walk to blocks -> drops -> chapter titles
  -> tiny-merge -> sentence split -> dialogue split -> chapter files
```

Scribe runs extraction and cleaning (`extract_epub_chapters`), then sentence
splitting (`split_chapter`), then dialogue splitting (`split_chapter_dialogue`,
which accepts either one-sentence-per-block or already-split input because it
rebuilds the paragraph by joining). The Kotlin pipeline (IN7) must preserve
this order: dialogue splitting sees whole paragraphs, never pre-split
fragments.

## 2. Input reading

2.1. Read spine items in spine order. Each spine item yields one document
with its blocks; documents are later grouped into chapters (section 5).

2.2. Non-linear spine items (`linear` attribute other than `yes`, for
example covers) are skipped, and the skip is recorded in the drop log
(`skipped non-linear spine item`). The book must still ingest without them.

2.3. Spine entries that cannot be read, or whose HTML fails to parse, are
skipped with a recorded notice (`could not read item`, `failed to parse
item`). Parsing is forgiving: XHTML is parsed as tag-soup HTML
(Scribe uses BeautifulSoup with the lxml HTML parser deliberately, so messy
real-world EPUBs still parse). The Kotlin walker (jsoup, IN4) must be equally
forgiving: never throw on malformed markup, skip what cannot be read, log it.

2.4. Chapter titles prefer the table of contents. Flatten the TOC
(EPUB 3 nav and older NCX entries, including nested section children) to a
map keyed by lowercase basename without fragment (`href` up to `#`, after
the last `/`). First map entry wins per basename. A spine document takes its
TOC title when present, else its first heading (section 5), else
`Chapter N` (section 5).

2.5. Navigation-document detection must be exact, not substring. A spine
item is nav when its type is the EPUB nav type (ebooklib `EpubNav` /
`ITEM_NAVIGATION`) or when its filename stem (lowercased, without directory,
query or extension) is exactly `nav`, `toc` or `ncx`. Counter-case from the
tests: a real chapter named `naval-history.xhtml` (stem `naval-history`)
is content and must survive. The Kotlin port must copy this exact-stem rule.

2.6. Record whether a document contains images (`<img>` present). This feeds
the image-only-page drop rule (section 4.7).

## 3. Element map

Walk each document in document order. Elements nested inside a handled
`blockquote` (headings, paragraphs inside quotes) are covered by the
blockquote handler and must not also emit their own blocks. `script` and
`style` elements are removed before walking and never produce text.

| Source element | Block kind | Notes |
|---|---|---|
| `h1`, `h2`, `h3` | heading, level 1-3 | level equals the digit |
| `h4`, `h5`, `h6` | heading, level 3 | clamped; the model allows levels 1-3 only |
| `p` | para, or break | break when the whole text matches the divider rule (section 6); image-only `p` (for example `<p><img/></p>`) yields empty text and falls to the empty-paragraph drop |
| `blockquote` | quote | whole element becomes ONE quote block; inner paragraphs are not separate blocks; text is the flattened text with single-space separators |
| `hr` | break | unconditional scene break |
| `div`, `section`, `article` | para, or break, or skipped | fallback for div-based books: only leaf containers (no `p`/`h1`-`h6`/`blockquote` descendants, not themselves inside `p`/`h1`-`h3`/`blockquote`) become paras; empty ones are skipped silently; divider-matching text becomes a break |
| `sup` | stripped, not a block | ALL `sup` elements are removed before block extraction and counted as footnote strips |
| `a` | stripped when footnote-like, else kept | a link is removed only when its `href` contains one of `footnote`, `footnotes`, `fn`, `endnote`, `note`, `ref` (case-insensitive) AND its text matches `^[\[\(\*†‡§]?\s*\d{1,3}\s*[\]\)\*†‡§]?$` or is at most 4 chars long; counted as footnote strips |
| `em`, `i` | italic span | character offsets into the cleaned block text (section 7) |
| `strong`, `b` | bold span | same; nesting `em` inside `strong` (or reverse) yields two independent spans |
| `img` and all other inline elements | no block, no span | images contribute no text; other inline tags pass their text through with no styling |

Empty headings and empty `p` results are NOT silent: they become empty-text
blocks at walk time and are dropped with a log entry at cleaning time
(`dropped empty heading`, `dropped empty paragraph`). Empty `div` fallbacks
are skipped silently (no log). IN4 must reproduce this logging split because
the import report (IN7) reads the drop log.

## 4. Drop rules

All drops are recorded as human-readable strings in one ordered drop list
(Scribe: `ExtractionResult.drops`; the draft report reads this list). The
wording shapes below are normative for parity of the import report; exact
punctuation may follow the Kotlin logger idiom but each drop must name the
file and the reason.

4.1. Nav/TOC pages: any document flagged nav by rule 2.5 is dropped whole
(`dropped nav/TOC page`), even if it contains text.

4.2. Empty paragraphs and empty headings: block text that is empty after
normalization is dropped (`dropped empty paragraph`, `dropped empty heading`).
Break blocks are exempt: they carry no text by design.

4.3. Boilerplate: a para or quote whose normalized text (case-insensitive) contains
any of these hints is dropped (`dropped boilerplate paragraph` with a
60-char preview): `copyright`, the copyright sign (U+00A9), `(c)`,
`all rights reserved`, `isbn`, `project gutenberg`, `gutenberg ebook`,
`distributed proofreading`, `transcriber's`, `transcribers`. Note the hints
are substring matches, so a story sentence mentioning "copyright" would also
drop; that is Scribe behavior, kept for parity, and an open question (14.3)
whether IN4 should narrow it.

4.4. Footnote markers: counted at strip time (section 3, `sup` plus
footnote-like `a`) and reported once per document
(`stripped N footnote marker(s)`). The stripped text is gone; surrounding
words join with the normal single-space collapse, so `trail.<marker> And`
becomes `trail. And` with no missing or doubled space.

4.5. Leftover dividers: a para whose text matches the break rule (section 6)
but was not classified at walk time is kept as a `break` block, not dropped.

4.6. Empty pages: after drops, a document with no remaining
heading/para/quote block carrying non-blank text is dropped whole:
(`dropped image-only page (no text)`) when the document had images,
else (`dropped empty page (no text blocks)`).

4.7. Spine-level skips (non-linear, unreadable, parse failures, footnote
strip counts) go through the same drop list, in spine order before
per-document drops, so the import report shows one chronological channel.

## 5. Chapter titles and the tiny-merge

5.1. Title priority per chapter: TOC title (rule 2.4), else the first
heading text in the document, else `Chapter N` where N is the FINAL 1-based
chapter index assigned after merging (not the spine position). Test shape:
an untitled third chapter becomes `Chapter 3`.

5.2. Tiny threshold: `TINY_CHAPTER_WORDS = 200`. Word count is
whitespace-separated tokens over heading/para/quote block texts
(`count_words`: empty counts 0; otherwise `text.split()` length). Break
blocks contribute 0 words.

5.3. Merge direction: accumulate sub-threshold chapters in a pending list;
when a chapter at or above threshold arrives, prepend all pending blocks to
it (tiny chapters come first, in order) and keep THAT chapter's href and
title. A trailing run of tiny chapters with no full chapter after them folds
backward into the last kept chapter. If every chapter is tiny, combine all
into one chapter titled with the last non-null title (else the first).
Each merge writes a drop entry naming merged and target chapters with word
counts (`merged tiny chapter(s) ... into next/previous chapter`).

5.4. Single-chapter books skip merging entirely (a lone tiny chapter stays).

5.5. After merging, chapters renumber 1..K in order; block ids restart per
chapter at 1 in document order; sentence ids restart per chapter at 1
(sections 8 and 10 renumber after their splits).

## 6. Normalization and the break rule

6.1. Normalization (`normalize_text`): NFC-normalize (Unicode form C, so
`e` plus combining acute becomes the single code point U+00E9), then collapse
every run of whitespace (spaces, tabs, newlines) to one ASCII space, then
strip leading/trailing whitespace. Curly quotes are preserved, never
straightened. Nothing else is expanded or substituted (no ligature, dash or
ellipsis rewriting; pronunciation fixes live in Scribe's `pronounce.yaml`
and are out of Slice 9 scope).

6.2. The same collapse runs twice: once per segment during span extraction
(section 7, so combining-mark length changes never shift later span
offsets) and once over the whole block text at cleaning time. Cleaning then
re-validates spans, keeping only those with `0 <= start <= end <= len(text)`.

6.3. Break (divider) rule, exact: strip the text; reject when empty or longer
than 12 characters; accept when every character is in the divider set AND at
least one mark from the must-contain set is present. Divider set: whitespace,
`*`, U+2042, hyphen-minus, en dash (U+2013), em dash (U+2014), tilde, middle
dot (U+00B7), bullet (U+2022), white bullet (U+25E6), underscore. Regex form:
`^[\s*\u2042\-\u2014\u2013~\u00b7\u2022\u25e6_]+$` with length at most 12.
Must-contain set: `*`, U+2042, U+2022, U+25E6, U+00B7. Effect: `* * *` and a
lone U+2042 are breaks; a plain `---` or `___` line is NOT (no must-contain
mark); `She left early.` is not. Break blocks carry no text and no sentences.

## 7. Italic/bold spans

7.1. Sources: `em`/`i` give `italic`, `strong`/`b` give `bold`, detected by
ancestor walk (a text node inside `<em><span>x</span></em>` is italic). All
other elements give no style.

7.2. Whitespace-collapse inheritance: runs of whitespace collapse to one
space, and that space inherits the style of the PRECEDING character (or the
following run's own style at text start), so `<em>foo bar</em>` stays one
span across the inner space. Leading whitespace emits no space.

7.3. Edge trim and order: after collapsing, each maximal styled run becomes
one span with leading/trailing whitespace trimmed from its edges
(`<em>foo</em> bar` spans exactly `foo`); empty-after-trim runs emit nothing.
Spans sort by `(start, end)`.

7.4. Span offsets are always relative to their own sentence's text (bundle
contract), so every split (sections 8, 10) clips crossing spans into each
side and rebases to sentence-local offsets. A span fully inside one sentence
keeps its relative position; a span crossing a boundary appears (clipped) in
both sentences; nested italic+bold on the same range stays two independent
spans. Out-of-range spans after cleaning are dropped (rule 6.2).

## 8. Sentence splitting

Baseline: Scribe uses pysbd (English, `clean=False`) to propose candidates,
then applies the post-fixes below. The Kotlin port (IN5) starts from the
platform sentence iterator (`java.text.BreakIterator.getSentenceInstance`
with English locale, API 24 safe, no desugaring) plus these same post-fixes
(decision D-080). Only if run-level parity (plan decision 2) is poor does
IN5 port a fuller rule set. IN5 must also report sentence-boundary agreement
as a percentage, never pass/fail.

8.1. Abbreviation merge: when the text before a candidate boundary ends with
one of `Mr.` `Mrs.` `Ms.` `Dr.` `St.` `Sr.` `Jr.` `Prof.` `Rev.` `Vs.`/`vs.`
(case-insensitive) or Latin `e.g.`/`i.e.`, drop the boundary (glue to the
next piece). The match anchor also accepts an opening quote or bracket just
before the abbreviation, so quote-first dialogue (`"Mr.`, curly-open `J.`)
merges too. Chains re-check: `J. K. Rowling` merges twice into one sentence.

8.2. Initials merge: a candidate ending in a single capital letter plus
period (`A.`-`Z.`, same quote/bracket anchor) merges forward. Known
limitation, kept: `St.` ambiguity (Saint vs Street) and a genuine
sentence-final single letter (a grade `A.`) also merge; rare in books,
harmless for continuity.

8.3. Deliberate exclusions: `No.` and `etc.` NEVER merge. A sentence-final
`No.` is a real ending; the number form (`No. 12`) stays together from the
baseline splitter without a rule. `etc.` routinely ends sentences
(`apples, pears, etc. She left.` stays split).

8.4. Ellipsis: a bare `...` or U+2026 never ends a sentence here
(`She trailed off... Then she left.` stays one sentence; chains like
`He left... She stayed... They met.` stay whole). Ellipsis plus closing
quotes (`..."`, curvies) merges ONLY when the next piece visibly continues
the same breath: first non-space character is a lowercase ASCII letter
(`"Wait..." she whispered` merges; `He said "Wait..." Then he left.` stays
split because the quote closed and a capitalized sentence follows).

8.5. Quotation attachment: consider only straight `"..."` and curly
U+201C/U+201D pairs; single quotes are ignored at this stage (apostrophe
ambiguity; dialogue splitting proper is section 10). Straight quotes pair
sequentially (1st with 2nd, 3rd with 4th); curly openers pair with the next
closer with depth counting, so same-type nesting collapses to the outer
region. A quoted region holding MORE than 3 sentences (`_MAX_QUOTE_SENTENCES`)
is split inside (boundaries added after its first inner sentence onward, so
TTS chunks stay small); any shorter quoted region is never split and stays
attached to surrounding narration. Unbalanced quotes: a paragraph with NO
intact pair splits as plain narration; leftover strays (lone inch-mark as in
`5" nail`, one half of multi-paragraph dialogue) are ignored while intact
pairs are still honored. All unbalanced cases are logged, never errors.

8.6. Headings: never split; one sentence each, text kept as-is. Blank input
(whitespace only) yields zero sentences.

8.7. Alignment fallback: candidates map back onto the paragraph by ordered
substring search; when a segment cannot be found in order, or gaps between
segments hold non-whitespace, keep the whole paragraph as one sentence and
log a warning. Text is never lost or reordered by the splitter.

8.8. Sentence ids run consecutively from 1 across the whole chapter (all
blocks); block ids are preserved (headings count for numbering even though
they hold one sentence; breaks hold none).

## 9. Dialogue detection (narration vs dialogue)

State machine over each paragraph producing `narration`/`dialogue` records
with quote keys `(chapter, block, quote)` numbered in open-quote order per
paragraph. Multi-sentence quotes stay ONE quote: every sentence inside shares
the key. Only `para`/`quote` blocks are scanned; headings stay single
narration sentences; breaks carry none. This section ports as-is in IN6;
speaker attribution (who speaks) is explicitly out of Slice 9 (two-voice
mode needs only the kind).

9.1. Mode select: double-quote mode when the paragraph contains ANY of `"`,
U+201C, U+201D; else single-quote mode (`'`, U+2018, U+2019). In double mode,
singles are apostrophes or nested emphasis belonging to the outer quote and
never open regions.

9.2. Apostrophes are never boundaries: a single-quote character with a word
character (letter, digit or `_`) on BOTH sides (`man's`, curly `Jack's
tale`) is skipped. Limitation, kept: a trailing possessive like `dogs'`
(letter before, space after) still reads as a closer in single-mode
paragraphs; rare in books, accepted.

9.3. Inch-mark rule: `"` immediately after a digit (`5" nail`) never opens
or closes; outside a quote it logs a stray, inside it is ignored. Corner
cost, kept: a straight-quoted region ending in a digit (`"count 730"`)
reads as unclosed and falls back to narration at chapter end; prose
virtually never ends dialogue on a digit.

9.4. Empty pairs (`""` and equivalents) never form regions and log a stray.

9.5. Multi-paragraph quotes: a paragraph ending with an open (no closer)
continues into the next paragraph, which normally starts with another opener
consumed as a continuation marker (the marker character stays in the text).
A quoteless continuation paragraph (no quote characters at all, still
inside) counts as continued dialogue. Continuation quotes carry
`continued=true`. An unclosed chain at chapter end is unbalanced: log a
warning and flip the whole trailing chain (linked by continued starts) back
to narration with no quotes.

9.6. Scare-quote rule (emphasis, NOT dialogue): a CLOSED quoted fragment
(single or double) is narration emphasis when ALL hold: (a) inner text has
no sentence-terminal mark (`.`, `?`, `!`, U+2026, or the three-char `...`);
(b) it does not end with a comma (trailing comma signals a dialogue tag
continuation as in `"Hi," she said`); (c) it has at most 2
whitespace-separated words (`SCARE_MAX_WORDS = 2`). Empty fragments count as
scare (and log a stray). Gold shape: `hideous` and `rehearsal` fragments
contribute zero quotes. Trailing-open regions (continuations) skip this
filter and stay dialogue. Limitation, kept: punctuation-less one-word
dialogue (`"Hello"` with no comma or period) reads as scare; books virtually
always punctuate dialogue.

9.7. Mixed sentences split at quote boundaries: `"We should leave," she
said.` becomes `"We should leave,"` (dialogue, quote 1) plus ` she said.`
(narration). Each segment is then sentence-split with the section 8 splitter
(reused, never forked), so abbreviation and ellipsis fixes keep working
inside both kinds. Halves of one original sentence share a paragraph-local
`split_pair` id (assembly reads it for the short tag pause); separate
sentences never share one. Pair rules: a narration segment starting with a
lowercase letter after a dialogue record opens (or adopts, when the dialogue
already holds one for tags on both sides) a pair; a narration tag ending in
`,` or `:` extends its pair through to the following quote. Uppercase starts
mean a new sentence, no pair.

9.8. Orphan marks: a piece holding only quote marks/whitespace (a closer the
splitter left standing alone) reattaches to the previous record when it
shares kind and quote, else carries forward so a dialogue opener never lands
on narration text.

9.9. Whitespace-only gaps between quotes attach to the previous sentence, or
prefix the next segment when leading, so no space is ever lost.

9.10. Logging: unbalanced/stray situations log warnings (`unbalanced`,
`stray`, `unclosed`); intact pairs are still honored when a stray shares the
paragraph. No intact dialogue quotes means all narration.

9.11. Span and page provenance: split pieces rebase spans exactly like
section 7.4. Source page (PDF provenance, `None` for EPUB) is inherited by
every piece of its paragraph; headings infer the next paged page, else the
previous record's page, else `None`.

## 10. The spacing rule (normative)

10.1. Sentence `text` keeps its original spacing: the gap after a sentence
is stored as TRAILING whitespace of that sentence, so `"".join(sentence
texts)` reproduces the source paragraph EXACTLY, byte for byte, including
double spaces, tabs, newlines and leading/trailing whitespace. Both
splitters (sections 8 and 9) guarantee this; every test asserts it.

10.2. Consumers needing clean strings (TTS, display of a single sentence)
`strip()` at use time. No separate bundle field carries spacing; the bundle
format is unchanged by this rule.

10.3. The Player renders by concatenation: sentences join exactly as stored
(`ParagraphLayout.kt`: append each sentence text in order). Fallback for
non-Scribe sources only: when a stored sentence lacks trailing whitespace
and the next lacks leading whitespace mid-block, the renderer inserts one
space so words from trimmed hand-made bundles never run together. Scribe
output already carries its separator, so the fallback never fires for
ingested books; the Kotlin ingester must therefore write Scribe-shaped
spacing (trailing gaps stored, never trimmed) so the fallback stays dormant.

10.4. Span offsets count every stored character INCLUDING the trailing
spacing (a span never covers the trailing gap in practice, but offsets are
measured over the stored string).

## 11. Reference: identifiers and artifacts (later packages own these)

11.1. Scribe book id: `uuid5(NAMESPACE_URL, "auloud:book:" + sha256_hex)`
over the lowercase hex SHA-256 of the source file bytes (streamed 1 MiB
chunks). Same bytes always give the same id, which keeps Player progress.
Device-namespace ids (plan decision 6, so a PC bundle of the same EPUB does
not clobber the on-device book) are IN1/IN7 work; this document records only
the Scribe scheme it must differ from.

11.2. Scribe draft artifacts per book (`<work-dir>/<book-id>/`):
`script.json` (tagged text with `kind`, `quote`, `confidence`,
`split_pair`), `cast.yaml` (edited by the user, never relevant on device),
`cast_report.md`, `draft_report.md` (chapter list, word counts, drops,
rough duration estimate at about 15 chars/sec). Deterministic: no timestamps,
so two drafts of one file are byte-identical. Attribution, casting and audio
are out of Slice 9.

## 12. Edge-case index

A checklist for IN4-IN6 tests, each traceable above: exact-stem nav vs
`naval-history` (2.5); non-linear skip (2.2); unreadable-item skip (2.3);
`sup` plus short footnote-link strips joining words cleanly (3, 4.4);
boilerplate substring behavior (4.3); image-only vs empty page wording (4.6);
`Chapter N` final-index titling (5.1); forward, backward and all-tiny merges
(5.3); divider lengths 0-12 vs 13+, plain dashes without must-contain marks
(6.3); NFC combining-mark offsets (6.1, 7.2); cross-boundary and nested spans
(7.4); abbreviation anchors after opening quotes (8.1); `No.`/`etc.`
exclusions (8.3); ellipsis-plus-quote lowercase vs capitalized continuation
(8.4); 3-sentence attach vs 4-sentence split quotes (8.5); single-quote
paragraphs vs nested singles (9.1); inch marks and digit-ending quotes (9.3);
multi-paragraph continuation with and without opener, quoteless continuation,
chapter-end flip (9.5, 9.9); scare fragments with terminals, commas and word
counts 1-3 (9.6); mixed-sentence pair open/adopt/extend (9.7); orphan closers
(9.8); round trip on double spaces, tabs, newlines, padding (10.1).

## 13. Open questions (do NOT invent; IN4-IN6 decide or ask the user)

13.1. Cover lookup: this document covers text extraction only. How Scribe
locates the cover image (OPF metadata vs first image) was not traced here;
IN3/IN7 must read the writer path and spec before implementing.

13.2. EPUB metadata (title, author, language): Scribe reads OPF metadata in
the draft path, but the exact missing-field fallbacks and the non-English
warning behavior were not traced here; IN3 decides, asking the user on gaps.

13.3. Boilerplate narrowing (rule 4.3): substring hints can drop story
sentences mentioning e.g. "copyright". Kept as-is for parity; narrowing
needs real-book evidence, not guessing.

13.4. Accepted limitations carried over unchanged: `St.` ambiguity and
sentence-final single letters (8.2); trailing possessives in single mode
(9.2); digit-ending straight dialogue (9.3); punctuation-less one-word
dialogue reading as scare (9.6); lowercase-after-terminal misread as tag
(`"Hi." she left`, 9.7); `div`-fallback behavior on real-world EPUBs beyond
the tested shapes (3).

## 14. Traceability

| Rule | Scribe location | Test |
|---|---|---|
| Spine order, non-linear skip, notices | `extract/epub.py: read_spine_documents` | `test_extract_epub.py: test_non_linear_spine_items_skipped` |
| Exact-stem nav detection | `extract/epub.py: _is_nav_item` | `test_extract_epub.py: test_naval_history_chapter_survives` |
| TOC title map, first-wins basename | `extract/epub.py: toc_title_map` | `test_extract_epub.py: test_mini_epub_chapters_blocks_spans` (titles) |
| Element map, blockquote collapse, div fallback | `extract/epub.py: parse_html_blocks` | `test_extract_epub.py: test_mini_epub_chapters_blocks_spans` |
| Footnote strip (`sup`, short links) | `extract/epub.py: _strip_footnotes` | `test_extract_epub.py: test_drops_include_footnote_strips` |
| Span build, collapse inheritance, edge trim | `extract/epub.py: _text_and_spans` | `test_extract_epub.py` (italic/bold offsets 9-14, 19-27) |
| Drops, titles, `Chapter N` | `extract/clean.py: _clean_document` | `test_extract_epub.py: test_mini_epub_chapters_blocks_spans` |
| Boilerplate hints, break test, word count | `extract/clean.py: is_boilerplate, is_break_text, count_words` | `test_extract_epub.py: test_boilerplate_and_break_and_words` |
| Normalization (NFC, collapse, curly kept) | `extract/clean.py: normalize_text` | `test_extract_epub.py: test_normalize_keeps_curly_quotes` |
| Tiny merge 200, forward/backward/all-tiny | `extract/clean.py: _merge_tiny, TINY_CHAPTER_WORDS` | `test_extract_epub.py: test_mini_epub_chapters_blocks_spans` (ch2 into ch3) |
| Chapter file shape, sids, placeholders | `extract/clean.py: _to_chapter_file` | `test_extract_epub.py` (sid sequence 1-4) |
| Abbrev/initial/ellipsis post-fixes | `text/sentences.py: _needs_merge, _continues_ellipsis_quote` | `test_sentences.py` (abbrev, initials, ellipsis groups) |
| `No.`/`etc.` exclusions, `e.g.`/`i.e.` | `text/sentences.py: _ABBREV_RE` docstring | `test_sentences.py` (No/vs/eg-ie/etc group) |
| Quote attach vs split (>3), regions | `text/sentences.py: _quote_regions, _MAX_QUOTE_SENTENCES` | `test_sentences.py` (quotation group) |
| Round trip, headings, sids, spans | `text/sentences.py: split_paragraph, split_chapter, _rebase` | `test_sentences.py` (round-trip, heading, span groups) |
| Mode select, apostrophes, nesting | `text/dialogue.py: split_paragraph_dialogue` | `test_dialogue.py` (single-quote, apostrophe groups) |
| Multi-paragraph continuation, chapter-end flip | `text/dialogue.py: split_chapter_dialogue` | `test_dialogue.py` (multiparagraph, unclosed groups) |
| Scare rule, `SCARE_MAX_WORDS = 2` | `text/dialogue.py: is_scare_quote` | `test_dialogue.py` (scare group, block-12 shape) |
| Mixed split, re-split, split_pair, orphans | `text/dialogue.py: split_paragraph_dialogue` | `test_dialogue.py` (mixed, gold-shape groups) |
| Spacing guarantee | `text/sentences.py` + `text/dialogue.py` docstrings | `test_sentences.py` + `test_dialogue.py` (round-trip groups); Player consumer `ParagraphLayout.kt` |
| Book id scheme | `draft.py: BOOK_ID_NAMESPACE, BOOK_ID_PREFIX, sha256_of_file` | `test_draft.py` (id determinism) |
