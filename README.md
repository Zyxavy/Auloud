# Auloud

Turn your ebooks into multi-voice audiobooks, then read along or just listen

> Status: in design. The interfaces below are the planned v1 and may change as slices are built. See `04-Roadmap.md`.

## What it does

- **Auloud Scribe (PC/server):** converts an EPUB (or PDF) into an audiobook using local, open-source AI voices. It gives dialogue its own voices, separate from the narrator, and produces exact sentence timings.
- **Auloud Player (Android):** stores the original book alongside the audiobook. Three modes: read only, listen only (screen off), and read + listen with a synced highlight. Tap any sentence to jump the audio there.

Everything runs offline. Nothing is uploaded.

## Why

Ebook readers with text-to-speech usually have few voices, one voice for everything. This project moves the heavy AI work to a PC, so even an old tablet can play natural-sounding, multi-voice audiobooks with the screen off.

## How it works

```
EPUB/PDF -> Scribe (PC) -> book bundle (MP3 + text + timings + original) -> Player (Android)
```

The bundle format is documented in `03-BundleSpec.md`, so other tools can produce or read it.

## Requirements

**Scribe:** Python 3.11+, `ffmpeg` on PATH, espeak-ng, and a TTS engine (Kokoro or Piper). A reasonably modern PC; a GPU is optional but speeds things up.

**Player:** Android 7.1+ (developed and tested on a Samsung Galaxy Tab E, Android 7.1.1), a microSD card or free storage (about 430 MB per 15 hours of audio).

## Player modes

| Mode | What you get |
| --- | --- |
| Read only | Scrollable text |
| Listen only | Audio with the screen off, lock screen and Bluetooth controls |
| Read + listen | Highlighted sentence follows the audio; tap to jump; scroll freely and return with "back to now" |

## Repo layout

```
scribe/   Python PC tool (AGPL-3.0-or-later)
player/   Android app (Apache-2.0)
spec/     Bundle specification
docs/     PRD, architecture, design, roadmap, test plan
```

## Documentation

`01-PRD.md`, `02-ArchitectureV1.md`, `03-BundleSpec.md`, `04-Roadmap.md`, `05-ScribeDesign.md`, `06-PlayerDesign.md`, `07-TestPlan.md`, `08-Licenses.md`, `DECISIONS.md`.

## Roadmap

- **v1:** PC-rendered MP3 audiobooks plus read-along player (EPUB, PDF with page-level sync), English.
- **v2:** run text-to-speech on the device itself (Kokoro or Piper).
- **v3:** support later Android versions.

## Limitations

- Character voices depend on speaker detection, which is rule-based in v1 and will sometimes be wrong. Fix mistakes by editing `cast.yaml` and rebuilding.
- Speaker accuracy on 107 hand-checked C&P lines is 92.5%; the misses are all lines no speaker can be assigned (interior thought, unattributed shouts), which render in a generic voice. Crowded scenes with several speakers in one paragraph are the weakest spot.
- PDFs are harder than EPUBs; results vary.
- v1 is English only.
- Voices are AI-generated.

## Books and copyright

Use it only with books you have the right to use, such as public-domain works or books you own, for your own listening. Do not share generated audiobooks of copyrighted works. You are solely responsible for what you convert and share with it; the authors of this project are not responsible for, nor accomplices to, copyright infringement. This repository contains no copyrighted books.

## License

Player: Apache-2.0. Scribe: AGPL-3.0-or-later. Voice models have their own licenses, see `08-Licenses.md`.

## Contributing

Issues and pull requests are welcome once the project is public. Please read the bundle spec before proposing format changes, since Scribe and Player both depend on it.