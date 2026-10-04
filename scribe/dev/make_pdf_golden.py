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

"""Build the tiny CP6 PDF golden bundle (``spec/fixtures/pdf-golden/``).

One-off generator (committed for reproducibility; the fixture itself is
committed with MP3s, so tests never run this). Mirrors the multivoice-golden
approach: a synthetic 2-page PDF (self-made text, no book content) goes
through the REAL draft + build pipeline with a FAKE synth engine
(deterministic 100 ms tones per sentence, real ``audio.assemble`` +
``audio.encode`` with ffmpeg, real ``bundle.writer`` with resolved voices).
No Kokoro models, no network, no slow mark.

Content (all synthetic):
- Page 1: narration "The amber lamp glowed softly above the quiet river
  bend." plus dialogue '"We should leave," Alice said.' (explicit tag,
  attributes Alice, splits into dialogue + tag like multivoice-golden).
- Page 2: narration "Morning came at last over the mossy hill."

Result: 1 chapter ("Chapter 1" fallback, 2 pages), 4 sentences, 2 voices
(narrator + Alice), ``pages`` marks computed from timings, ``page`` on
every sentence. Run from ``scribe/`` with ``uv run python
dev/make_pdf_golden.py`` (needs ffmpeg/ffprobe + pymupdf on PATH).
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np
import pymupdf

from build import run_build
from bundle.validate import validate_bundle
from draft import run_draft
from tts.base import TTSEngine

PAGE_W, PAGE_H = 595.0, 842.0
FIXTURE_NAME = "pdf-golden"


class FakeEngine(TTSEngine):
    """Deterministic stand-in: exact 100 ms sine per sentence (as test_build)."""

    def __init__(self, version: str = "fake-pdf-golden") -> None:
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


def _insert_paras(page: pymupdf.Page, texts: list[str], top: float = 120.0) -> None:
    y = top
    for text in texts:
        rect = pymupdf.Rect(72, y, 523, PAGE_H - 60)
        unused = page.insert_textbox(rect, text, fontsize=11)
        unused_h = unused.height if hasattr(unused, "height") else float(unused)
        y += (rect.height - unused_h) + 18.0


def _make_source_pdf(path: Path) -> Path:
    doc = pymupdf.open()
    page1 = doc.new_page(width=PAGE_W, height=PAGE_H)
    _insert_paras(
        page1,
        [
            "The amber lamp glowed softly above the quiet river bend.",
            '"We should leave," Alice said.',
        ],
    )
    page2 = doc.new_page(width=PAGE_W, height=PAGE_H)
    _insert_paras(
        page2,
        ["Morning came at last over the mossy hill."],
    )
    doc.set_metadata({"title": "PDF Golden Miniature", "author": "Auloud Test"})
    doc.save(str(path))
    doc.close()
    return path


def main() -> int:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        print("ffmpeg/ffprobe not on PATH (winget install ffmpeg)", file=sys.stderr)
        return 1
    repo_root = Path(__file__).resolve().parents[2]
    fixture = repo_root / "spec" / "fixtures" / FIXTURE_NAME
    with tempfile.TemporaryDirectory(prefix="pdf-golden-") as tmp:
        tmp_path = Path(tmp)
        source = _make_source_pdf(tmp_path / "source.pdf")
        work_root = tmp_path / "work"
        out_dir = tmp_path / "out"
        draft_result = run_draft(source, work_root=work_root)
        print(f"draft: {len(draft_result.chapters)} chapter(s), book {draft_result.book_id}")
        build_result = run_build(
            source,
            work_root=work_root,
            out_dir=out_dir,
            engine=FakeEngine(),
            show_progress=False,
        )
        print(
            f"build: {build_result.chapters_total} chapter(s), "
            f"{build_result.total_audio_ms} ms, {build_result.total_sentences} sentences"
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
        chapter = json.loads((fixture / "text" / "ch001.json").read_text(encoding="utf-8"))
        sentences = [s for b in chapter["blocks"] for s in b.get("sentences", [])]
        audio_size = (fixture / "audio" / "ch001.mp3").stat().st_size
        text_size = (fixture / "text" / "ch001.json").stat().st_size
        source_size = (fixture / "source" / "book.pdf").stat().st_size
        readme = f"""# pdf-golden: PDF text-path contract bundle (Slice 5 CP6)

