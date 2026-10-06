# Bundle Spec v2.0

The **book bundle** is the contract between Scribe (PC) and the Player (Android). Scribe writes it; the Player only reads it. Change this document before changing either program.

Changelog: v2.0 adds unrendered books (D-082, Slice 9 IN1): text plus sentence structure with no audio and no timings. A `render_state` field (`none`, `partial`, `complete`) makes `audio`, `duration_ms`, `start_ms` and `end_ms` conditional; on-device speakers are the reserved keys `narrator` and `dialogue`. Because required fields become conditional, this is a major version. Readers accept `spec_version` "1.0", "1.1", "1.2" and "2.0". Scribe writes "2.0" only for unrendered or partially rendered books; fully rendered PC books keep writing "1.1" (full) or "1.2" (range). v1.2 was additive only for range builds (D7). A range render lists only rendered chapters, renumbered consecutively 1..K, each MAY carry `source_index` (1-based chapter number in the source book), and the bundle `id` is derived from the source hash **plus the range** (full builds keep the existing id). Old Players ignore the extra field (`ignoreUnknownKeys`). No existing required field changes in v1.2. Under v1.2, Scribe wrote "1.1" for full builds (no `source_index`, byte-identical to v1.1) and "1.2" for range builds (with `source_index`). v2.0 part 2 (RN1, D-102) adds device rendering, additive inside 2.0 with no version bump: AAC-LC in M4A as a second audio entry alongside MP3 (mixed books legal, per-chapter format from the file extension), an optional per-chapter `render_fingerprint`, an optional manifest `gain_db` per role, an optional manifest `encoder_offset_ms`, and partial-book playlist/progress rules at spec level only. The AAC duration tolerance is provisional until the RN10 beep measurement confirms or loosens it.

## 1. Folder layout

```
<BookName>/
  manifest.json
  cover.jpg                # optional
  source/book.epub         # the original file, unchanged (or book.pdf)
  audio/ch001.mp3          # or ch001.m4a for device-rendered chapters (section 2)
  audio/ch002.mp3
  text/ch001.json
  text/ch002.json
```

Rules:

- Folder and file names use ASCII, lowercase for generated files; chapter numbers are zero-padded to 3 digits (`ch001`), or 4 if a book has more than 999 chapters.
- All text files are UTF-8 (NFC normalized), with `\n` line endings.
- All times are **integer milliseconds** (rendered chapters only; unrendered chapters carry no times).
- `text/chNNN.json` files always exist: text plus sentence structure is present even with no audio.
- Audio files (`audio/chNNN.mp3` for MP3 chapters, `audio/chNNN.m4a` for AAC chapters, section 2) exist only for rendered chapters. A `none` book (section 3) has no `audio/` files at all; a `partial` book has audio only for its rendered chapters.
- The Player ignores unknown fields, so newer Scribe versions can add fields without breaking older Players. A change of the major number in `spec_version` (e.g. 2.0) means breaking changes.

## 2. Audio

| Property | MP3 chapters | AAC chapters (2.0 part 2) |
| --- | --- | --- |
| Container / codec | MP3 (MPEG Layer III), `.mp3` | AAC-LC in M4A, `.m4a` |
| Channels | 1 (mono) | 1 (mono) |
| Sample rate | 24000 Hz (22050 Hz also acceptable) | 24000 Hz (22050 Hz also acceptable) |
| Bitrate | 64 kbps, **constant (CBR)** | about 64 kbps, constrained average (see below) |
| One file per chapter | Yes; encode from one continuous buffer | Yes; encode from one continuous buffer |

For MP3, CBR keeps seeking and timestamps accurate; variable bitrate can make them drift. Do not add silence padding at chapter boundaries beyond what `duration_ms` reflects.

AAC has no frame-level CBR the way MP3 does, so the spec states the AAC bitrate honestly: the encoder runs constrained targeting 64 kbps mono, and validation checks the *average* bitrate against 64 kbps within tolerance (section 7), never frame sizes. Manifest `cbr: true` on an AAC book means the constrained setting was used, not that frames are identical.

Per-chapter format: a chapter's format comes from its `audio` path extension (`.mp3` or `.m4a`, 1.x books always `.mp3`); the extension must agree with the probed codec (section 7). Mixed books (some chapters MP3 from Scribe, some M4A from the device) are legal: the manifest `audio` object carries the shared channel/rate/bitrate params, its `format` matches at least one rendered chapter, and each chapter is checked against its own extension. Per-chapter extension plus probe are authoritative, never the manifest value alone.

