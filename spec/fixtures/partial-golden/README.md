# partial-golden: range-bundle contract (Slice 6 UI1, spec v1.2)

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

`53a97272-f146-524b-94e2-3aee0d397b2f` — UUIDv5 over source sha plus range `2-3`
(full id for this source would be `8df84e5d-49fc-53b9-9bc0-9b8c722abfe7`; range ids never collide
with it, so Player progress never points at the wrong chapter).

## Content (all synthetic — committable)

- Source chapters 2-3 rendered as bundle chapters 1-2
  (`source_index` 2, 3). Full-book rebuild keeps id `8df84e5d-49fc-53b9-9bc0-9b8c722abfe7`.
- Audio: tones, NOT speech.

## Manifest chapters

| bundle index | source_index | title | duration_ms |
| --- | --- | --- | --- |
| 1 | 2 | Chapter Two | 9900 |
| 2 | 3 | Chapter Three | 9900 |

`spec_version` 1.2. Audio sizes: `{'ch001.mp3': 79916, 'ch002.mp3': 79916}`.
(`created_at` differs on any rebuild by design — compare ignoring that key.)
