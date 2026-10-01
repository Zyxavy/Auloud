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

"""Cleaning and chapter assembly for EPUB extraction (SW3).

Takes the per-spine-document :class:`ParsedBlock` lists from
:mod:`extract.epub` and produces SW2 :class:`ChapterFile` / :class:`Block`
structures (depended on as-is; no model changes):

- Drops nav/TOC pages, copyright boilerplate, empty paragraphs, footnote
  leftovers, and image-only pages (each drop is logged and recorded).
- Chapter titles come from the nav/TOC entry first, else the first
  heading, else ``"Chapter N"`` (final 1-based index).
- Tiny chapters (under ``TINY_CHAPTER_WORDS`` words, default 200) merge
  into the next chapter (or the previous one for a trailing tiny chapter).
- Text is NFC-normalized with collapsed whitespace; curly quotes kept.

Para/quote blocks carry one :class:`Sentence` each (the whole paragraph,
speaker ``"narrator"``) so italic/bold :class:`Span` offsets are testable;
SW4 splits these into real sentences and SW7 fills in timings
(``duration_ms``/``start_ms``/``end_ms`` are 0 placeholders here).
"""

from __future__ import annotations

import logging
import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

from bundle.models import Block, ChapterFile, Sentence

from extract.epub import ParsedBlock, SpineDocument, read_spine_documents

logger = logging.getLogger(__name__)

SPEC_VERSION = "1.0"
TINY_CHAPTER_WORDS = 200
NARRATOR = "narrator"

_BOILERPLATE_HINTS = (
    "copyright",
    "©",
    "(c)",
    "all rights reserved",
    "isbn",
    "project gutenberg",
    "gutenberg ebook",
    "distributed proofreading",
    "transcriber's",
    "transcribers",
)

_DIVIDER_RE = re.compile(r"^[\s*⁂\-—–~·•◦_]+$")
_DIVIDER_MUST_CONTAIN = ("*", "⁂", "•", "◦", "·")


def normalize_text(text: str) -> str:
    """NFC-normalize and collapse whitespace; keep curly quotes."""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", text)).strip()


def is_boilerplate(text: str) -> bool:
    """True for copyright/ISBN/Gutenberg boilerplate paragraphs."""
    lowered = text.lower()
    return any(hint in lowered for hint in _BOILERPLATE_HINTS)


def is_break_text(text: str) -> bool:
    """True when a paragraph is just a scene-break divider (asterisks)."""
    stripped = text.strip()
    if not stripped or len(stripped) > 12:
        return False
    if not _DIVIDER_RE.match(stripped):
        return False
    return any(mark in stripped for mark in _DIVIDER_MUST_CONTAIN)


def count_words(text: str) -> int:
    """Count whitespace-separated words (empty string counts as 0)."""
    stripped = text.strip()
    if not stripped:
        return 0
    return len(stripped.split())


def chapter_word_count(blocks: list[ParsedBlock]) -> int:
    """Total words in heading/para/quote block texts."""
    return sum(count_words(b.text) for b in blocks if b.kind in ("heading", "para", "quote"))


@dataclass
class RawChapter:
    """One spine document after drops, before tiny-merge and renumbering."""

    href: str
    title: str | None  # TOC title or first heading; None -> "Chapter N" later.
    blocks: list[ParsedBlock] = field(default_factory=list)


@dataclass
class ExtractionResult:
    """Top-level SW3 output: chapters plus a human-readable drop log."""

    chapters: list[ChapterFile] = field(default_factory=list)
    drops: list[str] = field(default_factory=list)


def _record(drops: list[str], message: str) -> None:
    drops.append(message)
    logger.info(message)


def _clean_document(doc: SpineDocument, drops: list[str]) -> RawChapter | None:
    """Apply drop rules to one spine document; None means drop the page."""
    label = doc.href
    if doc.is_nav:
        _record(drops, f"{label}: dropped nav/TOC page")
        return None
    kept: list[ParsedBlock] = []
    first_heading: str | None = None
    for block in doc.blocks:
        if block.kind == "break":
            kept.append(block)
            continue
        text = normalize_text(block.text)
        if block.kind == "heading":
            if not text:
                _record(drops, f"{label}: dropped empty heading")
                continue
            if first_heading is None:
                first_heading = text
            kept.append(ParsedBlock(kind="heading", text=text, level=block.level or 1))
            continue
        # Para / quote: drop empty, boilerplate, and divider leftovers.
        if not text:
            _record(drops, f"{label}: dropped empty paragraph")
            continue
        if block.kind == "para" and is_break_text(text):
            kept.append(ParsedBlock(kind="break"))
            continue
        if is_boilerplate(text):
            preview = text[:60] + ("…" if len(text) > 60 else "")
            _record(drops, f"{label}: dropped boilerplate paragraph ('{preview}')")
            continue
        # Re-trim spans to the normalized text (epub already normalized,
        # so this is a no-op safety net that also drops out-of-range spans).
        spans = [s for s in block.spans if 0 <= s.start <= s.end <= len(text)]
        kept.append(ParsedBlock(kind=block.kind, text=text, spans=spans))
    if not [b for b in kept if b.kind in ("heading", "para", "quote") and b.text.strip()]:
        if doc.has_images:
            _record(drops, f"{label}: dropped image-only page (no text)")
        else:
            _record(drops, f"{label}: dropped empty page (no text blocks)")
        return None
    title = doc.toc_title.strip() if doc.toc_title and doc.toc_title.strip() else None
    if title is None:
        title = first_heading
    return RawChapter(href=label, title=title, blocks=kept)


