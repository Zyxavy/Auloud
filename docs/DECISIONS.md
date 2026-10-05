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

### D-066: v2 license path for espeak-ng (open, decided after the spike)

- Date: 2026-10-05
- Status: Open (Slice 7 gate decides, with a license review; not legal advice)
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
