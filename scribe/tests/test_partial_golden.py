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

"""UI1 partial-golden contract (``spec/fixtures/partial-golden/``, spec v1.2).

Pins D7: range builds list only rendered chapters consecutively 1..K,
each with ``source_index``, bundle id range-aware (full keeps the existing
id), spec "1.2", and the bundle validates with the real probe. The fixture
was built by ``scribe/dev/make_partial_golden.py`` (real pipeline, fake
synth, real ffmpeg); tests here only READ it, never rebuild it.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

from bundle.validate import validate_bundle

FIXTURE = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "partial-golden"

# Pinned by the generator (source chapters 2-3 of the synthetic 3-chapter EPUB).
GOLDEN_RANGE_ID = "53a97272-f146-524b-94e2-3aee0d397b2f"
GOLDEN_FULL_ID = "8df84e5d-49fc-53b9-9bc0-9b8c722abfe7"


def test_partial_golden_present() -> None:
    for rel in (
        "manifest.json",
        "README.md",
        "audio/ch001.mp3",
        "audio/ch002.mp3",
        "text/ch001.json",
        "text/ch002.json",
        "source/book.epub",
    ):
        assert (FIXTURE / rel).is_file(), f"golden file missing: {rel}"


def test_partial_golden_pins() -> None:
    manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["id"] == GOLDEN_RANGE_ID
    assert manifest["id"] != GOLDEN_FULL_ID
    assert manifest["spec_version"] == "1.2"
    assert [c["index"] for c in manifest["chapters"]] == [1, 2]
    assert [c["source_index"] for c in manifest["chapters"]] == [2, 3]
    assert [c["title"] for c in manifest["chapters"]] == ["Chapter Two", "Chapter Three"]
    for entry in manifest["chapters"]:
        chapter = json.loads((FIXTURE / entry["text"]).read_text(encoding="utf-8"))
        assert chapter["chapter"] == entry["index"]
        assert chapter["source_index"] == entry["source_index"]
        assert chapter["spec_version"] == "1.2"


def test_partial_golden_valid_with_real_probe() -> None:
    if shutil.which("ffprobe") is None:
        raise AssertionError(
            "ffprobe not on PATH (winget install ffmpeg): the golden "
            "contract test must run the real probe, never skip it"
        )
    result = validate_bundle(FIXTURE)
    assert result.ok, result.errors
