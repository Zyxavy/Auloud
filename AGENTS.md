# Auloud: agent instructions

Auloud turns ebooks into multi-voice audiobooks on a PC (**Scribe**, Python) and plays them on an old Android tablet (**Player**, Kotlin) with read-along text. Scribe renders the best-quality multi-voice audio; since v2 the tablet also renders its own audio on-device (System TTS, plus Piper in the `full` flavor) and streams unrendered chapters live.

Target device: Samsung Galaxy Tab E (SM-T560NU), Android 7.1.1, `minSdk 24`, about 1.5 GB RAM (unverified). Language: English only.

## Read first
- `docs/03-BundleSpec.md` (or `spec/bundle.md`): the contract between Scribe and Player. Treat as law.
- `docs/04-Roadmap.md`: the current slice and its checklist.
- `docs/10-V3Roadmap.md`: the v3 plan (Scribe Rust/Tauri port plus KittenTTS).
- `docs/plans/` holds the slice plans (`docs/archive/v1/plans/` for v1, `docs/archive/v2/plans/` for v2).
- `docs/06-PlayerDesign.md`, `docs/05-ScribeDesign.md`: design for each side.
- `docs/DECISIONS.md`: past decisions. Do not contradict them without asking.

## Repo layout
```
player/   Android app (Kotlin, Compose, Media3, Room). Core flavor: Apache-2.0; full flavor: GPL-3.0 (see player/NOTICE).
scribe/   Python CLI + library. License: AGPL-3.0-or-later
spec/     bundle spec and shared test fixtures
docs/     PRD, architecture, designs, roadmap, test plan, decisions
```

## Hard constraints
**Player**
- `minSdk 24`. Never use APIs above 24 without a version guard. `java.time` is not available on API 24 without desugaring.
- No `INTERNET` permission. No cloud TTS. The tablet renders and streams with on-device voices only (System TTS everywhere, Piper packs in `full`); Scribe on the PC stays the multi-voice path.
- Only Apache-2.0/MIT/BSD-compatible dependencies in `core`. No GPL code in `core`; the `full` flavor is GPL-3.0 by decision (D-126 option A) and carries its notices.
- Stream audio; load only one chapter's JSON at a time; assume 1.5 GB RAM.
- Storage goes through the `BundleStorage` interface.

**Scribe**
- Python 3.11+, Windows 11 first. Library holds the logic; the CLI is a thin wrapper.
- ffmpeg is run as a separate process, never linked.

**Both**
- Do not change the bundle format without updating the spec first and adding a `DECISIONS.md` entry.
- Never commit copyrighted books or audio. Test content is public domain or self-made.
- Avoid using em-dashes (—) and '→'.

## Commands (adjust once the projects exist)
| Task | Command |
|---|---|
| Build Player | `gradlew.bat :app:assembleCoreRelease :app:assembleFullRelease` (Windows, in `player/`) |
| Player unit tests | `gradlew.bat :app:testCoreDebugUnitTest :app:testFullDebugUnitTest --no-daemon` (in `player/`) |
| Lint | `gradlew.bat :app:lintCoreDebug :app:lintFullDebug` (in `player/`) |
| Install on tablet | `adb install -r player/app/build/outputs/apk/core/debug/app-core-debug.apk` |
| Logs | `adb logcat -s Auloud:V` |
| Scribe tests | `uv run pytest` (in `scribe/`) |
| Validate a bundle | `uv run scribe validate <bundle-dir>` |

## How to work
1. Work on **one work package at a time**, from the current slice plan. Say which one.
2. Write or update tests with the code. Run the build and unit tests before saying anything is done.
3. **Never claim something is verified on the tablet.** You cannot test screen-off behavior, battery, or Samsung quirks; list these as "needs device test" for the user.
4. Do not add or upgrade dependencies without asking. When proposing one, check its current docs (Context7) for `minSdk 24` support and license.
5. If the spec is ambiguous, or a change would touch the bundle format, stop and ask.
6. Small commits, one per work package: `WPn: short summary`.
7. Tick roadmap checkboxes only for items you actually verified; log decisions and surprises in `docs/DECISIONS.md`.
8. Ask before destructive commands: `git push`, deleting files, `adb uninstall`, `adb shell pm clear`, or any `adb shell` command that writes.

## Skills (in `.opencode/skills/`)
`auloud-bundle`, `android-api24`, `slice-workflow`, `device-test`, `scribe-python`.