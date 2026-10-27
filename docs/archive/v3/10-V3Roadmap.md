# Auloud v3 Roadmap (Revised): Scribe as a Single Native App

Supersedes `docs/10-V3Roadmap.md` (Tauri-port version, archived at
`docs/archive/v3/10-V3Roadmap-tauri-port.md`) and the RS2-onward half of
`docs/plans/Slice15.md` (marked superseded; RS0 and RS1 stand as built).
The Slice 14 plan (`docs/plans/Slice14.md`) stays valid.

## 0. What changed and why

The earlier roadmap rewrote all of Scribe's logic in Rust and hosted the
existing web UI in Tauri. What you actually want is **one program you run
(an `.exe` on Windows, an equivalent on Linux) that opens a native Scribe
UI and renders the voices from there**, with the UI written in Rust and no
browser or webview.

The rewrite was not needed for that, and it would not make rendering
faster: almost all render time is the TTS model running in ONNX Runtime,
which is native code either way (to be confirmed by the profile in
section 6).

**New shape of v3**

- **UI:** a native Rust app (toolkit chosen by a spike). No Electron, no webview
- **Backend:** the existing Python Scribe, packaged inside the app as a hidden helper process. No Python or `uv` for the user to install
- **Contract between them:** the localhost API and job events that already exist (with their security work), documented and versioned
- **Python stays canonical.** No scribe logic is ported to Rust

**Reuse from the work already done**

- RS0: the Rust workspace and toolchain proof stay; the Tauri stub is replaced by the real UI crate at Slice 16 (the `tauri` dependency goes away there)
- RS1: the rule catalog, fixtures and expected files stay; they pin validator behavior for Scribe and the Player regardless of the port (the Rust contract-test consumer arrives only if a backend port is ever justified)
- RS2 onward: **cancelled**
- D-133 is amended by D-147 below: v3 is a native Rust desktop UI for Scribe with the Python Scribe packaged as its backend; a backend rewrite is not planned unless a measured gain justifies it

Assumptions to confirm: native Rust toolkit (not Tauri); Windows 11 first, Linux second; models are downloaded on first run or sideloaded.

## 1. Goals and non-goals

**Goals**

- Download, run an installer or unzip, double-click: the Scribe UI opens and works with no other installs
- The full workflow in the UI: add a book, draft, review and edit the cast with audition, render with ranges and a jobs tray, validate, transfer to the tablet
- Jobs survive closing the window and are picked up again on relaunch
- Model manager: first-run setup downloads models with checksums or accepts sideloaded packs, including the optional Kitten and Piper packs
- Keep the design system (paper, ink, oxblood, sage; serif for titles, all four confirmed in `scribe/ui/static/index.html`) and make the UI keyboard- and screen-reader-usable

**Non-goals:** rewriting Scribe's logic, new bundle features, auto-update, cloud services, macOS support beyond best-effort.

## 2. Architecture

```
Rust UI app (scribe-ui)  <--HTTP/SSE on 127.0.0.1, random port + token-->  Helper (bundled Python Scribe)
        |                                                                      |
   native toolkit                                             detached build/draft jobs (existing runner)
```

- The UI app starts the helper on launch with a random port and a per-run token, waits for a health check, and shuts it down on exit. The existing protections (127.0.0.1-only bind, Host allow-list, per-run token on state-changing requests, no CORS; see `scribe/ui/security.py`) stay as they are. Running jobs are separate detached processes (the existing design), so closing the app never kills a render
- The helper is a bundled, **non-frozen** Python runtime with pre-installed packages if the packaging gate says so, because single-file frozen executables with native packages (ONNX Runtime, spaCy, PDF libraries) are fragile and often flagged by antivirus
- The API gets a written contract (`docs/api.md`, new in Slice 16; no `docs/api.md` or version endpoint exists today) and a version endpoint, and the UI includes a typed Rust client crate with tests against recorded responses
- Data lives in per-user folders: workspace, models, job history, settings
- No Rust code duplicates cast, attribution, spec or validation logic

## 3. Key decisions (log from D-147; D-133 amended, not edited)

