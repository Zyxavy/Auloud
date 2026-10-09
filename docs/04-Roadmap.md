# Roadmap: Read-Along Audiobook Player

Working doc. Update the status boxes as you go. Relates to `01-PRD.md`, `02-ArchitectureV1.md`, `03-BundleSpec.md`.

Status key: `[ ]` not started, `[~]` in progress, `[x]` done. Size: S (a few evenings), M (a few weekends), L (longer).

## Ground rules

- Every slice ends with something you can run on the Tab E.
- The bundle format (03) stays fixed across slices. If it must change, change the spec first and bump `spec_version`.
- Do not start the next slice until the "done when" list of the current one is checked.
- Log decisions in `DECISIONS.md` as they happen.

## Slice 0: Foundations (S)

Goal: everything ready so Slice 1 starts clean.

**Tablet (Galaxy Tab E, Android 7.1.1)**

- [ ] Developer options enabled (tap Build number 7 times); USB debugging on
- [ ] Samsung USB driver installed on the laptop; data-capable USB cable
- [ ] `adb devices` lists the tablet as `device` (not `unauthorized`); "always allow" accepted
- [ ] Verified on the unit: Android 7.1.1, actual RAM, free internal storage, microSD present with enough free space (about 430 MB per 15-hour book)
- [ ] Battery holds a charge for long tests; charger and Bluetooth headset available
- [ ] Noted where Samsung's battery-optimization settings live (for WP8)
- [ ] microSD read test: put a text file on the card and read it by file path from a test app (decides the WP3 approach)

**Laptop**

- [ ] Android Studio (latest stable, bundled JDK), Android SDK platform and platform tools
- [ ] Git installed; private repo created; docs 01-09, README and DECISIONS copied into `docs/`
- [ ] Python, `uv` and ffmpeg installed (`winget install ffmpeg`)

**Project**

- [ ] Repo layout created: `scribe/`, `player/`, `spec/`, `docs/`; `03-BundleSpec.md` copied to `spec/bundle.md`
- [ ] Package name chosen (for example `app.auloud.player`)
- [ ] Android Studio project created with `minSdk 24`
- [ ] Empty app installed and launched on the Tab E

**Test content**

- [ ] Two 5-minute chapters from public-domain or self-made audio (no copyrighted audio)
- [ ] Hand-made test bundle built per section 9 of the spec, MP3s encoded CBR
- [ ] Test bundle copied to the microSD card

**Done when:** a hello-world APK runs on the Tab E, the microSD read test has a known result, and the test bundle is on the card.

## Slice 1: Player shell (M)

Goal: play a bundle with the screen off.

- [x] Import a bundle folder via the folder picker; parse `manifest.json`
- [x] Library screen listing books with cover and title
- [x] Media3 `MediaSessionService` as a foreground service, chapters as a playlist
- [x] Play, pause, seek, next and previous chapter; notification and Bluetooth controls
- [x] Save and restore position (Room)
- [x] First-run battery-optimization prompt

**Done when:**

- [x] The test bundle plays end to end with the screen off for 30+ minutes
- [x] Closing and reopening the app resumes at the same spot
- [x] Lock screen controls work

## Slice 2: Scribe, single voice (M)

Goal: a real EPUB becomes a valid bundle.

- [x] EPUB parse and text cleaning (chapters, headings, paragraphs, italics)
- [x] Sentence splitting
- [x] Single narrator voice synthesis (Kokoro or Piper), sentence by sentence
- [x] Timing collection from one continuous buffer per chapter
- [x] CBR MP3 encoding with ffmpeg; manifest and chapter JSON written
- [x] `scribe validate` implemented
- [x] `scribe build book.epub` works end to end

**Done when:**

- [x] A short public-domain book converts without errors and passes validation
- [x] The bundle imports into the Player from Slice 1 and plays

## Slice 3: Read-along (M)

Goal: three modes with a synced highlight.

