# Auloud Scribe - tests for build stage timing (FU15, #35).
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

"""FU15: stage timings come from call-site hooks, never from patched globals.

The timing output (``stage timings (s)`` in ``scribe.log``) keeps its keys.
Two builds in one process must each report their own totals, and a build
must leave ``build.assemble_chapter``, ``encode_assembled_chapter`` and the
``tts.cache`` read/write functions exactly as they were.
"""

from __future__ import annotations

import re
import threading
import time
from contextlib import contextmanager
from pathlib import Path

import numpy as np
import pytest

import build as build_module
import tts.cache as cache_mod
from build import StageTimes, run_build
from test_build import FakeEngine, _make_epub, _needs_ffmpeg
from tts.base import TTSEngine
from tts.cache import get_or_synth

STAGE_LINE = re.compile(r"stage timings \(s\): (.*)")


def _parse_stage_line(log_path: Path) -> dict[str, float]:
    match = STAGE_LINE.search(log_path.read_text(encoding="utf-8"))
    assert match, "no 'stage timings (s)' line in scribe.log"
    return {k: float(v) for k, v in (part.split("=") for part in match.group(1).split(", "))}


class SleepyEngine(FakeEngine):
    """FakeEngine with a fixed per-call delay and its own cache namespace."""

    def __init__(self, delay_s: float) -> None:
        super().__init__(version="sleepy-1")
        self.delay_s = delay_s

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        time.sleep(self.delay_s)
        return super().synth(text, voice, speed)


class _Recorder(TTSEngine):
    @property
    def sample_rate(self) -> int:
        return 24_000

    @property
    def engine_version(self) -> str:
        return "rec-1"

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        return np.full(2400, 0.1, dtype=np.float32)


def test_stage_times_adds_from_threads_without_losing_updates() -> None:
    times = StageTimes(("assemble", "encode"))

    def worker() -> None:
        for _ in range(500):
            times.add("assemble", 0.001)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    totals = times.snapshot()
    assert totals["assemble"] == pytest.approx(4.0)
    assert totals["encode"] == 0.0


def test_stage_times_timed_records_elapsed_even_when_body_raises() -> None:
    times = StageTimes(("draft",))
    with pytest.raises(RuntimeError):
        with times.timed("draft"):
            time.sleep(0.01)
            raise RuntimeError("boom")
    assert times.snapshot()["draft"] >= 0.01


def test_get_or_synth_reports_its_own_stages_through_timer(tmp_path: Path) -> None:
    seen: list[str] = []

    @contextmanager
    def timer(stage: str):
        seen.append(stage)
        yield

    engine = _Recorder()
    get_or_synth(engine, "Hi there.", "v", 1.0, 1.0, tmp_path, timer=timer)
    assert seen == ["cache_read", "synth_total", "cache_write"]

    seen.clear()
    get_or_synth(engine, "Hi there.", "v", 1.0, 1.0, tmp_path, timer=timer)
    assert seen == ["cache_read"]


def test_build_never_replaces_module_globals_while_rendering(tmp_path: Path) -> None:
    _needs_ffmpeg()
    originals = (
        build_module.assemble_chapter,
        build_module.encode_assembled_chapter,
        cache_mod._read_cached,
        cache_mod._store_cached,
    )
    seen_during_build: list[tuple[object, ...]] = []

    class SpyEngine(FakeEngine):
        def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
            seen_during_build.append(
                (
                    build_module.assemble_chapter,
                    build_module.encode_assembled_chapter,
                    cache_mod._read_cached,
                    cache_mod._store_cached,
                )
            )
            return super().synth(text, voice, speed)

    engine = SpyEngine()
    epub = _make_epub(tmp_path / "book.epub", ["Chapter One"])
    run_build(
        epub,
        work_root=tmp_path / "work",
        out_dir=tmp_path / "out",
        engine=engine,
        show_progress=False,
    )
    assert seen_during_build, "the spy engine never ran"
    assert all(seen == originals for seen in seen_during_build)
    assert (
        build_module.assemble_chapter,
        build_module.encode_assembled_chapter,
        cache_mod._read_cached,
        cache_mod._store_cached,
    ) == originals
    assert "synth" not in vars(engine)


def test_two_builds_in_one_process_report_independent_timings(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub = _make_epub(tmp_path / "book.epub", ["Chapter One"])
    slow_engine = SleepyEngine(delay_s=0.01)
    slow = run_build(
        epub,
        work_root=tmp_path / "work-slow",
        out_dir=tmp_path / "out-slow",
        engine=slow_engine,
        show_progress=False,
    )
    fast = run_build(
        epub,
        work_root=tmp_path / "work-fast",
        out_dir=tmp_path / "out-fast",
        engine=FakeEngine(version="fake-fast"),
        show_progress=False,
    )
    slow_t = _parse_stage_line(slow.log_path)
    fast_t = _parse_stage_line(fast.log_path)
    assert set(slow_t) == {
        "draft", "phonemize", "inference", "assemble", "encode",
        "cache_read", "cache_write", "synth_total", "total",
    }
    assert slow_t["synth_total"] >= 0.01 * len(slow_engine.calls)
    assert fast_t["synth_total"] < slow_t["synth_total"] / 2
    assert fast_t["total"] < slow_t["total"] + 1.0
