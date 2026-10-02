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