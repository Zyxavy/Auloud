# Auloud

Turn your ebooks into multi-voice audiobooks, then read along or just listen

> Status: v1 release candidate. Scribe and Player suites are green through CP9, release APK `1.0.0` is built, soak test (several days of listening on the Tab E) is still to be run by the user. See `docs/04-Roadmap.md` and `docs/test-log.md`.

## What it does

- **Auloud Scribe (PC):** converts an EPUB or PDF into an audiobook using local, open-source AI voices. It gives dialogue its own voices, separate from the narrator, and produces exact sentence timings.
- **Auloud Player (Android):** stores the finished bundle. Three modes: read only, listen only (screen off), and read + listen with a synced highlight. Tap any sentence to jump the audio there.

Everything runs offline. Nothing is uploaded.

## Why

Ebook readers with text-to-speech usually have few voices, one voice for everything. This project moves the heavy AI work to a PC, so even an old device can play natural-sounding, multi-voice audiobooks with the screen off.

## How it works

```
EPUB/PDF -> Scribe (PC) -> book bundle (MP3 + text + timings + original) -> Player (Android)
```

The bundle format is documented in `docs/03-BundleSpec.md` (mirrored at `spec/bundle.md`), so other tools can produce or read it. Spec is at v1.1 (additive PDF page marks; v1.0 bundles still validate).

## Requirements

**Scribe (PC):** Windows 11, Python 3.11+, `uv`, `ffmpeg` + `ffprobe` on PATH, espeak-ng 1.52.0, Kokoro model files (`kokoro-v1.0.onnx` + `voices-v1.0.bin`). A reasonably modern PC; a GPU is optional but speeds things up. Python deps (incl. PyMuPDF 1.28.2 and spaCy + `en_core_web_sm`) install via `uv sync` in `scribe/`.

**Player:** Android 7.1+ (developed for a Samsung Galaxy Tab E, Android 7.1.1, `minSdk 24`), a microSD card or free storage (about 430 MB per 15 hours of audio: mono 64 kbps MP3).

## Quick start

### A. Build a book on the PC

```powershell
cd scribe
uv sync
uv run scribe doctor
uv run scribe draft ..\MyBook.epub
# edit .scribe\<book-id>\cast.yaml, review .scribe\<book-id>\cast_report.md
uv run scribe build ..\MyBook.epub
uv run scribe validate bundles\<book-id>
```

PDFs use the same flow (`draft` then `build`); scanned PDFs with no text layer fail with a clear message (no OCR in v1). Useful flags (all verified against `scribe <cmd> --help`):

- `scribe draft <book> --work-dir <path>` (default `.scribe`)
- `scribe build <book> --work-dir <path> --out-dir <path> --models-dir models --strict --no-progress`
- `scribe validate <bundle>` (bundle directory)
- `scribe inspect <bundle> --speakers`
- `scribe doctor --models-dir models`
- `scribe voices` and `scribe voices --sample --out-dir logs/voice-samples --models-dir models --speed 1.0`
- `scribe version`

### B. Play it on the device

1. Build the release (or debug) APK on the PC, in `player/`: `gradlew.bat :app:assembleRelease` (release) or `gradlew.bat :app:assembleDebug` (debug). The release APK needs the signing key in gitignored `player\local.properties` (see `docs/ReleaseSigning.md`); without it the build signs with the debug key and prints a warning.
2. Sideload the APK on the device (allow unknown sources for the install).
3. Copy the finished bundle folder to the device: shared-internal `/Auloud/` (for example `/storage/emulated/0/Auloud`) or a microSD `Auloud/` folder.
4. In the Player library: add a watch folder with the system folder picker (persistable permission, survives reboot), then import/rescan. The default watch folder is shared-internal `/Auloud`.
5. Open the book, pick a mode, press play.

## Setup guide

### Scribe install (Windows 11, from zero)

These are the literal CP9 clean-install steps with wall times from that run (your times will vary; model download was copied locally in 0.1 s, so it is NOT clean-tested):

```powershell
git clone --branch dev/slice5 --single-branch <repo-url> auloud-clean  # about 1.2 s
cd auloud-clean\scribe
uv sync            # about 8.5 s wall with warm uv cache (73 packages resolved, 72 installed)
copy <your-models>\kokoro-v1.0.onnx models\
copy <your-models>\voices-v1.0.bin models\
uv run scribe doctor --models-dir models   # all 9 checks PASS in about 29.7 s
```

What `doctor` checks: python, ffmpeg, ffprobe, espeak-ng, tts-engine (kokoro-onnx), models, spacy (+ parse proof), pymupdf (+ open proof), gpu (info only). Fix hints print under `To fix:` for any FAIL.

Then a first real build (CP9 proof used a synthetic 1-page PDF with 4 short sentences):

```powershell
uv run scribe draft <book.pdf>             # about 9.1 s (1 chapter, 6 sentences, 22 words, 0 drops)
uv run scribe build --no-progress <book.pdf>  # about 15.6 s CLI wall, RTF about 1.28x
uv run scribe validate bundles\<book-id>  # PASS in about 3.9 s
uv run scribe inspect --speakers bundles\<book-id>
```

