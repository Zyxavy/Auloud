# Slice 12 Implementation Plan: v2.0 Complete Pass

Project: Auloud Player (main), docs and release engineering. Follows `16-V2-Roadmap.md` (Slice 12). Everything v2.0 promises is built (Slices 8-11); this slice closes the open gates, settles the license, proves it with a full-novel soak on the tablet, and ships `v2.0.0`.

## 1. Goal

A release candidate you can trust: the license question answered and implemented, both flavors building clean, the v1-to-v2 upgrade proven, constants tuned from real measurements, and a multi-day on-device soak (import, render, listen) passed.

**Done when** (v2 release criteria from the roadmap):

- A full novel imported on the Tab E is rendered on-device and listened to for days with correct read-along (soak)
- At least two engine tiers are selectable with a per-device recommendation, and switching re-renders only what is needed
- Narrator and dialogue voices are clearly distinct and level-matched; highlight within about 300 ms on device-rendered audio
- No `INTERNET` permission; the license path is implemented and documented; all new "Verified" boxes ticked

## 2. Open gates to close first (VC0)

1. **SysLong verdict.** Run the 5-minute long-utterance bench in the spike app on a cool tablet. It decides streaming (Slice 13): 1.3x or more means streaming is realistic, around 1.0x means background rendering only, lower means neither. It also sets the default render-ahead window. The spike app is still needed for this, so **delete the spike module (S7D) only after the verdict**.
2. **D-066 license call** (below).
3. **Contribution policy** (DCO sign-off or a CLA), the last open box in `08-Licenses.md`.
4. **Owed admin:** the tracker ticks and close text that were held back when the GitHub tools were down.

## 3. The D-066 decision (needs your call)

Facts from the Slice 7 work: the sherpa-onnx native library in the `full` flavor statically links espeak-ng, which is GPL-3.0, and the PC Piper runtime is GPL-3.0-or-later too. So a distributed `full` APK is a GPL-3.0 combined work. Apache-2.0 code (your Player) can be included in a GPL-3.0 work, so this is allowed; the question is what you distribute and under which terms. This isn't legal advice.

| Option | What it means | Good for | Cost |
| --- | --- | --- | --- |
| **A. Two licensed flavors** | `core` stays Apache-2.0 and is the main release; `full` is distributed under GPL-3.0 with a source offer | Wanting Piper available to users, and an open-source release | GPL compliance work for `full`: license texts, source offer, reproducible-build notes, per-flavor notices |
| **B. Release `core` only** | Publish `core` APKs; `full` is build-from-source with documented steps, no APK distribution | Minimal legal overhead; you still use `full` yourself | Piper is not available to ordinary users |
| **C. Whole Player under GPL-3.0** | One license for everything | Simplicity of message | Loses the permissive license for downstream users; changes D-015 |
| **D. Find a non-GPL phonemizer path** | Replace the espeak-ng dependency | Cleanest licensing | Research and quality risk; out of scope for v2.0 |

**My recommendation:** A if you want Piper in public releases (the flavor split was built for this); otherwise B. Because your measured Piper speed on the tablet is 0.36x and System TTS is the tablet's primary engine (D-078), B costs the tablet little. Either way, no personal-use obligation arises until you distribute.

## 4. Work packages

### VC0: Close the gates (S)

- Run SysLong; record the number in `docs/test-log.md`; update the roadmap (streaming go or no-go) and the default render-ahead window
- Log the D-066 decision with its compliance checklist; log the contribution policy
- Delete the spike module after the verdict; post the owed tracker updates
- **Verify:** decisions logged; spike gone; roadmap reflects the verdict

### VC1: License implementation (M)

Per the D-066 choice.

