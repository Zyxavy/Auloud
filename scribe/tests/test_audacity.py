import json
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf
from typer.testing import CliRunner

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cli
from export import audacity
from export.audacity import export_audacity

RUNNER = CliRunner()
GOLDEN = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "scribe-golden"
SR = 24000


def _fake_ffmpeg(monkeypatch, seconds_by_input: dict[str, float]) -> list[list[str]]:
    """Stand in for ffmpeg: write a silent WAV of the given length, record every ffmpeg call."""
    real_run = subprocess.run
    calls: list[list[str]] = []

    def fake_run(cmd, *args, **kwargs):
        if not cmd or "ffmpeg" not in str(cmd[0]).lower():
            return real_run(cmd, *args, **kwargs)
        calls.append(list(cmd))
        src = Path(cmd[cmd.index("-i") + 1]).name
        frames = int(seconds_by_input.get(src, 1.0) * SR)
        sf.write(cmd[-1], np.zeros(frames, dtype=np.float32), SR, subtype="PCM_16")
        return subprocess.CompletedProcess(cmd, 0, b"", b"")

    monkeypatch.setattr(audacity.subprocess, "run", fake_run)
    return calls


def _label_rows(zip_path: Path) -> list[list[str]]:
    with zipfile.ZipFile(zip_path) as zf:
        text = zf.read("labels.txt").decode("utf-8")
    return [line.split("\t") for line in text.splitlines()]


def test_export_audacity_creates_zip(tmp_path: Path) -> None:
    out = tmp_path / "out.zip"
    result = export_audacity(GOLDEN, output=out)
    assert result.path == out
    assert out.is_file()
    with zipfile.ZipFile(out) as zf:
        names = zf.namelist()
    assert any(n.endswith(".wav") for n in names)
    assert "project.lof" in names
    assert "labels.txt" in names


def test_cli_export_audacity(tmp_path: Path) -> None:
    out = tmp_path / "cli.zip"
    result = RUNNER.invoke(cli.app, ["export", "audacity", str(GOLDEN), "--output", str(out)])
    assert result.exit_code == 0, result.output
    assert out.is_file()


def test_ffmpeg_runs_once_per_chapter(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls = _fake_ffmpeg(monkeypatch, {})
    export_audacity(GOLDEN, output=tmp_path / "out.zip")
    # golden has 2 chapters and 19 sentences each; per-sentence ffmpeg cuts would be ~40+ calls
    assert len(calls) == 2
    assert [Path(c[c.index("-i") + 1]).name for c in calls] == ["ch001.mp3", "ch002.mp3"]


def test_labels_carry_running_chapter_offset(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _fake_ffmpeg(monkeypatch, {"ch001.mp3": 2.0, "ch002.mp3": 3.0})
    out = tmp_path / "out.zip"
    export_audacity(GOLDEN, output=out)
    rows = _label_rows(out)
    # chapter 1 has 19 sentences; chapter 2 starts at the decoded length of chapter 1 (2.0 s)
    assert float(rows[0][0]) == 0.0
    assert float(rows[19][0]) == pytest.approx(2.0)          # chapter 2 sid 1 at 0 ms
    assert float(rows[20][0]) == pytest.approx(2.9)          # chapter 2 sid 2 at 900 ms
    assert float(rows[20][1]) == pytest.approx(3.0)          # 1000 ms end plus the 2.0 s offset
    assert rows[20][2].startswith("narrator: A brindled cat")


def test_speaker_names_are_indexed_in_files_and_escaped(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    name = 'A: B"C/D'
    bundle = tmp_path / "bundle"
    (bundle / "text").mkdir(parents=True)
    (bundle / "audio").mkdir()
    manifest = json.loads((GOLDEN / "manifest.json").read_text(encoding="utf-8"))
    manifest["chapters"] = manifest["chapters"][:1]
    (bundle / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    text = json.loads((GOLDEN / "text" / "ch001.json").read_text(encoding="utf-8"))
    for block in text["blocks"]:
        for sentence in block["sentences"]:
            sentence["speaker"] = name
    (bundle / "text" / "ch001.json").write_text(json.dumps(text), encoding="utf-8")
    (bundle / "audio" / "ch001.mp3").write_bytes(b"")
    _fake_ffmpeg(monkeypatch, {})

    out = tmp_path / "out.zip"
    export_audacity(bundle, output=out)

    with zipfile.ZipFile(out) as zf:
        names = sorted(zf.namelist())
        lof = zf.read("project.lof").decode("utf-8")
        labels = zf.read("labels.txt").decode("utf-8")
    assert names == ["labels.txt", "project.lof", "spk1.wav"]
    tracks = list(ET.fromstring(lof).iter("track"))
    assert [t.get("name") for t in tracks] == [name]
    assert [t.get("file") for t in tracks] == ["spk1.wav"]
    assert f"{name}: Chapter One" in labels
