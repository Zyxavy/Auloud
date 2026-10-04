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
    assert manifest["spec_version"] == "1.1"
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


def test_golden_rebuild_matches_committed(tmp_path: Path) -> None:
    """Producer side: rebuild the golden bundle in-test, compare file-for-file.

    The committed-artifact tests above only assert the golden STAYS valid,
    so a writer/assemble/encode regression after the golden was cut would
    not break them. This test closes the producer side: it feeds the
    committed ``source/book.epub`` (same synthetic 2-chapter input) through
    the REAL draft -> assemble -> encode -> write path with the fake sine
    synth into a tmp dir, then asserts the rebuilt bundle matches the
    committed ``scribe-golden/`` file-for-file EXCEPT ``manifest.created_at``
    (UTC now by design — compare all other bytes exactly, and compare
    manifests parsed with ``created_at`` excluded).

    The ``write_bundle`` call runs the REAL SW2 ``validate_bundle`` gate
    (real ffprobe, no stubs) and raises on failure, so a passing rebuild
    implicitly passed validation. Tiny sine audio only: no TTS models, no
    network. ``README.md`` is fixture docs, not bundle output, so it is
    excluded from the file-for-file comparison.
    """
    import numpy as np

    from audio.assemble import (
        AssembledChapter,
        apply_loudness_gain,
        assemble_chapter,
    )
    from audio.encode import encode_assembled_chapter
    from build import _with_timings
    from bundle.models import Sentence
    from bundle.writer import write_bundle
    from draft import run_draft

    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        raise AssertionError(
            "ffmpeg/ffprobe not on PATH (winget install ffmpeg): the golden "
            "rebuild test must run the real encode + probe, never skip it"
        )

    def _golden_sine(sentence: Sentence) -> np.ndarray:
        """Golden fake synth: exact 100 ms sine, 440 Hz + 110 Hz steps by sid."""
        freq = 440.0 + (sentence.sid % 5) * 110.0
        t = np.arange(2400, dtype=np.float64) / 24_000
        return (0.4 * np.sin(2.0 * np.pi * freq * t)).astype(np.float32)

    src = GOLDEN / "source" / "book.epub"
    assert src.is_file(), f"golden source missing: {src}"
    work_root = tmp_path / "work"
    out_dir = tmp_path / "rebuilt"

    draft = run_draft(src, work_root=work_root)
    assert draft.book_id == GOLDEN_BOOK_ID
    assert len(draft.chapters) == 2

    timed = []
    audio_paths = []
    mp3_dir = tmp_path / "mp3"
    mp3_dir.mkdir(parents=True, exist_ok=True)
    for chapter in draft.chapters:
        assembled = assemble_chapter(chapter, _golden_sine)
        assert assembled.duration_ms == GOLDEN_DURATION_MS
        loud = apply_loudness_gain(assembled.pcm)
        to_encode = AssembledChapter(
            pcm=loud,
            timings=assembled.timings,
            duration_ms=assembled.duration_ms,
            sample_count=assembled.sample_count,
            sample_rate=assembled.sample_rate,
        )
        mp3_path = mp3_dir / f"ch{chapter.chapter:03d}.mp3"
        encode_assembled_chapter(
            to_encode, mp3_path, chapter_label=f"ch{chapter.chapter:03d}"
        )
        timed.append(_with_timings(chapter, assembled.timings, assembled.duration_ms))
        audio_paths.append(mp3_path)

    # Real writer + real validator gate (raises on failure — implicit pass).
    write_bundle(timed, audio_paths, src, out_dir)

    expected = (
        "manifest.json",
        "audio/ch001.mp3",
        "audio/ch002.mp3",
        "text/ch001.json",
        "text/ch002.json",
        "source/book.epub",
    )
    rebuilt_files = {
        p.relative_to(out_dir).as_posix() for p in out_dir.rglob("*") if p.is_file()
    }
    assert rebuilt_files == set(expected), f"bundle file set differs: {sorted(rebuilt_files)}"
    for rel in expected:
        if rel == "manifest.json":
            continue
        golden_bytes = (GOLDEN / rel).read_bytes()
        rebuilt_bytes = (out_dir / rel).read_bytes()
        assert golden_bytes == rebuilt_bytes, f"{rel}: bytes differ from committed golden"

    golden_manifest = json.loads((GOLDEN / "manifest.json").read_text(encoding="utf-8"))
    rebuilt_manifest = json.loads((out_dir / "manifest.json").read_text(encoding="utf-8"))
    assert "created_at" in golden_manifest and "created_at" in rebuilt_manifest
    golden_manifest.pop("created_at", None)
    rebuilt_manifest.pop("created_at", None)
    assert golden_manifest == rebuilt_manifest
