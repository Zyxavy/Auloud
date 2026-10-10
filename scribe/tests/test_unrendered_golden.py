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

"""IN1: spec 2.0 part 1 contract (``spec/fixtures/unrendered-golden/``).

Pins D-082: the hand-written unrendered golden (text plus sentence
structure, no audio, no timings) validates; a rendered 1.x bundle still
validates beside it; every invalid 2.0 combination is rejected naming the
file and the rule. The Player pins the same fixture from the other
direction (``UnrenderedGoldenTest``); together they catch drift between
the halves.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

from bundle.validate import AudioProbe, validate_bundle

GOLDEN = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "unrendered-golden"

#: Device-namespace id: UUIDv5 over ``auloud:device-book:<sha256-hex>``.
GOLDEN_BOOK_ID = "f8be80a6-3e94-56fa-9fb7-c88444bf239d"
GOLDEN_SOURCE_SHA = "9fdc3abf2e08eb43834276e67c2d304758acbd15f051e94b960b5d3ddfce13f1"


def _stub_probe(durations: dict[str, int] | None = None) -> Any:
    """Audio probe stub (unrendered chapters never call it)."""

    def _probe(path: Path) -> AudioProbe:
        return AudioProbe(
            codec="mp3",
            channels=1,
            sample_rate=24000,
            bit_rate_bps=64000,
            bit_rate_from_stream=True,
            duration_ms=(durations or {}).get(Path(path).name, 4000),
            cbr_frames=True,
        )

    return _probe


def _read_manifest(root: Path) -> dict[str, Any]:
    return json.loads((root / "manifest.json").read_text(encoding="utf-8"))


def _write_manifest(root: Path, manifest: dict[str, Any]) -> None:
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


def _read_chapter(root: Path, name: str) -> dict[str, Any]:
    return json.loads((root / "text" / name).read_text(encoding="utf-8"))


def _write_chapter(root: Path, name: str, chapter: dict[str, Any]) -> None:
    (root / "text" / name).write_text(json.dumps(chapter), encoding="utf-8")


def _copy_golden(tmp_path: Path) -> Path:
    root = tmp_path / "unrendered-golden"
    shutil.copytree(GOLDEN, root)
    return root


def _render_chapter(root: Path, name: str, duration_ms: int, audio_name: str) -> None:
    """Give one golden chapter timings plus an (empty) MP3 for partial/complete tests."""
    chapter = _read_chapter(root, name)
    chapter["duration_ms"] = duration_ms
    sentences = [s for b in chapter["blocks"] for s in b.get("sentences", [])]
    step = duration_ms // len(sentences)
    for pos, sentence in enumerate(sentences):
        sentence["start_ms"] = pos * step
        sentence["end_ms"] = (pos + 1) * step
    _write_chapter(root, name, chapter)
    manifest = _read_manifest(root)
    for entry in manifest["chapters"]:
        if entry["text"] == f"text/{name}":
            entry["audio"] = f"audio/{audio_name}"
            entry["duration_ms"] = duration_ms
    manifest["audio"] = {
        "format": "mp3",
        "channels": 1,
        "sample_rate": 24000,
        "bitrate_kbps": 64,
        "cbr": True,
    }
    _write_manifest(root, manifest)
    (root / "audio").mkdir(exist_ok=True)
    (root / "audio" / audio_name).write_bytes(b"")


# ---------------------------------------------------------------------------
# The golden itself
# ---------------------------------------------------------------------------


def test_unrendered_golden_present() -> None:
    for rel in (
        "manifest.json",
        "README.md",
        "text/ch001.json",
        "text/ch002.json",
        "source/book.epub",
    ):
        assert (GOLDEN / rel).is_file(), f"golden file missing: {rel}"
    assert not (GOLDEN / "audio").exists(), "unrendered golden must have no audio dir"


def test_unrendered_golden_pins() -> None:
    manifest = json.loads((GOLDEN / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["spec_version"] == "2.0"
    assert manifest["render_state"] == "none"
    assert manifest["id"] == GOLDEN_BOOK_ID
    assert manifest["source"]["sha256"] == GOLDEN_SOURCE_SHA
    assert "audio" not in manifest
    assert set(manifest["voices"]) >= {"narrator", "dialogue"}
    assert [c["index"] for c in manifest["chapters"]] == [1, 2]
    for entry in manifest["chapters"]:
        assert "audio" not in entry and "duration_ms" not in entry
        chapter = json.loads((GOLDEN / entry["text"]).read_text(encoding="utf-8"))
        assert chapter["spec_version"] == "2.0"
        assert "duration_ms" not in chapter
        sentences = [s for b in chapter["blocks"] for s in b.get("sentences", [])]
        assert [s["sid"] for s in sentences] == list(range(1, len(sentences) + 1))
        assert all("start_ms" not in s and "end_ms" not in s for s in sentences)
        assert all(s["speaker"] in ("narrator", "dialogue") for s in sentences)
    total = sum(
        len([s for b in json.loads((GOLDEN / e["text"]).read_text(encoding="utf-8"))["blocks"] for s in b.get("sentences", [])])
        for e in manifest["chapters"]
    )
    assert total == 7


def test_unrendered_golden_validates_without_ffprobe(tmp_path: Path) -> None:
    """No stub probe: unrendered chapters need no audio probing at all."""
    root = _copy_golden(tmp_path)

    def _boom(path: Path) -> AudioProbe:
        raise AssertionError(f"probe must not run for unrendered books: {path}")

    result = validate_bundle(root, probe=_boom)
    assert result.ok, result.errors


def test_unrendered_golden_validates_via_cli() -> None:
    from typer.testing import CliRunner

    from cli import app

    result = CliRunner().invoke(app, ["validate", str(GOLDEN)])
    assert result.exit_code == 0, result.output
    assert "valid:" in result.output


# ---------------------------------------------------------------------------
# Rendered 1.x bundles still validate (regression beside the new world)
# ---------------------------------------------------------------------------


def test_rendered_1x_bundles_still_validate(tmp_path: Path) -> None:
    fixtures = Path(__file__).resolve().parents[2] / "spec" / "fixtures"
    root = tmp_path / "valid-bundle"
    shutil.copytree(fixtures / "valid-bundle", root)
    (root / "source").mkdir()
    (root / "source" / "book.epub").write_bytes(b"")
    result = validate_bundle(root, probe=_stub_probe({"ch001.mp3": 1832400, "ch002.mp3": 1640100}))
    assert result.ok, result.errors


def test_1x_manifest_with_render_state_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    manifest["spec_version"] = "1.1"
    manifest["render_state"] = "complete"
    manifest["audio"] = {
        "format": "mp3",
        "channels": 1,
        "sample_rate": 24000,
        "bitrate_kbps": 64,
        "cbr": True,
    }
    for entry in manifest["chapters"]:
        entry["audio"] = "audio/ch001.mp3"
        entry["duration_ms"] = 4000
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "render_state" in joined


# ---------------------------------------------------------------------------
# Invalid 2.0 combinations: every error names the file and the rule
# ---------------------------------------------------------------------------


def test_missing_render_state_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    del manifest["render_state"]
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "render_state" in joined


def test_bad_render_state_value_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    manifest["render_state"] = "half"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "half" in joined


def test_render_state_mismatch_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    manifest["render_state"] = "complete"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "complete" in joined


def test_partial_needs_both_kinds(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    manifest["render_state"] = "partial"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "partial" in joined


def test_audio_without_duration_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    manifest["chapters"][0]["audio"] = "audio/ch001.mp3"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "audio without duration_ms" in joined


def test_audio_object_with_none_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    manifest["audio"] = {
        "format": "mp3",
        "channels": 1,
        "sample_rate": 24000,
        "bitrate_kbps": 64,
        "cbr": True,
    }
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "audio" in joined


def test_chapter_spec_mismatch_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    chapter = _read_chapter(root, "ch001.json")
    chapter["spec_version"] = "1.1"
    _write_chapter(root, "ch001.json", chapter)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "text/ch001.json" in joined and "spec_version" in joined


def test_timings_in_unrendered_chapter_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    chapter = _read_chapter(root, "ch001.json")
    first = chapter["blocks"][1]["sentences"][0]
    first["start_ms"] = 0
    first["end_ms"] = 100
    _write_chapter(root, "ch001.json", chapter)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "text/ch001.json" in joined and "timings" in joined


def test_foreign_speaker_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    chapter = _read_chapter(root, "ch001.json")
    chapter["blocks"][1]["sentences"][0]["speaker"] = "Ana"
    _write_chapter(root, "ch001.json", chapter)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "text/ch001.json" in joined and "Ana" in joined


def test_voices_missing_dialogue_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    del manifest["voices"]["dialogue"]
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "dialogue" in joined


def test_unknown_spec_version_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    manifest = _read_manifest(root)
    manifest["spec_version"] = "3.0"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "3.0" in joined


# ---------------------------------------------------------------------------
# Partial and complete 2.0 books validate (rendered chapters keep timings)
# ---------------------------------------------------------------------------


def test_partial_20_validates(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    _render_chapter(root, "ch001.json", 4000, "ch001.mp3")
    manifest = _read_manifest(root)
    manifest["render_state"] = "partial"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe({"ch001.mp3": 4000}))
    assert result.ok, result.errors


def test_complete_20_validates(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    _render_chapter(root, "ch001.json", 4000, "ch001.mp3")
    _render_chapter(root, "ch002.json", 3000, "ch002.mp3")
    manifest = _read_manifest(root)
    manifest["render_state"] = "complete"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe({"ch001.mp3": 4000, "ch002.mp3": 3000}))
    assert result.ok, result.errors


def test_partial_wrong_state_rejected(tmp_path: Path) -> None:
    root = _copy_golden(tmp_path)
    _render_chapter(root, "ch001.json", 4000, "ch001.mp3")
    manifest = _read_manifest(root)
    manifest["render_state"] = "complete"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_probe({"ch001.mp3": 4000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "complete" in joined