One-chapter bundle built by the REAL Scribe pipeline with a FAKE synth
engine (same pattern as `multivoice-golden`). Both suites pin it: if
either half drifts from spec v1.1, one of them goes red.

- Scribe: `scribe/tests/test_pdf_golden.py` runs the real SW2
  `validate_bundle` (real `ffprobe`, no stubs) and asserts valid, plus
  pins the book id, voices map, sentence pages and `pages` marks.
- Player: `PdfGoldenTest` loads `text/ch001.json` through the REAL
  `ChapterTextLoader` (blocks+pages reads as text, not `PdfForm`) and
  checks pages lookup.

## How it was built

Committed generator `scribe/dev/make_pdf_golden.py` (run from `scribe/`
with `uv run python dev/make_pdf_golden.py`, needs ffmpeg/ffprobe):

1. **Synthetic PDF (real `pymupdf`):** 2 pages, self-made text (no book
   content), title "PDF Golden Miniature", author "Auloud Test".
2. **Draft (real):** `draft.run_draft` (real spaCy attribution:
   `"We should leave," Alice said.` attributes Alice).
3. **Build (real):** `build.run_build` with a fake engine (deterministic
   100 ms tones per sentence), real `audio.assemble` + `audio.encode`,
   real `bundle.writer.write_bundle` (computes `pages` from timings, writes
   spec "1.1").
4. **Validate (real):** `validate_bundle` with real `ffprobe` must pass
   before the fixture is copied to `spec/fixtures/pdf-golden/`.

Re-running the generator overwrites this folder (including `source/book.pdf`
and `created_at`, which differs by design — compare ignoring that key).

## Fixed book id

`{manifest["id"]}` — UUIDv5 over the source PDF's sha256
(`{manifest["source"]["sha256"][:16]}...`, see `manifest.json`). Same source
bytes always give the same id, so the Player keeps saved progress across
rebuilds. Both suites pin this id: replacing the fixture without updating
the tests fails loudly.

## Content (all synthetic — committable)

- Text: 4 self-made sentences across 2 pages (amber lamp narration;
  `"We should leave,"` dialogue as Alice; ` Alice said.` narration tag;
  morning narration on page 2).
- Audio: tones, NOT speech. Anyone expecting narration is in the wrong
  fixture.

## Exact sentences (pinned by both suites)

`duration_ms` {chapter["duration_ms"]}, title "{chapter["title"]}". Sids run
1..{len(sentences)}, first start 0, timings ordered with gaps.

| sid | block | speaker | start_ms | end_ms | page | text (verbatim) |
| --- | --- | --- | --- | --- | --- | --- |
"""
        for s in sentences:
            readme += (
                f"| {s['sid']} | — | {s['speaker']} | {s['start_ms']} "
                f"| {s['end_ms']} | {s.get('page')} | `{s['text']}` |\n"
            )
        readme += f"""
`pages` marks: `{json.dumps(chapter.get('pages'))}` (each `start_ms` is its
page's first sentence start).

## Sizes / durations

| file | duration | size |
| --- | --- | --- |
| `audio/ch001.mp3` | {chapter["duration_ms"]} ms | {audio_size} bytes |
| `text/ch001.json` | {chapter["duration_ms"]} ms | {text_size} bytes |
| `source/book.pdf` | — | {source_size} bytes |

`source/book.pdf` is part of the fixture: the manifest pins its sha256
and the Scribe validator checks it. (`created_at` records the build time
and differs on any rebuild by design — compare ignoring that key.)
"""
        (fixture / "README.md").write_text(readme, encoding="utf-8")
        print(f"wrote {fixture} (book {manifest['id']}, {chapter['duration_ms']} ms)")
        print(f"sentences: {len(sentences)}, pages: {chapter.get('pages')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