Notes:

- `draft` accepts EPUB or PDF (`--help` still labels the argument `{book}`; both work). Scanned PDFs fail as a draft error mentioning no text layer.
- `build` is resumable: editing `cast.yaml` re-synthesizes only changed lines (cache keyed by text + voice + speed + engine). A 10-hour novel takes hours (CP9 RTF about 1.28x on GPU; CPU-only SW0 measured 1.38x), so run it detached and poll.
- espeak-ng must be 1.52.0 from the `.msi` at the default path; `ffmpeg`/`ffprobe` come from `winget install ffmpeg` plus a new terminal for PATH.

### Scribe Web UI (`scribe ui`)

The UI covers the whole flow without the CLI: upload, draft, cast review,
render with ranges, validate, hand off to the tablet. The CLI stays the
headless interface; the UI is a thin operator over the same library
(spec: `docs/14-ScribeWebUI-Spec.md`).

```powershell
cd scribe
uv sync --extra ui
uv run scribe doctor            # new `ui` row: fastapi + uvicorn import + versions
uv run scribe ui --workspace <path>   # binds 127.0.0.1 only, opens the browser, prints the URL
```

Flags (verified against `scribe ui --help`; there is deliberately no `--host`
flag): `--port <int>` (default 8137, next free port when busy), `--workspace
<path>` (default `.`, all file access stays inside it), `--no-browser` (print
the URL without opening a browser). One instance per workspace; a second
`scribe ui` on the same workspace refuses to start.

What you get:

- **Four views:** Books (status chips per book), Book detail stepper (draft
  report, cast, render, bundle card), Voices (every engine voice with audition),
  Jobs tray (live progress, pause/resume/cancel) plus a small Settings panel
  (keep-awake, device `auto`/`cpu`/`cuda` defaulting to `auto`).
- **Background jobs + reattach:** builds run as detached processes, so closing
  the browser (or restarting the server) never stops them; reopening
  reattaches to the live progress. A job whose process died reads as
  `interrupted` and resumes from the sentence cache.
- **Ranges:** chapter dropdown or page-range input for PDFs
  with a resolved preview; preflight shows will-render vs cached counts plus a
  time estimate before you start.
- **Cast without YAML:** voice dropdowns, per-row audition, speed, overrides
  picker with low-confidence filter and re-voice counts. Saves keep every
  hand-edited value but drop `#` comment lines (PyYAML cannot round-trip
  them); editing `cast.yaml` by hand mid-session gives a conflict message,
  never a silent overwrite.
- **Transfer to tablet:** removable drives detected plus a typed path, copy
  with progress and size + sha256 verification, a recorded "On tablet" entry,
  and an open-folder button; import the copied bundle in the Player via a
  watch folder as usual.

#### Fresh-user walkthrough (upload to valid bundle)

1. Install and start: `uv sync --extra ui`, then `uv run scribe ui`. Expected:
   the terminal prints a `http://127.0.0.1:<port>` URL and the browser opens
   on the Books view (empty state names the upload action).
2. Upload an EPUB or PDF (drag and drop). Expected: `202` with a `job_id`; the
   draft job runs in the tray; the book row reads `drafting`, then `Drafted`.
3. Open the book detail. Expected: chapter list with durations, draft quality
   report inline, cast summary.
4. Review the cast (no YAML): pick voices, press audition per row, add an
   override for a low-confidence line. Expected: the re-voice count names the
   affected lines; report view is read-only.
5. Render: pick a chapter range (or a PDF page range with the resolved
   preview), check the preflight (will-render/cached/estimate), press Start.
   Expected: the tray shows live progress with RTF, ETA and cache-hit rate;
   pause and resume keep the cache; closing the browser mid-build and
   reopening reattaches to the same job.
6. Validate: press validate on the finished build. Expected: `valid: true`
   plus the bundle card (duration, voices, validate badge); errors list
   `{file, rule, message}` naming file and rule.
7. Transfer: pick a removable drive (or type a path), copy, watch the byte
   progress to the verified tick. Expected: the book chip reads `On tablet`
   with destination and time; copying that folder to `/Auloud/` (or a microSD
   `Auloud/` watch folder) imports in the Player exactly like a CLI-built
    bundle.

### Getting bundles onto the device

- **Shared internal (simplest):** copy the bundle folder (the whole `<BookName>/` with `manifest.json`, `audio/`, `text/`, `source/`) to `/Auloud/` on shared internal storage. The app auto-creates this default.
- **microSD:** copy the bundle folder to an `Auloud/` folder on the card, then in the Player add it as a watch folder via the system folder picker (`ACTION_OPEN_DOCUMENT_TREE`). The grant is persistable across reboots.
- **Watch folders:** the library watches user-chosen folders (internal path or SAF tree URI) in insertion order; a missing card or folder pauses playback with "Storage unavailable - playback paused" and a later Play retries. Import validates manifest, audio files, and each chapter text file; a bad chapter is listed with file and rule and skipped at play time with a transient message while the rest stays playable.

