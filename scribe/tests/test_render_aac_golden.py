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

"""RN1: spec 2.0 part 2 contract (rendered/partial AAC goldens, D-102).

Pins the device-rendering half of the bundle contract: the AAC/M4A
audio entry (per-chapter format from the extension, mixed books legal),
the provisional AAC tolerance, per-chapter ``render_fingerprint``,
manifest ``gain_db`` (decibels) plus ``encoder_offset_ms``, and partial
semantics. Both goldens were built by ``scribe export-render-fixtures``
(real ffmpeg sine-tone M4A); tests here only READ them, never rebuild
them, except the freshness tests which re-export into tmp. The Player
pins the same fixtures from the other direction
(``RenderedAacGoldenTest``); together they catch drift between halves.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import uuid
from pathlib import Path
from typing import Any

from bundle.validate import AudioProbe, validate_bundle

FIXTURES = Path(__file__).resolve().parents[2] / "spec" / "fixtures"
RENDERED = FIXTURES / "rendered-aac-golden"
PARTIAL = FIXTURES / "partial-aac-golden"

M4A_CONTAINER = "mov,mp4,m4a,3gp,3g2,mj2"


def _stub_m4a_probe(durations: dict[str, int] | None = None, **overrides: Any) -> Any:
    """Audio probe stub shaped for M4A chapters (container included)."""

    def _probe(path: Path) -> AudioProbe:
        values: dict[str, Any] = {
            "codec": "aac",
            "channels": 1,
            "sample_rate": 24000,
            "bit_rate_bps": 64000,
            "bit_rate_from_stream": True,
            "duration_ms": (durations or {}).get(Path(path).name, 4000),
            "cbr_frames": False,
            "container": M4A_CONTAINER,
        }
        values.update(overrides)
        return AudioProbe(**values)

    return _probe


def _read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _copy_fixture(name: str, tmp_path: Path) -> Path:
    root = tmp_path / name
    shutil.copytree(FIXTURES / name, root)
    return root


def _write_manifest(root: Path, manifest: dict[str, Any]) -> None:
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")


def _write_chapter(root: Path, name: str, chapter: dict[str, Any]) -> None:
    (root / "text" / name).write_text(json.dumps(chapter), encoding="utf-8")


def _device_id(source_bytes: bytes) -> tuple[str, str]:
    sha = hashlib.sha256(source_bytes).hexdigest()
    book_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"auloud:device-book:{sha.lower()}"))
    return book_id, sha


# ---------------------------------------------------------------------------
# The goldens themselves
# ---------------------------------------------------------------------------


def test_goldens_present() -> None:
    for rel in (
        "manifest.json",
        "README.md",
        "audio/ch001.m4a",
        "audio/ch002.m4a",
        "text/ch001.json",
        "text/ch002.json",
        "source/book.epub",
    ):
        assert (RENDERED / rel).is_file(), f"golden file missing: {rel}"
    for rel in (
        "manifest.json",
        "README.md",
        "audio/ch001.m4a",
        "text/ch001.json",
        "text/ch002.json",
        "source/book.epub",
    ):
        assert (PARTIAL / rel).is_file(), f"golden file missing: {rel}"
    assert not (PARTIAL / "audio" / "ch002.m4a").exists()
    for audio in (
        RENDERED / "audio" / "ch001.m4a",
        RENDERED / "audio" / "ch002.m4a",
        PARTIAL / "audio" / "ch001.m4a",
    ):
        assert audio.stat().st_size > 1000, f"fixture audio is a placeholder: {audio}"


def test_rendered_golden_pins() -> None:
    manifest = _read_json(RENDERED / "manifest.json")
    assert manifest["spec_version"] == "2.0"
    assert manifest["render_state"] == "complete"
    assert manifest["audio"]["format"] == "m4a"
    assert manifest["gain_db"] == {"narrator": -1.5, "dialogue": 0.5}
    assert manifest["encoder_offset_ms"] == 0
    assert set(manifest["voices"]) >= {"narrator", "dialogue"}
    assert [c["index"] for c in manifest["chapters"]] == [1, 2]
    book_id, sha = _device_id((RENDERED / "source" / "book.epub").read_bytes())
    assert manifest["id"] == book_id
    assert manifest["source"]["sha256"] == sha
    total = 0
    for entry, duration in zip(manifest["chapters"], (4000, 3000), strict=True):
        assert entry["audio"].endswith(".m4a")
        assert entry["duration_ms"] == duration
        fingerprint = entry["render_fingerprint"]
        assert fingerprint["engine"] == "system"
        assert set(fingerprint["voices"]) == {"narrator", "dialogue"}
        assert set(fingerprint["speeds"]) == {"narrator", "dialogue"}
        assert fingerprint["engine_versions"], "one version string per engine used"
        chapter = _read_json(RENDERED / entry["text"])
        assert chapter["spec_version"] == "2.0"
        assert chapter["duration_ms"] == duration
        sentences = [s for b in chapter["blocks"] for s in b.get("sentences", [])]
        assert [s["sid"] for s in sentences] == list(range(1, len(sentences) + 1))
        assert all(s["speaker"] in ("narrator", "dialogue") for s in sentences)
        assert sentences[0]["start_ms"] == 0
        assert all(s["end_ms"] is not None and s["end_ms"] <= duration for s in sentences)
        total += len(sentences)
    assert total == 7


def test_partial_golden_pins() -> None:
    manifest = _read_json(PARTIAL / "manifest.json")
    assert manifest["spec_version"] == "2.0"
    assert manifest["render_state"] == "partial"
    assert manifest["audio"]["format"] == "m4a"
    assert manifest["gain_db"] == {"narrator": -1.0, "dialogue": 0.0}
    assert manifest["encoder_offset_ms"] == 0
    book_id, sha = _device_id((PARTIAL / "source" / "book.epub").read_bytes())
    assert manifest["id"] == book_id
    assert manifest["source"]["sha256"] == sha
    rendered, unrendered = manifest["chapters"]
    assert rendered["audio"] == "audio/ch001.m4a"
    assert rendered["duration_ms"] == 3000
    assert rendered["render_fingerprint"]["engine"] == "system"
    assert "audio" not in unrendered and "duration_ms" not in unrendered
    assert "render_fingerprint" not in unrendered
    timed = _read_json(PARTIAL / rendered["text"])
    assert timed["duration_ms"] == 3000
    assert all("start_ms" in s and "end_ms" in s for b in timed["blocks"] for s in b["sentences"])
    untimed = _read_json(PARTIAL / unrendered["text"])
    assert "duration_ms" not in untimed
    assert all(
        "start_ms" not in s and "end_ms" not in s for b in untimed["blocks"] for s in b["sentences"]
    )


def test_goldens_validate_with_real_probe() -> None:
    if shutil.which("ffprobe") is None:
        raise AssertionError(
            "ffprobe not on PATH (winget install ffmpeg): the golden "
            "contract test must run the real probe, never skip it"
        )
    for golden in (RENDERED, PARTIAL):
        result = validate_bundle(golden)
        assert result.ok, (golden.name, result.errors)


def test_goldens_validate_via_cli() -> None:
    from typer.testing import CliRunner

    from cli import app

    for golden in (RENDERED, PARTIAL):
        result = CliRunner().invoke(app, ["validate", str(golden)])
        assert result.exit_code == 0, (golden.name, result.output)
        assert "valid:" in result.output


def test_inspect_accepts_device_bundles() -> None:
    from bundle.inspect import format_inspect, inspect_bundle

    rendered = inspect_bundle(RENDERED)
    assert rendered.render_state == "complete"
    assert rendered.audio_format == "m4a"
    text = format_inspect(rendered)
    assert "render: complete (m4a), 2 of 2 chapters rendered" in text
    partial = inspect_bundle(PARTIAL)
    assert partial.render_state == "partial"
    assert "render: partial (m4a), 1 of 2 chapters rendered" in format_inspect(partial)


def test_inspect_via_cli() -> None:
    from typer.testing import CliRunner

    from cli import app

    result = CliRunner().invoke(app, ["inspect", str(PARTIAL)])
    assert result.exit_code == 0, result.output
    assert "render: partial (m4a)" in result.output


# ---------------------------------------------------------------------------
# Invalid combinations: every error names the file and the rule
# ---------------------------------------------------------------------------


def test_bad_audio_extension_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["chapters"][0]["audio"] = "audio/ch001.ogg"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and ".mp3 or .m4a" in joined


def test_1x_with_m4a_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["spec_version"] = "1.1"
    del manifest["render_state"]
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "m4a audio needs spec 2.0" in joined


def test_unknown_manifest_format_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["audio"]["format"] = "opus"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "opus" in joined


def test_manifest_format_matching_no_chapter_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["audio"]["format"] = "mp3"
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe())
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "matches no rendered chapter" in joined


def test_m4a_with_mp3_codec_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    result = validate_bundle(root, probe=_stub_m4a_probe(None, codec="mp3"))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "audio/ch001.m4a" in joined and "expected 'aac' for .m4a chapters" in joined


def test_m4a_with_wrong_container_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    result = validate_bundle(root, probe=_stub_m4a_probe(None, container="mp3"))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "audio/ch001.m4a" in joined and "is not mp4/m4a" in joined


def test_m4a_bitrate_outside_band_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    result = validate_bundle(root, probe=_stub_m4a_probe(None, bit_rate_bps=32000))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "audio/ch001.m4a" in joined and "constrained average" in joined


def test_m4a_without_cbr_frames_accepted(tmp_path: Path) -> None:
    """AAC has no MP3-style CBR frames: varying sizes must not fail."""
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    assert result.ok, result.errors


def test_m4a_duration_drift_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 5000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "audio/ch001.m4a" in joined and "differs from manifest" in joined


def test_real_mp3_bytes_as_m4a_rejected(tmp_path: Path) -> None:
    """Real bytes, wrong extension: the codec/container checks fire on the
    real ffprobe path, not just on stubs."""
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        raise AssertionError("ffmpeg/ffprobe not on PATH (winget install ffmpeg)")
    root = _copy_fixture("partial-aac-golden", tmp_path)
    mp3_path = root / "audio" / "tone.mp3"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=3:sample_rate=24000",
            "-ac",
            "1",
            "-ar",
            "24000",
            "-c:a",
            "libmp3lame",
            "-b:a",
            "64k",
            str(mp3_path),
        ],
        check=True,
        timeout=120,
    )
    disguised = root / "audio" / "ch001.m4a"
    disguised.write_bytes(mp3_path.read_bytes())
    result = validate_bundle(root)
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "audio/ch001.m4a" in joined and ("expected 'aac'" in joined or "mp4/m4a" in joined)


def test_mixed_mp3_and_m4a_validates_with_real_probe(tmp_path: Path) -> None:
    """Mixed books are legal: one MP3 chapter plus one M4A chapter."""
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        raise AssertionError("ffmpeg/ffprobe not on PATH (winget install ffmpeg)")
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    mp3_path = root / "audio" / "ch002.mp3"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=520:duration=3:sample_rate=24000",
            "-ac",
            "1",
            "-ar",
            "24000",
            "-c:a",
            "libmp3lame",
            "-b:a",
            "64k",
            str(mp3_path),
        ],
        check=True,
        timeout=120,
    )
    (root / "audio" / "ch002.m4a").unlink()
    manifest = _read_json(root / "manifest.json")
    manifest["chapters"][1]["audio"] = "audio/ch002.mp3"
    _write_manifest(root, manifest)
    result = validate_bundle(root)
    assert result.ok, result.errors


def test_gain_unknown_role_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["gain_db"] = {"narrator": -1.5, "Ana": 0.5}
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and 'unknown role "Ana"' in joined


def test_gain_empty_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["gain_db"] = {}
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "gain_db present but empty" in joined


def test_gain_nonfinite_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["gain_db"] = {"narrator": float("nan"), "dialogue": 0.0}
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "gain_db.narrator" in joined


def test_device_fields_in_1x_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["spec_version"] = "1.1"
    del manifest["render_state"]
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "gain_db is 2.0-only" in joined
    assert "encoder_offset_ms is 2.0-only" in joined
    assert "render_fingerprint" in joined and "2.0-only" in joined


def test_offset_wrong_type_rejected(tmp_path: Path) -> None:
    for pos, bad in enumerate(("12", 1.5, True)):
        root = tmp_path / f"case-{pos}"
        shutil.copytree(RENDERED, root)
        manifest = _read_json(root / "manifest.json")
        manifest["encoder_offset_ms"] = bad
        (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        result = validate_bundle(root, probe=_stub_m4a_probe())
        joined = "\n".join(result.errors)
        assert not result.ok, bad
        assert "manifest.json" in joined and "encoder_offset_ms" in joined, (bad, joined)


def test_fingerprint_on_unrendered_chapter_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("partial-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["chapters"][1]["render_fingerprint"] = manifest["chapters"][0]["render_fingerprint"]
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "without duration_ms" in joined


def test_fingerprint_missing_role_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    del manifest["chapters"][0]["render_fingerprint"]["voices"]["dialogue"]
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "dialogue" in joined


def test_fingerprint_bad_speed_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["chapters"][0]["render_fingerprint"]["speeds"]["narrator"] = 0
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "speed" in joined


def test_fingerprint_empty_versions_rejected(tmp_path: Path) -> None:
    root = _copy_fixture("rendered-aac-golden", tmp_path)
    manifest = _read_json(root / "manifest.json")
    manifest["chapters"][0]["render_fingerprint"]["engine_versions"] = {}
    _write_manifest(root, manifest)
    result = validate_bundle(root, probe=_stub_m4a_probe({"ch001.m4a": 4000, "ch002.m4a": 3000}))
    joined = "\n".join(result.errors)
    assert not result.ok
    assert "manifest.json" in joined and "engine_versions" in joined


# ---------------------------------------------------------------------------
# Export freshness (mirrors test_export_ingest.py)
# ---------------------------------------------------------------------------


def _generated_rel_paths(root: Path) -> list[str]:
    return sorted(
        str(p.relative_to(root)).replace("\\", "/")
        for p in list((root / "rendered-aac-golden").rglob("*"))
        + list((root / "partial-aac-golden").rglob("*"))
        if p.is_file()
    )


def test_export_twice_gives_identical_files(tmp_path: Path) -> None:
    from export_render import export_all

    first = tmp_path / "a" / "fixtures"
    second = tmp_path / "b" / "fixtures"
    first.mkdir(parents=True)
    second.mkdir(parents=True)
    export_all(first)
    export_all(second)
    assert _generated_rel_paths(first) == _generated_rel_paths(second)
    for rel in _generated_rel_paths(first):
        assert (first / rel).read_bytes() == (second / rel).read_bytes(), rel


def test_committed_files_match_fresh_export(tmp_path: Path) -> None:
    from export_render import export_all

    fresh = tmp_path / "fixtures"
    fresh.mkdir(parents=True)
    export_all(fresh)
    for rel in _generated_rel_paths(fresh):
        assert (FIXTURES / rel).is_file(), f"committed file missing: {rel}"
        assert (FIXTURES / rel).read_bytes() == (fresh / rel).read_bytes(), (
            f"stale committed file (re-run export-render-fixtures): {rel}"
        )


def test_cli_export_writes_deterministic_files(tmp_path: Path) -> None:
    from typer.testing import CliRunner

    from cli import app

    first = tmp_path / "a" / "fixtures"
    first.mkdir(parents=True)
    result = CliRunner().invoke(app, ["export-render-fixtures", "--fixtures-dir", str(first)])
    assert result.exit_code == 0, result.output
    assert "rendered-aac-golden" in result.output
