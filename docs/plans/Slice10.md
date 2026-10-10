# Slice 10 Implementation Plan: Background Rendering

Project: Auloud Player (main), small Scribe additions for shared test data and fixtures. Follows `16-V2-Roadmap.md` (Slice 10) and builds on Slice 8 (engines, voice roles, audio utilities) and Slice 9 (unrendered books).

## 1. Goal

Turn an unrendered, imported book into a playable one **on the tablet**. A foreground service renders chapters with the narrator and dialogue voices, encodes them as AAC, writes timings, and the existing read-along works unchanged. Rendering is prioritized **ahead of where you are reading**, can run only while charging, and survives the app being killed.

**Done when** (from the roadmap): an imported EPUB renders on the Tab E (overnight is fine) and plays with correct highlight; killing the app mid-render resumes without redoing finished work.

## 2. Inputs and what is still open

- **Measured so far (your runs):** Piper lessac-medium about 0.36x on the Tab E (12 s load, about 260 MB peak; two instances 415 MB at 0.23x combined; roughly 800 ms voice-switch gap); System TTS 2.56x then 0.86x (noisy)
- **D-078:** System TTS is the primary tablet engine; screen-off rendering through file synthesis and a foreground service
- **SysLong verdict** (streaming or not) is **not needed for this slice**; it decides Slice 13 and the default render-ahead window
- **D-066 license call** is needed before release, not now: System TTS rendering is in the `core` flavor; Piper rendering lives in `full`
- Rough feasibility: at 1x, a 10-hour book needs about 10 hours of rendering, so one to two nights on System TTS; at 0.36x (Piper) a full book is not realistic, but render-ahead is (about an hour of audio needs about 3 hours)

## 3. Scope

| In | Out (later) |
| --- | --- |
| Render service, queue, pause/resume/cancel, resume after kill | Streaming synthesis (Slice 13) |
| Narrator and dialogue voices, pauses, leveling, exact timings | Engine switching and re-render UI (Slice 11) |
| AAC encoding, spec 2.0 part 2 | Importing Scribe script bundles (backlog) |
| Render-ahead planner, charging-only, temperature and storage guards | Per-character voices on the device |
| Partial books: playlist mapping, gating, progress conversion | Cloud anything |
| Render UI, library chips, chapter statuses, debug aids |  |

## 4. Key design decisions (log from D-091 onward)

