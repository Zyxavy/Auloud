# Slice 16 Implementation Plan: API Contract and Toolkit Spike

Follows `docs/10-V3Roadmap.md` Slice 16 and the `rust-ui` skill. Project: native Rust UI for Scribe plus the typed client it speaks to the helper with. No application screens are built here beyond the spike harness; Slice 17 builds the real app on the winner.

## 1. Goal

Give the future native app two things it cannot be built without: **a written, versioned contract for the helper API it will drive**, and **a toolkit choice made on evidence, not taste**. The Python Scribe stays canonical; no Scribe logic is ported.

**Done when:**

- `docs/api.md` documents every endpoint the UI will use, the SSE event shapes, and the token/Host protections, and matches the code
- The helper reports its contract version at runtime; a client that does not know it fails loudly, never silently
- A `scribe-client` Rust crate speaks the contract with typed requests, tested against recorded helper responses plus one live-helper test
- The same two screens run in egui, iced, and Slint; the score sheet picks one; the choice is logged
- The RS0 Tauri stub is gone; the workspace builds the client and a placeholder UI crate

## 2. Inputs and what is known

- Helper API today (`scribe/ui/app.py`, `scribe/ui/helper.py`, `scribe/ui/security.py`, `scribe/ui/server.py`): 127.0.0.1-only bind, no `--host` flag, Host allow-list (wrong Host gets 421), per-run token required on every method in helper mode (`X-Auloud-Token`, wrong token 403), no CORS, `safe_join` traversal guards, per-workspace lock, detached-job runner with JSONL events replayed as SSE (`Last-Event-ID` resume, 15 s ping), ready line `{"api":1,"port":...,"ready":true,"versions":{...}}`
- Endpoint inventory (HP4 mapping): books upload/list/detail, plan, build, jobs list/get/pause/resume/cancel, job events (SSE), settings, cast get/put/report, voices list/sample, quotes, validate, drives, transfer, open-folder. The plan keeps transfer/open-folder/drives out of v1 scope decisions for later; the contract documents what exists, the UI uses a subset
- Cast table shape (`text/cast.py`): narrator `{engine, voice, speed}`, characters map, two generics, aliases, overrides, first-person selector; engines `kokoro`/`piper` (+ Kitten direction per Slice 14)
- Design tokens live in `scribe/ui/static/index.html`: paper, ink, oxblood, sage; serif for titles; dark by inversion (roadmap Slice 21 owns the full pass, the spike only proves theming is possible)
- RS0 workspace stands: pinned toolchain (`rust-toolchain.toml`), `deny.toml`, `scribe-spec`/`scribe-core`/`scribe-cli` (parity-era crates; untouched by this slice), `scribe-ui` Tauri stub (replaced here)
- License facts (crates.io 2026-10-11): egui MIT OR Apache-2.0, iced MIT, Slint GPL-3.0-only OR Slint-Royalty-free OR Slint-commercial; the Slint grant must be confirmed before choosing it (D-147 decision 1)
- Slice 15 stop-loss (D-157): v3 ships with a documented one-time setup; the UI must therefore handle "helper missing/broken" as a first-class error, not an unreachable case

**Unknowns this slice resolves:** the exact contract text (written from code, not memory), whether the three toolkits can do the two hard screens (cast table with in-cell dropdowns, live SSE jobs tray), and which one survives Narrator, scaling, compile time, and binary size.

## 3. Scope

| In | Out (later slices) |
| --- | --- |
| `docs/api.md`: endpoints, request/response shapes, SSE events, error shapes, token/Host rules, version policy | Real Books/Book-detail/Jobs/Cast/Voices screens (Slices 17-19) |
| One new runtime endpoint: the version report (small Python addition, no new dependency) | Model manager, first-run wizard (Slice 20) |
| `scribe-client` crate: typed requests, version pin + loud mismatch, recorded-response tests, one live test | Helper lifecycle (spawn/health/restart) in the app (Slice 17) |
| Spike harness + the same two screens in egui, iced, Slint; score sheet; logged choice | Design-system pass, keyboard/screen-reader full pass (Slice 21) |
| Removing the Tauri stub and its dependency; placeholder `scribe-ui` crate on the winner | Installers, NOTICE/SBOM (Slice 22) |
| Documented "helper missing" error path (stop-loss consequence) | Audio audition playback in the app (needs a playback story; spike notes the option, Slice 19 decides) |

