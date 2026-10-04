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

"""CP6: PDF text-path golden contract (``spec/fixtures/pdf-golden/``).

Verifies spec v1.1 additive change: blocks+pages chapters validate, sentence
``page`` provenance survives draft/build, the writer computes ``pages`` from
timings, EPUB sentences omit ``page`` (absent, never null), and the PDF-safe
remap preserves pages. The committed fixture was built by
``scribe/dev/make_pdf_golden.py`` (real pipeline, fake synth, real ffmpeg);
tests here only READ it (plus synthetic in-test PDFs/EPUBs), never rebuild it.
"""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import pytest
from ebooklib import epub

from build import remap_chapter_speakers, remap_pdf_chapter_speakers
from bundle.inspect import format_inspect, inspect_bundle
from bundle.models import Block, ChapterFile, Sentence
from bundle.validate import AudioProbe, validate_bundle
from bundle.writer import pages_from_sentences
from draft import run_draft
from text.cast import ResolvedVoice

FIXTURE = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "pdf-golden"

GOLDEN_BOOK_ID = "4636f63e-50e5-576d-8cfc-c26e82d659d3"
GOLDEN_DURATION_MS = 2150


def _stub_probe(durations: dict[str, int] | None = None) -> object:
    durations = durations or {}

    def _probe(path: Path) -> AudioProbe:
        return AudioProbe(
            codec="mp3",
            channels=1,
            sample_rate=24000,
            bit_rate_bps=64000,
            bit_rate_from_stream=True,
            duration_ms=durations.get(Path(path).name, GOLDEN_DURATION_MS),
            cbr_frames=True,
        )

    return _probe


def _write_bundle(
    root: Path,
    chapter: dict,
    voices: dict,
    duration_ms: int = GOLDEN_DURATION_MS,
    source_bytes: bytes = b"fake-pdf",
    source_name: str = "source/book.pdf",
) -> Path:
    (root / "audio").mkdir(parents=True, exist_ok=True)
    (root / "text").mkdir(parents=True, exist_ok=True)
    (root / "source").mkdir(parents=True, exist_ok=True)
    (root / "source" / Path(source_name).name).write_bytes(source_bytes)
    manifest = {
        "spec_version": "1.1",
        "id": "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
        "title": "PDF Test",
        "type": "pdf",
        "source": {"file": source_name, "sha256": hashlib.sha256(source_bytes).hexdigest()},
        "audio": {
            "format": "mp3",
            "channels": 1,
            "sample_rate": 24000,
            "bitrate_kbps": 64,
            "cbr": True,
        },
        "voices": voices,
        "chapters": [
            {
                "index": 1,
                "title": "Chapter 1",
                "audio": "audio/ch001.mp3",
                "text": "text/ch001.json",
                "duration_ms": duration_ms,
            }
        ],
    }
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (root / "text" / "ch001.json").write_text(json.dumps(chapter), encoding="utf-8")
    (root / "audio" / "ch001.mp3").write_bytes(b"")
    return root


def _two_voice_map() -> dict:
    return {
        "narrator": {"engine": "kokoro", "voice": "am_onyx", "speed": 1.0, "pitch": 1.0},
        "Alice": {"engine": "kokoro", "voice": "bf_isabella", "speed": 1.0, "pitch": 1.0},
    }


def _paged_chapter() -> dict:
    return {
        "spec_version": "1.1",
        "chapter": 1,
        "title": "Chapter 1",
        "duration_ms": GOLDEN_DURATION_MS,
        "blocks": [
            {
                "id": 1,
                "type": "para",
                "sentences": [
                    {
                        "sid": 1,
                        "speaker": "narrator",
                        "start_ms": 0,
                        "end_ms": 100,
                        "text": "First.",
                        "page": 1,
                    },
                    {
                        "sid": 2,
                        "speaker": "Alice",
                        "start_ms": 600,
                        "end_ms": 700,
                        "text": "Second.",
                        "page": 2,
                    },
                ],
            }
        ],
        "pages": [{"page": 1, "start_ms": 0}, {"page": 2, "start_ms": 600}],
    }


# ---------------------------------------------------------------------------
# Golden fixture contract (real probe, never skipped)
# ---------------------------------------------------------------------------


def test_pdf_golden_present() -> None:
    for rel in (
        "manifest.json",
        "README.md",
        "audio/ch001.mp3",
        "text/ch001.json",
        "source/book.pdf",
    ):
        assert (FIXTURE / rel).is_file(), f"golden file missing: {rel}"


