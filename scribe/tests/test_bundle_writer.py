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

"""SW8: bundle writer + manifest tests.

Strategy: synthetic EPUBs (with/without cover) supply the source file,
metadata and cover; chapters are hand-built ``ChapterFile`` objects (the
parsed+split shape), timed through the real SW7 assembly path with a fake
synth of exact 100 ms tones, and encoded with real ffmpeg. The written
bundle is checked with the REAL ``validate_bundle`` probe (no stubs), so
these tests prove the writer emits spec-valid bundles end to end.

Cover-absent is covered by the main integration test (no-cover EPUB);
cover-present and the JPEG-sniff fallback have dedicated tests.
"""

from __future__ import annotations

import json
import shutil
from datetime import datetime
from pathlib import Path

import numpy as np
import pytest
from ebooklib import epub

import cli
from audio.assemble import AssembledChapter, assemble_chapter
from audio.encode import encode_assembled_chapter
from bundle import writer
from bundle.models import Block, ChapterFile, Sentence
from bundle.validate import ValidationResult, validate_bundle
from bundle.writer import BundleWriteError, extract_cover, write_bundle
from draft import book_id_for_file, sha256_of_file
from text.cast import default_cast
from tts.base import SAMPLE_RATE

SR = SAMPLE_RATE

#: Minimal JPEG (SOI + JFIF header + EOI): enough for magic sniff + byte equality.
MIN_JPEG = (
    b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
    b"\xff\xd9"
)


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (writer tests need real binaries)")


def _sentence(sid: int, text: str) -> Sentence:
    return Sentence(sid=sid, speaker="narrator", start_ms=0, end_ms=0, text=text)


def _chapter(index: int, title: str, texts: list[str]) -> ChapterFile:
    """Heading (first sentence) + para (rest), sids 1..N across the chapter."""
    sentences = [_sentence(sid, text) for sid, text in enumerate(texts, start=1)]
    return ChapterFile(
        spec_version="1.0",
        chapter=index,
        title=title,
        duration_ms=0,
        blocks=[
            Block(id=1, type="heading", level=1, text=title, sentences=sentences[:1]),
            Block(id=2, type="para", sentences=sentences[1:]),
        ],
    )


