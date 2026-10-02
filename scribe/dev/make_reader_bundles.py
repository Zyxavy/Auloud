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

"""Dev-only test-data builder for Slice 3 read-along work (RA0a).

Builds two synthetic bundles with a fake tone engine (no TTS models, takes
seconds for the beep bundle and a few minutes for the long one):

- **beep bundle:** one chapter, 40 sentences; each sentence is a short beep
  placed at exactly its ``start_ms``, so sync can be judged by ear and eye;
- **long chapter:** one chapter, 5,000 sentences of ~0.4 s tone each with
  real-looking text, to stress scrolling and memory.

Both go through the real pipeline pieces (:func:`audio.assemble.assemble_chapter`,
:func:`audio.encode.encode_assembled_chapter`, :func:`bundle.writer.write_bundle`)
and therefore through the real SW2 validator — the writer refuses to finish a
bundle that does not validate. Output goes to the gitignored ``logs/`` folder.

This is NOT a user command (no ``cli.py`` wiring): run it directly::

    uv run python dev/make_reader_bundles.py [--out ../logs]

Sentence text follows the Scribe convention seen in the golden bundle
(trimmed text plus one trailing space, concatenated downstream).
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from audio.assemble import assemble_chapter  # noqa: E402
from audio.encode import encode_assembled_chapter  # noqa: E402
from bundle.models import Block, ChapterFile, Sentence  # noqa: E402
from bundle.writer import SPEC_VERSION, write_bundle  # noqa: E402
from tts.base import SAMPLE_RATE  # noqa: E402

#: Sentences in the beep bundle (heading + paras below must add up).
BEEP_SENTENCES = 40
#: Sentences in the long stress chapter.
LONG_SENTENCES = 5000
#: Sentences per paragraph block (heading excluded).
PARA_SIZE = 8
#: Beep marking each sentence start (alternating pitch, clearly audible).
BEEP_MS = 150
BEEP_FREQS = (880.0, 660.0)
BEEP_AMP = 0.4
#: Long-chapter tone: ~0.4 s each, quiet hum (peak gain is not applied here).
TONE_MS = 400
TONE_FREQ = 440.0
TONE_AMP = 0.3

_NOUNS = ("river", "lamp", "garden", "window", "road", "cat", "bell", "forest")
_ADJS = ("quiet", "amber", "mossy", "distant", "warm", "hollow", "patient", "grey")
_VERBS = ("glowed", "waited", "drifted", "settled", "lingered", "turned", "rested", "woke")
_TEMPLATES = (
    "The {a} {n} {v} beyond the {a2} {n2}.",
    "{a} light fell across the {a2} {n2} by the {n}.",
    "She {v} where the {a} {n} met the {a2} {n2}.",
    "No {n} {v} under the {a2} {n2} that evening.",
)


def _tone(seconds: float, freq: float, amplitude: float) -> np.ndarray:
    """Mono float32 sine of exact ``seconds`` at the bundle sample rate."""
    count = int(round(seconds * SAMPLE_RATE))
    t = np.arange(count, dtype=np.float64) / SAMPLE_RATE
    return (amplitude * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _beep_for(sentence: Sentence) -> np.ndarray:
    """Fake synth: short beep at the sentence start (alternating pitch)."""
    freq = BEEP_FREQS[sentence.sid % len(BEEP_FREQS)]
    return _tone(BEEP_MS / 1000.0, freq, BEEP_AMP)


def _tone_for(sentence: Sentence) -> np.ndarray:
    """Fake synth: ~0.4 s tone (pitch wobbles slightly so sentences differ)."""
    _ = sentence
    return _tone(TONE_MS / 1000.0, TONE_FREQ, TONE_AMP)


def sentence_text(index: int) -> str:
    """Deterministic real-looking sentence (Scribe trailing-space convention)."""
    template = _TEMPLATES[index % len(_TEMPLATES)]
    text = template.format(
        a=_ADJS[index % len(_ADJS)],
        n=_NOUNS[(index // 2) % len(_NOUNS)],
        v=_VERBS[(index // 3) % len(_VERBS)],
        a2=_ADJS[(index + 3) % len(_ADJS)],
        n2=_NOUNS[(index + 5) % len(_NOUNS)],
    )
    return text + " "


def build_chapter(title: str, texts: list[str]) -> ChapterFile:
    """Chapter model: one heading block plus para blocks of ``PARA_SIZE``."""
    sid = 1
    blocks = [
        Block(
            id=1,
            type="heading",
            level=1,
            sentences=[
                Sentence(sid=sid, speaker="narrator", start_ms=0, end_ms=0, text=title + " ")
            ],
        )
    ]
    sid += 1
    block_id = 2
    for start in range(0, len(texts), PARA_SIZE):
        sentences = [
            Sentence(sid=s, speaker="narrator", start_ms=0, end_ms=0, text=text)
            for s, text in zip(range(sid, sid + PARA_SIZE), texts[start : start + PARA_SIZE])
        ]
        sid += len(sentences)
        blocks.append(Block(id=block_id, type="para", sentences=sentences))
        block_id += 1
    return ChapterFile(
        spec_version=SPEC_VERSION, chapter=1, title=title, duration_ms=0, blocks=blocks
    )


def make_source_epub(path: Path, title: str, author: str, chapter_title: str, paras: int) -> Path:
    """Minimal valid EPUB (source copy + metadata for the writer)."""
    from ebooklib import epub as ebooklib_epub

    book = ebooklib_epub.EpubBook()
    book.set_identifier(f"auloud-ra-{path.stem}")
    book.set_title(title)
    book.set_language("en")
    book.add_author(author)
    body = f"<h1>{chapter_title}</h1>" + "".join(
        f"<p>Paragraph {n}.</p>" for n in range(1, paras + 1)
    )
    doc = ebooklib_epub.EpubHtml(title=chapter_title, file_name="ch1.xhtml", lang="en")
    doc.content = body
    book.add_item(doc)
    book.toc = (ebooklib_epub.Link("ch1.xhtml", chapter_title, "ch1"),)
    book.add_item(ebooklib_epub.EpubNcx())
    book.add_item(ebooklib_epub.EpubNav())
    book.spine = ["nav", doc]
    path.parent.mkdir(parents=True, exist_ok=True)
    ebooklib_epub.write_epub(str(path), book)
    return path


def build_bundle(
    name: str,
    title: str,
    texts: list[str],
    synth_fn: object,
    out_root: Path,
    work_dir: Path,
) -> Path:
    """Assemble, encode and write one synthetic bundle; returns its directory."""
    chapter = build_chapter(title, texts)
    assembled = assemble_chapter(chapter, synth_fn)  # type: ignore[arg-type]
    by_sid = {s.sid: s for block in (chapter.blocks or []) for s in block.sentences}
    for timing in assembled.timings:
        by_sid[timing.sid].start_ms = timing.start_ms
        by_sid[timing.sid].end_ms = timing.end_ms
    chapter.duration_ms = assembled.duration_ms
    mp3_path = work_dir / f"{name}.mp3"
    encode_assembled_chapter(assembled, mp3_path, chapter_label="ch001")
    epub_path = work_dir / f"{name}.epub"
    make_source_epub(
        epub_path, title, "Auloud test rig", title, paras=max(1, len(texts) // PARA_SIZE)
    )
    bundle_dir = out_root / name
    write_bundle([chapter], [mp3_path], epub_path, bundle_dir)
    return bundle_dir


def main() -> int:
    parser = argparse.ArgumentParser(description="Build Slice 3 read-along test bundles.")
    parser.add_argument(
        "--out",
        type=Path,
        default=Path(__file__).resolve().parent.parent.parent / "logs",
        help="output folder (default: repo logs/)",
    )
    args = parser.parse_args()
    out_root: Path = args.out
    work_dir = out_root / "ra-work"
    work_dir.mkdir(parents=True, exist_ok=True)

    started = time.monotonic()
    beep_texts = [sentence_text(i) for i in range(BEEP_SENTENCES - 1)]
    beep_dir = build_bundle("ra-beep", "Beep test", beep_texts, _beep_for, out_root, work_dir)
    beep_took = time.monotonic() - started
    print(f"beep bundle: {beep_dir} ({BEEP_SENTENCES} sentences, {beep_took:.1f} s)")

    started = time.monotonic()
    long_texts = [sentence_text(i) for i in range(LONG_SENTENCES - 1)]
    long_dir = build_bundle(
        "ra-long", "Long stress chapter", long_texts, _tone_for, out_root, work_dir
    )
    long_took = time.monotonic() - started
    print(f"long bundle: {long_dir} ({LONG_SENTENCES} sentences, {long_took:.1f} s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