def test_pdf_golden_pins() -> None:
    manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["id"] == GOLDEN_BOOK_ID
    assert manifest["spec_version"] == "1.1"
    assert manifest["type"] == "pdf"
    assert manifest["source"]["file"] == "source/book.pdf"
    assert len(manifest["chapters"]) == 1
    entry = manifest["chapters"][0]
    assert entry["duration_ms"] == GOLDEN_DURATION_MS
    assert entry["title"] == "Chapter 1"
    assert set(manifest["voices"]) == {"narrator", "Alice"}
    assert manifest["voices"]["narrator"]["voice"] == "am_onyx"
    assert manifest["voices"]["Alice"]["voice"] == "bf_isabella"

    chapter = json.loads((FIXTURE / "text" / "ch001.json").read_text(encoding="utf-8"))
    assert chapter["spec_version"] == "1.1"
    sentences = [s for b in chapter["blocks"] for s in b.get("sentences", [])]
    assert [s["sid"] for s in sentences] == [1, 2, 3, 4]
    assert [s["speaker"] for s in sentences] == ["narrator", "Alice", "narrator", "narrator"]
    assert [s["page"] for s in sentences] == [1, 1, 1, 2]
    assert all(s["speaker"] in manifest["voices"] for s in sentences)
    for s in sentences:
        for draft_key in ("kind", "confidence", "quote", "split_pair"):
            assert draft_key not in s
    assert sentences[1]["text"] + sentences[2]["text"] == '"We should leave," Alice said.'
    assert chapter["pages"] == [{"page": 1, "start_ms": 0}, {"page": 2, "start_ms": 1550}]
    assert sentences[3]["start_ms"] == 1550 == chapter["pages"][1]["start_ms"]


def test_pdf_golden_valid_with_real_probe() -> None:
    if shutil.which("ffprobe") is None:
        raise AssertionError(
            "ffprobe not on PATH (winget install ffmpeg): the golden "
            "contract test must run the real probe, never skip it"
        )
    result = validate_bundle(FIXTURE)
    assert result.ok, result.errors


def test_pdf_golden_inspect_shows_pages() -> None:
    result = inspect_bundle(FIXTURE)
    assert result.speakers == {"narrator": 3, "Alice": 1}
    text = format_inspect(result)
    assert "pages: 1-2 (2 pages)" in text


# ---------------------------------------------------------------------------
# Validator: v1.1 pages rules + 1.0/1.1 acceptance
# ---------------------------------------------------------------------------


def test_validator_blocks_plus_pages_valid(tmp_path: Path) -> None:
    root = _write_bundle(tmp_path, _paged_chapter(), _two_voice_map())
    assert validate_bundle(root, probe=_stub_probe()).ok


def test_validator_v10_still_valid(tmp_path: Path) -> None:
    chapter = _paged_chapter()
    chapter["spec_version"] = "1.0"
    del chapter["pages"]
    for block in chapter["blocks"]:
        for sentence in block.get("sentences", []):
            sentence.pop("page", None)
    root = _write_bundle(tmp_path, chapter, _two_voice_map())
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    manifest["spec_version"] = "1.0"
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    assert validate_bundle(root, probe=_stub_probe()).ok


def test_validator_rejects_unknown_spec_version(tmp_path: Path) -> None:
    root = _write_bundle(tmp_path, _paged_chapter(), _two_voice_map())
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    manifest["spec_version"] = "2.0"
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    result = validate_bundle(root, probe=_stub_probe())
    assert any("spec_version" in e and "2.0" in e for e in result.errors)


def test_validator_pages_must_match_first_sentence(tmp_path: Path) -> None:
    chapter = _paged_chapter()
    chapter["pages"] = [{"page": 1, "start_ms": 0}, {"page": 2, "start_ms": 650}]
    root = _write_bundle(tmp_path, chapter, _two_voice_map())
    result = validate_bundle(root, probe=_stub_probe())
    assert any("does not match first sentence" in e for e in result.errors)


def test_validator_pages_out_of_order_rejected(tmp_path: Path) -> None:
    chapter = _paged_chapter()
    chapter["pages"] = [{"page": 2, "start_ms": 0}, {"page": 1, "start_ms": 600}]
    root = _write_bundle(tmp_path, chapter, _two_voice_map())
    result = validate_bundle(root, probe=_stub_probe())
    assert any("out of order" in e for e in result.errors)


def test_validator_sentence_page_missing_from_marks(tmp_path: Path) -> None:
    chapter = _paged_chapter()
    chapter["pages"] = [{"page": 1, "start_ms": 0}]
    root = _write_bundle(tmp_path, chapter, _two_voice_map())
    result = validate_bundle(root, probe=_stub_probe())
    assert any("missing from pages marks" in e for e in result.errors)


def test_validator_sentence_page_must_be_positive(tmp_path: Path) -> None:
    chapter = _paged_chapter()
    chapter["blocks"][0]["sentences"][0]["page"] = 0
    root = _write_bundle(tmp_path, chapter, _two_voice_map())
    result = validate_bundle(root, probe=_stub_probe())
    assert any("invalid page" in e for e in result.errors)


# ---------------------------------------------------------------------------
# Writer: pages computed from timings; EPUB omits pages
# ---------------------------------------------------------------------------


def test_pages_from_sentences_groups_first_starts() -> None:
    chapter = ChapterFile(
        spec_version="1.1",
        chapter=1,
        title="T",
        duration_ms=2000,
        blocks=[
            Block(
                id=1,
                type="para",
                sentences=[
                    Sentence(
                        sid=1, speaker="narrator", start_ms=0, end_ms=100, text="A.", page=1
                    ),
                    Sentence(
                        sid=2, speaker="narrator", start_ms=600, end_ms=700, text="B.", page=1
                    ),
                    Sentence(
                        sid=3,
                        speaker="narrator",
                        start_ms=1550,
                        end_ms=1650,
                        text="C.",
                        page=2,
                    ),
                ],
            )
        ],
    )
    marks = pages_from_sentences(chapter)
    assert marks is not None
    assert [(m.page, m.start_ms) for m in marks] == [(1, 0), (2, 1550)]


