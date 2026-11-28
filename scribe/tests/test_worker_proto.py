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

"""Contract: the orchestrator fixtures in spec/orchestrator classify in worker.py."""

from __future__ import annotations

import json
from pathlib import Path

import worker

SPEC_DIR = Path(__file__).resolve().parents[2] / "spec" / "orchestrator"
ORCHESTRATOR_TO_WORKER = {"hello", "synth_batch", "shutdown"}
WORKER_TO_ORCHESTRATOR = {"ready", "batch_done", "batch_error"}


def _fixtures() -> list[tuple[str, dict]]:
    return [
        (path.name, json.loads(path.read_text(encoding="utf-8")))
        for path in sorted(SPEC_DIR.glob("*.json"))
        if not path.name.endswith(".schema.json")
    ]


def test_orchestrator_fixtures_classify_as_their_type() -> None:
    frames = [(name, f) for name, f in _fixtures() if f["type"] in ORCHESTRATOR_TO_WORKER]
    assert {f["type"] for _, f in frames} >= {"hello", "synth_batch"}
    for name, frame in frames:
        assert worker._classify(frame) == frame["type"], name


def test_worker_replies_are_not_accepted_as_requests() -> None:
    frames = [(name, f) for name, f in _fixtures() if f["type"] in WORKER_TO_ORCHESTRATOR]
    assert {f["type"] for _, f in frames} == WORKER_TO_ORCHESTRATOR
    for name, frame in frames:
        assert worker._classify(frame) == "unknown", name
