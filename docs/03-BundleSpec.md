# Bundle Spec v1.1

The **book bundle** is the contract between Scribe (PC) and the Player (Android). Scribe writes it; the Player only reads it. Change this document before changing either program.

Changelog: v1.1 is additive only. PDF text-path chapters keep `blocks` and MAY add `pages: [{page, start_ms}]` plus an optional `page` on each sentence. Old Players ignore the extra fields (`ignoreUnknownKeys`). No existing required field changes. Readers accept `spec_version` "1.0" and "1.1".

## 1. Folder layout

```
<BookName>/
  manifest.json
  cover.jpg                # optional
  source/book.epub         # the original file, unchanged (or book.pdf)
  audio/ch001.mp3
  audio/ch002.mp3
  text/ch001.json
  text/ch002.json
```

Rules:

- Folder and file names use ASCII, lowercase for generated files; chapter numbers are zero-padded to 3 digits (`ch001`), or 4 if a book has more than 999 chapters.
- All text files are UTF-8 (NFC normalized), with `\n` line endings.
- All times are **integer milliseconds**.
- The Player ignores unknown fields, so newer Scribe versions can add fields without breaking older Players. A change of the major number in `spec_version` (e.g. 2.0) means breaking changes.

## 2. Audio

| Property | Value |
| --- | --- |
| Format | MP3 (MPEG Layer III) |
| Channels | 1 (mono) |
| Sample rate | 24000 Hz (22050 Hz also acceptable) |
| Bitrate | 64 kbps, **constant (CBR)** |
| One file per chapter | Yes; encode from one continuous buffer |

CBR keeps seeking and timestamps accurate; variable bitrate can make them drift. Do not add silence padding at chapter boundaries beyond what `duration_ms` reflects.

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

Required fields: `spec_version`, `id`, `title`, `type`, `audio`, `chapters` (each with `index`, `title`, `audio`, `text`, `duration_ms`). Everything else is optional. `id` is a UUID that never changes for a given book; the Player keys saved progress by it.

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
| `speaker` | yes | key in `manifest.voices` (`"narrator"` for narration) |
| `start_ms`, `end_ms` | yes | position in this chapter's MP3 |
| `text` | yes | display text, UTF-8 |
| `spans` | no | inline formatting: `[{"start": 0, "end": 3, "style": "italic"}]`, character offsets into `text`; styles: `italic`, `bold` |
| `page` | no | 1-based source page in `source/book.pdf` (PDF text path only; absent for EPUB, never null) |

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

## 6. Timing rules

1. `start_ms` of the first sentence in a chapter is 0.
2. For every sentence: `0 <= start_ms < end_ms <= duration_ms`.
3. Sentences are in order and do not overlap; the next sentence's `start_ms` is at or after the previous `end_ms`. Short gaps for pauses are allowed; the Player highlights the previous sentence until the next `start_ms`.
4. `duration_ms` in the manifest, the chapter file, and the MP3 itself agree within 50 ms.
5. Timings are measured on the same continuous buffer that was encoded to MP3, not summed from separate per-sentence files after encoding.

## 7. Validation (Scribe runs this before finishing)

- All referenced files exist; `sha256` of the source matches.
- Every `speaker` exists in `manifest.voices`.
- `sid` values are consecutive; timing rules in section 6 hold.
- MP3 is mono, CBR, matches `manifest.audio`, and its duration matches `duration_ms`.
- Text is valid UTF-8.
- `spec_version` is "1.0" or "1.1" (both accepted; new bundles write "1.1").
- When `pages` is present with `blocks`: sorted by `page` and `start_ms`, first `start_ms` 0, each entry matches the first sentence on that page, page numbers 1-based.

Provide this as `scribe validate <bundle>`, and reuse the same checks in the Player's import step (skip a bad chapter with a message rather than crashing).

## 8. Player behavior contract

- Position is stored as `{book id, chapter index, position_ms}` and restored on open.
- Current sentence = the sentence with `start_ms <= position < end_ms`, else the last one whose `start_ms <= position`.
- Tap a sentence: seek audio to its `start_ms`.
- Load one chapter's JSON at a time; do not keep other chapters in memory.
- Missing optional fields (cover, spans, pages, sentence page) fall back to defaults.
- Unknown fields are ignored, so v1.0 Players read v1.1 bundles (pages) as text.

## 9. Test bundle for Slice 1 (hand-made)

1. Create two short chapters with any text-to-speech or recording, saved as mono WAV.
2. Encode to MP3: `ffmpeg -i in.wav -ac 1 -ar 24000 -b:a 64k -codec:a libmp3lame ch001.mp3`.
3. Read each duration with `ffprobe`, and write `manifest.json` and `text/ch001.json` by hand with 5-10 sentences and rough timings.
4. Copy the folder to the tablet's microSD card and import it. This is enough to build the whole Player shell before Scribe exists.
