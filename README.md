# Auloud

Turn EPUB and PDF books into multi-voice audiobooks, then read along or listen.

Everything runs on your own hardware. The PC or the Android device generates the
audio, and the only network use is the LAN-only Wi-Fi bundle transfer.

## Download

Both apps are on [Releases](https://github.com/Zyxavy/Auloud/releases). Scribe
renders on a PC; the Player plays on Android.

### Scribe (Windows 11)

| File | Size | Needs |
| --- | --- | --- |
| `Auloud-Scribe-3.0.0-gpu.zip` | 1.9 GB | NVIDIA GPU with a current driver |
| `Auloud-Scribe-3.0.0-cpu.zip` | 286 MB | any CPU, slower render |

Unzip anywhere and run `scribe-ui.exe`. The folder carries its own Python,
dependencies and ffmpeg, so it runs on a bare Windows 11 install; deleting the
folder uninstalls it. First run downloads the Kokoro voice models (about 350 MB)
or takes files you already have. Step by step:
[first-run guide](docs/ui-runbook.md). Licenses are in `licenses\` inside the zip
and under `Settings` > `Licenses`.

### Player (Android)

Sideload the APK with unknown sources allowed.

| File | Size | Voices |
| --- | --- | --- |
| `app-core-release.apk` | 10 MB | the device's own System TTS |
| `app-full-release.apk` | 64 MB | System TTS plus Piper packs you copy in |

Built for a Samsung Galaxy Tab E (Android 7.1.1), runs on Android 7.0 and later.
Budget about 430 MB of storage per 15 hours of PC-made audio.

## PC render

Scribe turns an EPUB or PDF into a bundle with a narrator plus a voice per
character. Requirements: Windows 11, Python 3.11+, `uv`, `ffmpeg` and `ffprobe`
on `PATH`, espeak-ng 1.52.0, and the Kokoro files `kokoro-v1.0.onnx` plus
`voices-v1.0.bin`.

```powershell
cd scribe
uv sync
uv run scribe draft ..\MyBook.epub
# check .scribe\<book-id>\cast_report.md, tweak voices in cast.yaml
uv run scribe build ..\MyBook.epub
uv run scribe validate bundles\<book-id>
```

Copy the bundle to `/Auloud/` on internal storage or `Auloud/` on the microSD
card, add it as a watch folder, and press play. To skip the cable, open Receive
via Wi-Fi in the device library and push the bundle from the Scribe UI on the
same network.

Also useful: `uv run scribe ui` (same flow in a browser), `scribe voices
--sample`, `scribe doctor` (checks the setup). A 10-hour novel takes hours, so
run builds detached and poll.

## Device render

Import a DRM-free EPUB on the device, pick two voices, render overnight, listen
with read-along. This path runs on the device alone.

1. Install English voice data in Android Settings > Language and input >
   Text-to-speech output.
2. In the library, tap Import EPUB and pick a file.
3. Choose a narrator and a dialogue voice, then audition each.
4. Start the render. It runs unplugged, and early chapters play while later ones
   render.
5. Play in any mode below.

`full` flavor only: copy each Piper pack folder (with its `.onnx` files) into
`/Auloud/models/`, then pick the `piper` engine per role. Each pack carries its
own license; check its `MODEL_CARD` first.

Two voices per book (narrator plus dialogue), English only, and renders take a
while, so charge the device. Tested on one device; others may differ.

## Player modes

| Mode | What you get |
| --- | --- |
| Read only | Scrollable text |
| Listen only | Audio with the screen off, plus lock screen and Bluetooth controls |
| Read + listen | Highlight follows the audio, tap a sentence to jump, "back to now" after free scroll |

One saved position per book across all modes, speed 0.75x to 2.0x, sleep timer,
and a chapter list with durations. First launch points you at the
battery-optimization screen, because Samsung can still kill background apps.

## Repo layout

```
scribe/     Python PC tool (AGPL-3.0-or-later)
scribe-rs/  Rust UI for Scribe, drives the packaged Python helper (AGPL-3.0-or-later)
player/     Android app (core Apache-2.0, full GPL-3.0; see player/NOTICE)
parity/     Python-vs-Rust parity harness
spec/       Bundle spec mirror (spec/bundle.md) and shared fixtures
docs/       PRD, designs, roadmap, test plan, runbooks, signing guide
```

## Docs map

- [PRD](docs/01-PRD.md), [Roadmap](docs/04-Roadmap.md): promises and slices
- [Bundle spec](docs/03-BundleSpec.md): the Scribe to Player contract
- [Player design](docs/06-PlayerDesign.md), [Scribe design](docs/05-ScribeDesign.md)
- [First-run guide](docs/ui-runbook.md): unpacked app to first render, plus failures
- [Test plan](docs/07-TestPlan.md), [test log](docs/test-log.md)
- [Licenses](docs/08-Licenses.md), [Release signing](docs/ReleaseSigning.md)
- [Decisions](docs/DECISIONS.md): why things are the way they are
- [Archive](docs/archive/): frozen records per version ([v1](docs/archive/v1/), [v2](docs/archive/v2/), [v3](docs/archive/v3/))

## Status

| Version | State | Ships |
| --- | --- | --- |
| v1 | shipped, `1.0.0` | PC renders MP3 audiobooks with narrator and character voices; read-along player with three modes; soak-passed on the Tab E |
| v2 | shipped, `2.1.6` | on-device rendering (System TTS, Piper in `full`), two voices, background renders, live streaming of unrendered chapters |
| v3 | shipped, `3.0.0` | Scribe desktop app: jobs tray, render with preflight, render speed, cast table with audition, voices, validate and transfer, portable Windows zip |
| v4 | next | Android platform refresh: current `targetSdk`, background-service rules, newer Android versions, word-level highlight |
| v5 | planned | iOS and iPadOS Player in Swift, import and playback with read-along. See [V5 plan](docs/V5-iOS-support.md) |

## Limitations

- Speaker detection is rule-based and sometimes wrong. Fix voices in `cast.yaml`
  or the cast table, then rebuild.
- PDFs vary more than EPUBs. A scanned PDF with no text layer fails; OCR is out
  of scope.
- English only. Voices are AI-generated.

## Books and copyright

Use only books you have the right to use (public domain, or books you own), for
your own listening. Do not share generated audiobooks of copyrighted works. This
repo ships code and test fixtures only.

## License

- Player `core`: Apache-2.0, with Apache-2.0/MIT/BSD dependencies.
- Player `full`: GPL-3.0, because it bundles the sherpa-onnx native libraries.
  See `player/NOTICE`. Piper packs carry their own licenses.
- Scribe: AGPL-3.0-or-later. Voice models carry their own licenses; see
  [Licenses](docs/08-Licenses.md).

## Contributing

Issues and PRs are welcome. Read the [bundle spec](docs/03-BundleSpec.md) before
proposing format changes. Sign off commits with `Signed-off-by`; a CLA is
optional. Run the suites first: `uv run pytest` in `scribe/`, and
`gradlew.bat :app:testCoreDebugUnitTest :app:testFullDebugUnitTest` plus lint in
`player/`.
