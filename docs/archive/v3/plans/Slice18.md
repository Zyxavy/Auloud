# Slice 18 Implementation Plan: Jobs Tray and Render Step

Follows `docs/10-V3Roadmap.md` Slice 18, the `rust-ui` skill, and `docs/api.md`.
Project: the app learns long-running renders: an always-visible tray with
live progress and job controls, a render step with preflight, and a quit
warning. Books/detail (AP2/AP3) stay as they are.

## 1. Goal

Make renders first-class: **start a multi-chapter render, watch truthful
progress with pause/resume/cancel, close the window mid-render, relaunch
to find it reattached**, and never lose one to a sleep, a quit, or a
silent stall.

**Done when:**

- A bottom tray always shows live jobs with progress, RTF, ETA, and cache
  hits, plus a settled history behind them
- Pause, resume, and cancel work from the tray with shaped errors
- Render step: chapter/range picker, PDF page range with resolved preview,
  preflight counts and time estimate, compute device, keep-awake toggle
- Closing with running jobs asks first; a closed window never kills a render
- A multi-chapter render completes with the window closed and reattaches on
  relaunch

## 2. Inputs and what is known

- Client (`scribe-client`): `list_jobs`, `job`, pause/resume/cancel,
  `plan_build`, `queue_build`, SSE iterator; version pin; 2 s pool cap
- Worker (AP1/AP2): books/upload flow on a 2 s cadence; lifecycle machine;
  `Client` lives worker-side; `mpsc` command/event protocol extends cleanly
- Contract (`docs/api.md` 4): job public shape (`summary: {cached,
  rendered, audio_ms, wall_s}`, terminal/resumable states, FIFO + per-book
  lock, `work-lock-held`/`bad-state` rules); plan preflight shape; pause is
  graceful (sentence boundary), resume reuses cache, cancel cleans torn files
- Keep-awake is server-side (`scribe/ui/keepawake.py`): while any job runs
  (and the persisted setting is on) the helper holds
  `SetThreadExecutionState`; the app only toggles `keep_awake` via `PUT
  /api/settings`. No client-side power API needed
- Device vocabulary is one enum everywhere (`auto|cpu|cuda`): app settings,
  helper settings, per-render override; the build route defaults to the
  persisted helper setting when omitted
- egui 0.36 has no `on_close_event` hook: quit warning goes through
  `close_requested` plus `ViewportCommand::CancelClose` with a confirm panel
- Detached jobs survive helper and app exit by design (HP1/AP1); reattach
  is just the jobs list on boot, which the tray already reads

**Unknowns this slice resolves:** tray layout that stays readable with
several jobs, preflight estimate accuracy on real books, and whether the
2 s poll keeps progress smooth or needs SSE after all.

## 3. Scope

| In | Out (later slices) |
| --- | --- |
| Bottom tray: live rows + history, pause/resume/cancel | Cast/voices editing + audition playback (Slice 19) |
| Render panel: chapter/range + PDF pages + preflight + device + start | Validate/transfer/model-manager UI (Slice 20) |
| Keep-awake toggle (helper setting), compute device default | Design/a11y pass (Slice 21), installers (Slice 22) |
| Quit confirm with running jobs | Auto-update, cloud, macOS (non-goals) |
| Progress math (RTF, ETA, percents) as tested pure functions | |

## 4. Key decisions (proposed; log on acceptance)

1. **Summary polling first, SSE only on evidence.** Progress renders from
   the job `summary` on the existing 2 s cadence (RTF = audio/wall,
   percent from plan totals, ETA from the preflight estimate). The SSE
   iterator stays unused until a book proves polling too coarse.
2. **History is the server list, not a local store.** Terminal jobs stay
   visible under a history group; the app keeps no job database to drift.
3. **Quit confirm, never quit block.** Close with running jobs shows what
   runs and asks; confirming quits (renders continue detached), cancelling
   stays. One `CancelClose`, no nagging.
4. **Per-render device defaults to the app setting.** The render panel
   offers an override; omitted means the helper's persisted device. Same
   enum, three places, one meaning.
5. **No logic crosses the boundary (standing rule).** Percents, RTF, and
   ETA are arithmetic over reported numbers; any rule about what they mean
   stays Python.

## 5. Work packages

### JP1: Tray + job controls (M)

- Bottom strip on every screen: live rows (label, state, progress bar,
  percent, RTF, ETA, cache hits) + history group (terminal jobs, newest
  first); worker `RefreshJobs` rides the 2 s poll; pause/resume/cancel
  buttons per resumable row with shaped errors
- Progress math as pure tested functions (`progress.rs` or in `books.rs`
  style module): percent from plan/rendered totals, RTF from
  audio_ms/wall_s, ETA from estimate minus elapsed; unit tests over canned
  summaries (no helper needed)
