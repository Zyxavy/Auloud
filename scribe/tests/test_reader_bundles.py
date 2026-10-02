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

"""RA0a: the dev test-data builder produces validating bundles end to end."""

from __future__ import annotations

import json
from pathlib import Path

from dev.make_reader_bundles import _beep_for, build_bundle, sentence_text
from bundle.validate import validate_bundle


def test_tiny_beep_bundle_validates(tmp_path: Path) -> None:
    """3 sentences through assemble/encode/write -> validator-clean bundle."""
    texts = [sentence_text(i) for i in range(2)]  # + heading = 3 sentences
    bundle_dir = build_bundle(
        "ra-tiny", "Tiny test", texts, _beep_for, tmp_path / "out", tmp_path / "work"
    )
    result = validate_bundle(bundle_dir)
    assert result.ok, result.errors
    manifest = json.loads((bundle_dir / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["chapters"][0]["duration_ms"] > 0
    chapter = json.loads((bundle_dir / "text" / "ch001.json").read_text(encoding="utf-8"))
    starts = [s["start_ms"] for block in chapter["blocks"] for s in block.get("sentences", [])]
    assert starts[0] == 0
    assert starts == sorted(starts)
