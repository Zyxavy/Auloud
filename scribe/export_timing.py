# Auloud Scribe - turns ebooks into multi-voice audiobooks (PC tool).
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

"""Deterministic shared assembly-timing vectors for Slice 10 RN2.

One artifact, written by :func:`export_all` and consumed by both sides:

- ``spec/fixtures/timing-vectors/timing-vectors.json``: per-case block
  structure (sentence kinds, native sample rates plus sample lengths,
  split-pair links) with the expected ``start_ms``/``end_ms`` per
  sentence, the pause after each sentence, and the chapter duration.
- ``spec/fixtures/timing-vectors/README.md``: schema, coverage and the
  rules RN5 must honor.

Every number below comes from the real code (``audio.assemble`` for
pauses and offsets, ``tts.base.resample_mono`` for native-to-bundle
lengths); the committed JSON is the contract afterwards. Scribe's own
suite (``tests/test_timing_vectors.py``) rebuilds each case from the
vectors and asserts the assembly matches; the Player (RN5) replays the
same arithmetic on the JVM with fakes.

Determinism: fixed native lengths and rates, constant audio (values
are irrelevant to timings), no timestamps, canonical JSON via the
shared ``export_ingest`` helpers (sorted keys, 2-space indent, UTF-8,
trailing newline). Re-running the export is byte-identical (pinned by
the freshness test).

Run from ``scribe/`` with ``uv run --no-sync scribe
export-timing-vectors`` (thin CLI wrapper in ``cli.py``), or ``uv run
--no-sync python export_timing.py`` directly.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

from audio.assemble import (
    PAUSE_BREAK_MS,
    PAUSE_HEADING_MS,
    PAUSE_PARA_MS,
    PAUSE_SENTENCE_MS,
    PAUSE_TAG_MS,
    assemble_chapter,
)
from bundle.models import Block, ChapterFile, Sentence
from export_ingest import canonical_json, default_fixtures_dir
from tts.base import SAMPLE_RATE, resample_mono

#: Version of the timing-vectors contract (bump on schema change).
TIMING_VECTORS_VERSION = 1

#: Directory name under ``spec/fixtures`` holding the vectors.
TIMING_VECTORS_DIRNAME = "timing-vectors"

CONVENTIONS = (
    "For each case: resample every sentence to 24000 Hz (Scribe "
    "linear-interp resample_mono; only the committed resampled_samples "
    "length affects timings, audio values are arbitrary), concatenate in "
    "document order with pauses (sentence 250 inside para/quote blocks, "
    "para 500 at para/quote block end, heading 800 after every heading "
    "sentence, break 1000 as standalone silence, tag 100 between adjacent "
    "same-block sentences sharing a split_pair; a break before the first "
    "sentence contributes nothing so the first start_ms is 0), then read "
    "start_ms/end_ms from running sample counts at 24000 Hz with "
    "round-half-even to integer ms. end_ms excludes the trailing pause; "
    "duration_ms covers the whole buffer including the final pause; "
    "pause_after_ms of a sentence is the gap to the next start_ms, or to "
    "duration_ms for the last sentence (composite across a block-final "
    "pause plus any following break silence)."
)


class ExportTimingError(ValueError):
    """The timing-vectors export cannot proceed (bad case or bad target)."""


@dataclass(frozen=True)
class VectorSentence:
    """One vector sentence: kind plus native audio shape.

    ``samples`` is the native length at ``sample_rate``; the exporter
    runs the real ``resample_mono`` to 24000 Hz and commits the result
    as ``resampled_samples``. ``split_pair`` links the halves of one
    quote-split sentence (else None).
    """

    sid: int
    kind: str
    sample_rate: int
    samples: int
    split_pair: int | None = None


@dataclass(frozen=True)
class VectorBlock:
    """One vector block: id plus type plus its sentences in order."""

    id: int
    type: str
    sentences: tuple[VectorSentence, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class TimingCase:
    """One timing vector: block structure plus the rule it pins.

    ``expected`` is never stored here: the exporter computes it with
    the real assembly, so the committed JSON always matches verified
    code at export time.
    """

    id: str
    notes: str
    blocks: tuple[VectorBlock, ...] = field(default_factory=tuple)


def _s(rate: int, samples: int, sid: int, kind: str = "narration") -> VectorSentence:
    """Shorthand for a plain (unpaired) vector sentence."""
    return VectorSentence(sid=sid, kind=kind, sample_rate=rate, samples=samples)


#: Canonical vector inputs. Native rates mirror real engine outputs
#: (16000 Piper lessac-low, 22050 common TTS, 44100 CD, 8000 low-rate,
#: 24000 bundle-native); exact-ms cases resample to multiples of 24
#: samples, while rounding-half-even lands on exact .5 ms boundaries.
TIMING_CASES: tuple[TimingCase, ...] = (
    TimingCase(
        id="sentence-pause",
        notes="Para-internal gap is the 250 ms sentence pause.",
        blocks=(
            VectorBlock(
                id=1,
                type="para",
                sentences=(_s(24000, 48000, 1), _s(24000, 24000, 2)),
            ),
        ),
    ),
    TimingCase(
        id="para-final",
        notes="Single para sentence: end_ms excludes the 500 ms trailing pause.",
        blocks=(VectorBlock(id=1, type="para", sentences=(_s(24000, 2400, 1),)),),
    ),
    TimingCase(
        id="heading-pause",
        notes="Heading sentence takes the 800 ms heading pause.",
        blocks=(
            VectorBlock(id=1, type="heading", sentences=(_s(24000, 24000, 1),)),
        ),
    ),
    TimingCase(
        id="quote-like-para",
        notes="Quote blocks pause like para: 250 inside, 500 at block end.",
        blocks=(
            VectorBlock(
                id=1,
                type="quote",
                sentences=(_s(24000, 2400, 1), _s(24000, 2400, 2)),
            ),
        ),
    ),
    TimingCase(
        id="break-mid",
        notes="Mid-chapter break adds 1000 ms silence between block pauses.",
        blocks=(
            VectorBlock(id=1, type="para", sentences=(_s(24000, 2400, 1),)),
            VectorBlock(id=2, type="break"),
            VectorBlock(id=3, type="para", sentences=(_s(24000, 2400, 2),)),
        ),
    ),
    TimingCase(
        id="break-trailing",
        notes="Trailing break keeps its 1000 ms; duration includes it.",
        blocks=(
            VectorBlock(id=1, type="para", sentences=(_s(24000, 2400, 1),)),
            VectorBlock(id=2, type="break"),
        ),
    ),
    TimingCase(
        id="leading-break-drop",
        notes="Leading break silence is dropped; the first start_ms is 0.",
        blocks=(
            VectorBlock(id=1, type="break"),
            VectorBlock(id=2, type="para", sentences=(_s(24000, 2400, 1),)),
        ),
    ),
    TimingCase(
        id="tag-split",
        notes="Split-pair halves take the 100 ms tag pause; other gaps normal.",
        blocks=(
            VectorBlock(
                id=1,
                type="para",
                sentences=(
                    VectorSentence(
                        sid=1, kind="dialogue", sample_rate=24000, samples=24000,
                        split_pair=1,
                    ),
                    VectorSentence(
                        sid=2, kind="narration", sample_rate=24000, samples=12000,
                        split_pair=1,
                    ),
                    _s(24000, 24000, 3),
                ),
            ),
        ),
    ),
    TimingCase(
        id="mixed-rates",
        notes="Four native rates resample to exact 500 ms each at 24000 Hz.",
        blocks=(
            VectorBlock(
                id=1,
                type="para",
                sentences=(
                    _s(16000, 8000, 1),
                    VectorSentence(
                        sid=2, kind="dialogue", sample_rate=22050, samples=11025,
                    ),
                    _s(44100, 22050, 3),
                    _s(8000, 4000, 4),
                ),
            ),
        ),
    ),
    TimingCase(
        id="rounding-half-even",
        notes="Exact .5 ms boundaries round half-even (100.5 to 100, not 101).",
        blocks=(
            VectorBlock(
                id=1,
                type="para",
                sentences=(_s(22050, 2216, 1), _s(24000, 2400, 2)),
            ),
        ),
    ),
)


def _speaker_for(kind: str) -> str:
    """Reserved 2.0 speaker for a sentence kind (documentary only)."""
    if kind == "dialogue":
        return "dialogue"
    if kind == "narration":
        return "narrator"
    raise ExportTimingError(f"vector sentence kind must be narration/dialogue, got {kind!r}.")


def resampled_length(samples: int, sample_rate: int) -> int:
    """Length at 24000 Hz via the real resample (values are arbitrary)."""
    if sample_rate <= 0:
        raise ExportTimingError(f"vector sample_rate must be positive, got {sample_rate}.")
    if samples <= 0:
        raise ExportTimingError(f"vector samples must be positive, got {samples}.")
    native = np.full((samples,), 0.5, dtype=np.float32)
    return int(resample_mono(native, int(sample_rate), SAMPLE_RATE).size)


def build_case_expectation(case: TimingCase) -> dict[str, object]:
    """Run one case through resample plus assembly; return JSON-able expectation."""
    if not case.blocks:
        raise ExportTimingError(f"case {case.id!r}: no blocks")
    resampled_by_sid: dict[int, np.ndarray] = {}
    out_blocks: list[dict[str, object]] = []
    sentence_sids: list[int] = []
    chapter_blocks: list[Block] = []
    for block in case.blocks:
        if block.type not in ("heading", "para", "quote", "break"):
            raise ExportTimingError(f"case {case.id!r}: unknown block type {block.type!r}")
        if block.type == "break" and block.sentences:
            raise ExportTimingError(f"case {case.id!r}: break block carries no sentences")
        rows: list[dict[str, object]] = []
        sentences: list[Sentence] = []
        for spec in block.sentences:
            if spec.kind not in ("narration", "dialogue"):
                raise ExportTimingError(
                    f"case {case.id!r}: bad kind {spec.kind!r} (need narration/dialogue)"
                )
            native_len = resampled_length(spec.samples, spec.sample_rate)
            resampled_by_sid[spec.sid] = np.full((native_len,), 0.5, dtype=np.float32)
            rows.append(
                {
                    "sid": spec.sid,
                    "kind": spec.kind,
                    "speaker": _speaker_for(spec.kind),
                    "sample_rate": int(spec.sample_rate),
                    "samples": int(spec.samples),
                    "resampled_samples": int(native_len),
                    "split_pair": spec.split_pair,
                }
            )
            sentences.append(
                Sentence(
                    sid=spec.sid,
                    speaker=_speaker_for(spec.kind),
                    start_ms=0,
                    end_ms=0,
                    text=f"Vector {case.id} sentence {spec.sid}.",
                    split_pair=spec.split_pair,
                )
            )
            sentence_sids.append(spec.sid)
        out_blocks.append({"id": block.id, "type": block.type, "sentences": rows})
        chapter_blocks.append(Block(id=block.id, type=block.type, sentences=sentences))
    chapter = ChapterFile(
        spec_version="1.0", chapter=1, title=f"Vector {case.id}", duration_ms=0,
        blocks=chapter_blocks,
    )

    def _synth(sentence: Sentence) -> np.ndarray:
        return resampled_by_sid[sentence.sid]

    try:
        out = assemble_chapter(chapter, _synth)
    except ValueError as exc:
        raise ExportTimingError(f"case {case.id!r}: assembly failed: {exc}") from exc
    timings = [
        {"sid": t.sid, "start_ms": t.start_ms, "end_ms": t.end_ms, "pause_after_ms": 0}
        for t in out.timings
    ]
    for pos, row in enumerate(timings):
        if pos + 1 < len(timings):
            row["pause_after_ms"] = int(timings[pos + 1]["start_ms"]) - int(row["end_ms"])
        else:
            row["pause_after_ms"] = int(out.duration_ms) - int(row["end_ms"])
    if sentence_sids != [t["sid"] for t in timings]:
        raise ExportTimingError(f"case {case.id!r}: sid order drifted through assembly")
    return {
        "blocks": out_blocks,
        "expected": {
            "timings": timings,
            "duration_ms": int(out.duration_ms),
            "sample_count": int(out.sample_count),
        },
    }


def build_timing_vectors() -> dict[str, object]:
    """Build the timing-vectors document (JSON-able, deterministic order)."""
    if int(SAMPLE_RATE) != 24000:
        raise ExportTimingError(f"bundle rate must be 24000, got {SAMPLE_RATE}.")
    cases: list[dict[str, object]] = []
    seen: set[str] = set()
    for case in TIMING_CASES:
        if case.id in seen:
            raise ExportTimingError(f"duplicate timing case id {case.id!r}")
        seen.add(case.id)
        expectation = build_case_expectation(case)
        cases.append(
            {
                "id": case.id,
                "notes": case.notes,
                "blocks": expectation["blocks"],
                "expected": expectation["expected"],
            }
        )
    return {
        "version": TIMING_VECTORS_VERSION,
        "conventions": CONVENTIONS,
        "bundle_rate_hz": int(SAMPLE_RATE),
        "pauses_ms": {
            "sentence": int(PAUSE_SENTENCE_MS),
            "para": int(PAUSE_PARA_MS),
            "heading": int(PAUSE_HEADING_MS),
            "break": int(PAUSE_BREAK_MS),
            "tag": int(PAUSE_TAG_MS),
        },
        "cases": cases,
    }


TIMING_VECTORS_README = """# timing-vectors: shared assembly-timing vectors (Slice 10 RN2)