- Reattach falls out of the list-on-boot the tray already reads; prove it
  in JP3, build nothing extra for it
- Tests: math unit tests; worker jobs round-trip against recorded shapes
  where possible; no live helper in the default suite
- **Verify:** pause/resume/cancel a real render from the tray; history
  survives relaunch; suite + clippy + deny green

### JP2: Render panel (M)

- Per-book render section (Book detail gains it; AP3 stepper stays):
  chapter checkboxes from `detail.chapters` plus a range string field
  (one form wins, the other clears), PDF page range with the plan's
  `page_resolution` preview text, device override defaulting to the app
  setting, keep-awake checkbox wired to `PUT settings`
- Preflight block from `plan_build`: selected/cached/to-render counts,
  estimated seconds + RTF basis, range-id notice when `is_range`; Start
  queues the build and drops to the tray
- Range/chapters-pages-exclusive and bad-range errors render as file + rule
- Tests: selection-form logic ( winners, exclusivity, clearing) unit tests;
  preflight rendering over the recorded `plan.json` fixture
- **Verify:** multi-chapter Kokoro render to done; a PDF page-range render;
  estimate within shouting distance of wall time; suite green

### JP3: Quit warning + acceptance (S)

- Close intercept: `close_requested` with running jobs shows what runs
  with Quit / Stay buttons (`CancelClose` on stay); no jobs means no
  interruption, ever
- Acceptance checklist (`docs/acceptance-slice18.md`): multi-chapter
  render with the window closed + relaunch reattach, pause/resume/cancel
  live, quit both ways, keep-awake overnight evidence if offered, resize
- **Verify:** owner acceptance logged; suite + clippy + deny green; tag
  `slice-18`

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | JP1 | watch and control live jobs from the tray |
| 2 | JP2 | start honest renders with preflight |
| 3 | JP3 | quit safely and tag |

## 7. Fallback paths (decided at JP3)

| Result | Decision |
| --- | --- |
| 2 s polling visibly steps progress | Halve the jobs cadence (books stay 2 s); SSE only if still coarse |
| Preflight estimates mislead on real books | Show wall-clock rate beside the estimate; never hide either |
| Quit confirm annoys | Keep it jobs-only (already the rule); no setting to disable safety |
| egui layout cramps the tray | Tray collapses to one line per live job + counts; history behind a toggle |

## 8. Definition of done

> **Closed 2026-10-26 (tag `slice-18` at `3e17bf2`).** Jobs tray with
> pause/resume/cancel, render panel with preflight, all agent-complete
> with suite, clippy and deny green. Two items need a real render and
> were covered by the soak in `docs/acceptance-slice22.md` criterion 4
> (mid-render app kill and resume, pause and resume, range re-render),
> run good: the window-closed multi-chapter render reattaching on
> relaunch, and pause/resume/cancel each pressed live.
> **Not done: "quit with running jobs both ways (quit + stay)".** The
> soak's mid-render kill is a kill, not a quit; the quit confirm with
> running jobs was never pressed by anyone here.

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Polling load on helper + UI | One 2 s jobs poll (shared for tray + history); SSE only on evidence |
| Pause/resume races (state changed underneath) | Render server truth each poll; `bad-state` shapes already handled |
| Quit during cancel/pause transitions | Confirm lists states live; quitting never kills detached work anyway |
| Keep-awake left on forever | It is the helper's persisted setting with its own UI; app only toggles |
| Scope creep into cast/validate UI | Tray + render panel only; editing actions keep their slice labels |

## 10. Prerequisites for you

> **Closed 2026-10-26, with one named gap.** A multi-chapter book and
> models were on hand; pause/resume/cancel were each pressed during the
> soak (mid-render kill and resume, pause and resume, range re-render);
> tray density, preflight honesty and error wording were judged as part
> of the native-UI walk in `docs/acceptance-slice22.md` criterion 3.
> Overnight keep-awake evidence is optional and was not reported.
> **Not done: "quit with running jobs both ways (quit + stay)".** The
> soak's mid-render kill and resume is a kill, not a quit; the quit
> confirm with running jobs was never pressed by anyone here.

## 11. Working with the agent

- One work package per session; it writes the tray, panel, and tests;
  **you** run live renders, press the controls, and judge density/wording
- It must not claim render behaviors it did not run against a real helper
- No new dependency without a license check; no contract change without a
  `docs/api.md` update in the same commit
- Commit per work package (`JPn:`); tag `slice-18`

## 12. Next

Slice 19 builds on this slice: cast table with voice dropdowns and audition
playback, overrides editor with quote picker and rebuild preflight, voices
view with the engine selector: all against the jobs flow proven here.
