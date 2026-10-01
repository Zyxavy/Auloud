# Auloud Scribe — turns ebooks into multi-voice audiobooks (PC tool).
# Copyright (C) 2026 Zyxavy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""SW2: validator tests for ``bundle/validate.py`` (spec section 7).

Strategy: the shared ``spec/fixtures/`` bundles cover parse-level cases
(valid manifest + chapters, bad JSON, missing field, missing MP3). The
fixtures' MP3s are 0-byte placeholders, so audio probing is injected via
the ``probe`` seam there; dedicated tests below exercise the real ffprobe
path on MP3s generated with ffmpeg, plus stubbed-subprocess tests for the
ffprobe output-parser branches (no real ffmpeg needed).

An explicit environment test fails when ffmpeg/ffprobe are absent, so the
per-test skips on the real-probe cases can never silently hide lost
coverage.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest

from bundle.validate import (
    AudioProbe,
    AudioProbeError,
    ValidationResult,
    probe_audio_ffprobe,
    validate_bundle,
)

FIXTURES = Path(__file__).resolve().parent.parent.parent / "spec" / "fixtures"
CHAPTER_MS = 3000


def _sentence(
    sid: int,
    start_ms: int,
    end_ms: int,
    speaker: str = "narrator",
    text: str = "Hello world.",
    spans: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    out: dict[str, Any] = {
        "sid": sid,
        "speaker": speaker,
        "start_ms": start_ms,
        "end_ms": end_ms,
        "text": text,
    }
    if spans is not None:
        out["spans"] = spans
    return out


def _chapter_dict(
    sentences: list[dict[str, Any]], duration_ms: int = CHAPTER_MS, chapter: int = 1
) -> dict[str, Any]:
    return {
        "spec_version": "1.0",
        "chapter": chapter,
        "title": "Chapter One",
        "duration_ms": duration_ms,
        "blocks": [
            {"id": 1, "type": "heading", "level": 1, "text": "Chapter One"},
            {"id": 2, "type": "para", "sentences": sentences},
        ],
    }


def _manifest_dict(
    duration_ms: int = CHAPTER_MS, source_sha: str = "", n_chapters: int = 1
) -> dict[str, Any]:
    chapters = [
        {
            "index": i,
            "title": f"Chapter {i}",
            "audio": f"audio/ch{i:03d}.mp3",
            "text": f"text/ch{i:03d}.json",
            "duration_ms": duration_ms,
        }
        for i in range(1, n_chapters + 1)
    ]
    return {
        "spec_version": "1.0",
        "id": "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
        "title": "Example Novel",
        "type": "epub",
        "source": {"file": "source/book.epub", "sha256": source_sha},
        "audio": {
            "format": "mp3",
            "channels": 1,
            "sample_rate": 24000,
            "bitrate_kbps": 64,
            "cbr": True,
        },
        "voices": {
            "narrator": {"engine": "kokoro", "voice": "af_sarah", "speed": 1.0, "pitch": 1.0}
        },
        "chapters": chapters,
    }


def write_ok_bundle(
    root: Path,
    sentences: list[dict[str, Any]] | None = None,
    duration_ms: int = CHAPTER_MS,
    source_bytes: bytes = b"fake-epub",
) -> Path:
    """Write a minimal consistent one-chapter bundle (empty MP3s: use a stub probe)."""
    if sentences is None:
        sentences = [_sentence(1, 0, 1500), _sentence(2, 1500, 2800)]
    (root / "audio").mkdir(parents=True, exist_ok=True)
    (root / "text").mkdir(parents=True, exist_ok=True)
    (root / "source").mkdir(parents=True, exist_ok=True)
    (root / "source" / "book.epub").write_bytes(source_bytes)
    manifest = _manifest_dict(duration_ms, hashlib.sha256(source_bytes).hexdigest())
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    (root / "text" / "ch001.json").write_text(
        json.dumps(_chapter_dict(sentences, duration_ms)), encoding="utf-8"
    )
    (root / "audio" / "ch001.mp3").write_bytes(b"")
    return root


def make_probe(
    durations: dict[str, int] | None = None,
    *,
    channels: int = 1,
    sample_rate: int = 24000,
    bit_rate_bps: int | None = 64000,
    from_stream: bool = True,
    cbr: bool = True,
    codec: str = "mp3",
) -> Any:
    """Stub audio probe: per-file durations, fixed stream properties."""

    def _probe(path: Path) -> AudioProbe:
        name = Path(path).name
        duration_ms = (durations or {}).get(name, CHAPTER_MS)
        return AudioProbe(
            codec=codec,
            channels=channels,
            sample_rate=sample_rate,
            bit_rate_bps=bit_rate_bps,
            bit_rate_from_stream=from_stream,
            duration_ms=duration_ms,
            cbr_frames=cbr,
        )

    return _probe


def _rewrite_manifest(root: Path, mutate: Any) -> dict[str, Any]:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    mutate(manifest)
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return manifest


def _rewrite_chapter(root: Path, name: str, mutate: Any) -> dict[str, Any]:
    path = root / "text" / name
    chapter = json.loads(path.read_text(encoding="utf-8"))
    mutate(chapter)
    path.write_text(json.dumps(chapter), encoding="utf-8")
    return chapter


# ---------------------------------------------------------------------------
# Shared fixtures (same bundles the Player tests use)
# ---------------------------------------------------------------------------


def test_shared_valid_bundle_passes(tmp_path: Path) -> None:
    root = tmp_path / "valid-bundle"
    shutil.copytree(FIXTURES / "valid-bundle", root)
    (root / "source").mkdir()
    # The fixture manifest pins the sha256 of an empty file.
    (root / "source" / "book.epub").write_bytes(b"")
    probe = make_probe({"ch001.mp3": 1832400, "ch002.mp3": 1640100})
    result = validate_bundle(root, probe=probe)
    assert result.ok, result.errors
    assert isinstance(result, ValidationResult)


def test_shared_bad_json_fails_naming_manifest(tmp_path: Path) -> None:
    root = tmp_path / "bad-json"
    shutil.copytree(FIXTURES / "bad-json", root)
    result = validate_bundle(root, probe=make_probe())
    assert not result.ok
    joined = "\n".join(result.errors)
    assert "manifest.json" in joined
    assert "invalid JSON" in joined


def test_shared_missing_field_fails_naming_title(tmp_path: Path) -> None:
    root = tmp_path / "missing-field"
    shutil.copytree(FIXTURES / "missing-field", root)
    result = validate_bundle(root, probe=make_probe())
    assert not result.ok
    joined = "\n".join(result.errors)
    assert "manifest.json" in joined
    assert "title" in joined


def test_shared_missing_mp3_fails_naming_audio_file(tmp_path: Path) -> None:
    root = tmp_path / "missing-mp3"
    shutil.copytree(FIXTURES / "missing-mp3", root)
    result = validate_bundle(root, probe=make_probe())
    assert not result.ok
    joined = "\n".join(result.errors)
    assert "manifest.json" in joined
    assert "audio/ch002.mp3" in joined


# ---------------------------------------------------------------------------
# Manifest-level rules
# ---------------------------------------------------------------------------


def test_missing_manifest_file(tmp_path: Path) -> None:
    result = validate_bundle(tmp_path, probe=make_probe())
    assert not result.ok
    assert any("manifest.json" in e and "missing" in e for e in result.errors)


def test_empty_chapters_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_manifest(root, lambda m: m.update({"chapters": []}))
    result = validate_bundle(root, probe=make_probe())
    assert any("no chapters" in e for e in result.errors)


def test_blank_title_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_manifest(root, lambda m: m.update({"title": "  "}))
    result = validate_bundle(root, probe=make_probe())
    assert any("title" in e for e in result.errors)


def test_non_increasing_chapter_indices_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_manifest(
        root,
        lambda m: m.update(
            {
                "chapters": [
                    {
                        "index": 1,
                        "title": "A",
                        "audio": "audio/ch001.mp3",
                        "text": "text/ch001.json",
                        "duration_ms": CHAPTER_MS,
                    },
                    {
                        "index": 1,
                        "title": "B",
                        "audio": "audio/ch001.mp3",
                        "text": "text/ch001.json",
                        "duration_ms": CHAPTER_MS,
                    },
                ]
            }
        ),
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("strictly increasing" in e for e in result.errors)


def test_non_spec_audio_declarations_rejected(tmp_path: Path) -> None:
    cases = [
        ({"channels": 2}, "channels"),
        ({"sample_rate": 44100}, "sample_rate"),
        ({"bitrate_kbps": 128}, "bitrate_kbps"),
        ({"cbr": False}, "cbr"),
        ({"format": "wav"}, "format"),
    ]
    for patch, needle in cases:
        root = write_ok_bundle(tmp_path / f"case_{needle}")
        _rewrite_manifest(root, lambda m, p=patch: m["audio"].update(p))
        result = validate_bundle(root, probe=make_probe())
        assert any(
            "manifest.json" in e and needle in e for e in result.errors
        ), (needle, result.errors)


def test_missing_audio_and_text_files_named(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    (root / "audio" / "ch001.mp3").unlink()
    (root / "text" / "ch001.json").unlink()
    result = validate_bundle(root, probe=make_probe())
    joined = "\n".join(result.errors)
    assert "audio file missing audio/ch001.mp3" in joined
    assert "text file missing text/ch001.json" in joined


def test_blank_audio_and_text_paths_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_manifest(root, lambda m: m["chapters"][0].update({"audio": "", "text": ""}))
    result = validate_bundle(root, probe=make_probe())
    joined = "\n".join(result.errors)
    assert "missing required field 'audio' (blank)" in joined
    assert "missing required field 'text' (blank)" in joined


def test_cover_listed_but_missing(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_manifest(root, lambda m: m.update({"cover": "cover.jpg"}))
    result = validate_bundle(root, probe=make_probe())
    assert any("cover.jpg" in e for e in result.errors)


# ---------------------------------------------------------------------------
# Source sha256
# ---------------------------------------------------------------------------


def test_source_sha_mismatch_names_file(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    (root / "source" / "book.epub").write_bytes(b"tampered")
    result = validate_bundle(root, probe=make_probe())
    assert any("source/book.epub" in e and "sha256 mismatch" in e for e in result.errors)


def test_source_file_missing(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    (root / "source" / "book.epub").unlink()
    result = validate_bundle(root, probe=make_probe())
    assert any("source/book.epub" in e and "missing" in e for e in result.errors)


# ---------------------------------------------------------------------------
# Chapter text rules: speakers, sids, timings, spans
# ---------------------------------------------------------------------------


def test_unknown_speaker_names_sentence(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, 0, 1500, speaker="Ghost")]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any(
        "ch001.json" in e and "unknown speaker" in e and "Ghost" in e
        for e in result.errors
    )


def test_sid_gap_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, 0, 1500), _sentence(3, 1500, 2800)]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("ch001.json" in e and "expected 2, got 3" in e for e in result.errors)


def test_sid_must_start_at_1(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(2, 0, 1500), _sentence(3, 1500, 2800)]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("expected 1, got 2" in e for e in result.errors)


def test_first_sentence_must_start_at_0(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, 100, 1500), _sentence(2, 1500, 2800)]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("first sentence start_ms is 100" in e for e in result.errors)


def test_start_after_end_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, 1500, 1500), _sentence(2, 1500, 2800)]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("start_ms 1500 >= end_ms 1500" in e for e in result.errors)


