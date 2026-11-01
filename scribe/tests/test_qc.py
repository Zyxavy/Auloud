from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from typer.testing import CliRunner

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cli
import qc
from qc import compute_wer, normalize_text

RUNNER = CliRunner()
GOLDEN = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "scribe-golden"


def test_normalize_and_wer():
    assert normalize_text("Hello, World!") == "hello world"
    assert compute_wer("the cat sat", "the cat sat") == 0.0
    assert compute_wer("the cat sat", "the dog sat") == pytest.approx(1 / 3)
    assert compute_wer("", "") == 0.0


def test_run_qc_mocked(tmp_path, monkeypatch):
    # monkeypatch load_model to avoid faster-whisper
    monkeypatch.setattr(qc, "load_model", lambda name="tiny": object())
    # transcribe returns the reference text (perfect) for ch1, empty-ish for ch2
    manifest = json.loads((GOLDEN / "manifest.json").read_text(encoding="utf-8"))
    refs = [c.get("text", "") for c in manifest["chapters"]]

    def fake_transcribe(audio_path, model=None, model_name="tiny"):
        # return reference of first chapter for first call, second for second
        name = Path(audio_path).name
        idx = 0 if "ch001" in name else 1
        return refs[idx]

    monkeypatch.setattr(qc, "transcribe", fake_transcribe)

    # output_path must be explicit: the default writes qc_report.json into the
    # committed fixture dir (pollution).
    result = qc.run_qc(
        GOLDEN, model="tiny", threshold=0.15, output_path=tmp_path / "rep.json"
    )
    assert result.output_path.is_file()
    report = result.report
    assert report["summary"]["chapters"] == 2
    assert report["chapters"][0]["wer"] == 0.0
    assert report["chapters"][0]["flagged"] is False
    # per-sentence present
    assert isinstance(report["chapters"][0]["sentences"], list)


def test_cli_qc(tmp_path, monkeypatch):
    monkeypatch.setattr(qc, "load_model", lambda name="tiny": object())
    manifest = json.loads((GOLDEN / "manifest.json").read_text(encoding="utf-8"))
    refs = [c.get("text", "") for c in manifest["chapters"]]

    def fake_transcribe(audio_path, model=None, model_name="tiny"):
        name = Path(audio_path).name
        idx = 0 if "ch001" in name else 1
        return refs[idx]

    monkeypatch.setattr(qc, "transcribe", fake_transcribe)

    out = tmp_path / "rep.json"
    res = RUNNER.invoke(cli.app, ["qc", str(GOLDEN), "--output", str(out)])
    assert res.exit_code == 0, res.output
    assert out.is_file()


def test_missing_faster_whisper(monkeypatch):
    # ensure load_model raises QCError when faster_whisper missing
    def _raise(name="tiny"):
        raise qc.QCError("missing")

    monkeypatch.setattr(qc, "load_model", _raise)
    # run_qc should propagate
    with pytest.raises(qc.QCError):
        qc.run_qc(GOLDEN, model="tiny")
