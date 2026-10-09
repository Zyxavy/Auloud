# PRD: Auloud

Version: v1 (revised). Target device: Samsung Galaxy Tab E (SM-T560NU), Android 7.1.1. Language: English.

## 1. Problem

Ebook readers with built-in TTS offer few voices (system Google TTS plus downloads), use a single voice for everything, and often stop when the screen turns off. 

## 2. Goal

Turn EPUB (and PDF) books into multi-voice audiobooks on a PC or server, and play them on an Android tablet that can read along: highlighted text synced to audio, screen-off listening, and one shared position across reading and listening.

## 3. Users

Primary: for personal use. Secondary: webnovel and ebook listeners who want free, offline, private audiobooks.

## 4. Key decision

The Tab E cannot run neural TTS in real time, so **all synthesis happens on a PC or server**. The tablet only plays audio and shows text. See `02-ArchitectureV1.md` and `03-BundleSpec.md`.

## 5. Scope (v1)

**In scope:** EPUB to audiobook conversion on a PC (Scribe), a tablet player (Player) with three modes, multi-voice narration (narrator plus character voices), PDF with page-level sync.

**Out of scope for v1:** on-device TTS, voice cloning, cloud services, iOS, a cast-editing UI in the app, translation, word-level highlighting in PDFs, a book store.

## 6. Requirements

### Scribe (PC/server)

| ID | Requirement | Priority |
| --- | --- | --- |
| S1 | Convert an EPUB into a book bundle (original file, per-chapter MP3, text and sync JSON, manifest) | P0 |
| S2 | Single narrator voice with exact sentence timings | P0 |
| S3 | Detect quoted dialogue and use a separate voice from the narrator | P1 |
| S4 | Attribute speakers using rules; generate an editable `cast.yaml` of characters to voices | P1 |
| S5 | PDF input: extract clean text, or emit page-level sync | P1 |
| S6 | Optional LLM speaker attribution for ambiguous lines | P2 |

### Player (Android)

| ID | Requirement | Priority |
| --- | --- | --- |
| P1 | Import a book bundle from USB/microSD and show a library | P0 |
| P2 | Play chapters with screen-off playback, lock screen and Bluetooth controls | P0 |
| P3 | Save and resume position (chapter and millisecond) | P0 |
| P4 | Read-only mode (scrollable text) | P0 |
| P5 | Read + listen mode: synced sentence highlight, tap a sentence to jump audio, manual scroll pauses auto-follow with a "back to now" button | P0 |
| P6 | Playback speed (0.75x to 2.0x) and sleep timer | P1 |
| P7 | PDF text view with page-level marks (`pages` + sentence `page`); rendered Page view deferred to v1.1 (D-046) | P1 |
| P8 | Chapter navigation | P1 |
| P9 | First-run prompt to exempt the app from battery optimization | P1 |

## 7. Player modes

1. **Read only:** text, no audio.
2. **Listen only:** audio with the screen off.
3. **Read + listen:** highlighted current sentence follows the audio.

All modes share one saved position.

## 8. Non-functional requirements

- Runs on Android 7.1.1 (`minSdk 24`) with about 1.5 GB RAM; streams audio and loads one chapter's text at a time.
- Screen-off battery drain comparable to a music player, since playback is plain MP3.
- Highlight stays within about 300 ms of the audio.
- Fully offline. No accounts, no network permission needed in v1.
- Storage: about 29 MB per hour of audio (mono, 64 kbps MP3); a 15-hour novel is roughly 430 MB, kept on microSD.

## 9. Success criteria

A full novel (10+ hours) is converted on the PC, copied to the tablet, and read and listened to across several days in all three modes, with the screen off for long stretches, no app kills, correct resume, and no manual file editing beyond the cast file.

## 10. Milestones (SLC slices)

1. Player shell: import a hand-made bundle, play chapters, screen-off playback.
2. Scribe single voice: EPUB to full bundle with sync map, end to end.
3. Read-along: reader view, highlight, tap-to-jump, three modes, resume.
4. Multi-voice: dialogue detection, `cast.yaml`, character voices.
5. Complete pass: PDF page sync, battery prompt, error handling, sleep timer, chapter navigation, full-novel soak test.

## 11. Risks

- Samsung's battery optimizer killing the playback service.
- Sync drift between audio and highlight (mitigated by CBR MP3 and one buffer per chapter).
- Speaker attribution errors (limit v1 to the narrator plus 3-5 characters with an editable cast file).
- Messy PDFs (prefer EPUB; clean PDFs on the PC).
- Model and library licenses.

## 12. Decisions on earlier open questions

1. **Language:** English.
2. **Device:** Galaxy Tab E, Android 7.1.1.
3. **License:** Player under Apache-2.0 (or MIT); Scribe under AGPL-3.0-or-later because ebooklib is AGPL (see D-024). Verify model licenses; not legal advice.

## 13. Later versions

- **v2:** embed the EPUB/PDF in the app so the device runs its own TTS (Kokoro or Piper).
- **v3:** support later Android versions.

## 14. v2 addendum (on-device rendering, Player 2.0.0, in development)

Supersedes section 4 for v2 only: the tablet now synthesizes as well as plays. Import an EPUB on the device, pick a narrator voice and a dialogue voice (two-voice mode; per-character Scribe voices collapse into the dialogue voice on re-render), render in the background (renders run unplugged, D-130), and listen with read-along from device timings. PC rendering stays the default multi-voice path and is unchanged.

Deltas against the v1 requirements above: two engine tiers (System TTS everywhere, Piper in the `full` flavor) with a per-device recommendation; sideloaded model packs instead of downloads (no `INTERNET` permission, unchanged); AAC-LC device audio alongside v1 MP3 playback; version `2.0.0` upgrades a v1 install in place. License: `core` Apache-2.0, `full` GPL-3.0 (D-126). Success is the Slice 12 release criteria in `09-V2Roadmap.md` section 6; the test plan is `07-TestPlan.md`.