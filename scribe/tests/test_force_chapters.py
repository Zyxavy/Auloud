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

"""Task 5: ``--force-chapters`` re-renders only the listed chapters.

Reuses the fake-engine + real-ffmpeg harness from ``test_build.py``. A forced
source chapter bypasses the resume-cache read (:func:`build.chapter_up_to_date`),
so it re-renders and overwrites its render output even when its fingerprint is
unchanged, while every other chapter stays cached. The shared sentence-audio
cache is never deleted.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from typer.testing import CliRunner

import build as build_module
import cli
from build import run_build
from bundle.validate import validate_bundle
from draft import book_id_for_file
from test_build import FakeEngine, _make_epub, _needs_ffmpeg


def _cache_names(work_root: Path, book_id: str) -> list[str]:
    """Sentence-audio cache entries (sorted) for one book."""
    cache_dir = work_root / book_id / "cache"
    return sorted(p.name for p in cache_dir.glob("*.flac"))


class _EncodeSpy:
    """Wrap the real encoder to record which chapters were re-encoded.

    A chapter only reaches the encoder when it actually re-renders, so the
    recorded labels are the chapters whose output was overwritten.
    """

    def __init__(self, real) -> None:
        self.real = real
        self.labels: list[str] = []

    def __call__(self, to_encode, mp3_path, chapter_label: str = "") -> None:
        self.labels.append(chapter_label)
        return self.real(to_encode, mp3_path, chapter_label=chapter_label)


def test_force_chapters_rerenders_only_listed(tmp_path: Path) -> None:
    """A steady rerun skips everything; forcing 2 re-renders only chapter 2."""
    _needs_ffmpeg()
    epub_path = _make_epub(
        tmp_path / "book.epub", ["Chapter One", "Chapter Two", "Chapter Three"]
    )
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    first = run_build(
        epub_path, work_root=work_root, out_dir=out, engine=FakeEngine(),
        show_progress=False,
    )
    assert (first.rendered, first.skipped) == (3, 0)

    steady = run_build(
        epub_path, work_root=work_root, out_dir=out, engine=FakeEngine(),
        show_progress=False,
    )
    assert (steady.rendered, steady.skipped) == (0, 3)

    forced = run_build(
        epub_path, work_root=work_root, out_dir=out, engine=FakeEngine(),
        show_progress=False, force_chapters="2",
    )
    assert (forced.rendered, forced.skipped) == (1, 2)
    assert validate_bundle(out).ok


def test_force_chapters_preserves_shared_sentence_cache(tmp_path: Path) -> None:
    """Forcing chapters neither deletes nor rewrites shared cache entries."""
    _needs_ffmpeg()
    epub_path = _make_epub(
        tmp_path / "book.epub", ["Chapter One", "Chapter Two", "Chapter Three"]
    )
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    first = run_build(
        epub_path, work_root=work_root, out_dir=out, engine=FakeEngine(),
        show_progress=False,
    )
    assert (first.rendered, first.skipped) == (3, 0)
    book_id, _ = book_id_for_file(epub_path)
    before = _cache_names(work_root, book_id)
    assert before  # the fake engine populated the sentence cache

    forced = run_build(
        epub_path, work_root=work_root, out_dir=out, engine=FakeEngine(),
        show_progress=False, force_chapters="1,3",
    )
    assert (forced.rendered, forced.skipped) == (2, 1)
    # Every shared sentence entry survives untouched (no delete, no rewrite).
    assert _cache_names(work_root, book_id) == before
    assert validate_bundle(out).ok


def test_force_chapters_overwrites_only_listed_output(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Only the forced chapter reaches the encoder (its output is overwritten)."""
    _needs_ffmpeg()
    epub_path = _make_epub(
        tmp_path / "book.epub", ["Chapter One", "Chapter Two", "Chapter Three"]
    )
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    run_build(
        epub_path, work_root=work_root, out_dir=out, engine=FakeEngine(),
        show_progress=False,
    )

    spy = _EncodeSpy(build_module.encode_assembled_chapter)
    monkeypatch.setattr(build_module, "encode_assembled_chapter", spy)
    forced = run_build(
        epub_path, work_root=work_root, out_dir=out, engine=FakeEngine(),
        show_progress=False, force_chapters="2",
    )
    assert (forced.rendered, forced.skipped) == (1, 2)
    assert spy.labels == ["ch002"]


def test_cli_force_chapters_flag(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The thin CLI wires ``--force-chapters`` through to ``run_build``."""
    _needs_ffmpeg()
    monkeypatch.setattr(
        build_module, "create_engine", lambda _models, **_k: FakeEngine()
    )
    epub_path = _make_epub(
        tmp_path / "book.epub", ["Chapter One", "Chapter Two", "Chapter Three"]
    )
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    runner = CliRunner()
    common = [
        "build", str(epub_path),
        "--work-dir", str(work_root),
        "--out-dir", str(out),
        "--no-progress",
    ]
    primed = runner.invoke(cli.app, common)
    assert primed.exit_code == 0, primed.output

    forced = runner.invoke(cli.app, [*common, "--force-chapters", "2"])
    assert forced.exit_code == 0, forced.output
    assert "rendered: 1, skipped: 2" in forced.output
