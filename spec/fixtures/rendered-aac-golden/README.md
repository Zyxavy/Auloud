# rendered-aac-golden (spec 2.0 part 2, Slice 10 RN1)

Hand-checkable tiny fixture for a device-rendered book: every chapter
rendered AAC-LC mono 24 kHz about 64 kbps in M4A, with per-chapter
`render_fingerprint` plus manifest `gain_db` and `encoder_offset_ms`.
Both sides consume it: Scribe pins it with the real `validate_bundle`
(including the real ffprobe AAC path), the Player with the real
`BundleParser`, `BundleValidator` and `ChapterTextLoader`.

Shape (spec `03-BundleSpec.md` sections 2-3, D-102):

- `manifest.json`: `spec_version` "2.0", `render_state` "complete",
  `audio.format` "m4a", `gain_db` per role in decibels,
  `encoder_offset_ms` 0, `voices` with the reserved `narrator` and
  `dialogue` keys, two chapter entries with `.m4a` audio, `duration_ms`
  and `render_fingerprint`.
- `text/ch001.json` (4 sentences, 4000 ms) and `text/ch002.json`
  (3 sentences, 3000 ms): `spec_version` "2.0", every sentence timed,
  sids run 1..N, speakers are `narrator`/`dialogue`.
- `audio/ch001.m4a` (440 Hz) and `audio/ch002.m4a` (520 Hz): real
  ffmpeg AAC encodes of sine tones (see provenance below).

Provenance: audio is `ffmpeg sine -> aac 64k mono 24k` (no speech, no
TTS model, nothing to license); sentence texts are original tiny lines
written for this fixture. `source/book.epub` is a synthetic placeholder;
the manifest `sha256` matches those bytes exactly. The bundle `id` is
the device-namespace id from the spec: UUIDv5 over
`auloud:device-book:<sha256-hex>`, so it can never collide with a
PC-rendered bundle of the same bytes.

Placeholders, not measurements: `encoder_offset_ms` 0 means the field
is present and shaped (RN10 measures the real platform-encoder constant
on the Tab E); `gain_db` values are illustrative (RN6/RN10 confirm how
real gains are derived); the fingerprint engine version is a placeholder
string (RN10 records the real Tab E strings). Regenerate with
`scribe export-render-fixtures` (deterministic: fixed tones, bitexact
mux, fixed JSON; the export encodes twice and refuses on any
difference).
