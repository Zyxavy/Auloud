# Slice 15 Implementation Plan (Rewritten): Helper Packaging Gate

Replaces `docs/plans/Slice15.md` (the Rust-port plan, RS0/RS1 built, RS2 onward cancelled; the old file is archived at `docs/archive/v3/Slice15-rust-port.md`). Follows `docs/10-V3Roadmap.md`. Project: Auloud Scribe, Python side, plus build tooling. No Rust application code is written in this slice; the RS0 workspace and RS1 fixtures are reused only as test material.

## 1. Goal

Answer one question before any UI is built: **can the Python Scribe be packaged so that a Windows 11 machine with nothing installed runs it, offline, and produces the same bundles as your development setup?** The packaged Scribe is the "helper" that the native Rust app will start and talk to.

**Done when:**

- A helper bundle runs on a clean Windows 11 environment (no Python, no `uv`, no espeak-ng, no ffmpeg, no VC++ runtime installed, network disabled), starts its API, and completes draft, build and validate on short books
- The output matches the development setup (text and attribution identical, durations within the 50 ms tolerance, bundle validates)
- The embedded-runtime and frozen-executable options are compared on measured numbers, and the decision (including the fallback if neither works) is logged
- A Linux run shows what a Linux package needs

## 2. Inputs and what is known

- Scribe today: Python 3.11+, spaCy with `en_core_web_sm` (URL-pinned), `kokoro-onnx`, `piper-tts` with its bundled espeak data, PyMuPDF, ebooklib, pysbd, FastAPI and uvicorn (the `ui` extra), ffmpeg and ffprobe as external programs, espeak-ng installed on your machine (the doctor checks it)
- The web UI server already has a detached job runner (jobs survive a server restart), per-run token, Host-header check, SSE events and keep-awake
- Package lessons from earlier slices: new modules and static assets need explicit inclusion in the wheel, and the installed copy must be rebuilt before trusting `uv run scribe`
- RTF reference on your machine: about 4.38x on CPU (`docs/test-log.md`)

**Unknowns this slice resolves:** whether Kokoro needs a system espeak-ng, which native DLLs need the Microsoft C++ runtime on a bare machine, how large the bundle is, how Defender reacts, and whether a frozen executable can still spawn its own detached jobs.

## 3. Scope

| In | Out (later slices) |
| --- | --- |
| A headless helper entry point with a ready handshake and parent watchdog | The Rust UI and API client (Slice 16 onward) |
| Embedded-runtime bundle build script (Windows, Linux) | The installer and first-run model downloader (Slices 20 and 22) |
| A frozen (PyInstaller, onedir) build for comparison | GPU providers; macOS |
| Clean-machine runs, equivalence checks, lifecycle tests | Code signing |
| `docs/archive/v3/packaging.md` with the decision | Any Rust application logic |

## 4. Key decisions (log from D-148)

