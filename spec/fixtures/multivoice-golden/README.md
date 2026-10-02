# multivoice-golden: multi-voice contract bundle (Slice 4 MV8)

One-chapter bundle built by the REAL Scribe pipeline with a FAKE synth
engine. Both suites pin it: if either half drifts from the bundle
contract, one of them goes red.

- Scribe: `scribe/tests/test_mv8.py` runs the real SW2
  `validate_bundle` (real `ffprobe`, no stubs) and asserts valid, plus
  pins the book id, voices map and sentence speakers.
- Player: MV9 `ChapterTextLoader` contract test loads `text/ch001.json`
  and checks the layout/spacing round trip on the split sentences.

## How it was built

1. **MV6 draft (real):** a tiny synthetic EPUB (title "Multivoice Golden
   Miniature", author "Auloud Test", 1 chapter, two paragraphs, self-made
   text, one `<em>` span) went through real `draft.run_draft` (real spaCy
   attribution: `"We should leave," Alice said.` attributes Alice).
2. **MV7 build (real):** `build.run_build` with a fake engine (deterministic
   100 ms tones per sentence, voice-sensitive like the MV7 test engine),
   real `audio.assemble` + `audio.encode`, real `bundle.writer.write_bundle`
   with the MV8 resolved voices map.
3. **MV8 writer (real):** sentence speakers are the resolved character keys
   (`narrator`, `Alice`); manifest `voices` carries both (`am_onyx` +
   `bf_isabella` from the D-036 palette).

The one-off build script lived in the builder's Temp dir (same pattern as
`scribe-golden`); the MV7 fake-engine pattern in `test_mv7.py` reproduces it.

## Fixed book id

`9e86414e-92b7-53e5-8a1f-e4ac7dc9dde9` — UUIDv5 over the source EPUB's
sha256 (`baf81477...07f9890e`, see `manifest.json`). Same source bytes
always give the same id, so the Player keeps saved progress across
rebuilds. Both suites pin this id: replacing the fixture without updating
the tests fails loudly.

## Content (all synthetic — committable)

- Text: 3 self-made sentences (amber lamp narration with one italic span;
  `"We should leave,"` dialogue as Alice; ` Alice said.` narration tag
  with its authentic leading space — `"".join` reproduces the paragraph
  exactly, which is what the MV9 spacing test checks).
- Audio: tones, NOT speech. Anyone expecting narration is in the wrong
  fixture.

## Exact sentences (pinned by the MV9 Player test)

`duration_ms` 1550, title "Chapter One". Sids run 1..3, first start 0,
timings ordered with gaps (100-600, 700-950) and a tail (1050-1550).

| sid | block | speaker | start_ms | end_ms | text (verbatim) |
| --- | --- | --- | --- | --- | --- |
| 1 | 1 (para) | narrator | 0 | 100 | `The amber lamp glowed softly above the quiet river bend.` (italic span 22-28, "softly") |
| 2 | 2 (para) | Alice | 600 | 700 | `"We should leave,"` (no trailing space) |
| 3 | 2 (para) | narrator | 950 | 1050 | ` Alice said.` (leading space) |

Raw join of block 2 reproduces the paragraph exactly:
`"We should leave," Alice said.` (one space). Note for the display
layer: `layoutParagraph` inserts its trimmed-source fallback space after
sid 2 (ends in `"`), so the laid-out text shows a double space there
(`"We should leave,"  Alice said.`, ranges 0-19 / 19-31).

## Sizes / durations

| file | duration | size |
| --- | --- | --- |
| `audio/ch001.mp3` | 1550 ms | 13100 bytes |
| `text/ch001.json` | 1550 ms | 986 bytes |
| `source/book.epub` | — | 2168 bytes |

`source/book.epub` is part of the fixture: the manifest pins its sha256
and the Scribe validator checks it. (`created_at` records the build time
and differs on any rebuild by design — compare ignoring that key.)