1. **UI toolkit by spike, not by taste.** Candidates: egui, iced, Slint (all active on crates.io: egui 0.36.x, iced 0.14.x, Slint 1.18.x, verified 2026-10-11). Criteria: a virtualized table with in-cell dropdowns (cast), text input quality, async and SSE integration, theming to the design tokens, accessibility on Windows (Narrator) and Linux, file dialogs, audio audition playback, compile time and binary size, license fit with AGPL-3.0-or-later. License facts (crates.io, same date): egui is MIT OR Apache-2.0, iced is MIT, Slint is GPL-3.0-only OR Slint-Royalty-free OR Slint-commercial; confirm which Slint grant applies before choosing it
2. **Helper packaging by spike:** an embedded Python runtime with installed packages versus a frozen executable, judged on clean-machine start, size, antivirus behavior and build reliability
3. **Contract:** reuse the existing HTTP API and SSE with the token and Host-header protections; no new transport unless the spike finds a blocker
4. **Model manager in the UI:** downloads done by the Rust app with checksums, written to the models folder the helper reads; the helper's `doctor` verifies. No download or sideload code exists today; this is new scope
5. **License:** the UI app is AGPL-3.0-or-later; bundled components' licenses go in NOTICE and an SBOM; the installer carries the source offer
6. **Stop-loss:** if packaging fails the "nothing to install" goal, ship the Rust UI with a documented one-time setup step (a bootstrap that creates the Python environment on first run), and record why
7. **Alternative kept in reserve:** Tauri hosting the existing web UI is the cheapest route to a single exe if the native-toolkit effort turns out too large

## 4. Slices

```mermaid
flowchart LR
    S14[14: Kitten engine] --> S15[15: Packaging gate]
    S15 --> S16[16: API + toolkit]
    S16 --> S17[17: App shell + books]
    S17 --> S18[18: Jobs + render]
    S18 --> S19[19: Cast + voices]
    S19 --> S20[20: Validate + transfer]
    S20 --> S21[21: Design + polish]
    S21 --> S22[22: Installers + release]
```

### Slice 14: KittenTTS engine (Player `full` flavor)

Unchanged. See `docs/plans/Slice14.md`; its remaining items are device-only.

### Slice 15: Helper packaging gate (S-M), do first

The riskiest premise, so test it before building any UI.

- Build the helper as an embedded Python runtime with the Scribe package and dependencies installed; also try one frozen build for comparison
- Run it on a **clean Windows 11 environment** with no Python (Windows Sandbox or a fresh user on a spare machine): start the API, `doctor`, draft and build a short book end to end with a Kokoro model, validate the bundle
- Check Linux feasibility in a container or WSL
- Record: install size, cold start time, idle memory, Defender or antivirus reactions, first-run model download path, how the data folders are located
- **Gate:** go with the embedded runtime if a clean machine works; otherwise apply decision 6

### Slice 16: API contract and toolkit spike (M)

- Write `docs/api.md` from the existing endpoints; add a version endpoint; list the SSE event shapes; confirm token and Host protections still apply
- Create the Rust client crate with typed requests and tests against recorded responses
- **Toolkit spike (timeboxed, same screens in each candidate):** a cast table with voice dropdowns and an audition button, and a jobs tray with a live progress bar fed by SSE; evaluate against decision 1 on a score sheet, including a Narrator pass and rendering at 125% and 150% display scaling
- **Done when:** toolkit chosen and logged; the client talks to a real helper

### Slice 17: App shell, helper lifecycle, Books and Book detail (L)

- Window shell and navigation; helper spawn, health check, restart on crash, clean shutdown; settings for the workspace location and device
- Books list with status chips; add a book through the native file dialog (copied into the workspace); draft job; Book detail stepper (extract, draft, cast, render, validate, transfer) showing each step's artifact; empty states naming the next action; errors shown as file and rule
- **Done when:** open the app, add a book, and draft it, with no browser or console

### Slice 18: Jobs tray and Render step (L)

- Always-visible jobs tray with live progress, RTF, ETA and cache hits; pause, resume, cancel; history; reattach after the app restarts; warning on quit with running jobs
- Render step: chapter picker or range, PDF page range with the resolved preview, preflight counts and time estimate, compute settings; Windows keep-awake while a job runs
- **Done when:** a multi-chapter render runs with the window closed and the app reattaches on relaunch

### Slice 19: Cast and Voices (L)

- Cast table (character, voice, speed, line counts and minutes, narrator and generic rows, first-person selector, minor characters collapsed), conflict message when the file changed on disk
- Audition clips played in the app; overrides editor with a quote picker and the "rebuild only affected lines" preflight; `cast_report` shown read-only
- Voices view and an engine selector (Kokoro, Piper, Kitten when installed)
- **Done when:** edit the cast, audition, apply an override, and rebuild without touching YAML

### Slice 20: Validate, Transfer and Model manager (M)

- Validate results as a list of file, rule and message
- Transfer: drive detection or a chosen folder, copy with progress and hash verification, an "On tablet" record
- Model manager and first-run wizard: download with checksum and progress, sideload, size and license display, delete
- **Done when:** a fresh install reaches a first valid bundle without leaving the app

### Slice 21: Design, accessibility and polish (M)

