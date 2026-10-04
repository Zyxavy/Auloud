# Slice 6 Implementation Plan: Scribe Web UI

Project: Auloud Scribe (Python, AGPL-3.0, Windows 11). Source of truth for requirements: the Scribe Web UI spec (suggested path `docs/14-ScribeWebUI-Spec.md`, to avoid clashing with the existing plan documents). This slice is a **standalone slice after `v1.0.0`** (spec decision 5): it must not delay the release.

## 1. Goal

`scribe ui` starts a localhost web app that covers the whole workflow, **ingest, review cast, render, validate, hand off to the tablet**, without changing what the CLI does. The CLI stays the headless interface; the UI is a thin operator over the same library.

**Done when** (spec section 9):

- A fresh user goes from upload to a valid multi-voice bundle without touching the CLI (README-tested)
- Chapter-range and page-range renders validate, and cache reuse shows in the counts
- Cast edit, audition, override and rebuild work without hand-editing YAML
- A 3-hour-scale build runs with the browser closed, pauses and resumes, and shows sane RTF and ETA
- New UI tests (API, job state machine) pass alongside the existing 482, with no slowdown to the existing suites

## 2. Scope

| In | Out (v1 of the UI) |
| --- | --- |
| Books, Book detail (stepper), Voices, Jobs tray | Accounts, remote access, auth beyond a local token |
| Draft, render (with chapter and page ranges), validate, transfer | Editing book text |
| Cast view with audition, overrides editor, cast report | Page-view rendering (Player side) |
| Background jobs: pause, resume, cancel, reattach | Docker (v2/v3) |
| Device selector (Auto/CPU/CUDA) and worker count | Anything on-device |
| Scribe design system (tokens and five components) |  |

## 3. Decisions (log in `DECISIONS.md`; recommendations are mine)

**D1: Job runner model.** The spec says worker threads. I recommend **one detached `scribe build` process per job**, writing progress as JSON lines to `events.jsonl` in a job folder; the server tails that file and streams it as SSE. Why: a native crash in inference can't take down the server; closing the browser, and even restarting the server, never touches a running job (the UI just reattaches); cancel is a clean process stop; the UI really is a thin layer over the CLI. Chapter-level parallelism happens inside the build process (`--workers N`). Pause means a graceful stop at a sentence boundary and resume means a new run that skips cached sentences (the MV7 path), costing a few seconds of model load. Real "frozen process" pause is not worth the Windows complexity.

