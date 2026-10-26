# Slice 19 Implementation Plan: Cast and Voices

Follows `docs/10-V3Roadmap.md` Slice 19, the `rust-ui` skill, and `docs/api.md`
sections 6-7. Project: the app learns the cast. A per-book cast table with
voice dropdowns and speed editing, an overrides editor with a quote picker,
a read-only cast report, and a voices view with engine selector and audition
playback. Jobs tray + render panel (Slice 18) stay as they are.

## 1. Goal

Edit the cast and hear voices without touching YAML or a console: **open a
drafted book, see every speaker with line counts and minutes, change a voice
or speed from a dropdown, audition the voice in the app, add an override
from a real quote, and rebuild only what changed** — with the file-changed
conflict never silently eaten.

**Done when:**

- Cast table per drafted book: narrator + characters + generic rows, voice
  dropdowns, speed editing, first-person selector, minor characters
  collapsed, conflict message when the file changed underneath
- Overrides editor with quote picker (low-confidence first) and the
  "rebuild only affected lines" preflight (`affected` counts)
- `cast_report` shown read-only
- Voices view with engine selector (Kokoro, Piper, Kitten when installed)
  and in-app audition playback
- Owner acceptance logged; suite + clippy + deny green; tagged `slice-19`

## 2. Inputs and what is known

- Contract (`docs/api.md` 6): `GET cast` (`{mtime, cast, first_person,
  stats: [{character, kind, voice, speed, lines, minutes, low}], minor,
  quotes_total, low_confidence_total, validation}` + `comment_warning`);
  `PUT cast` full replace with `expected_mtime` (`409 conflict` with
  `current_mtime, cast`); `PATCH cast` ops (`set_voice`, `set_speed`,
  `set_first_person`, `add_override`, `remove_override`; all-or-nothing);
  `GET cast/report` (`markdown`, `low_confidence`); `GET quotes`
  (`confidence=all|low`, paged, reading order)
- Contract (`docs/api.md` 7): `GET /api/cast/voices?engine=`
  (`{voices, details: [{name, locale, gender}], sample_text}`); `GET
  /api/voices/{name}.wav` (immutable-cached, never starts a job);
  `POST /api/voices/sample` (`{cached: true}` inline, else `202 {job_id,
  coalesced}`; the tray already polls jobs)
- Client (`scribe-client`): `cast`, `save_cast` (PUT), `voices`,
  `voice_details`, `sample_voices` exist. Missing: PATCH ops, cast report,
  quotes paging, wav-bytes fetch. All four are small typed wrappers in the
  established `send_json`/`get_json` style; no contract change, same commit
  as first use
- Worker (Slice 18): `mpsc` command/event protocol extends cleanly; render
  panel's `QueueBuild` is the rebuild path (overrides apply on next build;
  `affected` quotes tell the preflight what changed)
- egui 0.36 has no audio playback: audition needs a playback dependency
  (decision CS0 below, owner approval required by standing rule)

**Unknowns this slice resolves:** table layout that stays readable with
large casts (Slice 21 owns the polish pass), audition latency on real
voices, and whether per-line rebuild preflight matches wall behavior.

## 3. Scope

| In | Out (later slices) |
| --- | --- |
| Cast table: rows, dropdowns, speeds, first-person, minor collapsed | Validate/transfer/model-manager UI (Slice 20) |
| Conflict message + reload on 409 | Design/a11y pass (Slice 21), installers (Slice 22) |
| Overrides editor + quote picker + affected preflight | LLM attribution (backlog) |
| cast_report read-only view | Auto-drafted cast from frequency (backlog) |
| Voices view: engine selector, voice list, audition playback | |
| Quit warning stays; tray owns sample jobs | |

## 4. Key decisions (proposed; log on acceptance)

1. **PATCH ops first, PUT as fallback.** Single-field edits (voice, speed,
   first-person, one override) go through PATCH ops; PUT full-replace stays
   for nothing in this slice (no bulk editor). Fewer torn states.
2. **Conflict never auto-merges.** On 409 the panel shows whose change won
   (`current_mtime`), offers Reload (drop mine) and keeps my edits visible
   so nothing typed is lost silently. No three-way merge.
3. **Audition plays cached wavs, polls sample jobs.** `GET
   /api/voices/{name}.wav` is free and immutable-cached; uncached voices
   queue a sample job (tray shows it) and the row offers Play when the wav
   lands. No new polling loop: the 2 s jobs feed plus a wav fetch.
4. **CS0 dependency gate.** Audition playback needs an audio crate the repo
   does not have. No dependency is added without owner approval with a
   license check (`cargo deny` must stay green); if declined, CS3 ships
   "reveal sample file" via the existing open-folder path instead.
5. **No logic crosses the boundary (standing rule).** Affected-quote
   counts, confidence, and stats render as reported; any rule about what
   they mean stays Python.

## 5. Work packages

### CS0: Audition playback decision (S, owner)

- Candidate crate (e.g. rodio), license check (`cargo deny` clean),
  `minSdk`-irrelevant (desktop only) but offline-build note; fallback is
  "reveal the wav" with no new dependency
