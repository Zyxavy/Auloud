# Test plan (v2)

Covers the Player 2.1.0 on-device path (Slices 8-13: engines, EPUB ingest, background rendering, voice settings, the Slice 12 complete pass, Slice 13 live streaming). The v1 plan is frozen at `docs/archive/v1/07-Testplan.md` and is left untouched. Evidence lives in `docs/test-log.md` (v2 entries); tuning numbers live in `docs/constants.md`.

Status key: `[ ]` not run, `[x]` done. Only tick a box from the evidence named on its line.

## 1. Automated suites (JVM, agent-runnable)

- [x] Player unit suites, both flavors (`:app:testCoreDebugUnitTest :app:testFullDebugUnitTest`, from `player/`, with `--no-daemon`): green at VC5, 2696 tests, three consecutive full runs, 0 failures; lint clean on both flavors. Re-run before any release claim; record counts in `docs/test-log.md`.
- [x] `:app:licenseScan` passes for both flavors (`core` carries no sherpa/onnxruntime/espeak files or markers; `full` carries its native libs plus its notices). Re-run command plus APK entry counts in `docs/test-log.md` (VC1 entry).
- [x] `:app:releaseManifestCheck` passes every merged release manifest (no `INTERNET`). Re-run command in `docs/test-log.md` (VC2 entry).
- [x] VC3 JVM compatibility matrix (v1 MP3, Scribe PC, and PC multi-voice bundles play in the v2 reader; a 2.0 book hits the v1 refusal branch): 4/4 green, recorded in `docs/test-log.md`. The Room v1-to-v2 migration has no JVM test (v1 never exported its schema; no Robolectric/room-testing in the harness), so data survival is proven only by the section 2 over-install below.

## 2. Device rounds (DEVICE-RUN, reference device: Galaxy Tab E)

Do not tick these from JVM results; each needs a physical device.

- [x] Release smoke checklist on the signed APK: device verdict 2026-10-10
  ("good and complete", flavor not specified): `docs/ReleaseSigning.md`, all boxes ticked.
- [x] VC3 over-install: device verdict 2026-10-10 ("good and complete"): library and positions survive the same-key upgrade: `docs/ReleaseSigning.md` ("VC3 over-install procedure"), boxes ticked.
- [x] VC7 soak: maintainer reports multi-day soak good (one-line verdict 2026-10-10, no per-box numbers). Full box list in `docs/plans/Slice12.md` (VC7); no `soak-log-v2.md` daily log was kept.
- [x] VC0 gates: SysLong bench waived, hypothesis recorded (D-131, amended: streaming re-opened for live architectures); spike retained as the measurement tool; D-066 decided (D-126 option A), contribution policy DCO (D-127).

## 3. Release gate (VC8)

Run when sections 1-2 are green: the checklist in `docs/plans/Slice12.md` (section 6 definition of done) plus `docs/08-Licenses.md` section 7 plus the VC1-VC7 outcomes. Done 2026-10-10: tagged `slice-12` and `v2.0.0`. Slice 13 streaming (ST0-ST7) is covered in `docs/plans/Slice13.md` plus `docs/streaming.md`, tagged `slice-13` and `v2.1.0`.

## 4. What stays open by design

- Sideloaded Piper pack licenses are per-pack (check each `MODEL_CARD`); no single box can close them.
- Scribe dependency rows in `docs/08-Licenses.md` section 3 that are still unverified are PC-side work, not Player release blockers.
- The encoder-offset gate is closed by default-acceptance (D-132): offset 0 stands, nonzero still refused; a measured nonzero reopens it.
