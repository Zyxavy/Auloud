# Slice 17 Implementation Plan: App Shell, Helper Lifecycle, Books

Follows `docs/10-V3Roadmap.md` Slice 17, the `rust-ui` skill, and `docs/api.md`
(the contract). Project: the egui app becomes usable end to end for the
front half of the workflow: open, add a book, draft it, with no browser
or console. Cast/render/validate/transfer screens stay placeholders with
their Slice 19/20 labels until their slices.

## 1. Goal

Prove the native app can own the helper and the first half of the workflow:
**launch the helper, watch it, talk to it, add a book through a native
dialog, draft it, and show where the book stands**, all inside the egui
shell from UI2.

**Done when:**

- Opening the app starts a helper (or shows exactly why it could not),
  health-checks it, restarts it on crash, and shuts it down cleanly on exit
- Workspace location and device are settings, persisted across restarts
- Books list shows every book with status chips; empty state names the next
  action
- Add-book copies an EPUB/PDF into the workspace through a native file
  dialog and queues its draft job
- Book detail stepper (extract, draft, cast, render, validate, transfer)
  shows each step's artifact from real helper data
- A draft completes in-app; errors render as file and rule, never a panic
  or a hang

## 2. Inputs and what is known

- Scaffold (`scribe-ui/src/main.rs`): egui window, theme tokens, five-screen
  nav, helper-missing panel; `Screen::lands_in` already points here
- Client (`scribe-client`): blocking calls, loud version pin, SSE iterator,
  loopback guard, 2 s pool idle cap; live-proven in API2
- Contract (`docs/api.md`): books upload/list/detail, plan, build, jobs,
  events SSE, settings, cast/voices reads; version policy section 8
- Helper CLI (`scribe --help`, HP1/HP4): `helper --workspace DIR --port 0`
  with `AULOUD_HELPER_TOKEN`, ready line `{"ready", "port", "versions"}`;
  detached jobs survive helper exit; per-workspace lock refuses doubles
- Stop-loss (D-157): helper-missing stays a first-class screen, not an edge
- egui lessons from UI1: no side panels in 0.36 (nav is a top strip);
  500 ms repaint pacing; `request_repaint_after` drives live regions
- File dialogs: no crate in the tree yet; `rfd` is the candidate (license +
  deny check at implementation, owner approves the add)

**Unknowns this slice resolves:** helper discovery across dev/bundled
layouts, the worker-thread shape for blocking client calls, where app
settings live, and whether the egui update loop stays smooth during a live
draft poll.

## 3. Scope

| In | Out (later slices) |
| --- | --- |
| Helper spawn/health/restart/shutdown + crash + missing screens | Jobs tray history, pause/resume/cancel buttons (Slice 18) |
| Settings (workspace location, device, helper path) persisted | Render step UI (ranges, preflight, keep-awake) (Slice 18) |
| Books list with status chips + empty states | Cast/voices editing + audition playback (Slice 19) |
| Add-book native dialog + workspace copy + draft queue | Validate/transfer/model-manager UI (Slice 20) |
| Book detail stepper reading real artifacts | Design/a11y pass (Slice 21), installers (Slice 22) |
| Error rendering as file + rule everywhere | Auto-update, cloud, macOS (non-goals) |

## 4. Key decisions (proposed; log on acceptance)

1. **Worker thread owns the client, the UI thread owns pixels.** All
   `scribe-client` calls (including the SSE iterator) run on one worker
   thread; results travel over `mpsc` and poke `request_repaint`. No
   blocking call on the UI thread, no async runtime in the app.
2. **Helper discovery is explicit with a dev fallback.** Settings hold an
   optional helper path; empty means: bundled layout next to the exe, else
   the dev tree (`python -m scribe` via the venv layout HP1/HP4 use). The
   resolved command is shown on the missing screen so a bad path is
   diagnosable, never mysterious.
3. **One helper per workspace.** The helper's own lock already refuses
   doubles; the app surfaces that refusal as "already running" instead of
   fighting it. Closing the window stops the helper the app started, and
   only that one (detached jobs keep rendering by design).
4. **Version mismatch is an upgrade screen.** The client's loud error
   becomes a full screen naming both versions and the doc, never a toast.
5. **Settings live beside the workspace, in plain JSON.** No new settings
   crate: `{helper_path?, workspace, device}` in a versioned JSON file next
   to (not inside) the workspace, written atomically. Device mirrors the
   helper's own `auto|cpu|cuda` vocabulary.
6. **No logic crosses the boundary (restated for the new code).** Stepper
   states, chips, and counts render what the helper reports; any rule about
   what they mean stays Python.

## 5. Work packages

### AP1: Helper lifecycle + settings (M)

