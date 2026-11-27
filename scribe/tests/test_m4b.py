# Auloud Scribe - tests for M4B export.

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from typer.testing import CliRunner

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cli
from export.m4b import export_m4b

RUNNER = CliRunner()
GOLDEN = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "scribe-golden"


def _has_ffmpeg() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


@pytest.mark.skipif(not _has_ffmpeg(), reason="missing ffmpeg")
def test_export_m4b_creates_file(tmp_path: Path) -> None:
    out = tmp_path / "out.m4b"
    result = export_m4b(GOLDEN, output=out)
    assert result.path == out
    assert out.is_file()
    assert result.chapter_count == 2

    # ffprobe chapter count
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", "-show_chapters", str(out)],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(probe.stdout)
    assert len(data.get("chapters", [])) == 2

    # metadata
    mprobe = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", "-show_format", str(out)],
        capture_output=True,
        text=True,
        check=True,
    )
    mdata = json.loads(mprobe.stdout)
    tags = mdata.get("format", {}).get("tags", {}) or {}
    assert tags.get("title") == "Scribe Golden Bundle"
    assert tags.get("artist") == "Auloud Test"


@pytest.mark.skipif(not _has_ffmpeg(), reason="missing ffmpeg")
def test_cli_export_m4b(tmp_path: Path) -> None:
    out = tmp_path / "cli.m4b"
    result = RUNNER.invoke(
        cli.app,
        ["export", "m4b", str(GOLDEN), "--output", str(out)],
    )
    assert result.exit_code == 0, result.output
    assert out.is_file()