- [x] Reader view: blocks and sentences in a `LazyColumn`, headings and italics
- [x] Position ticker (about every 200 ms) mapping audio position to the current sentence
- [x] Highlight and auto-scroll in read + listen mode
- [x] Tap a sentence to seek audio
- [x] Manual scroll pauses auto-follow; "back to now" button
- [x] Mode switching (read only, listen only, read + listen) keeps one shared position
- [x] Speed control and sleep timer

**Done when:**

- [x] Highlight stays within about 300 ms of the audio at start, middle and end of a long chapter
- [x] Switching modes never loses your place
- [x] Memory stays stable during a 1-hour session

## Slice 4: Multi-voice (M to L)

Goal: distinct narrator and character voices.

- [x] Dialogue detection (straight and curly quotes, multi-paragraph quotes)
- [x] Rule-based speaker attribution ("said X", nearest name, two-person alternation)
- [x] Draft `cast.yaml` generated, hand-editable, then used for rendering
- [x] Voice palette chosen (narrator plus 3-5 characters)
- [x] Per-character speed/pitch offsets
- [x] Manifest `voices` map filled in; speaker stored per sentence

**Done when:**

- [x] A dialogue-heavy chapter sounds clearly different for narrator and characters
- [x] Mislabelled speakers can be fixed by editing `cast.yaml` and re-running, with no code changes

## Slice 5: Complete pass (M)

Goal: fit for a full novel and daily use.

- [x] PDF support: PDF to clean text on the PC with v1.1 page marks (JVM/unit verified CP5-CP7; rendered Page view deferred to v1.1 per D-046)
- [x] Chapter navigation screen (JVM verified CP3)
- [x] Error handling: bad chapter skipped with a message, missing files, low storage (JVM verified CP4)
- [x] Player-side validation on import (JVM verified CP4)
- [x] Battery whitelist flow tested on the Tab E
- [x] Full-novel soak test (see below)
- [x] README and setup instructions (written in CP10; fresh-follow on real hardware confirmed by the user)

Automated evidence (no device claimed): CP3 chapter list + jump (JVM suite green); CP4 import validation + skip notices + storage-loss pause (JVM suites green); CP5 PDF extraction incl. scanned-fail + determinism (unit); CP6 spec 1.1 + pdf-golden contract both suites; CP7 Text view reader path + page lookup (JVM); CP8 release APK `1.0.0` minified with no INTERNET; CP9 clean `uv` install builds a short bundle end to end. Device remainder for the user: battery-whitelist flow, eject-and-reinsert pause/resume, bad-bundle import on device, read + listen on a real PDF with memory stability, Slice 1-4 smoke on the release build, licenses screen legibility, launcher icon render, sideload + upgrade-install, README fresh-follow zero to playing book, full soak below.

## Slice 6: Scribe Web UI (merged into the v1.0.0 release line)

Goal: `scribe ui` covers ingest, cast review, render, validate and tablet hand-off in a localhost web app; the CLI stays the headless interface. Plan: `docs/archive/v1/plans/Slice6.md`.

