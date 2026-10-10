# Slice 11 Implementation Plan: Voice Settings and Engine Switching

Project: Auloud Player (main). Follows `16-V2-Roadmap.md` (Slice 11) and builds on Slice 8 (engines, voice roles, `VoiceMapper`, voice settings screen) and Slice 10 (render service, planner, per-chapter `render_fingerprint`, partial books, hub).

## 1. Goal

Change a book's narrator voice, dialogue voice, speeds or engine, and have the app **know exactly which rendered chapters are now out of date**, show what re-rendering would cost, and re-render **only those chapters**, without breaking playback or losing your place.

**Done when** (from the roadmap): switching a book from one engine to another and re-rendering a chapter works without editing files by hand.

## 2. Inputs and open items

- Per-chapter `render_fingerprint` (engine, voice ids, speeds, versions) is already written by Slice 10; this slice is its first consumer
- Carried over from Slice 10: the render panel's "Voices" link lands on Settings instead of the book's voice screen; fix it here
- **SysLong verdict** (5-minute run, cool tablet) is still needed for Slice 13 streaming and the default render-ahead window, not for this slice
- **D-066 license call** is needed before Slice 12, because it decides whether Piper (`full` flavor) ships; this slice must work in both flavors, with `core` (System TTS) as the required path
- Provisional numbers (beep offset, RTF band, the 400 ms/word estimate, the 40 °C guard) get tuned from real renders; re-render estimates use the same constants

## 3. Scope

| In | Out (later) |
| --- | --- |
| Per-book voices with global defaults; engine switching with mapping | Per-character voices on the device |
| Stale-chapter detection from fingerprints; impact estimate | Re-rendering Scribe (PC) bundles on the device (read-only; see decision 6) |
| Re-render pipeline: stale only, from here, all; atomic swap | Per-role audio caching to avoid redoing the unchanged voice (backlog) |
| Voice settings polish: real-line audition, A/B compare, speed per role | Streaming (Slice 13) |
| Library and chapter badges for stale and mixed-voice books | Cloud voices |

## 4. Key design decisions (log from D-113 onward)

