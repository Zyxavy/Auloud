# partial-aac-golden (spec 2.0 part 2, Slice 10 RN1)

Hand-checkable tiny fixture for a partially rendered book: chapter 1
rendered AAC-LC mono 24 kHz about 64 kbps in M4A with a
`render_fingerprint`, chapter 2 unrendered (text plus sentence
structure, no audio, no timings), `render_state` "partial". Both sides
consume it: Scribe pins it with the real `validate_bundle`, the Player
with the real `BundleParser`, `BundleValidator` and
`ChapterTextLoader`.

Shape (spec `03-BundleSpec.md` sections 2-3, D-102):

- `manifest.json`: `spec_version` "2.0", `render_state` "partial",
  `audio.format` "m4a", `gain_db` per role in decibels,
  `encoder_offset_ms` 0. Chapter 1 carries `.m4a` audio, `duration_ms`
  and `render_fingerprint`; chapter 2 carries `text` only.
- `text/ch001.json` (3 sentences, 3000 ms, every sentence timed) and
  `text/ch002.json` (2 sentences, no timings).
- `audio/ch001.m4a` (660 Hz): a real ffmpeg AAC encode of a sine tone.
  No `audio/ch002` file exists.

Provenance: same as `rendered-aac-golden` (synthetic tones, original
tiny texts, placeholder source bytes with matching sha256,
device-namespace bundle id). Placeholders `encoder_offset_ms` 0,
illustrative `gain_db`, placeholder fingerprint engine version (see the
rendered golden README). Regenerate with
`scribe export-render-fixtures`.
