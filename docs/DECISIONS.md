# Decision Log

One entry per decision, newest at the bottom. Status: **Accepted** (you decided) or **Proposed** (suggested in the design docs, not yet confirmed by you). Change a status when you confirm or reverse something, and add a new entry rather than deleting an old one.

## Template

```
### D-000: Title
- Date:
- Status: Proposed | Accepted | Superseded by D-xxx
- Decision:
- Why:
- Alternatives considered:
- Consequences / revisit when:
```

---

### D-001: Personal project, possibly open-source

- Date: 2026-09-29
- Status: Accepted
- Decision: Build for personal use first; release as open source later if it works well.
- Why: Solves a real personal need; open-sourcing later keeps options open.
- Consequences: Keep licenses clean from the start (see `08-Licenses.md`).

### D-002: Android first, iOS later

- Date: 2026-09-29
- Status: Accepted
- Decision: Target Android now; iOS is not part of v1.
- Why: Main device is Android, and Android's background audio model is easier to work with.

### D-003: Target device is the Samsung Galaxy Tab E (SM-T560NU), Android 7.1.1

- Date: 2026-09-29
- Status: Accepted
- Decision: `minSdk 24`; design for about 1.5 GB RAM (to be verified on the unit).
- Why: It's the device the user actually has.
- Consequences: Limits library versions and APIs; later Android versions are a v3 goal.

### D-004: English only for v1

- Date: 2026-09-29
- Status: Accepted

### D-005: All synthesis happens on a PC or server in v1

- Date: 2026-09-29
- Status: Accepted
- Decision: The tablet does not run TTS in v1. A PC renders the audiobook; the app plays it.
- Why: The Tab E cannot handle neural TTS in real time.
- Alternatives considered: on-device Piper/Kokoro (deferred to v2), streaming from a server.
- Revisit when: the v2 benchmark spike measures real-time factors on devices.

### D-006: The app stores the original EPUB/PDF plus the audiobook; three modes

- Date: 2026-09-29
- Status: Accepted
- Decision: Read only, listen only, and read + listen with a synced highlight.
- Why: Lets the user switch freely between reading and listening.

### D-007: Use the SLC lifecycle for v1

- Date: 2026-09-29
- Status: Accepted
- Decision: Aim for a Simple, Lovable, Complete first release, built in five slices.
- Consequences: Each slice ends with something runnable on the tablet; see `04-Roadmap.md`.

### D-008: Roadmap for later versions

- Date: 2026-09-29
- Status: Accepted
- Decision: v2 embeds the EPUB/PDF in the app so the device runs its own TTS (Kokoro or Piper); v3 supports later Android versions.

### D-009: Audio format: mono MP3, 24 kHz, 64 kbps CBR, one file per chapter

- Date: 2026-09-29
- Status: Proposed (MP3 chosen by user; the exact parameters are suggested)
- Why: Universal playback; CBR keeps seeking and timestamps accurate; about 29 MB per hour.
- Alternatives considered: Opus (smaller), AAC/M4A.
- Revisit when: sync tests show drift, or storage becomes a problem.

### D-010: Sync map generated at synthesis time (sentence-level)

- Date: 2026-09-29
- Status: Proposed
- Why: Scribe knows each sentence's exact duration, so no forced alignment is needed.
- Consequences: Timings come from one continuous buffer per chapter (see spec section 6).

### D-011: v1 reader displays Scribe's cleaned text; original file is stored but not rendered

- Date: 2026-09-29
- Status: Proposed
- Why: Exact sync and low memory use on the Tab E.
- Revisit when: real EPUB rendering (v1.1 backlog) is worth the cost.

### D-012: PDFs use page-level sync in v1

- Date: 2026-09-29
- Status: Proposed
- Why: Word-level highlighting in fixed-layout PDFs is much harder; preferring PDF-to-text on the PC where possible.

### D-013: Speaker attribution is rule-based in v1; LLM is optional later

- Date: 2026-09-29
- Status: Proposed
- Why: Keeps v1 simple; low-confidence lines are reviewed via `cast_report.md` and fixed in `cast.yaml`.

### D-014: Storage access behind a `BundleStorage` interface

- Date: 2026-09-29
- Status: Proposed
- Why: v1 uses file paths and the legacy storage permission (works on Android 7); v3 can swap in Storage Access Framework without rewriting the app.

### D-015: Licenses: Player Apache-2.0 (or MIT), Scribe GPL-3.0

- Date: 2026-09-29
- Status: Proposed
- Why: Piper and espeak-ng are GPL, so the PC tool that wraps them is GPL; the tablet app ships no GPL code in v1.
- Revisit when: v2 on-device TTS, or if Scribe is hosted as a service (AGPL considerations); see `08-Licenses.md`.

### D-016: No network permission in v1

- Date: 2026-09-29
- Status: Proposed
- Why: Fully offline; simpler privacy story.

### D-017: Project documentation set

- Date: 2026-09-29
- Status: Accepted
- Decision: Keep `01-PRD` to `08-Licenses`, `README`, and this log as the project docs.

### D-018: Project name is Readio (superseded by D-019)

- Date: 2026-09-29
- Status: Superseded by D-019
- Decision: The project is called Readio; the PC tool is Readio Scribe and the Android app is Readio Player.
- Why: Chosen by the user.
- Consequences / revisit when: before public release, search GitHub, F-Droid, the Play Store and trademark databases for conflicts.

### D-019: Project name is Auloud

- Date: 2026-09-29
- Status: Accepted
- Decision: The project is called Auloud (audio + aloud); the PC tool is Auloud Scribe and the Android app is Auloud Player.
- Why: Chosen by the user after weighing other names.
- Consequences / revisit when: before public release, search GitHub, F-Droid, the Play Store and trademark databases for conflicts, and check that "Auloud" is the spelling you want.

### D-020: Scribe is a CLI first; keep logic in a library; GUI later

- Date: 2026-09-29
- Status: Accepted
- Decision: Auloud Scribe ships as a CLI on Windows 11 for v1. Core logic lives in the `scribe/` Python library and the CLI is a thin wrapper, so a GUI can be added on top later. Add `scribe doctor` (checks Python, ffmpeg, espeak-ng, models, GPU) and `scribe voices --sample` (plays a test line per voice). A local web UI for reviewing speakers and voices (`scribe ui`) is a v1.1 backlog item. A packaged desktop app is only worth it if non-technical people will use Scribe.
- Why: The user is the only user for now; risk is in text, timing and audio, not the interface; the review step is the only place a GUI adds real value.
- Alternatives considered: a full desktop app (PySide6 or Tauri) now; a web UI in v1.
- Consequences: Use `uv` or `pipx` for install on Windows; print the real-time factor on build; the sentence cache keeps long builds resumable. Revisit when the project is released to non-technical users.

### D-021: Watch folders via SAF picker, internal storage default

- Date: 2026-09-29
- Status: Accepted
- Decision: The Player watches user-chosen folders (system folder picker, SAF persistable grants) instead of one fixed folder; the auto-created default is shared internal `/Auloud` (added `WRITE_EXTERNAL_STORAGE`); no SD preference since the Tab E has none. SAF covers show the placeholder (follow-up: `coverUri` seam).
- Why: Zero-setup default plus reading books where they already live; requested by the user after the SD-based design failed on their unit.
- Consequences: Pulled part of the v3 SAF plan forward; the `BundleStorage` abstraction absorbed it. Test on device: picker UI, grant survival across reboot, permission prompts.

### D-022: Slice 1 device-verified and tagged

- Date: 2026-09-29
- Status: Accepted
- Decision: Slice 1 "done when" verified on the Tab E (30-min and 2-h screen-off, resume after restart/reboot, lock-screen controls, all C1–C12 pass, nothing odd); tagged `slice-1`. Battery prompt leads to app > optimize battery usage on Samsung 7.1.1.
- Why: Biggest slice risk (Samsung service kills) retired by direct test.

### D-023: TTS engine is Kokoro-82M via kokoro-onnx, narrator voice af_heart

- Date: 2026-10-01
- Status: Accepted
- Decision: Scribe synthesizes with the Kokoro-82M model through the `kokoro-onnx` runtime (MIT; model Apache-2.0), American English, narrator voice `af_heart`. No torch/transformers stack on Windows.
- Why: SW0 spike — the official `kokoro` PyPI package (0.9.4) is uninstallable on modern Python (ancient `tokenizers 0.10.3` pin, no Windows wheels, Rust build required); `kokoro-onnx` installed cleanly. Measured RTF 1.38 on CPU (23.3 s audio / 16.8 s wall, model load excluded), so a 10-hour book takes ~7 h CPU-only. Voice quality judged good enough by listening test; Piper side skipped.
- Alternatives considered: Piper (not run — Kokoro satisfied quality and feasibility bars); torch-based kokoro (blocked on install).
- Consequences: SW6 engine wrapper targets the ONNX API; espeak-ng 1.52.0 required (installed). Revisit voice choice in Slice 4 (multi-voice).

### D-024: EPUB parsing via ebooklib; Scribe licensed AGPL-3.0

- Date: 2026-10-01
- Status: Accepted
- Decision: Scribe parses EPUBs with `ebooklib` (plus `beautifulsoup4` + `lxml` for the HTML walk) and Scribe itself is licensed AGPL-3.0 (LICENSE replaced with AGPL-3.0 text, headers and `pyproject.toml` switched from GPL to AGPL).
- Why: User decision for SW3. Installed METADATA confirms `ebooklib 0.20` is GNU Affero General Public License (classifier AGPLv3+), which requires the combined work to be AGPL-3.0; `beautifulsoup4 4.15.0` is MIT and `lxml 6.1.3` is BSD-3-Clause, both compatible. Relicensing now settles the SW3 decision point in `docs/plans/Slice2.md`.
- Alternatives considered: small own parser using `zipfile` + OPF to avoid the AGPL question (rejected by user decision).
- Consequences / revisit when: Partially supersedes D-015/D-020 for Scribe only (Player stays Apache-2.0/MIT, ships no GPL/AGPL code). Hosting Scribe as a network service triggers AGPL section 13: offer the Corresponding Source to remote users. No runtime network code added; `beautifulsoup4`/`lxml` licenses recorded from METADATA.

### D-025: Assembly and loudness choices (SW7)

