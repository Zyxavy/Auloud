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

"""SW3: EPUB extraction and cleaning tests (synthetic mini-EPUB only).

Builds a generated EPUB in-test (synthetic text, never real book content)
with headings, paras, a quote, breaks, italics/bold, boilerplate, an empty
para, a footnote marker, a tiny chapter, an image-only page and a nav page.
Asserts expected chapters/blocks/spans and that boilerplate is dropped.
"""

from __future__ import annotations

from pathlib import Path

from ebooklib import epub

from bundle.models import ChapterFile
from extract.clean import (
    count_words,
    extract_epub_chapters,
    is_boilerplate,
    is_break_text,
    normalize_text,
)


def _long_para(word: str, repeats: int) -> str:
    """Synthetic long paragraph: one sentence repeated (test-only text)."""
    sentence = f"The quiet {word} fox crosses the mossy hill at dawn."
    return " ".join([sentence] * repeats)


def _make_mini_epub(path: Path) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-mini-book")
    book.set_title("Test Mini Book")
    book.set_language("en")
    book.add_author("Test Author")

    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = (
        "<h1>Chapter One</h1>"
        f"<p>{_long_para('alder', 25)}</p>"
        "<p>She left <em>early</em> and <strong>ran fast</strong> home.</p>"
        "<blockquote><p>The night was cold and still.</p></blockquote>"
        "<hr/>"
        '<p class="center">* * *</p>'
        "<p></p><p>   </p>"
        "<p>Copyright © 2026 Example Press. All rights reserved.</p>"
        "<p>ISBN 978-0-00-000000-0</p>"
        '<p>The trail continues.<sup><a href="#fn1">[1]</a></sup> And the fox follows.</p>'
    )
    ch2 = epub.EpubHtml(title="Tiny Interlude", file_name="ch2.xhtml", lang="en")
    ch2.content = "<h2>Tiny Interlude</h2><p>A brief pause.</p>"
    ch3 = epub.EpubHtml(title="Chapter Three", file_name="ch3.xhtml", lang="en")
    ch3.content = f"<h1>Chapter Three</h1><p>{_long_para('bracken', 25)}</p>"
    ch4 = epub.EpubHtml(title="Untitled", file_name="ch4.xhtml", lang="en")
    ch4.content = f"<p>{_long_para('cedar', 25)}</p><p>Second untitled paragraph stays.</p>"
    imgpage = epub.EpubHtml(title="Image", file_name="imgpage.xhtml", lang="en")
    imgpage.content = '<div><img src="pic.jpg" alt="a picture"/></div>'

    for item in (ch1, ch2, ch3, ch4, imgpage):
        book.add_item(item)
    book.toc = [
        epub.Link("ch1.xhtml", "Chapter One", "ch1"),
        epub.Link("ch2.xhtml", "Tiny Interlude", "ch2"),
        epub.Link("ch3.xhtml", "Chapter Three", "ch3"),
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    # Nav first, then an image-only page, then content (exercises drop rules).
    book.spine = ["nav", imgpage, ch1, ch2, ch3, ch4]
    epub.write_epub(str(path), book)
    return path


def _sentences(chapter: ChapterFile) -> list:
    return [s for b in (chapter.blocks or []) for s in b.sentences]


def _make_non_linear_epub(path: Path) -> Path:
    """EPUB with a non-linear spine item (synthetic text only)."""
    book = epub.EpubBook()
    book.set_identifier("test-non-linear")
    book.set_title("Test Non Linear")
    book.set_language("en")

    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = f"<h1>Chapter One</h1><p>{_long_para('alder', 25)}</p>"
    hidden = epub.EpubHtml(title="Hidden Cover", file_name="cover-page.xhtml", lang="en")
    hidden.content = f"<h1>Hidden Cover Text</h1><p>{_long_para('hidden', 25)}</p>"
    ch2 = epub.EpubHtml(title="Chapter Two", file_name="ch2.xhtml", lang="en")
    ch2.content = f"<h1>Chapter Two</h1><p>{_long_para('bracken', 25)}</p>"

    for item in (ch1, hidden, ch2):
        book.add_item(item)
    book.toc = [
        epub.Link("ch1.xhtml", "Chapter One", "ch1"),
        epub.Link("ch2.xhtml", "Chapter Two", "ch2"),
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [ch1, (hidden, "no"), ch2]
    epub.write_epub(str(path), book)
    return path


def _make_naval_epub(path: Path) -> Path:
    """EPUB with a real chapter whose filename contains 'nav' (synthetic)."""
    book = epub.EpubBook()
    book.set_identifier("test-naval")
    book.set_title("Test Naval")
    book.set_language("en")

    naval = epub.EpubHtml(title="Naval History", file_name="naval-history.xhtml", lang="en")
    naval.content = f"<h1>Naval History</h1><p>{_long_para('harbor', 25)}</p>"
    book.add_item(naval)
    book.toc = [epub.Link("naval-history.xhtml", "Naval History", "naval")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = ["nav", naval]
    epub.write_epub(str(path), book)
    return path


def test_mini_epub_chapters_blocks_spans(tmp_path: Path) -> None:
    epub_path = _make_mini_epub(tmp_path / "mini.epub")
    result = extract_epub_chapters(epub_path)

    # Nav dropped, image-only dropped, tiny ch2 merged into ch3:
    # ch1 -> 1, ch3+ch2 -> 2, ch4 (untitled) -> 3.
    assert len(result.chapters) == 3
    first, second, third = result.chapters
    assert first.title == "Chapter One"
    assert second.title == "Chapter Three"
    assert third.title == "Chapter 3"

    # --- First chapter blocks ---
    kinds = [b.type for b in (first.blocks or [])]
    assert kinds == ["heading", "para", "para", "quote", "break", "break", "para"]
    assert (first.blocks or [])[0].text == "Chapter One"
    assert (first.blocks or [])[0].level == 1

    paras = [b for b in (first.blocks or []) if b.type == "para"]
    assert len(paras) == 3
    formatted = paras[1].sentences[0]
    assert formatted.text == "She left early and ran fast home."
    by_style = {s.style: s for s in formatted.spans}
    assert set(by_style) == {"italic", "bold"}
    assert formatted.text[by_style["italic"].start : by_style["italic"].end] == "early"
    assert formatted.text[by_style["bold"].start : by_style["bold"].end] == "ran fast"
    assert (by_style["italic"].start, by_style["italic"].end) == (9, 14)
    assert (by_style["bold"].start, by_style["bold"].end) == (19, 27)

    quotes = [b for b in (first.blocks or []) if b.type == "quote"]
    assert len(quotes) == 1
    assert quotes[0].sentences[0].text == "The night was cold and still."

    # Boilerplate, empty paras, and footnote markers are gone.
    all_text = " ".join(
        [(b.text or "") for b in (first.blocks or [])] + [s.text for s in _sentences(first)]
    )
    assert "Copyright" not in all_text
    assert "ISBN" not in all_text
    assert "[1]" not in all_text
    assert "The trail continues." in all_text
    assert "And the fox follows." in all_text
    assert all((b.text or "").strip() != "" for b in (first.blocks or []) if b.type == "heading")

    # Sentence ids are consecutive across the chapter.
    assert [s.sid for s in _sentences(first)] == [1, 2, 3, 4]

    # --- Tiny chapter merged into the next ---
    second_headings = [b.text for b in (second.blocks or []) if b.type == "heading"]
    assert "Tiny Interlude" in second_headings
    assert "Chapter Three" in second_headings
    # Merged blocks come first (tiny chapter prepended).
    assert second_headings[0] == "Tiny Interlude"
    assert any("A brief pause." in s.text for s in _sentences(second))

    # --- Drops logged ---
    joined = "\n".join(result.drops)
    assert "nav/TOC" in joined
    assert "image-only" in joined
    assert "empty paragraph" in joined
    assert "boilerplate" in joined
    assert "merged tiny" in joined.lower() or "merged trailing" in joined.lower()


def test_non_linear_spine_items_skipped(tmp_path: Path) -> None:
    result = extract_epub_chapters(_make_non_linear_epub(tmp_path / "nonlinear.epub"))
    assert [c.title for c in result.chapters] == ["Chapter One", "Chapter Two"]
    all_text = " ".join(
        [(b.text or "") for c in result.chapters for b in (c.blocks or [])]
        + [s.text for c in result.chapters for b in (c.blocks or []) for s in b.sentences]
    )
    assert "Hidden Cover Text" not in all_text
    assert any("non-linear" in d for d in result.drops)


def test_naval_history_chapter_survives(tmp_path: Path) -> None:
    result = extract_epub_chapters(_make_naval_epub(tmp_path / "naval.epub"))
    assert len(result.chapters) == 1
    assert result.chapters[0].title == "Naval History"
    assert not any("naval-history" in d and "nav/TOC" in d for d in result.drops)


def test_drops_include_footnote_strips(tmp_path: Path) -> None:
    result = extract_epub_chapters(_make_mini_epub(tmp_path / "mini.epub"))
    assert any("footnote" in d for d in result.drops)


def test_normalize_keeps_curly_quotes() -> None:
    assert normalize_text("  “Hello”   world \n") == "“Hello” world"
    # NFC: e + combining acute becomes é (single code point).
    assert normalize_text("café") == "café"


def test_boilerplate_and_break_and_words() -> None:
    assert is_boilerplate("Copyright © 2026 Example Press. All rights reserved.")
    assert is_boilerplate("ISBN 978-0-00-000000-0")
    assert is_boilerplate("This ebook was produced by Project Gutenberg volunteers.")
    assert not is_boilerplate("She left early and ran fast home.")
    assert is_break_text("* * *")
    assert is_break_text("⁂")
    assert not is_break_text("She left early.")
    assert count_words("") == 0
    assert count_words("  a  b c ") == 3