def test_negative_start_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, -5, 1500), _sentence(2, 1500, 2800)]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("negative start_ms" in e for e in result.errors)


def test_end_beyond_duration_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, 0, 1500), _sentence(2, 1500, 9999)]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("exceeds duration_ms" in e for e in result.errors)


def test_overlap_names_both_sentences(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, 0, 1500), _sentence(2, 1400, 2800)]
    )
    result = validate_bundle(root, probe=make_probe())
    assert any(
        "ch001.json" in e and "sentence 2 overlaps sentence 1" in e
        for e in result.errors
    )


def test_gap_between_sentences_allowed(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path, sentences=[_sentence(1, 0, 1000), _sentence(2, 2000, 2800)]
    )
    assert validate_bundle(root, probe=make_probe()).ok


def test_manifest_chapter_duration_drift_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_chapter(root, "ch001.json", lambda c: c.update({"duration_ms": 4000}))
    result = validate_bundle(root, probe=make_probe())
    assert any("differs from manifest" in e for e in result.errors)


def test_duration_tolerance_is_inclusive(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_chapter(root, "ch001.json", lambda c: c.update({"duration_ms": CHAPTER_MS + 50}))
    assert validate_bundle(root, probe=make_probe()).ok, "drift of exactly 50 ms must pass"
    _rewrite_chapter(root, "ch001.json", lambda c: c.update({"duration_ms": CHAPTER_MS + 51}))
    result = validate_bundle(root, probe=make_probe())
    assert any("differs from manifest" in e for e in result.errors)


def test_non_integer_timing_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_chapter(
        root, "ch001.json", lambda c: c["blocks"][1]["sentences"][0].update({"start_ms": 12.5})
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("ch001.json" in e and "integer" in e for e in result.errors)


def test_missing_sentence_field_named(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)

    def _drop(chapter: dict[str, Any]) -> None:
        del chapter["blocks"][1]["sentences"][0]["speaker"]

    _rewrite_chapter(root, "ch001.json", _drop)
    result = validate_bundle(root, probe=make_probe())
    assert any("ch001.json" in e and "speaker" in e for e in result.errors)


def test_invalid_chapter_json(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    (root / "text" / "ch001.json").write_text("{broken", encoding="utf-8")
    result = validate_bundle(root, probe=make_probe())
    assert any("ch001.json" in e and "invalid JSON" in e for e in result.errors)


def test_non_utf8_chapter_file(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    (root / "text" / "ch001.json").write_bytes(b"\xff\xfe not utf-8 \x80")
    result = validate_bundle(root, probe=make_probe())
    assert any("ch001.json" in e and "UTF-8" in e for e in result.errors)


def test_non_nfc_chapter_file_rejected(tmp_path: Path) -> None:
    # "cafe" + COMBINING ACUTE ACCENT: NFD form, must be flagged.
    root = write_ok_bundle(tmp_path, sentences=[_sentence(1, 0, 1500, text="café")])
    result = validate_bundle(root, probe=make_probe())
    assert any("ch001.json" in e and "NFC" in e for e in result.errors)


def test_non_nfc_raw_bytes_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    chapter = json.loads((root / "text" / "ch001.json").read_text(encoding="utf-8"))
    chapter["blocks"][1]["sentences"][0]["text"] = "café"
    (root / "text" / "ch001.json").write_bytes(
        json.dumps(chapter, ensure_ascii=False).encode("utf-8")
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("ch001.json" in e and "NFC" in e for e in result.errors)


def test_chapter_number_mismatch(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_chapter(root, "ch001.json", lambda c: c.update({"chapter": 2}))
    result = validate_bundle(root, probe=make_probe())
    assert any("manifest lists index 1" in e for e in result.errors)


def test_bad_span_style_and_range(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path,
        sentences=[
            _sentence(
                1,
                0,
                1500,
                text="Hi.",
                spans=[{"start": 0, "end": 2, "style": "underline"}],
            ),
            _sentence(
                2,
                1500,
                2800,
                text="Yo.",
                spans=[{"start": 0, "end": 99, "style": "bold"}],
            ),
        ],
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("unknown style 'underline'" in e for e in result.errors)
    assert any("exceeds text length" in e for e in result.errors)


def test_span_start_after_end_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(
        tmp_path,
        sentences=[
            _sentence(
                1, 0, 1500, text="Hello.", spans=[{"start": 4, "end": 2, "style": "bold"}]
            ),
            _sentence(2, 1500, 2800),
        ],
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("start 4 > end 2" in e for e in result.errors)


def test_heading_level_out_of_range(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_chapter(root, "ch001.json", lambda c: c["blocks"][0].update({"level": 5}))
    result = validate_bundle(root, probe=make_probe())
    assert any("level 5" in e and "need 1-3" in e for e in result.errors)


def test_unknown_block_type_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    _rewrite_chapter(
        root, "ch001.json", lambda c: c["blocks"].append({"id": 9, "type": "sidebar"})
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("invalid block type 'sidebar'" in e for e in result.errors)


def test_pdf_pages_form_valid_and_ordered(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    (root / "text" / "ch001.json").write_text(
        json.dumps(
            {
                "spec_version": "1.0",
                "chapter": 1,
                "title": "Pages 1-2",
                "duration_ms": CHAPTER_MS,
                "pages": [{"page": 1, "start_ms": 0}, {"page": 2, "start_ms": 1500}],
            }
        ),
        encoding="utf-8",
    )
    assert validate_bundle(root, probe=make_probe()).ok
    (root / "text" / "ch001.json").write_text(
        json.dumps(
            {
                "spec_version": "1.0",
                "chapter": 1,
                "title": "Pages 1-2",
                "duration_ms": CHAPTER_MS,
                "pages": [{"page": 1, "start_ms": 1500}, {"page": 2, "start_ms": 0}],
            }
        ),
        encoding="utf-8",
    )
    result = validate_bundle(root, probe=make_probe())
    assert any("out of order" in e or "expected 0" in e for e in result.errors)


def test_sids_across_blocks_are_chapter_wide(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)

    def _split(chapter: dict[str, Any]) -> None:
        first = chapter["blocks"][1]["sentences"][0]
        second = chapter["blocks"][1]["sentences"][1]
        chapter["blocks"][1]["sentences"] = [first]
        chapter["blocks"].append({"id": 3, "type": "para", "sentences": [second]})

    _rewrite_chapter(root, "ch001.json", _split)
    assert validate_bundle(root, probe=make_probe()).ok


# ---------------------------------------------------------------------------
# Audio rules (stubbed probe)
# ---------------------------------------------------------------------------


def test_audio_property_mismatches_named(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    for kwargs, needle in [
        ({"channels": 2}, "channels 2"),
        ({"sample_rate": 22050}, "sample_rate 22050"),
        ({"bit_rate_bps": 128000}, "bitrate"),
        ({"codec": "aac"}, "codec"),
    ]:
        result = validate_bundle(root, probe=make_probe(None, **kwargs))
        assert any(
            "audio/ch001.mp3" in e and needle in e for e in result.errors
        ), (kwargs, result.errors)


def test_non_cbr_frames_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    result = validate_bundle(root, probe=make_probe(cbr=False))
    assert any("CBR" in e for e in result.errors)


def test_mp3_duration_drift_rejected(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    result = validate_bundle(root, probe=make_probe({"ch001.mp3": CHAPTER_MS + 1000}))
    assert any("MP3 duration" in e for e in result.errors)


def test_mp3_duration_boundary_passes(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    result = validate_bundle(root, probe=make_probe({"ch001.mp3": CHAPTER_MS + 50}))
    assert result.ok, result.errors


def test_probe_failure_is_an_error_not_a_crash(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)

    def _boom(path: Path) -> AudioProbe:
        raise AudioProbeError("ffprobe failed: simulated outage")

    result = validate_bundle(root, probe=_boom)
    assert any("ffprobe failed" in e for e in result.errors)


# ---------------------------------------------------------------------------
# ffprobe output parsing (stubbed subprocess: no real ffmpeg needed)
# ---------------------------------------------------------------------------


def _stub_ffprobe(
    monkeypatch: pytest.MonkeyPatch,
    *,
    info: str,
    packets: str | None = None,
    timeout_on: str | None = None,
) -> list[list[str]]:
    """Fake ffprobe: stub which() and subprocess.run; return captured argv."""
    monkeypatch.setattr(shutil, "which", lambda _name: r"C:\fake\ffprobe.exe")
    calls: list[list[str]] = []

    def _fake_run(cmd: list[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
        calls.append(list(cmd))
        is_packets = "packet=size" in " ".join(cmd)
        if (timeout_on == "packets") == is_packets and timeout_on is not None:
            raise subprocess.TimeoutExpired(cmd, 60)
        if is_packets:
            payload = packets if packets is not None else json.dumps(
                {"packets": [{"size": "192"}] * 8}
            )
        else:
            payload = info
        return subprocess.CompletedProcess(
            args=cmd, returncode=0, stdout=payload, stderr=""
        )

    monkeypatch.setattr(subprocess, "run", _fake_run)
    return calls


def _info_stdout(stream: dict[str, Any], fmt: dict[str, Any]) -> str:
    return json.dumps({"streams": [stream], "format": fmt})


def test_probe_falls_back_to_format_bitrate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    info = _info_stdout(
        {"codec_name": "mp3", "channels": 1, "sample_rate": "24000"},
        {"duration": "3.000000", "bit_rate": "65653"},
    )
    _stub_ffprobe(monkeypatch, info=info)
    probe = probe_audio_ffprobe(Path("audio/ch001.mp3"))
    assert probe.bit_rate_bps == 65653
    assert probe.bit_rate_from_stream is False
    assert probe.duration_ms == 3000


@pytest.mark.parametrize("duration", ["N/A", None])
def test_probe_missing_duration_is_none(
    monkeypatch: pytest.MonkeyPatch, duration: str | None
) -> None:
    fmt: dict[str, Any] = {"bit_rate": "64000"}
    if duration is not None:
        fmt["duration"] = duration
    info = _info_stdout(
        {"codec_name": "mp3", "channels": 1, "sample_rate": "24000", "bit_rate": "64000"},
        fmt,
    )
    _stub_ffprobe(monkeypatch, info=info)
    assert probe_audio_ffprobe(Path("audio/ch001.mp3")).duration_ms is None


def test_probe_missing_channels_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    info = _info_stdout(
        {"codec_name": "mp3", "sample_rate": "24000", "bit_rate": "64000"},
        {"duration": "3.0", "bit_rate": "64000"},
    )
    _stub_ffprobe(monkeypatch, info=info)
    with pytest.raises(AudioProbeError, match="channels"):
        probe_audio_ffprobe(Path("audio/ch001.mp3"))


def test_probe_no_audio_stream_with_rc0_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _stub_ffprobe(monkeypatch, info=json.dumps({"streams": [], "format": {}}))
    with pytest.raises(AudioProbeError, match="no audio stream"):
        probe_audio_ffprobe(Path("audio/ch001.mp3"))


@pytest.mark.parametrize("timeout_on", ["info", "packets"])
def test_probe_timeout_has_install_help(
    monkeypatch: pytest.MonkeyPatch, timeout_on: str
) -> None:
    info = _info_stdout(
        {"codec_name": "mp3", "channels": 1, "sample_rate": "24000", "bit_rate": "64000"},
        {"duration": "3.0", "bit_rate": "64000"},
    )
    _stub_ffprobe(monkeypatch, info=info, timeout_on=timeout_on)
    with pytest.raises(AudioProbeError) as excinfo:
        probe_audio_ffprobe(Path("audio/ch001.mp3"))
    assert "timed out" in str(excinfo.value)
    assert "winget install ffmpeg" in str(excinfo.value)


def test_unknown_mp3_duration_is_an_error(tmp_path: Path) -> None:
    root = write_ok_bundle(tmp_path)
    unknown = AudioProbe(
        codec="mp3",
        channels=1,
        sample_rate=24000,
        bit_rate_bps=64000,
        bit_rate_from_stream=True,
        duration_ms=None,
        cbr_frames=True,
    )
    result = validate_bundle(root, probe=lambda _path: unknown)
    assert any("could not determine MP3 duration" in e for e in result.errors)


def test_garbage_mp3_fails_with_real_ffprobe(tmp_path: Path) -> None:
    if shutil.which("ffprobe") is None:
        pytest.skip("ffprobe not on PATH")
    root = write_ok_bundle(tmp_path)
    (root / "audio" / "ch001.mp3").write_bytes(b"not an mp3 at all")
    result = validate_bundle(root)  # real probe
    assert any("audio/ch001.mp3" in e for e in result.errors)


# ---------------------------------------------------------------------------
# Real ffprobe path on generated MP3s
# ---------------------------------------------------------------------------


def test_ffmpeg_and_ffprobe_are_on_path() -> None:
    """Environment precondition: fail (do not skip) when binaries are absent.

    The real-probe tests below skip individually without ffmpeg/ffprobe;
    this test makes that loss of coverage loud instead of silent.
    """
    missing = [name for name in ("ffmpeg", "ffprobe") if shutil.which(name) is None]
    assert not missing, (
        f"missing on PATH: {', '.join(missing)} — install with "
        "'winget install ffmpeg'; real-probe tests would silently skip without them"
    )


def _encode_mp3(
    out: Path,
    *,
    seconds: int = 3,
    src: str = "anullsrc=r=24000:cl=mono",
    args: list[str] | None = None,
) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        pytest.skip("ffmpeg not on PATH")
    cmd = [
        ffmpeg,
        "-v",
        "error",
        "-y",
        "-f",
        "lavfi",
        "-i",
        src,
        "-t",
        str(seconds),
        "-c:a",
        "libmp3lame",
        *(args or ["-b:a", "64k"]),
        str(out),
    ]
    subprocess.run(cmd, check=True, timeout=120)


def test_real_cbr_mp3_passes(tmp_path: Path) -> None:
    mp3 = tmp_path / "ch001.mp3"
    _encode_mp3(mp3)
    probe = probe_audio_ffprobe(mp3)
    assert probe.codec == "mp3"
    assert probe.channels == 1
    assert probe.sample_rate == 24000
    assert probe.bit_rate_bps == 64000
    assert probe.cbr_frames
    assert probe.duration_ms is not None
    root = tmp_path / "bundle"
    write_ok_bundle(root, duration_ms=probe.duration_ms)
    (root / "audio" / "ch001.mp3").write_bytes(mp3.read_bytes())
    chapter = json.loads((root / "text" / "ch001.json").read_text(encoding="utf-8"))
    chapter["blocks"][1]["sentences"][1]["end_ms"] = probe.duration_ms - 100
    (root / "text" / "ch001.json").write_text(json.dumps(chapter), encoding="utf-8")
    result = validate_bundle(root)  # real ffprobe, no stub
    assert result.ok, result.errors


def test_real_stereo_mp3_fails_channels(tmp_path: Path) -> None:
    mp3 = tmp_path / "ch001.mp3"
    _encode_mp3(mp3, args=["-b:a", "64k", "-ac", "2"])
    root = tmp_path / "bundle"
    write_ok_bundle(root)
    (root / "audio" / "ch001.mp3").write_bytes(mp3.read_bytes())
    result = validate_bundle(root)  # real ffprobe, no stub
    assert any("channels 2" in e for e in result.errors)


def test_real_vbr_mp3_fails_bitrate_and_cbr_frames(tmp_path: Path) -> None:
    mp3 = tmp_path / "ch001.mp3"
    # A tone (not silence) under VBR varies frame sizes, tripping the
    # frame-constancy check as well as the average-bitrate check.
    _encode_mp3(mp3, src="sine=frequency=440:sample_rate=24000", args=["-q:a", "4"])
    assert probe_audio_ffprobe(mp3).cbr_frames is False
    root = tmp_path / "bundle"
    write_ok_bundle(root)
    (root / "audio" / "ch001.mp3").write_bytes(mp3.read_bytes())
    result = validate_bundle(root)  # real ffprobe, no stub
    joined = "\n".join(result.errors)
    assert "average bitrate" in joined, joined
    assert "MP3 frames vary in size" in joined, joined
