# scribe-golden: contract bundle (Slice 2 SW10)

Two-chapter bundle built by the REAL Scribe pipeline with a FAKE synth
engine. Both suites pin it: if either half drifts from the bundle contract,
one of them goes red.

- Scribe: `scribe/tests/test_scribe_golden.py` runs the real SW2
  `validate_bundle` (real `ffprobe`, no stubs) and asserts valid.
- Player: `ScribeGoldenContractTest` parses it with the real
  `BundleParser` and checks it with the real `BundleValidator`.

## How it was built

1. **SW5 draft (real):** a tiny synthetic EPUB (title "Scribe Golden
   Bundle", author "Auloud Test", 2 chapters, each a heading + one
   paragraph cycling invented sentences) went through real
   `draft.run_draft`. Each paragraph repeats invented sentences to ~250
   words so both chapters clear the 200-word tiny-merge with two chapters
   intact.
2. **SW7 assemble (real):** `audio.assemble.assemble_chapter` with a fake
   synth — deterministic 100 ms sine tone per sentence
   (440 Hz + 110 Hz steps, no TTS models), then `apply_loudness_gain`.
3. **SW7 encode (real):** `audio.encode.encode_assembled_chapter`
   (one streaming `ffmpeg` process, mono 24 kHz 64 kbps CBR) plus the
   ffprobe duration-agreement check.
4. **SW8 writer (real):** `bundle.writer.write_bundle`, which copies the
   source EPUB, writes manifest + chapter JSON, and runs the SW2 validator
   gate before returning.

The one-off build script is not committed (it lived in the builder's Temp
dir); the commands above plus the repo's `test_build.py` fake-engine
pattern reproduce it.

## Fixed book id

`6d23921c-b53a-5429-964c-05c42413ce93` — UUIDv5 over the source EPUB's
sha256 (`a5963254...59d88b`, see `manifest.json`). Same source bytes always
give the same id, so the Player keeps saved progress across rebuilds.
Both suites pin this id: replacing the fixture without updating the tests
fails loudly.

## Content (all synthetic — committable)

- Text: 4 invented sentences (amber lamp, brindled cat, old ferry, mossy
  wall) cycled 6x per chapter in different orders; no real book text.
  Sentence texts keep SW4's original spacing (trailing spaces included) —
  that is authentic pipeline output, not a typo.
- Audio: sine tones, NOT speech. Anyone expecting narration is in the
  wrong fixture.

## Sizes / durations

| file | duration | size |
| --- | --- | --- |
| `audio/ch001.mp3` | 7450 ms | 60332 bytes |
| `audio/ch002.mp3` | 7450 ms | 60332 bytes |
| `text/ch001.json` | 7450 ms | 4674 bytes |
| `text/ch002.json` | 7450 ms | 4692 bytes |
| `source/book.epub` | — | 2775 bytes |

`source/book.epub` is part of the fixture: the manifest pins its sha256
and the Scribe validator checks it. (`created_at` records the build time
and differs on any rebuild by design — compare ignoring that key.)
