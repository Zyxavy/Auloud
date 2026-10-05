# Scribe Web UI — Spec Design (v1: localhost)

Mode: **Operate** (the visitor completes a task: convert a book, assign voices, monitor builds).
Theme: **Scribe** — clean, minimal, paper-and-ink. Details in section 8.

## 1. Goal

A local web UI over the existing Scribe library so a conversion never requires
the command line: pick a book, pick a chapter/page range, assign voices per
character with audition, run queued builds in the background with live
progress, and inspect/validate the result.

**Done when:** EPUB and PDF go from file to validated bundle, with per-character
voices, entirely through the UI, while a second build queues behind the first.

## 2. Non-goals (v1)

- No authentication, no multi-user, no remote access (binds `127.0.0.1` only).
- No Docker image (v2/v3; section 9 keeps the seams).
- No Page-view rendering, no Player features (Player stays a separate app).
- No new TTS engines, no new attribution rules (UI surfaces what Scribe does).
- No npm/build step for the frontend (section 6).

## 3. Requirements (user brief, mapped)

1. **Range conversion.** Chapter x-to-y (EPUB chapters, PDF outline chapters)
   or page x-to-y (PDF source pages). Applies to draft, build, and the emitted
   bundle, which contains only the range. Bundle id derives from book + range
   so caches stay stable and re-runs are minimal.
2. **Per-character voices.** Full cast table: every detected speaker with a
   voice dropdown, per-row audition, speed offset, and first-person mapping.
   Writes `cast.yaml` through the validated merge path (never clobbers).
3. **Background + hardware.** Jobs run detached from the page (close the tab,
   builds continue). Progress streams back on reconnect. CPU thread count and
   GPU provider (CUDA/DML/CPU via onnxruntime) selectable per job, detected
   at startup and shown in the UI (`scribe doctor` data).
4. **Clean minimal UI.** Section 8 (Scribe design system, Operate mode).
5. **Localhost v1, Docker later.** Section 9.

## 4. Architecture

```
browser (static HTML/CSS/JS, no framework, no build step)
  │  JSON + SSE over http://127.0.0.1:<port>
  ▼
scribe ui  (stdlib ThreadingHTTPServer in-process: zero new dependencies,
            same process as the library, AGPL-clean)
  │  direct function calls (not subprocesses)
  ▼
Scribe library (extract / text / tts / audio / bundle — unchanged logic,
                extended only where section 5 requires)
```

- One process: the server thread hosts the UI; a job-runner thread pool owns
  builds. Library calls are in-process (progress callbacks already exist for
  the CLI bar — same hooks feed SSE).
- Frontend: one static page per view (section 7), vanilla JS + `EventSource`.
  No framework keeps the audit surface tiny and the Docker image later small.
- State on disk stays canonical: work dirs, `cast.yaml`, bundles. The server
  holds only the job queue in memory (+ a JSONL job log for restart visibility).

## 5. Backend contracts (what Scribe must gain)

New library/CLI surface the UI needs; each is independently testable:

1. **Range selection.** `draft`/`build` accept `--chapters A-B` and (PDF)
   `--pages A-B`. Draft extracts only the range; build renders only ranged
   chapters; the bundle manifest records the range (`source_range`) and the
   id folds it in. Out-of-range values fail with a named error before any
   synthesis. Full-book remains the default (no flags).
2. **Cast API.** Read draft `cast.yaml` as structured data (speaker, kind,
   occurrences, current voice, speed, first-person); write back single-row
   edits through the never-clobber merge + validation; audition endpoint
   synthesizes one line in a given voice (cached like everything else).
3. **Voice inventory.** List all Kokoro voices with metadata + sample text;
   per-voice audition render; current palette defaults (narrator `am_onyx`,
   generics) pre-applied to new drafts.
4. **Job runner.** `start/cancel` a build job; job states
   (`queued/running/done/failed/cancelled`); progress events
   (chapter, sentence x/y, cached-vs-rendered counts, ETA); completion runs
   `validate` automatically and stores the result. GPU/CPU selection per job
   from detected providers.
5. **Inspect/validate as data.** Existing `inspect`/`validate` output as JSON
   (speakers with line counts, per-chapter durations, error list), not text.

## 6. HTTP + event surface (draft shape, implementation plan pins it)