- Spawn: resolved command + per-run token env, `--workspace`, `--port 0`;
  parse the ready line (timeout + loud failure); health-check truth;
  watchdog restarts on unexpected death (backoff, then the crash screen);
  clean shutdown on exit (and only the app's own helper)
- Missing/crash/version screens per decisions 3-4; settings form
  (workspace, device, helper path) with persisted JSON per decision 5
- Worker-thread + channel skeleton that later WPs reuse (first user: the
  health poll); 500 ms UI pacing as in the spike
- Tests: lifecycle state machine unit tests (spawn/ready/crash/stop
  transitions with a fake child); settings round-trip tests; no live
  helper in the default suite
- **Verify:** kill the helper mid-run and watch the crash screen; wrong
  token and wrong version each show their screen; restart the app and the
  settings persist; suite green, clippy/deny green

### AP2: Books list + add-book (M)

- Books screen: list from `GET /api/books` with status chips (drafting,
  draft failed, cast edited, rendering %, bundles/valid), refresh on
  worker poll; empty state naming add-book as the next action
- Add-book: native file dialog (`rfd` candidate; owner approves the add
  with its license/deny row), `.epub`/`.pdf` filter, copy into the
  workspace (never link outside files), `POST upload`, draft-job queued
  notice with a link to the book
- Errors (bad extension, too large, unreadable, name taken) render as
  file + rule from the contract shapes
- Tests: copy/dedupe/name-taken logic unit tests with temp dirs; dialog
  itself is owner-tested (native modal)
- **Verify:** add a real EPUB and PDF, watch both draft from the list;
  bad files show shaped errors; suite green

### AP3: Draft progress + Book detail stepper (M)

- Draft progress on the book (worker polls `GET /api/jobs/{id}`, SSE
  optional; reuse the client's event iterator where it pays)
- Detail stepper (extract, draft, cast, render, validate, transfer): each
  step reads its artifact (`script.json` presence, `cast.yaml` + mtime,
  bundles list, validation findings) and shows done/pending/failed;
  stale-cast hint when `cast.yaml` is newer than the script
-auta: no editing actions yet (Slice 19); the stepper is read-only truth
- Tests: stepper-state mapping unit tests over canned detail payloads
  (recorded fixtures live in `scribe-client/tests/data/`)
- **Verify:** a drafted book shows a full true stepper; a fresh upload
  walks extract to draft live; suite green

### AP4: Errors, empty states, acceptance (S)

- Pass over every screen: every failure path shows file + rule; every
  empty list names the next action; long titles/paths truncate, never
  overflow; window resize 800x600 to 1920x1080 stays usable
- Acceptance run: fresh workspace, add book, draft to done, restart app
  mid-draft and watch reattach-by-id, kill helper and watch the crash
  screen, wrong-helper-version upgrade screen
- **Verify:** acceptance script (new `docs/` checklist or test-log entry)
  completed by the owner; suite + clippy + deny green; tag `slice-17`

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | AP1 | open the app against a real helper with settings that persist |
| 2 | AP2 | add books and watch the list |
| 3 | AP3 | follow a draft into a truthful stepper |
| 4 | AP4 | run the acceptance and tag |

## 7. Fallback paths (decided at AP4)

| Result | Decision |
| --- | --- |
| egui update loop jitters under poll load | Slow the poll (2 s), batch repaints; document, do not thread the UI |
| Native dialog misbehaves on Windows | Fall back to typed-path entry for the slice; revisit with evidence |
| Helper spawn differs per layout more than expected | Discovery gains per-layout probes; the missing screen shows each attempt |
| rfd license/weight unacceptable | Typed-path entry + drag-drop later; no other dialog crate without asking |

## 8. Definition of done

- [ ] Helper lifecycle (spawn/health/restart/shutdown/crash/missing/version) all shown states
- [ ] Settings persist across restarts
- [ ] Books list + add-book + draft queue against a real helper
- [ ] Detail stepper truthful on canned and live data
- [ ] Errors as file + rule; empty states name next actions
- [ ] Owner acceptance run logged; suite + clippy + deny green; tagged `slice-17`

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Blocking client calls freeze the UI | Worker thread owns all client calls from AP1 (decision 1) |
| Two helpers on one workspace | Surface the lock refusal (decision 3); never force it |
| Silent version skew in the field | Upgrade screen (decision 4); pin tested with a faked newer helper |
| Scope creep into render/cast UI | Stepper is read-only; editing actions refuse with their slice label |
| New crates (`rfd`, maybe `dirs`) | License + deny + owner approval before adding, per repo rules |

## 10. Prerequisites for you

- [ ] Approve the `rfd` add (or name the alternative) when AP2 reaches it
- [ ] Run the acceptance (AP4): fresh workspace, real books, restart/kill cases
- [ ] Judge nav labels, empty-state wording, and error readability
- [ ] Models + short books on hand for live runs

## 11. Working with the agent

- One work package per session; it writes the lifecycle, screens, and
  tests; **you** approve new crates, run the acceptance, and judge wording
- It must not claim helper behaviors it did not run against a real helper,
  nor owner-judgment rows it cannot make
- No new dependency without a license check; no contract change without a
  `docs/api.md` update in the same commit
- Commit per work package (`APn:`); tag `slice-17`

## 12. Next

Slice 18 builds on this slice: always-visible jobs tray with live progress
and history, render step (chapter/range pickers, preflight, compute
settings), pause/resume/cancel, reattach after restart, quit warning.
