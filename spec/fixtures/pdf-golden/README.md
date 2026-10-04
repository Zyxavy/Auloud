# pdf-golden: PDF text-path contract bundle (Slice 5 CP6)

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

`4636f63e-50e5-576d-8cfc-c26e82d659d3` — UUIDv5 over the source PDF's sha256
(`ee1262f8418f44d7...`, see `manifest.json`). Same source
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

`duration_ms` 2150, title "Chapter 1". Sids run
1..4, first start 0, timings ordered with gaps.

| sid | block | speaker | start_ms | end_ms | page | text (verbatim) |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | — | narrator | 0 | 100 | 1 | `The amber lamp glowed softly above the quiet river bend.` |
| 2 | — | Alice | 600 | 700 | 1 | `"We should leave,"` |
| 3 | — | narrator | 950 | 1050 | 1 | ` Alice said.` |
| 4 | — | narrator | 1550 | 1650 | 2 | `Morning came at last over the mossy hill.` |

`pages` marks: `[{"page": 1, "start_ms": 0}, {"page": 2, "start_ms": 1550}]` (each `start_ms` is its
page's first sentence start).

## Sizes / durations

| file | duration | size |
| --- | --- | --- |
| `audio/ch001.mp3` | 2150 ms | 17900 bytes |
| `text/ch001.json` | 2150 ms | 1330 bytes |
| `source/book.pdf` | — | 1586 bytes |

`source/book.pdf` is part of the fixture: the manifest pins its sha256
and the Scribe validator checks it. (`created_at` records the build time
and differs on any rebuild by design — compare ignoring that key.)
