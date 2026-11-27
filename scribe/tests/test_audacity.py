import zipfile
from pathlib import Path

import pytest
from typer.testing import CliRunner

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cli
from export.audacity import export_audacity

RUNNER = CliRunner()
GOLDEN = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "scribe-golden"


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