def _make_epub(
    path: Path,
    *,
    title: str = "Writer Test Book",
    author: str | None = "Writer Author",
    cover: bytes | None = None,
    attach_jpeg: bytes | None = None,
) -> Path:
    """Tiny two-chapter EPUB; optional real cover or attached loose JPEG."""
    book = epub.EpubBook()
    book.set_identifier("test-writer-book")
    book.set_title(title)
    book.set_language("en")
    if author is not None:
        book.add_author(author)
    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = "<h1>Chapter One</h1><p>The rain had not stopped.</p>"
    ch2 = epub.EpubHtml(title="Chapter Two", file_name="ch2.xhtml", lang="en")
    ch2.content = "<h1>Chapter Two</h1><p>Dawn broke over the hill.</p>"
    for item in (ch1, ch2):
        book.add_item(item)
    book.toc = [
        epub.Link("ch1.xhtml", "Chapter One", "ch1"),
        epub.Link("ch2.xhtml", "Chapter Two", "ch2"),
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [ch1, ch2]
    if cover is not None:
        book.set_cover("cover.jpg", cover, create_page=False)
    if attach_jpeg is not None:
        book.add_item(
            epub.EpubItem(
                uid="photo",
                file_name="img/photo.jpg",
                media_type="image/jpeg",
                content=attach_jpeg,
            )
        )
    epub.write_epub(str(path), book)
    return path


def _sine(samples: int, freq: float = 440.0) -> np.ndarray:
    t = np.arange(samples, dtype=np.float64) / SR
    return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _fake_synth(sentence: Sentence) -> np.ndarray:
    """Exact 100 ms tone per sentence (2400 samples: exact ms at 24 kHz)."""
    return _sine(2400, freq=440.0 + 110.0 * sentence.sid)


def _with_timings(chapter: ChapterFile, assembled: AssembledChapter) -> ChapterFile:
    """Copy ``chapter`` blocks, filling sentence timings from ``assembled``."""
    timing_iter = iter(assembled.timings)
    blocks: list[Block] = []
    for block in chapter.blocks or []:
        sentences = [
            Sentence(
                sid=s.sid,
                speaker=s.speaker,
                start_ms=t.start_ms,
                end_ms=t.end_ms,
                text=s.text,
                spans=list(s.spans),
            )
            for s, t in zip(block.sentences, [next(timing_iter) for _ in block.sentences])
        ] if block.sentences else []
        blocks.append(
            Block(
                id=block.id,
                type=block.type,
                level=block.level,
                text=block.text,
                sentences=sentences,
            )
        )
    return ChapterFile(
        spec_version="1.0",
        chapter=chapter.chapter,
        title=chapter.title,
        duration_ms=assembled.duration_ms,
        blocks=blocks,
    )


def _build_inputs(
    chapters: list[ChapterFile], workdir: Path
) -> tuple[list[ChapterFile], list[Path]]:
    """SW7 path with the fake synth: assemble -> encode -> timed chapters + MP3s."""
    workdir.mkdir(parents=True, exist_ok=True)
    timed: list[ChapterFile] = []
    mp3s: list[Path] = []
    for chapter in chapters:
        assembled = assemble_chapter(chapter, _fake_synth)
        mp3 = workdir / f"src-ch{chapter.chapter:03d}.mp3"
        encode_assembled_chapter(assembled, mp3, chapter_label=f"ch{chapter.chapter:03d}")
        timed.append(_with_timings(chapter, assembled))
        mp3s.append(mp3)
    return timed, mp3s


def _mini_chapters() -> list[ChapterFile]:
    return [
        _chapter(1, "Chapter One", ["Chapter One", "The rain had not stopped.", "We go."]),
        _chapter(2, "Chapter Two", ["Chapter Two", "Dawn broke.", "Birds sang."]),
    ]


def _read_manifest(bundle: Path) -> dict:
    return json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# Integration: mini-book -> valid bundle, real probe, manifest assertions
# ---------------------------------------------------------------------------


def test_mini_book_writes_valid_bundle_real_probe(tmp_path: Path) -> None:
    """No-cover EPUB: bundle validates (real ffprobe) and the manifest is exact."""
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "mini.epub")
    timed, mp3s = _build_inputs(_mini_chapters(), tmp_path / "mp3")
    bundle = tmp_path / "bundle"

    result = write_bundle(timed, mp3s, epub_path, bundle)

    assert result.bundle_dir == bundle
    assert result.manifest_path == bundle / "manifest.json"
    assert result.chapters == 2
    assert result.cover is False

    # The REAL probe (no stubs): the writer's own gate already ran it, re-check.
    check = validate_bundle(bundle)
    assert check.ok, check.errors

    manifest = _read_manifest(bundle)
    book_id, sha = book_id_for_file(epub_path)
    assert manifest["spec_version"] == "1.0"
    assert manifest["id"] == book_id == result.book_id
    assert result.sha256 == sha
    assert manifest["title"] == "Writer Test Book"
    assert manifest["author"] == "Writer Author"
    assert manifest["type"] == "epub"
    assert manifest["source"] == {"file": "source/book.epub", "sha256": sha}
    assert manifest["audio"] == {
        "format": "mp3",
        "channels": 1,
        "sample_rate": 24000,
        "bitrate_kbps": 64,
        "cbr": True,
    }

    # Voices: narrator only, matching SW5's cast.yaml plus pitch 1.0.
    cast_narrator = default_cast()["narrator"]
    assert set(manifest["voices"]) == {"narrator"}
    voice = manifest["voices"]["narrator"]
    assert voice["engine"] == cast_narrator["engine"] == "kokoro"
    assert voice["voice"] == cast_narrator["voice"] == "af_heart"
    assert voice["speed"] == cast_narrator["speed"] == 1.0
    assert voice["pitch"] == 1.0

    # Per-chapter entries: zero-padded names, durations from the timed chapters.
    assert len(manifest["chapters"]) == 2
    for pos, chapter in enumerate(timed, start=1):
        entry = manifest["chapters"][pos - 1]
        assert entry == {
            "index": pos,
            "title": chapter.title,
            "audio": f"audio/ch{pos:03d}.mp3",
            "text": f"text/ch{pos:03d}.json",
            "duration_ms": chapter.duration_ms,
        }
        assert (bundle / entry["audio"]).is_file()
        assert (bundle / entry["text"]).is_file()

    # Source is a byte-identical copy of the original.
    assert (bundle / "source" / "book.epub").read_bytes() == epub_path.read_bytes()

    # Cover absent: no file AND no key (never null) — the documented choice.
    assert not (bundle / "cover.jpg").exists()
    assert "cover" not in manifest

    # created_at is UTC ISO-8601 with Z; generator names the scribe version.
    parsed = datetime.fromisoformat(manifest["created_at"].replace("Z", "+00:00"))
    assert parsed.tzinfo is not None
    assert manifest["generator"] == f"scribe {cli.__version__}"

    # Chapter JSON round-trips through the SW2 models with sane timings.
    first = ChapterFile.from_dict(
        json.loads((bundle / "text" / "ch001.json").read_text(encoding="utf-8"))
    )
    sentences = first.sentences_in_order()
    assert [s.sid for s in sentences] == [1, 2, 3]
    assert sentences[0].start_ms == 0
    assert first.duration_ms == manifest["chapters"][0]["duration_ms"]
    assert sha == sha256_of_file(epub_path)


# ---------------------------------------------------------------------------
# Cover: present, sniff fallback, invalid input
# ---------------------------------------------------------------------------