- Date: 2026-10-01
- Status: Accepted
- Decision: One continuous ffmpeg encode per chapter from sample-count timings (end_ms excludes trailing pause); pauses 250/500/800/1000 ms per design doc §9 (quote blocks take para pauses); loudness is deterministic numpy gain to -1 dBFS peak (design's loudnorm -16 LUFS was illustrative); leading-break silence dropped so spec §6 rule 1 (first start 0) holds; book id is UUIDv5 over source sha256 (SW5).
- Why: Keeps timings, buffer, and MP3 mutually consistent; measured ffprobe-vs-sample deltas 0 ms against the 50 ms rule.
- Consequences / revisit when: Slice 4 should revisit perceptual leveling for multiple voices.

### D-026: Slice 2 verified and tagged

- Date: 2026-10-01
- Status: Accepted
- Decision: Slice 2 done-when met — C&P sample converts without errors, passes `scribe validate`, imports into the Slice 1 Player and plays on the Tab E (user-confirmed, listen-only); golden-bundle contract passes both suites (215 scribe + full Player suite green); tagged `slice-2`. Found in SW11: `scribe validate` was never wired as a CLI command (added + tested) and `build.py` was missing from the installed wheel (force-include fixed).
- Why: Closes the slice with the Player actually playing Scribe output on hardware.

### D-027: Reader technology is Compose (RA0 spike)

- Date: 2026-10-01
- Status: Accepted
- Decision: The Slice 3 reader renders with Compose (`LazyColumn`, one `Text` + `AnnotatedString` per block, `derivedStateOf` highlight) — no RecyclerView fallback. The RA0 throwaway spike (5,000 synthetic sentences, 400 ms fake highlight, auto-scroll, PSS readout, debug-only Settings entry) felt smooth on the Tab E in the user's test.
- Why: The plan's gate question answered directly on hardware; the narrow-recomposition pattern (only flipped blocks recompose) held up on the slow device.
- Consequences: RA4 builds the real reader on this pattern; if later profiling shows a lag source, revisit with overlay numbers before considering Views.

### D-028: Play on a finished book restarts from chapter 1

- Date: 2026-10-01
- Status: Accepted
- Decision: A finished book (last chapter at full duration) reopens staying at the end, paused — but pressing Play restarts it from chapter 1 (`PlaybackController.playOrRestart`, `isFinishedBook` in `PlayerStateLogic`) instead of resuming the end or asking. Applies on every screen with a Play button (Listen player, reader mode bar).
- Why: No dialog, no extra state; the saved finished spot is only a resume anchor, and restarting is the least surprising Play behavior. Settles the deferred Slice 1 question.
- Consequences: A supporting change makes seeks persist immediately in the service (`onPositionDiscontinuity` SE reason), so Read-mode settle seeks and tap jumps survive even while paused.

### D-029: Sleep timer commands via intents, remaining via static snapshot

- Date: 2026-10-01
- Status: Accepted
- Decision: The RA8 sleep timer is set/cancelled with `startService()` intent extras (`EXTRA_SLEEP_OPTION`), the 1 s countdown/fade/pause/save lives in `PlaybackService`, and the remaining time reaches the UI through a volatile static snapshot (`SleepTimerMonitor`, copied into `PlaybackState` on the controller's regular refresh) — not Media3 custom commands, though the current Media3 docs were checked and custom commands are the idiomatic controller-to-session path.
- Why: Same process, so no IPC is needed; the WP9 `DebugSaveTracker` precedent already publishes service state this way; intent extras deliver timer commands synchronously with no connection or future plumbing; polling remaining through async command round-trips would cost more for a 1 Hz countdown than copying one volatile long on the existing ticker.
- Consequences: Revisit only if playback ever moves out of process.

### D-030: Slice 3 verified and tagged

- Date: 2026-10-01
- Status: Accepted
- Decision: Slice 3 done-when met on the Tab E (C13-C21 pass, qualitative; exact overlay numbers not recorded): beep sync under ~300 ms at start/middle/end and two speeds, lossless mode switches, stable hour-long session; tagged `slice-3`. Two items the plan required logged: the mode is a persisted global setting shared by every book (position always comes from playback, never the mode), and sentence spacing concatenates Scribe text as stored with one inserted space only for trimmed hand-made sources (checked against the golden bundle). Found in device testing: Listen was a navigation dead end (no way back to Read modes) — fixed with a mode-switcher slot on the Listen screen and retested.
- Why: Closes the slice with read-along actually working on hardware.

### D-031: MV0 voice audition and spaCy packaging

- Date: 2026-10-02
- Status: Accepted
- Decision: `scribe voices` lists Kokoro voices; `scribe voices --sample` renders one fixed self-made sentence in every voice to `logs/voice-samples/` (zero-padded index `<ii>-<voice>.wav` + `voices.txt`, PCM-16 WAV, never committed). Library code in `tts/voices.py` (works with any engine exposing `voices`; `KokoroEngine.voices` added) and `text/nlp.py` (model name + load path for MV3); `scribe doctor` gains a spacy check (present + versions + a real parse). spaCy added via `uv add spacy` (3.8.16, MIT); the model is URL-pinned (`en-core-web-sm 3.8.0` wheel from the spacy-models GitHub release, MIT) because it is not on PyPI.
- Why: The palette shortlist is a listening task, so samples must be browsable without code changes. URL-pinning beats `spacy download` because a plain download is wiped by the next `uv sync` (observed: reinstalling `auloud-scribe` uninstalled the model); the lockfile now survives reinstalls.
- Alternatives considered: `spacy download` only (rejected — not reproducible across syncs); MP3 samples (rejected — WAV/PCM-16 plays everywhere with no encode step); a `--voice` subset flag (skipped — MV0 needs the full set; the library takes an optional subset for the slow smoke test).
- Consequences / revisit when: `logs/voice-samples/` (every voice, approx 54 in the v1.0 file) is local-only; the user listens and shortlists the palette for MV1. No new force-include lines needed (new modules sit in the already-included `tts/` and `text/` dirs).

### D-032: MV1 gold set is a 5-line Crime and Punishment fragment

- Date: 2026-10-02
- Status: Accepted
- Decision: Use a hand-labelled 5-line Crime and Punishment Part I Chapter I fragment as the MV1 speaker gold seed under `spec/fixtures/speakers-gold/`, scored by `scribe/dev/eval_speakers.py`.
- Why: User choice over the plan's Alice/Holmes suggestion; the fragment was at hand and enough to prove the harness runs end to end.
- Alternatives considered: Alice's Adventures in Wonderland or a Sherlock Holmes story with 100-150 lines as the Slice 4 plan suggested (deferred until a full-novel EPUB is available).
- Consequences / revisit when: The n=5 set is a format seed, not a tuning set; expand to a 100-150 line full-novel set from Project Gutenberg before MV4 tuning. MV2+ rule work reuses the same directory and harness unchanged.

### D-033: MV2 dialogue detection and quote keys

- Date: 2026-10-02
- Status: Accepted
- Decision: `scribe/text/dialogue.py` detects quoted speech with a per-paragraph state machine (straight + curly doubles; singles only when the paragraph has no doubles; apostrophes with word chars on both sides never count; nested singles belong to the outer quote; multi-paragraph opens continue with a `continued` flag) and re-splits each segment with the existing `text/sentences.py` splitter (reused, never forked). Mixed sentences split at quote boundaries into `narration`/`dialogue` records keyed `(chapter, block, quote)` in open-quote order; the eval layer adds `book` (gold alias `source`) making `(book, chapter, block, quote)`. Scare-quote rule: a closed fragment with no terminal punctuation, no trailing comma, and at most 2 words is narration emphasis, not dialogue (gold block 12 contributes zero quotes). Unbalanced/stray quotes log via `logging` and fall back to narration; intact pairs are still honored when a stray inch-mark shares the paragraph. A chain still open at chapter end is logged and flipped to narration. Predictors output CANONICAL speaker names; matching stays exact (strip + casefold) with no alias matching until MV5.
- Why: Pins the 5-line gold numbering exactly (blocks 8/10/11 keys reproduced, block 12 zero) while keeping the Player spacing guarantee (`"".join` == paragraph exactly).
- Alternatives considered: numbering scare fragments (rejected: block 12 must contribute zero quotes); writing kind/quote metadata into the bundle now (rejected: bundle unchanged in MV2, metadata lives in `TaggedSentence`/`QuoteInfo` until MV6 wires `script.json`).
- Consequences / revisit when: MV3/MV4 build attribution on `QuoteInfo`/`QuoteContext.block_text` neighbours. Note: block 11 quote 2 spans sids 51-61 here (11 sids, one entry) vs the plan's 46-56 estimate; same shape, offset differs because sentence splits upstream shifted absolute sids, which is exactly why the gold anchors on block/quote order, never sid.

### D-034: QuoteContext lives in text/speakers.py; title words feed TITLE_RE

- Date: 2026-10-02
- Status: Accepted
- Decision: `QuoteContext` moved from `scribe/dev/eval_speakers.py` into the `scribe/text/speakers.py` library (new `continued: bool = False` field from the MV2 flag; all other fields and defaults unchanged). `dev/eval_speakers.py` re-exports the same class object, so old imports keep working. `_TITLE_WORDS` now builds `TITLE_RE` (single source; the tuple sat unused through MV3), and the two-word titled-name cap is documented and tested (`"Mr. John Jacob Smith"` matches `"Mr. John Jacob"`; the full surface still arrives via the verb-subject path).
- Why: the library imported `dev` at runtime inside `build_quote_contexts`, but the installed wheel ships no `dev/` (force-include covers library dirs only), so the import broke packaging. Of the two candidate homes, `speakers.py` won over `dialogue.py`: `candidates_for` consumes the context (predictor-slot types stay together) and `dialogue.py` stays detection-only with no new runtime dependency.
- Alternatives considered: home in `dialogue.py` (rejected: would add a speakers-to-dialogue runtime import and mix eval concerns into detection); duplicating the class in both places (rejected: drift risk).
- Consequences / revisit when: `dev` scripts bootstrap `sys.path` to import `text.*` when run directly; MV4 `pronoun_gender`/`is_pronoun` helpers added alongside for the tag rules.

### D-035: MV4 attribution rules, positional choice, and alias-aware eval

- Date: 2026-10-02
- Status: Accepted
- Decision: `scribe/text/attribution.py` applies the plan rules in exact order (explicit high, pronoun medium, alternation medium, continuation, fallback/unknown low) over `QuoteContext` entries in reading order. Positional choice: a focused block re-parse per block with the MV2 splitter (cached; sentence logic reused, never forked) instead of extending Candidate with spans, which would still need the quote spans living in dialogue's private regions. Only adjacent narration sentences are parsed for tags, so inner-quote verb subjects and `person-entity` listeners can never win a tag. Continuation inherits the previous speaker AND confidence (the uncertainty lives in the chain head, not the link). Alternation needs a dialogue-only block, two distinct known tail speakers, same book/chapter, and an established run (previous block dialogue-only or previous rule explicit/pronoun); an action beat with a bare quote falls to fallback instead. Eval: optional per-entry `surface` field plus `alias_aware` mode (strict stays default); 3-tuple predictors feed a by-rule table; the n=5 numbers are wiring validation only (0/5, recorded in `test-log.md`), with the 80%/90% targets explicitly not promised until the full-novel gold set lands.
- Why: keeps MV3 emission untouched (discovery intact), makes every tie-break deterministic and tested, and keeps strict canonical scoring intact while MV4 predictors emit surfaces.
- Alternatives considered: Candidate span extension (rejected: needs private region access, bigger shape churn); continuation fixed at high (rejected: would overstate low-confidence heads); global alias map (rejected: pronoun surfaces resolve per entry, not globally).
- Consequences / revisit when: first-person tags (`"I said"`) fall through to MV5 `first_person`; quoteless narration between bare quotes does not break alternation runs in v1; MV5 resolution replaces alias-aware scoring with canonical output.

### D-036: Voice palette and v2 diversity note

- Date: 2026-10-02
- Status: Accepted
- Decision: MV5/MV7 assign voices from the user's MV0 listening shortlist: narrator `am_onyx`; characters `bf_isabella`, `bm_lewis`, `im_nicola`, `jf_alpha`, `zf_xiaoxiao`, `am_eric`; generic female `af_bella`; generic male `am_adam`. Exact Kokoro voice names recorded in `test-log.md`.
- Why: User judged all of them good and mutually distinct enough for v1.
- Alternatives considered: none (user's ears decide).
- Consequences / revisit when: v2/v3 should add more diverse voices (user request) — a broader palette and longer listening pass belong to the v2 backlog, not this slice. Also: full-novel tuning deferred by user decision; gold expansion uses the local 2-chapter `CRIME AND PUNISHMENT.epub` instead (D-032's Gutenberg plan superseded for v1).

### D-037: MV7 review fixes use single-sentence gender hint and peak leveling

- Date: 2026-10-02
- Status: Accepted
- Decision: Build re-derives the gender hint at render time from the single sentence text only (`gender_hint_for(raw, sentence.text)`), while draft votes from paragraph context (prev plus block plus next). Per-voice leveling equalizes peaks to -1 dBFS (one gain per voice, then the chapter peak cap), not RMS or LUFS.
- Why: Threading the draft hint through `script.json` would reshape the work file and force every book to re-draft for no audible difference in the common case, and running the spaCy tagger at build would pull the model into packaging (the MV4 lesson). Peak gain is deterministic in numpy, keeps durations unchanged, and lives in assembly so cached sentence audio stays valid; loudnorm-style LUFS would add a version-dependent filter pass.
- Alternatives considered: threading the full-context hint through `script.json` (rejected: schema churn and forced re-draft); spaCy at build time (rejected: packaging cost); ffmpeg loudnorm to -16 LUFS (rejected: noted as example only in the design doc, version-dependent); RMS leveling (rejected: peak matching is simpler and already comparable by ear for v1).
- Consequences / revisit when: Accepted v1 risk that build and draft hints can disagree on edge cases and pick different generics; the resolved voice is fingerprinted, so any flip re-renders that chapter. Revisit with LUFS or RMS leveling and richer build context only if listening tests report uneven loudness or systematic generic-voice mistakes.

### D-038: Slice 4 verified and tagged

- Date: 2026-10-02
- Status: Accepted
- Decision: Slice 4 done-when met: C&P 2-chapter and Alice fragment sound clearly multi-voice by user listening test; the override round-trip renders exactly the edited line with `build` only (proven both directions on Alice, 106/1 then 107/0); tagged `slice-4`. Remaining plan section 4 decisions recorded: two-layer speaker data (raw attribution in `script.json`, resolution at `build`, so `cast.yaml` edits never re-draft); overrides anchor on `(chapter, block, quote)` or text match, never `sid`; `draft` merges `cast.yaml` without clobbering user entries; speed offsets only, no pitch shifting (pitch accepted and ignored); per-voice peak leveling at assembly, never in the cache; `first_person` maps "I said" to narrator by default or a named character. Found in acceptance: override `speaker: narrator` fell through to `default_female` — fixed so the narrator is matchable as an override target (case-insensitive exact).
- Why: Closes the slice with multi-voice books actually built, heard, and corrected on hardware.

### D-039: Slice 5 triage (CP0 carry-forwards)

- Date: 2026-10-02
- Status: Accepted
- Decision: Fix now in this slice: full-novel gold set (CP1); speaker remap made PDF-safe (CP6, never reuse the EPUB-only one as-is); notice auto-dismiss + SAF cover resolution (CP4, which owns that code anyway); preview/debug entries stripped from release (CP8, explicit checklist item); packaging convergence attempted in CP9 only if zero-friction, back out otherwise. Deferred to the v1.1 backlog: cache size cap (PC disk is cheap, 10-hour build cache is small), progress-bar ETA coverage, quoteless-narration alternation refinement (current rule is a documented default, cast_report review covers mistakes), thought-vs-speech voicing (still undecided, hook reserved). Crowded-block overconfidence goes into the CP1 tuning time box; whatever remains is documented as a README limitation. Docs updated to AGPL-3.0-or-later in CP0 (01-PRD, 05-ScribeDesign, 08-Licenses, README, plus the repo AGENTS.md which said GPL-3.0); Slice 4 roadmap already fully ticked.
- Why: Triage keeps Slice 5 on the release path; nothing deferred can block the soak test.

### D-040: CP1 gold convention and no-change tuning

- Date: 2026-10-02
- Status: Accepted
- Decision: The 107-line C&P gold keeps the user's generic labels on the 8 unattributable lines (interior thought, unattributed shouts render as `default_female`/`default_male`) instead of the old 5-line seed's true-speaker labels (Raskolnikov, drunken man); the old seed is deleted as superseded. Tuning changed no rules: all 8 strict misses are unknown-speaker convention lines with zero wrong-person errors, so any rule edit would overfit. Headline accuracy is the strict 92.5%, not the alias-aware 100% (which matches machine-written surfaces).
- Why: Gold records what the pipeline should render; the eval then measures attribution of assignable lines, and the fallback convention is tested separately by the pipeline's own resolution tests.

### D-041: CP4 runtime resilience (skip notices, storage loss, consume rule)

- Date: 2026-10-04
- Status: Accepted
- Decision: Corrupt/missing chapters skip with a transient notice (`SkipNoticeMonitor`, same-process singleton copying the `SleepTimerMonitor` pattern; service publishes before seeking away, controller copies via `consume()` into `PlaybackState.skipNotice`). Storage loss pauses with "Storage unavailable - playback paused" through the same channel; the next user Play retries naturally. Consume rule: null snapshots mean "no new notice" (the holder retains the displayed one); the UI dismisses on tap or after ~6 s via `clearSkipNotice()`, which is what stops rotation resurrecting it. Error-code choice: only `ERROR_CODE_IO_FILE_NOT_FOUND` and `ERROR_CODE_IO_NO_PERMISSION` are trusted as missing-file signals (local file/document-URI audio has no HTTP codes and the ejected-microSD mapping varies by device); everything else rides a consecutive-failure heuristic (2 in a row with a missing-file signal, or any 3 in a row, means storage loss; `STATE_READY` and storage handling reset the count, bounding the worst case to 3 skips so the playlist never loops). Missing chapter text stays a reader-only message (RA10: audio continues, confirmed by code read, no change). No new dependency, permission, or bundle-format change.
- Why: A single error code is never trusted alone for eject detection, but unbounded skipping would spin through a whole novel on an ejected card; the threshold pauses early while still tolerating one isolated bad chapter.
- Alternatives considered: trusting any `ERROR_CODE_IO_*` as storage loss (rejected: decoder and position codes would false-positive on single bad chapters); event-with-consumed-flag instead of holder-retained state (rejected: thicker than the codebase's snapshot/holder pattern).
- Consequences / revisit when: device test must confirm eject-and-reinsert pause/resume and the skip message on a real corrupt chapter; revisit the threshold only if the tablet surfaces eject as a different code.

### D-042: CP5 PDF extraction (PyMuPDF text layer, EPUB-parity cleaning)
- Date: 2026-10-04
- Status: Accepted
- Decision: Scribe extracts PDFs with PyMuPDF 1.28.2 (`pymupdf>=1.28.2` pinned in `scribe/pyproject.toml` + `uv.lock`; `scribe doctor` gains a `pymupdf` row proving import + in-memory open). Per-page `get_text("blocks")` with image blocks dropped; two-column pages (a column gap no block crosses, y-overlap over half the extent) read left-first; hyphen splits rejoin only on lowercase continuations; headers/footers are first/last lines repeating on 3+ pages in one y-band; page numbers are lone digits in the top/bottom margin (stripped before header/footer detection so footers sharing a page with a number still match); footnotes are short digit-marker blocks at the page bottom; ALL-CAPS short lines and outline titles become headings. Each PyMuPDF block is one paragraph (blocks split where the layout has large gaps, which is the observable form of the blank/indent/gap rule; per-line coordinates are not in `blocks` output, so indent-only breaks merged into one block stay joined). Chapters come from the outline top level (document order, leading pages join chapter 1), else chapter-like/all-caps heading splits, else fixed 20-page ranges. Chapter text flows through the same `_clean_document`/tiny-merge/`_to_chapter_file` as EPUB (one `SpineDocument` per chapter); `RawChapter` gains `source_pages` (sorted, unioned by the merge) for CP6 sentence-page provenance. Over half scanned pages fails as `DraftError` with "no text layer (scanned PDF?)"; no OCR. The draft report gains a PDF quality section (pages, dropped counts, short-line ratio, messy warning); thresholds are module constants in `extract/pdf.py`. Minimal writer support now: PDF sources copy to `source/book.pdf` with manifest `type` `"pdf"` (chapters stay `blocks`-form, so the validator passes unchanged); the full 1.1 spec amendment (`pages` marks, sentence `page`, fixtures) stays CP6 work, which also updates `docs/03-BundleSpec.md`.
- Why: User-approved PyMuPDF (AGPL inside AGPL Scribe, never in the Player) satisfies the CP5 verify (synthetic clean/header/two-column/scanned PDFs plus determinism) while reusing the whole EPUB pipeline downstream unchanged.
- Alternatives considered: `pypdf`/`pdfminer.six` (rejected by user approval of PyMuPDF); word-level (`dict`) output for indent-accurate paras (rejected: the plan mandates `blocks`; documented as a v1 limitation instead); full spec 1.1 amendment now (rejected: CP6 owns the spec, validator and fixtures; CP5 writes only what end-to-end build needs).
- Consequences / revisit when: novels with indent-only paragraph breaks may join paras (watch the short-line/messy signals and CP1-style tuning in CP6/CP11); row-merged two-column blocks keep stream order; revisit thresholds only with real-PDF evidence.

### D-043: CP6 spec v1.1 additive PDF pages (Slice 5 decision 2 realized)
- Date: 2026-10-04
- Status: Accepted
- Decision: Bump the bundle contract to v1.1 (`spec/bundle.md` + `docs/03-BundleSpec.md` kept byte-identical): a PDF text-path chapter keeps `blocks` and MAY add `pages: [{page, start_ms}]` plus an optional `page` on each sentence; pure `pages`-without-`blocks` stays valid legacy. Sentence `page` is 1-based source page, omitted (absent, never null) for EPUB. `pages[].start_ms` is the `start_ms` of the first sentence on that page. Readers accept `spec_version` "1.0" and "1.1"; new Scribe bundles write "1.1". Scribe: extraction carries per-para page (`ParsedBlock.page`) through cleaning into `Sentence.page`; dialogue split + attribution + assembly + `_with_timings` preserve it; the writer computes `pages` from final sentence timings (first sentence per page) and the validator enforces sorted/pages-match-first-sentence/page-in-range plus sentence-page coverage. Speaker remap is PDF-safe via a dedicated variant: block/sid addressing is identical (1..N deterministic), but the EPUB function drops `page`/`pages` (it has none) while `remap_pdf_chapter_speakers` requires every sentence to carry a 1-based `page` and carries `ChapterFile.pages` through (writer recomputes authoritative marks from timings). Attribution/cast/multivoice logic is otherwise unchanged. Player: `blocks`+`pages` parses as text (Text view, `page`/`pages` ignored for layout); only pure `pages`-without-`blocks` stays `ChapterTextPdfForm` (listen-only). Malformed `pages` (truncated/wrong types) is `ChapterTextInvalid`, never a crash.
- Why: Realizes the Slice 5 plan decision 2 as an additive change: old v1.0 Players (`ignoreUnknownKeys`) read v1.1 text bundles, and v1.0 bundles still validate.
- Alternatives considered: `page: null` for EPUB (rejected: spec says absent, never null; readers default missing to null anyway); writer trusting precomputed `pages` from upstream (rejected: timings are final only at write time, so the writer recomputes); reusing the EPUB remap as-is for PDF (rejected per D-039 triage: it drops page provenance).
- Consequences / revisit when: CP7 Page view reads `pages` for page lookup/seek; revisit page-sync rules only with real-PDF evidence (indent-joined paras share one page, which is correct provenance).

### D-044: CP7 Text view rides the shared EPUB path; page range display ready, population deferred
- Date: 2026-10-04
- Status: Accepted
- Decision: Blocks-form PDF chapters (`blocks` + optional `pages`/`page`) read end-to-end through the unchanged EPUB reader path (textPaths, SentenceIndex, tap mapping, follow, durations are all page-agnostic), so CP7 adds no playback/reader logic. The pure-pages PdfForm message is reworded from "Reading arrives later" to "Page-only chapter - listening still works" (loader, ReaderScreen, ReaderState KDoc), since the Page view may never come. Chapter list gains an optional `pageRange` (`formatPageRange` + `formatChapterSubtitle` + `withPageRange`, shown next to the duration only when present); `toChapterEntries` still leaves it null, so EPUB rows render byte-identically. Populating per-chapter ranges would mean loading every chapter JSON at list time, which breaks the one-chapter-at-a-time memory rule, so enrichment waits for a pages-light read or the Page-view decision. No `PdfRenderer`, no toggle, no new dependency/permission/bundle change.
- Why: The audit found no EPUB-assumption breakage (verified by the new pdf-golden reader tests); the only truthful change was messaging plus the display-ready page range.
- Alternatives considered: loading all chapter JSONs in BookScreen to fill ranges now (rejected: O(N) IO at list open, holds against the memory rule with no reader benefit yet); a Text/Page toggle now (rejected: explicitly gated, plan says cut it first).
- Consequences / revisit when: gate question for the user stays pending real-PDF experience on the Tab E (read + listen, memory stability); revisit range population only if the Page view is approved.

### D-045: Page-view gate closed

- Date: 2026-10-04
- Status: Accepted
- Decision: The user judged clean-text reading sufficient for their PDFs, so the CP7 Page view (`PdfRenderer`, Text/Page toggle) is not built; the "Page-only chapter" message and the unpopulated `pageRange` stay as they are. If a future PDF with figures/tables/layout that clean text loses arrives, reopen the gate then.
- Why: Per plan decision 3, the Page view exists only on demonstrated need; cutting it keeps Slice 5 on the release path (SLC: simple and complete beats extra features).

### D-046: Page view deferred to v1.1 as a reader option

- Date: 2026-10-04
- Status: Accepted
- Decision: The CP7 Page view is not dropped but deferred: v1.1 backlog gains "PDF Page view (`PdfRenderer`, one page at a time) as a Text/Page reader option". When built, it reuses the spec 1.1 `pages` marks (already emitted) and the display-ready `pageRange` plumbing (CP7). Text view remains the default.
- Why: The user's PDFs read fine as clean text today, but rendered pages stay a legitimate option for figure/table-heavy PDFs later; parking it in the backlog keeps the decision visible instead of silent.

### D-047: CP8 release build and packaging

- Date: 2026-10-04
- Status: Accepted
- Decision: Release `minifyEnabled` + `shrinkResources` with keep rules for kotlinx.serialization (bundle models), Room (data package), Media3 session/exoplayer/common, Guava futures and Coil; `versionName 1.0.0`, `versionCode 1`. Media3/Coil keeps are conservative (offline: the 1.5.1 AARs ship only dontwarns, Coil none; Room ships its own RoomDatabase keep, verified in the cached AARs). Signing reads `auloud.keystore.*` from gitignored `local.properties` and falls back to debug signing with a loud warning, so CI/tests never break. Hand-made vector launcher icon (book + sound waves, own drawing). Spike entry + screen gated behind `BuildConfig.DEBUG` via `isReaderPreviewAvailable` (unit-tested); the only other DEBUG uses are the two overlays and the save-time snapshot, all absent in release. Licenses: Settings entry to a static list (versions match the catalog), `player/NOTICE` + `player/THIRD_PARTY_LICENSES.md`; Player section-2 boxes ticked from the pinned POM license blocks, Kokoro-82M Apache-2.0 from the model card, espeak-ng GPL-3.0-or-later from its repo, en_core_web_sm MIT from installed METADATA (D-031); per-voice palette table added. The user generates and guards the key (docs/ReleaseSigning.md); losing it means reinstall.
- Why: CP8 verify needs a minified release APK (minify bugs only show there) without ever committing a key.
- Alternatives considered: checking the key into the repo (rejected: never commit keys); deciding the Player Apache-2.0-vs-MIT license now (deferred, D-015 still Proposed; NOTICE says so).
 - Consequences / revisit when: merged release manifest and `aapt dump badging` both show no INTERNET (ACCESS_NETWORK_STATE comes from an AndroidX dependency, accepted). Needs device test: sideload on the Tab E (unknown sources), upgrade-install over debug, Slice 1-4 smoke on the release build, licenses screen legible, launcher icon renders.

### D-048: CP9 clean-install proof; packaging not converged, deferred
- Date: 2026-10-04
- Status: Accepted
- Decision: (1) Fresh-environment install verified end to end on Windows 11: clone plus `uv sync` plus `scribe doctor` (all PASS) plus `draft` plus `build` (real Kokoro synth) plus `validate` on a 1-page synthetic PDF. (2) Packaging convergence NOT done, deferred per the D-039 zero-friction gate: the flat tree maps into the `scribe` wheel namespace correctly today and there is nothing small to fix. (3) No `docs/08-Liscenses.md` checkbox changes: section 7 has no fresh-install row, and CP9 re-verified no license rows (pinning already proven by the lockfile install).
- Why: CP9 Verify is "a clean `uv` environment builds a short bundle end to end". The proof below meets it literally. Convergence would mean rewriting every flat import (`from bundle.models import`, ...) to `scribe.*` across ~30 files plus the pytest `pythonpath`, the `cli.py` `sys.path` shim and the `dev/` bootstrap scripts: not zero-friction, so D-039 says back out.
- Clean proof (clone dir `C:\Users\kenta\AppData\Local\Temp\opencode\auloud-clean`, models COPIED from the working `scribe/models/` in 0.1 s, so the model-download step itself is NOT clean-tested):
  - `git clone --branch dev/slice5 --single-branch <repo> auloud-clean`: 1.2 s. Clone contains no `.venv`, `models/`, `.scribe/` or `bundles/` (all gitignored, correctly not travelling).
  - `uv sync` in `auloud-clean/scribe`: 8.5 s wall (CPython 3.14.5; 73 packages resolved, 72 installed; uv-cache reuse, only `auloud-scribe` itself built). URL-pinned `en-core-web-sm 3.8.0` and `pymupdf 1.28.2` install from the lockfile with no manual step.
  - `uv run scribe doctor --models-dir models`: all 9 checks PASS in 29.7 s (python 3.14.5, ffmpeg/ffprobe 9.0.1, espeak-ng 1.52.0, kokoro-onnx 0.6.1, models present, spacy 3.8.16 + en_core_web_sm 3.8.0 parse ok, pymupdf 1.28.2 open ok, RTX 4050 GPU).
  - Synthetic 1-page PDF via `pymupdf` (heading plus 4 short sentences with one quoted line, made with `insert_text`): `draft` 9.1 s (1 chapter, 6 sentences, 22 words, 0 drops); `build --no-progress` 15.6 s CLI wall (9.0 s in-process, RTF 1.28x, 6/6 sentences real Kokoro synth, 0 cached, 11 465 ms audio); `validate` PASS 3.9 s.
  - Quirks (all cosmetic, third-party, no action): pysbd 0.3.4 prints `SyntaxWarning: invalid escape sequence` lines on Python 3.14; `import fitz` in the throwaway PDF script prints a deprecation warning (Scribe itself imports `pymupdf`); uv progress on PowerShell 5.1 surfaces as `NativeCommandError` noise.
- Packaging evidence: `uv build` from current source ships all 27 library modules under `scribe/` including `scribe/extract/pdf.py` (CP5), `scribe/text/nlp.py` and `scribe/tts/voices.py`; `import scribe.extract.pdf` resolves against the installed wheel. Zero `from scribe.` imports exist in source (flat style everywhere); no force-include entry is missing, so THAT fix (the CP9 fallback) was not needed either.
- Alternatives considered: rewriting imports to `scribe.*` now (rejected: every-file churn, reinstall surprises, zero user-visible gain); reformatting 19 files flagged by `ruff format --check` (rejected: pre-existing drift, `ruff check` passes, drive-by reformat violates slice scope).
- Consequences / revisit when: working `scribe/.venv` is STALE (installed `cli.py` has 0 `pymupdf` mentions vs 16 in source; its `scribe doctor` lacks the pymupdf row, predating CP5). The agent did not touch it per CP9 constraints; the user may refresh at will with `uv sync --reinstall-package auloud-scribe` in `scribe/`. Working-tree suite stays green regardless (pytest runs source via `pythonpath`): 482 passed, 2 deselected (`-m "not slow"`, 36.3 s) plus `ruff check` clean. Revisit convergence only if the flat/namespace split causes a real bug. Exact README steps for CP10 are in the CP9 session report (clone, `uv sync`, copy models, `doctor`, `draft`, `build`, `validate` with the wall times above).

### D-049: CP10 docs pass (README + cleanup, docs-only)
- Date: 2026-10-04
- Status: Accepted
- Decision: (1) README rewritten as the v1 quick start + setup guide (requirements with espeak-ng 1.52.0, three modes, CP9 install steps with wall times, microSD path + SAF watch folders, full docs map); the author's copyright paragraph kept verbatim, untouched. (2) Stale language fixed in docs only: PDF Page view shown as a v1.1 option (D-046) with Text view + `pages` marks as v1, on-device TTS stays v2, Scribe license AGPL-3.0-or-later everywhere, 05 commands table + deps + loudness + pitch aligned to the real CLI (`--help` run for every command quoted), Player storage/import/screens aligned to watch folders + validation + licenses screen. No code, no bundle-format, no dependency change. (3) Roadmap Slice 5 ticks only JVM/unit-verified code boxes (PDF text path, chapter nav, error handling, import validation); battery, soak, README fresh-follow and all of test-plan sections 7/10 stay unticked for the user. 08 section 7 ticks only artifact-verified boxes (NOTICE + THIRD_PARTY, voice table, in-app screen present, README position). test-log gains brief CP3-CP9 automated entries with the session counts.
- Why: CP10 Verify is a fresh README follow zero to playing book, which only the user can do on hardware; the agent's part is accurate docs plus every quoted flag proven against the real CLI.
- Alternatives considered: ticking the README/soak boxes now (rejected: fresh-follow and multi-day listening are device work by definition).
- Consequences / revisit when: open contradictions logged, not fixed (docs-only WP): root `LICENSE` is MIT while 08 section 7 expects per-component Player Apache-2.0 (only `scribe/LICENSE` AGPL exists; `player/NOTICE` says Apache-2.0-or-MIT undecided); README License line still says "Player: Apache-2.0" matching the original wording while NOTICE says undecided. Revisit when the D-015 license choice is made.

### D-050: Slice 6 UI0 skipped, chapter parallelism scrapped

- Date: 2026-10-04
- Status: Accepted
- Decision: UI0 spikes skipped per user direction: build straight on the plan defaults (one detached `scribe build` process per job, sequential chapters, CPU default). D1 accepted on reasoning (Windows detach proven by UI4 tests instead of a prototype); D2 license check moves to UI2; D5 resolved without measurement. `--workers` / chapter-parallel rendering is removed from Slice 6 entirely: onnxruntime already saturates several cores per call, and the added complexity buys nothing at 4-25x CPU RTF.
- Why: The user's call; measurements would only have confirmed the defaults the plan already recommended.

### D-051: UI1 library groundwork (events, ranges, plan, stop, lock, device, spec 1.2)

- Date: 2026-10-04
- Status: Accepted
- Decision: (1) Progress is one dict listener `{event, chapter, sid, cached, rendered, audio_ms, wall_s, elapsed, estimate}`; the Rich bar and the JSONL writer share cumulative numbers. (2) `build --chapters 3-5,7`; draft always processes the whole book; `--pages` resolves via sentence page min/max to whole covering chapters; both together is `chapters-pages-exclusive`. (3) `plan_build()` dry run from fingerprints only (no synth); estimate from `build_stats.json` (last 20 chapter timings, None when no history). (4) Cooperative stop via `threading.Event` at sentence boundaries; partial `chNNN` files deleted so resume re-renders them via the MV7 cache. (5) Per-book `.scribe.lock` (O_EXCL, `{pid, started_at, cmd}`); stale means dead PID (Windows ctypes `OpenProcess`, POSIX `kill(pid, 0)`) or age over 24 h; same-PID re-entry is non-owned so build-into-draft nesting cannot deadlock. (6) `--device auto/cpu/cuda`, default auto (CUDA only if `onnxruntime.get_available_providers()` reports it, else CPU); CUDA wraps `Kokoro.from_session`, no packaging change. Found in review: the default CPU path returned a raw session object without the engine contract — fixed so both paths return `KokoroEngine`. (7) Errors shaped `{file, rule, message}` (`bad-range`, `chapters-pages-exclusive`, `pages-need-pdf`, `bad-device`, `cuda-unavailable`, `work-lock-held`, `stopped`); exit codes unchanged. (8) Spec 1.2 (additive, spec-first): range bundles renumber 1..K with optional `source_index` and a range-aware id (`uuid5("auloud:book:<sha>:chapters:<compact>")`, proven distinct across full + two ranges); full builds keep the existing id and spec 1.1 (byte-identical); Scribe validator enforces consecutive + `source_index` rules; Player accepts 1.2 and ignores `source_index` (new partial-golden fixture).
- Why: Detached-job groundwork (JSONL tail), UI ranges/preflight/pause, and progress-key safety (range ids never collide with each other or the full build).
- Alternatives considered: heartbeat lock file (rejected: thread + Windows lifetime complexity); cache-metadata RTF (rejected: lacks wall/audio); full builds writing 1.2 (rejected: breaks the byte-identical proof); Player model change for `source_index` (rejected: ignoring suffices).
- Consequences / revisit when: UI4 tails `events.jsonl` and wires `stop_event`; UI5 uses `plan_build()` and the resolutions; revisit the RTF window only with 3-hour-build evidence.

### D-052: UI2 server skeleton and packaging (FastAPI extra, D8 protections)

- Date: 2026-10-04
- Status: Accepted
- Decision: (1) D2 realized as planned: `scribe[ui]` optional extra (`fastapi>=0.115.0`, `uvicorn>=0.30.0`, `starlette>=0.46.0`, `python-multipart>=0.0.18`, `pydantic>=2.9.0`; all MIT/BSD/Apache-2.0, checked via the PyPI JSON API and recorded in `08-Liscenses.md` like the PyMuPDF row). Base CLI never imports them: `scribe ui` lazy-imports with a `uv sync --extra ui` hint (tested by blocking the import), and `scribe doctor` gains a `ui` row (import + versions). (2) New `scribe/ui/` package (AGPL headers): `security.py` (stdlib-only: token, Host allow-list, filename sanitizer, `safe_join`), `app.py` (`create_app(workspace)` with placeholder `GET /`, `GET /api/health`, tested-example `POST /api/echo`), `server.py` (127.0.0.1-only guard, next-free-port pick, per-workspace `.scribe-ui.lock`, browser open with print fallback), `static/index.html` (placeholder with the token embedded). (3) D8 now, not later: Host allow-list (127.0.0.1/localhost only, wrong Host gets 421 with no leak), no CORS headers anywhere, per-run `secrets` token required on POST/PUT/PATCH/DELETE (GET exempt), all file access via `safe_join` (absolute/drive/UNC/`..`-escape rejected as `path-traversal`). (4) Deliberate deviations: no `--host` flag at all (a bind guard refuses anything but 127.0.0.1 instead); FastAPI auto docs/openapi/redoc disabled so the skeleton exposes exactly three routes; the UI instance lock has no same-PID re-entry (a second holder always fails, even in-process); `POST /api/echo` kept as the tested token example.
- Why: The old web-UI spec sketch (stdlib server, sec 4) is superseded by the Slice 6 plan D2/D8 for upload/SSE/test-client reasons; keeping the skeleton dependency-free at import time (`security.py`/`server.py` need no extra) lets the port/lock/traversal tests run on the base install.
- Alternatives considered: reusing `acquire_work_lock` directly for the server lock (rejected: its same-PID re-entry would let one process hold two servers; the UI lock copies the O_EXCL + stale rules with its own file and no re-entry); serving the token in the printed URL (rejected: the page embeds it, the terminal line stays copy-pasteable).
- Consequences / revisit when: UI3 builds books/upload/audition on `create_app` + `safe_join` + token middleware unchanged; wheel proven to ship `scribe/ui/*.py` + `static/index.html` (`uv build` namelist). Cosmetic: installed starlette 1.7.0 prints a `StarletteDeprecationWarning` (httpx vs httpx2) under pytest, tests still green; revisit only if it ever fails collection. `ruff check` clean (`ruff format` drift left alone per D-048).

### D-053: UI3 books and book detail (scan, thread draft, stepper UI)

- Date: 2026-10-04
- Status: Accepted
- Decision: (1) New `scribe/ui/books.py` (AGPL header): pure file-derived scan/detail over `<workspace>/*.epub|*.pdf` (recursive, skipping `.scribe/`, `bundles/`, `jobs/`, `models/`, `logs/`, bundle `source/` copies), `<workspace>/.scribe/<book-id>/` and `<workspace>/bundles/*/`. Book id is the existing `draft.book_id_for_file` scheme; range bundles map back via `manifest.source.sha256`, else exact id, else listed as orphans. No work is ever triggered (validate runs read-only with an injectable probe seam). (2) Chips: Drafted (script parses with chapters), Cast edited (heuristic: `cast.yaml` mtime newer than `script.json` + 1 s), Rendering (per-book `.scribe.lock` names a live holder via `lock._pid_alive` + 24 h age; percent from the `events.jsonl` tail cached+rendered over the script sentence total, omitted when unknowable), Valid (any mapped bundle passes validation), On tablet always False (stub with TODO, UI8 owns transfer persistence, nothing invented). Validation errors are shaped `{file, rule, message}` with rule `validation`. (3) Upload `POST /api/books/upload` (multipart, token via middleware, sanitizer + `safe_join` confinement, `-N` collision suffix, `.epub`/`.pdf` only) saves the file and drafts on a daemon thread: in-memory `{status, filename, error}` plus the work-dir `events.jsonl` seam in the UI1 key contract (`draft_start`/`draft_done`/`draft_failed`, the failure line also carrying the `error` object so the message survives restart). UI4 subsumes this with real job folders; no job system, no cast editing, no transfer record here. Draft failures surface in detail as `{file, rule, message}` (`draft-failed`). (4) Consistency: detail reads the script after the slow scan and re-reads once when the bottom status is done/failed but the earlier read missed (done implies files on disk; found by test as a real torn read, not theory). (5) Frontend replaces `static/index.html` (token slot kept): Books table + detail stepper with the three UI9 components (table, stepper, chips; empty `#tray`/`#player-row` mount points), one `:root` token block UI9 owns, system fonts, ES modules, no build step, no `http` substring anywhere (offline test). (6) `test_ui_server.py` minimal-routes test updated to the six UI3 routes (only deviation from "extend, don't rework").
- Why: Keeps UI3 a thin file-derived operator (same artifacts as the CLI, proven byte-identical for script/cast/reports) while leaving UI4 (jobs), UI6 (cast), UI8 (validate/transfer) clean seams.
- Alternatives considered: detached-process draft now (rejected: UI4 owns detached jobs; a thread keeps UI3 scoped and the events seam preserves the tailing contract); persisting draft status in a new JSON record (rejected: work files + events tail already persist it; UI4's job folders are the real store); exact cast-edit tracking via content hash (rejected: mtime heuristic is enough for a chip, documented as such).
- Consequences / revisit when: `uv run pytest -m "not slow"` 551 green (531 baseline + 20 new UI3) + `ruff check` clean. Visual check of Books/detail in Edge left for the user (agent verified serving + content only). Revisit the mtime heuristic only if false Cast-edited chips appear; revisit percent math when UI4 build events carry totals.

### D-054: UI4 job runner and Jobs tray (detached jobs, sentinel pause, FIFO)

- Date: 2026-10-04
- Status: Accepted
- Decision: (1) New `scribe/ui/jobs.py` (AGPL header): one folder per job under `<workspace>/jobs/<job-id>/` (`job.json` with id/book/kind/state/created/updated/pid/exit/summary/error, `events.jsonl`, `build.log`, `stop` sentinel); history is a directory scan. States `queued/running/paused/done/failed/cancelled` + `interrupted` (was `running` with a dead pid, detected at manager start, resumable from cache). (2) Pause mechanism (the honest choice the task asked to document): a `stop` sentinel file the build polls at sentence boundaries — additive `stop_file` param on `run_build` (checked alongside the in-process `stop_event`; smallest possible seam, one new test, CLI gains `--stop-file`) plus `--events-jsonl`/`--stop-file`/`--no-progress` on the detached `scribe build` child. Pause creates the file (graceful `BuildStoppedError`, partial chapter cleaned, sentence cache untouched); resume deletes it and starts a new process with identical args (MV7 cache skips done work); cancel terminates and deletes the in-progress chapter's torn render pair (best effort; `chapter_up_to_date` would re-render it anyway). Terminate-without-sentinel was rejected for pause because only the cooperative path guarantees the partial cleanup ran. (3) Single global FIFO (one running job; second waits queued) plus one-render-per-book (same-book create/resume refused `409 work-lock-held`, as is a live `.scribe.lock` holder). Drafts are jobs too: upload queues a detached `scribe draft` with manager-written `draft_start`/`draft_done`/`draft_failed` lines in the UI1 key contract, mirrored to the work-dir seam so UI3 readers keep working. (4) Detach: Windows `DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW` (documented in `_detached_popen`), POSIX `start_new_session`; stdio to `build.log`. (5) API: `POST /api/books/{id}/build` (202 + job id; `chapters`/`pages`/`device`, default `cpu`), `GET /api/jobs`, `GET /api/jobs/{id}`, `POST pause|resume|cancel` (`409` on bad-state/lock, `400` on bad id/device, `404` unknown), `GET /api/jobs/{id}/events` SSE (`Last-Event-ID` replay by line id, immediate + 15 s `: ping` heartbeats, closes after terminal drain), `GET/PUT /api/settings` for the keep-awake toggle. Job ids are strict `[A-Za-z0-9_-]` (`400 bad-job-id`); all file access via `safe_join`; token middleware unchanged. (6) Keep-awake in `scribe/ui/keepawake.py`: `SetThreadExecutionState(ES_CONTINUOUS|ES_SYSTEM_REQUIRED)` via ctypes while any job runs (released to `ES_CONTINUOUS` when idle), persisted `<workspace>/.scribe-ui-settings.json` (`keep_awake`, default on), best-effort no-raise off Windows. (7) Tray in `static/index.html` (token slot, `:root` tokens, offline kept; frontend `http`-free assertion still green): fixed bottom bar with live progress percent (sentences over the book total), pause/cancel/resume buttons, finished toasts linking to the book detail, history table, keep-awake checkbox; empty states name the upload action.
- Why: D1/D-050 as amended (sequential chapters, no workers): crash-proof renders, browser-closed builds, reattach and cache-resume fall out of the detached + MV7 design.
- Alternatives considered: in-process worker threads (rejected: a native inference crash would take the server; D1 already settled this); real SIGSTOP-style frozen-process pause (rejected: Windows complexity for no cache benefit); heartbeat lock files (rejected: D-051 already chose PID + 24 h age); draft staying a bare thread (rejected: two job systems would drift; the detached draft keeps one FIFO, one store, one SSE).
- Consequences / revisit when: `uv run pytest -m "not slow"` 568 green (551 baseline + 17 new UI4) + `ruff check` clean; UI2 route-minimality test extended with the six UI4 routes (additive, not a behavior change). Surprises: (a) Windows `os.replace` on `job.json` races monitor/API threads (`PermissionError`) — atomic write now retries 20x50 ms and reads retry 5x20 ms; (b) `python -c` forbids `while` after `;` (real-detach test script is newline-joined); (c) `@app.on_event("shutdown")` is deprecated — shutdown warning moved to a `lifespan` handler; (d) the fake spawner gives drafts a fast default so upload drafts never consume the queued build behaviors. Needs device test: tray + pause/resume visuals in Edge; a real browser-closed build with pause/resume; keep-awake through a long render; server restart reattach against a live build. Revisit the single-global FIFO only with evidence that draft+build or two-book parallelism is wanted (D-050 scrapped workers deliberately). Known gaps from review (low, not data loss): `interrupted` detection trusts PID liveness without an age guard, so an OS-recycled PID could stall a queue entry until the next poll cycle; a kill before the first sentence event leaves a torn chapter-0 MP3 that `chapter_up_to_date` re-renders rather than the cancel path deleting it.

### D-055: UI5 render step (preflight API, device setting, picker UI)

- Date: 2026-10-05
- Status: Accepted
- Decision: (1) New `POST /api/books/{id}/plan {chapters?, pages?}` (token-guarded like all POSTs): pure `plan_build` over the drafted script (no audio, no lock) returning cached/to-render chapter + sentence counts, `estimated_seconds` + `rtf_used` (None with no history), `selected_chapters`, `page_resolution`, `is_range`, and `message` set to `format_plan` output so panel wording matches `scribe build --plan`. Selection errors reuse the CLI's `{file, rule, message}` shapes verbatim (`bad-range`, `chapters-pages-exclusive`, `pages-need-pdf`, `pages-no-cover`). Tests inject a fake engine via a `plan_engine` app seam (real fingerprint logic, stubbed synth); production tries `<workspace>/models` then CWD `models`, falling back to the library's all-to-render preflight when models are absent. (2) Compute settings extend the same `<workspace>/.scribe-ui-settings.json` file: `GET/PUT /api/settings` is now `{keep_awake, device}` (device `auto`/`cpu`/`cuda`, default `auto`); `PUT` accepts either field alone and validates `device` live with CLI-identical wording (`bad-device`, plus `cuda-unavailable` when this machine's onnxruntime lacks the CUDA provider). The build-create route uses the stored device when a request omits it, so the API default moves from `cpu` (D-054) to `auto` (the library/CLI default per D-051). (3) Frontend Render step in the book-detail stepper: checkbox chapter picker with durations + select-all/clear + X-to-Y shorthand (grammar hint shown; server 400 messages shown verbatim), PDF page-range input with debounced resolved preview ("pages A-B map to chapters C-D; partial edges render whole chapters"), preflight panel (will-render/cached/estimate or "no history yet"), device select persisted per machine, Start (202, then scrolls to the tray), and live progress/RTF/ETA/cache-hit plus pause/resume/cancel that reuse the shared 2 s jobs poll and `jobAction` (no second timer or stream). Terminal jobs reload the detail once, so step 4 shows the validation summary beside the finished rendered/cached counts. No render-semantics or job-system change (UI1/UI4 own those).
- Why: Keeps the UI a thin operator: preflight is the library dry run, device is one persisted key, and the live view reads the same poll the tray already runs.
- Alternatives considered: eager range validation in build-create (rejected: preflight already validates before Start; UI4 owns that route); a second EventSource for render progress (rejected: the jobs poll already carries summary counts every 2 s); persisting device per book (rejected: it is a machine property, one workspace key).
- Consequences / revisit when: `uv run pytest -m "not slow"` 578 green (568 baseline + 10 new UI5) + `ruff check` clean; UI2 route-minimality and UI4 settings tests extended additively. Surprises: (a) the `{file, rule, message}` split means `message` holds only the tail — verbatim checks must rejoin the triple against the CLI string; (b) delta tests need per-chapter-distinct fixture sentences or the shared-sentence cache makes chapter 2 look pre-rendered (correct behavior, wrong fixture). Needs device test (your Edge pass): picker, preflight, start/pause/resume, delta rebuild, plus the browser-closed build from UI4.

### D-056: UI6 cast view (patch ops, mtime conflicts, quotes, palette + clip contract)

- Date: 2026-10-05
- Status: Accepted
- Decision: (1) New `scribe/ui/cast.py` (AGPL header): five typed patch ops (`set_voice`, `set_speed`, `set_first_person`, `add_override` quote-key or text-match, `remove_override` by index/key/match) as pure dict-in/dict-out functions through the MV5 merge/validate functions (never around them), `validate_ops_atomic` (every op validates before anything is staged for write), and `write_cast_guarded` (validates verbatim, re-reads, compares `expected_mtime`, mismatch raises `CastConflictError` with the current state and writes nothing). Validation errors are re-split `file/rule/message` so rejoining gives the CLI string exactly. (2) API (additive, token on PUT/PATCH via the existing middleware): `GET /api/books/{id}/cast` (parsed cast, mtime, `has_comments` + `COMMENT_WARNING`, per-character stats, minor list, current validation errors), `PUT` full replace with the guard, `PATCH` op list (preferred; response carries `applied` plus `affected.quotes`, the exact re-voice delta), `GET .../cast/report` (raw `markdown` plus fully-escaped `html`; the view renders `textContent`), `GET .../quotes?confidence=all|low&page=&per_page=` (default 100, cap 500, with total; each entry carries raw speaker, confidence and the resolved character), `GET /api/cast/voices` (D-036 palette only, no model loaded), `GET /api/voices/{name}.wav` (cached clip or 409 `sample-missing` with the seeding command). (3) YAML-comments verdict (plan D6, loud by design): the writer is PyYAML (`safe_dump`), which cannot round-trip `#` comments, and a round-trip library (ruamel.yaml) would be a new dependency — so a UI save preserves every hand-edited value (patches apply onto the freshly-read file) but drops comment lines. Flagged in three places: module docstring, `has_comments`/`comment_warning`/`saved_comments` in the API, and here. The mtime guard is what protects hand edits from silent destruction, not comment preservation. (4) Audition contract UI7 extends: clips live flat under `<workspace>/.scribe/voice-samples/` (exact `<voice>.wav` wins, else the `scribe voices --sample` `NN-<voice>.wav` shape), servable by pointing `--out-dir` at it; the server never synths inline (one Kokoro clip costs seconds plus tens of seconds of model load — measured in D-023/D-048 — so generate-on-missing is always 409, never synchronous); UI7 adds full engine enumeration, an `<engine-version>/` cache segment and a one-click generation job, keeping the `GET` paths. (5) Stats honesty: lines come from draft quote resolution (gender `unknown`, since `script.json` persists no hints — the same fallback the build uses for hintless lines); minutes come from the first valid mapped bundle via `inspect_bundle` only (summing all bundles would double-count shared range chapters), else 0 with `has_audio: false`. Minor collapsible is raw surfaces at or below 3 lines (informational; promoting one means adding a `characters` entry by hand — no patch op invents characters).
- Why: Cast stays hand-editable (file is truth, conflicts never overwrite) while the UI covers the common edits, auditions and overrides without YAML; the palette keeps dropdowns fast and offline.
- Alternatives considered: ruamel.yaml round-trip now (rejected: new dependency needs asking; values are safe, comments are bannered); full engine voice list via model load per request (rejected: tens of seconds per page view; UI7 owns it); summing minutes across bundles (rejected: double-counts ranges); synchronous sample generation (rejected: seconds-plus per clip, honest 409 instead).
- Consequences / revisit when: `uv run pytest -m "not slow"` 598 green (578 baseline + 20 new UI6) + `ruff check` clean; route-minimality test extended additively. Surprises: (a) bare quotes in a named book inherit medium via continuation/alternation, so low-confidence coverage needed a second, nameless fixture (all-fallback-low, resolving `default_female`); (b) ruff caught two real bugs before tests ran (`has_comments`/`had_comments` name slip in PUT/PATCH, an int `.strip()` in paging). Needs user Edge pass: table edits, audition play, override add plus the re-voice count into a Render delta, and conflict by hand-editing `cast.yaml` mid-session.

### D-057: UI7 voices view and audition service (versioned clips, job generation)

- Date: 2026-10-05
- Status: Accepted
- Decision: (1) Versioned clip cache keyed by (voice, engine version, sample text) under `<workspace>/.scribe/voice-samples/<safe-version>/<text-hash>/` (`ui/cast.py`: `sample_text_hash` 12-hex sha256 of the fixed audition sentence, `sanitize_engine_version` traversal-proof dash mapping, `versioned_samples_dir`, `find_versioned_sample`, `find_cached_clip` versioned-first with the UI6 flat fallback). The engine version matches `KokoroEngine.engine_version` (`kokoro-onnx <pkg>`) but comes from `importlib.metadata` only, so the server never loads the model (proven by test: cached serve with `create_engine` rigged to fail and `tts.kokoro`/`kokoro_onnx` absent from `sys.modules`). Clip cache lives under `.scribe/`, already gitignored by root and `scribe/.gitignore` (verified, no change). (2) Generation as a detached `voices-sample` job (`ui/jobs.py`: `VOICES_KIND`, sentinel book `__voices__`, `create_voices_sample` with no per-book lock queuing on the same global FIFO, spawn `scribe voices --sample --out-dir <versioned-dir> [--voice ...]`, cancel/resume supported but pause refused as `bad-state` since the child never polls the sentinel). The CLI gains repeatable `--voice` subset filtering (default all; unknown voices fail cleanly as `voices failed`, listing filters too). (3) Coalescing (documented choice): POST uses queued-on-same (202 with the same id plus `coalesced: true` when a queued/running/paused job already covers the wanted set with same version/hash; superset covers subset, so regenerate-all coalesces single retries while disjoint sets queue separately); GET missing returns the coalesced 409 plus `job_id` only when such a job exists, else 409 without one. GET never starts a job itself, so the GET token exemption cannot trigger state changes (D8 kept). (4) Serving: versioned hits stream with `Cache-Control: public, max-age=31536000, immutable`, legacy flat hits with `no-cache`, always `audio/wav`; Range requests ride the framework `FileResponse` (not hand-rolled). `GET /api/cast/voices` keeps `voices`/`source` and adds the live `engine_version`, `sample_text`/`text_hash` and `details` (`{name, locale, gender}` from the Kokoro id prefix, heuristic display-only: a/b American/British, j Japanese, z Chinese, i Italian; f/m female/male). Full engine enumeration beyond the D-036 palette stays deferred. (5) Voices view: palette grid with locale/gender, Play via the one shared `auditionVoice` into the single `#player-row` mount (no second player; missing clips POST then poll `pollJobDone` then retry), per-voice Regenerate plus Regenerate-all (POST with `regenerate: true`), engine version shown. (6) Real-line verdict: DEFERRED explicitly, no gold-plating. A book-line audition would need quote-to-voice resolution, custom-text cache keys plus a serving seam, and picker wiring; the job only renders the fixed sentence. No new engine plumbing was added for it.
- Why: Auditions stay one-click without ever stalling the server on model load; versioning makes engine upgrades regenerate exactly once while same-version retries cost no job.
- Alternatives considered: GET auto-creating jobs (rejected: state change on a token-exempt GET breaks D8); 409-retry for POST coalescing (rejected: 202 same-id is the honest queued-on-same shape); exact `<voice>.wav` only in versioned dirs (rejected: the existing `--sample` zero-padded `NN-<voice>.wav` shape is kept via the `*-<voice>.wav` glob, same as flat); pausable sample jobs via sentinel polling inside `sample_voices` (rejected: samples render in seconds, cancel plus retry is enough); hardcoding every locale/gender row (rejected: prefix heuristic covers future ids, unknown stays unknown).
- Consequences / revisit when: `uv run pytest -m "not slow"` 615 green (598 baseline + 17 new UI7) + `ruff check` clean; route-minimality test extended with the two UI7 POST routes. Surprises: (a) the UI6 frontend needle `voice-samples` vanished with the old 409 copy, so the Voices section now names the cache dir in a muted line; (b) the sorted route list puts `/api/voices/{name}.wav` before `/api/voices/{name}/sample` (`.` sorts before `/`). Needs user Edge pass: grid render, Play, Regenerate single plus Regenerate-all, and the coalescing second-click while a job runs. Revisit full enumeration and real-line audition only with a book-line serving design.

### D-058: UI8 validate and transfer (inline validate, threaded copy+verify, record owns chip)
- Date: 2026-10-05
- Status: Accepted
- Decision: (1) Inline-vs-job verdict (measured): validate runs INLINE in `POST /api/books/{id}/validate` (read-only, no job). Stub 2-chapter validates in ~3 ms; a real 1-chapter build validates in ~3.9 s (D-048), so even a 20-chapter novel stays well under 30 s. A job would add FIFO/SSE/pause machinery for no benefit. Results are `{file, rule, message}` parsed from the existing validator strings (wording unchanged; rule is `validation`), returned as `{book_id, valid, validation, bundles}` and mirrored in the book detail. The ffprobe seam is injectable (`app.state.validate_probe`, like `plan_engine`). (2) Transfer as a `transfer` job kind (`ui/jobs.py`: same `jobs/<id>/job.json` shape, same global FIFO, no per-book lock; in-process copy thread, not a detached process, since copies are seconds of IO with no native crash risk). `POST /api/books/{id}/transfer {destination, force?, bundle?}` picks the first valid bundle (or the named one), copies to `<destination>/<bundle-dirname>`, refuses existing dirs with 409 `exists` unless `force=true` (which deletes that one dir only; nothing else is ever deleted). Progress polls via `GET /api/jobs/{id}` (`bytes_copied/bytes_total`); pause refused as `bad-state`, cancel cleans the partial copy, resume forces the rerun. Verification re-hashes the DESTINATION after the copy (size + sha256 per file, rule `verify-failed`); a truncated file always fails (proven by test). (3) Destination validation (`ui/transfer.py`, stdlib only): absolute only; relative, `..` parts, NUL/newline and shell metachars (`; & | $ \` < > "`, plus `$(`) are 400 `bad-destination`; the anchor/drive must exist. Only the SOURCE side uses `safe_join`; the destination is explicitly outside the workspace. Drives come from `GetLogicalDrives` + `GetDriveTypeW` via `ctypes` (removable only, no `wmic`/subprocess; `[]` elsewhere). (4) Record in `<workspace>/.scribe-transfers.json` (`{destination, dest_bundle, bundle_path, bundle_id, verified, bytes_total, time}`) written on success (`verified: true`) and on verify-failure (`verified: false`); the On-tablet chip requires `verified` plus the recorded `bundle_id` still matching a mapped bundle id (a rebuild clears the chip). (5) Frontend: Validate step (Validate-now button, `file: rule: message` list, bundle summary) plus Transfer step (removable-drive picker + typed path + force tick + bundle picker, byte progress with verification tick, record display, Player watch-folder instructions naming `/Auloud`, Open-folder button). Open-folder is a token-guarded `POST /api/books/{id}/open-folder` calling `os.startfile` on Windows only (400 `open-unsupported` elsewhere with the printed path still shown).
- Why: Keeps validate honest (seconds, read-only) while transfers get progress, verification and the chip without a second store; the id-match stops a stale record surviving a rebuild.
- Alternatives considered: validate as a job kind for uniformity (rejected: measured seconds, FIFO/SSE would only add latency); detached `scribe transfer` process (rejected: copy is managed IO, no inference crash to isolate); transfer outside the job store with a bespoke poll (rejected: the tray already polls `/api/jobs`, same shape is free); `safe_join` for destinations (rejected: destinations are outside the workspace by design); GET auto-opening folders (rejected: state change on a token-exempt GET breaks D8).
- Consequences / revisit when: `uv run pytest -m "not slow"` 627 green (615 baseline + 12 new UI8) + `ruff check` clean; route-minimality extended with the four UI8 routes. Needs user Edge pass: validate-now, drive picker + typed path + force overwrite, byte progress + verified tick, record + chip, Player import from a transferred bundle on the tablet, open-folder on Windows. Revisit Wi-Fi transfer only as the v1.1 backlog item (this slice stays USB/folder copy).

### D-059: UI9 design system and polish (tokens, contrast, five components, runbook)
- Date: 2026-10-05
- Status: Accepted
- Decision: (1) Tokens to spec: paper `#FAF7F0`, ink `#1A1714`, oxblood `#7B2D26`; muted darkened `#78716C` to `#6B625C` (the old muted measured 4.48:1 on spec paper, just under the bar); sage `#5F7166` kept as the single success token (`--success` duplicate and amber `--warn` removed, so busy chips use ink and selected chips use ink instead of sage); `--on-accent` added so buttons pass in both modes; dark mode flips the same 8 vars via `prefers-color-scheme` (paper `#1A1714`, raised `#26211D`, ink `#FAF7F0`, muted `#B8AEA6`, oxblood `#E8AFA6`, sage `#9DB8A6`, hairline `#3A342E`, on-accent `#1A1714`), not a second palette. (2) Components: `.card` removed (sections use hairline dividers, no boxes-in-boxes); shadows removed everywhere except the cast-conflict banner (the one elevation level); toasts documented as tray notices, not a sixth component; serif scoped to the wordmark plus the book title; tabular numerals on all live regions; pill radii to 4 px; chip padding to the 8 pt grid; tray progress fill to oxblood (position) so sage stays success-only. (3) Copy: draft/render/upload/device/job errors all render `file: rule: message`; Remove to Remove override, Clear to Clear selection, Reload to Reload cast. (4) Accessibility: skip link plus `main`, focus-visible extended to select/audio with a token-based width, cast errors `role=alert`, validate results assertive, player-row mount polite. (5) Tests: 8 new in `scribe/tests/test_ui9_design.py` (tokens, dark flip, no one-offs, contrast both modes, five-component inventory with a utility allowlist, offline, accessibility, copy); runbook `docs/UI9-BrowserRunbook.md` written, not run. Final WCAG ratios, light: body 16.68, muted 5.57, accent 8.74, success 4.86, button 9.35; dark: body 16.68, muted 8.20, accent 9.45, success 8.36, button 9.45.
- Why: visual-only polish with automated gates (contrast, component inventory, offline) instead of eyeballing; the browser pass stays manual per plan UI9.
- Alternatives considered: keeping `--success`/`--warn` (rejected: extra hues against the sage-is-success rule); a separate dark palette (rejected: plan says inversion of the same vars); keeping card shadows (rejected: boxes-in-boxes, one elevation only); `GET`-style auto-muted error text without rules (rejected: copy convention names file and rule).
- Consequences / revisit when: `uv run pytest -m "not slow"` 635 green (627 baseline + 8 new UI9) + `ruff check` clean; all older frontend needles still pass (`http`-free kept). Needs user 3-browser runbook (Edge, Chrome, Firefox: layout, stepper, tray, conflict banner, dark via OS setting, keyboard-only, zoom 200%). Revisit token values only if the runbook reports legibility complaints; revisit the utility allowlist only if a genuine sixth component is proposed.

### D-060: UI10 tests and hardening (shaped errors, security matrix, state gaps)
- Date: 2026-10-05
- Status: Accepted
- Decision: (1) Hardening in `scribe/ui/app.py` only: `RequestValidationError` returns 400 shaped `{file: request, rule: bad-request}` (non-dict bodies and missing multipart files previously gave 422 `detail`; no test asserted 422), and generic `Exception` returns 500 shaped `{file: server, rule: internal}` with no traceback (framework `HTTPException` 404/405 keeps `detail`; middleware 421/403 untouched). (2) 22 new tests in `scribe/tests/test_ui10_hardening.py` (AGPL header): Host rejected on all NEW routes, token missing on every POST/PUT/PATCH, traversal on every book/voice path param plus job-id charset on every job route (slash/dot segments document router-normalized framework 404, not shaped), bad bodies/field types shaped, malicious token escaped in index, SSE query-param reconnect/torn-skip/paused-drain/400-404, state gaps (double-pause, cancel-paused, pause-refused voices+transfer, resume-rejected done/running/queued, resume-cancelled allowed, queue-across-restart, interrupted pause/cancel-refused). (3) No `docs/UI9-BrowserRunbook.md` change: UI10 found no UI bugs worth listing (API-only hardening).
- Why: Closes the UI10 verify (whole suite green; coverage on the job state machine) while keeping the UI a thin operator: validation and crash shapes live in two handlers, every fix has its test, and the suite proves no model load (engine imports blocked) with no slowdown to existing suites.
- Alternatives considered: per-route `Any` payload signatures instead of a validation handler (rejected: touches 10+ signatures for the same 400 shape); shaping framework 404/405 for unknown routes (rejected: router-normalized dot/slash 404s are not our routes, and no test wants them shaped); a browser smoke test (rejected: plan allows the manual runbook, which stays untouched).
- Consequences / revisit when: `uv run pytest -m "not slow"` 657 green (635 baseline + 22 new UI10) + `ruff check` clean; UI subset 172 green in 49.78 s vs 42.26 s baseline for 150 (delta is the new suite itself; existing per-file durations unchanged within noise); full suite 105.56 s vs 98.08 s baseline. Surprises: (a) two EPUBs with identical bytes share one book id (UUIDv5 over sha256), so two-book queue tests need distinct titles; (b) TestClient raises server exceptions by default, so the shaped-500 test must construct with `raise_server_exceptions=False`; (c) the plan task phrase "resume-after-cancel-rejected" is clarified: cancelled IS resumable per `RESUMABLE`, so the test proves resume-cancelled succeeds while resume done/running/queued is rejected. Needs user: UI11 docs/acceptance plus the browser-closed 3-hour build and 3-browser runbook.

### D-061: UI11 docs half (spec saved, README, licenses, test-log; acceptance left)
- Date: 2026-10-05
- Status: Accepted
- Decision: Docs-only WP on `dev/slice6`, no code, no suites run (only `scribe ui --help` plus the CLI helps quoted in the README). (1) Spec saved as `docs/14-ScribeWebUI-Spec.md` (no `docs/14-*` clash; next free spec number used as suggested): v1 UI as built, faithful to `docs/plans/ScribeWebUI.md` sections 1-10 with per-section As-built notes plus a section-0 deviation table (workers scrapped, pause=sentinel, FIFO, inline validate, threaded transfer), appendix A acceptance checklist (Slice 6 Done-when), appendix B as-built route list read from `scribe/ui/app.py`. Note: no separate spec message existed in this session, so the plan doc is the spec source (recorded here, not silently assumed). (2) README gains a Web UI section (extra install, `scribe ui` flags, four views, jobs + reattach, ranges, cast without YAML, transfer) plus a 7-step fresh-user walkthrough with expected outputs; every quoted flag verified against live `--help` runs. (3) Licenses: UI dep set keeps its tick honestly (PyPI JSON API 2026-10-04 plus installed dist metadata 2026-10-05 agree on all five versions/expressions; floors in `pyproject.toml` plus `uv.lock` confirmed); recorded gap is full-text review (expressions only). (4) Roadmap UI11 box NOT ticked (its verify is user acceptance plus the `slice-6` tag); the Slice 6 section text now states the docs-half state exactly. (5) test-log gains one UI1-UI10 automated block with the session counts. Plan D1-D8 disposition: D1 job runner accepted on reasoning (D-050) as amended by D-054 (detached per job, sentinel pause, FIFO); D2 licenses accepted (D-052, re-verified here); D3 frontend accepted (UI3/UI5-UI9, offline no-build); D4 job store accepted (D-054, plus the `stop` sentinel addition); D5 CPU-default accepted with amendment (D-050 scrapped workers; default is `auto`, CUDA-when-present else CPU, D-051/D-055); D6 cast edits accepted with the comment caveat (D-056: values safe, `#` comments dropped, mtime guard is the protection); D7 spec 1.2 accepted (D-051: renumber plus `source_index` plus range-aware id); D8 security accepted (D-052 plus GET-never-creates-jobs D-057 and the UI10 matrix D-060). Real-line audition deferred and Page view parked to v1.1 (D-046) are recorded in the saved spec, not dropped silently.
- Why: UI11 verify splits in two: the agent can do docs, only the user can do acceptance (fresh-user run, browser-closed 3-hour build with pause/resume, 3-browser runbook) plus the tag. Ticking nothing keeps the roadmap honest.
- Alternatives considered: ticking UI11 now (rejected: acceptance plus tag are explicitly the user's); quoting `--voice`/`--stop-file` in the README (rejected: the installed `scribe voices/build --help` outputs run today do not show them, so only live-help-proven flags are quoted).
- Consequences / revisit when: needs user: spec section 9 acceptance end to end, then tag `slice-6` (do not tag from the agent side). Installed CLI staleness noted again (source has `--voice`/`--stop-file`, installed help lacks them; refresh with `uv sync --reinstall-package auloud-scribe` at will). Revisit the license gap only if vendored full texts are ever required.

### D-063: GPU measured, CPU kept; full voice list; detail collapse + chapter dropdown

- Date: 2026-10-05
- Status: Accepted
- Decision: (1) GPU: proven working in a throwaway venv (`onnxruntime-gpu 1.30.0` + NVIDIA `cublas/cudnn/cuda-runtime-cu12` pip wheels, no system CUDA toolkit; note the `kokoro-onnx[gpu]` extra silently installs CPU on Windows because its marker says `x86_64` while Windows reports `AMD64`) — but warmed CUDA measured **3.95x** vs warmed CPU **4.03x** on the RTX 4050 for Kokoro-82M: no gain (small model, per-sentence overhead dominates). Main env stays CPU-only; no package swap. `device.py` gains NVIDIA-wheel DLL registration (`ensure_cuda_dlls`: `os.add_dll_directory` + PATH prepend, best-effort) so `--device cuda` works out of the box for anyone who installs the GPU wheels (`uv pip install onnxruntime-gpu nvidia-cublas-cu12 nvidia-cudnn-cu12 nvidia-cuda-runtime-cu12`, ~2.5 GB); CPU stays the documented recommendation. (2) Voices: `/api/cast/voices`, audition and sample routes now serve the full engine list (54 ids read from the `voices-v1.0.bin` npz keys via `tts.voices.engine_voice_names` — directory read only, never a model load) when a models dir holds the archive, else the D-036 palette fallback; `describe_voice` hints already cover all prefixes. Draft auto-assignment still uses the palette (unchanged). (3) UI: book-detail Draft chapters and Cast names collapse past 8 chapters / 6 names (native `<details>`, no new classes); the render shorthand typing is replaced by a chapters dropdown (checkbox table inside `<details>` with live "N of M" summary; the API keeps accepting `--chapters` strings).
- Why: User acceptance feedback (clunky detail, typing chapters, GPU, more voices) plus measured evidence over assumptions.
- Alternatives considered: swapping the main env to onnxruntime-gpu (rejected: zero measured gain for gigabytes of coupling; `auto` would then silently move all builds to CUDA); ruamel.yaml-style voice list hardcoding (rejected: archive keys are truth, palette fallback covers absence).
- Consequences / revisit when: revisit GPU only with a larger model or evidence it matters; the throwaway proof env (`Temp/opencode/gpu-proof`, ~3 GB) can be deleted.

### D-062: UI render output lands in the workspace bundles dir

- Date: 2026-10-05
- Status: Accepted
- Decision: Detached `scribe build` jobs pass `--out-dir <workspace>/bundles` explicitly instead of relying on the CLI default (cwd-relative `bundles/<id>`, which is the server's cwd, not the workspace). Found in user acceptance: a finished job's bundle sat beside the server while the Validate step truthfully reported "nothing valid". The book-detail payload gains `bundles_dir`, and the empty Validate message names the directory it reads so the next mismatch is self-diagnosing.
- Why: The scan and the spawn must agree on one directory; the workspace owns both.

### D-064: Player license decided (Apache-2.0), per-component LICENSE files

- Date: 2026-10-05 (moved from D-050: that number belongs to the Slice 6 UI0 entry merged in from dev/slice6)
- Status: Accepted
- Decision: D-015 resolved as Apache-2.0 for the Player (patent grant, AndroidX norm). `player/LICENSE` added (full Apache-2.0 text, 2026 Zyxavy appendix); `player/NOTICE` updated from undecided to Apache-2.0. Root `LICENSE` stays MIT (repo default); `scribe/LICENSE` stays AGPL-3.0-or-later. README "Player: Apache-2.0" line is now accurate. Contribution policy (DCO/CLA) still undecided — the only remaining open box in 08 section 7.
- Why: Release checklist CP12 needs the files; the choice is the author's.

### D-065: v2 engine interface (TtsEngine)

- Date: 2026-10-05
- Status: Proposed (Slice 7/8 implements)
- Decision: A pluggable `TtsEngine` interface, not a choice between engines: voice list, capabilities (multi-speaker, load cost, sample rate), `synthesize(text, voice, speed)`. Voice ids namespaced per engine (`kokoro:af_heart`, `piper:<model>`, `system:<voice>`), matching the `engine` field already in the manifest.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 1; each engine declares what it can do and the app picks or recommends per device.

### D-066: v2 license path for espeak-ng (superseded by D-126)

- Date: 2026-10-05
- Status: Superseded by D-126
- Decision: Piper and Kokoro depend on espeak-ng for phonemes, which is GPL-3.0. Options: A. Bundle it in the Player and make the Player GPL-3.0 (simplest legally, fully self-contained; reopens the license just closed in D-064). B. Keep the Player Apache-2.0 and use only the System TTS adapter, so any GPL engine is a separate app the user installs (cleanest license boundary, less polished, depends on third-party engine apps). C. Ship an engine path with no GPL parts, if one exists at acceptable quality (check in the spike). Two-voice mode does not change this question.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 2; this is the v2 D-015.

### D-067: v2 model delivery without INTERNET (sideloaded packs)

- Date: 2026-10-05
- Status: Proposed
- Decision: Sideloaded model packs (a folder on the microSD card, imported like a watch folder), keeping the offline promise and the no-`INTERNET` design (D-016). An optional download feature would need the permission and is a privacy trade-off; deferred.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 3.

### D-068: v2 on-device audio storage (AAC-LC, spec amendment + measured tolerance)

- Date: 2026-10-05
- Status: Proposed (measure in Slice 10)
- Decision: Android has a built-in AAC encoder; an MP3 encoder would mean shipping extra native code. Recommend AAC-LC mono in MP4/M4A written with the platform muxer. Needs a small spec amendment (audio formats) and possibly a looser sync tolerance than 50 ms because of encoder delay; measure it.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 4.

### D-069: v2 render location (bundle folder when writable, else app storage)

- Date: 2026-10-05
- Status: Proposed
- Decision: Write rendered chapters into the bundle folder when writable (the bundle becomes complete and portable), else into app storage.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 5.

### D-070: v2 unrendered books and script bundles (optional in v2.0)

- Date: 2026-10-05
- Status: Proposed (optional in v2.0)
- Decision: The spec needs a way to describe a book whose audio does not exist yet (text and sentence structure, no MP3s or timings). Books imported on the device use it internally; Scribe can also export one (`scribe export --script`) for PDFs and for its better text cleaning.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 6.

### D-071: v2 two-voice mode on the device (decided)

- Date: 2026-10-05
- Status: Accepted
- Decision: On the device, every sentence is either narration or dialogue, rendered with the narrator voice or the dialogue voice. No speaker attribution on the device, only quote detection (a deterministic state machine, already built and tested in Scribe). Books from Scribe that carry per-character voices are still played as rendered; if one is re-rendered on the device, all non-narrator speakers collapse into the dialogue voice, and the data stays in the file for a future per-character mode. Consequences: Piper needs only two models loaded (or one multi-speaker model); Kokoro needs one model with two speaker ids. Voice switching happens only at narration/dialogue boundaries, and the existing 100 ms tag pause and per-voice leveling carry over. The cast screen becomes simple voice settings. On-device ingestion drops the hardest part (attribution) and can move into v2.0.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 7; removes on-device speaker attribution and shrinks the engine and memory problems.

### D-072: v2 PC stays fully multi-voice (decided)

- Date: 2026-10-05
- Status: Accepted
- Decision: Scribe's per-character casting, attribution and `cast.yaml` are unchanged, and multi-voice remains the default and recommended way to get the best audiobook. A "collapse to two voices" toggle on the PC is not part of v2; if PC and device books should ever sound alike, it can be added later as an optional setting.
- Why: Source is `docs/09-V2Roadmap.md` section 3 decision 8; the PC can handle multi-voice, so v2 changes nothing there.

### D-073: SW1 Piper runtime is piper-tts (P1 resolved)

- Date: 2026-10-05
- Status: Accepted
- Decision: Scribe's Piper engine uses `piper-tts` 1.8.0 (OHF reference implementation), not sherpa-onnx Python and not hand-rolled onnxruntime + phonemizer. Measured facts: PyPI `license` field says GPL-3.0-or-later (the old MIT memory was wrong); GPL-3.0 combines into AGPL-3.0-or-later Scribe with the work staying AGPL (GPLv3 section 13), recorded in `08-Licenses.md`; the wheel bundles espeak-ng data (out-of-the-box synth proven, no external install); only `onnxruntime` (already pinned) joins the dep tree. Real-voice proof: `en_US-lessac-low` (16 kHz native, 2.64 s in 0.18 s wall) resampled to 24 kHz through the existing `resample_mono` path; its Blizzard-2013 dataset license is proof-only, so SW4 must pick permissively licensed voices. Surprises: (1) the venv's installed `scribe` copy was stale (hatchling force-include materializes a copy, not a link) and `scribe.exe` was locked by a still-running `scribe ui` server from acceptance work — stopped with permission, then `uv sync --reinstall-package` refreshed it; (2) a plain `uv sync` pruned the `ui` extra (fastapi etc.), restored with `uv sync --extra ui` — plain sync is not safe in a venv that has extras installed.

### D-074: SW2 per-entry cast engines with Kokoro default (P2 resolved)

- Date: 2026-10-05
- Status: Accepted
- Decision: Every voice-carrying cast entry (narrator, characters, generics) accepts `engine` (`kokoro`/`piper`); missing or garbled means Kokoro everywhere (resolution, validation, merge), so all v1 casts and goldens validate byte-identically and `draft`/`merge` write no new keys. `validate_cast` checks each voice against its own engine's list (explicit per-engine map wins, else Kokoro falls back to `known_voices`); `ResolvedVoice` carries `engine`, `build` constructs exactly the needed engines (Kokoro via the existing path, Piper lazily from `models_dir`), routes synthesis per sentence, records per-speaker engines in the manifest (the `engine` field already existed and is validated non-blank — no spec amendment), and fingerprints per-row engines plus every used engine version (one full re-render on upgrade from older renders). `set_voice` takes an optional `engine`. Proven by a piper-narrator end-to-end build (re-render through the injected Piper fake, manifest says piper, bundle validates) and 695 green.
- Why: Smallest schema change that makes mixed-engine books renderable; the default-not-write rule keeps every existing file valid without migration.

### D-075: SW3 per-engine voices listing and audition plumbing

- Date: 2026-10-05
- Status: Accepted
- Decision: `scribe voices --engine kokoro|piper` (default kokoro; unknown is exit 1) lists and samples through the chosen engine; the UI mirrors it (`available_voices(workspace, engine)`, `GET /api/cast/voices?engine=`, sample POSTs take an optional `engine`, jobs store it and spawn `voices --sample --engine`, versioned clip cache separates by engine version string, clip serving tries each supported engine). Two deliberate semantics: Piper has no palette fallback (no pairs means an honest empty list, never Kokoro names under a piper label), and Piper ids describe as the raw `en-US` tag with gender unknown (the Kokoro prefix heuristic would mislabel them, e.g. Spanish). Frontend unchanged (calls default kokoro; engine selector is future work). Suite 706 green.
- Why: Listing and audition follow the engine without a model load anywhere on the list path; the job layer reuses the version-keyed machinery unchanged.

### D-076: SW4 mixed-engine proof (one Piper voice, Kokoro narrator)

- Date: 2026-10-05
- Status: Accepted
- Decision: SW4 proves Kokoro narrator + Piper Alice (`en_US-lessac-low`) + Kokoro Bob in one chapter: bundle validates, every shipped sentence window is non-silent in the real MP3, peak capped, both engine versions in the build log. Numbers: 32 s audio in 3.0 s wall (RTF 10.6, CPU). One Piper voice suffices: the second voice would add model-loading breadth only (lazy per-voice loading is unit-tested with fakes in SW1/SW2). License revision to D-073/D-075: the link delivered ~15 KB/s with resets (all Piper voices are 45-65 MB; ljspeech-medium stalled at 9 MB and keeps downloading in background), so the proof uses lessac locally under its research terms (gitignored, never committed or distributed) instead of waiting hours for permissive voices. Surprise: a plain `uv sync` pruned the `ui` extra a second time (108 UI tests silently skipped) — fixed with `uv sync --extra ui` and recorded durably in the repo `scribe-python` skill.
- Why: Routing across engines in one render is the whole SW4 claim; voice count is not.

### D-077: Kokoro reserved for faster devices; the tablet uses Piper

- Date: 2026-10-05
- Status: Accepted
- Decision: No Kokoro run on the Tab E. The model file alone is 330 MB with ~330 MB+ runtime on a 1.5 GB RAM 32-bit device, so the run would prove what arithmetic already says; Kokoro stays a faster-device engine (Scribe PC rendering unchanged). The tablet gate measures Piper (sherpa, lessac pack sideloaded) + System TTS only. This does NOT settle D-066: sherpa links espeak-ng statically whatever the engine, so the license review still decides the PW7 path. PW8's recommendation prefers Piper where present.
- Why: The user's call; the spike session stays focused on what can actually ship on the tablet.

### D-078: System TTS is the primary tablet engine; screen-off via file synthesis + foreground service

- Date: 2026-10-05
- Status: Accepted
- Decision: The tablet path uses the user's own System TTS (Google/Samsung voices) through `synthesizeToFile`, not live `speak()`. Screen-off works because Auloud owns playback: synthesis produces audio files (fast, exact timings) and the existing foreground service + wake lock plays them — the same service that already plays screen-off in v1. Other apps stop because they stream live utterances tied to a foreground activity; nothing in this design depends on the screen. Engine choice stands (D-065/PW8 switcher: system, piper, kokoro where viable). Consequences: (1) D-066 pressure drops for the tablet path — no sherpa bundled, no GPL code in the Player, Apache-2.0 holds; sherpa/Piper becomes the faster-device/optional path. (2) Per-character voices are impossible on the System tier; two-voice mode stays but the 804 ms switch gap needs a design answer (batch same-voice synthesis, not naive toggling). (3) Streaming vs background-render still hinges on the sustained-RTF proof (0.86x vs 2.56x split unresolved — long-sentence run decides).
- Why: The user's call; matches how they already listen, minus the screen-off limitation.

### D-079: jsoup for on-device HTML parsing (Slice 9 IN0)

- Date: 2026-10-05
- Status: Accepted
- Decision: The IN4 structure/cleaning port uses jsoup as its HTML parser for sloppy EPUB XHTML. Version floor 1.23.2 (the current release on https://jsoup.org/download, confirmed 2026-10-05). License MIT (https://jsoup.org/license; MIT License block in the published POM), compatible with the Apache-2.0 Player; recorded as a planned dependency in `docs/08-Licenses.md` section 2. minSdk 24 evidence: jsoup runs on Java 8 and up including Android (download page), with core library desugaring plus the NIO spec required in Android projects; its build validates the Java 8 and Android API 21 surfaces (animal-sniffer `check-java8-api` / `check-android21-api` in the jsoup POM), so API 24 is above the validated baseline. No Gradle dependency is added in IN0; IN4 pins `org.jsoup:jsoup` in `player/gradle/libs.versions.toml` following the existing catalog comment style.
- Why: Sloppy real-world XHTML needs a forgiving parser; jsoup is self-contained (no runtime dependencies), MIT, and the standard choice named by the Slice 9 plan (decision 8).
- Alternatives considered: platform XML pull parsing (rejected: too strict for messy EPUBs); bundling another parser (rejected: no other candidate meets the license plus minSdk bar without asking).
- Consequences / revisit when: IN4 must enable core library desugaring with the NIO spec if the app module does not already, and must confirm the pinned version keeps the Java 8 baseline (re-check the download page plus POM on upgrade). Needs device test: none for parsing itself (JVM tests cover it); import on the tablet is IN8/IN10 work.

### D-080: Sentence-splitting starting point (Slice 9 plan decision 3)

- Date: 2026-10-05
- Status: Accepted
- Decision: IN5 starts from the platform sentence iterator (`java.text.BreakIterator.getSentenceInstance` with an English locale; API 24 safe, no desugaring) plus Scribe's post-fixes exactly as written down in `docs/ingestion-rules.md` section 8 (title abbreviations and initials with quote/bracket anchors, ellipsis handling including the ellipsis-plus-quote lowercase rule, `No.`/`etc.` exclusions, quote attachment up to 3 sentences with splitting above that, unbalanced-quote logging, headings as one sentence, exact round trip with trailing spacing stored, span rebasing, sids from 1). A fuller rule-set port happens only if run-level parity numbers are poor. Sentence-boundary agreement against Scribe is reported as a percentage in `docs/test-log.md`, never pass/fail; chapters, blocks and narration/dialogue runs must match exactly.
- Why: Implements Slice 9 plan decision 3 verbatim; exact boundary parity is explicitly not required (pysbd vs platform rules differ), so starting small keeps IN5 scoped while the post-fixes carry the hard-won behavior.
- Alternatives considered: porting pysbd's full rule tables now (rejected: cost without evidence; the plan gates this on measured agreement); using BreakIterator bare with no post-fixes (rejected: abbreviation and ellipsis behavior would regress audibly).
- Consequences / revisit when: if agreement is poor, IN5 ports more of section 8 or asks; the rules document stays the reference either way.

### D-081: Storage path for imported books (Slice 9 plan decision 7)

- Date: 2026-10-05
- Status: Accepted
- Decision: Imported books live under the existing books root, resolved exactly like every other book: `BooksRootResolver.defaultBooksRoot` (shared internal `/Auloud`, created by `DefaultFolderEnsurer`, readable under the `WRITE_EXTERNAL_STORAGE` runtime permission per D-021). SAF-picked folders keep working through `RoutingBundleStorage`; import never writes outside the storage layer (`BundleStorage` interface, no `java.io.File` leakage). The book folder uses the same layout as any bundle (`manifest.json`, `source/book.epub`, `text/chNNN.json`, cover) so the library, validator and later rendering work unchanged. Import writes to a temp folder inside the books root and renames on success; cancel or failure leaves nothing behind (IN7 verifies). Book ids use a device namespace per plan decision 6 (IN1/IN7 detail), so a PC-rendered bundle of the same EPUB appears as a separate library entry.
- Why: Implements Slice 9 plan decision 7 verbatim after checking the current storage layer (`FileBundleStorage`, `SafBundleStorage` via `RoutingBundleStorage`, `BooksRootResolver`, `DefaultFolderEnsurer`): the shared-`/Auloud` default from D-021 is the existing path, so import reuses it instead of inventing storage.
- Alternatives considered: app-private files dir as the import target (rejected: splits the library across two roots and breaks the validator/library reuse the plan requires); writing directly into the final folder (rejected: crash or cancel would leave partial books).
- Consequences / revisit when: needs device test (IN10: import from tablet storage, duplicate detection, corrupt/DRM failure leaving nothing). Revisit only if the tablet denies the write permission or SAF trees need a different temp-rename mechanism.

### D-082: Spec 2.0 part 1 - unrendered books (Slice 9 IN1)

- Date: 2026-10-06
- Status: Accepted
- Decision: The bundle gains unrendered books (text plus sentence structure, no audio, no timings) as spec 2.0, implementing Slice 9 plan decisions 4 and 5. A `render_state` field (`none`, `partial`, `complete`) makes the manifest `audio` object, per-chapter `audio`/`duration_ms` and per-sentence `start_ms`/`end_ms` conditional (present for rendered chapters, absent never null otherwise); per-chapter state is inferred from `duration_ms` presence and must agree with `render_state`. Every 2.0 sentence uses the reserved speakers `narrator` or `dialogue`, and `voices` always contains both keys (placeholder engine/voice values until Slice 10 rendering). Required fields becoming conditional makes this a major version: readers accept `1.0`, `1.1`, `1.2` and `2.0`; Scribe keeps writing `1.1`/`1.2` for fully rendered PC books and `2.0` only for unrendered or partially rendered books. A 1.x reader refuses a 2.0 bundle at the version check naming the file and the rule, never reaching the missing-audio paths. Progress for unrendered books is chapter index plus sentence sid (no milliseconds); the Player stores it in a new nullable `sentenceSid` column on `progress` (null for all ms-based positions) via a real Room v1 to v2 migration with exported schema. Book ids for device-imported books use a device namespace (UUIDv5 over `auloud:device-book:<sha256-hex>`) so a PC-rendered bundle of the same EPUB appears as a separate library entry. The Scribe bundle writer is unchanged (it writes rendered 1.x chapters only; unrendered chapters are written on the device from IN7 on). Both validators agree on the shared `spec/fixtures/unrendered-golden/` fixture (tiny original text, 2 chapters, 7 sentences). No new dependency on either side.
- Why: Spec text first, then validators, then fixtures per the sanctioned change rule; the conditional-fields shape keeps 1.x bundles byte-valid while giving IN3-IN7 a target to write and IN9 a reader target.
- Alternatives considered: minor version with optional fields (rejected: required fields becoming conditional is breaking by definition, and old Players must fail loudly instead of choking on missing audio); reusing `position_ms` with 0 for unrendered progress (rejected: 0 is a real position in rendered books, so a separate sid column is unambiguous); PC-writer support for unrendered chapters now (rejected: nothing on the PC needs it, and the writer gate stays rendered-only).
- Consequences / revisit when: the Room migration cannot run on the JVM here, so it needs the on-device check (fresh install plus upgrade over an existing v1 library with saved ms positions intact). The sid reader lands in IN9 and sid-to-ms conversion on render in Slice 10; revisit the placeholder voice values then.

### D-083: IN2 shared ingestion test data contract (Slice 9 IN2)

- Date: 2026-10-06
- Status: Accepted
- Decision: Scribe exports the Slice 9 shared test data with a dev command, `scribe export-ingest-fixtures` (library logic in `scribe/export_ingest.py`, thin CLI wrapper in `cli.py`, shipped via a new `force-include` entry; no new dependency). Dialogue vectors live in `spec/fixtures/dialogue-cases.json` (23 cases: id, paragraphs/chapter scope, input paragraphs, expected runs with kind plus text, notes on the rule exercised; adjacent same-kind sentences merged by concatenation, per-chapter-end flip for chapter scope). EPUB parity lives in `spec/fixtures/ingest-parity/<fixture>.json` (chapters with titles and word counts, blocks with type and text, merged runs on para/quote blocks) plus `sources.json` (covered/skipped/out-of-scope with sha256 and reason) and a hand-written README. Scribe's `tests/test_dialogue.py` consumes the vectors (parametrized harness plus vector-backed mechanics tests); `tests/test_export_ingest.py` pins double-run determinism and freshness against the committed files. Covered now: scribe-golden, partial-golden, multivoice-golden; unrendered-golden is recorded skipped (IN1 placeholder bytes, sha cross-checked); pdf-golden is out-of-scope (EPUB-only slice).
- Why: Implements Slice 9 plan IN2 verbatim (plan decision 2 sets the chapters/blocks/runs parity level); the JSON files are the IN6 Kotlin contract, and discovery is automatic so later golden EPUBs need no code change.
- Alternatives considered: dev-only script under `scribe/dev/` with no CLI wiring (rejected: the brief asks for a dev command under the library-plus-thin-wrapper rule, and a named CLI subcommand is what IN6 reruns); hand-pinning expected runs in the module (rejected: the real splitter computes them at export, and the committed JSON plus freshness test pin them afterwards).
- Consequences / revisit when: the installed `scribe` console script is a stale copy until the next `uv sync --extra ui` reinstall (verified via CliRunner on the source tree instead); bump `DIALOGUE_CASES_VERSION` if the vector schema ever changes; revisit coverage when the C&P/Alice goldens land (IN10).

### D-084: IN3 EPUB container limits and lookup order (Slice 9 IN3)

- Date: 2026-10-06
- Status: Accepted
- Decision: The on-device container reader caps uncompressed sizes at 32 MiB per entry and 256 MiB total; entry names with `..`, absolute paths or drive/UNC forms are rejected; `META-INF/encryption.xml` refuses the book as DRM. TOC merges nav first then NCX (first-wins per lowercase basename without fragment, so nav wins on conflict). Cover lookup mirrors Scribe's writer (OPF `cover` meta id, then `cover-image` properties, then first image; JPEG magic `FF D8` only, else no cover). Title falls back to the file stem, author and language to null; only a present non-`en` language warns. Spine keeps non-linear and nav items with flags (IN4 skips them); dangling idrefs skip with a warning. No new dependency (platform XML parser).
- Why: Implements plan decision 9 with values that fit real novels (chapters are KBs, covers under 5 MB) while stopping zip bombs on the 1.5 GB tablet; the orders copy Scribe's writer and TOC behavior so IN4 parity starts from the same titles and covers.
- Alternatives considered: 8 MiB per-entry like the text JSON cap (rejected: illustrated EPUB images routinely exceed it); total 1 GB (rejected: too close to device RAM); NCX-first merge (rejected: nav is authoritative on EPUB 3); failing on missing cover (rejected: Scribe treats absent cover as normal, and goldens ship none).
- Consequences / revisit when: revisit caps only with real-book evidence of larger images; needs device test: none for parsing itself (30 JVM tests cover it); import on the tablet is IN8/IN10 work.

### D-085: IN4 desugaring for the jsoup pin (Slice 9 IN4)

- Date: 2026-10-06
- Status: Accepted
- Decision: Enable core library desugaring with the NIO spec in `player/app/build.gradle.kts` (`isCoreLibraryDesugaringEnabled = true`) via `com.android.tools:desugar_jdk_libs_nio:2.1.5` (latest 2.1.x on Google Maven, 2026-10-06) on the `coreLibraryDesugaring` configuration only. The artifact is GPL-2.0 with the Classpath Exception per its POM; the exception permits bundling desugared classes into the Apache-2.0 Player, so the Player license is unchanged (recorded in `docs/08-Licenses.md`). IN4's own Kotlin uses only APIs present on API 24 (`java.text`, regex, `java.util`), so desugaring serves jsoup internals only. No other new dependency.
- Why: jsoup's download page mandates desugaring with the NIO spec on Android, and its 1.23.2 POM marks `java.nio.file` and related APIs as desugar-provided (animal-sniffer ignores). The NIO variant is used instead of the base artifact because jsoup references those APIs. Build-time check (assemble + unit tests green) is the minSdk compatibility evidence available here.
- Alternatives considered: base `desugar_jdk_libs` without NIO (rejected: leaves the exact APIs jsoup names undesugared); no desugaring, relying on jsoup's parse path never loading NIO classes (rejected: contradicts jsoup's own requirement, unverifiable without the tablet).
- Consequences / revisit when: needs device test: import on the Tab E (IN10) proves no `NoClassDefFoundError` at runtime. Revisit the pin only if a jsoup upgrade re-check (download page plus POM) changes the baseline.
### D-086: IN5 sentence-boundary agreement verdict (Slice 9 IN5)

- Date: 2026-10-06
- Status: Accepted
- Decision: Keep the D-080 rule set (platform sentence iterator plus Scribe post-fixes, no fuller port). Agreement with Scribe on the golden books is 15/15 paragraphs exact (100.0%), 161/161 sentences identical, recorded in `docs/test-log.md`. Two inherited defects were fixed to reach it: (1) the ported `ABBREV_RE`/`INITIAL_RE` ended in a literal dollar instead of the end anchor, so no abbreviation or initial merge ever fired (existing tests passed only on BreakIterator native behavior); (2) single-quoted chunks need active glue (`singleRegions` with the rule 9.2 apostrophe guard, always attach, no 3-sentence split) because pysbd never splits inside singles while BreakIterator does. `IngestSentence` carries text plus spans only (no kind/speaker); IN6 tags dialogue in its own layer keyed by sid. Note: the brief said `docs/test-log.md` exists, but only `docs/archive/v1/test-log.md` did, so IN5 created `docs/test-log.md` for v2 entries and left the archive untouched.
- Why: The agreement bar from the brief (<90% triggers more rules) is met on the measured corpus, and the synthetic suite (41 tests, mirroring Scribe `test_sentences.py`) passes, so a fuller port would add cost without evidence.
- Alternatives considered: weakening the initials/singles tests to BreakIterator native behavior (rejected: both behaviors are explicit spec rules 8.2/8.5 with Scribe tests behind them); porting fuller pysbd tables now (rejected: nothing left to gain on current evidence).
- Consequences / revisit when: the corpus is small (15 paragraphs, no quote blocks in the goldens) and was measured with JDK 21 BreakIterator tables, not Android ICU, so IN10 re-checks agreement on the tablet with real books (C&P/Alice). If real-book agreement is poor, port more of section 8 then.

### D-087: IN6 dialogue port decisions (Slice 9 IN6)

- Date: 2026-10-06
- Status: Accepted
- Decision: (1) `DialogueTagger.tagChapter` rebuilds each paragraph by joining IN5 sentences (exact per the IN5 round-trip guarantee) and re-splits every quote segment with `SentenceSplitter` (reused, never forked), mirroring Scribe's rebuild-by-join (rule 1). Output is kind plus the reserved 2.0 speaker per sentence (`narrator`/`dialogue`), paragraph-local `splitPair` ids, per-paragraph quote numbers with `continued` flags, and sids renumbered 1..N across the chapter. (2) The chapter-end flip also resets speaker to `narrator`: Scribe resets kind/quote/continued only because its speaker field is still undifferentiated at that stage, while here kind and speaker are bound (2.0 allows only the two reserved keys), so a flipped sentence must not keep the `dialogue` speaker. (3) No vector-vs-rules conflict: all 23 vectors agree with rules 9.1-9.11 as written (mode select, apostrophes, inch marks, continuation, scare, pairs, orphans, spacing), so no conflict record was needed. (4) Warnings (unbalanced/stray/unclosed keywords kept for Scribe greppability, plus chapter/block context) go to a `warnings` list for the IN7 import report instead of a logger.
- Why: keeps the port behavior-identical at the run level (16/16 golden runs exact, recorded in `docs/test-log.md`) while fitting the 2.0 bundle shape (speaker is the kind encoding; IN7 drops quote numbers and split pairs when writing, Slice 10 reuses split pairs for tag pauses).
- Alternatives considered: tagging IN5 sentences in place without re-splitting (rejected: mixed sentences must split at quote boundaries, which is finer than sentence boundaries); forking a no-glue sentence splitter for dialogue segments to match Scribe sentence counts inside single-quoted regions (rejected: acceptance is run-level per plan decision 2, and the glued single sentence produces identical runs).
- Consequences / revisit when: IN7 consumes `ChapterDialogue` for the writer; IN10 re-checks run agreement on device with real books. Revisit the singles-glue sentence-count difference only if Slice 10 rendering needs Scribe-identical sentence splits.

### D-088: IN7 import pipeline and writer design (Slice 9 IN7)

- Date: 2026-10-06
- Status: Accepted
- Decision: (1) Storage writes go through `BundleStorage` (D-081) via five new ops (`writeBytes`, `writeText`, `copySourceFile`, `movePath`, `deleteRecursively`) with read-only defaults so every existing fake and the SAF branch compile untouched; only `FileBundleStorage` implements them and `RoutingBundleStorage` routes file paths there while refusing SAF writes with a clear message (import targets are always file folders under the books root). (2) Final folder is `<booksRoot>/<device-id>`, temp is `<booksRoot>/.tmp-<id>`; the manifest is written last, a stale temp from a crashed process is deleted before writing, and every failure or cancel deletes the temp (plus the final folder when the rename already happened). (3) Duplicate detection scans already-imported manifests for the same source hash BEFORE chapter work (no repository change needed); the library upsert reuses `LibraryRepository.importBundle` after the rename, and an upsert failure deletes the final folder. (4) Device ids are UUIDv5 over `auloud:device-book:<sha>` in `NAMESPACE_URL` (Scribe parity, golden-pinned), so PC and device imports of one EPUB stay separate rows. (5) Progress is one suspend callback per written chapter; cancellation is cooperative between chapters via `ensureActive` and stays a thrown `CancellationException` (never a `Failed` outcome); elapsed time and `created_at` come from an injected clock. (6) The temp folder self-validates with IN1's `BundleValidator` plus chapter parsing before the rename, so only conforming books are ever renamed.
- Why: keeps all writes inside the existing storage seam (fakeable in JVM tests, no parallel layer), makes re-imports land on the deterministic path the hash scan finds, and proves IN1 conformance at runtime as well as in tests.
- Alternatives considered: a separate file-ops seam beside `BundleStorage` (rejected: a parallel storage layer, which the brief forbids); title-based folder names (rejected: collisions and sanitizing bugs; UUID folders are ugly but unique); duplicate lookup via a new repository getter (rejected: the manifest scan uses existing seams and also covers folders the library forgot); writing the manifest first (rejected: a manifest-first temp looks like a book to rescan).
- Consequences / revisit when: IN8 drives `IngestPipeline.importEpub` from the picker and forwards progress to Main (callbacks run on IO); concurrent imports of the SAME book fail at the rename with a shaped error; a process kill between manifest write and rename can leave a `.tmp-` folder that the next same-book import deletes but rescan would otherwise list. Needs device test (IN10): real import time, duplicate UX, corrupt/DRM failure leaving nothing.

### D-089: IN8 import UI, sweep, serialization, chip, delete (Slice 9 IN8)

- Date: 2026-10-06
- Status: Accepted
- Decision: (1) The picker uses `OpenDocument` (`application/epub+zip`, no permission); the activity streams the document into a cache scratch file the `ImportViewModel` owns and deletes on every terminal path, then drives `IngestPipeline.importEpub` with per-chapter progress forwarded through `StateFlow` and cancel wired to the job (cancel stays a `Cancelled` state, never `Failed`). A success dismiss triggers one rescan so the new row plus chip appear. (2) Failure UX is a friendly headline per class (DRM, corrupt, too-large, empty, race-loser, unreadable pick, generic) with the pipeline file-plus-rule strings kept verbatim in the details, so the rule token survives for support; the rename-refusal loser string is never rewritten. (3) Startup sweep runs inside the library rescan (which the launch, every manual rescan and the permission grant all trigger): each watch root sweeps `.tmp-*` via a new defaulted `BundleStorage.listStrayTempDirs` op (file branch lists, SAF yields empty) before listing, and the import loop defensively skips stray names so a temp born mid-rescan is never read as a book. (4) Same-book serialization lives in the pipeline (`ImportLocks` per-hash mutex around scan-through-upsert): a concurrent same-book import waits and then reports `Duplicate`; different books still run concurrently at the pipeline level, while the import screen itself is single-flight. (5) The "Not rendered" chip derives from an in-memory book-id to `render_state` map filled by each rescan (`none`/`partial` chip, `complete`/1.x do not), not from a new DB column, so it stays fully JVM-testable and needs no migration. (6) No existing delete path existed (verified by search), so delete is new: `LibraryRepository.deleteBook` (folder via storage, then row; unknown id fails shaped) plus `ProgressRepository.delete` (no orphan for a later re-import to resurrect), confirmed in the dialog.
- Why: keeps the UI thin (state machine in `ImportViewModel`, screens render state), every seam fakeable on the JVM, and every choice follows an existing pattern (VoiceAudition plain-class VM, library `ImportError` verbatim convention, IN7 defaulted storage ops).
- Alternatives considered: sweep in `MainActivity.onCreate` before the ViewModel exists (rejected: races the init-triggered rescan and runs only once per process, while rescan self-heals on every scan); `render_state` as a new `BookEntity` column with a v2 to v3 migration (rejected: migrations cannot run on the JVM here, so the chip would be unverifiable until a device test); serialization only in the ViewModel (rejected: direct pipeline callers would still race, and the ViewModel would double-hash the file).
- Consequences / revisit when: the import screen is single-flight (a second pick while running gets a notice); after Slice 10 starts rewriting `render_state`, the in-memory map must still be refreshed by rescan or the chip goes stale. Needs device test (IN10, not claimed): picker behavior on the Tab E, import timing, duplicate/corrupt/DRM screens, chip legibility, delete confirm flow.

### D-090: IN9 read-only reader and playback gate (Slice 9 IN9)

- Date: 2026-10-06
- Status: Accepted
- Decision: (1) Playback gate: `PlaybackQueue` gains `isRenderedChapter` (non-blank audio plus positive duration), `gateFor` (Playable only when every chapter is rendered; `none`, `partial`, empty and invalid all NeedRender) and `buildPlayable` (rendered chapters only, identical to `build` for fully rendered books). `PlaybackService.prepareBook` refuses NeedRender shaped (`manifest.json: book has no playable audio (render audio to listen)`) before any `MediaItem` is built, so no ExoPlayer item is ever built from an empty path. (2) Sid progress: `ProgressRepository.save` gains an optional `sentenceSid` (null by default, so every existing caller is unchanged); unrendered saves write `positionMs` 0 plus the 1-based sid, rendered saves keep null (and clear a previous sid on overwrite). The sid reader restores chapter plus sid with `restoreReadingSid` (saved sid kept when present, else the chapter start; ms-only rows and out-of-range chapters fall back the same way). (3) Read-only routing: `BookScreen` reads the manifest `render_state` once with the chapter list (the library chip map is only the pre-load hint, the manifest is truth) and shows `UnrenderedBookScreen` when listening is unavailable (`isListenAvailable`: null and `complete` listen, everything else reads). `partial` books stay read-only in IN9; Slice 10 may enable subset playback. The stored global mode is never written by the read-only screen. (4) The read-only screen owns an `UnrenderedReaderViewModel` (no service, no controller, no ticker, no `SentenceIndex`): one chapter JSON at a time, jumps/taps/scroll-settle move and save the sid, follow reuses the shared machine, failures never overwrite the last good position. Taps move directly (no audio to lose, so no confirm prompt). (5) Dialogue marking: `dialogue` speakers draw in `MaterialTheme.colorScheme.tertiary` on `para`/`quote` blocks (headings keep the headline style), off via a persisted `dialogueMarking` setting (default on) next to the other reading prefs. 1.x books carry no `dialogue` speakers, so they render exactly as before. (6) The shared mode switcher gains disabled Listen modes plus the verbatim `render audio to listen` hint (defaults keep the rendered row identical).
- Why: the binding IN1-review obligation (gate every playback entry, prove zero audio items from the golden) plus the IN9 verify (state, sid save/restore, mode gating) with no rendered-book behavior change and no new dependency, permission or manifest change.
- Alternatives considered: filtering the queue to the rendered subset and playing partial books now (rejected: playlist positions would no longer match manifest chapter indices, so saved progress could land on the wrong chapter; refusing is honest until Slice 10 owns partial playback); a separate sid-only progress method instead of the optional param (rejected: two save paths could drift, and the default keeps every existing call site compiling identically); reusing `ReaderViewModel` with a playback flow for unrendered books (rejected: there is no audio position to mirror, and `SentenceIndex` skips untimed sentences by design, so a sid-native ViewModel is smaller and cannot run timed code by mistake).
- Consequences / revisit when: rendered 2.0 `complete` books play through the gated path (proven identical to `build` by test); Slice 10 converts sid progress to ms on render and refreshes the chip map by rescan. Needs device test (IN10, not claimed): dialogue color legibility on the Tab E in light and dark, read-only opening from the library, close/reopen restore, chapter list jumps, the listen-unavailable hint, and that existing rendered books still play with read-along.

### D-091: RN0-1 voice-batched, spooled rendering (Slice 10 plan decision 1)

- Date: 2026-10-06
- Status: Proposed (pending RN4/RN5 verification)
- Decision: Per chapter, render in voice passes (all narrator sentences, then all dialogue sentences) and assemble in reading order from spool files keyed by chapter, sentence id and voice fingerprint; the spool gives sentence-level resume and flat memory.
- Why: System TTS pays about 800 ms per voice switch (measured), so naive toggling wastes minutes per dialogue-heavy chapter; one model at a time also fits the 415 MB two-Piper-model budget.
- Alternatives considered: in-order synthesis with per-sentence voice switching (rejected: switch cost dominates at dialogue density); holding all sentence audio in memory (rejected: breaks the 1.5 GB budget on long chapters).
- Consequences / revisit when: spool adds disk IO (about 86 MB per 30 min) plus stale-spool cleanup; Scribe's `assemble_chapter` synthesizes in document order today, so the port must separate synth order from assembly order without moving tag-pause boundaries. Overturn if the beep/self-check (RN10) shows switch cost is negligible on the System path, or if spool bookkeeping proves flakier than the resume it buys.

### D-092: RN0-2 AAC in M4A for device renders (Slice 10 plan decision 2)

- Date: 2026-10-06
- Status: Proposed (pending RN6 device encode plus RN1 spec amendment)
- Decision: Device-rendered chapters are AAC-LC mono 24 kHz about 64 kbps in M4A via the platform encoder and muxer; needs spec 2.0 part 2 (RN1).
- Why: Android 7.1.1 ships an AAC encoder but no MP3 encoder; shipping one would mean extra native code plus a license review (see D-068, still Proposed).
- Alternatives considered: MP3 via bundled native code (rejected: native weight plus license surface for parity nobody needs); Opus (rejected: weaker platform support on API 24 for the muxer path).
- Consequences / revisit when: encoder delay/drift may force a looser sync tolerance than 50 ms (decision 3 measures it); old 1.x Players will refuse 2.0 AAC books at the version gate by design. Overturn if the platform encoder is missing or misbehaves on the Tab E (RN6 reports a clear error then).

### D-093: RN0-3 encoder delay is measured, not assumed (Slice 10 plan decision 3)

- Date: 2026-10-06
- Status: Proposed (pending RN10 measurement)
- Decision: A debug beep engine renders tones at known positions; the measured offset is stored as a constant, recorded in the manifest as `encoder_offset_ms`, applied when timings are written, and players need no change; AAC sync tolerance is decided from that data (may loosen from 50 ms).
- Why: Assuming zero delay risks a systematic highlight lag on every device-rendered chapter; measuring once per encoder turns it into a constant.
- Alternatives considered: assuming zero offset (rejected: unproven on the platform AAC path); per-chapter measurement (rejected: wasteful once the encoder behavior is known constant).
- Consequences / revisit when: RN1 writes the tolerance rule from the measured number; if drift (not just offset) appears, per-chapter or per-file correction replaces the constant. Overturn if the beep chapter shows offset 0 within the existing 50 ms tolerance (then record 0 and keep the tolerance).

### D-094: RN0-4 device timing rules identical to Scribe (Slice 10 plan decision 4)

- Date: 2026-10-06
- Status: Proposed (pending RN2 vectors plus RN5 verification; Scribe side already Accepted in D-025)
- Decision: Pauses 250 ms sentence, 500 ms paragraph, 800 ms heading, 1000 ms scene break, 100 ms dialogue tag split; `start_ms`/`end_ms` from sample counts; `end_ms` excludes the pause; first start is 0. Verified with shared vectors exported from Scribe's assembly tests (RN2).
- Why: Identical rules keep read-along, validation and PC/device bundles on one contract; Scribe's `scribe/audio/assemble.py` plus `tests/test_assemble_encode.py` and `tests/test_mv7.py` already pin the semantics.
- Alternatives considered: device-specific pauses (rejected: two contracts to test, audible inconsistency between PC and device chapters).
- Consequences / revisit when: RN2 exports the vectors and Scribe tests consume them too; the Kotlin port must reproduce them exactly, including leading-break silence drop and split-pair tag detection (recomputed via `DialogueTagger`; the bundle carries no split-pair fields by design). Overturn only with listening evidence that a pause value sounds wrong on device (then change both sides together, not one).

### D-095: RN0-5 book-level gain per role plus per-chapter peak cap (Slice 10 plan decision 5)

- Date: 2026-10-06
- Status: Proposed (pending RN4/RN5 verification; builds on Accepted D-037 peak leveling)
- Decision: One gain per role derived from the first rendered chapter, stored in the manifest, plus a per-chapter peak limit at assembly, so chapters rendered on different nights do not jump in loudness.
- Why: Scribe already equalizes peaks per voice at assembly (D-037, deterministic numpy gain, durations unchanged); the book-level gain extends the same metric across nights without re-rendering finished chapters.
- Alternatives considered: per-chapter-only leveling (rejected: night-to-night voice drift stays audible); RMS/LUFS leveling (rejected per D-037: version-dependent filters, silence-weighting ambiguity; peak matching is proven good enough by ear for v1).
- Consequences / revisit when: the first chapter sets the reference for the whole book, so a bad first chapter (odd levels) biases everything after it; RN4 measures per-role levels during the spool passes. Overturn if listening tests report uneven loudness across nights (then revisit reference selection or the metric itself).

### D-096: RN0-6 crash-safe write order with temp-then-rename (Slice 10 plan decision 6)

- Date: 2026-10-06
- Status: Proposed (pending RN7 crash-simulation tests)
- Decision: Per chapter: audio file, then timed text JSON, then the manifest update; each written to temp and renamed; a startup recovery step cleans orphan temps and repairs half-finished chapters.
- Why: Ordered writes plus atomic renames bound crash damage to the newest chapter; readers never see a manifest entry without its audio and JSON on disk (same shape as Scribe's cache atomicity and the IN7 temp-then-rename import).
- Alternatives considered: manifest-first (rejected: a manifest-first crash looks like a book to rescan, the IN7 lesson); in-place overwrite (rejected: torn files on kill).
- Consequences / revisit when: RN7 simulates a crash at each step; recovery must also handle kills between manifest write and rename. Overturn only if crash tests show a step where ordering cannot protect the reader (then add the missing guard, do not drop ordering).

### D-097: RN0-7 render job state in a small file per book, not the database (Slice 10 plan decision 7)

- Date: 2026-10-06
- Status: Proposed (pending RN3 verification)
- Decision: One small atomically-written state file per book holds the render job (states: queued, running, paused, done, failed, cancelled, interrupted for process death, resumable); no Room migration.
- Why: Avoids another Room migration (which cannot run on the JVM here, the IN1 lesson) and keeps the render queue testable as pure file logic; the Scribe UI job store (`scribe/ui/jobs.py`, one folder per job with sentinel pause) proves the shape works.
- Alternatives considered: a new Room table (rejected: migration is unverifiable until a device test; the state is a machine-local queue, not relational data).
- Consequences / revisit when: strongest counter-argument found in review: the library chip map is already an in-memory projection that goes stale when files change under it (D-089 note), so rescan must refresh render chips after Slice 10 rewrites `render_state`, and file and DB can disagree mid-render. State-file timestamps use epoch millis (`System.currentTimeMillis`), never `java.time` (unavailable on API 24 without desugaring). Overturn if concurrent writers corrupt the file despite atomic writes (then serialize through the single render service, which already owns the single-running-job rule).

### D-098: RN0-8 render-ahead from the reading position (Slice 10 plan decision 8)

- Date: 2026-10-06
- Status: Proposed (pending RN3 planner tests)
- Decision: The planner starts at the chapter containing the reading position, then continues forward; options are next N chapters (default a few hours of audio), whole book, or from here; while charging the window keeps rolling as you read.
- Why: At about 1x System TTS a full book needs a night per 10 hours, so rendering ahead of the reader (not chapter 1) is what makes the book listenable tomorrow; the SysLong verdict is explicitly not needed for this slice.
- Alternatives considered: always chapter-1 order (rejected: a reader at chapter 20 waits through 19 re-renders of already-heard audio); whole-book-only (rejected: no partial payoff on slow engines like Piper 0.36x).
- Consequences / revisit when: RN3 pins ordering from different positions plus the rolling window in JVM tests; planner inputs are chapter index plus saved sid/ms, never wall-clock assumptions (`java.time` forbidden, epoch millis only). Overturn if acceptance shows readers mostly restart books (D-028 restart-from-1 behavior) rather than continue (then default to whole-book).

### D-099: RN0-9 explicit chapter-to-media-item mapping for partial books (Slice 10 plan decision 9)

- Date: 2026-10-06
- Status: Proposed (pending RN7 mapping tests; builds on the Accepted IN9 gate in D-090)
- Decision: Rendered chapters may be non-contiguous, so playlist position is not chapter index: a tested mapping type converts between them; progress stays keyed by manifest chapter; Listen and Read plus listen enable per chapter; playback stops with a clear message at the end of the rendered portion.
- Why: `PlaybackQueue.buildPlayable` today uses the filtered position as `chapterIndex`, which is harmless only because the service refuses partial books first (issue #14 item 3 warns exactly against reusing it for partial playback); saved progress in chapter coordinates must never land on the wrong chapter after filtering.
- Alternatives considered: reusing `buildPlayable` positions as chapter indices (rejected: #14 item 3, wrong-chapter progress on sparse books); renumbering progress on render (rejected: destroys the stable chapter key the spec guarantees).
- Consequences / revisit when: RN7 absorbs #14 items 1-3 (deprecate or delegate ungated `build`, add the refused-prepareBook test, keep the warning attached); saved sid progress converts to ms from the new timings; rendered 1.x books keep the identical-to-`build` path. Overturn only if sparse chapters prove unusable in practice (then restrict rendering to contiguous-from-position windows instead of weakening the mapping).

### D-100: RN0-10 per-chapter render fingerprint (Slice 10 plan decision 10)

- Date: 2026-10-06
- Status: Proposed (pending RN1 spec text plus RN4/RN7 verification)
- Decision: Each rendered chapter stores a fingerprint (engine, voice ids, speeds, engine versions) so Slice 11 can find stale chapters after a voice change and re-render only those.
- Why: Scribe's sentence cache already keys on (text, voice, speed, pitch, engine version) for exactly this invalidation reason; the chapter fingerprint is the same idea one level up, and the two-voice collapse rule (D-071) makes the key small.
- Alternatives considered: no fingerprint, re-render all on any voice change (rejected: wastes nights); fingerprint per sentence (rejected: chapter granularity matches the render and delete unit).
- Consequences / revisit when: RN1 defines the field shape (`render_fingerprint`, optional, ignored by old readers); a fingerprint mismatch invalidates spool files (RN4) and marks the chapter stale (Slice 11). Overturn if the fingerprint ever fails to change when audio would (then it is a correctness bug, widen the key).

### D-101: RN0-11 rendering safety guards (Slice 10 plan decision 11)

- Date: 2026-10-06
- Status: Proposed (pending RN8 policy tests plus device tuning)
- Decision: Pause when the charger unplugs (if charging-only is on), pause above a battery-temperature limit and resume when cooler, pause on low storage; the pre-start estimate shows audio length, size and time from the benchmark RTF.
- Why: Overnight renders on a 1.5 GB tablet must not cook the battery, die mid-chapter, or fill the disk; estimates come from measured RTF ranges (System TTS varied 2.56x to 0.86x run to run), so they are shown as ranges.
- Alternatives considered: no guards, render unconditionally (rejected: the soak risk table names heat, battery and storage explicitly); hard temperature/storage constants (rejected: defaults need tuning from device numbers in acceptance).
- Consequences / revisit when: RN8 tests the policy classes with injected inputs on the JVM; the service owns charger/temperature/storage signals behind thin seams; all thresholds read from settings with defaults, never `java.time` schedules. Overturn a default only with measured device numbers (RN11 acceptance), not with emulator guesses.

### D-102: RN1 spec 2.0 part 2 - device-rendering contract (Slice 10 RN1)

- Date: 2026-10-06
- Status: Accepted
- Decision: Finalize the Slice 10 plan Appendix A draft into the real spec (docs/03-BundleSpec.md mirrored byte-identical to spec/bundle.md): AAC-LC mono 24 kHz about 64 kbps in M4A as a second audio entry (.m4a chapter files, per-chapter format from the extension, mixed MP3/M4A books legal); provisional 50 ms AAC duration tolerance; optional per-chapter render_fingerprint (engine, voices, speeds, engine_versions); optional manifest gain_db per role in decibels; optional manifest encoder_offset_ms integer; partial semantics plus playlist/progress rules at spec level only (stable chapter index key, bundle order is playlist order, unrendered chapters have no media item). Scribe validator plus inspect and Player validator plus loader (BundleModels) accept device-rendered bundles; shared fixtures spec/fixtures/rendered-aac-golden/ (2.0 complete, real ffmpeg sine-tone M4A) and spec/fixtures/partial-aac-golden/ (2.0 partial, one M4A chapter) built by the new scribe export-render-fixtures dev command; Scribe 30 new part-2 tests and Player 19 new tests, full suites green.
- Why: RN1 verify (golden validates, partial book loads, invalid combinations rejected with file and rule) needs the contract text first, then validators, then fixtures per the sanctioned change rule; the additive-inside-2.0 shape keeps every 1.x bundle byte-valid and every Scribe MP3 book loading untouched.
- RN0 open questions resolved (one line each): gain name/unit is gain_db in decibels per role (readers ignore it, loudness is baked into the audio); AAC bitrate wording is constrained-average (encoder runs constrained targeting 64k, validator checks the average within 16 kbps and never frame sizes); version stays 2.0 with no bump (part 2 is additive, readers accept the same four versions); M4A extension is lowercase .m4a with probe rules codec aac plus mp4/m4a container plus shared channel/rate params; mixed MP3/M4A books are legal with the manifest format matching at least one rendered chapter and per-chapter extension plus probe authoritative.
- Alternatives considered: minor version bump for part 2 (rejected: nothing required becomes conditional, old 2.0 readers skip the new fields by the ignore-unknown-keys rule); manifest format normative per chapter (rejected: unenforceable in mixed books); MP3-tight bitrate band for AAC (rejected: measured tone average 61.5k at the 64k setting, so the band is 16 kbps); nominal-vs-probed fixture durations (nominal chosen so the JSON is byte-stable and the export fails loudly when the probe disagrees).
- Consequences / revisit when: provisional values RN6/RN10 will confirm (AAC 50 ms tolerance and whether drift needs more than a constant, encoder_offset_ms real Tab E constant replacing the fixture 0, gain_db derivation magnitudes replacing the illustrative fixture values, fingerprint engine version strings replacing the placeholder); the installed scribe console script is stale until the next uv sync reinstall (verified via CliRunner plus python export_render.py on the source tree, the D-054 precedent); fixture M4A files are desktop-ffmpeg stand-ins, so the first real Tab E encode plus beep chapter may still move the tolerance.
- Provisional values (RN10 confirms or loosens): AAC duration tolerance 50 ms (AAC_DURATION_TOLERANCE_MS, same number as MP3, flagged provisional in spec and code); AAC average-bitrate band 16 kbps (AAC_BITRATE_TOLERANCE_BPS); fixture encoder_offset_ms 0, fixture gains (-1.5/0.5 rendered, -1.0/0.0 partial), placeholder engine version string.

### D-103: RN2 shared assembly-timing vectors (Slice 10 RN2)

- Date: 2026-10-06
- Status: Accepted
- Decision: Scribe exports the shared timing vectors with a dev command, `scribe export-timing-vectors` (library logic in `scribe/export_timing.py`, thin CLI wrapper in `cli.py`, shipped via a new `force-include` entry; no new dependency). Vectors live in `spec/fixtures/timing-vectors/timing-vectors.json` (10 cases: id, notes, blocks with sentence kind plus native rate plus sample length plus split-pair link, expected start_ms/end_ms plus pause_after_ms per sentence, chapter duration_ms plus sample_count) plus a hand-shaped README. Committed `pauses_ms` are stamped from the live `audio.assemble` constants (250/500/800/1000/100). Scribe's `tests/test_timing_vectors.py` (28 tests) replays every vector through the real `resample_mono` plus `assemble_chapter` and pins double-run determinism plus freshness against the committed files. Full Scribe suite green (801 passed, 3 deselected, `-m "not slow"`).
- Why: Implements Slice 10 plan RN2 verbatim (D-094 needs identical timing rules on the device); the JSON files are the RN5 Kotlin contract, and the Scribe consumer test proves the vectors match verified code instead of hand-written guesses.
- Vector format: `version` 1 plus `conventions` plus `bundle_rate_hz` 24000 plus `pauses_ms` plus `cases`. Per sentence: `sid`, `kind` (narration/dialogue), documentary `speaker`, native `sample_rate` plus `samples`, committed `resampled_samples` at 24000 Hz, `split_pair` (int or null). Per expected timing: `sid`, `start_ms`, `end_ms`, `pause_after_ms` (gap to the next start, or to duration for the last sentence; composite across a block-final pause plus any following break silence). All ms, sample and rate fields are integers; no floats, no timestamps.
- Coverage: sentence-pause (250), para-final (500; end excludes pause, duration includes it), heading-pause (800), quote-like-para (quote pauses exactly like para), break-mid and break-trailing (1000 kept), leading-break-drop (first start 0), tag-split (100 between split-pair halves, normal pauses elsewhere), mixed-rates (16000/22050/44100/8000 natives land on exact 500 ms each), rounding-half-even (exact .5 ms boundaries at 100.5/350.5/450.5/950.5 round to 100/350/450/950).
- Determinism guarantees: fixed native lengths and rates, constant audio (values never affect timings), canonical JSON via the shared `export_ingest` helpers (sorted keys, 2-space indent, UTF-8, trailing newline); double export is byte-identical (SHA256 EFD1B448 for the JSON and F84E308D for the README across two runs, committed files hash-identical to a fresh export).
- Alternatives considered: hand-pinning expected timings in the module (rejected: the real assembly computes them at export, and the committed JSON plus freshness test pin them afterwards); a separate JSON file per case (rejected: one 13 KB file plus README matches the dialogue-cases precedent and stays reviewable); committing sentence texts (rejected: texts never affect timings, so they would be noise the Kotlin port must ignore).
- Consequences / revisit when: RN5 must honor identical pauses, ms from running sample counts with round-half-even (Python `round`, JVM `Math.rint`; Kotlin `Math.round` is half-up and fails the rounding case), resample lengths by `round(n * dst / src)` half-even on doubles, exact integer pause samples (`ms * rate // 1000`), the leading-break drop, and device-side split-pair recomputation via `DialogueTagger` (the bundle carries no split-pair fields by design). Only sample lengths affect timings, never audio values. Reuse note: no shared exporter scaffolding existed beyond `export_ingest.canonical_json` plus `default_fixtures_dir`, which the new exporter imports (not forks); the installed `scribe` console script is stale until the next `uv sync --extra ui` reinstall (verified via CliRunner plus direct `python export_timing.py` on the source tree, the D-054 precedent). Bump `TIMING_VECTORS_VERSION` if the schema ever changes; a pause-constant edit without re-export fails the freshness test by design.

### D-104: RN3 planner and job model (Slice 10 RN3)

- Date: 2026-10-06
- Status: Accepted
- Decision: New pure-JVM `player/.../render/` package (no service, notification, wake lock, MediaCodec or UI; no new dependency, permission or manifest change): `RenderPlan` (WholeBook wraps from the reading position, FromHere runs to the end, NextN takes the first N forward; default N 5 as the placeholder for a few hours of audio; `roll` keeps `createdAt` so queue order never moves; `RenderEstimateInputs` names what RN9 needs and computes nothing), job state machine (QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED, INTERRUPTED with the documented matrix; DONE needs a complete plan, FAILED needs a reason, requeue keeps finished chapters, DONE is terminal), per-book state file plus a global FIFO queue plus guard policy (`RenderGuards.decide` priority storage, then temperature, then charger; `RenderSignalInputs` seam for RN8; provisional 40.0 C limit for RN11 to tune). Full Player suite green (854 tests, 0 failures, including 79 new render tests); lint clean.
- Why: Implements Slice 10 plan RN3 plus D-097/D-098 exactly as scoped: ordering from the reading position with skip plus roll, every transition with interrupted-then-resumed, one global runner, FIFO across books, thin platform seams, atomic persistence.
- RN0 open questions resolved (one line each): state file is `<bundleDir>/render-job.json` with temp `<bundleDir>/render-job.json.tmp` (travels with the book, validator ignores it, rescan refreshes chips); atomic write is temp-then-rename with a 20 x 50 ms retry plus delete-target fallback because Kotlin `File.renameTo` returns false instead of throwing and fails on Windows when the target exists or is open (D-054 parity: Scribe retries the same budget on `os.replace` PermissionError; reads retry 5 x 20 ms, corrupt JSON fails fast); job scope is global single-runner, not per-book (one TTS engine plus one encoder on about 1.5 GB RAM; per-book parallelism would thrash memory, battery and heat); clock source is epoch millis via `System.currentTimeMillis` by default with an injectable `() -> Long` in every planner, job, store and queue call (no `java.time` on API 24).
- Alternatives considered: state in Room (rejected per D-097: another migration unverifiable on the JVM; file logic stays testable); per-book parallel runners (rejected: engine, encoder and heat budget allow one); planner-owned estimates (rejected: RN9 owns audio length, size and time from measured durations plus RTF range, planner only exposes ordered chapters); `BundleStorage` rename op for the state file (rejected: this package must not change that interface, so a small `RenderFileIo` seam with a `java.io.File` production impl stands in until RN7/RN8 wire dirs); auto-promoting past PAUSED/INTERRUPTED (rejected: a paused job keeps its runner claim and an interrupted job waits for an explicit resume, requeue or abandon, only terminal DONE/FAILED/CANCELLED promote the next book).
- Consequences / revisit when: RN4/RN5 consume `orderedChapters` plus `completedChapters` for voice-batched spool resume; RN7 owns write-order recovery plus sid-to-ms conversion and must keep the rescan chip refresh (D-089 note); RN8 wires `RenderSignalInputs` plus the service restart path through `markInterruptedIfActive` plus `startNext`; RN9 replaces DEFAULT_NEXT_N with duration-mapped hours and tunes the 40.0 C default from Tab E numbers; no tablet claim is made (all JVM).

### D-105: RN4 synthesis passes and spool (Slice 10 RN4)

- Date: 2026-10-06
- Status: Accepted
- Decision: New `RenderFingerprint` (D-100 shape `{engine, voices, speeds, engine_versions}` with `toJsonObject` for RN7's manifest write, SHA-256 `fileTag` for spool names, speeds hashed by float bits), `RenderVoices.resolve` (registry plus role store plus a version lambda, failing up front naming the missing engine, voice id, model pack or version string), `SpoolIo`/`JavaFileSpoolIo` plus `SpoolFiles` naming (`chNNN-sMMM-<tag>.pcm` plus `chNNN-index.json`) plus `SpoolPcm` 16-bit mono codec plus versioned `SpoolIndex`, and `SpoolRenderer` (pass 1 narrator sids ascending, release, pass 2 dialogue sids ascending; per-sentence PCM with incremental index; one retry then chapter failure naming chapter plus sid; cancel between sentences; per-role peaks measured, gain never applied; split pairs recomputed via `DialogueTagger.tagChapter` on joined bundle paragraphs; `renderJobChapters` advancing only through `RenderJobs.onChapterDone`). Full Player suite green (891 tests, 0 failures, including 37 new RN4 tests); lint clean.
- Why: Implements Slice 10 plan RN4 plus D-091/D-095/D-100 exactly as scoped: one engine/voice switch per chapter, sentence-level resume after a kill, flat memory (one sentence in RAM), JVM-testable behind the spool seam with a scripted fake engine.
- RN0 open questions resolved (one line each): mixed-engine `engine` field is the sorted namespaces joined with `+` (spec-valid non-blank, still invalidates on any change); blank engine versions fail fast (a placeholder would validate but never invalidate after an update, the D-100 correctness bug); spool stays off `BundleStorage` (temp workspace, RN8 picks the dir, so that interface is untouched); release fires only between passes for distinct engine instances (a shared engine stays loaded, both stay loaded after the chapter for cross-chapter amortization); resume skip needs a matching index plus the PCM (a kill before the first index write re-renders one chapter, keeping peaks exact); unknown speakers and blank texts fail the chapter (2.0 allows only the reserved keys, so silent mis-rendering is worse than a named failure).
- Alternatives considered: spool file per chapter in one blob (rejected: breaks sentence-level resume and flat memory); fingerprint per sentence (rejected per D-100: chapter matches the render and delete unit); reusing `RenderFileIo` for spool bytes (rejected: text-oriented, PCM needs binary plus prefix listing); trusting filename tags alone for resume peaks (rejected: peaks of skipped sentences come from the index, so the index must match too); applying book gain during spool (rejected per D-095: RN5 derives it from the first chapter, the spool stays at engine level).
- Consequences / revisit when: RN5 consumes the spool index (kind/sid/rate/splitPair per sentence) for assembly plus the per-role peaks for the book gain; RN7 reuses `toJsonObject` for the manifest fingerprint and owns stale-spool sweeping plus sid-to-ms conversion; RN8 supplies real Tab E engine version strings plus the spool dir plus the Piper release wiring and must keep engines loaded across chapters; RN10 records the real version strings replacing test fakes; no tablet claim is made (all JVM).

### D-106: RN5 assembly and timings (Slice 10 RN5)

- Date: 2026-10-06
- Status: Accepted
- Decision: New `AssemblyMath` (pause and sample math port: 250/500/800/1000/100 ms, `samplesForMs`, half-even `msForSamples`, half-even `resampledLength`, linear-interp `resampleMono`, `voicePeakLevels`, `gainsForPeaks`, attenuate-only `capGainFor`, db helpers, `pauseAfterSentence`) plus `ChapterAssembler` (streaming two-pass assembly from re-readable spool PCM into the new `ChapterEncoderSink` seam, `BookGains.derive` pure derivation, `assembleFromSpool` helper reading RN4 types without modifying them, `CollectingEncoderSink` test sink). Full Player suite green (927 tests, 0 failures, including 36 new RN5 tests: 12 math, 11 vector-file, 13 assembler); no new dependency, permission or manifest change; no service, notification, MediaCodec or UI code.
- Why: Implements Slice 10 plan RN5 plus D-094/D-095 exactly as scoped: ordered assembly from the spool with resample to 24 kHz, book-level per-role gain plus chapter peak cap, identical pauses including 100 ms split-pair tag pauses from RN4 flags, timings from sample counts, mixed-rate resample-then-count, and the minimal RN6 encoder-input seam now (PCM chunks plus rate plus finish/duration handshake).
- Port fidelity (Kotlin/JVM vs Python/numpy): ms and resample lengths use `Math.rint` (half-even, matching Python `round`); `Math.round` (half-up) is banned here and would fail the rounding-half-even vector (100.5 to 100, not 101). Pause samples use exact integer math (`ms * rate // 1000` via Long product, no float). Resample grids match `numpy.linspace(0, n-1)` in double precision with the same `max(dst_len, 1)` clamp (single-sample fills, empty stays empty, same-rate copies); values may differ from numpy below 1 ulp of float rounding (double multiply then Float narrow, same order as Scribe `astype(float32)`), but vectors assert exact lengths plus timings only, so audio values stay arbitrary per the vector README. Gains apply as two separate multiplies (book gain, then cap) mirroring Scribe per-voice plus whole-buffer stages. Break-block sentences are ignored for synth and sid checks (Scribe drops them the same way); unknown block types, sid order drift, empty chapters and degenerate start/end all fail naming chapter plus sid. Float-bit note found in review: `0.8f.toDouble()` is `0.8000000119`, not `0.8`, so gain expectations use the float-bit value (test fixed before commit).
- Gain derivation plus cap values: target is the Scribe peak (`PEAK_TARGET_DBFS` -1.0, linear `10^(-1/20)` about 0.89125) on both sides. `BookGains.derive` maps first-chapter peaks to linear `target / peak` (silence 0.0 gives 1.0) and decibels `20*log10` (1.0 gives 0.0 dB); keys are narrator/dialogue only, unknown roles fail; pure function, RN7 stores the db map as manifest `gain_db`. The chapter cap is attenuate-only (`min(1, target / peak)`, silence gives 1.0), unlike Scribe `apply_loudness_gain` which always normalizes: re-normalizing every chapter would erase the first-chapter reference and reintroduce night-to-night jumps. Gain only, durations unchanged. The cap is measured from the true assembled peak after book gains via two streaming passes (measure, then stream with the cap), not from spool metadata, so resample undershoot cannot bias it; the PCM loader is therefore called twice and must be re-readable (spool files are).
- Encoder seam definition: `ChapterEncoderSink` carries `sampleRateHz` (24000), `writePcm(FloatArray)` chunks (sentence audio plus silence pauses, already gain-scaled; any chunk split is legal, one call per sentence or pause is the current shape, never a contract) and `finish(totalSamples, durationMs)` exactly once for the RN6 duration check. RN6 owns the platform side (encoder config, buffer feeding and back-pressure, end of stream, muxer track setup, presentation times from sample counts, temp file then rename, duration check against the sample count, encoder-delay hook, clear error when no AAC encoder exists). `encoder_offset_ms` is applied at JSON write time (RN7), not here: assembly emits raw sample-count timings and the fixture offset stays 0 until RN10 measures the Tab E constant. Spool PCM loading rides a caller-supplied lambda (decoded 16-bit via `SpoolPcm` in production, map lookup in tests), so `SpoolIo` stays write-only and no RN3/RN4, TTS, ingest, playback or storage file is modified.
- Alternatives considered: cap by re-normalizing every chapter like Scribe (rejected: defeats the book gain reference across nights); cap from spool index peaks without re-measuring (rejected: resample plus 16-bit quantization undershoot would make the cap conservative by a small unknown amount; two-pass resample costs negligible CPU next to TTS and stays memory-flat); holding the whole-chapter float array for a single-pass measure-then-scale (rejected: breaks the 1.5 GB memory rule on long chapters; peak working set stays one native plus one resampled sentence plus one pause chunk, max pause 24 k samples/96 KB as float, documented in the file KDoc); adding a read op to `SpoolIo` (rejected: touches RN4; the loader lambda keeps assembly testable with zero RN4 edits).
- Consequences / revisit when: RN6 implements the sink with the real AAC path (fake sink proves the streaming shape only); RN7 applies `encoder_offset_ms` at write time plus stores `gain_db` from `BookGains.derive` and owns sid-to-ms conversion; RN10 replaces fixture offset 0 plus illustrative gains with measured Tab E values and confirms or loosens the provisional 50 ms AAC tolerance; no tablet claim is made (all JVM).

### D-107: RN6 AAC encoder and muxer (Slice 10 RN6)

- Date: 2026-10-06
- Status: Accepted
- Decision: New `player/.../render/AudioEncoder.kt` (pure JVM: `EncoderConfig` AAC-LC mono 24000 Hz 64000 bps constrained plus `encoderOffsetMs` default 0; `AudioEncoder` feed/finish/abort interface with back-pressure documented; `EncoderTimestamps` PTS from sample counts; `EncoderOffset` raw-plus-offset hook; `Pcm16` float-to-PCM16; `EncoderCore` count and duration validation; `EncoderFiles` `.m4a.tmp`-then-rename with the RN3 20 x 50 ms retry loop; `AudioEncoderSinkAdapter` plus `ChapterEncode.encode` streaming assembly into the encoder) plus `player/.../render/AndroidAudioEncoder.kt` (thin MediaCodec plus MediaMuxer edge: encoder config, input-buffer feeding from sink chunks, output drain, empty-buffer end of stream, muxer track setup, temp-then-rename, abort cleanup). Shaped errors (`NoAacEncoder`, config, muxer/codec, IO, duration mismatch) all name the chapter; a missing platform encoder is a clear error naming the need. 25 new JVM tests with a fake encoder; full Player suite green (954 tests, 0 failures, 100% successful); no new dependency, permission or manifest change; no service, notification, wake-lock or UI code; no RN3/RN4/RN5, TTS, ingest, playback, storage or reader change (reads only).
- Why: Implements Slice 10 plan RN6 plus the D-092 codec call and the D-093 hook exactly as scoped: platform code holds `android.*` at the outer edge while timestamp math, state handling and error mapping stay JVM-testable; the fake proves buffering, EOS, error mapping, the offset hook and the duration check without a device.
- Codec config choices: MIME `audio/mp4a-latm`, AAC-LC profile, mono, 24000 Hz, 64000 bps (spec section 2 constrained-average wording; the validator checks the average within 16 kbps, so the encoder just targets 64k). No MP3 path (no platform MP3 encoder on API 24) and no bundled native code (license weight per D-092). `MediaCodec` (API 16), `MediaMuxer` (API 18) and `getInputBuffer`/`getOutputBuffer` (API 21) are all older than `minSdk 24`, so no version guard; `java.time` unused.
- Timestamp math: every PTS is `sampleOffset * 1_000_000 / rate` in Long math from cumulative fed counts (input sub-chunks recompute from the absolute offset, never start-plus-delta, so no rounding drift; EOS buffer carries the PTS of the total). `finish` requires fed samples equal the declared total and the declared duration equal `AssemblyMath.msForSamples` of that total exactly (same function the assembler used, so agreement is exact, not tolerant). Codec-config bytes ride the format change and are never written; data before track setup fails loudly.
- Delay-hook shape: `EncoderConfig.encoderOffsetMs` (default 0) rides into `EncodedChapter`; `EncoderOffset.apply`/`applyToTimings` shift by `raw + offset` preserving order and gaps. RN7 calls the batch helper at JSON write time and records the same value as manifest `encoder_offset_ms`; readers need no correction. Fixture offset stays 0.
- Alternatives considered: `signalEndOfInputStream` for EOS (rejected: the empty EOS input buffer is the API 16 path and carries an exact PTS); reusing `RenderFileIo` for the rename (rejected: text-oriented, PCM output needs parent-dir plus binary-rename semantics, so a small dedicated `EncoderFileIo` stands in with no `BundleStorage` change); folding rename failures into the muxer error (rejected: the IO shape names the file, which is what a full-disk failure needs).
- Consequences / revisit when: RN7 owns the offset application at write time plus `gain_db` storage and sid-to-ms conversion; RN8 drives `ChapterEncode.encode` per chapter. RN10 must measure on the Tab E: the beep-chapter offset constant (replaces fixture 0), confirm-or-loosen the provisional 50 ms AAC tolerance (D-102), and the first real encode playing in the app (device check, not claimed here). No tablet claim is made (all JVM plus platform compile).

### D-108: RN10 beep engine and self-checks (Slice 10 RN10)

- Date: 2026-10-06
- Status: Accepted
- Decision: New `player/.../render/BeepTtsEngine.kt` (debug-only deterministic `TtsEngine`, namespace `beep`: 500 ms pure sine tones at 24000 Hz, 440 Hz plus 110 Hz per 1-based sentence index, amplitude 0.5; index parsed from trailing text integer via `BeepSelfCheck.textForSid` "Beep N", fallback to the sentence-1 tone; speed accepted and ignored), `BeepSelfCheck.kt` (debug action rendering the 4-tone alternating narrator/dialogue test chapter through the unchanged RN4 spool plus RN5 `assembleFromSpool` plus an injected `AudioEncoder`, then validating the emitted manifest plus chapter JSON through `BundleValidator` with an in-memory seam; refuses release builds), `BeepOffset.kt` (pure `diagnose`/`estimateOffsetMs` median offset with half-even rounding plus rising-edge `detectOnsets` with silence re-arm), and `DebugRenderEngines.beepEngineIfDebug` (beep instance in debug, null in release). Debug UI is one card at the bottom of the voice-lab screen plus host wiring in `VoiceAuditionHost` (debug-gated button rendering into cache `beep-check/` with the platform encoder, one status line; full render UI stays RN9). Gate reuses the CP8 seam (`isBeepSelfCheckAvailable` next to `isReaderPreviewAvailable`, `BuildConfig.DEBUG` call sites, R8 folds the constant). 34 new JVM tests (12 engine determinism/shape/validation, 11 offset synthetic plus onset detection, 5 self-check known-positions/validation/release-refusal, 6 gate both branches plus registry exclusion); full Player suite green (988 tests, 0 failures); lint clean (0 issues); no new dependency, permission or manifest change.
- Why: Implements Slice 10 plan RN10 plus D-093 exactly as scoped: the real-`TtsEngine` choice (over the RN4-test fake pattern) keeps the beep path on the production `RenderVoices`/`SpoolRenderer`/`ChapterAssembler`/`ChapterEncode` code with zero RN3-RN6 edits, so the self-check proves the chain the System TTS render will use; known tone positions (starts 0/1000/2000/3000 ms, 500 ms tones) plus ear-distinct frequencies give the RN11 highlight check by ear and eye; the median-plus-deviation math turns the owner's measured onsets into the `encoder_offset_ms` constant plus a drift verdict.
- Seam choices: spool bytes arrive through a `readSpoolBytes` lambda (the spool seam is write-only by RN4 design and the assembler already takes a loader lambda, so no new read op); the debug action takes `isDebugBuild` and refuses when false (defense in depth beyond the UI gate); no `src/debug` source set split (the CP8 spike precedent keeps debug code in main behind the folded `BuildConfig.DEBUG` constant, which keeps JVM tests simple and the release list provably identical).
- Alternatives considered: fake-engine seam for the beep path (rejected: parallel seam proving nothing about production code); per-sentence varying tone lengths (rejected: uniform 500 ms keeps positions trivially known; frequency already distinguishes sentences); onset detector with refractory gap (rejected after a failing test: re-triggered inside continuous tones; edge-trigger with silence re-arm detects exact starts); new debug screen (rejected: the voice lab already builds the registry, so a gated card there is the smallest trigger).
- Provisional values (RN11 confirms on the Tab E): fixture `encoder_offset_ms` stays 0; beep chapter known starts 0/1000/2000/3000 ms at 500 ms tones; onset threshold 0.08 with 200 ms re-arm silence; AAC 50 ms tolerance unchanged pending measurement. Owner procedure in `docs/test-log.md` (RN11 beep section).
- Consequences / revisit when: RN8 drives `ChapterEncode.encode` per chapter and owns the spool dir the debug host previews; RN11 (owner) renders the beep chapter on the Tab E, measures onsets, records the constant, and confirms or loosens the 50 ms AAC tolerance. No tablet claim is made (all JVM plus platform compile; device render, listening check and highlight timing are owner device tests).

### D-109: RN7 bundle update, recovery, partial books (Slice 10 RN7)

- Date: 2026-10-06
- Status: Accepted
- Decision: New `player/.../render/` logic plus minimal mechanical touches, no service, notification, MediaCodec or UI-screen code (partial-playback wiring stays RN8/RN9): `RenderFinalize.kt` (D-096 order per chapter: RN6 audio presence gate, then timed JSON with `EncoderOffset` applied at write, then manifest update with `render_state` recompute, chapter audio/text/duration, `gain_db` first-wins, `encoder_offset_ms`, `render_fingerprint`; every write temp-then-rename on the RN3 20 x 50 ms retry; JSON plus manifest self-check before commit), `RenderRecovery.kt` (startup `recoverBook`: manifest-derived temp sweep, orphan spool sweep, four chapter repairs, then the RN3 interrupted path; manifest rewritten at most once), `ChapterMediaMap.kt` (D-099 explicit playlist-position to manifest-chapter mapping with identity for rendered books, per-chapter listening gate, end-of-portion rule plus message), `RenderProgress.kt` (sid to ms conversion on first render of the chapter). Mechanical touches, all named: `PlaybackQueue.requirePlayable` pure refusal (the service delegates, same string), ungated `build` deprecated, `buildPlayable` carries the manifest chapter position (not the filtered position) plus mapping-aware `startFromMap`, `StrayTempSweep.renderTempPaths`/`sweepPaths` extension (IN7 sweeper reused, no parallel sweeper, no storage interface change), pure `rowMarkerText` in `ChapterList.kt` (no Composable changed). 55 new JVM tests (8 mapping, 12 queue-mapping/refusal, 12 finalize incl. crash states, 11 recovery incl. spool/job paths, 8 progress conversion, 3 marker, 1 chip-refresh); full Player suite green (1043 tests, 0 failures); lint clean.
- Why: Implements Slice 10 plan RN7 plus D-096/D-099 exactly as scoped, with every crash state (audio done/JSON missing, JSON done/manifest stale, temp residue, lost files) repaired to a validator-clean state proven by running the real `BundleValidator` in the tests, and rendered 1.x books byte-identical through the gated path (identity mapping regression-tested).
- Repair rules (each reported, never silent): orphan audio deleted (spool intact, RN8 resume regenerates); timed JSON without manifest entry completed forward with audio verified plus duration from the JSON, inventing no fingerprint, gain or offset (absent means unknown per spec); timed JSON without audio stripped back to untimed; manifest entry without files downgraded to unrendered (JSON stripped alongside, found by test: entry-only downgrade fails validation the other way). Spool orphans are PCM with no index entry or an unparseable index; fingerprint-stale spool is NOT swept here (RN4 invalidates per chapter at render time). Sid conversion: null, other-chapter and ms saves pass through; sid saves convert to the sentence start with sid cleared; unknown sids fall back to 0; untimed chapters pass through (RN9 reader path keeps working).
- #14 disposition: item 1 done via deprecation (body kept for the gate test that documents what the gate prevents); item 2 done via `requirePlayable` plus golden refusal tests (same code path the service calls); item 3 done via the manifest-position `chapterIndex` plus `startFromMap` (warning KDoc now points at the mapping); item 4 minimal logic piece done via `rowMarkerText` ("Now playing" vs "Reading"), screen wiring deferred to RN9; item 5 done via the rescan test (the map is replaced wholesale every rescan, proven across a none to partial rewrite); item 6 already owned by RN4/RN5 split recompute, no work.
- Surprises: (a) nonzero offset vs spec section 6 rule 1 (first start 0, carried exactly into 2.0): shifting all timings moves the first start, so a nonzero offset fails the finalize self-check shaped instead of writing spec-violating JSON; the provisional offset is 0 so no live path hits this (pinned by test). OPEN SPEC QUESTION for the owner at RN11 measurement: allow first start equal to `encoder_offset_ms` for device chapters (spec-first amendment, then this check follows), or keep refusing nonzero offsets. (b) `buildPlayable` semantic change is safe today only because the service still refuses partial books; RN8 must switch the service to `startFromMap` plus media-to-chapter save conversion when it enables subset playback. (c) No roadmap tick: the v2 roadmap has no per-WP boxes and Slice 10 done-when is device acceptance (RN11).
- Consequences / revisit when: RN8 calls `recoverBook` at startup (no render running), drives finalize per chapter with `BookGains.derive` db plus measured offset, and owns the spool dir plus service partial path; RN9 wires `rowMarkerText` plus per-chapter row states plus `isListenAvailable` for partial books plus render estimates. Revisit the forward-complete repair only if inventing no fingerprint proves wrong for Slice 11 staleness (absent already means unknown, so re-render is the safe default). No tablet claim is made (all JVM plus platform compile).

### D-110: RN8 render service (Slice 10 RN8)

- Date: 2026-10-07
- Status: Accepted
- Decision: New `player/.../render/RenderService.kt` (foreground `Service`, exported false, plain `startService` entry, START_STICKY) plus pure `RenderEstimate.kt` (storage math: 8.0 audio B/ms at 64 kbps, 50.09 spool B/ms from the 86 MiB per 30 min plan figure, conservative 30 min fallback for unknown durations, all rates injectable) and `RenderServicePolicy.kt` (task-removed pause plus persist, restart never auto-starts, guard-only auto-resume, pause messages, media-to-chapter save conversion, finished-save gate, spool dir `<cache>/render-spool/<bookId>`), plus thin `RenderSignals.kt` (sticky battery intent for charger plus tenths-C temperature, `File.usableSpace`, prefs-backed `RenderPolicy` in the shared settings file). The service drives the full chain per chapter (recover, plan-or-resume, guard gate, RN4 spool with cancel between sentences, RN5 assembly plus RN6 platform encode, RN7 finalize with first-chapter `BookGains.derive` db and offset 0, spool sweep, sid-to-ms progress conversion, job persist) with a progress notification (pause/cancel or resume/cancel actions, chapter plus sentence text), a partial wake lock held only while actively rendering, and charger/temperature/storage auto-pause with guard-only auto-resume. PlaybackService takes the RN7 service partial path: empty map still refuses shaped (same string), identity books keep the exact old start path, sparse books start via `startFromMap`, every progress save converts media to chapter position (unmapped saves nothing), and the end of a rendered portion publishes `END_OF_RENDERED_MESSAGE` through the notice channel instead of a finished save. 22 new JVM tests (9 estimate, 13 policy); full Player suite green (1065 tests, 0 failures); `lintDebug` clean (no errors; no warnings on touched files).
- Why: Implements Slice 10 plan RN8 plus D-101 exactly as scoped, following the playback service foreground plus lifecycle pattern (every-start logging, task-removed/destroy saves, Auloud tags) with all policy JVM-testable behind the RN3 signal seam.
- Permission record: no new permission. `WAKE_LOCK` (normal, install-time, no runtime prompt) was already declared in the manifest since Slice 1 and covers the partial wake lock; `FOREGROUND_SERVICE` was already there too and is untouched. Nothing else added: no `foregroundServiceType` (API 29+), no `POST_NOTIFICATIONS` (API 33+), no `INTERNET`, per the plan mandate.
- recoverBook placement: service render start, not `Application.onCreate`. No Application subclass exists (verified by search; `MainActivity` owns no render state), and adding one plus a manifest application element for a per-book sweep with no book registry would be churn: recovery runs per book before every render start, which is the only writer, so playback and the reader never need it.
- Alternatives considered: spool inside the book folder (rejected: validator-invisible temp churn plus rescan noise; cache keeps the bundle clean); per-chapter word-count duration estimates now (rejected: no measured constant exists, the conservative fallback plus RN9 measured durations is honest); `signalEndOfInputStream` style auto-start after kill (rejected: RN3 queue rule says INTERRUPTED waits for explicit resume); releasing TTS engines between passes (rejected per RN4: instances stay loaded across chapters and release when the run ends; the pass hook stays a documented no-op); SAF-tree rendering now (rejected: shaped refusal until app-storage output lands, D-069).
- Provisional values (RN10/RN11 confirm): engine version strings (system TTS package version, `sherpa-1.13.8`, beep `beep-1`), `encoder_offset_ms` 0, 40.0 C temperature default, 30 min fallback chapter length, unknown-chapter estimates. First lint suppressions in the repo, all narrow with plan-mandated justification at single choke points: `ForegroundServiceType` plus `NotificationPermission` (forbidden manifest concepts on the API 24 target) and `WakelockTimeout` plus `UsableSpace` (timeout-free lock released on every logged exit; `usableSpace` is the API 24 call).
- Consequences / revisit when: RN9 builds the screens on the service intent extras plus notification actions plus policy messages, and replaces estimate fallbacks with measured durations plus the RTF range. Needs device test (RN11, not claimed): screen-off survival, swipe-away pause plus resume, kill mid-render resume without redoing spool-skipped sentences, unplug/replug with charging-only on, beep-chapter offset measurement, partial-book playback with the end-of-portion message, low-storage and high-temperature behavior. No roadmap tick: the v2 roadmap has no per-WP boxes and Slice 10 done-when is device acceptance (RN11).
- Review fixup 2026-10-07: restart seam kept live by calling restartAction in the null-intent path via shouldStopAfterNullRestart (WAIT_EXPLICIT stays, NO_JOB and DONE stop only with no book loaded).

### D-111: RN9 render UI (Slice 10 RN9)

- Date: 2026-10-07
- Status: Accepted
- Decision: UI plus view-models only, driving the RN8 service seam (intents, job file, policy prefs); no service, notification, MediaCodec, TTS, ingest or validator change. New `player/.../render/RenderUiState.kt` (pure: option mapping, estimate math, plain errors, chip text, chapter states, debug lines), `RenderAudioDelete.kt` (chapter/book audio delete through RN7-compatible manifest plus JSON strips on the RN3 seam), `RenderPanelViewModel.kt` (plain class with service/prefs/rescan callbacks, JVM-fakeable), `RenderPanel.kt` (estimate, options, charging toggle, voices summary plus link, start/pause/resume/cancel, plain errors, delete with confirm) and `RenderDebugOverlay.kt`, plus `player/.../reader/PartialBookScreen.kt` (partial-book hub: panel, chapter list with per-chapter states plus render/delete actions, per-chapter reader/player routing, debug section). RN7 deferred wiring done here: BookScreen routes `partial` to the hub (per-chapter listen via the RN8 sparse playlist, per-chapter read via the read-only screen, Back always returns to the hub), chapter rows use `rowMarkerText` ("Now playing" for listening, "Reading" for read-only, per-row in the hub), per-chapter listening goes through `isChapterListeningEnabled`, and the end-of-portion message rides the existing notice line in the player UI (no new code; verified by read). Minimal named touches only: ChapterListScreen optional row params (defaults render exactly as before), LibraryViewModel optional `renderJobReader` plus jobs map (default null preserves the old chip), LibraryScreen chip via `renderChipText`, `isRenderDebugAvailable` gate (same seam as the beep card), `RenderPolicyPrefs.save`, MainActivity wiring (file job reader, rescan on book change, voices link back through Settings). Library chip precedence is job first ("Rendering 42%", "Paused at 42%", "Render failed") then render_state ("Partially rendered", "Not rendered"). 71 new JVM tests; full Player suite green (1149 tests, 0 failures: 1078 baseline plus 71 new); `lintDebug` clean (no errors; no warnings on touched files).
- Why: Implements Slice 10 plan RN9 exactly as scoped, keeping every number either measured (manifest durations) or explicitly ranged/rough (word-count sizing, RTF range, fallback count).
- Estimate constants (all provisional, RN11 retunes): wall time from the plan benchmark range (System TTS 0.86x slow to 2.56x fast, always shown as a range, never a single number); unrendered chapters sized at 400 ms per word (150 wpm adult narration) from chapter JSON word counts read sequentially and never held; unreadable chapters use the RN8 30 min fallback and are counted as unknown with a "Rough estimate" note.
- Alternatives considered: word counts via a new estimator seam (rejected: inline counting in the panel VM is one function and needs no interface); per-chapter render as a new service scope (rejected: NEXT_N with N 1 from that chapter renders exactly it, no service change); read plus listen inside the hub for rendered chapters (rejected: the hub opens Listen for rendered and Read for unrendered; read-along on partial chapters stays future work); voices deep-link straight to the audition screen (rejected: navigation is single-activity state, so the link lands on Settings where "Choose voices" is one tap away); auto-rescan inside the panel VM (rejected: storage plus repository ownership stays in MainActivity, the VM only signals through `onBookChanged`).
- Delete semantics: per-chapter delete strips the entry (book gain reference kept for remaining chapters); whole-book delete also clears `gain_db` plus `encoder_offset_ms` (they described the deleted audio); both delete the job file so the next render re-plans instead of skipping deleted chapters as done; spool files are kept on purpose (same fingerprint reuses them, so re-render after delete is fast).
- Consequences / revisit when: no roadmap tick (the v2 roadmap has no per-WP boxes; Slice 10 done-when is RN11 device acceptance). Needs device test (RN11, not claimed): panel legibility and estimate sanity on the Tab E, start/pause/resume/cancel from the panel, per-chapter render plus delete flows, hub navigation both ways, sparse playback with the end-of-portion message, charging-only unplug/replug, overlay lines against live rendering. Revisit the 400 ms/word and RTF ends only with measured Tab E numbers, not emulator guesses.

### D-112: RN9 fix: render hub opens for unrendered books

- Date: 2026-10-08
- Status: Accepted
- Decision: `BookScreen` routes both `none` and `partial` through `shouldOpenRenderHub` to `PartialBookScreen`; the library render chip taps open the book (`onBookSelected`). `UnrenderedBookScreen` stays as the hub Read destination. Zero-rendered hub needs no change (empty media map routes every chapter to READ with panel plus per-chapter Render actions).
- Why: Fresh imports are `none`, so the old `partial`-only route plus dead chip left the render panel unreachable for exactly the books that need it; book-level `isListenAvailable` stays false for `none`/`partial` with per-chapter gating in the hub.
- Alternatives considered: a separate chip action (rejected: brief mandates the row tap); changing `isListenAvailable` (rejected: book-level Listen must stay disabled with zero rendered chapters).
- Consequences / revisit when: needs device test (RN11, not claimed): fresh-import tap reaches the hub on the Tab E, chip tap opens the book, zero-rendered panel plus chapter Render buttons legible.

### D-113: Slice 11 global defaults with per-book voices (plan decision 1)

- Date: 2026-10-08
- Status: Adopted
- Decision: New books copy the global narrator and dialogue voices at first render; each book then keeps its own voices in its manifest. Changing global defaults never touches existing books. A "use as default for new books" option in the book voice screen promotes a choice to global.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 1; adopted as the Slice 11 direction in VS0 prep (no code, so no conflict with any existing decision; the global-only store from Slice 8 stays untouched until VS1).
- Alternatives considered: per-book voices with no global defaults (rejected by the plan: new books need a starting point); editing global defaults in place for all books (rejected: would silently stale every rendered chapter).
- Consequences / revisit when: VS1 implements the `BookVoices` read/write plus the fallback; Scribe-rendered books are flagged read-only per D-118. Revisit only if per-book storage needs a migration (the manifest `voices` entries already exist, so none is expected).

### D-114: Stale means fingerprint differs for used roles only (plan decision 2)

- Date: 2026-10-08
- Status: Adopted
- Decision: A chapter is stale when its fingerprint differs from the book current voice settings, but only for the roles that chapter actually uses. A chapter with no dialogue sentences does not become stale when only the dialogue voice changes. VS0 finding: Slice 10 always includes both roles (see `docs/fingerprint.md` section 2), so VS2 must change the fingerprint to include only used roles; older both-role fingerprints keep working as "stale" at worst, never as current and never as a crash.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 2; adopted as the Slice 11 direction in VS0 prep. Over-eager stale marks would waste nights of rendering; under-eager marks would play mismatched audio silently.
- Alternatives considered: keep both roles always (rejected: every dialogue-only change would re-render dialogue-free chapters); fingerprint per sentence (rejected: chapter matches the render and delete unit, per D-100).
- Consequences / revisit when: VS2 changes `RenderFingerprint` construction plus `fromJsonObject` plus the validator to accept single-role fingerprints, with matrix tests (change narrator voice, dialogue-only change on a dialogue-free chapter, speed, engine, version-only, unrendered chapter, partial book, old-format fingerprints). Revisit only if a chapter fingerprint ever fails to change when its audio would (then it is a correctness bug, widen the key, per D-100).

### D-115: Three chapter states beyond "not rendered" (plan decision 3)

- Date: 2026-10-08
- Status: Adopted
- Decision: `Current`; `Stale` (voice, speed, or engine differs); `Outdated` (only the engine version string differs, for example after a system TTS update). Outdated is shown quietly and never re-renders automatically.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 3; adopted as the Slice 11 direction in VS0 prep. A system TTS update changes every fingerprint version string without changing any audible voice, so it must not trigger automatic re-renders.
- Alternatives considered: two states only (rejected: version-only churn would either spam re-renders or hide real staleness); auto re-render on Outdated (rejected: same audio out, nights wasted).
- Consequences / revisit when: VS2 computes the states from fingerprints vs book voices; VS5 shows the badges. Revisit the quiet treatment only if users report confusion about Outdated chapters.

### D-116: Re-render replaces whole chapters safely (plan decision 4)

- Date: 2026-10-08
- Status: Adopted
- Decision: New audio is written under a new fingerprint-based filename, finalized with the Slice 10 write order (audio, then timed JSON, then manifest), and only then does the manifest switch to it; the old file stays playable until the swap and is deleted afterwards (deferred while the player has it loaded). Cancel or failure leaves the old audio untouched. Reusing the unchanged role audio is not possible because the spool is deleted after finalize, so a role-level cache stays in the backlog.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 4; adopted as the Slice 11 direction in VS0 prep. Builds on Accepted D-096 (crash-safe write order) and D-099 (stable chapter index mapping).
- Alternatives considered: in-place overwrite (rejected: a kill leaves torn audio, the D-096 lesson); per-role audio caching now (rejected: spool lifetime does not cover it; backlog item).
- Consequences / revisit when: VS3 implements the versioned filenames plus atomic swap plus deferred deletion plus orphan cleanup in recovery. VS3 must confirm the naming stays within the spec (per-chapter `audio` path entries) or amend the spec first per the bundle change rule; no spec change is made in VS0. No new dependency, permission, or bundle-format change in this decision.

### D-117: Position survives re-render through sentence ids (plan decision 5)

- Date: 2026-10-08
- Status: Adopted
- Decision: A saved position inside a re-rendered chapter converts old milliseconds to sentence id to new milliseconds before the swap, using the Slice 10 conversion (`RenderProgress.convertOnRender`, D-109).
- Why: Source is `docs/plans/Slice11.md` section 4 decision 5; adopted as the Slice 11 direction in VS0 prep. Voices and speeds change durations, so raw milliseconds would land in the wrong sentence.
- Alternatives considered: keep raw milliseconds (rejected: drift by whole sentences on speed changes); drop the position to chapter start (rejected: loses the listener place for no reason).
- Consequences / revisit when: VS3 wires the conversion into the swap path with tests at chapter start, middle, and end. Revisit only if re-timed sentences no longer line up (then the conversion input, not the rule, is suspect).

### D-118: Scribe (PC) bundles stay read-only for voices (plan decision 6)

- Date: 2026-10-08
- Status: Adopted
- Decision: Scribe bundles carry per-character PC audio; re-rendering on the device would replace it with a two-voice version. The app shows their voices, disables editing with a plain explanation, and leaves a "make a device copy" feature for later.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 6; adopted as the Slice 11 direction in VS0 prep. Protects the PC multi-voice work (D-072: the PC stays fully multi-voice) from silent collapse into two voices (D-071).
- Alternatives considered: allow re-render with a warning (rejected by the plan default: data loss by tap); auto device-copy on first edit (rejected: later feature, needs its own design).
- Consequences / revisit when: VS1 flags read-only books; VS4/VS6 show the explanation and block re-render with a clear message. Revisit only if the user asks for a different rule (the plan explicitly invites that).

### D-119: Mixed-voice books are legal (plan decision 7)

- Date: 2026-10-08
- Status: Adopted
- Decision: Books with some chapters on the old voice and some on the new voice are legal and labeled as such; "finish re-rendering" is one tap.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 7; adopted as the Slice 11 direction in VS0 prep. Overnight renders are incremental, so the library must tolerate halfway states instead of forcing all-or-nothing.
- Alternatives considered: forbid partial application (rejected: a slow engine would hold the whole book hostage); silent mixed state (rejected: the listener deserves to know chapters differ).
- Consequences / revisit when: VS3 keeps chapter granularity; VS5 adds the mixed-voice banner plus per-chapter actions. Revisit only if mixed books prove confusing in acceptance (VS7).

### D-120: Gain follows the role (plan decision 8)

- Date: 2026-10-08
- Status: Adopted
- Decision: When a role voice or engine changes, its book-level gain is re-derived from the first chapter rendered with the new voice. Already rendered chapters keep the loudness baked into their audio.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 8; adopted as the Slice 11 direction in VS0 prep. Builds on Accepted D-095/D-106 (first-chapter book gain plus attenuate-only chapter cap); re-deriving on voice change keeps the reference meaningful instead of pinning new voices to an old voice level.
- Alternatives considered: keep the old gain for new voices (rejected: night-to-night jumps return); re-level old chapters to the new gain (rejected: rewrites finished audio for no listener benefit).
- Consequences / revisit when: VS3 implements the re-derivation with the fingerprint updated per chapter on success. Revisit only with listening evidence of uneven loudness (then revisit reference selection or the metric, per D-095).

### D-121: Applying a change always shows impact first (plan decision 9)

- Date: 2026-10-08
- Status: Adopted
- Decision: Applying a voice change shows how many chapters, hours of audio, estimated render time (benchmark plus measured speed), and storage needed (old plus new during swap), with choices: re-render now (reading position forward first), later, or keep old audio.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 9; adopted as the Slice 11 direction in VS0 prep. Estimates reuse the Slice 10 constants (RTF band, 400 ms/word, 40 C guard stay provisional until retuned from real renders).
- Alternatives considered: apply immediately with no estimate (rejected: multi-night renders must not start by surprise); estimates as single numbers (rejected: RN9 rule says ranges, never single numbers).
- Consequences / revisit when: VS2 computes counts/hours/time/storage; VS4 builds the impact dialog plus the three apply choices. Revisit estimate constants only with measured Tab E numbers.

### D-122: Slow-engine warning (plan decision 10)

- Date: 2026-10-08
- Status: Adopted
- Decision: Picking an engine categorized "Too slow" or "Background" shows the estimated render time per hour of audio before confirmation.
- Why: Source is `docs/plans/Slice11.md` section 4 decision 10; adopted as the Slice 11 direction in VS0 prep. Piper-class speeds can turn a book into a multi-night job; the warning sets that expectation before the user commits.
- Alternatives considered: no warning (rejected: same surprise as D-121, at engine-pick time); blocking slow engines (rejected: the user may still want them with eyes open).
- Consequences / revisit when: VS4 adds the warning to the engine picker using the benchmark category. Revisit categories only with measured engine numbers.

### D-123: VS4 voice-screen judgment calls (engine categories, read-only audition, KEEP meaning)

- Date: 2026-10-09
- Status: Accepted
- Decision: (1) Engine categories are provisional in `EngineBenchmark`: `system` and `beep` are STANDARD (no warning), `piper` is TOO_SLOW at the Slice 10 plan 0.36x (about 2 h 47 min of rendering per hour of audio), and any other namespace is BACKGROUND (conservative warning with the slow end of the system band until measured). (2) Scribe read-only books disable audition as well as editing: the book voices are PC voice ids the device engines do not offer, so a preview would only fail confusingly; samples stay visible as text. (3) The impact dialog KEEP choice discards the edits and keeps the old voices plus audio untouched (NOW persists plus starts the stale-only re-render reading-position forward first; LATER persists with no start, leaving the legal mixed-voice state for VS5 badges).
- Why: The plan names the categories and the three choices but not their exact contents; each default above is the least surprising reading (a warning must never mislead, a failing preview is worse than a disabled button, three choices must have three distinct outcomes).
- Alternatives considered: KEEP as persist-without-render (rejected: identical to LATER, so the third button would be dead); read-only audition enabled (rejected: PC voice ids fail against the device registry); unknown engines standard (rejected: a future slow engine would slip through unwarned).
- Consequences / revisit when: the owner confirms or corrects the KEEP meaning in acceptance (VS7); VS5 badges show the LATER mixed state; categories retune with measured engine numbers.
- Update 2026-10-09 (VS7 owner verdict on the Tab E, follow-up to commit a812743): KEEP now persists the edited BookVoices with no render start, using the same persist path as LATER (storage write, no start, old audio keeps playing, chapters read Stale for manual re-render). Checked the code before changing: LATER carries no queue or schedule flag (persist plus notice plus onBookChanged only), so KEEP and LATER are now behavior-identical and no fake distinction is kept.

### D-124: VS5 library and chapter badges (stale UI wiring, no new pipeline)
- Date: 2026-10-09
- Status: Accepted
- Decision: (1) Stale badges read `Voices: current`, `Voices: changed - needs re-render`, `Voices: outdated (engine updated)`, `Voices: not rendered` (the `Voices:` prefix keeps the VS2 axis distinct from the RN9 job-progress line; both lines show, pinned by test never to match). Library chip reads `N chapter(s) need re-render` next to the RN9 chip, tap opens the book. (2) Mixed-voice banner shows exactly when stale > 0 plus current > 0 or outdated > 0 (unrendered chapters never count); text `Mixed voices: N of M chapters use older voices.` with one-tap `Finish re-rendering` (STALE_ONLY from the current chapter). All-stale books get a `Re-render stale (N)` button instead (no banner: nothing is on the new voices yet). This is also what surfaces the VS4 LATER state. (3) Per-chapter `Re-render` starts STALE_ONLY at the tapped chapter (the chapter goes first, the rest of the stale set follows; there is no single-chapter pipeline mode, and cancelling after one chapter gives the single-chapter effect). (4) Delete-stale-audio semantic: stale audio IS the current playable audio (pre-swap it stays playable; post-swap the old file is already deleted or deferred by `RerenderSwap`), so the bulk option removes playable audio and each stale chapter returns to NOT_RENDERED for the normal first-render path to recreate. There are no separate stale files. Current chapters are untouched; failures report file-plus-rule after the reload. (5) Complete books enter the voice screen from a `Voices` button in the chapter-list header (smallest seam: the list already hosts badges, banner and actions; the reader and Listen screens needed no menu). The voice host is shared with the hub. Bulk delete stays hub-only in VS5: deleting from a complete book would flip it to partial mid-screen and need re-routing (follow-up). (6) Read-only books (Scribe PC audio, legacy 1.x) show no stale UI at all (their chapters carry no device fingerprints, so all would read STALE); the complete-book `Voices` entry still opens for them (read-only face). Library and book scans stream one rendered chapter JSON at a time (flags only, never held), skip books with nothing rendered with zero chapter reads, and fail open to no chip (null, never a crash).
- Why: Every choice is the least surprising reading of the VS5 brief that keeps the two axes (job progress vs voice match) visibly separate and never invents pipeline behavior.
- Alternatives considered: merging stale text into the RN9 chip/line (rejected: conflates the axes the brief keeps distinct); per-chapter single-chapter pipeline mode (rejected: new pipeline, explicitly out of VS5); delete-stale as old-file cleanup (rejected: post-swap olds are already deleted/deferred, and pre-swap the old file is the playable one); complete-book entry in the reader overflow or player menu (rejected: the chapter-list header already exists on both branches and hosts the banner).
- Consequences / revisit when: device visual check is the owner job (VS7, #18); revisit badge wording only with listening-confused reports, and the bulk-delete scoping if complete-book deletes are wanted without re-routing.

### D-125: VS6 re-render edge-case guards (refuse with message, leave audio intact)
- Date: 2026-10-09
- Status: Accepted
- Decision: (1) One pure seam `RerenderGuards` owns the three planning-time refusals: read-only books refuse with the model `READ_ONLY_MESSAGE`, a missing engine/voice/model pack refuses naming the piece plus the voice-screen fix suffix, and low swap storage refuses with needed vs free byte counts (old plus new per the VS2 math, checked before a byte of new audio). (2) Every re-render entry point calls it before any write: the service intent (graph builds before planning so the voice check has a live registry; per-chapter voice plus storage re-checks run before spooling), the panel bulk plus per-chapter actions (read-only, then empty, then voices, then storage; refusals set the error line with no start), and the voice-screen apply flow (request plus confirm re-validate voices; NOW with stale audio pre-checks storage before persisting; LATER skips storage and defers to the service). (3) Unset roles refuse like a missing voice (the message names the role); a voice change on an unrendered book persists with no job and renders later on the normal first-render path. Playback never consults the guards (audio presence only), so existing audio keeps playing while planning refuses.
- Why: The VS6 brief needs identical messages plus byte-identical audio/manifest on every failure path; one seam keeps the service, the panel and the voice screen from drifting apart.
- Alternatives considered: per-entry ad-hoc messages (rejected: wording drift across three surfaces); skipping the whole-job voice block when only an unused role is missing (rejected for VS6: conservative block, documented in the service KDoc).
- Consequences / revisit when: device acceptance (VS7, #18) confirms the messages read well on the tablet; revisit the conservative whole-job block only with a real mixed-availability report.

### D-126: D-066 decided as option A - two licensed flavors (core Apache-2.0, full GPL-3.0)

- Date: 2026-10-09
- Status: Accepted
- Decision: Slice 12 plan section 3 option A is chosen. `core` stays Apache-2.0 and is the main release; `full` (sherpa-onnx with statically linked espeak-ng, plus Piper voice packs) is distributed under GPL-3.0 with a written source offer. D-064 stands for `core`; D-066 is closed by this entry. This is not legal advice.
- Why: Piper stays available to users; the flavor split was built for this call; the tablet runs System TTS as the primary engine (D-078, Piper measured 0.36x on the tablet), so `core` costs the tablet little.
- Alternatives considered: B release `core` only (rejected: Piper unavailable to ordinary users); C whole Player under GPL-3.0 (rejected: loses the permissive license for downstream users, wider than needed); D non-GPL phonemizer path (rejected: research and quality risk, out of scope for v2.0).
- VC1 compliance checklist (from Slice 12 plan section 4 VC1): GPL-3.0 license text shipped inside the `full` APK; written source offer for the `full` combined work; build instructions pinning the exact sherpa-onnx AAR version; per-flavor NOTICE plus THIRD_PARTY_LICENSES; in-app licenses screen entries for jsoup, sherpa-onnx, onnxruntime, espeak-ng, Piper, and the voice models; automated scan proving `core` carries no espeak-ng or sherpa-onnx files and `full` carries its notices; every new Verified box in `08-Licenses.md` ticked; per-voice license table complete.
- VC1 file survey (survey only, no code changed in VC0): `player/LICENSE` - exists today as single-file Apache-2.0 full text. `player/app/build.gradle.kts` - single app module with no product flavors, sherpa-onnx in main implementation. `player/gradle/libs.versions.toml` - sherpa-onnx pinned at 1.13.8 via JitPack with the static espeak-ng note. `player/settings.gradle.kts` - includes `:app` plus `:spike`, JitPack repo declared at settings level. New per-flavor LICENSE layout (plan: sherpa-carrying module gets its own LICENSE) - does not exist yet, VC1 creates it. `player/NOTICE` - exists as single-file Apache-2.0 notice stating the app ships no GPL code. `player/THIRD_PARTY_LICENSES.md` - exists as single file listing espeak-ng as Scribe-side only with no sherpa-onnx or onnxruntime rows. New per-flavor NOTICE plus THIRD_PARTY_LICENSES - do not exist yet, VC1 splits them per flavor. `player/app/src/main/java/app/auloud/player/settings/LicensesScreen.kt` - static list with no sherpa-onnx, onnxruntime, Piper, or bundled espeak-ng entries, header states the app ships no GPL code. New GPL-3.0 text asset plus written source offer in the `full` APK - neither exists in the repo today, VC1 adds them. New automated license scan hook (Gradle task or script checking `core` clean and `full` carries notices) - no scan task or script exists today, VC1 adds it. `docs/08-Licenses.md` section 5 plus section 7 - section 5 still describes the open choice, contribution-policy box decided by D-127. `README.md` License section - single-license wording today, VC1 adds the two-flavor wording.
- Consequences / revisit when: VC1 implementation stays open (flavor split, notices, in-app screen, scan, README and 08-Licenses updates); contribution policy (DCO sign-off or CLA, the last open box in 08-Licenses.md section 7) stays open and is not decided here.
  - 2026-10-09 review fixup: the contribution-policy sentence above is superseded by D-127 (DCO sign-off decided); rest of D-126 stands.
  - 2026-10-09 review fixup: VC1 survey gap noted: jsoup ships in main implementation (player/app/build.gradle.kts:116, MIT) but has no row in the player/THIRD_PARTY_LICENSES.md shipped table or in playerLicenses in LicensesScreen.kt; VC1 adds it with the full license table.

### D-127: Contribution policy is DCO sign-off
- Date: 2026-10-09
- Status: Accepted
- Decision: Contributors sign off commits with Signed-off-by lines (DCO); no CLA paperwork required.
- Why: Solo project with minimal overhead; sign-off lines are standard practice and need no extra paperwork.
- Alternatives considered: CLA (rejected: paperwork overhead for a solo project with no org to administer it).
- Consequences / revisit when: Contributors must sign off commits; 08-Licenses.md section 7 contribution-policy box is now decided and ticked (the box wording is purely "policy decided"). Enforcement remains a small CONTRIBUTING note explaining the sign-off step, left for VC1 or later. Revisit only if the project gains an org or needs stronger provenance.

### D-128: VC1 license implementation (D-126 option A as built)
- Date: 2026-10-09
- Status: Accepted
- Decision: One `license` flavor dimension with `core` (main Apache-2.0 release, no sherpa code) and `full` (GPL-3.0 combined work). sherpa-onnx 1.13.8 is `fullImplementation`-only. Same applicationId and version on both flavors so either upgrades a v1 install in place; no new permission, no new dependency, minSdk 24 unchanged. The sherpa-carrying config gets its own LICENSE note (`player/LICENSE.full`) plus per-flavor NOTICE and THIRD_PARTY_LICENSES; the interim do-not-distribute warning (52b47fe) is retired, replaced by true per-flavor statements. The `full` APK carries `gpl-3.0.txt` (byte copy of the piper-tts 1.8.0 wheel COPYING), `SOURCE_OFFER.txt` (3-year written offer with build instructions pinning the 1.13.8 JitPack AAR), and `NOTICE.txt`. Callers in `main` compile unchanged against a same-named per-flavor engine (real in `full`, empty stub in `core`); the piper version pin and the license lists live per flavor too. Scan: `:app:licenseScan` fails `core` on sherpa/onnx/espeak entry names or com/k2fsa + onnxruntime + espeak-ng-data dex markers, and fails `full` when sherpa files or the three assets are missing; per-flavor license-data unit tests prove each screen lists what its build ships. Scan entry-name matching is strict on files; app-level prose mentions (own class names, help text, Scribe-side rows) are not library code and are covered by the data tests instead. License paragraphs needing owner review before any full-APK distribution: the GPL-3.0 paragraphs in NOTICE.full, the placeholders (publisher name, contact, repo URL) in SOURCE_OFFER.txt, and the LICENSE.full note.
- Why: Smallest split that makes `core` distributable now and `full` compliant later, with the proof automated.
- Alternatives considered: separate :piper module (rejected: flavor source sets carry the same separation with no new module); renaming the engine to dodge its own name in scans (rejected: churn with no user benefit, scan checks library markers instead); hooking the scan into `check` (rejected: every test run would build two APKs; the exact command is in docs/test-log.md instead).
- Consequences / revisit when: VC1 closes the D-126 implementation item; the owner must confirm the three review texts plus fill the offer placeholders before distributing `full`. Scribe license rows in 08-Licenses.md section 3 stay open (not VC1 scope). Revisit the scan markers only if the sherpa AAR layout changes.