Section 2 applies to rendered chapters only. Unrendered books (`render_state` `none`, section 3) carry no audio at all and omit the manifest `audio` object. Partial books (`partial`) carry audio only for their rendered chapters; the manifest `audio` object is present for `partial` and `complete` books.

## 3. `manifest.json`

```json
{
  "spec_version": "1.1",
  "id": "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
  "title": "Example Novel",
  "author": "A. Author",
  "language": "en",
  "type": "epub",
  "source": { "file": "source/book.epub", "sha256": "<hex>" },
  "cover": "cover.jpg",
  "audio": { "format": "mp3", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true },
  "voices": {
    "narrator": { "engine": "kokoro", "voice": "af_sarah", "speed": 1.0, "pitch": 1.0 },
    "Ana":      { "engine": "kokoro", "voice": "bf_emma",  "speed": 1.0, "pitch": 1.05 }
  },
  "chapters": [
    { "index": 1, "title": "Chapter One", "audio": "audio/ch001.mp3", "text": "text/ch001.json", "duration_ms": 1832400 },
    { "index": 2, "title": "Chapter Two", "audio": "audio/ch002.mp3", "text": "text/ch002.json", "duration_ms": 1640100 }
  ],
  "created_at": "2026-09-29T10:00:00Z",
  "generator": "scribe 0.1.0"
}
```

Required fields (1.x): `spec_version`, `id`, `title`, `type`, `audio`, `chapters` (each with `index`, `title`, `audio`, `text`, `duration_ms`). In 2.0 the manifest `audio` and the per-chapter `audio`/`duration_ms` are conditional (see below); everything else keeps its 1.x status. Everything else is optional. `id` is a UUID that never changes for a given book; the Player keys saved progress by it. Range builds (Slice 6 D7): the bundle lists only rendered chapters, renumbered consecutively 1..K (`index` and file stems `ch001` follow the bundle order, not the source order). Each entry MAY carry `source_index` (1-based chapter number in the source book; absent for full builds, never null). The bundle `id` for a range build is UUIDv5 over `auloud:book:<sha256-hex>:chapters:<a-b,c>` (source hash plus the selected source indices); a full build keeps the existing id (UUIDv5 over `auloud:book:<sha256-hex>`), so the Player keeps saved progress across rebuilds and different ranges never collide.

Spec 2.0 unrendered books (Slice 9 IN1, D-082): a book with text and sentence structure but no audio and no timings. The manifest carries `render_state`, one of `none` (no chapter rendered), `partial` (some chapters rendered) or `complete` (every chapter rendered). `render_state` is required in 2.0 manifests and absent (never null) in 1.x manifests. Conditional fields in 2.0, by per-chapter rendered state: a rendered chapter entry carries `audio` (non-blank path) and `duration_ms` (positive integer) exactly as in 1.x; an unrendered chapter entry omits both keys (absent, never null) and always carries `text`. The manifest `audio` object is omitted for `none` books and present for `partial` and `complete` books. Per-chapter state is inferred from the presence of `duration_ms`; the manifest `render_state` must agree with it (`none`: every chapter unrendered; `partial`: at least one of each; `complete`: every chapter rendered).

```json
{
  "spec_version": "2.0",
  "render_state": "none",
  "id": "d82207f2-850a-5c9a-9d4e-1b2c3d4e5f60",
  "title": "Unrendered Example",
  "type": "epub",
  "source": { "file": "source/book.epub", "sha256": "<hex>" },
  "voices": {
    "narrator": { "engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0 },
    "dialogue": { "engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0 }
  },
  "chapters": [
    { "index": 1, "title": "Chapter One", "text": "text/ch001.json" },
    { "index": 2, "title": "Chapter Two", "text": "text/ch002.json" }
  ],
  "created_at": "2026-09-29T10:00:00Z",
  "generator": "auloud-player 2.0"
}
```

Device namespace ids: books imported on the device derive `id` as UUIDv5 over `auloud:device-book:<sha256-hex>` (same UUID namespace as Scribe, different prefix), so a PC-rendered bundle of the same EPUB appears as a separate library entry instead of clobbering the on-device book. Range suffixes (`:chapters:<a-b,c>`) work the same way under the device prefix when partial rendering later selects chapters.

Spec 2.0 part 2 device-rendering fields (RN1, D-102; all optional, all ignored by readers):

