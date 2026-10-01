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

"""SW9: ``scribe build`` (resume, strict, orphans, log/totals) + ``inspect``.

All rendering uses a fake engine with tiny generated audio (exact 100 ms
sine tones) plus real ffmpeg/ffprobe — no real TTS, no new slow tests.
Encode-dependent tests skip loudly when ffmpeg/ffprobe are missing.
"""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import numpy as np
import pytest
from ebooklib import epub
from typer.testing import CliRunner

import cli
from build import (
    BuildError,
    BuildResult,
    compare_bundles,
    ensure_script,
    format_summary,
    remove_stale_chapter_files,
    run_build,
)
from bundle.inspect import InspectError, format_inspect, inspect_bundle
from bundle.models import ChapterFile
from bundle.validate import validate_bundle
from draft import SCRIPT_FILENAME, book_id_for_file, run_draft
from tts.base import TTSEngine

FIXTURES = Path(__file__).resolve().parents[2] / "spec" / "fixtures"

#: One synthetic sentence (~9 words); 25 repeats clear the 200-word tiny-merge.
SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."
REPEATS = 25
FAIL_MARKER = "The broken bell never rings again."


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (build tests need real binaries)")


class FakeEngine(TTSEngine):
    """Deterministic stand-in: exact 100 ms sine per sentence, records calls."""

    def __init__(self, version: str = "fake-9") -> None:
        self.calls: list[tuple[str, str, float]] = []
        self._version = version

    @property
    def sample_rate(self) -> int:
        return 24_000

    @property
    def engine_version(self) -> str:
        return self._version

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        self.calls.append((text, voice, float(speed)))
        freq = 440.0 + (len(text) % 5) * 110.0  # deterministic across processes
        t = np.arange(2400, dtype=np.float64) / 24_000
        return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


class FailingEngine(FakeEngine):
    """Fake engine that blows up on any sentence containing a marker."""

    def __init__(self, marker: str = FAIL_MARKER) -> None:
        super().__init__()
        self.marker = marker

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        if self.marker in text:
            raise RuntimeError(f"synth boom on purpose: {text[:24]}")
        return super().synth(text, voice, speed)