## 4. Key decisions (proposed; log on acceptance)

1. **Contract first, code second.** `docs/api.md` is written from the code, then the client is written from the doc; any doc/code mismatch found during client tests fixes the doc (or the code, if the code is wrong), never a third retelling.
2. **Version pin with a loud failure.** The client sends what it speaks; on mismatch it shows the upgrade message and refuses, never degrades silently. The ready-line `versions` object is the source; the new runtime endpoint exposes the same object to already-running clients.
3. **Recorded responses, one live test.** Client tests use fixtures checked into `scribe-rs/scribe-client/tests/data/` (real helper output, scrubbed of tokens/ports); exactly one integration test boots a real helper (marked slow/ignored by default so `cargo test` stays offline).
4. **Spike on the two hard screens only.** Cast table (50+ rows, in-cell voice dropdown, audition button, conflict line) and jobs tray (live progress bar fed by SSE replay, pause/resume/cancel buttons). A toolkit that cannot do these cannot do the app.
5. **Score, don't vote.** Same sheet for all three (section 8); numbers first, judgment after. The owner judges looks, Narrator behavior, and scaling; the agent measures compile time, binary size, and memory.
6. **No logic crosses the boundary.** The client shapes requests and parses responses; any cast/attribution/spec/validation rule stays Python. A second implementation is a bug, not a backup.

## 5. Work packages

### API1: Contract doc + version endpoint (S)

- Read `scribe/ui/app.py`, `helper.py`, `security.py`, `server.py`, `jobs.py`, `cast.py` (UI), `books.py`; write `docs/api.md`: base URL/port/token rules, every endpoint (method, path, auth, request/response shapes with examples), SSE event stream (line format, resume, heartbeat), error shape `{file, rule, message}` + status codes, the version policy
- Python: one runtime version endpoint exposing the ready-line `versions` object (name it `/api/version`; no new dependency; token rule follows the helper-mode convention, document the choice)
- Tests: existing Python suite stays green; one test pins the endpoint output against the ready-line versions
- **Verify:** doc reviewed against code (no undocumented route the UI needs, no documented route that does not exist); new test green; full Python suite green

### API2: Typed client crate (M)

- New `scribe-client` crate (AGPL-3.0-or-later SPDX on every file): typed request builders and response types for the UI's subset (health/version, books upload/list/detail, plan, build, jobs list/get/pause/resume/cancel, events stream, settings, cast get/put, voices list/sample, validate); version pin checked on first response with the loud-mismatch error; localhost-only guard (refuse non-loopback URLs)
- Record real helper responses into `scribe-client/tests/data/` (scrub tokens/ports); unit tests run fully offline against them; one `#[ignore]` integration test drives a real helper end to end (upload, draft poll, build one chapter, validate)
- `cargo fmt`, `cargo clippy --workspace --all-targets -D warnings`, `cargo deny check` green; no new dependency without the deny pass + `08-Licenses.md` check
- **Verify:** `cargo test` green offline; the ignored live test passes on demand against a real helper; clippy/deny green

### UI1: Spike harness + three spikes (M-L, timeboxed to about two weeks)