Pause and offset data for the on-device assembly port (RN5). Generated
from Scribe's real code (`audio.assemble` for pauses and offsets,
`tts.base.resample_mono` for native-to-bundle lengths), so the Kotlin
port compares against true Scribe behavior instead of hand-written
guesses. Scribe's own suite pins these files fresh on every run
(`scribe/tests/test_timing_vectors.py`); a code change without
re-export fails loudly.

## Files

- `timing-vectors.json`: one case per pause rule plus mixed-rate and
  rounding cases (block structure with sentence kinds, native rates
  plus lengths and split-pair links; expected `start_ms`/`end_ms` per
  sentence, the pause after each sentence, chapter `duration_ms`).
- This README (hand-written shape, committed content regenerated by
  the exporter).

## Vector schema

```json
{
  "version": 1,
  "bundle_rate_hz": 24000,
  "pauses_ms": {"sentence": 250, "para": 500, "heading": 800,
                "break": 1000, "tag": 100},
  "cases": [
    {"id": "sentence-pause", "notes": "<rule pinned>",
     "blocks": [
       {"id": 1, "type": "para", "sentences": [
         {"sid": 1, "kind": "narration", "speaker": "narrator",
          "sample_rate": 24000, "samples": 48000,
          "resampled_samples": 48000, "split_pair": null}
       ]}
     ],
     "expected": {
       "timings": [{"sid": 1, "start_ms": 0, "end_ms": 2000,
                    "pause_after_ms": 250}],
       "duration_ms": 3750, "sample_count": 90000}
    }
  ]
}
```

