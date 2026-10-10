# Auloud v2 Roadmap: On-Device Voices

Builds on v1.0.0 (PC renders the audiobook, the tablet plays it). Source for the v2 headline in `04-Roadmap.md`: embed the book in the app so the device can synthesize speech itself. This document turns that into slices with gates, decisions and risks.

> **Scope update:** v2 uses a **two-voice mode on the device only**: one narrator voice and one dialogue voice. **The PC version (Scribe) stays fully multi-voice, unchanged**, since the PC can handle it. This removes on-device speaker attribution and shrinks the engine and memory problems. See decisions 7 and 8.

## 0. Before v2 starts: v1 loose ends

- [ ] Rebuild the signed APK from `release/v1.0` (the merge added Player spec-1.2 handling after your last APK) and re-smoke it before calling the binary final
- [ ] Back up the signing key to a second location (it lives outside the repo, see `docs/ReleaseSigning.md`)
- [ ] Add the `slice-6` tag; run the 3-browser runbook for the web UI
- [x] Decide the contribution policy (DCO or CLA), the one open box in `08-Licenses.md` (decided: DCO sign-off, D-127)
- [ ] Fix the `07-TestPlan.md` filename case drift

## 1. Can Auloud support Piper, Kokoro and others, and let the user switch?

Yes, and this is the right design: **a pluggable engine interface**, not a choice between engines. Each engine declares what it can do and the app picks or recommends per device.

| Engine tier | What it is | Strengths | Limits |
| --- | --- | --- | --- |
| **System TTS** | Android's built-in `TextToSpeech` API: Google TTS, Samsung TTS, or any engine the user has installed | No model download, small, fast; any installed engine appears automatically; uses `synthesizeToFile` to get exact audio for timings | Few voices, uneven quality, per-character variety limited to available voices plus rate/pitch |
| **Piper** | Light neural voices (VITS models) | Small models, good quality for the size, plausible on a weak tablet | Usually one voice per model file, so many characters means loading several models or using a multi-speaker model |
| **Kokoro** | 82M-parameter neural TTS | Best quality; many voices inside one model (good for multi-character books) | Heavy; may not run in real time on the Tab E |

Piper and Kokoro can both run through one native library (sherpa-onnx, which supports both families on Android as far as I know; verify the current builds, including 32-bit ARM, in the spike). So supporting both costs mostly model packs and voice mapping, not two runtimes.

"Google TTS" here means the **on-device Google TTS engine** through the system API. Cloud text-to-speech (Google Cloud and similar) is a different thing: it needs internet, keys and money, and conflicts with the offline, no-`INTERNET` design (D-016), so it is out of scope.

**Recommendation:** build the interface, ship System TTS plus Piper and/or Kokoro according to the benchmark, and let a one-time on-device benchmark recommend an engine per device ("Kokoro is fine on this phone; use Piper on this tablet").

## 2. v2 vision and non-goals

**Vision:** import a book (or a script from Scribe), pick an engine, and the device renders an audiobook itself (in the background, or streamed), with read-along working on the device's own timings. PC rendering stays the best-quality, multi-voice path and works exactly as in v1.

**Stays the same:** the bundle format and read-along, offline operation, no network permission, Android 7.1.1 as the base target.

**Non-goals:** cloud TTS, voice cloning, per-character voices on the device (the device is narrator + dialogue only; the PC keeps per-character voices), newer-Android support (that is v4), iOS, on-device PDF extraction in the first v2 releases.

## 3. Key decisions (log in `DECISIONS.md`)

1. **Engine interface** (`TtsEngine`): voice list, capabilities (multi-speaker, load cost, sample rate), `synthesize(text, voice, speed)`; voice ids namespaced per engine (`kokoro:af_heart`, `piper:<model>`, `system:<voice>`), matching the `engine` field already in the manifest.
2. **License path for espeak-ng** (the v2 D-015): Piper and Kokoro depend on espeak-ng for phonemes, which is GPL-3.0. Options:
   - **A. Bundle it in the Player and make the Player GPL-3.0.** Simplest legally and fully self-contained; changes the license you just closed.
   - **B. Keep the Player Apache-2.0 and use only the System TTS adapter**, so any GPL engine is a separate app the user installs (Android lets engines plug in this way). Cleanest license boundary, less polished, and you depend on third-party engine apps.
   - **C. Ship an engine path with no GPL parts**, if one exists at acceptable quality (check in the spike). Decide after the spike with a license review; this isn't legal advice. Two-voice mode does not change this question.