def _make_epub(
    path: Path,
    titles: list[str],
    *,
    book_title: str = "Build Test Book",
    marker_chapter: int = 0,
) -> Path:
    """Synthetic EPUB with one long chapter per title (each over 200 words)."""
    book = epub.EpubBook()
    book.set_identifier("test-build-book")
    book.set_title(book_title)
    book.set_language("en")
    book.add_author("Build Author")
    items = []
    for pos, title in enumerate(titles, start=1):
        body = " ".join([SENTENCE] * REPEATS)
        if pos == marker_chapter:
            body += f" {FAIL_MARKER}"
        item = epub.EpubHtml(title=title, file_name=f"ch{pos}.xhtml", lang="en")
        item.content = f"<h1>{title}</h1><p>{body}</p>"
        book.add_item(item)
        items.append(item)
    book.toc = [
        epub.Link(f"ch{pos}.xhtml", title, f"ch{pos}") for pos, title in enumerate(titles, 1)
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = items
    epub.write_epub(str(path), book)
    return path


def _script_sentences(work_dir: Path, book_id: str) -> list[list[str]]:
    """Sentence texts per chapter from a work folder's script.json."""
    data = json.loads((work_dir / book_id / SCRIPT_FILENAME).read_text(encoding="utf-8"))
    out = []
    for raw in data["chapters"]:
        chapter = ChapterFile.from_dict(raw)
        out.append([s.text for s in chapter.sentences_in_order()])
    return out


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# ---------------------------------------------------------------------------
# (a) kill simulation: abort, resume, identical bundle (modulo created_at)
# ---------------------------------------------------------------------------


def test_kill_resume_identical_bundle(tmp_path: Path) -> None:
    """fail_after=1 aborts; rerun resumes (skips + cache reuse) and matches
    an uninterrupted build byte-for-byte except manifest.created_at."""
    _needs_ffmpeg()
    epub_path = _make_epub(
        tmp_path / "book.epub", ["Chapter One", "Chapter Two", "Chapter Three"]
    )
    work_kill = tmp_path / "work-kill"
    out_kill = tmp_path / "out-kill"
    killer = FakeEngine()
    with pytest.raises(BuildError, match="simulated failure"):
        run_build(
            epub_path,
            work_root=work_kill,
            out_dir=out_kill,
            engine=killer,
            fail_after=1,
            show_progress=False,
        )
    # The killed run rendered chapter 1 only: no bundle, but render artifacts.
    book_id, _ = book_id_for_file(epub_path)
    render1 = work_kill / book_id / "render"
    assert (render1 / "audio" / "ch001.mp3").is_file()
    assert (render1 / "text" / "ch001.json").is_file()
    assert not (render1 / "audio" / "ch002.mp3").exists()
    assert not (out_kill / "manifest.json").exists()
    mp3_before = _sha(render1 / "audio" / "ch001.mp3")
    json_before = _sha(render1 / "text" / "ch001.json")
    script_all = _script_sentences(work_kill, book_id)
    ch1_texts = script_all[0]
    # The kill run synthesized chapter 1's UNIQUE sentences exactly once
    # (the 25 repeated body sentences share one cache entry after the first).
    assert sorted(c[0] for c in killer.calls) == sorted(set(ch1_texts))

    resumer = FakeEngine()
    resumed = run_build(
        epub_path,
        work_root=work_kill,
        out_dir=out_kill,
        engine=resumer,
        show_progress=False,
    )
    assert isinstance(resumed, BuildResult)
    assert (resumed.skipped, resumed.rendered) == (1, 2)
    assert resumed.failed == []

    # Finished chapters untouched: chapter-1 artifacts bit-identical.
    assert _sha(render1 / "audio" / "ch001.mp3") == mp3_before
    assert _sha(render1 / "text" / "ch001.json") == json_before
    # Cache reuse: the resume skipped chapter 1 entirely (no synth calls for
    # its sentences — not even cache lookups), and chapters 2-3 re-hit the
    # body-sentence cache entries the kill run wrote. Every unique sentence
    # in the book was synthesized exactly once across both runs.
    resumed_texts = [call[0] for call in resumer.calls]
    assert not set(resumed_texts) & set(ch1_texts)
    combined = [call[0] for call in killer.calls] + resumed_texts
    all_unique = {text for chapter in script_all for text in chapter}
    assert sorted(combined) == sorted(all_unique)
    assert len(combined) == len(set(combined))

    # Identical-bundle definition: uninterrupted build elsewhere, compare
    # byte-for-byte except manifest.created_at.
    out_ref = tmp_path / "out-ref"
    run_build(
        epub_path,
        work_root=tmp_path / "work-ref",
        out_dir=out_ref,
        engine=FakeEngine(),
        show_progress=False,
    )
    diffs = compare_bundles(out_ref, out_kill)
    assert diffs == [], diffs
    assert validate_bundle(out_kill).ok


# ---------------------------------------------------------------------------
# (b) --strict aborts fast vs non-strict renders on, then reports
# ---------------------------------------------------------------------------


def test_strict_aborts_on_first_error(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(
        tmp_path / "book.epub",
        ["Chapter One", "Chapter Two", "Chapter Three"],
        marker_chapter=2,
    )
    out = tmp_path / "out"
    with pytest.raises(BuildError, match="chapter 2 failed"):
        run_build(
            epub_path,
            work_root=tmp_path / "work",
            out_dir=out,
            engine=FailingEngine(),
            strict=True,
            show_progress=False,
        )
    book_id, _ = book_id_for_file(epub_path)
    render = tmp_path / "work" / book_id / "render"
    assert (render / "audio" / "ch001.mp3").is_file()  # finished before the failure
    assert not (render / "audio" / "ch003.mp3").exists()  # never attempted
    assert not (out / "manifest.json").exists()  # no bundle on abort
    log = (tmp_path / "work" / book_id / "scribe.log").read_text(encoding="utf-8")
    assert "chapter 2" in log and "FAILED" in log and "aborted (strict)" in log


def test_nonstrict_continues_then_reports(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(
        tmp_path / "book.epub",
        ["Chapter One", "Chapter Two", "Chapter Three"],
        marker_chapter=2,
    )
    out = tmp_path / "out"
    with pytest.raises(BuildError, match="chapter 2"):
        run_build(
            epub_path,
            work_root=tmp_path / "work",
            out_dir=out,
            engine=FailingEngine(),
            strict=False,
            show_progress=False,
        )
    book_id, _ = book_id_for_file(epub_path)
    render = tmp_path / "work" / book_id / "render"
    # Continued past the failure: chapter 3 rendered despite chapter 2.
    assert (render / "audio" / "ch003.mp3").is_file()
    assert (render / "text" / "ch003.json").is_file()
    assert not (out / "manifest.json").exists()  # gaps cannot ship: no bundle
    log = (tmp_path / "work" / book_id / "scribe.log").read_text(encoding="utf-8")
    assert "chapter 2" in log and "FAILED" in log


# ---------------------------------------------------------------------------
# (c) stale draft redrafts (no ffmpeg: freshness check only)
# ---------------------------------------------------------------------------


def test_stale_script_redrafts_on_source_change(tmp_path: Path) -> None:
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_root = tmp_path / "work"
    first = run_draft(epub_path, work_root=work_root)
    assert first.script_path.is_file()

    # Tamper the stored sha: the script is now stale -> ensure_script redrafts.
    data = json.loads(first.script_path.read_text(encoding="utf-8"))
    data["source_sha256"] = "0" * 64
    first.script_path.write_text(json.dumps(data, sort_keys=True) + "\n", encoding="utf-8")

    info = ensure_script(epub_path, work_root=work_root)
    assert info.redrafted is True
    assert len(info.chapters) == 2
    fixed = json.loads(first.script_path.read_text(encoding="utf-8"))
    assert fixed["source_sha256"] == info.sha256 == first.sha256

    # Fresh now: no redraft, same chapters back.
    again = ensure_script(epub_path, work_root=work_root)
    assert again.redrafted is False
    assert [c.title for c in again.chapters] == [c.title for c in info.chapters]


# ---------------------------------------------------------------------------
# (d) inspect: valid fixture contents + clean errors on bad bundles
# ---------------------------------------------------------------------------


def test_inspect_valid_fixture_contents() -> None:
    result = inspect_bundle(FIXTURES / "valid-bundle")
    assert result.title == "Example Novel"
    assert result.author == "A. Author"
    assert [c.index for c in result.chapters] == [1, 2]
    assert [c.title for c in result.chapters] == ["Chapter One", "Chapter Two"]
    assert [c.duration_ms for c in result.chapters] == [1832400, 1640100]
    assert result.total_duration_ms == 1832400 + 1640100
    assert result.speakers == {"narrator": 2}
    assert result.chapters[0].samples == ["The rain had not stopped for three days."]
    assert result.chapters[1].samples == ["Morning came at last."]

    text = format_inspect(result)
    assert "Example Novel" in text and "A. Author" in text
    assert "[1] Chapter One" in text and "[2] Chapter Two" in text
    assert "1832400 ms" in text and "1640100 ms" in text
    assert "narrator: 2 sentences" in text
    assert "The rain had not stopped for three days." in text
    assert "Morning came at last." in text


def test_inspect_bad_bundle_clean_error(tmp_path: Path) -> None:
    with pytest.raises(InspectError, match="manifest.json"):
        inspect_bundle(tmp_path / "empty")
    with pytest.raises(InspectError, match="manifest.json"):
        inspect_bundle(FIXTURES / "bad-json")


def test_inspect_cli_exit_codes() -> None:
    runner = CliRunner()
    ok = runner.invoke(cli.app, ["inspect", str(FIXTURES / "valid-bundle")])
    assert ok.exit_code == 0, ok.output
    assert "Example Novel" in ok.output and "narrator: 2 sentences" in ok.output

    bad = runner.invoke(cli.app, ["inspect", str(FIXTURES / "bad-json")])
    assert bad.exit_code == 1
    assert "inspect failed" in bad.output
    assert "Traceback" not in bad.output


# ---------------------------------------------------------------------------
# (e) scribe.log + totals/RTF print contents
# ---------------------------------------------------------------------------


def test_scribe_log_and_totals(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    result = run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out",
        engine=FakeEngine(),
        show_progress=False,
    )
    assert result.chapters_total == 2
    assert (result.rendered, result.skipped) == (2, 0)
    assert result.total_audio_ms > 0
    assert result.total_sentences == sum(
        len(c) for c in _script_sentences(tmp_path / "work", result.book_id)
    )
    manifest = json.loads((tmp_path / "out" / "manifest.json").read_text(encoding="utf-8"))
    assert result.total_audio_ms == sum(c["duration_ms"] for c in manifest["chapters"])
    assert result.wall_seconds >= 0
    assert result.rtf > 0

    log_text = result.log_path.read_text(encoding="utf-8")
    assert "build start" in log_text
    assert "build done" in log_text
    assert f"total_audio_ms={result.total_audio_ms}" in log_text
    assert "real-time factor (RTF)" in log_text

    summary = format_summary(result)
    assert "total audio" in summary
    assert "wall time" in summary
    assert "real-time factor" in summary
    assert str(tmp_path / "out") in summary


def test_build_cli_prints_totals(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Thin CLI wiring: real library, fake engine, totals reach stdout."""
    _needs_ffmpeg()
    import build as build_module

    monkeypatch.setattr(build_module, "create_engine", lambda _models: FakeEngine())
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    runner = CliRunner()
    outcome = runner.invoke(
        cli.app,
        [
            "build",
            str(epub_path),
            "--work-dir",
            str(tmp_path / "work"),
            "--out-dir",
            str(tmp_path / "out"),
            "--no-progress",
        ],
    )
    assert outcome.exit_code == 0, outcome.output
    assert "total audio" in outcome.output
    assert "wall time" in outcome.output
    assert "real-time factor" in outcome.output


# ---------------------------------------------------------------------------
# Carry-over (SW8 review): no orphaned chapters on shrink; helper units
# ---------------------------------------------------------------------------


def test_shrinking_book_leaves_no_orphans(tmp_path: Path) -> None:
    """Same output dir reused for a shorter book: stale ch003 audio/text
    (bundle) gone, and stale render files gone; the bundle still validates."""
    _needs_ffmpeg()
    out = tmp_path / "out"
    big = _make_epub(
        tmp_path / "big.epub", ["Chapter One", "Chapter Two", "Chapter Three"]
    )
    run_build(
        big, work_root=tmp_path / "work", out_dir=out, engine=FakeEngine(),
        show_progress=False,
    )
    assert (out / "audio" / "ch003.mp3").is_file()
    assert (out / "text" / "ch003.json").is_file()

    small = _make_epub(tmp_path / "small.epub", ["Chapter One", "Chapter Two"])
    small_id, _ = book_id_for_file(small)
    stale_audio = tmp_path / "work" / small_id / "render" / "audio" / "ch009.mp3"
    stale_text = tmp_path / "work" / small_id / "render" / "text" / "ch009.json"
    stale_audio.parent.mkdir(parents=True, exist_ok=True)
    stale_text.parent.mkdir(parents=True, exist_ok=True)
    stale_audio.write_bytes(b"stale")
    stale_text.write_text("{}", encoding="utf-8")

    run_build(
        small, work_root=tmp_path / "work", out_dir=out, engine=FakeEngine(),
        show_progress=False,
    )
    assert not (out / "audio" / "ch003.mp3").exists()
    assert not (out / "text" / "ch003.json").exists()
    assert not stale_audio.exists()
    assert not stale_text.exists()
    check = validate_bundle(out)
    assert check.ok, check.errors


def test_remove_stale_chapter_files_unit(tmp_path: Path) -> None:
    folder = tmp_path / "audio"
    folder.mkdir()
    for name in ("ch001.mp3", "ch002.mp3", "ch003.mp3", "ch010.mp3", "notes.txt", "chX.mp3"):
        (folder / name).write_bytes(b"x")
    removed = remove_stale_chapter_files(folder, keep=2, suffix=".mp3")
    assert [p.name for p in removed] == ["ch003.mp3", "ch010.mp3"]
    assert (folder / "ch001.mp3").is_file()
    assert (folder / "notes.txt").is_file()  # non-chapter files never touched
    assert remove_stale_chapter_files(tmp_path / "missing", keep=2, suffix=".mp3") == []


def test_compare_bundles_ignores_created_at_only(tmp_path: Path) -> None:
    first, second = tmp_path / "a", tmp_path / "b"
    for root in (first, second):
        (root / "audio").mkdir(parents=True)
        (root / "audio" / "ch001.mp3").write_bytes(b"\xff\xfb" * 100)
    created = ['"created_at": "2026-01-01T00:00:00Z"', '"created_at": "2027-05-05T05:05:05Z"']
    for root, stamp in zip((first, second), created):
        (root / "manifest.json").write_text(
            '{"id": "x", "title": "T", ' + stamp + "}\n", encoding="utf-8"
        )
    assert compare_bundles(first, second) == []

    (second / "audio" / "ch001.mp3").write_bytes(b"\xff\xfb" * 100 + b"\x00")
    assert compare_bundles(first, second) == ["audio/ch001.mp3: bytes differ"]

    (second / "audio" / "ch001.mp3").write_bytes(b"\xff\xfb" * 100)
    (second / "extra.txt").write_text("hi", encoding="utf-8")
    diffs = compare_bundles(first, second)
    assert len(diffs) == 1 and "extra.txt" in diffs[0]


# ---------------------------------------------------------------------------
# (f) resume fingerprint: title edits, pause-constant edits, BUFFER_VERSION
# ---------------------------------------------------------------------------


def test_title_only_edit_rerenders_that_chapter(tmp_path: Path) -> None:
    """Same sentences but a new chapter title must re-render that chapter.

    The title flows into the shipped JSON (and the manifest), so the render
    fingerprint covers it: editing only ``script.json`` chapter 1's title
    re-renders chapter 1 and skips chapter 2.
    """
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    first = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (first.rendered, first.skipped) == (2, 0)

    book_id, _ = book_id_for_file(epub_path)
    script_path = work_root / book_id / SCRIPT_FILENAME
    data = json.loads(script_path.read_text(encoding="utf-8"))
    assert data["chapters"][0]["title"] == "Chapter One"
    data["chapters"][0]["title"] = "Chapter One (Renamed)"
    script_path.write_text(json.dumps(data, sort_keys=True) + "\n", encoding="utf-8")

    second = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (second.rendered, second.skipped) == (1, 1)

    render_data = json.loads(
        (work_root / book_id / "render" / "text" / "ch001.json").read_text(encoding="utf-8")
    )
    assert render_data["title"] == "Chapter One (Renamed)"
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["chapters"][0]["title"] == "Chapter One (Renamed)"
    assert validate_bundle(out).ok


def test_buffer_version_bump_rerenders_all(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Bumping BUFFER_VERSION re-renders every chapter once.

    The manual version covers only buffer/timing-construction changes that
    constants cannot capture; pause/loudness/encode edits are fingerprinted
    automatically (see the pause-constant test below). Any bump invalidates
    all renders.
    """
    _needs_ffmpeg()
    import build as build_module

    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    first = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (first.rendered, first.skipped) == (2, 0)

    # Rerun without a bump skips everything (sanity: fingerprint is stable).
    steady = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (steady.rendered, steady.skipped) == (0, 2)

    monkeypatch.setattr(
        build_module, "BUFFER_VERSION", build_module.BUFFER_VERSION + 1
    )
    bumped = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (bumped.rendered, bumped.skipped) == (2, 0)
    assert validate_bundle(out).ok


def test_pause_constant_change_rerenders_all(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Changing a pause constant value re-renders every chapter once.

    The fingerprint reads the ACTUAL ``audio.assemble`` pause values (no
    hand-bumped int), so editing a pause length invalidates renders
    automatically — forgetting a bump cannot silently reuse stale audio.
    """
    _needs_ffmpeg()
    import audio.assemble as assemble_module

    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    first = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (first.rendered, first.skipped) == (2, 0)

    steady = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (steady.rendered, steady.skipped) == (0, 2)

    monkeypatch.setattr(
        assemble_module, "PAUSE_SENTENCE_MS", assemble_module.PAUSE_SENTENCE_MS + 50
    )
    changed = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (changed.rendered, changed.skipped) == (2, 0)
    assert validate_bundle(out).ok


# ---------------------------------------------------------------------------
# (g) torn-file resume: truncated MP3, missing JSON, corrupt JSON, half bundle
# ---------------------------------------------------------------------------


def test_truncated_mp3_rerenders(tmp_path: Path) -> None:
    """A kill mid-encode leaves a short MP3: ffprobe drift forces re-render."""
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    book_id, _ = book_id_for_file(epub_path)
    mp3 = work_root / book_id / "render" / "audio" / "ch001.mp3"
    raw = mp3.read_bytes()
    assert len(raw) > 2048  # real MP3, so halving moves duration well past 50 ms
    mp3.write_bytes(raw[: len(raw) // 2])

    second = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (second.rendered, second.skipped) == (1, 1)
    assert validate_bundle(out).ok


def test_mp3_without_json_rerenders(tmp_path: Path) -> None:
    """MP3 present but its chapter JSON missing (kill between files): re-render."""
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    book_id, _ = book_id_for_file(epub_path)
    missing = work_root / book_id / "render" / "text" / "ch001.json"
    assert missing.is_file()
    assert (work_root / book_id / "render" / "audio" / "ch001.mp3").is_file()
    missing.unlink()

    second = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (second.rendered, second.skipped) == (1, 1)
    assert missing.is_file()
    assert validate_bundle(out).ok


def test_corrupt_chapter_json_rerenders(tmp_path: Path) -> None:
    """Half-written render JSON (truncated bytes): parse fails, re-render."""
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    book_id, _ = book_id_for_file(epub_path)
    torn = work_root / book_id / "render" / "text" / "ch001.json"
    torn.write_text("{ half-written", encoding="utf-8")

    second = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert (second.rendered, second.skipped) == (1, 1)
    assert validate_bundle(out).ok


def test_half_written_bundle_from_aborted_run_repairs(tmp_path: Path) -> None:
    """Abort via fail_after, plant torn bundle files, resume rewrites valid bundle.

    The aborted run writes no manifest; the planted half-written bundle
    (truncated audio + unparseable manifest, as a kill during write_bundle
    could leave) must be fully overwritten by the resume, matching an
    uninterrupted reference build except manifest.created_at.
    """
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    work_kill = tmp_path / "work-kill"
    out_kill = tmp_path / "out-kill"
    with pytest.raises(BuildError, match="simulated failure"):
        run_build(
            epub_path,
            work_root=work_kill,
            out_dir=out_kill,
            engine=FakeEngine(),
            fail_after=1,
            show_progress=False,
        )
    assert not (out_kill / "manifest.json").exists()

    # Simulate a kill mid-write: partial audio plus a torn manifest.
    (out_kill / "audio").mkdir(parents=True, exist_ok=True)
    (out_kill / "text").mkdir(parents=True, exist_ok=True)
    (out_kill / "audio" / "ch001.mp3").write_bytes(b"half-written")
    (out_kill / "manifest.json").write_text("{ half-written", encoding="utf-8")

    resumed = run_build(
        epub_path,
        work_root=work_kill,
        out_dir=out_kill,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert resumed.failed == []
    assert (resumed.skipped, resumed.rendered) == (1, 1)
    assert validate_bundle(out_kill).ok

    out_ref = tmp_path / "out-ref"
    run_build(
        epub_path,
        work_root=tmp_path / "work-ref",
        out_dir=out_ref,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert compare_bundles(out_ref, out_kill) == []


# ---------------------------------------------------------------------------
# (h) guarded CBR gate: probe without cbr_frames re-renders, never aborts
# ---------------------------------------------------------------------------


def test_missing_cbr_attribute_rerenders(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A probe object lacking ``cbr_frames`` must re-render, not raise.

    The gate lives outside the per-chapter try in ``run_build``, so an
    unguarded ``probe.cbr_frames`` access would abort the whole build with
    ``AttributeError``. Missing/None counts as "not known-CBR" -> ``None``.
    """
    import types

    import build as build_module
    from bundle.models import Block, ChapterFile, Sentence

    sentence = Sentence(
        sid=1, speaker="narrator", start_ms=0, end_ms=100,
        text="Hello world.", spans=[],
    )
    block = Block(id=1, type="para", level=None, text=None, sentences=[sentence])
    script_chapter = ChapterFile(
        spec_version="1.0", chapter=1, title="Chapter One",
        duration_ms=600, blocks=[block],
    )
    cast = {"narrator": {"voice": "v", "speed": 1.0}}
    engine = FakeEngine()
    fingerprint = build_module._fingerprint(cast, engine, script_chapter)

    json_path = tmp_path / "ch001.json"
    mp3_path = tmp_path / "ch001.mp3"
    payload = dict(script_chapter.to_dict())
    payload[build_module.RENDER_FINGERPRINT_KEY] = fingerprint
    json_path.write_text(json.dumps(payload, sort_keys=True) + "\n", encoding="utf-8")
    mp3_path.write_bytes(b"\xff\xfb" * 100)

    # No cbr_frames attribute at all -> re-render, not AttributeError.
    bare = types.SimpleNamespace(duration_ms=600)
    assert not hasattr(bare, "cbr_frames")
    monkeypatch.setattr(build_module, "probe_audio_ffprobe", lambda _p: bare)
    assert (
        build_module.chapter_up_to_date(
            script_chapter, json_path, mp3_path, fingerprint
        )
        is None
    )

    # Explicit None -> also re-render.
    none_cbr = types.SimpleNamespace(duration_ms=600, cbr_frames=None)
    monkeypatch.setattr(build_module, "probe_audio_ffprobe", lambda _p: none_cbr)
    assert (
        build_module.chapter_up_to_date(
            script_chapter, json_path, mp3_path, fingerprint
        )
        is None
    )

    # Sanity: intact CBR probe still hits.
    good = types.SimpleNamespace(duration_ms=600, cbr_frames=True)
    monkeypatch.setattr(build_module, "probe_audio_ffprobe", lambda _p: good)
    hit = build_module.chapter_up_to_date(
        script_chapter, json_path, mp3_path, fingerprint
    )
    assert hit is not None and hit.title == "Chapter One"
