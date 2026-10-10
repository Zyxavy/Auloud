# unrendered-golden (spec 2.0, Slice 9 IN1)

Hand-written tiny fixture for an unrendered book: text plus sentence
structure, no audio and no timings. Both sides consume it: Scribe pins it
with the real `validate_bundle`, the Player with the real `BundleParser`,
`BundleValidator` and `ChapterTextLoader`.

Shape (spec `03-BundleSpec.md` section 3, D-082):

- `manifest.json`: `spec_version` "2.0", `render_state` "none", no `audio`
  object, `voices` with the reserved `narrator` and `dialogue` keys
  (placeholder `system`/`default` values until Slice 10 rendering), two
  chapter entries with `text` only (no `audio`, no `duration_ms`).
- `text/ch001.json` (4 sentences) and `text/ch002.json` (3 sentences):
  `spec_version` "2.0", no `duration_ms`, sentences with `sid`,
  `speaker` and `text` only (no `start_ms`/`end_ms`). sids run 1..N
  across the blocks. ch001 sid 3 carries one italic span (proves spans
  work without timings).
- No `audio/` directory at all.

Provenance: the sentences are original tiny text written for this
fixture (no book excerpt, nothing to license). `source/book.epub` is a
synthetic placeholder (34 ASCII bytes,
`auloud-unrendered-golden-source-v1`); the manifest `sha256` matches
those bytes exactly. The bundle `id` is the device-namespace id from
the spec: UUIDv5 over `auloud:device-book:<sha256-hex>`
(`f8be80a6-3e94-56fa-9fb7-c88444bf239d`), so it can never collide with
a PC-rendered bundle of the same bytes.

Pinned: spec "2.0", `render_state` "none", 2 chapters, 7 sentences
(5 narration including the 2 headings, 2 dialogue).