1. **Embedded runtime is the default candidate.** A standalone Python distribution plus installed packages in its own folder, run as `python -m scribe ...`. It keeps native packages unmodified, makes spawning detached jobs trivial (the interpreter is a real `python.exe`), and avoids the antivirus problems typical of frozen single-file executables.
2. **Frozen onedir build as the comparison**, not onefile. Judged on the same metrics. A frozen executable also needs a re-entry flag so the job runner can spawn itself; note that as a cost.
3. **Reproducible by lockfile:** exact versions and hashes from the lock; the Python distribution and the ffmpeg build are pinned with URL and hash; the build writes a `helper-manifest.json` of versions and file hashes.
4. **Install layout:** a read-only helper folder with a short path (long-path risk on Windows), and per-user data folders for workspace, models, job history, logs and settings. The helper takes these as arguments or environment variables; it never writes into its own folder.
5. **Startup protocol:** the parent starts the helper with a port of 0 and a token in the environment; the helper prints one JSON line (`ready`, port, versions: Scribe, spec, API) and then serves on `127.0.0.1` only. The helper exits when the parent process disappears. Detached jobs deliberately do **not** die with the helper.
6. **Windows runtime DLLs:** if the clean machine lacks the Microsoft C++ runtime, bundle the redistributable DLLs app-locally (permitted by Microsoft's redistribution terms) rather than requiring an installer.
7. **ffmpeg:** bundle a pinned build and record whether it is LGPL or GPL (it runs as a separate program, but the source-offer obligations differ); the final choice is made in Slice 22.
8. **Models stay outside the bundle** (Kokoro is hundreds of MB); this slice uses sideloaded copies. Download and checksum handling arrives with the model manager.
9. **Gate thresholds (proposals, adjust if you disagree):** cold start to ready in 10 s or less, warm start in 5 s or less, idle memory 300 MB or less, build speed within 10% of the development machine, zero Defender detections, works with networking disabled. Size is recorded and judged by you.

## 5. Work packages

### HP0: Prep (S)

- Inventory the runtime: every installed package with size, native libraries and data files, what the spaCy model and Piper bring, and where Kokoro gets its phonemizer
- Read the web UI server, detached job runner, doctor and path handling so the helper entry point reuses them
- Write the Windows Sandbox config (`.wsb`): helper and fixtures mapped read-only, a writable results folder, networking disabled; confirm it boots
- Create `docs/archive/v3/packaging.md` with the empty metrics sheet; pin the Python version; log decisions 1-9
- **Verify:** inventory and sandbox boot recorded; decisions logged

### HP1: Helper entry point (M)

- A headless command (for example `python -m scribe helper`) with arguments for workspace, models, data and log folders, `--port 0`, the parent process id, and the token from the environment
- Prints the single ready line; binds `127.0.0.1` only; no browser; the token is required on every request; the Host-header allow-list includes the chosen port; logs go to a file
- Parent watchdog (Windows and Linux); graceful shutdown; detached jobs unaffected
- `scribe ui` keeps working exactly as before
- **Verify (tests):** ready line format, token enforcement, random port, watchdog with a simulated parent, Host check, unchanged `scribe ui`; the full Python suite stays green

### HP2: Embedded bundle build (M-L)

- `scribe/dev/package_helper.py` (run with `uv`, alongside the other export scripts): fetch the pinned standalone Python for the target, install locked dependencies with hashes into its site-packages, install the built Scribe wheel (static assets included) and the spaCy model wheel, strip caches and tests, byte-compile
- Add the pinned ffmpeg and ffprobe, and the app-local C++ runtime DLLs for Windows if HP4 shows they are needed
- Write `helper-manifest.json`; verify two builds produce the same hashes; produce a zip
- **Verify:** on your development machine, `helper\python.exe -m scribe doctor` passes; manifest reproducible

### HP3: Frozen comparison build (S-M, timeboxed to about a day)

- PyInstaller in onedir mode for the helper entry point, including hidden imports and data files for spaCy and its model, onnxruntime, PyMuPDF, kokoro-onnx and Piper
- Same ready protocol; record how detached jobs are spawned (the re-entry flag) and anything that breaks
- If it cannot be made to work in the timebox, the blockers are the finding
- **Verify:** same smoke checks as HP2, or a written list of blockers

### HP4: Clean Windows 11 run (M)

- `scribe/dev/run_gate.ps1` runs inside the sandbox with networking off and no Python: start the helper, wait for ready, then via the API and the CLI: draft and build the Alice fragment and the C&P fragment with a Kokoro voice, build a short chapter with a Piper voice, draft the PDF golden, render one `voices --sample` clip, validate every bundle, run `doctor`; write a results file
- Record per run: ready time (first launch and second), idle memory, build speed, Defender detections (check the Defender threat log), any missing DLL or file errors
- **Expected finding:** a missing Microsoft C++ runtime DLL; fix by bundling it (decision 6)
- **Extra cases:** an install path near the 260-character limit, a user path with spaces and non-ASCII characters, and a second launch after the first
- Run both variants (embedded and frozen)
- **Verify:** results files for each variant and case saved under `docs/`

### HP5: Output equivalence (S-M)

- Compare helper output with the development setup for the same inputs: `script.json` and `text/*.json` identical, attribution identical, manifest equal apart from ids, timestamps and paths, audio durations and sentence timings within 50 ms, `scribe validate` passes on both
- Run the speakers gold-set evaluation using the helper's interpreter and confirm the same accuracy as the development result (92.5% strict on the C&P set)
- **Verify:** a comparison report with every item passing, or a list of differences with causes

### HP6: Lifecycle and API behavior (S-M)

- From outside the helper (a small script): ready handshake, token required, wrong Host rejected, SSE progress during a build, pause, resume and cancel
- **Kill the helper mid-build:** the detached job must keep running, and a new helper must see and reattach to it; kill the parent: the helper exits; port already in use; two helpers on one workspace are refused by the lock
- Repeat on the frozen variant and record differences
- **Verify:** every behavior recorded as pass or fail with notes

### HP7: Linux check (M)

- Build the embedded bundle for Linux and run a subset of HP4 and HP6 on Ubuntu 22.04 in a container or WSL with no system Python: draft and build a short book, validate, API handshake, offline
- Record the system libraries needed (check the native libraries' dependencies), and note what an AppImage would need; no AppImage is built here
- **Verify:** results saved; required system libraries listed

### HP8: Measurements and report (S)

Fill the sheet in `docs/archive/v3/packaging.md`:

| Metric | Embedded | Frozen | Dev machine |
| --- | --- | --- | --- |
| Size unzipped, zipped, file count |  |  |  |
| Cold start and warm start to ready |  |  |  |
| Idle memory |  |  |  |
| Build speed (RTF) on the C&P fragment |  |  |  |
| Defender result and first-run scan time |  |  |  |
| Long path, spaces, non-ASCII |  |  |  |
| Offline behavior |  |  |  |
| Detached job survives helper kill |  |  |  |
| Build reliability and reproducibility |  |  |  |

### HP9: Decision and close-out (S)

- Apply the gate thresholds (decision 9); choose embedded, frozen, or the fallback; if neither passes, apply the roadmap's stop-loss (a first-run bootstrap, or a documented one-time setup)
- Finalize `docs/archive/v3/packaging.md`: chosen approach, the helper contract (arguments, environment, ready line, folders, versions), build steps, pitfalls found
- Log decisions; D-133/D-147 and the superseded plans are already recorded, so verify rather than repeat; update the roadmap with the findings that shape Slice 16 (endpoint list and ready protocol)
- Tag `slice-15`

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | HP0, HP1 | have a headless helper and a working sandbox |
| 2 | HP2 | unzip a Python bundle that runs Scribe on your machine |
| 3 | HP4 (embedded), HP5, HP6 | see it run clean, offline, with identical output |
| 4 | HP3, HP4 (frozen) | compare against a frozen build |
| 5 | HP7, HP8 | know the Linux needs and the full numbers |
| 6 | HP9 | decide and close |

## 7. Fallback paths (decided at HP9)

| Result | Decision |
| --- | --- |
| Embedded works on a clean machine | Proceed as in the roadmap |
| Only the frozen build works | Accept it, with the antivirus caveat, and adapt job spawning |
| Neither works cleanly | Stop-loss: a first-run bootstrap that builds the environment (needs internet once), or documented prerequisites |
| Works but too large | Make PyMuPDF, Piper and spaCy optional on-demand packs; keep Kokoro and models separate |
| Tauri reserve | Packaging problems don't change that option; it needs the same helper |

## 8. Definition of done

- [ ] Helper entry point built and tested; `scribe ui` unchanged; Python suite green
- [ ] Reproducible embedded bundle for Windows and Linux; a frozen comparison build or documented blockers
- [ ] Clean-machine runs recorded for every variant and extra case
- [ ] Output equivalence report passes (or differences explained)
- [ ] Lifecycle behaviors recorded, including job survival and reattach
- [ ] Metrics sheet complete; decision logged against the thresholds; `docs/archive/v3/packaging.md` final
- [ ] Tagged `slice-15`

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Missing Microsoft C++ runtime or other DLLs on a bare machine | Test in the sandbox first; bundle the DLLs app-locally |
| Defender or SmartScreen flags the helper | Embedded runtime preferred; measure both; code signing is a later decision |
| Windows path length and non-ASCII user paths break something | Short install path, explicit tests in HP4 |
| Frozen build can't spawn detached jobs or misses data files | Re-entry flag; blockers recorded as findings |
| Bundle too large | Optional packs for PDF, Piper and spaCy; models stay outside |
| Kokoro needs a system espeak-ng | Detected in HP4; bundle the data or library; record |
| ffmpeg license obligations | Record the build type now; decide in Slice 22 |
| Output differs from the development setup | HP5 comparison with identical pins and exact versions |
| Windows Sandbox unavailable on your Windows edition | Use a Hyper-V or VirtualBox VM, or a fresh user on a spare machine (see below) |

## 10. Prerequisites for you

- [ ] Windows 11 edition: Windows Sandbox needs Pro, Enterprise or Education with virtualization enabled; Home needs another clean-machine route (a VirtualBox VM or a spare machine)
- [ ] Models available for sideloading: Kokoro files and one Piper voice
- [ ] The short test books (Alice fragment, C&P fragment, PDF golden); Ubuntu in WSL or a container for HP7

## 11. Working with the agent

- One work package per session; it writes the helper command, the build tooling, the test scripts and the report; **you** run the sandbox and any spare-machine tests, judge Defender prompts, and make the decision at HP9
- It must not claim clean-machine results without a run; every number comes from a recorded run
- No new dependency without a license check; log decisions as they are made
- Commit per work package (`HPn: summary`); tag `slice-15`

## 12. Next

Slice 16 uses the helper contract from this slice: write the API document, build the typed Rust client against a real helper, and run the UI toolkit spike.