- Replace the Tauri stub: remove `tauri` + `tauri-build` from `scribe-ui`, delete the generated shell (`capabilities/`, `gen/`, `dist/`, `icons/` if orphaned); `scribe-ui` becomes a placeholder binary on no toolkit yet (prints version + helper-missing message) so the workspace stays green throughout
- Spike harness: a fake event feeder replaying recorded SSE lines plus a stub client returning canned responses, so all three spikes run with no helper and identical data
- Same two screens in egui, iced, Slint (feature-gated or three small bins, whichever keeps the workspace building): cast table + jobs tray per section 4.4; shared theme constants for the four tokens + serif titles to prove theming
- Record per toolkit: cold + incremental compile time, release binary size, idle + 500-row memory, notes on dropdowns, text input, async/SSE fit, file-dialog story, audition-playback story
- **Verify:** all three spikes run the two screens from the same canned data; measurements recorded on the score sheet; workspace green on the pinned toolchain

### UI2: Score, choose, scaffold (S)

- Owner session: look at the three spikes (Narrator pass, 125%/150% scaling, looks), fill the judgment rows; agent fills the measured rows first
- Log the choice in `docs/DECISIONS.md` (license grant confirmed in the entry, Slint only with its grant verified); archive the sheet in `docs/`
- Scaffold `scribe-ui` on the winner: window + theme + navigation shell + the "helper missing" error screen (stop-loss consequence); loser spikes removed from the tree (no dead crates)
- **Verify:** decision logged; workspace builds with exactly one toolkit; placeholder app opens, shows the error screen with no helper, exits clean

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | API1 | read the contract the whole app will be built on |
| 2 | API2 | hold a client that speaks it, proven against recordings |
| 3 | UI1 | run the same two screens in three toolkits |
| 4 | UI2 | see the winner scaffolded and the choice logged |

## 7. Fallback paths (decided at UI2)

| Result | Decision |
| --- | --- |
| One toolkit clearly fits | Build on it (Slice 17) |
| Two fit, judgment splits | Owner breaks the tie; note the runner-up and why |
| None fits (tables, a11y, or input disqualify all three) | Tauri reserve per D-147 decision 7: host the existing web UI; the contract + client work is reused as-is |
| Client work exposes contract holes | Fix the helper (small, versioned) before Slice 17; never work around in the client |

## 8. Definition of done

> **Closed 2026-10-26 (tag `slice-16` at `a892de4`).** egui chosen on the
> spike score sheet (D-159); the losers were removed; the typed client
> talks to a real helper over the versioned contract. The owner rows on
> the spike - looks, Narrator pass, 125%/150% scaling - were never
> reported per box; the choice stands on the measured sheet, not on
> those rows.

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Doc drifts from code | Contract-first rule (decision 1); client tests fail on drift |
| Silent version mismatch in the field | Loud failure (decision 2); tested with a faked newer helper |
| Recorded fixtures go stale | Regenerate script + live test catches it; fixtures carry the helper version |
| Slint license grant unclear | Confirm before UI2; egui/iced fallbacks measured equally |
| Spike screens flatter a toolkit | Same data, same tasks, owner judges the look; binary/time/memory measured, not felt |
| Tauri removal breaks the workspace | Placeholder binary first (UI1), scaffold after the choice (UI2); green at every commit |
| New crate dependencies | Deny pass + license check before adding; ask first per repo rules |

## 10. Prerequisites for you

> **Closed 2026-10-26.** The spike owner rows (looks, Narrator pass,
> 125%/150% scaling) were never reported per box; egui won on the
> measured score sheet and the choice stands without them. The Slint
> license grant is moot - Slint lost, so the entry never blocked. Models
> and a short book were on hand for the live test and the soak.

## 11. Working with the agent

- One work package per session; it writes the doc, the endpoint, the crate, the spikes and the sheet; **you** judge looks/accessibility/scaling, confirm licenses, and make the UI2 choice
- It must not claim spike behavior it did not run, nor owner judgment rows it cannot make; measured vs judged rows stay labeled on the sheet
- No new dependency (Rust or Python) without a license check; no helper-contract change without a `docs/api.md` update in the same commit
- Commit per work package (`APIn:` / `UIn:`); tag `slice-16`

## 12. Next

Slice 17 builds the real app on this slice: window shell and navigation, helper spawn/health/restart/clean shutdown, Books list + add-book + draft, Book detail stepper — with no browser or console.