## Player modes

| Mode | What you get |
| --- | --- |
| Read only | Scrollable text |
| Listen only | Audio with the screen off, lock screen and Bluetooth controls |
| Read + listen | Highlighted sentence follows the audio; tap to jump; scroll freely and return with "back to now" |

All three modes share one saved position per book, plus speed (0.75x to 2.0x), sleep timer, chapter list with durations (tap to jump), and a first-run battery-optimization prompt (Samsung can still kill background apps; the prompt leads to the right settings screen).

## Repo layout

```
scribe/   Python PC tool (AGPL-3.0-or-later)
player/   Android app (Apache-2.0 or MIT, to be decided; see player/NOTICE)
spec/     Bundle specification (spec/bundle.md mirrors docs/03-BundleSpec.md) + shared fixtures
docs/     PRD, architecture, design, roadmap, test plan, runbooks, signing guide
```

## Documentation

Start here, then follow the map:

- `docs/01-PRD.md` - what v1 promises (EPUB + PDF text view, three modes, multi-voice)
- `docs/02-ArchitectureV1.md` - PC-renders plus light-player split, bundle sketch
- `docs/03-BundleSpec.md` - the contract (v1.1); `spec/bundle.md` is the byte-identical mirror
- `docs/04-Roadmap.md` - slices plus Slice 5 soak list (device boxes left for the user)
- `docs/05-ScribeDesign.md` - Scribe commands, pipeline, cast.yaml
- `docs/06-PlayerDesign.md` - Player screens, service, reader, storage
- `docs/07-TestPlan.md` - section 6 device checklist, section 7 soak, section 10 release checklist
- `docs/08-Liscenses.md` - licenses and per-voice table (Player deps verified; Scribe rows partly unverified; UI extra verified with versions)
- `docs/DECISIONS.md` - D-001 through D-061 current (Slice 6 D1-D8 disposition in D-061)
- `docs/test-log.md` - device log plus CP0-CP9 and Slice 6 UI1-UI10 automated evidence
- `docs/14-ScribeWebUI-Spec.md` - Scribe Web UI spec, v1 as built (Slice 6 amendments)
- `docs/UI9-BrowserRunbook.md` - 3-browser manual runbook (Edge, Chrome, Firefox; user runs)
- `docs/Slice3-Runbook.md` - Tab E checks C13 onward (beep/long bundles, overlay readings)
- `docs/ReleaseSigning.md` - generate the key once outside the repo, wire `player\local.properties`, verify no INTERNET
- `docs/plans/Slice5.md` - CP0-CP12 work packages for this slice (CP10 is this docs pass)
- `spec/fixtures/pdf-golden/README.md` - the v1.1 contract fixture and its pinned sentences
- `scribe/dev/make_pdf_golden.py` - generator header for that fixture

## Roadmap

- **v1 (this release):** PC-rendered MP3 audiobooks plus read-along player (EPUB and PDF clean text with page-level marks), English. Release APK `1.0.0` built; soak test pending.
- **v1.1 backlog:** PDF Page view (`PdfRenderer`, one page at a time) as a Text/Page reader option, LLM speaker attribution, real EPUB rendering, Wi-Fi transfer, bookmarks, themes, auto-drafted cast.
- **v2:** run text-to-speech on the device itself (Kokoro or Piper). Needs a benchmark spike and a license revisit (bundling espeak-ng changes the Player license).
- **v3:** support later Android versions.

## Limitations

- Character voices depend on speaker detection, which is rule-based in v1 and will sometimes be wrong. Fix mistakes by editing `cast.yaml` and rebuilding.
- Speaker accuracy on 107 hand-checked C&P lines is 92.5%; the misses are all lines no speaker can be assigned (interior thought, unattributed shouts), which render in a generic voice. Crowded scenes with several speakers in one paragraph are the weakest spot.
- PDFs are harder than EPUBs; results vary.
- v1 is English only.
- Voices are AI-generated.

PDF detail: v1 reads PDFs as clean text through the same reader as EPUB (sentence sync plus `pages` marks for lookup). The rendered Page view is a v1.1 option (D-046), not built. Scanned PDFs fail with a clear message; indent-only paragraph breaks can join; two-column order follows the layout blocks.

## Books and copyright

Use it only with books you have the right to use, such as public-domain works or books you own, for your own listening. Do not share generated audiobooks of copyrighted works. You are solely responsible for what you convert and share with it; the authors of this project are not responsible for, nor accomplices to, copyright infringement. This repository contains no copyrighted books.

## License

Player: Apache-2.0. Scribe: AGPL-3.0-or-later. Voice models have their own licenses, see `docs/08-Liscenses.md`.

## Contributing

Issues and pull requests are welcome once the project is public. Please read the bundle spec before proposing format changes, since Scribe and Player both depend on it.