- [x] UI1 library: events, chapter/page ranges, `plan_build`, cooperative stop, per-book lock, `--device`, spec 1.2 range bundles (482 baseline untouched, 498 green)
- [x] UI2 server skeleton: `scribe[ui]` extra, 127.0.0.1-only + Host check + per-run token + traversal guards, doctor row (531 green)
- [x] UI3 books and book detail
- [x] UI4 job runner and jobs tray (unit/API verified: 17 new job tests + route list green, full suite green; tray visuals and browser-closed build left for the user)
- [x] UI5 render step with ranges and preflight (API verified: 10 new render tests + route list green, full suite 578 green; picker/preflight/start/pause/resume/delta-rebuild visuals left for the user in Edge)
- [x] UI6 cast view with audition and overrides (API verified: 20 new cast tests + route list green, full suite 598 green; table/audition/override/conflict visuals left for the user in Edge)
- [x] UI7 voices view and audition service (API verified: 17 new voices tests + route list green, full suite 615 green; grid/play/regenerate/coalescing visuals left for the user in Edge)
- [x] UI8 validate and transfer (API verified: 12 new transfer tests + route list green, full suite 627 green; validate-now/drive-picker/copy-progress/verified-tick/record/chip plus the tablet Player import left for the user in Edge)
- [x] UI9 design system and polish (tokens/contrast/component/offline: 8 new design tests green, full suite 635 green + ruff clean; 3-browser runbook + zoom/keyboard/dark left for the user in Edge/Chrome/Firefox)
- [x] UI10 tests and hardening (API/security/SSE/state-machine: 22 new hardening tests green, full suite 657 green + ruff clean; no model load, no slowdown to existing suites; browser runbook untouched, no UI bugs found)
- [x] UI11 docs and acceptance (agent docs half done 2026-10-05: spec saved as `docs/archive/v1/14-ScribeWebUI-Spec.md`, README Web UI + walkthrough, UI dep licenses re-verified, D-061 with the D1-D8 map, test-log UI1-UI10 block; user acceptance 2026-10-05: "mostly complete and good enough", acceptance fixes landed as D-062/D-063 + collapsible UI commits)

**Done when (v1 release criteria):** the soak test passes.

### Soak test (v1 acceptance)

- [x] Novel converted on the PC and copied to the tablet (2-chapter C&P per CP2 scope + a real PDF, not a 10-hour novel)
- [x] Listened over several days, with long screen-off stretches, in all three modes
- [x] No app kills; no crashes
- [x] Resume is correct after every restart
- [x] Sync spot-checks pass in early, middle and late chapters
- [x] Battery drain during screen-off playback is acceptable

All user-verified on the Tab E 2026-10-05, logged in `docs/archive/v1/soak-log.md` (verdict: pass).

## Backlog (v1.1)

- LLM speaker attribution via Ollama for ambiguous lines
- Real EPUB rendering with original layout
- PDF Page view (`PdfRenderer`, one page at a time) as a Text/Page reader option
- Wi-Fi transfer from PC to tablet
- Bookmarks and highlights
- Themes, font size and line spacing options
- Auto-drafted cast from character frequency

## v2

Embed the EPUB/PDF in the app so the device runs its own TTS (Kokoro or Piper).

*Status: built on `dev/v2` (Player `2.0.0`, two voices, System TTS plus Piper in the `full` flavor); release pending the Slice 12 soak and sign-off. Detail lives in `docs/09-V2Roadmap.md`; Slices 9-11 are device-passed, Slice 12 is in progress. Boxes below stay as written until the Slice 12 gate ticks them.*

- [ ] Benchmark spike first: measure real-time factor of Piper and Kokoro on target devices
- [ ] Engine interface so voices are pluggable
- [ ] On-device tagging with rules
- [ ] Background render (no charger requirement, D-130); streaming synthesis for immediate listening
- [ ] Decide licensing impact of bundling espeak-ng

## v3

Scribe desktop port (D-133): port Scribe to Rust with a Tauri v2 app for Windows 11 and Linux. The Python Scribe stays canonical until parity; the port honors `spec/` unchanged.

## v4

Android platform work (D-133), placeholder until v3 ships:

- [ ] Update `targetSdk` and handle newer background-service and notification permission rules
- [ ] Test on current Android versions and a range of devices
- [ ] Revisit `minSdk` and the tablet-only assumptions
- [ ] Word-level highlight callbacks (needs API 26+)
- [ ] Scribe on Android (Tauri mobile targets)

## Risks to watch per slice

| Slice | Watch for |
| --- | --- |
| 1 | Samsung killing the service; MP3 seek accuracy |
| 2 | Timing drift; espeak/phonemizer install problems |
| 3 | Highlight lag; RAM on long chapters |
| 4 | Speaker misattribution; voice fatigue |
| 5 | Messy PDFs; storage limits on the tablet |