def test_pages_from_sentences_none_when_no_provenance() -> None:
    chapter = ChapterFile(
        spec_version="1.1",
        chapter=1,
        title="T",
        duration_ms=1000,
        blocks=[
            Block(
                id=1,
                type="para",
                sentences=[
                    Sentence(sid=1, speaker="narrator", start_ms=0, end_ms=500, text="A.")
                ],
            )
        ],
    )
    assert pages_from_sentences(chapter) is None


# ---------------------------------------------------------------------------
# Remap: EPUB drops pages, PDF-safe requires and carries them
# ---------------------------------------------------------------------------


def _timed_chapter(pages: list[int] | None) -> ChapterFile:
    sentences = []
    for pos, page in enumerate(pages or [None, None], start=1):
        sentences.append(
            Sentence(
                sid=pos,
                speaker="Alice" if pos == 2 else "narrator",
                start_ms=(pos - 1) * 600,
                end_ms=(pos - 1) * 600 + 100,
                text=f"S{pos}.",
                page=page,
            )
        )
    return ChapterFile(
        spec_version="1.1",
        chapter=1,
        title="T",
        duration_ms=2000,
        blocks=[Block(id=1, type="para", sentences=sentences)],
    )


def test_remap_epub_preserves_page_but_drops_marks() -> None:
    chapter = _timed_chapter([1, 2])
    plan = {
        1: ResolvedVoice("narrator", "am_onyx", 1.0),
        2: ResolvedVoice("Alice", "bf_isabella", 1.0),
    }
    remapped = remap_chapter_speakers(chapter, plan)
    assert [s.page for s in remapped.sentences_in_order()] == [1, 2]
    assert remapped.pages is None


def test_remap_pdf_requires_pages_and_carries_marks() -> None:
    from bundle.models import PageEntry

    chapter = _timed_chapter([1, 2])
    chapter.pages = [PageEntry(page=1, start_ms=0), PageEntry(page=2, start_ms=600)]
    plan = {
        1: ResolvedVoice("narrator", "am_onyx", 1.0),
        2: ResolvedVoice("Alice", "bf_isabella", 1.0),
    }
    remapped = remap_pdf_chapter_speakers(chapter, plan)
    assert [s.speaker for s in remapped.sentences_in_order()] == ["narrator", "Alice"]
    assert remapped.pages is not None and [p.page for p in remapped.pages] == [1, 2]

    bare = _timed_chapter([None, None])
    with pytest.raises(Exception, match="missing 1-based page"):
        remap_pdf_chapter_speakers(bare, plan)


# ---------------------------------------------------------------------------
# Draft: PDF attaches pages, EPUB omits the key entirely
# ---------------------------------------------------------------------------


def _make_epub(path: Path) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-pdf-golden-epub")
    book.set_title("EPUB No Pages")
    book.set_language("en")
    book.add_author("EPUB Author")
    body = " ".join(["The quiet alder fox crosses the mossy hill at dawn."] * 25)
    item = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    item.content = f"<h1>Chapter One</h1><p>{body}</p>"
    book.add_item(item)
    book.toc = [epub.Link("ch1.xhtml", "Chapter One", "ch1")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [item]
    epub.write_epub(str(path), book)
    return path


def test_draft_epub_sentences_have_no_page_key(tmp_path: Path) -> None:
    epub_path = _make_epub(tmp_path / "mini.epub")
    result = run_draft(epub_path, work_root=tmp_path / ".scribe")
    data = json.loads(result.script_path.read_text(encoding="utf-8"))
    for raw_chapter in data["chapters"]:
        for block in raw_chapter.get("blocks", []):
            for sentence in block.get("sentences", []):
                assert "page" not in sentence, f"EPUB sentence must omit page: {sentence}"


def test_draft_pdf_sentences_carry_pages(tmp_path: Path) -> None:
    import pymupdf

    doc = pymupdf.open()
    for page_no in (1, 2):
        page = doc.new_page(width=595.0, height=842.0)
        rect = pymupdf.Rect(72, 120, 523, 782)
        page.insert_textbox(
            rect,
            " ".join(
                [f"Page {page_no} marker text."]
                + ["The quiet alder fox crosses the mossy hill at dawn."] * 10
            ),
            fontsize=11,
        )
    pdf = tmp_path / "mini.pdf"
    doc.save(str(pdf))
    doc.close()
    result = run_draft(pdf, work_root=tmp_path / ".scribe")
    data = json.loads(result.script_path.read_text(encoding="utf-8"))
    paged = [
        s.get("page")
        for c in data["chapters"]
        for b in c.get("blocks", [])
        for s in b.get("sentences", [])
    ]
    assert paged and all(isinstance(p, int) and p >= 1 for p in paged)
    assert set(paged) == {1, 2}
