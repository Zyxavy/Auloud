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

"""RN2: shared assembly-timing vectors stay fresh and deterministic.

Pins the Slice 10 RN2 verify conditions: the committed
``spec/fixtures/timing-vectors/`` files match a fresh export (a pause
or assembly change without re-export fails here), running the export
twice gives byte-identical files, and every vector replays through the
real code (``tts.base.resample_mono`` for native lengths, then
``audio.assemble`` for offsets). The Player replays the same vectors
on the JVM (RN5); together they catch drift between halves.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest
from typer.testing import CliRunner

from audio.assemble import (
    PAUSE_BREAK_MS,
    PAUSE_HEADING_MS,
    PAUSE_PARA_MS,
    PAUSE_SENTENCE_MS,
    PAUSE_TAG_MS,
    assemble_chapter,
    ms_for_samples,
)
from bundle.models import Block, ChapterFile, Sentence
from cli import app
from export_timing import TIMING_VECTORS_DIRNAME, TIMING_VECTORS_VERSION, export_all
from tts.base import SAMPLE_RATE, resample_mono

FIXTURES = Path(__file__).resolve().parents[2] / "spec" / "fixtures"
VECTORS_DIR = FIXTURES / TIMING_VECTORS_DIRNAME
VECTORS_PATH = VECTORS_DIR / "timing-vectors.json"
README_PATH = VECTORS_DIR / "README.md"
RUNNER = CliRunner()

#: Every pause rule must appear as a committed pause_after_ms gap.
REQUIRED_PAUSES = {
    PAUSE_SENTENCE_MS,
    PAUSE_PARA_MS,
    PAUSE_HEADING_MS,
    PAUSE_BREAK_MS,
    PAUSE_TAG_MS,
}


def _read_vectors() -> dict:
    return json.loads(VECTORS_PATH.read_text(encoding="utf-8"))


def _rebuild_chapter(case: dict) -> tuple[ChapterFile, dict[int, np.ndarray]]:
    """Rebuild a ChapterFile plus resampled sentence audio from a vector case."""
    blocks: list[Block] = []
    audio_by_sid: dict[int, np.ndarray] = {}
    for block_raw in case["blocks"]:
        sentences: list[Sentence] = []
        for sent_raw in block_raw.get("sentences", []):
            native = np.full((sent_raw["samples"],), 0.5, dtype=np.float32)
            resampled = resample_mono(native, sent_raw["sample_rate"], SAMPLE_RATE)
            assert int(resampled.size) == sent_raw["resampled_samples"], (
                f"{case['id']} sid {sent_raw['sid']}: resample length drifted "
                f"({resampled.size} vs committed {sent_raw['resampled_samples']})"
            )
            audio_by_sid[sent_raw["sid"]] = resampled
            sentences.append(
                Sentence(
                    sid=sent_raw["sid"],
                    speaker=sent_raw["speaker"],
                    start_ms=0,
                    end_ms=0,
                    text=f"Vector {case['id']} sentence {sent_raw['sid']}.",
                    split_pair=sent_raw["split_pair"],
                )
            )
        blocks.append(Block(id=block_raw["id"], type=block_raw["type"], sentences=sentences))
    chapter = ChapterFile(
        spec_version="1.0", chapter=1, title=f"Vector {case['id']}",
        duration_ms=0, blocks=blocks,
    )
    return chapter, audio_by_sid


def _assemble_case(case: dict):
    chapter, audio_by_sid = _rebuild_chapter(case)

    def _synth(sentence: Sentence) -> np.ndarray:
        return audio_by_sid[sentence.sid]

    return assemble_chapter(chapter, _synth)


# ---------------------------------------------------------------------------
# Contract shape
# ---------------------------------------------------------------------------


def test_vectors_present_and_versioned() -> None:
    assert VECTORS_PATH.is_file(), "committed timing vectors missing"
    assert README_PATH.is_file(), "committed timing-vectors README missing"
    document = _read_vectors()
    assert document["version"] == TIMING_VECTORS_VERSION
    assert document["conventions"], "vectors must state their evaluation rules"
    assert document["bundle_rate_hz"] == SAMPLE_RATE == 24000
    assert document["pauses_ms"] == {
        "sentence": PAUSE_SENTENCE_MS,
        "para": PAUSE_PARA_MS,
        "heading": PAUSE_HEADING_MS,
        "break": PAUSE_BREAK_MS,
        "tag": PAUSE_TAG_MS,
    }
    by_id = {case["id"]: case for case in document["cases"]}
    assert len(by_id) == len(document["cases"]), "duplicate vector ids"
    assert len(by_id) >= 10, "vectors must cover the full pause matrix plus rates"


def test_vectors_use_integers_only_for_ms_samples_rates() -> None:
    document = _read_vectors()
    for case in document["cases"]:
        assert isinstance(case["id"], str) and case["id"]
        assert isinstance(case["notes"], str) and case["notes"]
        for block in case["blocks"]:
            assert isinstance(block["id"], int) and not isinstance(block["id"], bool)
            assert block["type"] in ("heading", "para", "quote", "break")
            for sent in block.get("sentences", []):
                assert sent["kind"] in ("narration", "dialogue"), case["id"]
                assert sent["speaker"] in ("narrator", "dialogue"), case["id"]
                for key in ("sample_rate", "samples", "resampled_samples"):
                    value = sent[key]
                    assert isinstance(value, int) and not isinstance(value, bool), (
                        f"{case['id']} sid {sent['sid']}: {key} must be an integer"
                    )
                    assert value > 0
                pair = sent["split_pair"]
                assert pair is None or (isinstance(pair, int) and not isinstance(pair, bool))
        expected = case["expected"]
        assert isinstance(expected["duration_ms"], int)
        assert isinstance(expected["sample_count"], int)
        for timing in expected["timings"]:
            for key in ("sid", "start_ms", "end_ms", "pause_after_ms"):
                assert isinstance(timing[key], int) and not isinstance(timing[key], bool)


def test_vectors_cover_required_rules() -> None:
    """Pause matrix, mixed rates, leading-break drop and a split pair."""
    document = _read_vectors()
    gaps: set[int] = set()
    rates: set[int] = set()
    has_leading_break = False
    has_split_pair = False
    for case in document["cases"]:
        for timing in case["expected"]["timings"]:
            gaps.add(timing["pause_after_ms"])
        for block in case["blocks"]:
            for sent in block.get("sentences", []):
                rates.add(sent["sample_rate"])
                if sent["split_pair"] is not None:
                    has_split_pair = True
        first = case["blocks"][0]
        if first["type"] == "break" and not first.get("sentences"):
            has_leading_break = True
    for pause in (PAUSE_SENTENCE_MS, PAUSE_PARA_MS, PAUSE_HEADING_MS, PAUSE_TAG_MS):
        assert pause in gaps, f"no vector pins the {pause} ms pause"
    assert any(gap >= PAUSE_BREAK_MS for gap in gaps), "no vector pins the 1000 ms break"
    assert len(rates) >= 4, f"vectors must span several native rates, got {sorted(rates)}"
    assert 24000 in rates
    assert has_leading_break, "vectors must pin the leading-break silence drop"
    assert has_split_pair, "vectors must pin at least one split-pair tag case"


def test_no_forbidden_characters_in_committed_files() -> None:
    for path in (VECTORS_PATH, README_PATH):
        text = path.read_text(encoding="utf-8")
        assert "—" not in text, f"{path.name} holds an em-dash"
        assert "→" not in text, f"{path.name} holds an arrow"


# ---------------------------------------------------------------------------
# Vectors replay through the real code
# ---------------------------------------------------------------------------


def _case_ids() -> list[str]:
    return [case["id"] for case in _read_vectors()["cases"]]


@pytest.mark.parametrize("case_id", _case_ids())
def test_case_matches_assembly(case_id: str) -> None:
    """Committed timings, pauses and duration equal a fresh assembly."""
    case = next(c for c in _read_vectors()["cases"] if c["id"] == case_id)
    out = _assemble_case(case)
    expected = case["expected"]
    assert [(t.sid, t.start_ms, t.end_ms) for t in out.timings] == [
        (t["sid"], t["start_ms"], t["end_ms"]) for t in expected["timings"]
    ]
    assert out.duration_ms == expected["duration_ms"]
    assert out.sample_count == expected["sample_count"]


@pytest.mark.parametrize("case_id", _case_ids())
def test_case_invariants_hold(case_id: str) -> None:
    """First start 0, ordered non-overlap, end excludes pause, gaps pinned."""
    case = next(c for c in _read_vectors()["cases"] if c["id"] == case_id)
    expected = case["expected"]
    timings = expected["timings"]
    assert [t["sid"] for t in timings] == list(range(1, len(timings) + 1))
    assert timings[0]["start_ms"] == 0
    prev_end = 0
    by_sid = {
        sent["sid"]: sent
        for block in case["blocks"]
        for sent in block.get("sentences", [])
    }
    for pos, timing in enumerate(timings):
        assert timing["start_ms"] >= prev_end
        assert timing["start_ms"] < timing["end_ms"] <= expected["duration_ms"]
        # Sentence audio holds no pause: end-start is the resampled length in ms.
        resampled = by_sid[timing["sid"]]["resampled_samples"]
        assert timing["end_ms"] - timing["start_ms"] == ms_for_samples(resampled)
        if pos + 1 < len(timings):
            assert timing["pause_after_ms"] == timings[pos + 1]["start_ms"] - timing["end_ms"]
        else:
            assert timing["pause_after_ms"] == expected["duration_ms"] - timing["end_ms"]
        prev_end = timing["end_ms"]


def test_rounding_case_pins_half_even() -> None:
    """The .5 ms boundaries must round half-even (half-up would differ).

    Offsets come from the committed resampled lengths plus exact pause
    samples, so this test replays the boundary values both roundings
    would see and proves the committed choice discriminates them.
    """
    import math

    case = next(c for c in _read_vectors()["cases"] if c["id"] == "rounding-half-even")
    sent_lengths = [
        sent["resampled_samples"]
        for block in case["blocks"]
        for sent in block.get("sentences", [])
    ]
    assert sent_lengths == [2412, 2400]
    start2 = 2412 + PAUSE_SENTENCE_MS * SAMPLE_RATE // 1000
    end2 = start2 + 2400
    duration = end2 + PAUSE_PARA_MS * SAMPLE_RATE // 1000
    assert (start2, end2, duration) == (8412, 10812, 22812)

    def _half_up(samples: int) -> int:
        return math.floor(samples * 1000 / SAMPLE_RATE + 0.5)

    timings = case["expected"]["timings"]
    assert [(t["sid"], t["start_ms"], t["end_ms"]) for t in timings] == [
        (1, 0, 100),
        (2, 350, 450),
    ]
    assert case["expected"]["duration_ms"] == 950
    # Every boundary is an exact .5 ms, so half-up disagrees everywhere.
    assert [2412 * 1000 / SAMPLE_RATE, start2 * 1000 / SAMPLE_RATE] == [100.5, 350.5]
    assert [_half_up(s) for s in (2412, start2, end2, duration)] == [101, 351, 451, 951]


# ---------------------------------------------------------------------------
# Export freshness (mirrors test_export_ingest.py)
# ---------------------------------------------------------------------------


def _generated_rel_paths(root: Path) -> list[str]:
    return sorted(
        str(p.relative_to(root)).replace("\\", "/")
        for p in (root / TIMING_VECTORS_DIRNAME).rglob("*")
        if p.is_file()
    )


def test_export_twice_gives_identical_files(tmp_path: Path) -> None:
    first = tmp_path / "a"
    second = tmp_path / "b"
    first.mkdir(parents=True)
    second.mkdir(parents=True)
    export_all(first)
    export_all(second)
    assert _generated_rel_paths(first) == _generated_rel_paths(second)
    for rel in _generated_rel_paths(first):
        assert (first / rel).read_bytes() == (second / rel).read_bytes(), rel


def test_committed_files_match_fresh_export(tmp_path: Path) -> None:
    fresh = tmp_path / "fixtures"
    fresh.mkdir(parents=True)
    export_all(fresh)
    for rel in _generated_rel_paths(fresh):
        assert (FIXTURES / rel).is_file(), f"committed file missing: {rel}"
        assert (FIXTURES / rel).read_bytes() == (fresh / rel).read_bytes(), (
            f"stale committed file (re-run export-timing-vectors): {rel}"
        )
    assert set(_generated_rel_paths(FIXTURES)) >= set(_generated_rel_paths(fresh))


def test_cli_export_writes_deterministic_files(tmp_path: Path) -> None:
    """The thin CLI wrapper exports; two CLI runs agree byte-for-byte."""
    first = tmp_path / "a"
    first.mkdir(parents=True)
    result = RUNNER.invoke(app, ["export-timing-vectors", "--fixtures-dir", str(first)])
    assert result.exit_code == 0, result.output
    assert "timing-vectors.json" in result.output
    second = tmp_path / "b"
    second.mkdir(parents=True)
    rerun = RUNNER.invoke(app, ["export-timing-vectors", "--fixtures-dir", str(second)])
    assert rerun.exit_code == 0, rerun.output
    for rel in _generated_rel_paths(first):
        assert (first / rel).read_bytes() == (second / rel).read_bytes(), rel
