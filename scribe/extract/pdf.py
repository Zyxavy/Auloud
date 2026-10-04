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

"""PDF text extraction (Slice 5 CP5): text layer to clean chapters.

Opens the PDF with PyMuPDF and reads each page's text layer via
``get_text("blocks")``: ``(x0, y0, x1, y1, text, block_no, block_type)``.
Image-only blocks (``block_type != 0``) are dropped; a page with zero text
blocks afterwards counts as scanned (no text layer; there is no OCR in v1).

Cleaning (all deterministic, no timestamps):

- Reading order: blocks sorted by ``(y0, x0)`` with rounded coordinates,
  except pages whose blocks form two clear x-bands (a vertical gap no text
  block crosses, spanning most of the text height): those read
  left-column-first, then the right column. This handles columns that
  arrive as distinct blocks (the usual typeset case); rows already merged
  into a single wide block keep their content-stream order, since
  ``blocks`` output carries no per-line coordinates to re-split them.
  Full-width blocks (headers,
  footers) that sit strictly above or below the columns keep their visual
  place; a full-width block in the middle falls back to plain ``y0`` order
  for that page (figures/tables mangle text order anyway).
- Hyphenated line breaks: a line ending in ``-`` whose next line starts
  with a lowercase letter joins without the hyphen or space (``"hill-"``
  + ``"side"`` becomes ``"hillside"``).
- Repeated headers/footers: a first/last line repeating on 3+ pages in the
  same y-band is dropped everywhere (counts recorded). Lone digits in the
  top/bottom margin are page numbers and are dropped. Footnote marker
  blocks (first line starts with a digit marker, short, at the page
  bottom) are dropped and counted.
- Paragraphs: each PyMuPDF text block is already paragraph-shaped (blocks
  split where the layout has large gaps, which is exactly the task's
  "blank line or indent/large gap means a new para" rule at the only level
  ``blocks`` output can observe). Lines inside a block join with a space;
  blank lines split paragraphs. Known limitation: first-line-indent-only
  paragraph breaks that PyMuPDF merges into one block cannot be seen here
  (per-line x coordinates are not in ``blocks`` output) and stay joined.
- Headings: leading ALL-CAPS short lines, plus paragraphs matching a PDF
  outline title, become headings. Chapters come from the PDF outline
  (``doc.get_toc()``, top-level entries, document order) when present,
  else chapter-like/all-caps heading splits, else fixed 20-page ranges.
- Every chapter keeps its 1-based ``source_pages`` (CP6 threads these
  into per-sentence page provenance and ``pages`` sync marks).

Cleaning parity with EPUB: chapter text flows through the same
:func:`extract.clean._clean_document` (via a :class:`SpineDocument` per
chapter, so empty/boilerplate drops behave identically), then the same
tiny-merge and :func:`extract.clean._to_chapter_file`. Only the public
``clean`` helpers (``normalize_text``, ``is_boilerplate``,
``is_break_text``) plus those two pipeline stages are reused (no copied
rules). ``_clean_document`` itself is EPUB-shaped (nav/image-only/page
semantics live on ``SpineDocument``), so the PDF-specific page work above
stays here.

Scanned PDFs: when over half the pages have no text layer,
:class:`ScannedPdfError` names the file with a ``"no text layer (scanned
PDF?)"`` message; :mod:`draft` turns it into a clean :class:`DraftError`
(no traceback). No OCR is attempted.

Same bytes in always give an identical :class:`ExtractionResult` (TOC
order as-is, blocks sorted by rounded coordinates, drops appended in
page order; no dict-order dependence, no timestamps).
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from pathlib import Path

from extract.clean import (
    TINY_CHAPTER_WORDS,
    ExtractionResult,
    _clean_document,
    _merge_tiny,
    _to_chapter_file,
    is_break_text,
    normalize_text,
)
from extract.epub import ParsedBlock, SpineDocument

logger = logging.getLogger(__name__)

#: Chapter fallback: fixed page ranges of this size (plan CP5).
FIXED_CHAPTER_PAGES = 20
#: A first/last line repeating on this many pages (same y-band) is a header/footer.
HEADER_FOOTER_MIN_PAGES = 3
#: Y-band tolerance for "same position" header/footer matching, in points.
HEADER_FOOTER_Y_TOLERANCE_PT = 12.0
#: Lone-digit lines inside these top/bottom page fractions are page numbers.
PAGE_NUMBER_TOP_FRACTION = 0.10
PAGE_NUMBER_BOTTOM_FRACTION = 0.10
_PAGE_NUMBER_RE = re.compile(r"^\d{1,4}$")
#: Footnote marker blocks live below this fraction of the page height ...
FOOTNOTE_BOTTOM_FRACTION = 0.75
#: ... and their marker line is shorter than this (characters).
FOOTNOTE_MAX_LEN = 150
_FOOTNOTE_MARKER_RE = re.compile(r"^(\d{1,3}[.\)\]]?|[\[\(]\d{1,3}[\]\)])\s*\S")
#: ALL-CAPS lines up to this length (chars) count as headings.
HEADING_ALL_CAPS_MAX_LEN = 120
#: Chapter-like heading pattern for heading-heuristic chapter splits.
_CHAPTER_TITLE_RE = re.compile(r"^(chapter|part|section)\b", re.IGNORECASE)
_CHAPTER_TITLE_MAX_LEN = 120
#: Scanned fraction above which extraction fails (strictly over half).
SCANNED_FAIL_RATIO = 0.5
#: Lines shorter than this count toward the short-line quality ratio.
SHORT_LINE_CHARS = 60
#: Short-line ratio above this marks the PDF messy (warning only).
MESSY_SHORT_LINE_RATIO = 0.5
#: Average header/footer/page-number drops per page above this marks messy.
MESSY_JUNK_PER_PAGE = 3.0
#: Minimum vertical gap (points) that can separate two text columns.
COLUMN_MIN_GAP_PT = 24.0
#: Column overlap: the y-overlap of the two bands must cover this fraction
#: of their combined y-extent to count as two columns (not stacked blocks).
COLUMN_OVERLAP_FRACTION = 0.5


class ScannedPdfError(ValueError):
    """A PDF has no usable text layer (probably scanned images, no OCR)."""


@dataclass
class PdfQuality:
    """Counts for the ``draft_report.md`` PDF quality section (CP5)."""

    pages: int = 0
    scanned_pages: int = 0
    header_lines_dropped: int = 0
    footer_lines_dropped: int = 0
    page_numbers_dropped: int = 0
    footnotes_dropped: int = 0
    total_lines: int = 0
    short_lines: int = 0
    messy: bool = False
    messy_reasons: list[str] = field(default_factory=list)

    @property
    def short_line_ratio(self) -> float:
        """Fraction of content lines shorter than ``SHORT_LINE_CHARS``."""
        if self.total_lines <= 0:
            return 0.0
        return self.short_lines / self.total_lines


@dataclass
class _Line:
    """One non-empty text line with its block provenance (1-based page)."""

    text: str
    page: int
    y: float  # owning block's y0: header/footer/margin proxy
    x0: float  # owning block's x0
    y1: float  # owning block's y1 (bottom-margin proxy)
    block_start: bool  # first line of its block (paragraph boundary)
    block_id: int  # index of the owning block on the page


@dataclass
class _Para:
    """One rebuilt paragraph, still carrying its source page (CP6 needs it)."""

    text: str
    page: int
    is_heading: bool = False
    level: int = 2


def _text_blocks(page: object) -> list[tuple[float, float, float, float, str]]:
    """Text-layer blocks as ``(x0, y0, x1, y1, text)`` (image blocks out)."""
    blocks: list[tuple[float, float, float, float, str]] = []
    for raw in page.get_text("blocks"):  # type: ignore[union-attr]
        x0, y0, x1, y1, text, _block_no, block_type = raw
        if block_type != 0:
            continue
        if not str(text).strip():
            continue
        blocks.append((float(x0), float(y0), float(x1), float(y1), str(text)))
    return blocks


def _order_blocks(
    blocks: list[tuple[float, float, float, float, str]],
) -> list[tuple[float, float, float, float, str]]:
    """Reading order: two-column left-first when bands are clear, else y0 sort.

    A column gap is a vertical strip at least ``COLUMN_MIN_GAP_PT`` wide
    that no text block crosses, with text on both sides whose y-overlap
    covers ``COLUMN_OVERLAP_FRACTION`` of their combined extent. Crossing
    (full-width) blocks must all sit strictly above or below the columns
    (headers/footers); a mid-column full-width block falls back to plain
    ``(y0, x0)`` order for the page.
    """
    if len(blocks) < 2:
        return sorted(blocks, key=lambda b: (round(b[1]), round(b[0])))

    edges = sorted({round(b[0]) for b in blocks} | {round(b[2]) for b in blocks})
    best: tuple[float, float] | None = None
    for left_edge, right_edge in zip(edges, edges[1:]):
        if right_edge - left_edge < COLUMN_MIN_GAP_PT:
            continue
        mid = (left_edge + right_edge) / 2.0
        left = [b for b in blocks if b[2] <= mid]
        right = [b for b in blocks if b[0] >= mid]
        if not left or not right:
            continue
        lo = max(min(b[1] for b in left), min(b[1] for b in right))
        hi = min(max(b[3] for b in left), max(b[3] for b in right))
        total = max(max(b[3] for b in left), max(b[3] for b in right)) - min(
            min(b[1] for b in left), min(b[1] for b in right)
        )
        if total <= 0 or (hi - lo) / total < COLUMN_OVERLAP_FRACTION:
            continue
        if best is None or (right_edge - left_edge) > (best[1] - best[0]):
            best = (float(left_edge), float(right_edge))
    if best is None:
        return sorted(blocks, key=lambda b: (round(b[1]), round(b[0])))

    mid = (best[0] + best[1]) / 2.0
    left = sorted([b for b in blocks if b[2] <= mid], key=lambda b: (round(b[1]), round(b[0])))
    right = sorted([b for b in blocks if b[0] >= mid], key=lambda b: (round(b[1]), round(b[0])))
    full = sorted(
        [b for b in blocks if b not in left and b not in right],
        key=lambda b: (round(b[1]), round(b[0])),
    )
    top = min(min(b[1] for b in left), min(b[1] for b in right))
    bottom = max(max(b[3] for b in left), max(b[3] for b in right))
    headers = [b for b in full if b[3] <= top]
    footers = [b for b in full if b[1] >= bottom]
    if len(headers) + len(footers) != len(full):
        # A full-width block sits mid-column (figure/table caption...):
        # column order would scramble it, so keep plain y0 order instead.
        return sorted(blocks, key=lambda b: (round(b[1]), round(b[0])))
    return [*headers, *left, *right, *footers]


def _page_lines(blocks: list[tuple[float, float, float, float, str]], *, page: int) -> list[_Line]:
    """Flatten ordered blocks to lines (blank raw lines are separators)."""
    lines: list[_Line] = []
    for block_id, (x0, y0, x1, y1, text) in enumerate(blocks):
        first = True
        for raw in str(text).split("\n"):
            stripped = raw.strip()
            if not stripped:
                first = False
                continue
            lines.append(
                _Line(
                    text=stripped,
                    page=page,
                    y=float(y0),
                    x0=float(x0),
                    y1=float(y1),
                    block_start=first,
                    block_id=block_id,
                )
            )
            first = False
    return lines


def _repeat_texts(
    candidates: list[tuple[str, float]],
    *,
    min_pages: int,
    y_tolerance: float,
) -> set[str]:
    """Texts occurring on ``min_pages``+ pages within one y-band (headers...)."""
    by_text: dict[str, list[float]] = {}
    for text, y in candidates:
        by_text.setdefault(text, []).append(y)
    repeats: set[str] = set()
    for text, ys in by_text.items():
        if len(ys) < min_pages:
            continue
        ordered = sorted(ys)
        median = ordered[len(ordered) // 2]
        if all(abs(y - median) <= y_tolerance for y in ordered):
            repeats.add(text)
    return repeats


def _is_page_number(line: _Line, page_height: float) -> bool:
    """Lone digits in the top/bottom margin (a page number, not content)."""
    if not _PAGE_NUMBER_RE.match(line.text):
        return False
    return line.y <= page_height * PAGE_NUMBER_TOP_FRACTION or line.y1 >= page_height * (
        1.0 - PAGE_NUMBER_BOTTOM_FRACTION
    )


def _join_hyphens(lines: list[_Line]) -> list[_Line]:
    """Join ``"mossy-"`` + ``"hill"`` (lowercase continuation) pairs.

    The merged line keeps the first line's ``block_start`` flag, so the
    paragraph rebuild below does not split a word across a block boundary.
    """
    joined: list[_Line] = []
    for line in lines:
        if (
            joined
            and joined[-1].text.endswith("-")
            and len(joined[-1].text) > 1
            and line.text[:1].islower()
        ):
            prev = joined[-1]
            prev.text = prev.text[:-1] + line.text.lstrip()
        else:
            joined.append(line)
    return joined


def _is_heading_text(text: str) -> bool:
    """ALL-CAPS short lines read as headings (body stays body)."""
    if len(text) > HEADING_ALL_CAPS_MAX_LEN:
        return False
    cased = [ch for ch in text if ch.isalpha()]
    return bool(cased) and text.upper() == text


def _extract_with_quality(
    source: Path, *, tiny_threshold: int
) -> tuple[ExtractionResult, PdfQuality]:
    """Shared worker: chapters plus the quality counts for the report."""
    import pymupdf

    path = Path(source)
    if not path.is_file():
        raise FileNotFoundError(f"PDF not found: {path}")
    quality = PdfQuality()
    drops: list[str] = []
    try:
        doc = pymupdf.open(str(path))
    except Exception as exc:
        raise ValueError(f"{path.name}: cannot read PDF: {exc}") from exc

    with doc:
        page_count = len(doc)
        quality.pages = page_count
        if page_count == 0:
            drops.append(f"{path.name}: no pages in PDF")
            return ExtractionResult(chapters=[], drops=drops), quality

        ordered: list[list[_Line]] = []
        heights: list[float] = []
        scanned = 0
        for page_no in range(1, page_count + 1):
            page = doc[page_no - 1]
            height = float(page.rect.height)
            heights.append(height)
            blocks = _order_blocks(_text_blocks(page))
            if not blocks:
                scanned += 1
                drops.append(f"page {page_no}: no text blocks (scanned page?)")
                ordered.append([])
                continue
            ordered.append(_page_lines(blocks, page=page_no))
        quality.scanned_pages = scanned
        if scanned > page_count * SCANNED_FAIL_RATIO:
            raise ScannedPdfError(
                f"{path.name}: no text layer (scanned PDF?): {scanned} of "
                f"{page_count} pages have no extractable text"
            )

        # Pass 1: page numbers first (pattern + margin, no cross-page
        # state), so a footer sharing its page with a number is still the
        # last remaining line below. Then repeating first/last lines.
        content_per_page: list[list[_Line]] = []
        for lines, height in zip(ordered, heights):
            remaining: list[_Line] = []
            for line in lines:
                if _is_page_number(line, height):
                    quality.page_numbers_dropped += 1
                    continue
                remaining.append(line)
            content_per_page.append(remaining)
        if quality.page_numbers_dropped:
            drops.append(f"dropped {quality.page_numbers_dropped} page number(s)")
        firsts = [
            (normalize_text(lines[0].text), lines[0].y) for lines in content_per_page if lines
        ]
        lasts = [
            (normalize_text(lines[-1].text), lines[-1].y) for lines in content_per_page if lines
        ]
        headers = _repeat_texts(
            firsts,
            min_pages=HEADER_FOOTER_MIN_PAGES,
            y_tolerance=HEADER_FOOTER_Y_TOLERANCE_PT,
        )
        footers = _repeat_texts(
            lasts,
            min_pages=HEADER_FOOTER_MIN_PAGES,
            y_tolerance=HEADER_FOOTER_Y_TOLERANCE_PT,
        )

        # Pass 2: drop headers/footers/numbers/footnotes, then rebuild paras.
        header_pages: dict[str, int] = {}
        footer_pages: dict[str, int] = {}
        paras: list[_Para] = []
        toc = doc.get_toc() or []
        toc_titles = {normalize_text(str(entry[1])) for entry in toc if len(entry) > 1}
        for lines, height in zip(content_per_page, heights):
            if not lines:
                continue
            first_text = normalize_text(lines[0].text)
            last_text = normalize_text(lines[-1].text)
            drop_first = first_text in headers
            drop_last = last_text in footers and not (len(lines) == 1 and drop_first)
            if drop_first:
                header_pages[first_text] = header_pages.get(first_text, 0) + 1
                quality.header_lines_dropped += 1
            if drop_last:
                footer_pages[last_text] = footer_pages.get(last_text, 0) + 1
                quality.footer_lines_dropped += 1
            start_at = 1 if drop_first else 0
            end_at = len(lines) - 1 if drop_last else len(lines)
            kept = [ln for ln in lines[start_at:end_at]]
            filtered: list[_Line] = []
            skip_block = -1
            for line in kept:
                if line.block_id == skip_block:
                    continue
                if (
                    line.block_start
                    and len(line.text) < FOOTNOTE_MAX_LEN
                    and _FOOTNOTE_MARKER_RE.match(line.text)
                    and line.y >= height * FOOTNOTE_BOTTOM_FRACTION
                ):
                    skip_block = line.block_id
                    quality.footnotes_dropped += 1
                    continue
                filtered.append(line)
            for line in _join_hyphens(filtered):
                quality.total_lines += 1
                if len(line.text) < SHORT_LINE_CHARS:
                    quality.short_lines += 1
                if line.block_start or not paras or paras[-1].page != line.page:
                    paras.append(_Para(text=line.text, page=line.page))
                else:
                    paras[-1].text += " " + line.text

        for text in sorted(header_pages):
            preview = text[:60] + ("..." if len(text) > 60 else "")
            drops.append(f"dropped repeating header on {header_pages[text]} pages: '{preview}'")
        for text in sorted(footer_pages):
            preview = text[:60] + ("..." if len(text) > 60 else "")
            drops.append(f"dropped repeating footer on {footer_pages[text]} pages: '{preview}'")
        if quality.footnotes_dropped:
            drops.append(f"dropped {quality.footnotes_dropped} footnote(s)")

        # Headings: outline titles first, else ALL-CAPS short lines.
        for para in paras:
            normalized = normalize_text(para.text)
            if normalized in toc_titles:
                para.is_heading, para.level = True, 1
            elif _is_heading_text(normalized):
                para.is_heading, para.level = True, 2
                para.text = normalized

        chapters = _split_chapters(paras, top_level_outline(toc), page_count, path.name)

        # Same post-clean as EPUB: drops, boilerplate, tiny-merge, numbering.
        raw_chapters = []
        for href, title, blocks, pages in chapters:
            spine = SpineDocument(
                item_id=href,
                href=href,
                linear="yes",
                is_nav=False,
                has_images=False,
                toc_title=title,
                blocks=blocks,
            )
            raw = _clean_document(spine, drops)
            if raw is not None:
                raw.source_pages = sorted(set(pages))
                raw_chapters.append(raw)
        merged = _merge_tiny(raw_chapters, drops, tiny_threshold=int(tiny_threshold))
        files = [_to_chapter_file(i + 1, raw) for i, raw in enumerate(merged)]

    _finish_quality(quality)
    return ExtractionResult(chapters=files, drops=drops), quality


def top_level_outline(toc: list) -> list[tuple[int, str, int]]:
    """Top-level outline entries as ``(level, title, page)`` in document order.

    Only entries at the outline's top level split chapters; deeper levels
    stay in-chapter headings. Order is the document's own (never sorted),
    so output stays deterministic for the same bytes.
    """
    if not toc:
        return []
    top = min(int(entry[0]) for entry in toc if len(entry) > 2)
    return [
        (int(entry[0]), str(entry[1]), int(entry[2]))
        for entry in toc
        if len(entry) > 2 and int(entry[0]) == top
    ]


def _split_chapters(
    paras: list[_Para],
    outline: list[tuple[int, str, int]],
    page_count: int,
    source_name: str,
) -> list[tuple[str, str | None, list[ParsedBlock], list[int]]]:
    """Split paras into ``(href, title, blocks, pages)`` chapters.

    Outline first (page ranges between top-level entries, document order;
    leading pages attach to the first chapter), else heading splits
    (chapter-like headings preferred, else ALL-CAPS headings), else fixed
    ``FIXED_CHAPTER_PAGES``-page ranges.
    """
    if outline:
        bounds: list[tuple[int, str]] = []
        for _level, title, start in outline:
            clamped = max(1, min(int(start), page_count))
            if bounds and clamped <= bounds[-1][0]:
                continue  # Out-of-order duplicate; keep document order sane.
            bounds.append((clamped, normalize_text(title) or "Untitled"))
        if bounds:
            ranges: list[tuple[int, int, str]] = []
            for pos, (start, title) in enumerate(bounds):
                end = bounds[pos + 1][0] - 1 if pos + 1 < len(bounds) else page_count
                ranges.append((start, max(start, end), title))
            ranges[0] = (1, ranges[0][1], ranges[0][2])  # leading pages join ch.1
            return _chapters_from_ranges(paras, ranges, source_name)

    splits = _heading_splits(paras, chapter_like_only=True) or _heading_splits(
        paras, chapter_like_only=False
    )
    if splits:
        return _chapters_from_ranges(paras, splits, source_name)

    ranges = []
    for start in range(1, page_count + 1, FIXED_CHAPTER_PAGES):
        end = min(start + FIXED_CHAPTER_PAGES - 1, page_count)
        title = f"Chapter {(start - 1) // FIXED_CHAPTER_PAGES + 1}"
        ranges.append((start, end, title))
    return _chapters_from_ranges(paras, ranges, source_name)


def _heading_splits(paras: list[_Para], *, chapter_like_only: bool) -> list[tuple[int, int, str]]:
    """Chapter ranges from heading paras (first heading per page splits)."""
    starts: list[tuple[int, str]] = []
    seen_pages: set[int] = set()
    for para in paras:
        if not para.is_heading or para.page in seen_pages:
            continue
        text = normalize_text(para.text)
        chapter_like = len(text) <= _CHAPTER_TITLE_MAX_LEN and _CHAPTER_TITLE_RE.match(text)
        if chapter_like_only and not chapter_like:
            continue
        seen_pages.add(para.page)
        starts.append((para.page, text))
    if not starts:
        return []
    last_page = max(p.page for p in paras)
    ranges: list[tuple[int, int, str]] = []
    if starts[0][0] > 1:
        ranges.append((1, starts[0][0] - 1, "Chapter 1"))
    for pos, (start, title) in enumerate(starts):
        end = starts[pos + 1][0] - 1 if pos + 1 < len(starts) else last_page
        ranges.append((start, max(start, end), title))
    return ranges


def _chapters_from_ranges(
    paras: list[_Para],
    ranges: list[tuple[int, int, str]],
    source_name: str,
) -> list[tuple[str, str | None, list[ParsedBlock], list[int]]]:
    """Group paras into chapters by inclusive ``(start, end, title)`` pages."""
    chapters: list[tuple[str, str | None, list[ParsedBlock], list[int]]] = []
    for start, end, title in ranges:
        blocks: list[ParsedBlock] = []
        pages: list[int] = []
        for para in paras:
            if not (start <= para.page <= end):
                continue
            pages.append(para.page)
            text = normalize_text(para.text)
            if not text:
                continue
            if para.is_heading:
                blocks.append(ParsedBlock(kind="heading", text=text, level=para.level))
            elif is_break_text(text):
                blocks.append(ParsedBlock(kind="break"))
            else:
                blocks.append(ParsedBlock(kind="para", text=text))
        href = f"{source_name} pages {start}-{end}"
        if start == end:
            href = f"{source_name} page {start}"
        chapters.append((href, title, blocks, pages))
    return chapters


def _finish_quality(quality: PdfQuality) -> None:
    """Fill the messy flag and reasons from the module thresholds."""
    reasons: list[str] = []
    ratio = quality.short_line_ratio
    if quality.total_lines and ratio > MESSY_SHORT_LINE_RATIO:
        reasons.append(
            f"high short-line ratio {ratio:.0%} "
            f"({quality.short_lines}/{quality.total_lines} lines under "
            f"{SHORT_LINE_CHARS} chars; above {MESSY_SHORT_LINE_RATIO:.0%})"
        )
    if quality.scanned_pages:
        reasons.append(f"{quality.scanned_pages} page(s) with no text layer (image-only; skipped)")
    junk = (
        quality.header_lines_dropped + quality.footer_lines_dropped + quality.page_numbers_dropped
    )
    if quality.pages and junk / quality.pages > MESSY_JUNK_PER_PAGE:
        reasons.append(f"heavy header/footer noise ({junk} dropped lines on {quality.pages} pages)")
    quality.messy = bool(reasons)
    quality.messy_reasons = reasons


def extract_pdf_chapters(
    source: Path | str, *, tiny_threshold: int = TINY_CHAPTER_WORDS
) -> ExtractionResult:
    """Extract and clean a PDF into ChapterFile blocks (CP5 entry point).

    Mirrors :func:`extract.clean.extract_epub_chapters` (same output type,
    same ``tiny_threshold`` merging); raises :class:`ScannedPdfError` when
    over half the pages have no text layer.
    """
    result, _quality = _extract_with_quality(Path(source), tiny_threshold=int(tiny_threshold))
    return result


def extract_pdf_with_quality(
    source: Path | str, *, tiny_threshold: int = TINY_CHAPTER_WORDS
) -> tuple[ExtractionResult, PdfQuality]:
    """Chapters plus :class:`PdfQuality` (the draft report reads quality)."""
    return _extract_with_quality(Path(source), tiny_threshold=int(tiny_threshold))