1. **Global defaults, per-book voices.** New books copy the global narrator and dialogue voices at first render; each book then keeps its own voices in its manifest. Changing global defaults never touches existing books. A "use as default for new books" option in the book's voice screen promotes a choice to global.
2. **A chapter is stale when its fingerprint differs from the book's current voice settings**, but only for the roles that chapter actually uses. A chapter with no dialogue sentences does not become stale when only the dialogue voice changes. Check how Slice 10 builds the fingerprint; if it always includes both roles, change it to include only used roles (older fingerprints keep working as "stale" at worst).
3. **Three chapter states beyond "not rendered":** *Current*; *Stale* (voice, speed or engine differs); *Outdated* (only the engine's version string differs, for example after a system TTS update). Outdated is shown quietly and never re-renders automatically.
4. **Re-render replaces whole chapters, safely.** The new audio is written under a new, fingerprint-based filename, finalized with the Slice 10 write order, and only then does the manifest switch to it; the old file stays playable until the swap and is deleted afterwards (deferred while the player has it loaded). Cancel or failure leaves the old audio untouched. Reusing the unchanged role's audio is not possible because the spool is deleted after finalize, so a role-level cache stays in the backlog.
5. **Position survives re-render.** Voices and speeds change durations, so a saved position inside a re-rendered chapter is converted old milliseconds to sentence id to new milliseconds before the swap, using the Slice 10 conversion.
6. **Scribe (PC) bundles stay read-only for voices.** They carry per-character PC audio; re-rendering on the device would replace it with a two-voice version. Show their voices, disable editing with a plain explanation, and revisit a "make a device copy" feature later. Say if you want a different rule.
7. **Mixed-voice books are legal** (some chapters old, some new) and are labeled as such; "finish re-rendering" is one tap.
8. **Gain follows the role:** when a role's voice or engine changes, its book-level gain is re-derived from the first chapter rendered with the new voice. Already rendered chapters keep the loudness baked into their audio.
9. **Applying a change always shows impact first:** how many chapters, hours of audio, estimated render time (from the benchmark and measured speed), and storage needed (old plus new during swap), with choices: re-render now (reading position forward first), later, or keep old audio.
10. **Slow-engine warning:** picking an engine categorized "Too slow" or "Background" shows the estimated render time per hour of audio before you confirm.

## 5. Work packages

### VS0: Prep (S)

- Read the Slice 10 fingerprint code, the manifest `voices` handling, the voice settings screen, `VoiceMapper`, and how the render panel and hub link to settings
- Write `docs/fingerprint.md`: exactly which fields go into a chapter fingerprint and how "roles used" is determined
- Log decisions 1-10
- **Verify:** document reviewed; no behavior change

### VS1: Per-book voice model (M)

- `BookVoices` (narrator, dialogue, speeds, engine) read and written through the manifest `voices` entries; global defaults as fallback; Scribe-rendered books flagged read-only
- Validation: engine available, voice installed, model pack present; unset dialogue voice means "same as narrator"
- Engine switch uses `VoiceMapper` (keep, then same locale, then first sorted) with a mapping preview
- **Verify (JVM tests):** read/write round trip, defaults, read-only books, mapping across engines, validation errors

### VS2: Stale detection (M)

- `ChapterRenderState` per chapter from fingerprints vs the book's voices and the roles used; book summary (counts, hours of audio affected, estimated render time, storage needed)
- **Verify (JVM tests):** a matrix: change narrator voice, dialogue voice only (chapter without dialogue stays current), speed, engine, version-only, unrendered chapter, partial book, old-format fingerprints

### VS3: Re-render pipeline (L)

- Planner extension: stale-only, from here, all; re-render jobs reuse the Slice 10 service, queue and guards
- Versioned filenames, atomic manifest switch, deferred deletion of old audio, orphan cleanup in recovery
- Position conversion (decision 5); cancel and failure keep old audio; gain re-derivation (decision 8); fingerprint updated per chapter on success
- Changing voices while a render is running: pause the job, recompute the plan, ask what to do
- **Verify (JVM tests with fakes):** order and selection, swap write order with crash simulation at each step, position conversion, cancel and failure paths, voice change mid-render

### VS4: Voice settings UI (M)

- A voice screen reachable from the render panel and the hub (fixing the Settings-only link), with scope for this book or global defaults
- Real-line audition: a narration sample and a dialogue sample taken from the book itself; A/B compare of two voices; speed stepper per role; engine picker with the benchmark category and the slow-engine warning; mapping preview when switching engines
- Apply flow with the impact dialog (decision 9)
- **Verify (JVM tests):** view-model states, apply flow outcomes, sample selection; **device:** visual check

### VS5: Library and chapter badges (S-M)

- Library chip for books with stale chapters; chapter list badges for Current, Stale, Outdated, Not rendered; per-chapter re-render action; "Re-render stale" button; mixed-voice banner; delete stale audio option
- **Verify (JVM tests):** state-to-badge mapping, counts; **device:** visual check

### VS6: Edge cases and guards (S)

- Engine or voice no longer available (system TTS engine removed, model pack deleted): playback unaffected, re-render blocked with a clear message and the way to fix it
- Low storage during swap; read-only Scribe books; roles unset; a voice change on an unrendered book simply updates settings
- **Verify (JVM tests):** each failure mode produces the right message and leaves audio intact

### VS7: Acceptance (you)

- [ ] Change only the dialogue voice: chapters with dialogue become stale; a chapter with no dialogue stays current
- [ ] Change narrator speed: all rendered chapters stale; the impact dialog's numbers look plausible
- [ ] Re-render one chapter while listening to a different one: no glitch
- [ ] Re-render the chapter you are partway through: position preserved afterward
- [ ] Cancel mid re-render: old audio still plays and the chapter is still marked stale
- [ ] Switch engine (System to Piper in the `full` flavor, if D-066 allows): mapping preview sensible; one chapter re-rendered; mixed-voice banner shown
- [ ] Real-line audition and A/B compare work for both roles
- [ ] Scribe-rendered book: voices visible, editing disabled with an explanation
- [ ] Library chip, chapter badges and counts match reality; the render panel's "Voices" link opens the book's voice screen
- [ ] Estimates versus actual render time and storage recorded in `docs/test-log.md`
- [ ] Existing rendering, partial-book playback and read-along unaffected

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | VS0, VS1, VS2 | see stale states computed on the JVM |
| 2 | VS3 | re-render safely with fakes under test |
| 3 | VS4, VS5 | change voices and see badges on the tablet |
| 4 | VS6, VS7 | harden and accept |

## 7. Definition of done

- [ ] All verifications passed; Player suite green, no regression
- [ ] Device acceptance passed on the Tab E, with the `core` flavor required and `full` optional
- [ ] Decisions logged; test numbers recorded; roadmap ticked only for verified items; tagged `slice-11`

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Swapping audio while the player has the file open | Versioned filenames; deferred deletion; swap tested while playing |
| Position drift after re-render | Convert through sentence ids; test at chapter start, middle, end |
| Over-eager "stale" (annoying re-renders) | Roles-used fingerprints; Outdated state for version-only changes |
| Under-eager staleness (silent mismatch) | Fingerprint doc and matrix tests; badges always visible |
| Storage pressure during swap (old plus new) | Estimate before applying; low-space guard |
| Engine becomes unavailable after an OS update | Playback unaffected; clear blocked-state message |
| Users change voices mid-render | Pause, recompute, ask |
| Scope creep into per-character voices | Explicitly out; Scribe books stay read-only |

## 9. Working with the agent

- One work package per session; it implements and tests on the JVM with fakes; **you** run device checks and listen to the auditions
- Do not change the on-disk fingerprint without updating `docs/fingerprint.md` and the matrix tests
- No new dependency without a license and `minSdk 24` check
- Commit per work package (`VSn: summary`); tag `slice-11`

## 10. Next

**Slice 12, the v2.0 complete pass:** an on-device soak with a full novel imported, rendered and listened to for days; the D-066 decision implemented (with `NOTICE`, in-app licenses and `08-Licenses.md`); remaining measurements and constants retuned from real data; the Room v1-to-v2 upgrade check; docs; tag `v2.0.0`. **Slice 13 (streaming)** follows, gated by the SysLong verdict.