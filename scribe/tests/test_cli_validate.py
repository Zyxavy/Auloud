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
"""Tests for the `scribe validate` CLI command (thin wiring over SW2)."""

from pathlib import Path

from typer.testing import CliRunner

from cli import app

RUNNER = CliRunner()
GOLDEN = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "scribe-golden"


def test_validate_valid_bundle_exit_zero() -> None:
    result = RUNNER.invoke(app, ["validate", str(GOLDEN)])
    assert result.exit_code == 0, result.output
    assert "valid:" in result.output


def test_validate_bad_bundle_exit_one_names_rule() -> None:
    bad = GOLDEN.parent / "missing-mp3"
    result = RUNNER.invoke(app, ["validate", str(bad)])
    assert result.exit_code == 1, result.output
    assert "Traceback" not in result.output
    assert "audio/ch002.mp3" in result.output


def test_validate_missing_dir_exit_one_no_traceback() -> None:
    result = RUNNER.invoke(app, ["validate", str(GOLDEN.parent / "no-such-bundle")])
    # Typer rejects the missing path itself (usage error, exit 2).
    assert result.exit_code == 2
    assert "Traceback" not in result.output