- Layout of license files per module and flavor (the sherpa module gets its own `LICENSE`); `NOTICE` and `THIRD_PARTY_LICENSES.md` per flavor; in-app licenses screen entries for jsoup, sherpa-onnx, onnxruntime, espeak-ng, Piper, and the voice models
- For `full` under A: the GPL-3.0 text shipped in the APK, a written source offer, build instructions pinning the exact sherpa-onnx AAR version, and README wording
- Keep the automated scan: the `core` APK contains no espeak-ng or sherpa-onnx files, and the `full` APK carries its notices
- Tick every "Verified" box in `08-Licenses.md`; per-voice license table complete
- **Verify:** scan passes for both flavors; in-app licenses match the notices; documents reviewed

### VC2: Release engineering (M)

- Version `2.0.0` and version code; signed builds for the flavors you ship
- R8 and keep rules verified for both flavors (the native bridge in `full` is the risky part); merged manifests checked for no `INTERNET` permission
- ABI and size plan for `full` (the spike APK was 130 MB with all ABIs): ship 32-bit and 64-bit ARM only, consider per-ABI APKs; model packs stay separate
- Confirm debug-only features (beep engine, overlays) are unreachable in release
- Release smoke checklist on the signed APK for the critical paths from Slices 1-11 (import, playback, screen-off, read-along, render, voices)
- **Verify:** release APKs install on the tablet and pass the smoke checklist

### VC3: Upgrade path (S-M)

- On the tablet: install the signed `v1.0.0` APK, import books and create progress, then install `2.0.0` over it (same signing key); library and progress must survive the Room migration
- If feasible, add a JVM migration test that applies the exported v1 schema to the v2 schema; otherwise document the manual procedure
- Compatibility matrix: v2 plays v1 (MP3, Scribe) bundles and PC multi-voice bundles; a v1 Player refuses 2.0 books cleanly (the version gate)
- **Verify:** over-install keeps data; matrix results recorded

### VC4: Constants retune (S-M)

