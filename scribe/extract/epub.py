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

"""EPUB spine reading and chapter HTML walk (SW3).

Reads the spine in order via ``ebooklib`` and walks each document's HTML
with BeautifulSoup (lxml). Maps ``h1-h3`` to headings, ``p`` to paras,
``blockquote`` to quotes, ``hr`` or centered-asterisk paragraphs to
breaks, and ``em``/``i`` plus ``strong``/``b`` to :class:`Span` ranges
(character offsets into the cleaned text).

Footnote markers (``sup`` elements and short footnote links) are stripped
here (each removal is logged); empty paragraphs, boilerplate, nav/TOC and
image-only pages are dropped later in :mod:`clean` (which logs each drop).

No network access at runtime; all parsing is local.
"""

from __future__ import annotations

import logging
import re
import unicodedata
import warnings
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from bs4 import BeautifulSoup, NavigableString, Tag, XMLParsedAsHTMLWarning

from bundle.models import Span

logger = logging.getLogger(__name__)


def _soup(html: bytes | str) -> BeautifulSoup:
    """Parse chapter HTML with lxml (XHTML parsed as HTML is intentional)."""
    with warnings.catch_warnings():
        # EPUB chapters are XHTML; the HTML parser is deliberately used
        # (forgiving for messy real-world EPUBs).
        warnings.simplefilter("ignore", XMLParsedAsHTMLWarning)
        return BeautifulSoup(html, "lxml")


# Divider lines that count as scene breaks when they are the whole paragraph.
# Kept in sync with clean.is_break_text (single source would be circular).
_DIVIDER_RE = re.compile(r"^[\s*⁂\-—–~·•◦_]+$")
_DIVIDER_MUST_CONTAIN = ("*", "⁂", "•", "◦", "·")

_HEADING_LEVELS: dict[str, int] = {
    "h1": 1,
    "h2": 2,
    "h3": 3,
    "h4": 3,  # Spec allows 1-3; deeper headings clamp to 3.
    "h5": 3,
    "h6": 3,
}

_FOOTNOTE_HREF_HINTS = ("footnote", "footnotes", "fn", "endnote", "note", "ref")
_FOOTNOTE_TEXT_RE = re.compile(r"^[\[\(\*†‡§]?\s*\d{1,3}\s*[\]\)\*†‡§]?$")


@dataclass
class ParsedBlock:
    """One block parsed from chapter HTML (before cleaning drops/merges)."""

    kind: str  # "heading" | "para" | "quote" | "break"
    text: str = ""
    level: int | None = None
    spans: list[Span] = field(default_factory=list)


@dataclass
class SpineDocument:
    """One spine item in order, with its parsed blocks and TOC title."""

    item_id: str
    href: str  # ebooklib file_name, e.g. "EPUB/ch1.xhtml"
    linear: str
    is_nav: bool
    has_images: bool
    toc_title: str | None
    blocks: list[ParsedBlock] = field(default_factory=list)


def _collapse(text: str) -> str:
    """NFC-normalize and collapse whitespace (mirrors clean.normalize_text).

    Canonical implementation lives in :mod:`clean` as ``normalize_text``;
    this local copy avoids a circular import (clean imports this module).
    Curly quotes are preserved.
    """
    normalized = unicodedata.normalize("NFC", text)
    return re.sub(r"\s+", " ", normalized).strip()


def _is_break_text(text: str) -> bool:
    """True when a whole-paragraph string is a scene-break divider."""
    stripped = text.strip()
    if not stripped or len(stripped) > 12:
        return False
    if not _DIVIDER_RE.match(stripped):
        return False
    return any(mark in stripped for mark in _DIVIDER_MUST_CONTAIN)


def _strip_footnotes(body: Tag, source: str) -> int:
    """Remove footnote markers (sup + short footnote links); return count."""
    removed = 0
    for sup in body.find_all("sup"):
        sup.decompose()
        removed += 1
    for link in body.find_all("a"):
        href = str(link.get("href", "")).lower()
        link_text = (link.get_text() or "").strip()
        if any(hint in href for hint in _FOOTNOTE_HREF_HINTS) and (
            _FOOTNOTE_TEXT_RE.match(link_text) or len(link_text) <= 4
        ):
            link.decompose()
            removed += 1
    if removed:
        logger.info("%s: stripped %d footnote marker(s)", source, removed)
    return removed