- `audio.format` is `mp3` or `m4a`. In a mixed book (section 2) it matches at least one rendered chapter.
- `gain_db` (manifest, 2.0 only): one number per role key (`narrator`, `dialogue`), in decibels, derived from the first rendered chapter and applied at assembly of later chapters before the per-chapter peak cap. Readers ignore it (loudness is baked into the audio); it exists so re-renders and audits reproduce levels. Absent means no book-level gain recorded.
- `encoder_offset_ms` (manifest, 2.0 only): integer milliseconds of measured encoder delay, applied to timings when they are written. `0` means measured zero; absent means unknown or not measured. Readers ignore it.
- `render_fingerprint` (rendered chapter entries only, 2.0 only, never on unrendered chapters): `{engine, voices, speeds, engine_versions}` where `engine` is the namespaced engine id (for example `system`), `voices` maps each role to the voice id used, `speeds` maps each role to its speed multiplier, and `engine_versions` maps each engine used to its version string (the same strings the synth cache keys on). Slice 11 compares it to current settings to mark stale chapters. Absent or unknown means fingerprint unknown, never up to date.

```json
{
  "spec_version": "2.0",
  "render_state": "partial",
  "audio": { "format": "m4a", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true },
  "gain_db": { "narrator": -1.5, "dialogue": 0.5 },
  "encoder_offset_ms": 0,
  "chapters": [
    { "index": 1, "title": "Chapter One", "audio": "audio/ch001.m4a", "text": "text/ch001.json", "duration_ms": 4000,
      "render_fingerprint": {
        "engine": "system",
        "voices": { "narrator": "default", "dialogue": "default" },
        "speeds": { "narrator": 1.0, "dialogue": 1.05 },
        "engine_versions": { "system": "placeholder (RN10 records the real Tab E strings)" } } },
    { "index": 2, "title": "Chapter Two", "text": "text/ch002.json" }
  ]
}
```

Reserved speakers: every sentence in a 2.0 book uses `narrator` (narration) or `dialogue` (quoted speech) as its `speaker`, and `voices` always contains both keys. The engine/voice values are placeholders until Slice 10 rendering; only their shape (non-blank engine and voice, speed and pitch above 0) is validated. Extra voices MAY be present but are unused; 1.x multi-voice books are unaffected.

## 4. Chapter file (EPUB books): `text/chNNN.json`

```json
{
  "spec_version": "1.1",
  "chapter": 1,
  "title": "Chapter One",
  "duration_ms": 1832400,
  "blocks": [
    { "id": 1, "type": "heading", "level": 1, "text": "Chapter One" },
    { "id": 2, "type": "para", "sentences": [
      { "sid": 1, "speaker": "narrator", "start_ms": 0,    "end_ms": 4200, "text": "The rain had not stopped for three days." },
      { "sid": 2, "speaker": "Ana",      "start_ms": 4200, "end_ms": 6100, "text": "\"We should leave,\" she said." } ] },
    { "id": 3, "type": "break" }
  ]
}
```

Block types:

| type | Fields |
| --- | --- |
| `heading` | `level` (1-3), `text`; may also carry `sentences` if it is read aloud |
| `para` | `sentences[]` |
| `quote` | `sentences[]` (block quote) |
| `break` | none (scene break, shown as a divider) |

Sentence fields:

| field | required | meaning |
| --- | --- | --- |
| `sid` | yes | integer, starts at 1 and increases by 1 through the chapter (across blocks) |
| `speaker` | yes | key in `manifest.voices` (`"narrator"` for narration; in 2.0 books only `"narrator"` or `"dialogue"`, section 3) |
| `start_ms`, `end_ms` | 1.x yes; 2.0 conditional | position in this chapter's audio file (MP3 or M4A). Present with integer timings for rendered chapters; omitted (absent, never null) for every sentence of an unrendered chapter. Within one chapter file, either every sentence carries both keys or none does. |
| `text` | yes | display text, UTF-8 |
| `spans` | no | inline formatting: `[{"start": 0, "end": 3, "style": "italic"}]`, character offsets into `text`; styles: `italic`, `bold` |
| `page` | no | 1-based source page in `source/book.pdf` (PDF text path only; absent for EPUB, never null) |

Spec 2.0 chapter files carry `spec_version` "2.0" (matching the manifest). An unrendered chapter omits `duration_ms` (absent, never null) and every sentence omits `start_ms` and `end_ms`; a rendered chapter in a `partial` or `complete` book carries them exactly as in 1.x. A 2.0 chapter always carries `blocks` (sentence structure is present even with no audio); pure `pages`-without-`blocks` stays a 1.x-only legacy shape. Unrendered chapters carry no `pages` marks (marks are millisecond offsets, so they need timings).