def _merge_tiny(
    chapters: list[RawChapter], drops: list[str], *, tiny_threshold: int
) -> list[RawChapter]:
    """Merge sub-threshold chapters forward (backward for a trailing one)."""
    if len(chapters) <= 1:
        return chapters
    merged: list[RawChapter] = []
    pending: list[RawChapter] = []
    for chapter in chapters:
        words = chapter_word_count(chapter.blocks)
        if words < tiny_threshold:
            pending.append(chapter)
            continue
        if pending:
            blocks: list[ParsedBlock] = []
            names: list[str] = []
            for tiny in pending:
                blocks.extend(tiny.blocks)
                names.append(
                    f"'{tiny.title or tiny.href}' ({chapter_word_count(tiny.blocks)} words)"
                )
            blocks.extend(chapter.blocks)
            _record(
                drops,
                f"merged tiny chapter(s) {', '.join(names)} into next chapter "
                f"'{chapter.title or chapter.href}'",
            )
            chapter = RawChapter(href=chapter.href, title=chapter.title, blocks=blocks)
            pending = []
        merged.append(chapter)
    if pending:
        # Trailing tiny chapter(s): fold into the previous kept chapter.
        if merged:
            target = merged[-1]
            blocks = list(target.blocks)
            names = []
            for tiny in pending:
                blocks.extend(tiny.blocks)
                names.append(
                    f"'{tiny.title or tiny.href}' ({chapter_word_count(tiny.blocks)} words)"
                )
            _record(
                drops,
                f"merged trailing tiny chapter(s) {', '.join(names)} into previous chapter "
                f"'{target.title or target.href}'",
            )
            merged[-1] = RawChapter(href=target.href, title=target.title, blocks=blocks)
        else:
            # Every chapter is tiny: keep one combined chapter.
            blocks = []
            for tiny in pending:
                blocks.extend(tiny.blocks)
            title = pending[-1].title or pending[0].title
            _record(drops, "all chapters tiny; combined into a single chapter")
            merged.append(RawChapter(href=pending[0].href, title=title, blocks=blocks))
    return merged


def _to_chapter_file(index: int, raw: RawChapter) -> ChapterFile:
    """Convert one cleaned chapter to SW2 ChapterFile/Block structures."""
    title = raw.title if raw.title else f"Chapter {index}"
    blocks: list[Block] = []
    block_id = 0
    sid = 0
    for parsed in raw.blocks:
        block_id += 1
        if parsed.kind == "heading":
            blocks.append(
                Block(id=block_id, type="heading", level=parsed.level or 1, text=parsed.text)
            )
        elif parsed.kind in ("para", "quote"):
            sid += 1
            blocks.append(
                Block(
                    id=block_id,
                    type=parsed.kind,
                    sentences=[
                        Sentence(
                            sid=sid,
                            speaker=NARRATOR,
                            start_ms=0,  # Placeholder; SW7 fills timings.
                            end_ms=0,
                            text=parsed.text,
                            spans=list(parsed.spans),
                        )
                    ],
                )
            )
        else:  # break
            blocks.append(Block(id=block_id, type="break"))
    return ChapterFile(
        spec_version=SPEC_VERSION,
        chapter=index,
        title=title,
        duration_ms=0,  # Placeholder; SW7/SW8 set the real duration.
        blocks=blocks,
    )


def extract_epub_chapters(
    epub_path: Path | str, *, tiny_threshold: int = TINY_CHAPTER_WORDS
) -> ExtractionResult:
    """Extract and clean an EPUB into ChapterFile blocks (SW3 entry point)."""
    read = read_spine_documents(Path(epub_path))
    drops: list[str] = []
    # Single channel: every spine-level skip/strip lands in drops (SW5's
    # draft report reads this list, so logger-only drops would go missing).
    for notice in read.notices:
        _record(drops, notice)
    raw_chapters: list[RawChapter] = []
    for doc in read.documents:
        raw = _clean_document(doc, drops)
        if raw is not None:
            raw_chapters.append(raw)
    merged = _merge_tiny(raw_chapters, drops, tiny_threshold=int(tiny_threshold))
    chapters = [_to_chapter_file(i + 1, raw) for i, raw in enumerate(merged)]
    return ExtractionResult(chapters=chapters, drops=drops)