- **Verify:** owner picks playback or reveal; decision logged

### CS1: Cast table + conflict (M)

- Per-book cast section (detail gains it; render section stays): stats
  rows (character, kind chip, voice dropdown from `voices(engine)`,
  speed field, lines + minutes, low-confidence flag), narrator and
  generic rows pinned, first-person selector, minor behind a toggle
- Edits via PATCH ops (new client `patch_cast`); 409 renders the conflict
  message with Reload that keeps typed edits on screen
- `comment_warning` renders when the file has `#` comments
- Tests: op-payload shaping unit tests; worker cast round-trip against
  recorded shapes; no live helper in the default suite
- **Verify:** change a voice + speed on a real book, rebuild, hear the
  difference; force a conflict (edit `cast.yaml` mid-session) and see the
  message; suite + clippy + deny green

### CS2: Overrides + report (M)

- Overrides editor: quote picker (`GET quotes`, low-confidence default,
  paged), add/remove override ops, `affected` counts as the "rebuild only
  this" preflight, wired to the render panel's Start
- `cast_report` read-only view (markdown text; low-confidence list links
  into the picker where cheap, else plain list)
- Tests: picker paging logic unit tests; affected-counts rendering over
  recorded fixtures
- **Verify:** override one quote, see its affected count, rebuild, hear
  only that line change; suite green

### CS3: Voices view + audition (M, needs CS0)

- Voices screen: engine selector, voice list (name, locale, gender),
  sample text shown; Play fetches the cached wav, else queues a sample
  job and enables Play on arrival
- Tests: engine-switch + cached/queued state unit tests over recorded
  shapes
- **Verify:** audition three voices on real hardware; a queued sample
  enables Play without a restart; suite + clippy + deny green

### CS4: Acceptance + tag (S)

- Acceptance checklist (`docs/acceptance-slice19.md`): edit, audition,
  override, rebuild without YAML; conflict both ways; engine switch
- **Verify:** owner acceptance logged; suite + clippy + deny green; tag
  `slice-19`

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 0 | CS0 | owner picks playback or reveal |
| 1 | CS1 | edit voices and speeds without YAML |
| 2 | CS2 | fix single lines from real quotes |
| 3 | CS3 | hear every voice before choosing |
| 4 | CS4 | tag |

CS1 needs no CS0 answer (dropdowns work without playback). CS3 waits on CS0.

## 7. Fallback paths (decided at CS4)

| Result | Decision |
| --- | --- |
| Cast table cramps on big books | Minor-collapsed by default (already the rule); Slice 21 owns virtualized rows |
| PATCH conflicts annoy on shared files | Keep Reload-loses-mine explicit; single-user workspaces rarely hit it |
| Audition latency misleads | Show queued/loading state on the row; never a fake spinner-to-silence |
| Sample job coalescing confuses | Row shows whose job it waits on (`coalesced` names the active job) |

## 8. Definition of done

> **Closed 2026-10-26 (tag `slice-19` at `c576cce`).** Cast table,
> conflict message, overrides editor, cast_report, voices view with
> engine selector and audition all agent-complete with suite, clippy and
> deny green. The slice's own "done when" - edit the cast, audition,
> apply an override and rebuild without touching YAML - is part of the
> native-UI walk recorded in `docs/acceptance-slice22.md` criterion 3.

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| New audio dependency (license, size, offline build) | CS0 gate: license check + deny green before any code; reveal-fallback ready |
| Conflict lost edits | Typed edits stay on screen; Reload is explicit, never automatic |
| Quote paging load on helper | `per_page` capped (default 100); low-confidence filter server-side |
| Scope creep into validate/transfer UI | Cast + voices only; anything else keeps its slice label |
| Playback on the tablet (irrelevant) | Desktop-only concern; Player untouched |

## 10. Prerequisites for you

> **Closed 2026-10-26.** CS0 is decided and landed: rodio 0.23
> (`default-features = false`, `playback` + `wav`) is in `Cargo.toml` and
> the owner picked in-app playback over reveal-fallback (D-162). Table
> density, dropdown wording and error text were judged as part of the
> native-UI walk recorded in `docs/acceptance-slice22.md` criterion 3,
> whose wording is "add book, draft, **cast edit with audition**, render
> with ranges, validate and transfer" - so a multi-voice book, an
> override and an audition were on that walk.
>
> Not separately recorded: a low-confidence quote, one forced
> `cast.yaml` conflict judged message-first, three voices auditioned, and
> an engine switch tried. Those are per-box owner actions; the soak was
> a one-line verdict, so they are not claimed from it.

## 11. Working with the agent

- One work package per session; it writes the panel, ops, and tests;
  **you** edit voices, press Play, and judge density/wording
- It must not claim cast behaviors it did not run against a real helper
- No new dependency without a license check; no contract change without a
  `docs/api.md` update in the same commit
- Commit per work package (`CSn:`); tag `slice-19`

## 12. Next

Slice 20 builds on this slice: validate list, transfer with hash verify,
model manager and first-run wizard, all against the jobs flow proven in
Slice 18.