## 5. Chapter file (PDF books)

PDF text path (v1.1, preferred): keep `blocks` exactly as in section 4 and MAY add page sync:

```json
{
  "spec_version": "1.1",
  "chapter": 1,
  "title": "Pages 1-2",
  "duration_ms": 1832400,
  "blocks": [ { "id": 1, "type": "para", "sentences": [
    { "sid": 1, "speaker": "narrator", "start_ms": 0, "end_ms": 4200, "text": "First page text.", "page": 1 },
    { "sid": 2, "speaker": "narrator", "start_ms": 4200, "end_ms": 6100, "text": "Second page text.", "page": 2 } ] } ],
  "pages": [ { "page": 1, "start_ms": 0 }, { "page": 2, "start_ms": 4200 } ]
}
```

Rules: `page` on a sentence is the 1-based source page it was extracted from. `pages[].start_ms` is the `start_ms` of the first sentence on that page. `pages` is sorted by `page` (and `start_ms`), starts at 0, and every entry matches a sentence boundary. Readers that only need text ignore `pages` and `page` and render `blocks` as usual.

Legacy page-sync only (v1.0 option (b), still valid): a chapter MAY instead carry `pages` without `blocks` (no sentences). The Player shows a page until playback reaches the next page's `start_ms`.

Range bundles (v1.2, D7): a chapter file's `chapter` is the consecutive bundle index (1..K) and it MAY carry `source_index` (the 1-based source-book chapter it was rendered from; absent for full builds, never null). Example: source chapters 3-4 rendered as a range become bundle chapters 1-2 with `source_index` 3 and 4. Readers ignore `source_index` for layout and playback; it exists so operators can map a range bundle back to the source book.

## 6. Timing rules

Rules 1-5 apply to rendered chapters and sentences (those carrying timings). Unrendered chapters carry no `duration_ms` and their sentences carry no `start_ms`/`end_ms`; the rules below are vacuous for them (nothing to order, overlap or agree).

1. `start_ms` of the first sentence in a chapter is 0.
2. For every sentence: `0 <= start_ms < end_ms <= duration_ms`.
3. Sentences are in order and do not overlap; the next sentence's `start_ms` is at or after the previous `end_ms`. Short gaps for pauses are allowed; the Player highlights the previous sentence until the next `start_ms`.
4. `duration_ms` in the manifest, the chapter file, and the probed audio agree within 50 ms for MP3 chapters. For M4A chapters the tolerance is provisionally the same 50 ms (PROVISIONAL until the RN10 beep measurement confirms or loosens it); the measured encoder constant is recorded as manifest `encoder_offset_ms` and applied to timings when they are written, so readers need no per-format correction.
5. Timings are measured on the same continuous buffer that was encoded, not summed from separate per-sentence files after encoding. Encoder delay is a constant offset recorded in `encoder_offset_ms`, not per-sentence drift.

## 7. Validation (Scribe runs this before finishing)