def test_cover_present_extracted(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "cover.epub", cover=MIN_JPEG)
    assert extract_cover(epub_path) == MIN_JPEG

    timed, mp3s = _build_inputs(_mini_chapters(), tmp_path / "mp3")
    bundle = tmp_path / "bundle"
    result = write_bundle(timed, mp3s, epub_path, bundle)

    assert result.cover is True
    assert (bundle / "cover.jpg").read_bytes() == MIN_JPEG
    assert _read_manifest(bundle)["cover"] == "cover.jpg"
    assert validate_bundle(bundle).ok


def test_cover_sniff_fallback_without_cover_item(tmp_path: Path) -> None:
    """Loose JPEG in the manifest (no cover item) is still picked up."""
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "loose.epub", attach_jpeg=MIN_JPEG)
    assert extract_cover(epub_path) == MIN_JPEG

    timed, mp3s = _build_inputs(_mini_chapters(), tmp_path / "mp3")
    bundle = tmp_path / "bundle"
    write_bundle(timed, mp3s, epub_path, bundle)

    assert (bundle / "cover.jpg").read_bytes() == MIN_JPEG
    assert _read_manifest(bundle)["cover"] == "cover.jpg"
    assert validate_bundle(bundle).ok


def test_cover_absent_returns_none_and_omits_key(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "nocover.epub")
    assert extract_cover(epub_path) is None

    timed, mp3s = _build_inputs(_mini_chapters(), tmp_path / "mp3")
    bundle = tmp_path / "bundle"
    write_bundle(timed, mp3s, epub_path, bundle)

    assert not (bundle / "cover.jpg").exists()
    assert "cover" not in _read_manifest(bundle)
    assert validate_bundle(bundle).ok


def test_extract_cover_missing_file_returns_none(tmp_path: Path) -> None:
    assert extract_cover(tmp_path / "nope.epub") is None


def test_missing_author_omits_key(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "noauthor.epub", author=None)
    timed, mp3s = _build_inputs(_mini_chapters(), tmp_path / "mp3")
    bundle = tmp_path / "bundle"
    write_bundle(timed, mp3s, epub_path, bundle)

    assert "author" not in _read_manifest(bundle)
    assert validate_bundle(bundle).ok


# ---------------------------------------------------------------------------
# Determinism: same source bytes -> same bundle id
# ---------------------------------------------------------------------------


def test_id_deterministic_across_runs(tmp_path: Path) -> None:
    """Same EPUB twice -> same manifest id (created_at may differ: by design)."""
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "mini.epub")
    timed, mp3s = _build_inputs(_mini_chapters(), tmp_path / "mp3")
    write_bundle(timed, mp3s, epub_path, tmp_path / "bundle-a")
    write_bundle(timed, mp3s, epub_path, tmp_path / "bundle-b")

    id_a = _read_manifest(tmp_path / "bundle-a")["id"]
    id_b = _read_manifest(tmp_path / "bundle-b")["id"]
    assert id_a == id_b == book_id_for_file(epub_path)[0]


# ---------------------------------------------------------------------------
# Fail-loud paths: named BundleWriteError, problems named
# ---------------------------------------------------------------------------


def test_validation_failure_raises_loudly(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Poisoned post-write validation -> BundleWriteError naming the problem."""
    epub_path = _make_epub(tmp_path / "mini.epub")
    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter One",
        duration_ms=1000,
        blocks=[
            Block(
                id=1,
                type="para",
                sentences=[
                    Sentence(
                        sid=1,
                        speaker="narrator",
                        start_ms=0,
                        end_ms=500,
                        text="Hello world.",
                    )
                ],
            )
        ],
    )
    fake_mp3 = tmp_path / "fake.mp3"
    fake_mp3.write_bytes(b"not really an mp3")

    poison = "text/ch001.json: sentence 2 overlaps sentence 1"

    def _failing(bundle_dir: Path) -> ValidationResult:
        return ValidationResult(errors=[poison])

    monkeypatch.setattr(writer, "validate_bundle", _failing)

    bundle = tmp_path / "bundle"
    with pytest.raises(BundleWriteError) as excinfo:
        write_bundle([chapter], [fake_mp3], epub_path, bundle)
    assert poison in str(excinfo.value)
    # Partial output is left for inspection but is NOT a valid bundle.
    assert (bundle / "manifest.json").is_file()


def test_chapter_audio_count_mismatch_raises(tmp_path: Path) -> None:
    epub_path = _make_epub(tmp_path / "mini.epub")
    chapters = _mini_chapters()
    with pytest.raises(BundleWriteError, match="2 chapters but 1 audio files"):
        write_bundle(chapters, [tmp_path / "only.mp3"], epub_path, tmp_path / "bundle")


def test_missing_source_raises(tmp_path: Path) -> None:
    with pytest.raises(BundleWriteError, match="EPUB not found"):
        write_bundle(_mini_chapters(), [], tmp_path / "nope.epub", tmp_path / "bundle")
