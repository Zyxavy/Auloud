# Auloud Scribe — turns ebooks into multi-voice audiobooks (PC tool).
# Copyright (C) 2026 Zyxavy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Dialogue detection and sentence splitting (Slice 4 MV2).

State machine over paragraphs that finds quoted speech, splits mixed
sentences at quote boundaries, and tags each record ``narration`` or
``dialogue`` with its quote key ``(chapter, block id, quote number within
the block)`` in open-quote order (matching the ``speakers-gold`` anchor).

Rules (MV2):

- Double quotes: straight ``"`` plus curly ``“``/``”``. Single quotes
  (``'``/``‘``/``’``) count as dialogue delimiters ONLY in paragraphs with
  no double-quote characters at all; in double-mode paragraphs singles are
  apostrophes or nested emphasis belonging to the outer quote.
- Apostrophes are never quote boundaries: a single-quote character with a
  word character (letter/digit) on BOTH sides (``man’s``, ``that’s``,
  ``Zimmerman’s``) is skipped. Limitation: trailing possessives like
  ``dogs'`` (letter before, space after) still read as a closer in
  single-mode paragraphs; rare in books, accepted for MV2.
- Multi-paragraph quotes: a paragraph ending with an open (no closer)
  continues into the next paragraph, which normally starts with another
  opener consumed as a continuation marker (the marker stays in the text).
  A quoteless continuation paragraph (no quote chars, still inside) counts
  as continued dialogue. Continuation quotes are flagged ``continued``;
  MV4 links them to keep the speaker. An unclosed chain at chapter end is
  unbalanced: it is logged and flipped to narration (see below).
- Multi-sentence quotes stay ONE quote: a balanced region spanning many
  sentences (gold block 11 quote 2) gets a single quote number; every
  sentence split inside it shares that key. Quote numbers count dialogue
  regions in open order, never sentences.
- Mixed sentences split at quote boundaries: ``"We should leave," she
  said.`` becomes ``"We should leave,"`` (dialogue, quote 1) plus
  `` she said.`` (narration). Each segment is then sentence-split with the
  existing :mod:`text.sentences` splitter (reused, never forked), so
  abbreviation/ellipsis fixes keep working inside both kinds. Halves of one
  original sentence share a paragraph-local ``split_pair`` id (MV7: the
  narration tag continues lowercase, or runs into the next quote with a
  comma/colon); separate sentences never share one, so assembly can tell a
  same-sentence tag (short pause) from a new sentence (normal pause).
- Exact round trip: segments partition the paragraph byte-exactly, and each
  segment round-trips through the sentence splitter, so ``"".join`` over a
  block's sentences reproduces the original paragraph EXACTLY (trailing
  spaces stored with sentences, per the Player spacing rule in
  ``ParagraphLayout.kt``: concatenate as stored). Whitespace-only gaps
  between quotes attach to the previous sentence (or prefix the next when
  leading), so no space is ever lost.
