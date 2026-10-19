# UI runbook: fresh install to first render

This is the path from a bare Windows or Linux machine to a rendered
chapter, plus the failure table. Slice 21 RB1; the packaged zip is Slice
22 IN1 and is step 1. The zip carries a generated `licenses\` folder
(NOTICE, third-party list, one canonical text per license, per-component
license files, the source offer and a CycloneDX SBOM), and the app shows
three of those files under `Settings` > `Licenses` (Slice 22 LG1). The zip
on disk was staged before that step existed, so a zip that carries them
comes from the next `scribe\dev\pack_portable.ps1` run.

## Who ran what

No line in this document was run end to end on a clean machine. Every
Windows step names the source it was read from; the Linux steps are the
differences found in the same sources, and are for the owner to run and
mark. Nothing here claims a device or OS test.

| Section | OS | Ran by | State |
| --- | --- | --- | --- |
| Windows steps | Windows 11 | not run by the agent | read from source, line named per step |
| Packaged zip (step 1, rule 2 of step 3) | Windows 11 dev machine | agent, 2026-10-18 | stage folder unzipped: helper ready in 0.6 s, `/api/health` ok, the app spawned the bundled helper and exited clean. No clean-machine run: this machine has Python and the repo |
| Linux differences | Linux | not run | owner to confirm each line |

Legend: `[code]` means read from the named source, not observed.

## 1. What you need

- Windows 11 or Linux, 64-bit.
- Rust, at the workspace pin (`scribe-rs/rust-toolchain.toml`). `[code]`
- Python 3.11+ with `uv`, only when you use the dev-tree helper (the
  packaged helper of Slice 22 does not need it). `[code]`
- `ffmpeg` and `ffprobe` on `PATH`: only for a build from source (the
  helper runs ffmpeg as a separate process, never linked, and the packaged
  zip carries its own copy). `[code]`
- espeak-ng 1.52.0 for Kokoro phonemisation, per `README.md`.
- About 400 MB free for the Kokoro pair (see step 4) plus book and
  output space.
- A network only for the model download in step 4; sideload works
  offline.
- Optional: the tablet on your LAN for step 8.

## 2. Windows: zero to first render

1. **Get the app: the packaged zip, or a build.** `[code]`

   The packaged zips are `logs\portable\Auloud-Scribe-3.0.0-cpu.zip` and
   `...-gpu.zip`, written by `scribe\dev\pack_portable.ps1 -Variant cpu|gpu`
   (it builds the app, builds the helper with `scribe\dev\package_helper.py`,
   then self-checks the folder and the zip). Take `-cpu` for a machine with no
   usable NVIDIA driver: it carries the CPU TTS runtime instead of the CUDA
   stack, so it is a 286 MB zip instead of a 1.9 GB one (783 MB unpacked
   against 3.3 GB) and renders slower (D-187). Unzip it anywhere and run `scribe-ui.exe` from the folder it
   creates, then skip to step 3. That machine needs Windows 11 64-bit and
   nothing else: no Rust, no Python, no `ffmpeg` on `PATH`, because
   `helper\embedded\` carries the interpreter, the installed dependencies
   and ffmpeg (`scribe-ui/src/helper.rs`, `resolve_command`, rule 2 of
   step 3). Deleting that folder is the uninstall: the workspace and the
   downloaded models live outside it and stay.

   To build from source instead:

   ```powershell
   cd scribe-rs
   cargo build --release
   ```

   The binary is `scribe-rs\target\release\scribe-ui.exe`; the window
   title is `Auloud Scribe` (`scribe-ui/src/main.rs`, `main`).

2. **Start it.** `[code]`

   ```powershell
   .\target\release\scribe-ui.exe
   ```

3. **Watch the helper come up.** The first frame shows `Starting helper…`
   then the Books screen (`scribe-ui/src/main.rs`, `App::ui`). The app
   spawns the helper with `--workspace DIR --port 0` and the
   `AULOUD_HELPER_TOKEN` environment variable, and waits up to 120 s for
   a ready line (`scribe-ui/src/helper.rs`). A workspace of `~/Auloud`
   is used until you change it in step 5.

   Helper discovery, in order (`scribe-ui/src/helper.rs`,
   `resolve_command`): `[code]`

   1. The `Helper path` from Settings, when it is a file.
   2. `helper\embedded\python\python.exe` next to the app.
   3. `helper\frozen\scribe-helper\scribe-helper.exe` next to the app.
   4. The dev venv, walking up to five folders from the app looking for
      `scribe\.venv\Scripts\python.exe`; `python -m scribe` then runs
      with that tree as its working directory.
   5. Nothing found: the halt screen `No helper connected.` with the
      reason `no helper found` (`scribe-ui/src/machine.rs`).

4. **Get the Kokoro models** (Models screen). The screen shows the
   folder (`<workspace>\models`), the license line, and two pinned
   files: `kokoro-v1.0.onnx` (325,505,369 bytes) and
   `voices-v1.0.bin` (28,214,398 bytes) (`scribe-ui/src/models.rs`).
   Each row reads `missing`, `ready (pinned, SIZE)` or
   `present, not pinned (SIZE)`. `[code]`

   - `Download` fetches from the pinned kokoro-onnx release and checks
     the sha256. It needs network.
   - `Sideload…` takes a file you already have (`onnx` or `bin`) and
     checks the same digest. This is the offline path.
   - `Delete` removes the file; `Check again` re-hashes.

5. **Check Settings.** Fields: `Workspace:`, `Device:` (`auto`, `cpu`,
   `cuda` radios), `Helper path (empty = discover):`, then `Save` and
   `First-run wizard` (`scribe-ui/src/main.rs`, `settings_ui`). Settings
   live in `%APPDATA%\Auloud\scribe-ui.settings` and fall back to
   defaults when missing or corrupt (`scribe-ui/src/settings.rs`).
   `[code]`

   The `First-run wizard` is three skippable steps: welcome, models,
   device (`scribe-ui/src/main.rs`, `wizard_ui`).

6. **Add a book** (Books screen, `Add book`). The native file dialog
   takes an EPUB or a PDF; the copy lands in the workspace and a draft
   job starts. The row shows a status chip, `draft: <state>` or
   `rendering N%`, and the detail stepper below shows the six steps
   (`extract`, `draft`, `cast`, `render`, `validate`, `transfer`) with
   `done`, `pending` or `FAILED` chips and their notes
   (`scribe-ui/src/books.rs`). `[code]`

7. **Cast and render.** For the selected book, the Cast table has one
   row per character: the character and kind, a voice dropdown, a speed
   field, and line and minute counts. The Render section takes a
   chapter range or a PDF page range (`Chapters (range):`, `Pages:`),
   a device and a render speed (`Render speed:`: `slow` renders 1
   chapter at a time, `balanced` 4, `fast` up to 8), then `Check plan`
   for the preflight counts and `Start render`
   (`scribe-ui/src/main.rs`, `cast_ui`, `render_ui`).
   `[code]`

   The jobs tray at the bottom shows live jobs with `Pause`, `Resume`
   and `Cancel`, and the same live rows plus history on the Jobs
   screen. `[code]`

8. **Validate and send.** The Validate section re-runs validation and
   lists file, rule and message per bundle. The Send section takes
   `Tablet IP:` and `Code:` (the pairing code shown by the tablet's
   Receive screen) and a `Send` button, and refuses a bad bundle with
   `No valid bundle (see Validate); the tablet will refuse it.`
   (`scribe-ui/src/main.rs`, `validate_ui`, `send_ui`). `[code]`

9. **First render done.** The finished bundle is under the workspace;
   the Jobs screen history shows `done`. Copy it to the tablet
   (`/Auloud/` on internal storage or an `Auloud/` folder on the microSD
   card), or leave the Send step to do it over Wi-Fi.

## 3. Linux: the differences

Only the differences from section 2 are listed. Every line below is
`[code]` and unrun.

1. Build prerequisites beyond the Rust pin: `ffmpeg`, and the headers
   the audio and TLS stacks need. `ureq` here uses
   `native-tls-no-default`, so a build needs OpenSSL headers
   (`scribe-ui/Cargo.toml`). rodio pulls cpal, which needs ALSA headers
   on Debian-like systems. Expect X11 or Wayland client libraries and a
   working Vulkan or GL driver for wgpu.
2. The same discovery order applies, with the Unix names: `python3`
   instead of `python.exe`, `bin` instead of `Scripts`, and a bare
   `scribe-helper` instead of `scribe-helper.exe`
   (`scribe-ui/src/helper.rs`, `exe_python`, `exe_scripts`,
   `exe_helper`).
3. Settings live in `~/.config/auloud/scribe-ui.settings` and the
   default workspace is `~/Auloud` (`scribe-ui/src/settings.rs`).
4. Keep-awake is a Windows call (`SetThreadExecutionState`) and is a
   best-effort no-op elsewhere (`docs/DECISIONS.md`, UI job system
   entry). Expect the screen to sleep during a long render unless you
   change the desktop setting.

## 4. Failure table

The left column quotes the fixed text on screen; the right is what to do.
A startup failure then appends `: <the child's last 500 characters>`, or
`; see helper.log in the workspace` when the child printed nothing, and
the missing-helper reason continues past `no helper found`. All lines
were read from source, not observed.

