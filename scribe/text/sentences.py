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

"""Sentence splitting per paragraph (SW4).

Pipeline for one paragraph (:func:`split_paragraph`):

1. ``pysbd`` (English, ``clean=False``) proposes sentence candidates.
2. Post-fix merges glue back pieces pysbd over-split:

   - title abbreviations (``Mr.``/``Mrs.``/``Ms.``/``Dr.``/``St.``/``Sr.``/
     ``Jr.``/``Prof.``/``Rev.``) and single-capital initials (``J. K.``),
   - ellipses (``...`` and ``…``) — an ellipsis never ends a sentence here.
   - ``No.`` is deliberately *not* in the abbreviation set: when pysbd
     splits after it (``The answer was No. He left.``) it is the
     sentence-final word "no", not the number abbreviation (which pysbd
     already keeps together, as in ``No. 12``).
   - Known limitation: ``St.`` (Saint vs Street) and single letters that
     genuinely end a sentence (a grade ``A.``) still merge; rare in books
     and harmless for TTS continuity.
3. Quotation handling (straight ``"..."`` and curly ``“...”`` pairs only;
   single quotes are left alone because apostrophes make them ambiguous —
   dialogue splitting proper is Slice 4). A quoted region holding more than
   three sentences is split inside so TTS chunks stay small; any other
   quoted region is never split and stays attached to the surrounding
   narration. Unbalanced quotes are logged and the paragraph is split as
   plain narration (multi-paragraph dialogue looks unbalanced per
   paragraph, which is normal, not an error).
4. Headings are not split at all: one sentence each, read aloud as-is.

Round-trip preservation: sentence ``text`` keeps its original spacing — the
gap after a sentence is stored as trailing whitespace of that sentence, so
``"".join(s.text for s in sentences) == paragraph`` exactly, even for
double spaces, tabs, newlines, or leading/trailing whitespace. (SW3 feeds
this function whitespace-collapsed paragraphs, but the guarantee holds for
any input.) Consumers needing clean strings (TTS, display) should
``strip()``; no new bundle field is used for spacing, so the bundle format
is unchanged.

Span interaction: SW3 paragraphs carry character-offset :class:`Span`
ranges (italic/bold) into the paragraph text. Each sentence carries the
spans intersecting its range, rebased to sentence-local offsets; a span
crossing a boundary is clipped into each side and nested styles are kept
as independent spans. This approach is required by the bundle contract
(spec section 4: spans are offsets "into ``text``", i.e. the sentence text
the Player renders) — the alternatives fail it: dropping crossing spans
loses emphasis, and keeping paragraph-global offsets would point outside
the sentence text and fail validation.

:func:`split_chapter` applies this to every block of an SW3
:class:`ChapterFile` and renumbers ``sid`` consecutively from 1 across the
chapter (spec section 4); timings stay 0 placeholders for SW7, which makes
SW7's numbering/timing fill-in trivial.

English only. No network access (pysbd runs fully local rule tables).
"""

from __future__ import annotations

import logging
import re
from collections.abc import Sequence

import pysbd

from bundle.models import Block, ChapterFile, Sentence, Span

logger = logging.getLogger(__name__)

NARRATOR = "narrator"  # Default speaker; mirrors extract.clean.NARRATOR for SW3 input.

_SEGMENTER = pysbd.Segmenter(language="en", clean=False)

_ABBREV_RE = re.compile(r"(?:^|\s)(?:Mr|Mrs|Ms|Dr|St|Sr|Jr|Prof|Rev)\.$")
_INITIAL_RE = re.compile(r"(?:^|\s)[A-Z]\.$")

_MAX_QUOTE_SENTENCES = 3  # A quotation with more sentences than this is split inside.


def _needs_merge(piece: str) -> bool:
    """True when a piece ends mid-sentence and must glue to the next piece."""
    if piece.endswith("...") or piece.endswith("…"):
        return True
    return _ABBREV_RE.search(piece) is not None or _INITIAL_RE.search(piece) is not None


def _locate_contents(text: str, raws: Sequence[str]) -> list[tuple[int, int]] | None:
    """Map pysbd segments back to (start, end) content spans in ``text``.

    Returns None when a segment cannot be found in order or a gap between
    segments holds non-whitespace (callers then keep the paragraph whole,
    so text is never lost or reordered).
    """
    spans: list[tuple[int, int]] = []
    cursor = 0
    for raw in raws:
        content = raw.strip()
        if not content:
            continue
        pos = text.find(content, cursor)
        if pos < 0 or text[cursor:pos].strip():
            return None
        spans.append((pos, pos + len(content)))
        cursor = pos + len(content)
    if not spans:
        return None
    if text[cursor:].strip():
        return None
    return spans


def _merge_contents(text: str, spans: list[tuple[int, int]]) -> list[tuple[int, int]]:
    """Drop boundaries after abbreviation/initial/ellipsis pieces (chains re-check)."""
    merged: list[list[int]] = [[spans[0][0], spans[0][1]]]
    for start, end in spans[1:]:
        if _needs_merge(text[merged[-1][0] : merged[-1][1]].strip()):
            merged[-1][1] = end
        else:
            merged.append([start, end])
    return [(start, end) for start, end in merged]


def _plain_spans(text: str) -> list[tuple[int, int]] | None:
    """pysbd split plus post-fix merges as content spans; None if unalignable."""
    spans = _locate_contents(text, _SEGMENTER.segment(text))
    if spans is None:
        return None
    return _merge_contents(text, spans)


