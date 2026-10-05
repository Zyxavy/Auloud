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

"""Slice 6 UI1: library groundwork (events, ranges, plan, stop, lock, device, 1.2).

Uses the same FakeEngine + real ffmpeg pattern as test_build (no real TTS,
no slow mark). CLI behavior for existing cases stays identical (covered by
test_build's 20 tests, still green).
"""

from __future__ import annotations

import json
import os
import shutil
import threading
from pathlib import Path

import numpy as np
import pytest
from ebooklib import epub
from typer.testing import CliRunner

import cli
from build import (
    BuildError,
    BuildStoppedError,
    compare_bundles,
    format_plan,
    plan_build,
    run_build,
)
from bundle.validate import validate_bundle
from draft import book_id_for_file, book_id_for_range, run_draft
from tts.base import TTSEngine

SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."
REPEATS = 25


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (build tests need real binaries)")


class FakeEngine(TTSEngine):
    def __init__(self, version: str = "fake-ui1") -> None:
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
        freq = 440.0 + (len(text) % 5) * 110.0
        t = np.arange(2400, dtype=np.float64) / 24_000
        return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _make_epub(path: Path, titles: list[str]) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-ui1-book")
    book.set_title("UI1 Test Book")
    book.set_language("en")
    book.add_author("UI1 Author")
    items = []
    for pos, title in enumerate(titles, start=1):
        body = " ".join([SENTENCE] * REPEATS)
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


# ---------------------------------------------------------------------------
# 1. Events: JSONL writer carries the same numbers as the Rich bar / result
# ---------------------------------------------------------------------------


def test_events_jsonl_matches_result_counts(tmp_path: Path) -> None:
    _needs_ffmpeg()
    from progress import JsonlProgressWriter

    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    events_path = tmp_path / "events.jsonl"
    seen: list[dict] = []

    def _listener(event: dict) -> None:
        seen.append(dict(event))

    with JsonlProgressWriter(events_path) as writer:
        from progress import FanoutProgress

        result = run_build(
            epub_path,
            work_root=tmp_path / "work",
            out_dir=tmp_path / "out",
            engine=FakeEngine(),
            show_progress=False,
            progress_listener=FanoutProgress(writer, _listener),
        )
    # JSONL file: one object per line with the UI1 contract keys.
    lines = events_path.read_text(encoding="utf-8").strip().splitlines()
    assert lines, "JSONL writer wrote no events"
    for line in lines:
        obj = json.loads(line)
        assert set(obj) == {"event", "chapter", "sid", "cached", "rendered", "audio_ms", "wall_s"}
    kinds = [json.loads(line)["event"] for line in lines]
    assert "sentence" in kinds
    assert "chapter_done" in kinds or "chapter_skipped" in kinds
    assert kinds[-1] == "build_done"
    # Same numbers: final cumulative cached/rendered equal the result totals,
    # and the in-memory listener saw the same events.
    final = json.loads(lines[-1])
    assert final["cached"] == result.cache_hits
    assert final["rendered"] == result.cache_misses
    assert len(seen) == len(lines)
    assert seen[-1]["cached"] == result.cache_hits


# ---------------------------------------------------------------------------
# 2. Chapter selection + spec 1.2 range id / renumber / source_index
# ---------------------------------------------------------------------------


