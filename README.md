# Auloud

Turn your ebooks into multi-voice audiobooks, then read along or just listen.

Everything runs on your own hardware: the PC renders, and the Android device receives bundles over your own Wi-Fi (LAN only). Nothing is uploaded to any cloud.

## Get the app

Both downloads are on [Releases](https://github.com/Zyxavy/Auloud/releases).

**Auloud Scribe (Windows 11, the machine that renders).** Unzip `Auloud-Scribe-3.0.0-gpu.zip` anywhere and run `scribe-ui.exe` from the folder it creates. No installer, no Python, no Rust and no `ffmpeg` on `PATH`: the folder carries its own interpreter, its dependencies and ffmpeg, and deleting the folder is the uninstall. It wants an NVIDIA GPU with a current driver; on any other machine take `Auloud-Scribe-3.0.0-cpu.zip` instead (286 MB against 1.9 GB, and a slower render, D-187). First run downloads the Kokoro voice models (about 350 MB) or takes files you already have. Step by step: [first-run guide](docs/ui-runbook.md).

The zip carries its generated license notice, third-party list and source offer under `licenses\`, and the app shows them under `Settings` > `Licenses`; see [Licenses](docs/08-Licenses.md).

**Auloud Player (the Android device).** Download the APK and sideload it (allow unknown sources for the install):

- **core** (most people): renders with the device's own System TTS voices.
- **full**: adds on-device Piper voices. Only pick this if you sideload Piper packs too (below).

Developed for a Samsung Galaxy Tab E (Android 7.1.1). Needs free storage: about 430 MB per 15 hours of audio for PC-made books.

## Two ways to make books

**A. The PC renders (best quality, multi-voice).** Scribe on Windows turns an EPUB or PDF into a finished bundle with a narrator plus a voice per character. Copy the bundle to the Android device and play.

**B. The Android device renders (no PC).** Import a DRM-free EPUB on the device, pick two voices, render overnight, listen with read-along.

## Path A: PC to Android device in 5 steps

With the app this is the [first-run guide](docs/ui-runbook.md): add a book, cast it, render, validate, send. From the source tree:

Requirements: Windows 11, Python 3.11+, `uv`, `ffmpeg` + `ffprobe` on PATH, espeak-ng 1.52.0, Kokoro model files (`kokoro-v1.0.onnx` + `voices-v1.0.bin`).

```powershell
cd scribe
uv sync
uv run scribe draft ..\MyBook.epub
# check .scribe\<book-id>\cast_report.md, tweak voices in cast.yaml if you like
uv run scribe build ..\MyBook.epub
uv run scribe validate bundles\<book-id>
```

Then copy the bundle folder onto the Android device (`/Auloud/` on internal storage, or an `Auloud/` folder on the microSD card), add it as a watch folder in the library, and press play. Or skip the cable: open Receive via Wi-Fi in the device library and push the bundle straight from the Scribe UI on the same network.

Handy extras: `uv run scribe ui` (same flow in a browser, no CLI needed), `scribe voices --sample` (hear voices first), `scribe doctor` (checks your setup). PDFs work the same way; scanned PDFs with no text layer fail with a clear message. A 10-hour novel takes hours, so run builds detached and poll.

Details: [Scribe design](docs/05-ScribeDesign.md), [Web UI walkthrough](docs/archive/v1/14-ScribeWebUI-Spec.md), [bundle spec](docs/03-BundleSpec.md).

## Path B: render on the Android device

1. Install offline voice data: Android device Settings -> Language and input -> Text-to-speech output, pick an engine and install its English voices. The Player uses the network for Wi-Fi bundle receive only (your own LAN, no cloud), so this step is what makes rendering work.
2. In the library tap Import EPUB and pick the file. The book opens with readable text.
3. Choose voices (narrator + dialogue) and audition each role.
4. Start the render (works unplugged). You can listen to early chapters while later ones still render.
5. Play in any of the three modes below.

`full` flavor only: copy each Piper pack folder (with its `.onnx` files) into `/Auloud/models/` (internal or microSD), then pick the `piper` engine per role. Each pack has its own license, check its `MODEL_CARD` first.

Limits: two voices (narrator + dialogue; a PC book re-rendered on-device collapses extra speakers into dialogue), English only, renders take a while (charge while rendering). Tested on one Android device; other devices may differ.

Details: [v2 roadmap](docs/09-V2Roadmap.md), [render tuning notes](docs/constants.md).

## Player modes

| Mode | What you get |
| --- | --- |
| Read only | Scrollable text |
| Listen only | Audio with the screen off, lock screen and Bluetooth controls |
| Read + listen | Highlight follows the audio; tap a sentence to jump; "back to now" after free scroll |

One saved position per book across all modes, speed 0.75x to 2.0x, sleep timer, chapter list with durations. First launch asks about battery optimization (Samsung can still kill background apps; the prompt leads to the right screen).

## Repo layout

```
scribe/     Python PC tool (AGPL-3.0-or-later)
scribe-rs/  Native Rust UI for Scribe; drives the packaged Python helper (AGPL-3.0-or-later)
player/     Android app (core Apache-2.0, full GPL-3.0; see player/NOTICE)
parity/     Python-vs-Rust parity harness (stdlib driver, expected files)
spec/       Bundle spec mirror (spec/bundle.md) + shared fixtures
docs/       PRD, designs, roadmap, test plan, runbooks, signing guide
```

## Docs map

- [PRD](docs/01-PRD.md): what v1 promises
- [Bundle spec](docs/03-BundleSpec.md): the Scribe <-> Player contract (v1.1)
- [Roadmap](docs/04-Roadmap.md): slices and the soak list
- [Player design](docs/06-PlayerDesign.md) and [Scribe design](docs/05-ScribeDesign.md)
- [First-run guide](docs/ui-runbook.md): unpacked app to first render, plus the failure table
- [Test plan](docs/07-TestPlan.md), [test log](docs/test-log.md)
- [Licenses](docs/08-Licenses.md) (per-dependency and per-voice tables)
- [Decisions](docs/DECISIONS.md): why things are the way they are
- [Release signing](docs/ReleaseSigning.md): flavors, keys, release smoke checks
- [Archive](docs/archive/v1/): frozen v1 records and plans; [v2 plans](docs/archive/v2/plans/)

## Status checklist

- [x] **v1 (done):** PC renders MP3 audiobooks from EPUB/PDF with narrator + character voices and sentence timings; read-along player with three modes; release APK `1.0.0` soak-passed on the Tab E.
- [~] **v2 (partial):** on-device rendering (System TTS plus Piper in `full`), two voices, background renders, live streaming of unrendered chapters. Player `2.1.0` tagged; some device numbers still provisional.
- [~] **v3 (in progress):** native desktop Scribe app (Rust UI driving the packaged Python backend): jobs tray, render step with preflight, render speed, cast table with audition, voices view, validate/transfer, and a portable Windows zip (`gpu` and `cpu`) instead of an installer. Python Scribe stays canonical.
- [ ] **v4 (later):** Android platform refresh: current `targetSdk`, background-service rules, newer Android versions, word-level highlight. Placeholder until v3 ships.
- [ ] **v5 (planned):** iOS/iPadOS Player in Swift (xtool build from Windows): import and play bundles with read-along, no rendering on device. See [V5 plan](docs/V5-iOS-support.md).

## Limitations

- Speaker detection is rule-based and sometimes wrong; fix voices in `cast.yaml` (or the cast table) and rebuild.
- PDFs are harder than EPUBs; results vary. Scanned PDFs fail cleanly (no OCR).
- English only. Voices are AI-generated.

## Books and copyright

Use only books you have the right to use (public domain or books you own), for your own listening. Do not share generated audiobooks of copyrighted works. This repo contains no copyrighted books.

## License

- Player core: Apache-2.0, no GPL code.
- Player full: GPL-3.0 combined work (sherpa-onnx native libs inside; see `player/NOTICE`). Piper packs are files you copy yourself under their own licenses.
- Scribe: AGPL-3.0-or-later. Voice models have their own licenses, see [Licenses](docs/08-Licenses.md).

## Contributing

Issues and PRs welcome. Read the [bundle spec](docs/03-BundleSpec.md) before proposing format changes. Sign off commits (`Signed-off-by`, no CLA). Run the suites first: `uv run pytest` in `scribe/`, `gradlew.bat :app:testCoreDebugUnitTest :app:testFullDebugUnitTest` plus lint in `player/`.
