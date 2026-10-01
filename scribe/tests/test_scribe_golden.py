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

"""SW10: golden contract bundle (``spec/fixtures/scribe-golden/``).

Runs the REAL SW2 :func:`bundle.validate.validate_bundle` — real ffprobe
probe, no stubs — over the committed golden bundle and asserts valid.
This guards fixture rot (a damaged MP3, hand-edited JSON, or stale sha
fails here). The Player pins the same fixture from the other direction
(``ScribeGoldenContractTest`` with the real ``BundleParser`` /
``BundleValidator``); together they catch format drift between the halves.

The fixed book id and chapter durations are pinned so a silently swapped
fixture fails loudly instead of passing against different content.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

GOLDEN = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "scribe-golden"

#: UUIDv5 over the golden source EPUB's sha256 (see the fixture README).
GOLDEN_BOOK_ID = "6d23921c-b53a-5429-964c-05c42413ce93"

#: Per-chapter durations the golden was built with (fake 100 ms sine synth).
GOLDEN_DURATION_MS = 7450


def _manifest() -> dict:
    return json.loads((GOLDEN / "manifest.json").read_text(encoding="utf-8"))


def test_golden_fixture_present() -> None:
    """All committed golden files exist (nothing lost to .gitignore/pruning)."""
    for rel in (
        "manifest.json",
        "README.md",
        "audio/ch001.mp3",
        "audio/ch002.mp3",
        "text/ch001.json",
        "text/ch002.json",
        "source/book.epub",
    ):
        assert (GOLDEN / rel).is_file(), f"golden file missing: {rel}"


def test_golden_pins_book_id_and_chapters() -> None:
    """Fixed id, two chapters, exact durations — a swapped fixture fails."""
    manifest = _manifest()
    assert manifest["id"] == GOLDEN_BOOK_ID
    assert manifest["spec_version"] == "1.0"
    assert manifest["type"] == "epub"
    assert len(manifest["chapters"]) == 2
    for pos, entry in enumerate(manifest["chapters"], start=1):
        assert entry["index"] == pos
        assert entry["audio"] == f"audio/ch{pos:03d}.mp3"
        assert entry["text"] == f"text/ch{pos:03d}.json"
        assert entry["duration_ms"] == GOLDEN_DURATION_MS


def test_golden_valid_with_real_probe() -> None:
    """REAL validator + REAL ffprobe over the committed golden: must pass."""
    from bundle.validate import validate_bundle

    if shutil.which("ffprobe") is None:
        raise AssertionError(
            "ffprobe not on PATH (winget install ffmpeg): the golden "
            "contract test must run the real probe, never skip it"
        )
    result = validate_bundle(GOLDEN)  # no stub probe: the real ffprobe path
    assert result.ok, result.errors
