# Auloud Scribe — turns ebooks into multi-voice audiobooks (PC tool).
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

"""SW1 smoke tests: CLI wiring and `doctor` check helpers.

Environment-dependent rows (ffmpeg, GPU, ...) are covered through mocks;
only the Python-version check asserts against the real interpreter, which
`requires-python >= 3.11` guarantees.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import cli
from typer.testing import CliRunner


def test_version_command_prints_version() -> None:
    result = CliRunner().invoke(cli.app, ["version"])
    assert result.exit_code == 0
    assert cli.__version__ in result.output


def test_doctor_runs_and_prints_table(tmp_path: Path) -> None:
    # Exit code depends on the machine (missing engine/models -> 1), so the
    # smoke test asserts the table shape, not the code.
    result = CliRunner().invoke(cli.app, ["doctor", "--models-dir", str(tmp_path)])
    assert result.exit_code in (0, 1)
    assert "CHECK" in result.output and "STATUS" in result.output
    for row in (
        "python",
        "ffmpeg",
        "ffprobe",
        "espeak-ng",
        "tts-engine",
        "models",
        "spacy",
        "gpu",
    ):
        assert row in result.output


def test_python_check_passes_on_supported_interpreter() -> None:
    result = cli.check_python()
    assert result.status == cli.PASS


def test_python_check_fails_below_minimum() -> None:
    result = cli.check_python(min_version=(99, 0))
    assert result.status == cli.FAIL
    assert result.hint


def test_missing_tool_reports_fail_with_install_help(
    monkeypatch: object,
) -> None:
    # `monkeypatch` is typed loosely to avoid importing pytest here.
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: None)
    result = cli.check_tool("ffmpeg", ["-version"], cli.FFMPEG_HELP)
    assert result.status == cli.FAIL
    assert "winget install ffmpeg" in result.hint


def test_tool_that_does_not_respond_is_fail(monkeypatch: object) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: r"C:\fake\ffmpeg.exe")

    def _boom(*args: object, **kwargs: object) -> object:
        raise FileNotFoundError("gone")

    mp.setattr(cli.subprocess, "run", _boom)
    result = cli.check_tool("ffmpeg", ["-version"], cli.FFMPEG_HELP)
    assert result.status == cli.FAIL


def test_engine_missing_is_graceful_not_an_exception(monkeypatch: object) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.importlib.util, "find_spec", lambda _name: None)
    result = cli.check_engine()
    assert result.status == cli.FAIL
    assert "kokoro-onnx" in result.detail
    assert result.hint  # exact install help, no traceback


def test_models_missing_names_each_file(tmp_path: Path) -> None:
    result = cli.check_models(tmp_path)
    assert result.status == cli.FAIL
    for name in cli.MODEL_FILES:
        assert name in result.detail


def test_models_present_when_both_files_exist(tmp_path: Path) -> None:
    for name in cli.MODEL_FILES:
        (tmp_path / name).write_bytes(b"fake")
    result = cli.check_models(tmp_path)
    assert result.status == cli.PASS


def test_espeak_ng_found_on_path(monkeypatch: object) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: r"C:\tools\espeak-ng.exe")
    fake = subprocess.CompletedProcess(
        args=["espeak-ng", "--version"], returncode=0, stdout="eSpeak NG 1.52.0\n", stderr=""
    )
    mp.setattr(cli.subprocess, "run", lambda *args, **kwargs: fake)
    result = cli.check_espeak_ng(msi_path=Path(r"C:\nonexistent\espeak-ng.exe"))
    assert result.status == cli.PASS
    assert "1.52.0" in result.detail


def test_espeak_ng_missing_reports_install_help(monkeypatch: object, tmp_path: Path) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: None)
    result = cli.check_espeak_ng(msi_path=tmp_path / "espeak-ng.exe")
    assert result.status == cli.FAIL
    assert "espeak-ng" in result.hint


def test_gpu_absent_is_info_not_fail(monkeypatch: object) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: None)
    result = cli.check_gpu()
    assert result.status == cli.INFO


def test_tool_with_empty_version_output_is_fail(monkeypatch: object) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: r"C:\fake\ffmpeg.exe")
    fake = subprocess.CompletedProcess(
        args=["ffmpeg", "-version"], returncode=0, stdout="\n", stderr=""
    )
    mp.setattr(cli.subprocess, "run", lambda *args, **kwargs: fake)
    result = cli.check_tool("ffmpeg", ["-version"], cli.FFMPEG_HELP)
    assert result.status == cli.FAIL
    assert "did not respond" in result.detail
    assert "reinstall" in result.hint


def test_espeak_ng_with_empty_version_output_is_fail(monkeypatch: object) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: r"C:\tools\espeak-ng.exe")
    fake = subprocess.CompletedProcess(
        args=["espeak-ng", "--version"], returncode=0, stdout="", stderr=""
    )
    mp.setattr(cli.subprocess, "run", lambda *args, **kwargs: fake)
    result = cli.check_espeak_ng(msi_path=Path(r"C:\nonexistent\espeak-ng.exe"))
    assert result.status == cli.FAIL
    assert "reinstall" in result.hint


def test_gpu_with_empty_output_stays_info(monkeypatch: object) -> None:
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setattr(cli.shutil, "which", lambda _name: r"C:\fake\nvidia-smi.exe")
    fake = subprocess.CompletedProcess(
        args=["nvidia-smi", "-L"], returncode=0, stdout="", stderr=""
    )
    mp.setattr(cli.subprocess, "run", lambda *args, **kwargs: fake)
    result = cli.check_gpu()
    assert result.status == cli.INFO


def test_spacy_check_passes_with_installed_model() -> None:
    # spacy + en_core_web_sm are pinned project dependencies (MV0), so the
    # real check must pass in the project environment (like the python check).
    result = cli.check_spacy()
    assert result.status == cli.PASS
    assert "en_core_web_sm" in result.detail
    assert "parse ok" in result.detail


def test_spacy_missing_is_fail_with_sync_hint(monkeypatch: object) -> None:
    import sys

    mp = monkeypatch  # type: ignore[union-attr]
    mp.setitem(sys.modules, "spacy", None)  # `import spacy` then raises ImportError
    result = cli.check_spacy()
    assert result.status == cli.FAIL
    assert "not importable" in result.detail
    assert "uv sync" in result.hint


def test_spacy_model_missing_is_fail_with_download_hint(monkeypatch: object) -> None:
    import text.nlp

    mp = monkeypatch  # type: ignore[union-attr]

    def _missing() -> object:
        raise OSError("No module named 'en_core_web_sm'")

    mp.setattr(text.nlp, "load_model", _missing)
    result = cli.check_spacy()
    assert result.status == cli.FAIL
    assert "en_core_web_sm" in result.detail
    assert "spacy download en_core_web_sm" in result.hint


def test_spacy_model_version_probe() -> None:
    import text.nlp

    assert text.nlp.MODEL_NAME == "en_core_web_sm"
    assert text.nlp.model_version() is not None  # URL-pinned in pyproject.toml + uv.lock
    assert text.nlp.spacy_version() is not None


def test_spacy_packaging_broken_is_graceful_fail(monkeypatch: object) -> None:
    import sys

    mp = monkeypatch  # type: ignore[union-attr]
    mp.setitem(sys.modules, "text.nlp", None)  # `from text.nlp import ...` raises ImportError
    result = cli.check_spacy()
    assert result.status == cli.FAIL
    assert "install broken" in result.detail
    assert "reinstall" in result.hint


def test_load_model_cached_returns_same_object() -> None:
    import text.nlp

    assert text.nlp.load_model() is text.nlp.load_model()
