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

"""Build the tiny UI1 partial-bundle golden (``spec/fixtures/partial-golden/``).

One-off generator (committed for reproducibility; the fixture itself is
committed with MP3s, so tests never run this). Mirrors the pdf-golden
approach: a synthetic 3-chapter EPUB (self-made text, no book content)
goes through the REAL draft + full build + range build with a FAKE synth
engine (deterministic 100 ms tones, real ``audio.assemble`` +
``audio.encode`` with ffmpeg, real ``bundle.writer`` with resolved voices).
No Kokoro models, no network, no slow mark.

Content (all synthetic): 3 chapters, each 25 repeats of one sentence
(over the 200-word tiny-merge). Range build selects source chapters 2-3,
renumbered to bundle 1-2 with ``source_index`` 2 and 3, range-aware id,
spec 1.2. Run from ``scribe/`` with ``uv run python
dev/make_partial_golden.py`` (needs ffmpeg/ffprobe).
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np
from ebooklib import epub

from build import run_build
from bundle.validate import validate_bundle
from draft import book_id_for_file, book_id_for_range, run_draft
from tts.base import TTSEngine

FIXTURE_NAME = "partial-golden"
SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."
REPEATS = 25
TITLES = ["Chapter One", "Chapter Two", "Chapter Three"]
RANGE = [2, 3]


class FakeEngine(TTSEngine):
    def __init__(self, version: str = "fake-partial-golden") -> None:
        self._version = version

    @property
    def sample_rate(self) -> int:
        return 24_000

    @property
    def engine_version(self) -> str:
        return self._version

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        freq = 440.0 + (len(text) % 5) * 110.0
        t = np.arange(2400, dtype=np.float64) / 24_000
        return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _make_epub(path: Path) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-partial-golden")
    book.set_title("Partial Golden Miniature")
    book.set_language("en")
    book.add_author("Auloud Test")
    items = []
    for pos, title in enumerate(TITLES, start=1):
        body = " ".join([SENTENCE] * REPEATS)
        item = epub.EpubHtml(title=title, file_name=f"ch{pos}.xhtml", lang="en")
        item.content = f"<h1>{title}</h1><p>{body}</p>"
        book.add_item(item)
        items.append(item)
    book.toc = [
        epub.Link(f"ch{pos}.xhtml", title, f"ch{pos}") for pos, title in enumerate(TITLES, 1)
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = items
    epub.write_epub(str(path), book)
    return path


def main() -> int:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        print("ffmpeg/ffprobe not on PATH (winget install ffmpeg)", file=sys.stderr)
        return 1
    repo_root = Path(__file__).resolve().parents[2]
    fixture = repo_root / "spec" / "fixtures" / FIXTURE_NAME
    with tempfile.TemporaryDirectory(prefix="partial-golden-") as tmp:
        tmp_path = Path(tmp)
        source = _make_epub(tmp_path / "source.epub")
        work_root = tmp_path / "work"
        out_dir = tmp_path / "out"
        _full_id, sha = book_id_for_file(source)
        expected_id = book_id_for_range(sha, RANGE)
        draft_result = run_draft(source, work_root=work_root)
        print(f"draft: {len(draft_result.chapters)} chapter(s), book {draft_result.book_id}")
        build_result = run_build(
            source,
            work_root=work_root,
            out_dir=out_dir,
            engine=FakeEngine(),
            show_progress=False,
            chapters="2-3",
        )
        print(
            f"build: {build_result.chapters_total} chapter(s), "
            f"{build_result.total_audio_ms} ms, id {build_result.book_id}"
        )
        assert build_result.book_id == expected_id, (
            f"range id {build_result.book_id} != expected {expected_id}"
        )
        check = validate_bundle(out_dir)
        if not check.ok:
            print("validate FAILED:", file=sys.stderr)
            for error in check.errors:
                print(f"  - {error}", file=sys.stderr)
            return 1
        if fixture.exists():
            shutil.rmtree(fixture)
        shutil.copytree(out_dir, fixture)
        manifest = json.loads((fixture / "manifest.json").read_text(encoding="utf-8"))
        chapters = []
        for entry in manifest["chapters"]:
            ch = json.loads((fixture / entry["text"]).read_text(encoding="utf-8"))
            chapters.append(ch)
        audio_sizes = {
            p.name: p.stat().st_size for p in sorted((fixture / "audio").glob("*.mp3"))
        }
        readme = f"""# partial-golden: range-bundle contract (Slice 6 UI1, spec v1.2)

Two-chapter range bundle built by the REAL Scribe pipeline with a FAKE
synth engine (same pattern as `pdf-golden`). Pins D7: consecutive
renumbering, `source_index`, range-aware id. Both suites pin it.

- Scribe: `scribe/tests/test_partial_golden.py` runs the real SW2
  `validate_bundle` (real `ffprobe`, no stubs) and asserts valid, plus
  pins the range id, consecutive indices, `source_index` values and spec
  "1.2".
- Player: `PartialGoldenTest` (or the shared golden contract) loads the
  bundle through the REAL parser (renumbered chapters read as text,
  `source_index` ignored) — added only if the 1.2 fields break parsing.

## How it was built

Committed generator `scribe/dev/make_partial_golden.py` (run from
`scribe/` with `uv run python dev/make_partial_golden.py`, needs
ffmpeg/ffprobe):

1. **Synthetic EPUB:** 3 chapters, self-made sentence repeated 25x each
   (over the 200-word tiny-merge), title "Partial Golden Miniature".
2. **Draft (real):** `draft.run_draft` (full book, always).
3. **Range build (real):** `build.run_build(..., chapters="2-3")` with a
   fake engine (100 ms tones), real assemble + encode + writer (spec
   "1.2", `source_index`, range id).
4. **Validate (real):** `validate_bundle` with real `ffprobe` must pass
   before copy to `spec/fixtures/partial-golden/`.

## Fixed range id

`{manifest["id"]}` — UUIDv5 over source sha plus range `2-3`
(full id for this source would be `{_full_id}`; range ids never collide
with it, so Player progress never points at the wrong chapter).

## Content (all synthetic — committable)

- Source chapters 2-3 rendered as bundle chapters 1-2
  (`source_index` 2, 3). Full-book rebuild keeps id `{_full_id}`.
- Audio: tones, NOT speech.

## Manifest chapters

| bundle index | source_index | title | duration_ms |
| --- | --- | --- | --- |
"""
        for entry in manifest["chapters"]:
            readme += (
                f"| {entry['index']} | {entry.get('source_index')} "
                f"| {entry['title']} | {entry['duration_ms']} |\n"
            )
        readme += f"""
`spec_version` {manifest["spec_version"]}. Audio sizes: `{audio_sizes}`.
(`created_at` differs on any rebuild by design — compare ignoring that key.)
"""
        (fixture / "README.md").write_text(readme, encoding="utf-8")
        print(f"wrote {fixture} (range {RANGE}, id {manifest['id']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
