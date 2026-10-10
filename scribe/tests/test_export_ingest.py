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

"""IN2: shared ingestion test data stays fresh and deterministic.

Pins the Slice 9 IN2 verify conditions: running the export twice gives
byte-identical files, and the committed ``spec/fixtures/dialogue-cases.json``
plus ``spec/fixtures/ingest-parity/`` match a fresh export (a pipeline
change without re-export fails here). Also pins the vector contract the
Kotlin port (IN6) consumes: schema shape, required case classes, and
internal consistency of every parity file.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

from typer.testing import CliRunner

from cli import app
from export_ingest import export_all

FIXTURES = Path(__file__).resolve().parents[2] / "spec" / "fixtures"
PARITY_DIR = FIXTURES / "ingest-parity"
RUNNER = CliRunner()

#: Every brief-required case class must appear under at least one vector id.
REQUIRED_CLASSES = {
    "multi-paragraph": {"multiparagraph-continuation", "unclosed-multiparagraph-chain"},
    "singles": {"singles-no-doubles", "multisentence-singles-one-quote"},
    "apostrophes": {
        "apostrophe-in-double-mode",
        "curly-apostrophes",
        "straight-apostrophes",
    },
    "scare quotes": {"scare-quotes-block12"},
    "unbalanced": {
        "inch-mark-stray",
        "intact-pair-with-stray",
        "unclosed-single-paragraph",
        "unclosed-multiparagraph-chain",
    },
    "mixed sentences": {"mixed-straight", "mixed-curly-multisentence", "gold-block-8"},
}


def _copy_fixtures(tmp_path: Path) -> Path:
    """Copy the whole fixtures tree (small: MP3s are tones) for export."""
    target = tmp_path / "fixtures"
    shutil.copytree(FIXTURES, target)
    return target


def _read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _generated_rel_paths(root: Path) -> list[str]:
    """Relative paths of every export-generated file (README is hand-written)."""
    return sorted(
        str(p.relative_to(root)).replace("\\", "/")
        for p in list((root / "ingest-parity").glob("*.json")) + [root / "dialogue-cases.json"]
    )


def test_export_twice_gives_identical_files(tmp_path: Path) -> None:
    first_root = _copy_fixtures(tmp_path / "a")
    second_root = _copy_fixtures(tmp_path / "b")
    first = export_all(first_root)
    second = export_all(second_root)
    assert [p.name for p in first] == [p.name for p in second]
    for generated in _generated_rel_paths(first_root):
        assert (first_root / generated).read_bytes() == (second_root / generated).read_bytes()


def test_committed_files_match_fresh_export(tmp_path: Path) -> None:
    fresh_root = _copy_fixtures(tmp_path)
    export_all(fresh_root)
    for generated in _generated_rel_paths(fresh_root):
        assert (FIXTURES / generated).is_file(), f"committed file missing: {generated}"
        assert (FIXTURES / generated).read_bytes() == (fresh_root / generated).read_bytes(), (
            f"stale committed file (re-run export-ingest-fixtures): {generated}"
        )
    committed = set(_generated_rel_paths(FIXTURES))
    assert committed == set(_generated_rel_paths(fresh_root))


def test_cli_export_writes_deterministic_files(tmp_path: Path) -> None:
    """The thin CLI wrapper exports; two CLI runs agree byte-for-byte."""
    first = _copy_fixtures(tmp_path / "a")
    result = RUNNER.invoke(app, ["export-ingest-fixtures", "--fixtures-dir", str(first)])
    assert result.exit_code == 0, result.output
    assert "dialogue-cases.json" in result.output
    second = _copy_fixtures(tmp_path / "b")
    rerun = RUNNER.invoke(app, ["export-ingest-fixtures", "--fixtures-dir", str(second)])
    assert rerun.exit_code == 0, rerun.output
    for generated in _generated_rel_paths(first):
        assert (first / generated).read_bytes() == (second / generated).read_bytes()


def test_dialogue_vectors_cover_required_classes() -> None:
    document = _read_json(FIXTURES / "dialogue-cases.json")
    assert document["version"] == 1
    assert document["conventions"], "vectors must state their evaluation rules"
    by_id = {case["id"]: case for case in document["cases"]}
    assert len(by_id) == len(document["cases"]), "duplicate vector ids"
    for label, ids in REQUIRED_CLASSES.items():
        assert ids & set(by_id), f"no vector covers {label}"
    for case in document["cases"]:
        assert case["scope"] in ("paragraphs", "chapter"), case["id"]
        assert case["notes"], f"vector {case['id']} must note the rule exercised"
        assert case["input"] and all(isinstance(p, str) for p in case["input"]), case["id"]
        assert case["expected"], f"vector {case['id']} needs expected runs"
        for run in case["expected"]:
            assert run["kind"] in ("narration", "dialogue"), case["id"]
            assert isinstance(run["text"], str), case["id"]
            assert 0 <= run["paragraph"] < len(case["input"]), case["id"]
        for index, paragraph in enumerate(case["input"]):
            joined = "".join(r["text"] for r in case["expected"] if r["paragraph"] == index)
            assert joined == paragraph, f"round trip failed: {case['id']} paragraph {index}"


def test_parity_files_internally_consistent() -> None:
    sources = _read_json(PARITY_DIR / "sources.json")["sources"]
    assert sources, "sources.json must record coverage"
    for entry in sources:
        assert entry["status"] in ("covered", "skipped", "out-of-scope"), entry
        if entry["status"] == "covered":
            parity = _read_json(PARITY_DIR / entry["parity"])
            assert parity["source"]["sha256"] == entry["sha256"]
            assert parity["source"]["fixture"] == entry["fixture"]
            assert [c["index"] for c in parity["chapters"]] == list(
                range(1, len(parity["chapters"]) + 1)
            )
            for chapter in parity["chapters"]:
                assert chapter["words"] >= 0
                for block in chapter["blocks"]:
                    assert block["type"] in ("heading", "para", "quote", "break")
                    assert isinstance(block["text"], str)
                    if block["type"] in ("para", "quote"):
                        assert block["runs"], f"block {block['id']} needs runs"
                        for run in block["runs"]:
                            assert run["kind"] in ("narration", "dialogue")
                        joined = "".join(r["text"] for r in block["runs"])
                        assert joined == block["text"], f"block {block['id']} round trip"
        else:
            assert entry["reason"], f"{entry['fixture']} needs a recorded reason"