def _text_and_spans(element: Tag) -> tuple[str, list[Span]]:
    """Build cleaned text plus italic/bold spans for one block element."""
    segments: list[tuple[str, bool, bool]] = []
    for node in element.descendants:
        if not isinstance(node, NavigableString):
            continue
        raw = str(node)
        if not raw:
            continue
        parent = node.parent
        italic = False
        bold = False
        if isinstance(parent, Tag):
            italic = node.find_parent(["em", "i"]) is not None
            bold = node.find_parent(["strong", "b"]) is not None
        # Normalize each segment before offset tracking so NFC length
        # changes (combining marks) do not shift later offsets.
        segments.append((unicodedata.normalize("NFC", raw), italic, bold))

    chars: list[str] = []
    itals: list[bool] = []
    bolds: list[bool] = []
    for text, italic, bold in segments:
        for ch in text:
            chars.append(ch)
            itals.append(italic)
            bolds.append(bold)

    # Collapse whitespace runs to a single space; the collapsed space
    # inherits the preceding style so "<em>foo bar</em>" stays one span.
    out_chars: list[str] = []
    out_itals: list[bool] = []
    out_bolds: list[bool] = []
    pending_space = False
    pending_ital = False
    pending_bold = False
    for ch, ital, bold in zip(chars, itals, bolds):
        if ch.isspace():
            if out_chars or pending_space is False:
                # Remember style; emit one space when a non-space follows.
                if not pending_space:
                    pending_space = True
                    pending_ital = itals[len(out_chars) - 1] if out_chars else ital
                    pending_bold = bolds[len(out_chars) - 1] if out_chars else bold
            continue
        if pending_space:
            if out_chars:
                out_chars.append(" ")
                out_itals.append(pending_ital)
                out_bolds.append(pending_bold)
            pending_space = False
            pending_ital = False
            pending_bold = False
        out_chars.append(ch)
        out_itals.append(ital)
        out_bolds.append(bold)

    text = "".join(out_chars)
    spans: list[Span] = []
    for flags, style in ((out_itals, "italic"), (out_bolds, "bold")):
        pos = 0
        while pos < len(text):
            if not flags[pos]:
                pos += 1
                continue
            end = pos + 1
            while end < len(text) and flags[end]:
                end += 1
            # Trim spaces at span edges (e.g. "<em>foo</em> bar" -> "foo").
            start = pos
            while start < end and text[start].isspace():
                start += 1
            while end > start and text[end - 1].isspace():
                end -= 1
            if end > start:
                spans.append(Span(start=start, end=end, style=style))
            pos = end
    spans.sort(key=lambda s: (s.start, s.end))
    return text, spans


def parse_html_blocks(html: bytes | str, source: str = "") -> list[ParsedBlock]:
    """Parse one spine document's HTML into blocks in document order."""
    soup = _soup(html)
    body = soup.body if soup.body is not None else soup
    for tag in body(["script", "style"]):
        tag.decompose()
    _strip_footnotes(body, source or "<html>")

    blocks: list[ParsedBlock] = []
    for elem in body.descendants:
        if not isinstance(elem, Tag):
            continue
        name = elem.name.lower() if elem.name else ""
        if name in ("script", "style"):
            continue
        if name in ("h1", "h2", "h3", "h4", "h5", "h6"):
            if elem.find_parent("blockquote") is not None:
                continue  # Covered by the blockquote handler.
            text = _collapse(elem.get_text(separator=" "))
            if not text:
                blocks.append(ParsedBlock(kind="para", text=""))
                continue
            blocks.append(ParsedBlock(kind="heading", text=text, level=_HEADING_LEVELS[name]))
        elif name == "p":
            if elem.find_parent("blockquote") is not None:
                continue
            # Image-only paragraphs (e.g. "<p><img/></p>") carry no text.
            text, spans = _text_and_spans(elem)
            text = _collapse(text)
            if not text:
                blocks.append(ParsedBlock(kind="para", text=""))
                continue
            if _is_break_text(text):
                blocks.append(ParsedBlock(kind="break"))
                continue
            blocks.append(ParsedBlock(kind="para", text=text, spans=spans))
        elif name == "blockquote":
            text, spans = _text_and_spans(elem)
            text = _collapse(text)
            if not text:
                blocks.append(ParsedBlock(kind="para", text=""))
                continue
            blocks.append(ParsedBlock(kind="quote", text=text, spans=spans))
        elif name == "hr":
            blocks.append(ParsedBlock(kind="break"))
        elif name in ("div", "section", "article"):
            # Fallback for div-based paragraphs: only leaf containers
            # without real block children become paras.
            if elem.find(["p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote"]) is not None:
                continue
            if elem.find_parent("blockquote") is not None:
                continue
            if elem.parent is not None and elem.parent.name in (
                "p",
                "h1",
                "h2",
                "h3",
                "blockquote",
            ):
                continue
            text, spans = _text_and_spans(elem)
            text = _collapse(text)
            if not text:
                continue
            if _is_break_text(text):
                blocks.append(ParsedBlock(kind="break"))
                continue
            blocks.append(ParsedBlock(kind="para", text=text, spans=spans))
    return blocks


