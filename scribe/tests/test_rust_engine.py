# Auloud Scribe - turns ebooks into multi-voice audiobooks (PC tool).
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

"""Rust orchestrator handoff: manifest shape, launcher failures, worker startup.

The Rust binary is not built here, so these cover the Python side of the
contract: the manifest keys `Manifest` in `main.rs` requires, the launcher
surfacing a missing binary, and a worker refusing to claim ready when the
engine cannot load (which would otherwise report a silent no-op build).
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

import rust_engine
from manifest import write_manifest

SCRIBE_DIR = Path(__file__).resolve().parent.parent


def test_manifest_has_every_key_the_rust_side_requires(tmp_path: Path) -> None:
    chapters = [
        {
            "index": 1,
            "title": "Chapter One",
            "sentences": [{"sid": 0, "text": "Hi.", "voice": "am_adam", "speed": 1.0}],
        }
    ]
    path = write_manifest(
        tmp_path / "build_manifest.json",
        book_id="book",
        chapters=chapters,
        models_dir="models",
        device="cpu",
        cache_dir="cache",
        workers=2,
        batch_size=32,
    )
    data = json.loads(path.read_text(encoding="utf-8"))
    assert set(data) == {
        "v",
        "book_id",
        "models_dir",
        "device",
        "cache_dir",
        "workers",
        "batch_size",
        "chapters",
    }
    assert data["v"] == 1
    assert data["chapters"] == chapters


def test_launcher_raises_when_the_binary_is_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(rust_engine, "find_orchestrator_binary", lambda: None)
    with pytest.raises(RuntimeError, match="not found"):
        rust_engine.run_rust_build(
            book_id="book",
            chapters=[],
            models_dir="models",
            device="cpu",
            work_dir=".",
            cache_dir="cache",
        )


def test_worker_exits_non_zero_when_the_engine_cannot_load(tmp_path: Path) -> None:
    """No model files means no ready frame: the build must fail, not no-op."""
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIBE_DIR / "worker.py"),
            "--models-dir", str(tmp_path / "no-models-here"),
            "--device", "cpu",
            "--cache-dir", str(tmp_path),
        ],
        input=b"",
        capture_output=True,
        timeout=300,
    )
    assert result.returncode != 0
    assert b"cannot load engine" in result.stderr