- `samples` is the native length at `sample_rate`; `resampled_samples`
  is the committed length at 24000 Hz (only lengths affect timings;
  audio values are arbitrary). `split_pair` links the halves of one
  quote-split sentence (else null). `pause_after_ms` is the gap to the
  next `start_ms`, or to `duration_ms` for the last sentence
  (composite across a block-final pause plus any following break).
- All ms, sample and rate fields are integers; no floats, no
  timestamps. `speaker` is documentary (assembly never reads it).

## Coverage

| Case | Rule pinned |
|---|---|
| `sentence-pause` | 250 ms inside para |
| `para-final` | 500 ms trailing; end excludes pause, duration includes it |
| `heading-pause` | 800 ms after a heading sentence |
| `quote-like-para` | quote pauses exactly like para (250/500) |
| `break-mid` | 1000 ms mid-chapter silence between block pauses |
| `break-trailing` | trailing 1000 ms kept in duration |
| `leading-break-drop` | leading break dropped; first start is 0 |
| `tag-split` | 100 ms between split-pair halves; normal pauses elsewhere |
| `mixed-rates` | 16/22.05/44.1/8 kHz natives land on exact 500 ms each |
| `rounding-half-even` | exact .5 ms boundaries round half-even, not half-up |

## Regenerating

Run from `scribe/` with `uv run --no-sync scribe export-timing-vectors`
(library logic in `scribe/export_timing.py`, thin CLI wrapper in
`scribe/cli.py`). Output is deterministic: fixed native lengths and
rates, constant audio, sorted keys, no timestamps. Running it twice
gives byte-identical files; verify with `git status` (clean after a
re-run) or the double-run test in `test_timing_vectors.py`.