| On screen | Cause and fix |
| --- | --- |
| `No helper connected.` with `no helper found` | Discovery found nothing. Set `Helper path` in Settings (`scribe-ui` stays reachable on halt screens), then `Retry`. |
| `helper path is not a file: PATH (fix it in Settings)` | The configured path is wrong. Clear it to fall back to discovery, or point it at the helper. |
| `cannot start PATH: <os error> (fix it in Settings)` | The helper path exists but cannot run (permissions, wrong build). Check Settings. |
| `helper exited during startup (code N)` | The helper started and died. The message carries the last 500 characters of its output; the full log is `helper.log` in the workspace. |
| `no ready line in 120 s (killed silent child)` | The helper never printed its ready line and was killed. See `helper.log`. A first start on a cold machine is the usual cause. |
| A message containing `ui-lock-held` | The workspace is already served by another helper process, normally another Auloud window. When no window is open, that process is orphaned: stop it in Task Manager, then press `Retry`. |
| `Helper crashed.` | The helper died after startup. `Retry` restarts it; running renders are detached and reattach by job id. |
| `helper died N times in a row; retry manually` | The restart loop gave up. Fix the cause from `helper.log` first. |
| `Helper speaks a contract this app does not know.` plus `helper API X, app API Y: upgrade one side so both agree (docs/api.md), then Retry` | The app and the helper are different versions. Upgrade the side that is behind. |
| `cannot bind http://127.0.0.1:PORT: <os error>` | The app refused the address it builds from the ready line's port (the client accepts a loopback origin only), so the ready line was malformed. A busy port has no message of its own: the helper asks for an ephemeral port (`--port 0`). Retry; if it repeats, `helper.log` holds the ready line. |
| Models screen: a file reads `missing` | Kokoro is not installed where the helper looks. Use `Download` or `Sideload…` (step 4). A render that needs Kokoro will fail until then. |
| Models screen: a file reads `present, not pinned (SIZE)` | The file is there but its digest is not the pinned one. It may still work; download replaces it if not. |
| Send section: `No valid bundle (see Validate); the tablet will refuse it.` | Validation failed for this book. Fix the listed files and rules, then re-validate. |

## 5. What this runbook does not cover

- NOTICE, the SBOM and the source offer travel inside the zip and the app
  shows NOTICE, the third-party list and the source offer under `Settings`
  > `Licenses` (LG1); there is no legal review and no installer. The
  portable zip is the Windows artifact, and the folder it unpacks to is
  the install (Slice 22 IN1).
- Anything on the tablet: screen-off playback, battery and Samsung
  quirks stay with `docs/07-TestPlan.md` and its device test log.
- The Python CLI (`uv run scribe ...`), which the README covers.