def _is_nav_item(item: Any, href: str) -> bool:
    """True for nav/TOC documents (EpubNav, nav type, or nav-ish filename)."""
    cls_name = type(item).__name__
    if cls_name == "EpubNav":
        return True
    try:
        from ebooklib import ITEM_NAVIGATION

        if item.get_type() == ITEM_NAVIGATION:
            return True
    except Exception:  # pragma: no cover - defensive; metadata lookup only
        pass
    lowered = href.lower()
    base = lowered.rsplit("/", 1)[-1].split("?", 1)[0]
    stem = base.rsplit(".", 1)[0] if "." in base else base
    return stem in ("nav", "toc", "ncx") or "nav" in stem or stem.endswith("toc")


def toc_title_map(book: Any) -> dict[str, str]:
    """Flatten ``book.toc`` to ``{basename-without-fragment: title}``."""

    mapping: dict[str, str] = {}

    def _add(href: str | None, title: str | None) -> None:
        if not href or not title:
            return
        clean_href = href.split("#", 1)[0].strip()
        if not clean_href:
            return
        key = clean_href.rsplit("/", 1)[-1].lower()
        if key and key not in mapping:
            mapping[key] = title.strip()

    def _walk(entries: Any) -> None:
        if entries is None:
            return
        if isinstance(entries, (list, tuple)):
            for entry in entries:
                _walk(entry)
            return
        href = getattr(entries, "href", None)
        title = getattr(entries, "title", None)
        if isinstance(title, str) and href is not None:
            _add(str(href), title)
        # Sections nest children as (section, [children]) tuples; also
        # handle objects exposing a children/sublinks list.
        for attr in ("children", "sublinks", "items"):
            children = getattr(entries, attr, None)
            if isinstance(children, (list, tuple)):
                _walk(children)

    _walk(getattr(book, "toc", []))
    return mapping


def read_spine_documents(epub_path: Path) -> list[SpineDocument]:
    """Read the EPUB spine in order; parse each document's HTML blocks."""
    from ebooklib import epub as ebooklib_epub

    path = Path(epub_path)
    if not path.is_file():
        raise FileNotFoundError(f"EPUB not found: {path}")
    book = ebooklib_epub.read_epub(str(path))
    titles = toc_title_map(book)

    documents: list[SpineDocument] = []
    for idref, linear in list(getattr(book, "spine", [])):
        item = book.get_item_with_id(idref)
        if item is None:
            logger.info("%s: spine idref '%s' has no item; skipped", path.name, idref)
            continue
        get_content = getattr(item, "get_content", None)
        if get_content is None:
            continue  # Non-document spine entry (image etc.); skip quietly.
        href: str = str(item.get_name() or item.get_id() or idref)
        try:
            raw = item.get_content()
        except Exception as exc:
            logger.info("%s: could not read '%s': %s", path.name, href, exc)
            continue
        html = raw if isinstance(raw, (bytes, str)) else bytes(raw)
        has_images = (
            b"<img" in html[:200000].lower() if isinstance(html, bytes) else "<img" in html.lower()
        )
        base = href.rsplit("/", 1)[-1].lower()
        toc_title = titles.get(base)
        source = f"{path.name}:{href}"
        try:
            blocks = parse_html_blocks(html, source=source)
        except Exception as exc:
            logger.info("%s: failed to parse '%s': %s", path.name, href, exc)
            blocks = []
        documents.append(
            SpineDocument(
                item_id=str(item.get_id()),
                href=href,
                linear=str(linear),
                is_nav=_is_nav_item(item, href),
                has_images=bool(has_images),
                toc_title=toc_title,
                blocks=blocks,
            )
        )
    return documents
