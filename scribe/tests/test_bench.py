# Auloud Scribe — tests for the `scribe bench` CLI command.

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

import pytest
from typer.testing import CliRunner

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cli

RUNNER = CliRunner()
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "bench_chapter.epub"
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


def _has_ffmpeg() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def _has_models() -> bool:
    return (MODELS_DIR / "kokoro-v1.0.onnx").is_file() and (MODELS_DIR / "voices-v1.0.bin").is_file()


@pytest.mark.skipif(not _has_ffmpeg() or not _has_models(), reason="missing ffmpeg or models")
def test_bench_runs_and_reports_metrics(tmp_path: Path) -> None:
    out_dir = tmp_path / "bench-out"
    report = tmp_path / "bench_report.json"
    result = RUNNER.invoke(
        cli.app,
        [
            "bench",
            "--devices", "cpu",
            "--fixture", str(FIXTURE),
            "--out-dir", str(out_dir),
            "--report", str(report),
            "--work-root", str(tmp_path / ".scribe"),
        ],
    )
    assert result.exit_code == 0, result.output
    assert "RTF" in result.output
    assert "GPU %" in result.output
    assert "peak RSS" in result.output