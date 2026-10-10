# The bundle contract

The bundle is a folder Scribe writes and the Player reads. Its layout and rules are the law both sides obey; the full text is `docs/03-BundleSpec.md` (copy at `spec/bundle.md`). This page is the shape of it, not a substitute.

```
<book>/
  manifest.json
  cover.jpg (optional)
  source/book.epub (or book.pdf)
  audio/ch001.mp3, ch002.mp3, ...
  text/ch001.json, ch002.json, ...
```

## The invariants that matter

- Audio is MP3, mono, 24 kHz, 64 kbps CBR, one file per chapter, encoded from one continuous buffer.
- All times are integer milliseconds. Sentence ids start at 1 and rise by 1 across the chapter.
- `0 <= start_ms < end_ms <= duration_ms`, ordered, no overlaps. Gaps are allowed.
- Every sentence `speaker` exists in `manifest.voices`.
- `duration_ms` in the manifest, the chapter JSON, and the MP3 agree within 50 ms.
- Text is UTF-8 NFC. Readers ignore unknown JSON keys, so the format can grow without breaking old players.
- A bad chapter is skipped with a message. It never crashes import or playback.

## Spec versions

`1.x` covers Scribe-written rendered books. `2.0` adds on-device books: `render_state` per chapter (`none`, `partial`, `complete`), placeholder voices, and device audio rules. The Player refuses books it cannot play with a plain message, and progress keys on the manifest `id` as chapter index plus milliseconds.

## Why a frozen contract

Scribe and Player ship separately (PC tool versus sideloaded APK, different languages, different release cadences). Without a versioned contract every change on one side risks silent breakage on the other. So the rule is mechanical: spec first, version bump for breaking changes, `docs/DECISIONS.md` entry, shared fixtures in `spec/fixtures/`, then code. `scribe validate` runs the checks on any bundle; both sides test against the same fixtures, including the bad ones (missing MP3, overlapping sentences, unknown speaker).