- Design tokens (paper, ink, oxblood, sage, dark by inversion) with contrast checked; serif display type for titles; the five components (table, stepper, tray, player row, chips)
- Keyboard navigation and focus order, screen-reader pass, high-contrast behavior, DPI scaling 100% to 200%, performance with large cast tables
- A UI runbook for Windows and Linux

### Slice 22: Installers and v3.0 release (M-L)

- Windows installer (or portable zip with the exe) and a Linux AppImage or tarball, each bundling the helper; NOTICE, SBOM, in-app licenses and the source offer
- README and first-run guide; uninstall keeps the workspace
- **Soak on a clean machine:** a full multi-voice novel from add-book to transferred bundle, a mid-render app kill and resume, pause and resume, a range re-render; compare the resulting bundle with the one from the Python workflow (validates; text identical; durations within tolerance)
- Tag `v3.0.0`

## 5. Gates and order

| Step | Slices | Gate |
| --- | --- | --- |
| 1 | 14 | Kitten results recorded |
| 2 | 15 | **Packaging gate:** a clean machine runs the helper |
| 3 | 16 | **Toolkit chosen** from the spike scores |
| 4 | 17, 18 | End-to-end render from the native app |
| 5 | 19, 20 | Cast, validate, transfer and models without files or console |
| 6 | 21, 22 | Release criteria |

## 6. Optional side task: render speed profile (not on the critical path)

Run before Slice 17 if you want data about speed (about an hour or two of agent time):

1. Profile a 5-10 minute chapter build: time in model inference, phonemization, and everything else
2. Compare warmed speed for the current Kokoro path, an int8 Kokoro model, and sherpa-onnx's Kokoro from Python
3. Confirm which ONNX Runtime provider the GPU run actually used (your CPU and GPU numbers were nearly identical, which suggests the GPU wasn't doing the work)
4. Record in `docs/test-log.md`

- **Decision rule:** if non-inference time is under about 15% and no alternative path beats today's by about 1.3x, leave the backend alone. A gain from a different engine path would most likely be adoptable from Python without a rewrite

## 7. v3.0 release criteria

- [~] Kitten engine result recorded (Slice 14), licenses verified
- [ ] A clean Windows 11 machine with no Python runs the installed app and completes a build; Linux build documented and tested
- [x] Add book, draft, cast edit with audition, render with ranges, validate and transfer all work in the native UI
- [x] Jobs survive closing the window; relaunch reattaches
- [x] Models install through the manager (download or sideload)
- [x] Keyboard and screen-reader pass done; scaling checked
- [x] Licenses, NOTICE, SBOM and source offer complete; decisions logged; tagged `v3.0.0` (2026-10-26)

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| The helper can't be packaged cleanly (native wheels, size, antivirus flags) | Slice 15 gate first; embedded runtime instead of a frozen exe; bootstrap fallback (decision 6) |
| Native toolkit weaknesses (tables, accessibility, text input) | Spike with a score sheet; Tauri kept in reserve (decision 7) |
| Helper crashes or hangs | Health checks and restart; jobs are separate processes; clear errors |
| API drift between UI and helper | Written contract, version endpoint, recorded-response tests |
| Model downloads (hundreds of MB, flaky networks) | Checksums, resume, sideload option |
| Windows SmartScreen or antivirus warnings on an unsigned installer | Document for personal use; signing decision later |
| Linux variety (Wayland and X11, dialogs, audio) | Best-effort support; test on one mainstream distro; AppImage |
| UI scope creep | Seven views, five components; extras go to the backlog |
| Time on v3 delays v4 Android | Gates allow stopping; Slice 14 stands alone |

## 9. Working with the agent

- RS2 and later are paused; `docs/plans/Slice15.md` is marked superseded (RS0/RS1 history stands); D-133 is amended by D-147; the RS0 workspace and RS1 fixtures stay
- Per-slice plans like the earlier ones are written when each slice starts; the next one is the new Slice 15
- The `rust-scribe` skill becomes a `rust-ui` skill (the helper contract, no logic in the UI crate, fixture-recorded client tests); `AGENTS.md` is refreshed
- The agent writes and tests code; **you** make the toolkit and packaging calls, run the clean-machine installs, check accessibility, and listen to auditions
- No new dependency without a license check (`cargo deny` for Rust, existing process for Python)

## 10. After v3

v4 (Android platform: newer Android, `targetSdk`, word-level highlight on API 26+, storage changes), v5 (iOS and iPadOS Player with xtool), and the v1.1 backlog: LLM attribution, real EPUB rendering, PDF Page view, Wi-Fi transfer, bookmarks, themes, auto-drafted cast. Backend ports to Rust remain possible later, one piece at a time, if profiling ever justifies them.