- All referenced files exist; `sha256` of the source matches.
- Every `speaker` exists in `manifest.voices`.
- `sid` values are consecutive; timing rules in section 6 hold.
- Chapter `audio` paths end in `.mp3` or `.m4a` (1.x books: always `.mp3`); the extension decides the expected codec. Manifest `audio.format` is `mp3` or `m4a` and matches at least one rendered chapter (`none` books carry no `audio` object, so the rule is vacuous for them). Mixed MP3/M4A books are legal.
- MP3 chapters: codec mp3, mono, CBR frames, average bitrate within tolerance of 64 kbps, duration within 50 ms of `duration_ms`. M4A chapters: codec aac in an mp4/m4a container, mono, average bitrate within 16 kbps of 64 kbps (no frame-size check: AAC has no MP3-style CBR frames, so the average wanders with content and the check catches gross misconfiguration only), duration within the provisional 50 ms AAC tolerance of `duration_ms`.
- Text is valid UTF-8.
- `spec_version` is "1.0", "1.1", "1.2" or "2.0" (manifest and every chapter file; readers accept all four, writers pick per section 3).
- `render_state` is absent in 1.x bundles and required in 2.0 bundles (`none`, `partial` or `complete`).
- In 2.0 bundles: the manifest `audio` object is absent for `none` and present for `partial`/`complete`; each chapter entry carries `audio` plus `duration_ms` when rendered and omits both when unrendered (never one without the other); `render_state` agrees with the chapters (`none`: all unrendered; `partial`: at least one of each; `complete`: all rendered); chapter file `duration_ms` presence matches its manifest entry; within one chapter file either every sentence carries `start_ms`/`end_ms` or none does; unrendered chapters carry `blocks` and no `pages`; every sentence `speaker` is `narrator` or `dialogue` and `voices` contains both keys.
- `gain_db` (when present, 2.0 only): non-empty object, keys a subset of `narrator`/`dialogue`, values finite numbers. `encoder_offset_ms` (when present, 2.0 only): integer. `render_fingerprint` (when present, 2.0 only, rendered chapters only): `engine` non-blank; `voices` and `speeds` carry `narrator` and `dialogue` (non-blank voice ids, speeds above 0); `engine_versions` non-empty with non-blank strings.
- Invalid combinations are rejected naming the file and the rule, for example `manifest.json: render_state "half" must be one of none, partial, complete`, `manifest.json: chapter 2 has audio without duration_ms (rendered chapters need both)`, `text/ch001.json: sentence 3 carries timings but the chapter has no duration_ms`, `text/ch001.json: sentence 2 has speaker "Ana" (2.0 books allow only narrator and dialogue)`, `manifest.json: chapter 1 audio file "audio/ch001.ogg" must end in .mp3 or .m4a (per-chapter format comes from the extension)`, `audio/ch001.m4a: codec is 'mp3', expected 'aac' for .m4a chapters`, `manifest.json: gain_db has unknown role "Ana" (need narrator and/or dialogue)`.
- Manifest chapter `index` values are consecutive 1..N in bundle order.
- When `source_index` is present (manifest entry or chapter file): 1-based, unique, strictly increasing in bundle order; the chapter file `source_index` matches its manifest entry when both are present.
- When `pages` is present with `blocks`: sorted by `page` and `start_ms`, first `start_ms` 0, each entry matches the first sentence on that page, page numbers 1-based.

Provide this as `scribe validate <bundle>`, and reuse the same checks in the Player's import step (skip a bad chapter with a message rather than crashing).

## 8. Player behavior contract

- Position is stored as `{book id, chapter index, position_ms}` and restored on open. For unrendered books (no milliseconds) position is stored as `{book id, chapter index, sentence sid}` instead; when audio is rendered it converts using the timings.
- Current sentence = the sentence with `start_ms <= position < end_ms`, else the last one whose `start_ms <= position`. In unrendered books the current sentence is the saved sid (no timings to compare).
- Tap a sentence: seek audio to its `start_ms`.
- Load one chapter's JSON at a time; do not keep other chapters in memory.
- Missing optional fields (cover, spans, pages, sentence page, source_index, gain_db, encoder_offset_ms, render_fingerprint) fall back to defaults.
- Unknown fields are ignored, so v1.0 Players read v1.1 bundles (pages) and v1.2 range bundles (source_index, renumbered) as text, and older 2.0 readers skip part 2 fields they predate. Position stays `{book id, chapter index, position_ms}` where chapter index is the bundle-consecutive index; because range bundles carry a range-derived id, saved progress never points at the wrong chapter.
- Partial books (spec level only; the player mapping is Slice 10 RN7 code, not contract): chapter `index` is the stable key and is never renumbered when later chapters render; playlist order follows bundle chapter order; an unrendered chapter has no media item. Progress stays `{book id, chapter index, ...}` in chapter coordinates. When playback reaches the end of the rendered portion it stops with a message (player UX, not a validation rule).
- Version gate: a 1.x reader refuses a 2.0 bundle at the version check (`manifest.json: spec_version "2.0" must be "1.0", "1.1" or "1.2"`) and never reaches the missing-audio paths, so old Players fail cleanly with the file and the rule. A 2.0 reader accepts 1.x bundles unchanged (rendered books keep playing with read-along) and rejects anything newer the same way (for example "3.0").

## 9. Test bundle for Slice 1 (hand-made)

1. Create two short chapters with any text-to-speech or recording, saved as mono WAV.
2. Encode to MP3: `ffmpeg -i in.wav -ac 1 -ar 24000 -b:a 64k -codec:a libmp3lame ch001.mp3`.
3. Read each duration with `ffprobe`, and write `manifest.json` and `text/ch001.json` by hand with 5-10 sentences and rough timings.
4. Copy the folder to the tablet's microSD card and import it. This is enough to build the whole Player shell before Scribe exists.
