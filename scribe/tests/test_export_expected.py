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

"""RS1: parity expected files are fresh, deterministic, and cover every rule.

- Re-exporting every fixture in memory matches the committed
  ``parity/expected/*.json`` byte-for-byte (rerun ``dev/export_expected.py``
  to refresh after a validator change; the diff then shows exactly what the
  Rust side must match).
- The rule table in ``docs/rust-port.md`` matches ``--render-docs`` output.
- Every rule with a real fixture appears at least once across the expected
  files (``rs4-only``/``unit-only`` entries are exempt by design).
"""

from __future__ import annotations

import json
from pathlib import Path

from dev.export_expected import EXPECTED, FIXTURES, RULES, export_fixture, render_docs

REPO = Path(__file__).resolve().parent.parent.parent
RUST_PORT = REPO / "docs" / "rust-port.md"
EXEMPT_FIXTURES = {"rs4-only", "unit-only"}


def _committed() -> dict[str, dict]:
    return {
        path.stem: json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(EXPECTED.glob("*.json"))
    }


def test_expected_files_are_fresh() -> None:
    committed = _committed()
    names = sorted(path.name for path in FIXTURES.iterdir() if (path / "manifest.json").is_file())
    assert names, "no bundle fixtures found"
    assert set(names) == set(committed), (
        f"expected files out of sync: fixtures={sorted(set(names) - set(committed))} "
        f"extra={sorted(set(committed) - set(names))}"
    )
    for name in names:
        fresh = export_fixture(name)
        assert fresh == committed[name], f"stale expected file for {name}"


def test_export_is_deterministic() -> None:
    names = sorted(path.name for path in FIXTURES.iterdir() if (path / "manifest.json").is_file())
    for name in names:
        assert export_fixture(name) == export_fixture(name), f"nondeterministic {name}"


def test_rule_table_docs_match() -> None:
    text = RUST_PORT.read_text(encoding="utf-8")
    start = text.index("<!-- RULE-TABLE-START -->\n") + len("<!-- RULE-TABLE-START -->\n")
    end = text.index("<!-- RULE-TABLE-END -->")
    # The spliced section is render_docs() plus print's newline (see splice).
    assert text[start:end] == render_docs() + "\n", "docs/rust-port.md rule table is stale"


def test_every_rule_covered() -> None:
    seen: dict[str, int] = {}
    for data in _committed().values():
        for error in data["errors"]:
            seen[error["rule"]] = seen.get(error["rule"], 0) + 1
    missing = [
        rule.id
        for rule in RULES
        if rule.fixture not in EXEMPT_FIXTURES and seen.get(rule.id, 0) == 0
    ]
    assert not missing, f"rules without fixture coverage: {missing}"
    unknown = set(seen) - {rule.id for rule in RULES}
    assert not unknown, f"expected files use unknown rule ids: {sorted(unknown)}"