def _quote_regions(text: str) -> tuple[list[tuple[int, int]], bool]:
    """Balanced double-quote regions as (open, close-exclusive) offsets.

    Straight quotes pair sequentially; curly quotes pair each opener with
    the next closer (same-type nesting collapses to the outer region).
    The flag is False when quotes are left over (odd straights, unmatched
    curlies) — the caller still splits the paragraph as plain narration.
    """
    regions: list[tuple[int, int]] = []
    balanced = True
    straight = [match.start() for match in re.finditer(r'"', text)]
    if len(straight) % 2:
        balanced = False
    for open_i, close_i in zip(straight[::2], straight[1::2]):
        regions.append((open_i, close_i + 1))
    depth = 0
    open_i = -1
    for i, char in enumerate(text):
        if char == "\u201c":
            if depth == 0:
                open_i = i
            depth += 1
        elif char == "\u201d":
            if depth == 0:
                balanced = False
            else:
                depth -= 1
                if depth == 0:
                    regions.append((open_i, i + 1))
    if depth:
        balanced = False
    return sorted(regions), balanced


def _rebase(spans: Sequence[Span] | None, low: int, high: int) -> list[Span]:
    """Spans intersecting [low, high), rebased to sentence-local offsets."""
    rebased: list[Span] = []
    for span in spans or []:
        start = max(span.start, low)
        end = min(span.end, high)
        if start < end:
            rebased.append(Span(start=start - low, end=end - low, style=span.style))
    return rebased


def split_paragraph(
    text: str,
    spans: Sequence[Span] | None = None,
    *,
    speaker: str = NARRATOR,
    start_sid: int = 1,
) -> list[Sentence]:
    """Split one paragraph into sentences with rebased spans.

    Sentence ``text`` keeps its trailing spacing so ``"".join(...)``
    reproduces ``text`` exactly; ``sid`` runs from ``start_sid``;
    ``start_ms``/``end_ms`` are 0 placeholders for SW7. Blank input yields
    no sentences.
    """
    if not text.strip():
        return []
    contents = _plain_spans(text)
    if contents is None:
        logger.warning("sentence split failed to align; keeping paragraph whole: %r", text[:60])
        return [
            Sentence(
                sid=start_sid,
                speaker=speaker,
                start_ms=0,
                end_ms=0,
                text=text,
                spans=_rebase(spans, 0, len(text)),
            )
        ]
    boundaries: set[int] = {0, len(text)}
    for start, _ in contents[1:]:
        boundaries.add(start)
    regions, balanced = _quote_regions(text)
    if not balanced:
        logger.info("unbalanced quotes; splitting as narration: %r", text[:60])
    for open_i, close_i in regions:
        inner = _plain_spans(text[open_i + 1 : close_i - 1])
        if inner is None or len(inner) <= _MAX_QUOTE_SENTENCES:
            for boundary in list(boundaries):
                if open_i < boundary < close_i:
                    boundaries.discard(boundary)
        else:
            base = open_i + 1  # Skip the first: the lead-in glues to it.
            for start, _ in inner[1:]:
                boundaries.add(base + start)
    ordered = sorted(boundaries)
    return [
        Sentence(
            sid=start_sid + k,
            speaker=speaker,
            start_ms=0,
            end_ms=0,
            text=text[ordered[k] : ordered[k + 1]],
            spans=_rebase(spans, ordered[k], ordered[k + 1]),
        )
        for k in range(len(ordered) - 1)
    ]


def split_chapter(chapter: ChapterFile) -> ChapterFile:
    """Split every para/quote block of an SW3 chapter; renumber sids from 1.

    Headings become one sentence each (text kept as-is); breaks and
    pages-form chapters pass through unchanged. Returns a new ChapterFile.
    """
    if chapter.blocks is None:
        return ChapterFile(
            spec_version=chapter.spec_version,
            chapter=chapter.chapter,
            title=chapter.title,
            duration_ms=chapter.duration_ms,
            blocks=None,
            pages=list(chapter.pages or []),
        )
    blocks: list[Block] = []
    sid = 1
    for block in chapter.blocks:
        if block.type == "heading":
            heading_text = block.text or ""
            sentences = (
                [
                    Sentence(
                        sid=sid,
                        speaker=NARRATOR,
                        start_ms=0,
                        end_ms=0,
                        text=heading_text,
                        spans=[],
                    )
                ]
                if heading_text
                else []
            )
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
        elif block.type in ("para", "quote"):
            sentences = []
            for sent in block.sentences:
                pieces = split_paragraph(sent.text, sent.spans, speaker=sent.speaker, start_sid=sid)
                sentences.extend(pieces)
                sid += len(pieces)
            blocks.append(
                Block(
                    id=block.id,
                    type=block.type,
                    level=block.level,
                    text=block.text,
                    sentences=sentences,
                )
            )
        else:
            blocks.append(
                Block(
                    id=block.id,
                    type=block.type,
                    level=block.level,
                    text=block.text,
                    sentences=list(block.sentences),
                )
            )
    return ChapterFile(
        spec_version=chapter.spec_version,
        chapter=chapter.chapter,
        title=chapter.title,
        duration_ms=chapter.duration_ms,
        blocks=blocks,
    )