- Unbalanced/stray quotes: a lone closer (``”`` with no open, dangling
  ``"``), an inch-mark (``"`` after a digit, e.g. ``5" nail``), or an empty
  pair (``""``) never forms a region. Leftovers are logged via ``logging``
  and the paragraph (or, with intact pairs present, just the stray) is
  treated as narration: no intact dialogue quotes means all narration;
  intact quotes are still honored and only the stray is logged. Corner cost:
  a straight-quoted region ending in a digit (``"count 730"``) reads as
  unclosed and falls back to narration at chapter end; prose virtually
  never ends dialogue on a digit.
- Scare-quote rule (explicit): a CLOSED double- or single-quoted fragment
  is emphasis on narration, NOT dialogue, when ALL hold: (a) its inner text
  has no sentence-terminal punctuation (``.`` ``?`` ``!`` ``…`` or ``...``),
  (b) it does not end with a comma (a trailing comma signals a dialogue
  tag continuation such as ``"Hi," she said``), and (c) it has at most two
  whitespace-separated words. Gold block 12 (``“hideous”``, ``“rehearsal”``)
  contributes zero quotes under this rule. Limitation: punctuation-less
  one-word dialogue (``"Hello"`` with no comma or period) reads as scare;
  books virtually always punctuate dialogue, so this is accepted for MV2.
  Trailing-open regions (multi-paragraph continuation) skip the scare
  filter and stay dialogue.

Only ``para``/``quote`` blocks are scanned; headings stay single-sentence
narration and breaks carry no sentences. Draft block ids are preserved
(headings count); sids renumber consecutively from 1 across the chapter.
Out of scope: MV3+ attribution, cast.yaml, thought-vs-speech, dash-style
dialogue, non-English text.
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from dataclasses import dataclass, field

from bundle.models import Block, ChapterFile, Sentence, Span
from text.sentences import split_paragraph as _split_sentences

logger = logging.getLogger(__name__)

#: Sentence kinds produced by this module.
NARRATION = "narration"
DIALOGUE = "dialogue"

#: Scare-quote filter: at most this many words without terminal punctuation.
SCARE_MAX_WORDS = 2

#: Characters that mark a fragment as real speech (never scare quotes).
_TERMINALS = (".", "?", "!", "\u2026")

_LEFT_DOUBLE = "\u201c"
_RIGHT_DOUBLE = "\u201d"
_LEFT_SINGLE = "\u2018"
_RIGHT_SINGLE = "\u2019"

_DOUBLE_CHARS = ('"', _LEFT_DOUBLE, _RIGHT_DOUBLE)


def is_scare_quote(inner: str) -> bool:
    """True when a closed quoted fragment is emphasis, not dialogue.

    ``inner`` is the text between the outer quotes (no markers). Empty
    fragments count as scare (callers additionally log them as stray).
    See the module docstring for the exact rule and its limitation.
    """
    stripped = inner.strip()
    if not stripped:
        return True
    if "..." in stripped:
        return False
    if any(mark in stripped for mark in _TERMINALS):
        return False
    if stripped.endswith(","):
        return False
    return len(stripped.split()) <= SCARE_MAX_WORDS


@dataclass
class _Region:
    """One balanced quote region as paragraph offsets (close exclusive)."""

    start: int
    end: int
    continued: bool = False
    closed: bool = True


@dataclass(frozen=True)
class QuoteInfo:
    """One dialogue quote: stable key plus its stored text."""

    chapter: int
    block: int
    quote: int
    text: str
    continued: bool = False

    @property
    def key(self) -> tuple[int, int, int]:
        """Stable anchor ``(chapter, block, quote)`` matching the gold."""
        return (self.chapter, self.block, self.quote)


@dataclass
class TaggedSentence:
    """One split sentence plus its MV2 dialogue tag.

    ``split_pair`` (MV7) links the two halves of one original sentence
    split at a quote boundary: the dialogue half and its narration tag
    share a paragraph-local id (``None`` everywhere else). Assembly reads
    it for the short (~100 ms) tag pause; a shared id never crosses a
    block boundary, so ids stay local to one paragraph split.
    """

    sid: int
    chapter: int
    block: int
    text: str
    spans: list[Span]
    speaker: str
    kind: str
    quote: int | None = None
    continued: bool = False
    split_pair: int | None = None

    @property
    def key(self) -> tuple[int, int, int] | None:
        """Quote key for dialogue records, else None."""
        if self.kind == DIALOGUE and self.quote is not None:
            return (self.chapter, self.block, self.quote)
        return None


@dataclass
class ParagraphDialogue:
    """Result of splitting one paragraph at quote boundaries."""

    sentences: list[TaggedSentence] = field(default_factory=list)
    quotes: list[QuoteInfo] = field(default_factory=list)
    out_quote: bool = False
    next_quote: int = 1


@dataclass
class ChapterDialogue:
    """Result of splitting one chapter: bundle-safe plus dialogue tags."""

    chapter: ChapterFile
    tagged: list[TaggedSentence] = field(default_factory=list)
    quotes: list[QuoteInfo] = field(default_factory=list)


def _is_word_char(char: str) -> bool:
    return bool(char) and (char.isalnum() or char == "_")


def _scan_doubles(text: str, *, in_quote: bool) -> tuple[list[_Region], bool, bool]:
    """Scan double-quote mode; returns ``(regions, out_quote, stray)``."""
    regions: list[_Region] = []
    stray = False
    in_q = in_quote
    depth = 1 if in_quote else 0
    open_idx = 0 if in_quote else -1
    continued = bool(in_quote)
    scan_from = 0
    if in_quote:
        stripped = text.lstrip()
        lead = len(text) - len(stripped)
        if stripped and stripped[0] in ('"', _LEFT_DOUBLE):
            scan_from = lead + 1
        else:
            scan_from = 0
    for i in range(scan_from, len(text)):
        char = text[i]
        if char == _LEFT_DOUBLE:
            if not in_q:
                in_q = True
                depth = 1
                open_idx = i
                continued = False
            else:
                depth += 1
        elif char == _RIGHT_DOUBLE:
            if not in_q:
                stray = True
            elif depth > 1:
                depth -= 1
            else:
                regions.append(_Region(open_idx, i + 1, continued, True))
                in_q = False
                depth = 0
                open_idx = -1
                continued = False
        elif char == '"':
            prev = text[i - 1] if i > 0 else ""
            nxt = text[i + 1] if i + 1 < len(text) else ""
            if prev.isdigit():
                if not in_q:
                    stray = True
                continue
            if not in_q:
                if nxt and not nxt.isspace():
                    in_q = True
                    depth = 0
                    open_idx = i
                    continued = False
                else:
                    stray = True
            else:
                regions.append(_Region(open_idx, i + 1, continued, True))
                in_q = False
                depth = 0
                open_idx = -1
                continued = False
    out_quote = in_q
    if in_q:
        regions.append(_Region(open_idx, len(text), continued, False))
    return regions, out_quote, stray


def _scan_singles(text: str, *, in_quote: bool) -> tuple[list[_Region], bool, bool]:
    """Scan single-quote mode (no doubles in paragraph). Apostrophes skip."""
    regions: list[_Region] = []
    stray = False
    in_q = in_quote
    depth = 1 if in_quote else 0
    open_idx = 0 if in_quote else -1
    continued = bool(in_quote)
    scan_from = 0
    if in_quote:
        stripped = text.lstrip()
        lead = len(text) - len(stripped)
        if stripped and stripped[0] in ("'", _LEFT_SINGLE):
            scan_from = lead + 1
        else:
            scan_from = 0
    for i in range(scan_from, len(text)):
        char = text[i]
        if char not in ("'", _LEFT_SINGLE, _RIGHT_SINGLE):
            continue
        prev = text[i - 1] if i > 0 else ""
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if _is_word_char(prev) and _is_word_char(nxt):
            continue  # Apostrophe: never a boundary.
        if char == _LEFT_SINGLE:
            if not in_q:
                in_q = True
                depth = 1
                open_idx = i
                continued = False
            else:
                depth += 1
        elif char == _RIGHT_SINGLE:
            if not in_q:
                stray = True
            elif depth > 1:
                depth -= 1
            else:
                regions.append(_Region(open_idx, i + 1, continued, True))
                in_q = False
                depth = 0
                open_idx = -1
                continued = False
        else:  # Straight single quote.
            if not in_q:
                if nxt and not nxt.isspace():
                    in_q = True
                    depth = 1
                    open_idx = i
                    continued = False
                else:
                    stray = True
            else:
                regions.append(_Region(open_idx, i + 1, continued, True))
                in_q = False
                depth = 0
                open_idx = -1
                continued = False
    out_quote = in_q
    if in_q:
        regions.append(_Region(open_idx, len(text), continued, False))
    return regions, out_quote, stray


def _opener_index(text: str, region: _Region) -> int | None:
    """Index of the opening quote mark, or None for a quoteless continuation."""
    if region.continued:
        rest = text[region.start :].lstrip()
        if rest and rest[0] in ('"', "'", _LEFT_DOUBLE, _LEFT_SINGLE):
            return len(text) - len(rest)
        return None
    return region.start


def _inner_text(text: str, region: _Region) -> str:
    """Text between the outer quote marks of a region (no markers)."""
    if not region.closed:
        opener = _opener_index(text, region)
        return text[region.start :] if opener is None else text[opener + 1 :]
    opener = _opener_index(text, region)
    if opener is None:
        return text[region.start : region.end - 1]
    return text[opener + 1 : region.end - 1]


def _slice_spans(spans: Sequence[Span] | None, low: int, high: int) -> list[Span]:
    """Spans intersecting ``[low, high)``, rebased to segment-local offsets."""
    rebased: list[Span] = []
    for span in spans or []:
        start = max(span.start, low)
        end = min(span.end, high)
        if start < end:
            rebased.append(Span(start=start - low, end=end - low, style=span.style))
    return rebased


#: Characters that on their own carry no speech (a sentence splitter may
#: leave a closing mark as its own piece, e.g. ``'Hello there.'`` splitting
#: off a lone ``'``). Such orphans reattach to the previous record so TTS
#: never reads a bare quote mark and sids stay gap-free.
_ORPHAN_MARKS = frozenset(("'", '"', _LEFT_DOUBLE, _RIGHT_DOUBLE, _LEFT_SINGLE, _RIGHT_SINGLE))


def _starts_continuation(segment: str) -> bool:
    """True when a narration segment continues the previous sentence.

    The test is the first non-space character: lowercase means the tag
    runs on from the dialogue (``'"Hi," she said'``), uppercase means a
    new sentence (``'"Hi." She smiled'``). Limitation: a new sentence that
    starts lowercase after terminal punctuation (``'"Hi." she left'``)
    misreads as a tag; real prose capitalizes there, while tags after
    ``!``/``?`` (``'"Run!" he shouted'``) are common, so the lowercase
    side carries the decision.
    """
    stripped = segment.lstrip()
    return bool(stripped) and stripped[0].islower()


def _ends_continuation(segment: str) -> bool:
    """True when a narration segment runs into the following quote.

    A trailing comma or colon (``'She said, '``, ``'He shouted: '``)
    hands the sentence to the quote; terminal punctuation means the tag
    (if any) is behind, not ahead.
    """
    return segment.rstrip().endswith((",", ":"))


def _is_quote_orphan(text: str) -> bool:
    """True when ``text`` is only quote marks/whitespace (reattach it)."""
    stripped = text.strip()
    return bool(stripped) and all(char in _ORPHAN_MARKS for char in stripped)


def split_paragraph_dialogue(
    text: str,
    spans: Sequence[Span] | None = None,
    *,
    chapter: int = 1,
    block: int = 1,
    speaker: str = "narrator",
    start_sid: int = 1,
    start_quote: int = 1,
    in_quote: bool = False,
) -> ParagraphDialogue:
    """Split one paragraph at quote boundaries and tag each record.

    Returns :class:`ParagraphDialogue` with sentence records (sids from
    ``start_sid``), quote entries (numbers from ``start_quote``), the
    ``out_quote`` carry for the next paragraph, and ``next_quote``.
    ``"".join(s.text)`` over the result always equals ``text`` exactly.
    """
    result = ParagraphDialogue(next_quote=start_quote)
    if not text.strip():
        result.out_quote = in_quote
        result.next_quote = start_quote
        return result
    use_singles = not any(char in text for char in _DOUBLE_CHARS)
    if use_singles:
        regions, out_quote, stray = _scan_singles(text, in_quote=in_quote)
    else:
        regions, out_quote, stray = _scan_doubles(text, in_quote=in_quote)
    result.out_quote = out_quote
    dialogue: list[tuple[_Region, int]] = []
    quote_no = start_quote
    saw_empty = False
    for region in regions:
        if not region.closed:
            dialogue.append((region, quote_no))
            quote_no += 1
            continue
        inner = _inner_text(text, region)
        if not inner.strip():
            saw_empty = True
            continue
        if is_scare_quote(inner):
            continue
        dialogue.append((region, quote_no))
        quote_no += 1
    result.next_quote = quote_no
    if not dialogue:
        if stray or saw_empty:
            logger.warning("unbalanced quotes; treating paragraph as narration: %r", text[:60])
        sentences = _split_sentences(text, list(spans or []), speaker=speaker, start_sid=start_sid)
        for sent in sentences:
            result.sentences.append(
                TaggedSentence(
                    sid=sent.sid,
                    chapter=chapter,
                    block=block,
                    text=sent.text,
                    spans=list(sent.spans),
                    speaker=sent.speaker,
                    kind=NARRATION,
                )
            )
        return result
    if stray or saw_empty:
        logger.warning("stray quote characters ignored; intact quotes honored: %r", text[:60])
    for region, number in dialogue:
        result.quotes.append(
            QuoteInfo(
                chapter=chapter,
                block=block,
                quote=number,
                text=text[region.start : region.end],
                continued=region.continued,
            )
        )
    bounds: list[int] = [0, len(text)]
    for region, _number in dialogue:
        bounds += [region.start, region.end]
    bounds = sorted(set(bounds))
    # Segment edges that touch a dialogue region: a segment starting where
    # a region ends follows its quote; one ending where a region starts
    # precedes it. MV7 split pairs are assigned off these junctions.
    region_end_at = {region.end: region for region, _ in dialogue}
    region_start_at = {region.start: region for region, _ in dialogue}
    tagged: list[TaggedSentence] = []
    next_sid = start_sid
    pending_leading = ""
    carry_forward = ""
    pair_seq = 0
    pending_pair: int | None = None
    for low, high in zip(bounds, bounds[1:]):
        if high <= low:
            continue
        seg = text[low:high]
        active: tuple[_Region, int] | None = None
        for region, number in dialogue:
            if region.start <= low < region.end:
                active = (region, number)
                break
        if not seg.strip():
            if tagged:
                tagged[-1].text += seg
            else:
                pending_leading += seg
            continue
        seg_spans = _slice_spans(spans, low, high)
        if pending_leading:
            seg = pending_leading + seg
            seg_spans = [
                Span(s.start + len(pending_leading), s.end + len(pending_leading), s.style)
                for s in seg_spans
            ]
            pending_leading = ""
        pieces = _split_sentences(seg, seg_spans, speaker=speaker, start_sid=1)
        if not pieces:
            if tagged:
                tagged[-1].text += seg
            else:
                pending_leading += seg
            continue
        # MV7 split pair: the id this segment's first/last records share
        # with the dialogue half of their original sentence (None: no
        # same-sentence neighbour on that side). Dialogue records consume
        # a pair opened by a trailing-comma tag (``'She said, '``);
        # narration records open one when they continue a quote
        # (lowercase start, ``'"Hi," she said'``) and extend it through a
        # trailing comma into the next quote.
        first_pair: int | None = None
        if active is not None:
            first_pair, pending_pair = pending_pair, None
        elif pending_pair is not None:
            # Defensive only: a pair always precedes its dialogue segment,
            # so a narration segment must never see one pending. Drop it
            # rather than leak an id across unrelated sentences.
            pending_pair = None
        elif region_end_at.get(low) is not None and _starts_continuation(seg):
            prev = tagged[-1] if tagged else None
            if prev is not None and prev.kind == DIALOGUE:
                if prev.split_pair is not None:
                    # Tag on both sides of one quote (``'She said, "Hi,"
                    # she added'``): the whole sentence shares the pair
                    # the dialogue already holds, so adopt it, never fork.
                    first_pair = prev.split_pair
                else:
                    pair_seq += 1
                    first_pair = pair_seq
                    prev.split_pair = pair_seq
        base = len(tagged)
        for sent in pieces:
            record_kind = DIALOGUE if active is not None else NARRATION
            record_quote = active[1] if active is not None else None
            record_continued = active[0].continued if active is not None else False
            if _is_quote_orphan(sent.text):
                # A bare mark split off its sentence (usually a closer).
                # Reattach backwards when the previous record shares the
                # same voice (same segment); otherwise carry it forward so
                # a dialogue opener never lands on narration text.
                if tagged and tagged[-1].kind == record_kind and tagged[-1].quote == record_quote:
                    base = len(tagged[-1].text)
                    tagged[-1].text += sent.text
                    tagged[-1].spans += [
                        Span(s.start + base, s.end + base, s.style) for s in sent.spans
                    ]
                else:
                    carry_forward += sent.text
                continue
            text_out = carry_forward + sent.text
            spans_out = [
                Span(s.start + len(carry_forward), s.end + len(carry_forward), s.style)
                for s in sent.spans
            ]
            carry_forward = ""
            tagged.append(
                TaggedSentence(
                    sid=next_sid,
                    chapter=chapter,
                    block=block,
                    text=text_out,
                    spans=spans_out,
                    speaker=sent.speaker,
                    kind=record_kind,
                    quote=record_quote,
                    continued=record_continued,
                    split_pair=first_pair if len(tagged) == base else None,
                )
            )
            next_sid += 1
        if active is None and tagged[base:]:
            # A narration tag running into the next quote (``'She said, "Hi"'``)
            # shares its original sentence with that quote: extend the pair
            # (reusing the left one when this segment opened one, as in
            # ``'"Hi," she said, "bye"'``) through to the next segment.
            if region_start_at.get(high) is not None and _ends_continuation(seg):
                if first_pair is None:
                    pair_seq += 1
                    first_pair = pair_seq
                    tagged[base].split_pair = pair_seq
                tagged[-1].split_pair = first_pair
                pending_pair = first_pair
        if carry_forward:
            # An orphaned mark with no next piece in its segment (splitters
            # attach closers, so this is defensive only): keep the text on
            # the last record so the round trip never loses characters.
            if tagged:
                tagged[-1].text += carry_forward
            else:
                pending_leading += carry_forward
            carry_forward = ""
    if carry_forward and tagged:
        # Defensive: a trailing orphan with no next piece (splitters always
        # attach closers, so this should not happen); keep the text.
        tagged[-1].text += carry_forward
    result.sentences = tagged
    return result


def _paragraph_text_and_spans(block: Block) -> tuple[str, list[Span]]:
    """Rebuild a para/quote block's paragraph text plus paragraph-global spans."""
    text_parts: list[str] = []
    spans: list[Span] = []
    offset = 0
    for sent in block.sentences:
        text_parts.append(sent.text)
        for span in sent.spans:
            spans.append(Span(start=offset + span.start, end=offset + span.end, style=span.style))
        offset += len(sent.text)
    return "".join(text_parts), spans


def split_chapter_dialogue(
    chapter: ChapterFile, *, chapter_index: int | None = None
) -> ChapterDialogue:
    """Split every para/quote block of a chapter at quote boundaries.

    Accepts SW3 output (one sentence per block) or SW4 output (already
    sentence-split: the paragraph is rebuilt via join, which round-trips).
    Headings stay single-sentence narration; breaks and pages-form chapters
    pass through. Block ids are preserved; sids renumber from 1. At chapter
    end an unclosed multi-paragraph chain is logged and flipped to
    narration so no chapter ends mid-quote.
    """
    index = chapter.chapter if chapter_index is None else chapter_index
    if chapter.blocks is None:
        return ChapterDialogue(
            chapter=ChapterFile(
                spec_version=chapter.spec_version,
                chapter=index,
                title=chapter.title,
                duration_ms=chapter.duration_ms,
                blocks=None,
                pages=list(chapter.pages or []),
            )
        )
    blocks: list[Block] = []
    tagged_all: list[TaggedSentence] = []
    quotes_all: list[QuoteInfo] = []
    sid = 1
    in_quote = False
    # Per para/quote block: (block id, started-inside-quote, ended-open,
    # trailing dialogue quote number or None).
    block_infos: list[tuple[int, bool, bool, int | None]] = []
    for block in chapter.blocks:
        if block.type == "heading":
            heading_text = block.text or "".join(s.text for s in block.sentences)
            sentences: list[Sentence] = []
            tagged: list[TaggedSentence] = []
            if heading_text:
                sentences.append(
                    Sentence(
                        sid=sid,
                        speaker="narrator",
                        start_ms=0,
                        end_ms=0,
                        text=heading_text,
                        spans=[],
                    )
                )
                tagged.append(
                    TaggedSentence(
                        sid=sid,
                        chapter=index,
                        block=block.id,
                        text=heading_text,
                        spans=[],
                        speaker="narrator",
                        kind=NARRATION,
                    )
                )
                sid += 1
            blocks.append(
                Block(
                    id=block.id,
                    type=block.type,
                    level=block.level,
                    text=block.text,
                    sentences=sentences,
                )
            )
            tagged_all.extend(tagged)
            continue
        if block.type not in ("para", "quote"):
            blocks.append(
                Block(
                    id=block.id,
                    type=block.type,
                    level=block.level,
                    text=block.text,
                    sentences=list(block.sentences),
                )
            )
            continue
        paragraph, spans = _paragraph_text_and_spans(block)
        speaker = block.sentences[0].speaker if block.sentences else "narrator"
        started_in = in_quote
        result = split_paragraph_dialogue(
            paragraph,
            spans,
            chapter=index,
            block=block.id,
            speaker=speaker,
            start_sid=sid,
            start_quote=1,
            in_quote=in_quote,
        )
        in_quote = result.out_quote
        trailing = result.quotes[-1].quote if (result.quotes and result.out_quote) else None
        block_infos.append((block.id, started_in, result.out_quote, trailing))
        sentences = [
            Sentence(
                sid=t.sid, speaker=t.speaker, start_ms=0, end_ms=0, text=t.text, spans=list(t.spans)
            )
            for t in result.sentences
        ]
        sid += len(sentences)
        blocks.append(
            Block(
                id=block.id,
                type=block.type,
                level=block.level,
                text=block.text,
                sentences=sentences,
            )
        )
        tagged_all.extend(result.sentences)
        quotes_all.extend(result.quotes)
    if in_quote:
        # The closing quote never came: the trailing chain of blocks ending
        # open (linked by continued starts) is unbalanced. Flip each tail
        # block's trailing quote back to narration so no chapter ends mid-quote.
        logger.warning(
            "unclosed quote at chapter end; treating trailing speech as narration (chapter %s)",
            index,
        )
        tail: list[tuple[int, int]] = []
        for block_id, started_in, ended_open, trailing in reversed(block_infos):
            if not ended_open or trailing is None:
                break
            tail.append((block_id, trailing))
            if not started_in:
                break
        drop_keys = set(tail)
        for tagged in tagged_all:
            if (tagged.block, tagged.quote) in drop_keys and tagged.kind == DIALOGUE:
                tagged.kind = NARRATION
                tagged.quote = None
                tagged.continued = False
        quotes_all = [q for q in quotes_all if (q.block, q.quote) not in drop_keys]
    out_chapter = ChapterFile(
        spec_version=chapter.spec_version,
        chapter=index,
        title=chapter.title,
        duration_ms=chapter.duration_ms,
        blocks=blocks,
    )
    return ChapterDialogue(chapter=out_chapter, tagged=tagged_all, quotes=quotes_all)