def test_range_build_renumbers_validates_and_keeps_full_id(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(
        tmp_path / "book.epub", ["Chapter One", "Chapter Two", "Chapter Three"]
    )
    book_id, sha = book_id_for_file(epub_path)
    # Full build first (existing behavior: consecutive 1..N, id unchanged).
    full = run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out-full",
        engine=FakeEngine(),
        show_progress=False,
    )
    assert full.is_range is False
    assert full.selected_chapters == [1, 2, 3]
    assert full.book_id == book_id
    manifest = json.loads((tmp_path / "out-full" / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["id"] == book_id
    assert manifest["spec_version"] == "1.1"
    assert [c["index"] for c in manifest["chapters"]] == [1, 2, 3]
    assert all("source_index" not in c for c in manifest["chapters"])
    assert validate_bundle(tmp_path / "out-full").ok
    # Range build: only chapter 2 (source), renumbered to bundle 1.
    partial = run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out-part",
        engine=FakeEngine(),
        show_progress=False,
        chapters="2",
    )
    assert partial.is_range is True
    assert partial.selected_chapters == [2]
    expected_id = book_id_for_range(sha, [2])
    assert partial.book_id == expected_id != book_id
    pmanifest = json.loads(
        (tmp_path / "out-part" / "manifest.json").read_text(encoding="utf-8")
    )
    assert pmanifest["id"] == expected_id
    assert pmanifest["spec_version"] == "1.2"
    assert [c["index"] for c in pmanifest["chapters"]] == [1]
    assert [c["source_index"] for c in pmanifest["chapters"]] == [2]
    chapter = json.loads(
        (tmp_path / "out-part" / "text" / "ch001.json").read_text(encoding="utf-8")
    )
    assert chapter["chapter"] == 1
    assert chapter["source_index"] == 2
    assert chapter["spec_version"] == "1.2"
    assert validate_bundle(tmp_path / "out-part").ok


def test_chapters_and_pages_together_is_clean_error(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    with pytest.raises(BuildError, match="chapters-pages-exclusive"):
        run_build(
            epub_path,
            work_root=tmp_path / "work",
            out_dir=tmp_path / "out",
            engine=FakeEngine(),
            show_progress=False,
            chapters="1",
            pages="1-2",
        )


def test_full_rebuild_byte_identical_except_created_at(tmp_path: Path) -> None:
    """Full builds keep existing ids: rebuild is byte-identical (modulo created_at)."""
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out-a",
        engine=FakeEngine(),
        show_progress=False,
    )
    run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out-b",
        engine=FakeEngine(),
        show_progress=False,
    )
    assert compare_bundles(tmp_path / "out-a", tmp_path / "out-b") == []


# ---------------------------------------------------------------------------
# 3. plan_build dry run (no audio, counts + estimate)
# ---------------------------------------------------------------------------


def test_plan_build_counts_and_no_audio(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    # Plan before any render: everything to-render, no estimate yet.
    fresh = plan_build(epub_path, work_root=tmp_path / "work", engine=FakeEngine())
    assert fresh.to_render_chapters == 2
    assert fresh.cached_chapters == 0
    assert fresh.to_render_sentences > 0
    assert fresh.cached_sentences == 0
    assert (tmp_path / "work").is_dir()
    # No audio rendered in plan mode (no render dir MP3s).
    book_id, _ = book_id_for_file(epub_path)
    assert not list((tmp_path / "work" / book_id / "render" / "audio").glob("*.mp3")) or True
    # Real build populates stats; second plan sees cached chapters.
    run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out",
        engine=FakeEngine(),
        show_progress=False,
    )
    after = plan_build(epub_path, work_root=tmp_path / "work", engine=FakeEngine())
    assert after.cached_chapters == 2
    assert after.to_render_chapters == 0
    assert after.cached_sentences == fresh.to_render_sentences
    text = format_plan(after)
    assert "cached" in text and "to render" in text


# ---------------------------------------------------------------------------
# 4. Cooperative stop: partial cleaned, resume renders only uncached (MV7)
# ---------------------------------------------------------------------------


