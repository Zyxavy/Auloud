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

"""CP5: PDF text extraction tests (synthetic PDFs built in-test with fitz).

No fixture files are committed and no real book content is used: every PDF
is generated here with ``pymupdf`` (short self-made sentences), covering a
clean single-column file, headers/footers/page numbers, two-column reading
order, hyphenation, scanned (image-only) failure, outline vs fallback
chapters, determinism, a messy synthetic file, draft dispatch and a
FakeEngine end-to-end build.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import numpy as np
import pymupdf
import pytest
from typer.testing import CliRunner

import cli
from build import run_build
from bundle.validate import validate_bundle
from draft import DraftError, run_draft
from extract.pdf import (
    ScannedPdfError,
    extract_pdf_chapters,
    extract_pdf_with_quality,
)
from tts.base import TTSEngine

PAGE_W, PAGE_H = 595.0, 842.0


def _sentence(word: str) -> str:
    """One synthetic sentence (~10 words, test-only text)."""
    return f"The quiet {word} fox crosses the mossy hill at dawn."


def _body(word: str, sentences: int = 6) -> str:
    """One synthetic paragraph (inserted as a single text line)."""
    return " ".join([_sentence(word)] * sentences)


def _save(doc: pymupdf.Document, path: Path) -> Path:
    doc.save(str(path))
    doc.close()
    return path


def _insert_paras(
    page: pymupdf.Page, texts: list[str], *, top: float = 120.0, gap: float = 18.0
) -> float:
    """Insert wrapped paragraphs stacked from ``top``; return the next free y.

    ``insert_textbox`` wraps (unlike ``insert_text``, which clips long
    lines at the page edge), so multi-sentence test paragraphs survive
    whole. Single control lines (headers, numbers, headings) still use
    ``insert_text`` at the call site.
    """
    y = top
    for text in texts:
        rect = pymupdf.Rect(72, y, 523, PAGE_H - 60)
        unused = page.insert_textbox(rect, text, fontsize=11)
        unused_h = unused.height if hasattr(unused, "height") else float(unused)
        y += (rect.height - unused_h) + gap
    return y


def _chapter_text(chapter) -> str:
    """Every block and sentence text of one chapter, in order."""
    parts: list[str] = []
    for block in chapter.blocks or []:
        parts.append(block.text or "")
        for sentence in block.sentences:
            parts.append(sentence.text)
    return " ".join(parts)


def _all_text(result) -> str:
    """Every block and sentence text of an ExtractionResult, in order."""
    return " ".join(_chapter_text(c) for c in result.chapters)


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (build tests need real binaries)")


class FakeEngine(TTSEngine):
    """Deterministic stand-in: exact 100 ms sine per sentence (as test_build)."""

    def __init__(self, version: str = "fake-pdf") -> None:
        self._version = version

    @property
    def sample_rate(self) -> int:
        return 24_000

    @property
    def engine_version(self) -> str:
        return self._version

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        freq = 440.0 + (len(text) % 5) * 110.0
        t = np.arange(2400, dtype=np.float64) / 24_000
        return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def test_clean_single_column_extraction(tmp_path: Path) -> None:
    doc = pymupdf.open()
    for page_no in range(1, 4):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        _insert_paras(
            page,
            [f"Page {page_no} paragraph {para_no} " + _body("alder", 6) for para_no in range(1, 4)],
        )
    pdf = _save(doc, tmp_path / "clean.pdf")

    result = extract_pdf_chapters(pdf)

    assert len(result.chapters) == 1
    assert result.chapters[0].title == "Chapter 1"
    text = _all_text(result)
    assert "Page 2 paragraph 1" in text
    sids = [s.sid for b in (result.chapters[0].blocks or []) for s in b.sentences]
    assert sids == list(range(1, len(sids) + 1))


def test_headers_footers_page_numbers_stripped(tmp_path: Path) -> None:
    doc = pymupdf.open()
    for page_no in range(1, 5):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        page.insert_text((72, 60), "My Test Book", fontsize=10)
        _insert_paras(
            page,
            [
                f"Page {page_no} body paragraph {para_no} " + _body("bracken", 4)
                for para_no in range(1, 4)
            ],
        )
        page.insert_text((72, 770), "A Sample Tale Retold", fontsize=9)
        page.insert_text((72, 800), str(page_no), fontsize=10)
    pdf = _save(doc, tmp_path / "hf.pdf")

    result, quality = extract_pdf_with_quality(pdf, tiny_threshold=0)

    text = _all_text(result)
    assert "My Test Book" not in text
    assert "Sample Tale" not in text
    assert "Page 3 body paragraph 2" in text
    assert quality.header_lines_dropped == 4
    assert quality.footer_lines_dropped == 4
    assert quality.page_numbers_dropped == 4
    joined = "\n".join(result.drops)
    assert "header" in joined and "footer" in joined and "page number" in joined


def test_two_column_reading_order(tmp_path: Path) -> None:
    # Column-major insertion (left column first, then right), as typeset
    # PDFs lay out: each column forms its own block band.
    doc = pymupdf.open()
    page = doc.new_page(width=PAGE_W, height=PAGE_H)
    for i in range(6):
        page.insert_text((50, 100 + i * 15), f"LEFT column line {i + 1} here.")
    for i in range(6):
        page.insert_text((320, 100 + i * 15), f"RIGHT column line {i + 1} here.")
    pdf = _save(doc, tmp_path / "cols.pdf")

    result = extract_pdf_chapters(pdf)

    text = _all_text(result)
    assert "LEFT column line 6" in text and "RIGHT column line 1" in text
    assert text.index("LEFT column line 6") < text.index("RIGHT column line 1")


def test_hyphenation_joined(tmp_path: Path) -> None:
    doc = pymupdf.open()
    page = doc.new_page(width=PAGE_W, height=PAGE_H)
    page.insert_text((72, 120), "The hill-")
    page.insert_text((72, 135), "side path winds on through alder trees.")
    page.insert_text((72, 165), "A well-")
    page.insert_text((72, 180), "Known fox rests by the mossy stone wall.")
    pdf = _save(doc, tmp_path / "hyphen.pdf")

    result = extract_pdf_chapters(pdf)

    text = _all_text(result)
    assert "hillside" in text
    assert "hill-" not in text
    # Uppercase continuations are not hyphen splits: the hyphen survives.
    assert "well-" in text


def _image_page(doc: pymupdf.Document) -> None:
    pix = pymupdf.Pixmap(pymupdf.csRGB, pymupdf.Rect(0, 0, 50, 50), 0)
    page = doc.new_page(width=PAGE_W, height=PAGE_H)
    page.insert_image(pymupdf.Rect(72, 100, 200, 200), pixmap=pix)


def test_scanned_pdf_fails_with_clean_message(tmp_path: Path) -> None:
    doc = pymupdf.open()
    _image_page(doc)
    _image_page(doc)
    _image_page(doc)
    pdf = _save(doc, tmp_path / "scanned.pdf")

    with pytest.raises(ScannedPdfError, match=r"no text layer \(scanned PDF\?\)"):
        extract_pdf_chapters(pdf)
    try:
        extract_pdf_chapters(pdf)
    except ScannedPdfError as exc:
        assert "scanned.pdf" in str(exc)
        assert "3 of 3" in str(exc)
    else:  # pragma: no cover - the match above already proves the raise
        raise AssertionError("expected ScannedPdfError")


def test_partly_scanned_pdf_keeps_text_pages(tmp_path: Path) -> None:
    doc = pymupdf.open()
    _image_page(doc)
    for page_no in range(1, 4):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        _insert_paras(page, [f"Readable page {page_no} " + _body("cedar", 6)])
    _image_page(doc)
    pdf = _save(doc, tmp_path / "mixed.pdf")

    result, quality = extract_pdf_with_quality(pdf)

    assert quality.scanned_pages == 2
    assert "Readable page 2" in _all_text(result)
    assert any("scanned page" in drop for drop in result.drops)


def test_outline_chapters_and_nested_outline(tmp_path: Path) -> None:
    doc = pymupdf.open()
    for page_no, marker in ((1, "alpha"), (2, "alpha"), (3, "beta"), (4, "beta")):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        _insert_paras(
            page,
            [
                f"Marker {marker} page {page_no} " + _body(marker, 6),
                _body(f"{marker}-second", 6),
            ],
        )
    doc.set_toc([[1, "First Part", 1], [1, "Second Part", 3]])
    pdf = _save(doc, tmp_path / "outline.pdf")

    result = extract_pdf_chapters(pdf)

    assert [c.title for c in result.chapters] == ["First Part", "Second Part"]
    texts = [_chapter_text(c) for c in result.chapters]
    assert "Marker alpha" in texts[0] and "Marker beta" not in texts[0]
    assert "Marker beta" in texts[1] and "Marker alpha" not in texts[1]


def test_nested_outline_uses_top_level_only(tmp_path: Path) -> None:
    doc = pymupdf.open()
    for page_no in range(1, 3):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        _insert_paras(page, [f"Body page {page_no} " + _body("alder", 8)])
    doc.set_toc([[1, "Whole Part", 1], [2, "Deep A", 1], [2, "Deep B", 2]])
    pdf = _save(doc, tmp_path / "nested.pdf")

    result = extract_pdf_chapters(pdf)

    assert [c.title for c in result.chapters] == ["Whole Part"]


def test_fallback_fixed_page_ranges(tmp_path: Path) -> None:
    doc = pymupdf.open()
    for page_no in range(1, 26):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        page.insert_text((72, 120), f"Range body words for page {page_no} " + _body("harbor", 2))
    pdf = _save(doc, tmp_path / "ranges.pdf")

    result = extract_pdf_chapters(pdf, tiny_threshold=0)

    assert [c.title for c in result.chapters] == ["Chapter 1", "Chapter 2"]
    text = _all_text(result)
    assert "Range body words for page 1 " in text
    assert "Range body words for page 25 " in text


def test_heading_heuristic_chapters_without_outline(tmp_path: Path) -> None:
    doc = pymupdf.open()
    headings = {1: "CHAPTER ONE", 3: "CHAPTER TWO"}
    for page_no in range(1, 5):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        top = 120.0
        if page_no in headings:
            page.insert_text((72, top), headings[page_no], fontsize=14)
            top += 40.0
        _insert_paras(
            page,
            [f"Story page {page_no} part {part} " + _body("elm", 8) for part in (1, 2)],
            top=top,
        )
    pdf = _save(doc, tmp_path / "headings.pdf")

    result = extract_pdf_chapters(pdf)

    assert [c.title for c in result.chapters] == ["CHAPTER ONE", "CHAPTER TWO"]


def test_extract_twice_identical(tmp_path: Path) -> None:
    doc = pymupdf.open()
    for page_no in range(1, 5):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        page.insert_text((72, 60), "Repeat Head", fontsize=10)
        _insert_paras(
            page,
            [f"Determinism page {page_no} part {part} " + _body("pine", 6) for part in (1, 2)],
        )
        page.insert_text((72, 800), str(page_no), fontsize=10)
    doc.set_toc([[1, "Half One", 1], [1, "Half Two", 3]])
    pdf = _save(doc, tmp_path / "deterministic.pdf")

    first = extract_pdf_chapters(pdf)
    second = extract_pdf_chapters(pdf)

    assert [c.to_dict() for c in first.chapters] == [c.to_dict() for c in second.chapters]
    assert first.drops == second.drops


def _two_page_novel(tmp_path: Path, name: str = "novel.pdf") -> Path:
    doc = pymupdf.open()
    for page_no in range(1, 3):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        page.insert_text((72, 60), "Novel Head", fontsize=10)
        _insert_paras(
            page,
            [
                f"Novel page {page_no} part {part} " + _body("willow", 4) + " Said John."
                for part in range(1, 7)
            ],
        )
        page.insert_text((72, 800), str(page_no), fontsize=10)
    doc.set_metadata({"title": "PDF Novel", "author": "PDF Author"})
    return _save(doc, tmp_path / name)


def test_draft_pdf_end_to_end(tmp_path: Path) -> None:
    pdf = _two_page_novel(tmp_path)

    result = run_draft(pdf, work_root=tmp_path / ".scribe")

    assert result.title == "PDF Novel"
    assert result.author == "PDF Author"
    assert len(result.chapters) == 1
    assert result.pdf_quality is not None and result.pdf_quality.pages == 2
    report = result.report_path.read_text(encoding="utf-8")
    assert "## PDF quality" in report
    assert "Pages: 2" in report
    data = json.loads(result.script_path.read_text(encoding="utf-8"))
    assert len(data["chapters"]) == 1


def test_draft_scanned_pdf_clean_failure(tmp_path: Path) -> None:
    doc = pymupdf.open()
    _image_page(doc)
    _image_page(doc)
    pdf = _save(doc, tmp_path / "scanned.pdf")

    with pytest.raises(DraftError, match=r"no text layer \(scanned PDF\?\)") as excinfo:
        run_draft(pdf, work_root=tmp_path / ".scribe")
    assert "scanned.pdf" in str(excinfo.value)


def test_cli_draft_scanned_pdf_no_traceback(tmp_path: Path) -> None:
    doc = pymupdf.open()
    _image_page(doc)
    _image_page(doc)
    _image_page(doc)
    pdf = _save(doc, tmp_path / "scanned.pdf")

    invoked = CliRunner().invoke(cli.app, ["draft", str(pdf), "--work-dir", str(tmp_path / "work")])

    assert invoked.exit_code == 1, invoked.output
    combined = (invoked.output or "") + (getattr(invoked, "stderr", "") or "")
    assert "draft failed" in combined.lower()
    assert "no text layer" in combined
    assert "Traceback" not in combined


def test_messy_pdf_quality_warning_and_footnote_drop(tmp_path: Path) -> None:
    doc = pymupdf.open()
    shorts = ["Yes.", "No, never.", "She nodded once.", "Run!", "Hush now."]
    for page_no in range(1, 4):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        y = 120.0
        for line in shorts:
            page.insert_text((72, y), f"{line} (page {page_no})")
            y += 25.0
        page.insert_text((72, 700), "1 A brief note about the alder fox.")
        page.insert_text((72, 770), "Messy Tale", fontsize=9)
    pdf = _save(doc, tmp_path / "messy.pdf")

    result, quality = extract_pdf_with_quality(pdf, tiny_threshold=0)

    assert quality.messy
    assert any("short-line ratio" in reason for reason in quality.messy_reasons)
    text = _all_text(result)
    assert "brief note about the alder fox" not in text
    assert "Messy Tale" not in text
    assert "She nodded once." in text
    assert quality.footnotes_dropped == 3
    assert quality.footer_lines_dropped == 3


def test_pdf_build_end_to_end(tmp_path: Path) -> None:
    _needs_ffmpeg()
    doc = pymupdf.open()
    for page_no in range(1, 3):
        page = doc.new_page(width=PAGE_W, height=PAGE_H)
        _insert_paras(
            page,
            [f"Build page {page_no} part {part} " + _body("birch", 4) for part in range(1, 7)],
        )
    pdf = _save(doc, tmp_path / "build.pdf")

    result = run_build(
        pdf,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "bundle",
        engine=FakeEngine(),
        show_progress=False,
    )

    bundle = tmp_path / "bundle"
    manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["type"] == "pdf"
    assert manifest["source"]["file"] == "source/book.pdf"
    assert (bundle / "source" / "book.pdf").read_bytes() == pdf.read_bytes()
    validation = validate_bundle(bundle)
    assert validation.ok, validation.errors
    assert result.chapters_total == 1