If the installed `scribe` copy is stale (new command 404s), reinstall
first with `uv sync --extra ui` from `scribe/`, or bypass the install
with the direct fallback `uv run --no-sync python export_timing.py`
from `scribe/`.

## Rules RN5 must honor

Identical pauses (D-094); ms from running sample counts at 24000 Hz
with round-half-even (Python `round`, JVM `Math.rint`: Kotlin
`Math.round` is half-up and gives a different answer on the
`rounding-half-even` case); resample lengths by `round(n * dst / src)`
half-even on doubles; pause samples by exact integer math
(`ms * rate // 1000`); leading-break silence drop; split pairs
recomputed on device via `DialogueTagger` (the bundle carries no
split-pair fields by design).
"""


def export_all(fixtures_dir: Path | str | None = None) -> list[Path]:
    """Write the timing vectors plus README; return written paths.

    :param fixtures_dir: ``spec/fixtures`` dir (default: the repo one).
    """
    root = Path(fixtures_dir) if fixtures_dir is not None else default_fixtures_dir()
    if not root.is_dir():
        raise ExportTimingError(f"fixtures dir not found: {root}")
    target = root / TIMING_VECTORS_DIRNAME
    target.mkdir(parents=True, exist_ok=True)
    vectors_path = target / "timing-vectors.json"
    vectors_path.write_text(canonical_json(build_timing_vectors()), encoding="utf-8")
    readme_path = target / "README.md"
    readme_path.write_text(TIMING_VECTORS_README, encoding="utf-8")
    return [vectors_path, readme_path]


def main(argv: list[str] | None = None) -> int:
    """Entry point for ``python export_timing.py`` (the CLI wraps this)."""
    parser = argparse.ArgumentParser(description="Export shared Slice 10 timing vectors.")
    parser.add_argument(
        "--fixtures-dir",
        type=Path,
        default=None,
        help="spec/fixtures dir (default: <repo-root>/spec/fixtures).",
    )
    args = parser.parse_args(argv)
    try:
        written = export_all(args.fixtures_dir)
    except ExportTimingError as exc:
        print(f"export failed: {exc}", file=sys.stderr)
        return 1
    for path in written:
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