- `GET /api/books` — known source files + their work dirs/bundles.
- `POST /api/draft {file, chapters?, pages?}` → job id (+ draft report JSON).
- `GET /api/cast?job=` — cast table rows; `PATCH /api/cast {row edits}`.
- `GET /api/voices` — inventory; `POST /api/audition {voice, text?}` → audio URL.
- `POST /api/build {job, voices?, speed?}` → queued; `DELETE /api/jobs/{id}` cancels.
- `GET /api/jobs` — queue with states; `GET /api/events` (SSE: progress,
  completion, validation result).
- `GET /api/bundles/{id}` — inspect JSON; validation errors inline.

## 7. Views (five, no more in v1)

1. **Books.** Source files (EPUB/PDF), per-book status (no draft / drafted /
   building / bundle ready + validation badge), "New conversion" (file path +
   range shortcut). One primary action per row.
2. **Book detail.** Range picker (chapter dropdowns populated from draft, or
   page numbers for PDFs) → Draft → quality report inline → **Cast table**
   (speaker, lines count, voice select, audition play, speed, first-person
   toggle) → Build → bundle card (duration, voices, validate badge, reveal
   in folder).
3. **Queue.** All jobs with live progress bars (chapter x/y, rendered vs
   cached, ETA), cancel buttons, completion state + validation result;
   survives tab close (server-side jobs, reconnect resubscribes).
4. **Voices.** Audition grid: every voice with play button + sample line;
   "set as narrator / generic" shortcuts. (Listening shortlist lives here.)
5. **Settings.** Hardware (provider select, CPU threads), models directory,
   `doctor` output verbatim, work-dir location. No other settings in v1.

## 8. Scribe design system (theme)

Operate mode: scanability and consistency outrank expression. Brand lives in
precise details, not decoration.

- **Palette (light-first, dark follows the OS):** paper `#FAF8F3`,
  ink `#1C1917`, muted `#78716C`, hairline `#E7E2D9`, one accent
  (oxblood `#7C2D12`, used for primary actions + the "now building"
  state only). Semantic colors only for validation (green pass / red fail).
- **Type:** system serif for book text and excerpts (Georgia, `serif`);
  system sans for UI (Segoe UI, `sans-serif`); tabular numerals for all
  timings and counts. Two sizes carry 90% of the UI (15px body, 13px meta).
- **Rhythm:** 8pt grid, 16px page gutters, one-column max 720px for reading
  surfaces (cast table, reports); queue/voices may use the full width.
- **Components (five):** table (cast, right-aligned numbers, sticky header),
  progress row (bar + `ch 3/12 · 41 rendered · ETA 22 min`), audio chip
  (play/stop + voice name), badge (validation pass/fail, job state),
  dialog (confirm destructive/costly actions: build, cancel, overwrite).
  Everything else is native HTML (selects, buttons, details/summary).
- **Rules:** no modals except confirmations; every background action shows
  progress within one second; every error names the file + rule (Scribe
  convention) and offers the next step; no animation except progress and
  state transitions (150 ms ease); keyboard-reachable controls; targets
  ≥ 40px.
- **Audition discipline:** one preview voice at a time (starting a second
  stops the first); cached audio replays instantly.

## 9. Ops seams (v2/v3, not built now)

- Bind address flag (`--host`, default `127.0.0.1`); no auth code in v1, but
  all views read one `api()` helper so adding a token later touches one file.
- Work dirs, models, bundles all path-configurable (already true) → Docker
  `-v` mounts with no code change; document the three mounts.
- GPU: provider names already abstracted (CUDA/DML/CPU); Docker needs only
  `--gpus` + the onnxruntime build that carries the provider.
- Frontend has no build step, so the image is `python + uv sync + scribe`.

## 10. Milestones (for the implementation plan)

- **W0:** server skeleton + one view (Books list over existing work dirs).
- **W1:** range-capable draft + Book detail through the quality report.
- **W2:** cast table read/write + audition (backend contracts 2-3 first).
- **W3:** job runner + Queue view with SSE (contract 4; the background core).
- **W4:** Voices + Settings + validate/inspect views (contract 5).
- **W5:** Scribe-theme pass over all views (section 8) + hardening
  (errors name file+rule, empty states, confirm dialogs).
- Each milestone: backend contract tests + one browser walkthrough each.

## 11. Open questions (for planning, not blocking W0)

- Partial-range bundles: full manifest copy with subset chapters (assumed) —
  confirm Player lists them sanely (it reads any manifest; verify once).
- Audition cache location/size policy (reuse TTS cache keyed by voice+text).
- Port default + single-instance lock (second `scribe ui` opens the browser).
- Minimum audition text per voice (one shared sample line vs per-voice).