3. **Model delivery without `INTERNET`:** sideloaded model packs (a folder on the microSD card, imported like a watch folder), keeping the offline promise. An optional download feature would need the permission and is a privacy trade-off; defer.
4. **On-device audio storage:** Android has a built-in AAC encoder; an MP3 encoder would mean shipping extra native code. Recommend AAC-LC mono in MP4/M4A written with the platform muxer. Needs a small spec amendment (audio formats) and possibly a looser sync tolerance than 50 ms because of encoder delay; measure it.
5. **Render location:** write rendered chapters into the bundle folder when writable (the bundle becomes complete and portable), else into app storage.
6. **Unrendered books and script bundles:** the spec needs a way to describe a book whose audio does not exist yet (text and sentence structure, no MP3s or timings). Books imported on the device use it internally; Scribe can also export one (`scribe export --script`) for PDFs and for its better text cleaning. Optional in v2.0.
7. **Two-voice mode (decided):** on the device, every sentence is either narration or dialogue, rendered with the **narrator voice** or the **dialogue voice**. No speaker attribution on the device, only quote detection (a deterministic state machine, already built and tested in Scribe). Books from Scribe that carry per-character voices are still played as rendered; if one is re-rendered on the device, all non-narrator speakers collapse into the dialogue voice, and the data stays in the file for a future per-character mode. Consequences:
   - Piper needs only two models loaded (or one multi-speaker model); Kokoro needs one model with two speaker ids
   - Voice switching happens only at narration/dialogue boundaries, and the existing 100 ms tag pause and per-voice leveling carry over
   - The cast screen becomes simple voice settings
   - On-device ingestion drops the hardest part (attribution) and can move into v2.0
8. **The PC stays multi-voice (decided):** Scribe's per-character casting, attribution and `cast.yaml` are unchanged, and multi-voice remains the default and recommended way to get the best audiobook. A "collapse to two voices" toggle on the PC is **not** part of v2; if you ever want PC and device books to sound alike, it can be added later as an optional setting.

## 4. Milestones and slices

### v2.0: Embed the book, render on the device

**Slice 7: Benchmark spike and gate (S-M).** *Status: replaced.* A separate benchmark app was not practical on the Tab E, so the gate is now (1) the written hypotheses in `17-Slice8-Plan.md` and (2) an **in-app benchmark on the Voice lab screen** built in Slice 8, which any device, including the Tab E, can run without adb. The original spike checklist below stays as a reference for what to measure.

- Throwaway Android app with sherpa-onnx; measure on the Tab E (and a modern phone if you have one): Piper low and medium voices, Kokoro (full and quantized if available), System TTS `synthesizeToFile`
- Measure warmed real-time factor (audio seconds per wall second), model load time, RAM, heat and battery over 10 minutes, 32-bit vs 64-bit behavior
- **Two-voice tests:** two Piper models loaded at once (RAM), Kokoro with two speaker ids (switch cost), System TTS switching voices per utterance (latency)
- Check the espeak-ng/GPL situation concretely (what each engine path links)
- **Exit criteria:** an engine tier table with numbers; the license option chosen (decision 2); targets confirmed: roughly RTF 1.0 or better for background rendering and about 1.3 or better for streaming. If nothing neural reaches 1.0 on the Tab E, v2 on the tablet means System TTS plus background rendering with Piper-low, and Kokoro is reserved for faster devices

**Slice 8: Engine interface and engines (M-L).**

- Player: `TtsEngine` interface, engine and voice registry, System TTS adapter, sherpa-onnx engines per the gate, model-pack import from a folder, a voice audition screen with narrator and dialogue roles
- Scribe: a Piper engine for PC rendering (the interface exists; it was skipped in v1) and namespaced engine voices in `cast.yaml`; multi-voice behavior unchanged; the web UI voices view lists engines
- **Done when:** the Tab E can audition voices from each included engine, and Scribe can render a short multi-voice chapter with Piper