1. **Voice-batched, spooled rendering.** Synthesis is offline, so sentences need not be produced in reading order. For each chapter: pass 1 renders all narrator sentences, pass 2 all dialogue sentences, pass 3 assembles them in order. The engine and voice switch **once per chapter**, instead of hundreds of times (at about 800 ms per switch, a dialogue-heavy chapter would waste many minutes). Each sentence's audio is spooled to a temp file keyed by chapter, sentence id and a voice fingerprint, which also gives **sentence-level resume** after a crash and keeps memory flat.
2. **AAC in M4A** through the platform encoder and muxer, mono, 24 kHz, about 64 kbps (parity with v1's bitrate). Needs spec 2.0 part 2.
3. **Encoder delay is measured, not assumed.** A debug beep engine renders a chapter with tones at known positions; the offset found is stored as a constant (and recorded in the manifest) and applied when the timings are written, so players need no change. Sync tolerance for AAC may be loosened from 50 ms (spec decision with data).
4. **Timing rules identical to Scribe:** pauses of 250 ms (sentence), 500 ms (paragraph), 800 ms (heading), 1000 ms (scene break), 100 ms between a dialogue sentence and the tag split from it; `start_ms`/`end_ms` from sample counts; `end_ms` excludes the pause; the first start is 0. Verified with shared vectors exported from Scribe's assembly tests.
5. **Leveling:** a book-level gain per role, derived from the first rendered chapter and stored in the manifest, plus a per-chapter peak limit. This avoids loudness jumps between chapters rendered on different nights.
6. **Crash-safe write order:** audio file, then timed text JSON, then the manifest update; each as temp-then-rename. A startup recovery step cleans orphan temp files and repairs half-finished chapters.
7. **Job state lives in a small file per book** (atomic writes), not the database, which avoids another Room migration. States: queued, running, paused, done, failed, cancelled, interrupted (process died; resumable).
8. **Render-ahead by default:** the planner starts at the chapter containing the reading position, then continues forward; options are "next N chapters" (default a few hours of audio), "whole book", or "from here". While charging, the window keeps rolling as you read.
9. **Partial books need an explicit chapter-to-media-item mapping.** Rendered chapters may not be contiguous, so a playlist position is not a chapter index. Progress stays keyed by chapter; the mapping is a tested abstraction; Listen and Read + listen are enabled per chapter; at the end of the rendered portion playback stops with a clear message.
10. **Per-chapter render fingerprint** (engine, voice ids, speeds, engine versions) stored with each chapter so Slice 11 can tell which chapters are stale after a voice change.
11. **Safety guards:** pause when the charger is unplugged (if charging-only is on), pause above a battery-temperature limit (resume when cooler; defaults to be tuned from your numbers), pause on low storage with an estimate shown before starting.

## 5. Work packages

### RN0: Prep (S)

- Read how the Player currently relates chapter index, playlist position and saved progress; read Scribe's assembly timing code and tests; confirm Slice 8's audio utilities (resample, leveling, silence) are reusable
- Draft spec 2.0 part 2 text; list decisions 1-11 for `DECISIONS.md`
- **Verify:** decisions logged; no code yet

### RN1: Spec 2.0 part 2 and fixtures (M)

- Spec: AAC/M4A audio entry, tolerance, optional `render_fingerprint` per chapter, `gain` per role, `encoder_offset_ms`, `render_state` partial semantics
- Scribe validator and `inspect` accept device-rendered bundles; Player validator and loader updated; fixtures: a tiny rendered AAC golden (made with ffmpeg by a Scribe dev command) and a partial-book fixture
- **Verify (tests, both sides):** golden validates; partial book loads; invalid combinations rejected with file and rule

### RN2: Shared timing vectors (S)

- Export from Scribe's assembly tests: lists of sentence kinds and sample lengths with expected `start_ms`/`end_ms` and chapter duration
- **Verify:** deterministic files; Scribe tests consume them too

### RN3: Planner and job model (M)

- `RenderPlan` from reading position and options; ordering; rolling window; job state machine with persisted state file and transitions; queue across books
- **Verify (JVM tests):** ordering from different positions, rolling window, every transition, interrupted-then-resumed, one running job at a time

### RN4: Synthesis passes and spool (M-L)

- Resolve engines and voices from the Slice 8 registry and role settings; validate availability up front (missing engine or model pack gives a clear message)
- Passes per decision 1; spool files keyed by chapter, sentence id and fingerprint; skip existing files on resume; one retry per sentence, then fail the chapter naming chapter and sentence id; cancellation points between sentences; release engines between passes (memory with two Piper models)
- Per-role level measurement for decision 5
- **Verify (JVM tests with a fake engine):** pass ordering, resume skipping, retry and failure, cancellation, fingerprint mismatch invalidates spool files

### RN5: Assembly and timings (M)

- Ordered assembly from the spool, resample to 24 kHz, apply gain, insert pauses, compute timings from sample counts, chapter peak limit; stream into the encoder interface
- **Verify (JVM tests):** shared vectors from RN2; timing arithmetic with mixed rates

### RN6: AAC encoder and muxer (M-L)

- `AudioEncoder` interface; platform implementation (encoder configuration, buffer feeding and back-pressure, end of stream, muxer track setup, presentation times from sample counts); temp file then rename; duration check against sample count; encoder-delay hook for decision 3; clear errors if no AAC encoder is available
- **Verify (JVM tests with a fake encoder):** buffering, EOS, error mapping; **device:** a real encode plays in the app

### RN7: Bundle update, recovery, partial books (M)

- Write timed chapter JSON and manifest per decision 6; startup recovery of orphan temp files and half-finished chapters
- Chapter-to-media-item mapping and gating (decision 9); saved sentence-id progress converts to milliseconds using the new timings; end-of-rendered-portion behavior; rendered books untouched
- **Verify (JVM tests):** write-order crash simulation at each step; mapping with sparse chapters; progress conversion; existing Player suite stays green

### RN8: Render service (L)

- Foreground service with a progress notification and pause/cancel actions, partial wake lock while rendering, restart behavior after process death using persisted state, task-removal handling
- Charging-only auto-pause and resume; temperature guard; storage estimate and low-space pause; lifecycle logging (create, destroy, task removed, restart)
- **Verify (JVM tests of policy classes):** charging, temperature and storage decisions with injected inputs; **device:** service survives screen-off and swipe-away

### RN9: Render UI (M)

- Book panel: estimate (audio length, size, time from the benchmark RTF), options (whole book, next N chapters, from here), charging-only toggle, voices summary linking to voice settings, start; library chips ("Rendering 42%", "Partially rendered"); chapter list statuses and per-chapter render action; delete audio; errors with plain language; debug overlay lines for render RTF, current chapter and sentence, battery temperature and spool size
- **Verify (JVM tests):** view-model states and estimates; **device:** visual check

### RN10: Beep engine and self-checks (S)

- Debug-only deterministic engine producing tones of known length per sentence; a debug action to render a short test chapter with it; used to measure encoder delay and to check highlight against the beeps by ear and eye
- **Verify:** test chapter validates; offset measured and recorded

### RN11: Acceptance (you)

- [ ] Beep chapter: highlight flips at each beep within about 300 ms; encoder offset recorded in `docs/test-log.md`
- [ ] Render one chapter of an imported book with System TTS; listen with read-along; narrator and dialogue clearly distinct and level-matched; 100 ms tag pauses sound natural
- [ ] Render several chapters while charging with the screen off; compare actual speed with the estimate
- [ ] Kill the app mid-render and relaunch: it resumes and finished sentences are not redone
- [ ] Unplug the charger with charging-only on: rendering pauses; replug: it resumes
- [ ] Partial book: playback of rendered chapters works; unrendered chapters are clearly marked; stopping at the end of the rendered portion shows the message
- [ ] Full flavor (optional, subject to D-066): render one chapter with a Piper voice at about 0.36x and record it
- [ ] Low storage and high temperature behaviors checked if you can provoke them
- [ ] Existing rendered (Scribe) books still play with read-along

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | RN0, RN1, RN2 | have the spec, fixtures and timing vectors |
| 2 | RN3, RN4, RN5 | see the whole pipeline pass on the JVM with fakes |
| 3 | RN6, RN10 | produce real AAC on the tablet and measure encoder offset |
| 4 | RN7 | play a partially rendered book |
| 5 | RN8, RN9 | render in the background with a proper UI |
| 6 | RN11 | accept on the tablet |

## 7. Definition of done

- [ ] All verifications passed; Player and Scribe suites green; no regression for rendered books
- [ ] Device acceptance passed on the Tab E (System TTS path required; Piper optional)
- [ ] Encoder offset measured; sync within the agreed tolerance
- [ ] Spec 2.0 part 2, decisions 1-11 and test results logged; roadmap ticked only for verified items; tagged `slice-10`

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| System TTS speed varies run to run (2.56x then 0.86x) | Estimates shown as ranges from measured RTF; the planner is rate-agnostic; tell the user rendering time is approximate |
| Samsung kills the render service | Foreground service, wake lock, persisted state, sentence-level resume via the spool; measure kill frequency in acceptance |
| Encoder delay or drift breaks sync | Beep engine measurement; stored offset; tolerance decided from data |
| Heat and battery drain | Charging-only default, temperature guard, notification shows progress |
| Spool uses disk (about 86 MB per 30 minutes of audio) | Delete per chapter after finalize; storage estimate includes it |
| Partial-book mapping bugs (chapter vs playlist index) | Explicit mapping type, tests with sparse chapters, existing tests as a safety net |
| Piper memory with two models (about 415 MB) | Voice-batched passes load one model at a time |
| AAC encoder missing or misbehaving on a device | Clear error; fall back message; keep the System TTS path testable with the fake encoder on the JVM |
| DB or file-state corruption on kill | Atomic writes, ordered writes, startup recovery |

## 9. Working with the agent

- One work package per session; it implements and tests on the JVM with fakes; **you** run all device checks and listening tests, including the beep chapter
- Keep platform code (encoder, service, battery) thin behind interfaces so most logic is JVM-tested
- No new dependency without a license and `minSdk 24` check
- Commit per work package (`RNn: summary`); tag `slice-10`

## 10. Next

Slice 11 adds voice settings polish and engine switching: the per-chapter fingerprint lets it find stale chapters and re-render only those. Slice 13 (streaming) waits for the SysLong verdict.

## Appendix A. Spec 2.0 part 2 draft (DRAFT for RN1 to finalize)

Status: DRAFT sketched in RN0 from plan section 4 decisions 2-5 and 9-10. RN1 owns the real text in `docs/03-BundleSpec.md` plus `spec/bundle.md`, the validator changes and the fixtures. Do NOT treat this appendix as the spec; it is the starting proposal only. Uses `spec_version` "2.0" with no version bump beyond what IN1 already defined (part 2 is additive inside 2.0; RN1 confirms or corrects that call).

A.1 Audio entry for device renders. The manifest `audio` object gains `format` "m4a" (AAC-LC in an MP4/M4A container) alongside the existing "mp3" value; `channels` 1, `sample_rate` 24000, `bitrate_kbps` about 64, `cbr` true (AAC is not frame-CBR the way MP3 is; RN1 words this honestly, e.g. constrained-constant-bitrate or an average-bitrate statement, and the validator checks container plus stream, not MP3 frames). Chapter files are `audio/chNNN.m4a` (stem keeps the zero-padded chapter index convention). Mixed books (some chapters MP3 from Scribe, some M4A from the device) are legal: the format lives per chapter entry, the manifest `audio` object describes the rendered chapters as today.

A.2 Tolerance. The 50 ms `duration_ms` agreement rule (manifest vs chapter JSON vs probed audio) stays for MP3 chapters. For M4A chapters the tolerance is decided from the RN10 beep measurement (plan decision 3): if the measured encoder offset is stable, timings are written offset-corrected and the same 50 ms applies; if offset plus drift exceed it, RN1 records a looser AAC-specific number with the measured evidence. The measured offset is stored per book as `encoder_offset_ms` (integer, milliseconds, 0 when measured zero) so any reader can audit sync without changing player code.

A.3 Render fingerprint. Each rendered chapter entry MAY carry `render_fingerprint`: an object with `engine` (namespaced engine id, e.g. `system`, `piper:<model>`, `kokoro:<voice>` family per D-065), `voices` (the narrator plus dialogue voice ids used), `speeds` (per-role speed multipliers), and `engine_versions` (one version string per engine used, same strings the synth cache keys on). Readers ignore it (`ignoreUnknownKeys` already covers the Player); Slice 11 compares it to current settings to mark stale chapters. Unknown or absent fingerprint means "fingerprint unknown", never "up to date".

A.4 Per-role gain. The manifest MAY carry `gain_db` (or `gain`, RN1 picks the name and unit): one number per role key (`narrator`, `dialogue`), in decibels, derived from the first rendered chapter per plan decision 5 and applied at assembly of later chapters before the per-chapter peak cap. Readers ignore it (loudness is baked into the audio); it exists so re-renders and audits reproduce levels. Absent means "no book-level gain recorded".

A.5 Partial semantics (already in 2.0 section 3, restated for RN1 test design). `render_state` `partial` means at least one rendered and at least one unrendered chapter; per-chapter rendered state is inferred from `duration_ms` presence and must agree with `render_state`. Unrendered chapters omit `audio` and `duration_ms` (absent, never null) and every sentence omits `start_ms`/`end_ms`; rendered chapters in a `partial` book carry them exactly as in 1.x. Validation errors name the file and the rule, following the existing 2.0 strings.

A.6 API 24 note for RN1. All new fields are plain JSON numbers and strings; no date, time or duration strings that would tempt a `java.time` parse on the Player side (unavailable on API 24 without desugaring). Durations stay integer milliseconds; versions stay strings.