**D2: Server framework.** FastAPI + uvicorn, shipped as an optional extra (`scribe[ui]`) so the plain CLI install stays light. Reasons: file upload needs multipart (Python's stdlib support for it is gone in 3.13), SSE is simple with a streaming response, and the test client is excellent. Licenses (MIT/BSD/Apache-2.0 for FastAPI, Starlette, uvicorn, python-multipart, pydantic) get checked and pinned like PyMuPDF was.

**D3: Frontend.** Plain HTML, CSS and ES modules, no build step, no Node, no CDN (works fully offline). System font stacks (a serif stack for titles, a sans stack for the interface), so no font files or licenses.

**D4: Job store.** A folder per job: `job.json` (state), `events.jsonl`, `build.log`. History is a directory scan. No database.

**D5: Parallelism and GPU are measured, not assumed.** UI0 measures both; defaults come from the numbers. The spec's own pushback holds: CPU already renders at 4-25x real time, so a GPU selector may end up "advanced/experimental".

**D6: Cast file edits.** The file stays the source of truth. The UI uses structured patch operations through the MV5 merge functions, plus a modified-time check so a hand edit made meanwhile produces a conflict message instead of a silent overwrite. If the current writer drops YAML comments, either preserve them (a round-trip YAML library) or document it.

**D7: Partial bundles and chapter numbering (needs a spec amendment).** A range render lists only rendered chapters, but the Player keys saved progress by bundle `id` and chapter position. If two different ranges of the same book share one id, progress would point at the wrong chapter. Recommendation: renumber chapters consecutively in the bundle, add an optional `source_index` per chapter (additive, spec 1.2), and derive the bundle id from the source hash **plus the range** for partial builds (a full build keeps the existing id). Update `03-BundleSpec.md`, validator, and shared fixtures first; the Player ignores the extra field.

**D8: Local security beyond "bind to 127.0.0.1".** Other web pages in your browser can still send requests to localhost services. Implement: Host-header check (blocks DNS rebinding), no CORS headers, and a random per-run token embedded in the served page and required on every state-changing request. No login, no extra UX.

## 4. Work packages

### UI0: Spikes (S-M), do first

- **Job model:** prototype `scribe build --events` and a minimal server that spawns it detached and tails the file as SSE; confirm Windows detaching works (server restart reattach, browser close)
- **Parallelism:** time 1, 2, 3 and 4 chapters in parallel on your CPU: aggregate RTF and RAM (watch thread oversubscription: onnxruntime already uses several cores per call)
- **GPU:** measure CUDA vs CPU for Kokoro-82M on your RTX 4050 (needs the GPU build of onnxruntime and matching CUDA libraries, and it conflicts with the CPU package); decide whether the selector ships as advanced or hidden
- **Verify:** numbers in `docs/test-log.md`; D1, D2, D5 logged

### UI1: Library groundwork (M)

Changes in the library, not the UI. The CLI's behavior and outputs must stay identical for existing cases.

- Build events interface (progress callbacks to a JSONL writer; the CLI's Rich bar becomes one listener)
- `build` accepts a chapter selection; PDF page ranges resolve to covering chapters via `source_pages`; draft still processes the whole book
- `plan_build()` dry run: counts of cached vs to-render sentences and a time estimate from recent RTF (also exposed as `scribe build --plan`)
- Cooperative stop (pause/cancel) at sentence boundaries; atomic partial-chapter cleanup on cancel
- Lock file per book so a UI render and a CLI render can't run at once; stale-lock detection
- `--workers` and `--device` options; unified error shape `{file, rule, message}` shared by CLI and API
- Spec 1.2 amendment for D7, validator, fixtures, tiny partial-bundle golden
- **Verify:** existing 482 tests unchanged; a full build is byte-identical to before; a range build validates; the Player contract test still parses the golden bundles

### UI2: Server skeleton and packaging (M)

- `scribe ui [--port 8137] [--workspace PATH]`: binds 127.0.0.1 only (assert it), opens the browser, prints the URL; clear error or next free port if busy; single instance per workspace
- App factory, static assets in the wheel (force-include and reinstall before trusting `uv run scribe`), lazy import with a friendly install hint when the extra is missing; `scribe doctor` gains a UI check
- D8 protections (Host check, token), upload filename sanitizing, all file access confined to the workspace except an explicit user-chosen transfer destination
- **Verify (tests):** wrong Host rejected; missing token rejected; path traversal rejected; server won't start on a non-local host

### UI3: Books and Book detail (M)

- Workspace scan: source files, work folders, bundles; status chips (Drafted, Cast edited, Rendering 42%, Valid, On tablet) derived from files and the job store
- Upload (drag and drop) starts a draft job; the Book detail stepper shows each step's artifact inline (chapter list with durations, cast summary, progress, validation results)
- Empty states name the next action
- **Verify (API tests):** status derivation from fixture workspaces; upload then draft produces the same artifacts as the CLI

### UI4: Job runner and Jobs tray (L)

- Job folders, detached process launch, event tailing, SSE with reconnect (`Last-Event-ID`) and heartbeats
- State machine: `queued, running, paused, done, failed, cancelled`, plus `interrupted` when the machine or server died mid-run (resume reuses cache); one render per book, queue across books
- Pause, resume, cancel; server shutdown warns about running jobs; history with outcome and RTF
- Keep Windows awake during a running job (system-required execution state, released when idle), with a setting to disable
- **Verify (tests with a fake worker):** every transition, queueing, reattach after server restart, cancel cleans partial output, interrupted-then-resumed renders only uncached sentences

### UI5: Render step (M)

- Chapter picker (multi-select or X-to-Y) from the draft; page-range input with a resolved preview ("pages 40-90 map to chapters 3-4"; partial edges render whole chapters, shown plainly)
- Preflight from `plan_build()`: how many sentences will render, how many are cached, estimated time
- Compute settings persisted per machine (device, workers), start, pause, resume, cancel with live progress, RTF, ETA, cache hit rate
- **Verify (tests):** range and page resolution, preflight numbers against known caches; **you:** re-ranging a built book renders only new sentences

### UI6: Cast view (L)

- Table: character, voice dropdown, audition button, speed, line count and minutes (from the `inspect --speakers` data); narrator, generic female/male and `first_person` rows; bit parts collapsed under "minor"
- Overrides editor with a quote picker (chapter, block, quote number, text, current speaker, confidence), filter for low-confidence lines, and the "rebuild affected lines only" preflight counts
- `cast_report.md` shown read-only (escape everything; no raw HTML), low-confidence lines linking to their rows
- Edits go through D6 patch operations with validation errors shown as `{file, rule, message}`; conflict message if the file changed on disk
- **Verify (tests):** each patch operation, validation errors identical in wording to the CLI, merge never clobbers hand edits, conflict detection

### UI7: Voices view and audition service (S-M)

- Palette grid with audition clips; sample WAVs are generated by a background job (`voices --sample`, cached by voice and engine version), not by loading the model inside the server
- A reusable player-row component used by both Voices and the Cast table
- Optional later: audition with a real line from the book
- **Verify (tests):** cached clips served, regeneration only when the engine version changes

### UI8: Validate and Transfer (M)

- Validate job with results listed as `{file, rule, message}`
- Transfer step: pick a destination (removable drives detected, or a typed path), copy with progress and size/hash verification, record "On tablet" with destination and time, show how to import it in the Player (watch folder), "open folder" button
- **Verify (tests):** copy verification catches a truncated file; the chip and record update

### UI9: Design system and polish (M)

- Tokens as CSS variables from the spec (paper, ink, oxblood accent, muted sage; dark by inversion); one serif display use; tabular numerals for timings; 4 px radius, 8 pt spacing, hairline dividers, one elevation level
- Five components only: table, stepper, tray, player row, chips; copy conventions (`58:32`, `24.8x RTF`, errors naming file and rule)
- Accessibility: visible focus, labels, keyboard operation, progress announced via a live region, contrast checked by an automated test against the tokens (confirm the success color and muted text meet 4.5:1)
- Test in Edge, Chrome and Firefox; no external requests
- **Verify:** contrast test; a short manual runbook on the three browsers

### UI10: Tests and hardening (M)

- API tests (TestClient) for every endpoint and error wording; SSE tests; security tests from UI2
- New suites isolated and fast (no model loading unless marked slow); confirm the existing suites' runtime doesn't regress
- Optional dev-only browser smoke test; otherwise the manual runbook is the UI check
- **Verify:** whole suite green; coverage on the job state machine

### UI11: Docs and acceptance (S)

- README section and a fresh-user walkthrough; save the spec to `docs/`; update `08-Licenses.md` for new dependencies (verify each box); `DECISIONS.md` entries D1-D8
- **Acceptance (you):** run spec section 9 end to end, including the browser-closed 3-hour build and a pause/resume
- Tag `slice-6`

## 5. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | UI0 | know the job model, worker count and whether the GPU matters |
| 2 | UI1 | use ranges and `--plan` from the CLI already |
| 3 | UI2, UI3 | open the UI, see books, upload and draft |
| 4 | UI4, UI5 | render from the UI with a live tray and ranges |
| 5 | UI6, UI7 | cast and audition without editing YAML |
| 6 | UI8, UI9, UI10, UI11 | transfer, polish, harden and accept |

## 6. Definition of done

- [ ] All verifications passed; existing and new suites green; no CLI behavior change for existing flows
- [ ] Spec section 9 acceptance passed by you
- [ ] Security checks (Host, token, path confinement) tested
- [ ] Packaged install works from a clean environment with and without the `ui` extra
- [ ] Licenses verified; decisions D1-D8 logged; roadmap updated; tagged `slice-6`

## 7. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Parallel chapter workers don't speed things up (CPU already saturated) or use too much RAM | UI0 measurement; default to the measured best, allow 1 |
| GPU path is fragile on Windows (package conflicts, CUDA versions) | Treat as advanced/experimental or hide it; CPU is already fast |
| Detached processes behave differently on Windows | UI0 prototype before building on it |
| Laptop sleeps during a long render | Keep-awake while a job runs; `interrupted` state resumes from cache |
| UI and CLI touch the same book at once | Lock file in the library, honored by both |
| Partial-range bundles confuse the Player's saved progress | D7: renumber, `source_index`, range-aware id |
| Hand edits to `cast.yaml` conflict with UI edits | Patch operations, modified-time check, clear conflict message |
| Packaging misses static assets or new modules | Force-include, reinstall before testing, a clean-env install test |
| UI scope creep | Five components, four views; extras go to the backlog |

## 8. Working with the agent

- One work package per session; it implements and runs tests; **you** judge the UI visually, run the browser-closed build, and listen to auditions
- Use the design skill the agent used for the spec (Operate mode) for UI9
- No new dependency without a license check; keep the `ui` extra optional
- Commit per work package (`UIn: summary`)

## 9. After this slice

v1.1 backlog (LLM attribution, real EPUB rendering, Wi-Fi transfer) can build on this UI: attribution review and a Wi-Fi transfer step both slot into the existing stepper. Docker stays a v2/v3 note.