**Slice 9: On-device ingestion, EPUB (M-L).** *Status: complete (tag `slice-9`; IN10 device-passed 2026-10-06, see #13).*

- Import an EPUB directly: Kotlin port of extraction, cleaning, sentence splitting and dialogue detection (quote state machine, with the same split of mixed sentences); every sentence is tagged narration or dialogue
- Keep the original file in the book folder; spec amendment for unrendered books (decision 6) first, with validator and fixtures
- Parity tests: run the port on the shared golden EPUBs and compare structure and `kind` tags with Scribe's output
- **Done when:** an EPUB imported on the tablet shows a chapter list and readable text with dialogue marked, with no PC involved

**Slice 10: Background rendering (L).** *Status: complete (tag `slice-10`; RN11 device-passed 2026-10-08, see #16; offset number unrecorded, fixture 0 stands).*

- Foreground rendering service: per-chapter jobs, progress, pause and resume, wake lock, storage checks (charging-only gate removed by D-130; renders run unplugged)
- Narrator and dialogue voices synthesized per sentence; short pause at quote/tag boundaries; per-voice loudness leveling; timings from sample counts; chapters stored as AAC (decision 4) with timing JSON, so read-along works unchanged
- Optional: import a Scribe script bundle (decision 6) as another source
- **Done when:** an imported EPUB renders overnight on the Tab E and plays with correct highlight; killing the app mid-render resumes without redoing finished chapters

**Slice 11: Voice settings and engine switching (M).** *Status: complete (tag `slice-11`; VS7 device-passed 2026-10-09, see #18; KEEP=persist per 2026-10-09 device verdict c1e231a; estimate numbers unrecorded, constants stand).*

- Settings screen: narrator voice, dialogue voice, speed per role, audition
- Engine switcher with a per-device recommendation from a built-in benchmark; automatic voice mapping when switching engines (by gender and palette); re-render affected chapters
- **Done when:** switching a book from one engine to another and re-rendering a chapter works without editing files by hand

**Slice 12: v2.0 complete pass (M).** *Status: complete (tags `slice-12` + `v2.0.0`; release criteria section 6 all ticked 2026-10-10).*

- Agent-complete: VC0 gates logged (D-126 option A, D-127 DCO) with SysLong hypothesis-recorded (D-131, bench waived, spike retained); VC1 two licensed flavors with passing `licenseScan`; VC2 version `2.0.0` with ARM-only signed-flavor builds and passing `releaseManifestCheck`; VC3 JVM compatibility matrix (device over-install verified 2026-10-10, see below); VC4 constants doc (provisionals stand, offset gate closed by acceptance D-132); VC5 sentence agreement 34/34 runs plus the reader debounce flake fixed; VC6 README v2 plus `07-TestPlan.md` plus PRD addendum; UX1 hub Listen plus charger removal (D-130).
- Device-verified 2026-10-10: VC2 smoke checklist, VC3 over-install, VC8 release. Soak: multi-day soak reported good (one-line verdict, no per-box numbers). Tracked in `07-TestPlan.md` sections 2-3; procedures in `docs/ReleaseSigning.md` and `docs/plans/Slice12.md`.

- On-device soak: a full novel imported, rendered on the tablet, and listened to over days; battery and thermal checks during rendering
- License decision implemented (GPL Player, or the adapter/plugin approach) with `NOTICE`, in-app licenses and `08-Licenses.md` updated (sherpa-onnx, onnxruntime, models, espeak-ng); README and docs updated; tag `v2.0.0`

### v2.1: Instant listening

**Slice 13: Streaming synthesis (M-L).** *Status: complete (maintainer
acceptance 2026-10-10; tags `slice-13` and `v2.1.0`).*

- Synthesize-ahead pipeline into an audio buffer so listening starts within seconds, falling back to rendered chapters when present; handle underruns; seeking into unsynthesized text starts synthesis from that sentence
- Highlight sync from live timings; foreground service with the screen off
- Gated by the measured device RTF. Hypothesis verdict (D-131, no bench run): file-synthesis streaming is NOT feasible on the Tab E (Cortex-A7, 1.5 GB RAM, System TTS slow end 0.86x, Piper 0.36x). 2026-10-10 amendment: maintainer device evidence (ReadEra live system-TTS read-aloud works on the tablet) re-opened streaming for a `speak()`-style live architecture, whose numbers our file-synthesis measurements do not cover; the re-opened feasibility was then measured at the gate (outcome below). The Tab E v2.0 story stays background rendering.
- 2026-10-10 gate outcome (D-137): measured NO-GO on the Tab E (best live med 401 ms vs 150, start latency 10+ s vs ~2 s; single-voice reduced go fails too). Background rendering stays the listening path on this tablet; the ST1-ST6 agent build stays behind `GATE_PASSED=false` as the faster-device head start (`docs/streaming.md` retained).
- 2026-10-10 override (D-138): maintainer ordered GO ("good enough", variant (a)). Numbers stand; `GATE_PASSED` flips true and ST7 decides by ear.
- **Done when:** on a capable device, pressing play on an unrendered chapter starts audio in a few seconds with no gaps over a 30-minute listen

## 5. Scribe and web UI changes in v2

- Piper engine; engine-namespaced voices; `scribe export --script` (optional); multi-voice casting unchanged
- `scribe doctor` checks for new engines; spec 2.0 validator rules (unrendered books, audio formats)
- Web UI: engine selector in the cast view; an "Export for on-device rendering" action in the Transfer step (optional)

## 6. v2 release criteria (ticked 2026-10-10; bases in `docs/test-log.md`)

- [x] An EPUB imported on the Tab E is rendered on-device and played for days with correct read-along (soak: RN11 + VS7 passes, maintainer-reported multi-day soak good)
- [x] At least two engine tiers selectable, with a per-device recommendation (System + Piper in the engine picker with benchmark categories; VS7 engine-switch pass)
- [x] Narrator and dialogue voices clearly distinguishable and level-matched (VS7 pass by ear)
- [x] Switching engines re-renders only what is needed (VS2/VS3 matrix + VS7 stale pass)
- [x] Highlight within about 300 ms on device-rendered audio (RN11 functional pass)
- [x] No `INTERNET` permission; model packs sideloaded (`releaseManifestCheck` green; `ModelPacks` scan, no downloads)
- [x] License path implemented and documented; Player "Verified" boxes ticked (D-126/D-128, VC1; Scribe PC-side rows stay open per `07-TestPlan.md` section 4, not release blockers)
- [x] Battery and thermal behavior acceptable during rendering (maintainer soak verdict good; temperature guard stays; renders run unplugged per D-130)

## 7. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| The Tab E can't run neural TTS fast enough | Slice 7 gate; System TTS tier; background render overnight; Kokoro for faster devices |
| Memory for two voices on 1.5 GB RAM | Test two Piper models at once in Slice 7; fall back to one multi-speaker model or swap models at chapter boundaries |
| espeak-ng GPL forces a license change | Decide in Slice 7 among options A/B/C before building on it |
| Encoder delay breaks the 50 ms sync tolerance | Measure in Slice 10; relax the tolerance for AAC in the spec if needed |
| Heat and battery drain while rendering | Charging-only option, foreground service, throttling, thermal checks in the soak |
| Large model packs and 8 GB internal storage | Sideload to microSD, show sizes, let users delete packs |
| Kotlin port drifts from Scribe's text handling | Shared golden EPUBs and parity tests; record any differences |
| Dialogue detection misses unusual quote styles | Same fallback as Scribe (treat as narration), log, and keep PC rendering as the quality path |
| Third-party System TTS quality varies | Treat as a convenience tier; recommend neural engines when the device can run them |
| No adb for diagnostics | On-device debug overlay for RTF, memory, and render progress |

## 8. Backlog beyond v2

Per-character voices on the device (the data is already in Scribe bundles), an optional "collapse to two voices" setting in Scribe, v1.1 items still open (LLM attribution via Ollama, real EPUB rendering, Page view, Wi-Fi transfer, bookmarks, themes), on-device PDF extraction, optional model download, voice variety work from D-036, then v3 (Scribe Rust/Tauri v2 desktop port for Windows 11 and Linux, D-133), then v4 (newer Android: `targetSdk`, `minSdk` revisit, notification and foreground-service rules, Storage Access Framework changes, word-level callbacks, Scribe on Android).