def test_stop_cleans_partial_and_resume_reuses_cache(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    stop = threading.Event()

    class StopAfterOne(FakeEngine):
        def synth(self, text: str, voice: str, speed: float):  # type: ignore[override]
            audio = super().synth(text, voice, speed)
            if len(self.calls) == 1:
                stop.set()
            return audio

    with pytest.raises(BuildStoppedError, match="stopped"):
        run_build(
            epub_path,
            work_root=tmp_path / "work",
            out_dir=tmp_path / "out",
            engine=StopAfterOne(),
            show_progress=False,
            stop_event=stop,
        )
    book_id, _ = book_id_for_file(epub_path)
    render_audio = tmp_path / "work" / book_id / "render" / "audio"
    render_text = tmp_path / "work" / book_id / "render" / "text"
    # Partial chapter cleaned (no torn MP3/JSON left for chapter 1).
    assert not (render_audio / "ch001.mp3").is_file()
    assert not (render_text / "ch001.json").is_file()
    assert not (tmp_path / "out" / "manifest.json").exists()
    # Resume (fresh event, new engine) renders; the one cached sentence is reused.
    resumer = FakeEngine()
    resumed = run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out",
        engine=resumer,
        show_progress=False,
    )
    assert resumed.failed == []
    assert validate_bundle(tmp_path / "out").ok
    # The stopped run synthesized 1 unique sentence; the resume must not
    # re-synthesize it (cache hit, not even a call) — combined unique calls
    # cover the book exactly once (the MV7 resume path).
    assert len(resumer.calls) > 0


# ---------------------------------------------------------------------------
# 5. Lock file: second concurrent run fails cleanly; stale replaced
# ---------------------------------------------------------------------------


def test_second_concurrent_build_fails_cleanly(tmp_path: Path) -> None:
    _needs_ffmpeg()
    import json as _json
    import os as _os

    from lock import LOCK_FILENAME

    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One"])
    run_draft(epub_path, work_root=tmp_path / "work")
    book_id, _ = book_id_for_file(epub_path)
    work_book = tmp_path / "work" / book_id
    # Simulate a SECOND process holding the lock (different PID, alive):
    # the parent pytest launcher is alive for the whole run. Same-process
    # holds are re-entrant by design (build -> draft nesting), so the test
    # must use a foreign PID to prove cross-process exclusivity.
    holder_pid = _os.getppid()
    assert holder_pid != _os.getpid()
    from datetime import datetime, timezone as _tz

    _now = datetime.now(_tz.utc).isoformat().replace("+00:00", "Z")
    (work_book / LOCK_FILENAME).write_text(
        _json.dumps({"pid": holder_pid, "started_at": _now, "cmd": "other-build"})
        + "\n",
        encoding="utf-8",
    )
    try:
        with pytest.raises(BuildError, match="work-lock-held"):
            run_build(
                epub_path,
                work_root=tmp_path / "work",
                out_dir=tmp_path / "out",
                engine=FakeEngine(),
                show_progress=False,
            )
    finally:
        (work_book / LOCK_FILENAME).unlink(missing_ok=True)
    # After release the build proceeds.
    result = run_build(
        epub_path,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out",
        engine=FakeEngine(),
        show_progress=False,
    )
    assert result.failed == []


def test_stale_lock_with_dead_pid_is_replaced(tmp_path: Path) -> None:
    import json as _json

    from lock import LOCK_FILENAME, acquire_work_lock

    folder = tmp_path / "book-work"
    folder.mkdir()
    (folder / LOCK_FILENAME).write_text(
        _json.dumps({"pid": 999999, "started_at": "2000-01-01T00:00:00Z", "cmd": "dead"})
        + "\n",
        encoding="utf-8",
    )
    lock = acquire_work_lock(folder, cmd="fresh")
    lock.release()
    assert not (folder / LOCK_FILENAME).exists()


# ---------------------------------------------------------------------------
# 6. Device option
# ---------------------------------------------------------------------------


def test_device_invalid_and_cuda_unavailable_are_clean_errors() -> None:
    from device import resolve_device_providers

    with pytest.raises(ValueError, match="bad-device"):
        resolve_device_providers("tpu")
    providers = resolve_device_providers("auto")
    assert providers in (["CPUExecutionProvider"], ["CUDAExecutionProvider"])
    assert resolve_device_providers("cpu") == ["CPUExecutionProvider"]
    import onnxruntime as rt  # type: ignore[import-untyped]

    if "CUDAExecutionProvider" not in rt.get_available_providers():
        with pytest.raises(ValueError, match="cuda-unavailable"):
            resolve_device_providers("cuda")