- From real renders (Slice 10-11 acceptance and the soak): RTF bands, the 400 ms-per-word estimate, the 40 °C temperature guard, spool size, estimate accuracy
- Record in `docs/constants.md`: value, source measurement, date
- **Encoder offset gate (open since Slice 10):** finalize refuses a nonzero `encoder_offset_ms` because it contradicts the first-start-0 rule. If the beep measurement shows a nonzero offset, decide how to handle it (shift the audio's start, or amend the spec) before release; if it measures zero, record that and close the question
- **Verify:** `constants.md` complete; any changed constants logged as decisions with tests updated

### VC5: Quality sweep (S-M)

- **Sentence agreement:** compare the Android sentence iterator's output with Scribe's on quote-heavy books (a debug export of splits compared with Scribe's `script.json` on the PC); record agreement for sentences and for narration/dialogue runs; improve rules only if the numbers are poor
- Fix the `UnrenderedReaderViewModelTest` debounce flake (inject virtual time)
- Lint clean, TODO/FIXME sweep, and any cheap backlog items worth closing (for example complete-book bulk delete routing)
- **Verify:** agreement numbers in `docs/test-log.md`; suites green repeatedly (run three times to prove the flake is gone)

### VC6: Docs (S-M)

- README v2: import, voices, render, listen workflow; requirements; flavors and model packs; preparing System TTS (offline voices); limitations (two voices on device, English only, render times, tested on one tablet)
- Update `04-Roadmap.md`, `07-TestPlan.md` (v2 sections), `08-Licenses.md`, the docs index, DECISIONS and test-log; short v2 addendum to `01-PRD.md`
- **Verify:** someone following the README from a fresh install gets from EPUB import to a playing rendered book

### VC7: v2 soak test (you, several days including render nights)

Log daily in `docs/soak-log-v2.md`, on the release candidate with the flavor you ship.

- [ ] A public-domain novel of 10+ hours imported on the tablet (for example the Sherlock Holmes collection or *Pride and Prejudice*)
- [ ] Rendered on-device with System TTS, charging-only, over one to two nights; listening starts on early chapters while later ones render (render-ahead)
- [ ] Listened over several days in all three modes; at least two screen-off stretches of 2+ hours
- [ ] At least one mid-render kill (swipe away or reboot) and resume without redoing finished sentences
- [ ] A voice change on the book, then a re-render of a few chapters while listening to others; position preserved
- [ ] No crashes or service kills in playback; render kills counted and acceptable; temperature and battery behavior acceptable
- [ ] Highlight lag within about 300 ms (overlay) at early, middle and late chapters
- [ ] A PC-rendered (Scribe) multi-voice book still plays with read-along
- [ ] Storage after rendering recorded (audio size per hour); estimates versus actual recorded
- [ ] If shipping `full`: a Piper pack loads, synthesizes and renders at least one chapter (record the speed); otherwise note it as build-from-source only

### VC8: Release (S)

- Run the release checklist (`07-TestPlan.md`, `08-Licenses.md` section 7, VC1-VC7 outcomes)
- Signed APK(s), back up the key, tag `slice-12` and `v2.0.0`; optional GitHub release with the APKs, the source offer if `full` ships, and install notes
- **Verify:** every box in section 6 ticked

## 5. Order and critical path

| Step | Work packages | Notes |
| --- | --- | --- |
| 1 | VC0 | Run SysLong now; make the license call |
| 2 | VC1, VC2 | Produce the release candidate as early as possible |
| 3 | VC7 starts | The soak runs for days and rendering takes nights; start it on the candidate |
| 4 | VC3, VC4, VC5, VC6 | Done in parallel with the soak, using its data |
| 5 | VC8 | Tag when everything is ticked |

If the soak exposes a bug, fix and re-run only the affected parts; keep the soak log honest about what was re-tested.

## 6. Definition of done

- [ ] Gates closed: SysLong recorded, D-066 and contribution policy decided, spike removed
- [ ] License implementation complete for every flavor shipped; scan green; `08-Licenses.md` fully verified
- [ ] Signed release builds pass the smoke checklist; no `INTERNET` permission
- [ ] Over-install from `v1.0.0` keeps the library and progress
- [ ] Constants documented from real data; encoder offset question closed
- [ ] Sentence-agreement numbers recorded; flaky test fixed; suites green
- [ ] Soak passed and logged; docs updated
- [ ] Decisions logged; roadmap ticked only for verified items; tagged `slice-12` and `v2.0.0`

## 7. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| GPL compliance mistakes for `full` | Choose B if the overhead isn't worth it; otherwise follow the VC1 checklist and the automated scan |
| R8 breaks the native bridge or serialization in release only | Test the release build of each flavor, not just debug |
| Room migration loses data on upgrade | VC3 over-install test with real data; keep a manual re-import fallback |
| Samsung kills the render service on some nights | Soak counts kills; resume from the spool is already tested; document the battery setting |
| Heat or battery drain in long renders | Charging-only default; guard tuned from real numbers |
| Nonzero encoder offset conflicts with the spec | VC4 gate decides before release |
| `full` APK size | ABI limits and split APKs; model packs stay separate |
| Soak takes longer than expected | Start it on the first candidate; run polish in parallel |
| Only one device tested | State it plainly in the README; invite reports for others |

## 8. Working with the agent

- One work package per session; it does code, scans, docs and tests; **you** make the license call, run SysLong, install builds, and do all soak and listening checks
- It must not claim device results; it records them from your reports
- No new dependency without a license and `minSdk 24` check; license text edits need your review
- Commit per work package (`VCn: summary`); tags as in VC8

## 9. After v2.0.0

- **Slice 13 (streaming):** if SysLong shows 1.3x or more, plan press-to-listen with live highlight (v2.1); otherwise record it as not feasible on the tablet and keep background rendering
- v1.1 backlog items (LLM attribution, real EPUB rendering, Page view, Wi-Fi transfer, bookmarks, themes), per-role audio caching, per-character device voices, a "device copy" of Scribe books
- **v3:** newer Android support (`targetSdk`, `minSdk` revisit, notification and foreground-service rules, Storage Access Framework changes)