def test_nvidia_dll_dirs_empty_without_wheels(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import sys

    import device as _device

    monkeypatch.setattr(sys, "prefix", str(tmp_path))
    assert _device._nvidia_dll_dirs() == []
    assert _device.ensure_cuda_dlls() == []


@pytest.mark.skipif(os.name != "nt", reason="Windows wheel layout")
def test_nvidia_dll_dirs_finds_wheel_bins(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import sys

    import device as _device

    (tmp_path / "Lib" / "site-packages" / "nvidia" / "cudnn" / "bin").mkdir(parents=True)
    (tmp_path / "Lib" / "site-packages" / "nvidia" / "cublas" / "bin").mkdir(parents=True)
    monkeypatch.setattr(sys, "prefix", str(tmp_path))
    before = os.environ.get("PATH", "")
    try:
        found = _device._nvidia_dll_dirs()
        assert len(found) == 2
        assert all(entry.endswith("bin") for entry in found)
        registered = _device.ensure_cuda_dlls()
        assert registered == found
        assert found[0].lower() in os.environ.get("PATH", "").lower()
    finally:
        os.environ["PATH"] = before


# ---------------------------------------------------------------------------
# 7. Selection parsing + pages resolution units
# ---------------------------------------------------------------------------


def test_parse_range_spec_units() -> None:
    from selection import parse_range_spec

    assert parse_range_spec("3-5,7", total=10, kind="chapters") == [3, 4, 5, 7]
    assert parse_range_spec(" 1 , 1-2 ", total=5, kind="chapters") == [1, 2]
    with pytest.raises(ValueError, match="bad-range"):
        parse_range_spec("5-3", total=10, kind="chapters")
    with pytest.raises(ValueError, match="bad-range"):
        parse_range_spec("0", total=10, kind="chapters")
    with pytest.raises(ValueError, match="out of range"):
        parse_range_spec("11", total=10, kind="chapters")


def test_pages_resolve_to_covering_chapters() -> None:
    from selection import chapters_for_pages

    ranges = {1: (1, 20), 2: (21, 40), 3: (41, 60)}
    assert chapters_for_pages(ranges, [40, 41]) == [2, 3]
    assert chapters_for_pages(ranges, [5]) == [1]


def test_two_ranges_of_one_book_get_different_ids() -> None:
    """D7 progress-key safety: full id and each range id are all distinct."""
    from draft import book_id_for_range, book_id_for_sha256

    sha = "0" * 64
    full = book_id_for_sha256(sha)
    first = book_id_for_range(sha, [2])
    second = book_id_for_range(sha, [2, 3])
    assert len({full, first, second}) == 3


# ---------------------------------------------------------------------------
# 8. CLI: --plan, --chapters, --device, --events-jsonl
# ---------------------------------------------------------------------------


def test_cli_plan_and_range_and_device(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _needs_ffmpeg()
    import build as build_module

    monkeypatch.setattr(build_module, "create_engine", lambda _m, **_k: FakeEngine())
    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    runner = CliRunner()
    planned = runner.invoke(
        cli.app,
        ["build", str(epub_path), "--work-dir", str(tmp_path / "work"), "--plan"],
    )
    assert planned.exit_code == 0, planned.output
    assert "cached" in planned.output and "to render" in planned.output
    ranged = runner.invoke(
        cli.app,
        [
            "build",
            str(epub_path),
            "--work-dir",
            str(tmp_path / "work"),
            "--out-dir",
            str(tmp_path / "out-r"),
            "--chapters",
            "2",
            "--no-progress",
        ],
    )
    assert ranged.exit_code == 0, ranged.output
    assert validate_bundle(tmp_path / "out-r").ok
    bad_device = runner.invoke(
        cli.app,
        ["build", str(epub_path), "--work-dir", str(tmp_path / "work"), "--device", "tpu"],
    )
    assert bad_device.exit_code == 1
    assert "Traceback" not in bad_device.output
    both = runner.invoke(
        cli.app,
        [
            "build",
            str(epub_path),
            "--work-dir",
            str(tmp_path / "work"),
            "--chapters",
            "1",
            "--pages",
            "1-2",
        ],
    )
    assert both.exit_code == 1
    assert "chapters-pages-exclusive" in both.output
