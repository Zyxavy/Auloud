# Auloud v3 Roadmap: Scribe Desktop App (Rust + Tauri) and the KittenTTS Engine

## 0. Premise and assumptions (verified)

v3 is the Scribe Rust/Tauri desktop port and all Android platform work moves to v4 (D-133, verified 2026-10-10: Rust with a Tauri v2 desktop app for Windows 11 and Linux; the Python Scribe stays canonical until parity; the port honors `spec/` unchanged; the port stays AGPL-3.0-or-later). Scribe's current shape matches this plan's assumptions (verified in `scribe/pyproject.toml`): Python CLI plus library, local web UI, spaCy (`en_core_web_sm`), `kokoro-onnx`, `piper-tts` with `onnxruntime`, PyMuPDF, `ebooklib`, ffmpeg as a subprocess.

**Before v3 starts:** the audit fix plan FP1-FP7 is done (committed). Still open: the 2.1.0 smoke test and 2.0 to 2.1 over-install proof, the push, PR and release page.

## 1. Goals and non-goals

**Goals**

- One installable desktop app: no Python, `uv`, spaCy or manual model wiring; first-run setup downloads models with checksums (or accepts sideloaded packs)
- Same behavior as today: the CLI and the web UI workflow produce spec-valid bundles, multi-voice by default, with the same wording of errors
- Keep the existing web UI (design system, views, flows) as the frontend, hosted natively by Tauri
- Add the KittenTTS engine as an optional voice tier on both the Player and the new Scribe

**Non-goals:** new bundle features, on-device Android changes (v4), iOS (v5), cloud services, auto-update, polishing macOS and Linux beyond best-effort builds.

## 2. Key decisions (log from the next free decision number, currently D-141)

1. **Parity is defined by fixtures, not by reading the Python code.** The Rust port is a third implementation validated by the same shared fixtures and vectors the Kotlin Player uses (`spec/fixtures/`: golden bundles, timing vectors, dialogue cases, ingest-parity, speakers gold, PDF goldens) plus a comparison harness against the Python Scribe on the golden books.
2. **Compatible on-disk formats:** bundles (spec 1.x to 2.0), `script.json`, `cast.yaml`, work-folder layout and job folders stay compatible so you can move between the Python and Rust versions. The sentence cache is **not** shared (engine ids and versions differ); a rebuild re-renders once.
3. **Speaker attribution is the main technical risk.** The Python version uses spaCy's dependency parse (92.5% strict on the 107-line C&P gold set in `spec/fixtures/speakers-gold/`). Options: (a) rules plus a lightweight tagger in Rust, (b) an optional Python "NLP pack" sidecar behind a flag for best accuracy, (c) both, with the Rust baseline as default. A **numeric gate** in Slice 17 decides: the Rust default must stay within an agreed margin of the Python baseline on the gold sets, otherwise the sidecar option ships.
4. **One TTS runtime where possible:** sherpa-onnx's C API through Rust bindings for Kokoro, Piper and Kitten (same runtime and phonemizer story as the Android `full` flavor; a `sherpa_onnx` crate with safe bindings including the Kitten and Kokoro configs exists), unless a spike shows direct ONNX Runtime is faster or simpler for Kokoro. Speed must at least match today's measured RTF 4.38x on CPU (`docs/test-log.md`).
5. **Encoding stays ffmpeg** as a bundled sidecar process in v3.0 (streaming CBR MP3, the 50 ms duration gate); a native Rust encoder is a later option. Bundling ffmpeg and its license are handled in the packaging slice.
6. **PDF through pdfium** (permissive BSD-style plus Apache-2.0 license text, not copyleft) instead of PyMuPDF (AGPL). Parity is judged on the PDF gold files.
7. **Frontend reuse:** keep the HTML/CSS/ES-module UI; replace HTTP and SSE with Tauri commands and events. No local network listener, so the localhost security work (Host check, token) is not needed in the desktop app, and the CLI remains available. The job model (detached process versus in-process threads) gets decided in Slice 20 from what Tauri supports well; the sentence-level resume and pause semantics must stay identical.
8. **Licensing:** the port stays AGPL-3.0-or-later unless dependency changes allow otherwise (espeak-ng is GPL-3.0; ebooklib and PyMuPDF are no longer used). Log the decision and update `08-Licenses.md`.
9. **Stop-loss:** if the attribution or speed gate fails badly, fall back to a **Tauri shell around the existing Python Scribe** as a sidecar (heavier install, far less rewriting) and record why.

## 3. Slices

### Slice 14: KittenTTS engine (Player `full` flavor) (M)

Small, independent, and a quick win; do it first.

**Facts (verified 2026-10-10 against sherpa-onnx docs and the KittenML repo):** sherpa-onnx supports KittenTTS models (nano, micro, mini, including nano int8 at about 25 MB); a voice pack needs the model file, `voices.bin`, `tokens.txt` and an `espeak-ng-data` directory. There are 8 legacy voices (Bella, Jasper, Luna, Bruno, Rosie, Hugo, Kiki, Leo), English only, 24 kHz. The pinned sherpa-onnx 1.13.8 is current and retains Kitten support; the AAR ships `armeabi-v7a`, but the Kitten config on 32-bit ARM is specifically unproven. License caveat: legacy weights are marked Apache-2.0, but the KittenML repo warns models are licensed separately (KittenTTS-2 uses its own license), and the runtime needs espeak-ng data (GPL-3.0). Verify both before bundling anything; the engine stays in the GPL `full` flavor regardless.

Work packages:

- **KT0 Prep:** confirm the pinned sherpa-onnx 1.13.8 supports the Kitten config on 32-bit ARM; define the Kitten pack manifest (variant, files, checksums, voices with ids and genders); verify the model and voice licenses given the caveat above; log decisions
- **KT1 Engine adapter:** `SherpaKittenEngine` behind `TtsEngine`, namespaced ids `kitten:<voice>`, lazy load and release, speed parameter, thread count, off-main-thread synthesis; fake-native-layer JVM tests like the Piper adapter
- **KT2 Packs and registry:** pack discovery and validation for Kitten packs (same sideloaded folder flow), engine registration in `full` only; `core` flavor scan stays green (no espeak or sherpa files)
- **KT3 Voice lab and benchmark:** list Kitten voices, audition both roles, benchmark categories (Fast, Background, Too slow) with warmed RTF, load time and memory, for **nano int8, then micro**; mini only on faster devices
- **KT4 A/B listening protocol:** the same book passage with System TTS, Piper (low and medium), Kitten nano int8 and micro; 10-minute listens to judge fatigue; record scores and speeds in `docs/test-log.md`
- **KT5 Recommendation logic:** update the per-device engine recommendation so Kitten can be the recommended neural tier on the tablet if it wins; voice mapping for engine switches covers Kitten voices
- **KT6 Render check:** render one chapter of an imported book on the Tab E with Kitten narrator and dialogue voices; confirm read-along timing and stale-chapter behavior still work
- **Done when:** the Voice lab lists Kitten voices, benchmark numbers for nano int8 and micro on the Tab E are recorded, the A/B result and recommendation are logged, a chapter renders on-device with Kitten, and `core` is unaffected
- **Possible follow-up:** if a Kitten variant sustains well above 1.3x on the tablet, open a backlog item for neural live streaming (separate from the System TTS streaming from Slice 13)

### Slice 15: Rust workspace, spec core and parity harness (M)

- Cargo workspace: `scribe-core`, `scribe-cli`, `scribe-ui` (Tauri), shared crate for the spec
- Bundle models and validator for spec 1.0 to 2.0 with identical rule wording; version gate; `doctor` skeleton
- Fixture contract tests over every golden bundle and vector; a comparison script that runs the Python Scribe and the Rust Scribe on the same inputs and diffs structure, text and timings
- **Done when:** all golden bundles validate identically in Rust and Python; each bad fixture fails with the same message

### Slice 16: Ingestion port (L)

- EPUB container, OPF, TOC (nav and NCX), cover, DRM refusal; HTML to blocks with spans; cleaning rules from `ingestion-rules.md`; sentence splitting with the same post-fixes; dialogue detection (shared vectors); `draft` without attribution
- **Gate:** chapters and blocks exact against Python on the golden EPUBs; dialogue runs match; sentence-boundary agreement reported

### Slice 17: Attribution, cast and the NLP gate (L)

- Candidate extraction, the five rules with confidence, alias clustering, gender hints
- `cast.yaml` (merge that never clobbers, overrides anchored by chapter, block and quote number), resolution order, `cast_report.md`
- **Numeric gate:** evaluate on the C&P gold set (`spec/fixtures/speakers-gold/crime-and-punishment-2ch.yaml`, Python baseline 92.5% on 107 lines); promote an Alice passage fixture from `logs/alice-work/` first so the gate runs on two sets. Decide decision 3 (rules only, optional sidecar, or both) with the numbers in `docs/test-log.md`

### Slice 18: TTS engines, assembly and `build` (L)

- Engine trait and runtime per decision 4: Kokoro first, then Piper; phonemization; fingerprint cache; resampling; per-voice leveling; pauses and the 100 ms tag pause; timings from sample counts against the shared timing vectors
- ffmpeg streaming encode, 50 ms duration check, `build` with resume, `--plan`, chapter ranges and spec 1.2 range bundles
- **Gate:** speed at least equal to Python (RTF 4.38x on CPU per `docs/test-log.md`) and bundles validate; compare durations and timings with the Python bundle on the golden books

### Slice 19: PDF and CLI parity (M-L)

- pdfium extraction (columns, hyphenation, headers and footers, scanned detection, determinism), page marks for spec 1.1
- Remaining commands: `inspect`, `validate`, `voices --sample`, error wording, exit codes; a CLI parity matrix against the Python version
- **Done when:** every documented command behaves the same on the golden books

### Slice 20: Tauri app shell (L)

- Existing web UI hosted in Tauri; commands and events replace HTTP and SSE; native file dialogs for books and for the Transfer step; workspace settings
- Job runner with pause, resume, cancel, keep-awake, history, reattach after restart (decision 7)
- Books, Book detail, Cast, Voices, Jobs views working; design system and accessibility preserved
- **Done when:** upload to valid multi-voice bundle works entirely in the app

### Slice 21: Engine expansion and model manager (M)

- Piper and **Kitten** engines in the Rust Scribe (namespaced `engine:voice` in `cast.yaml`; bare names still mean Kokoro), the engine selector in the cast view, grouped voice lists, audition clips cached by voice and engine version
- Model manager: first-run downloads with checksums and progress, sideload option, size and license display, delete; CPU and optional GPU provider selector only if a measurement shows a benefit
- **Done when:** a mixed Kokoro, Piper and Kitten chapter builds and validates; models install and update without touching files

### Slice 22: Packaging and v3.0 release (M-L)

- Windows installer with bundled pdfium, espeak data, ffmpeg sidecar and the shared fixtures for self-test; macOS and Linux builds best-effort
- NOTICE, in-app licenses, SBOM; README; first-run wizard; **migration** from a Python workspace (work folders, `cast.yaml`, job history)
- **Soak:** a full multi-voice novel built in the app, compared with the Python bundle (text exact; durations and timings within tolerance); a pause, kill and resume; a Tauri UI runbook on Windows
- Tag `v3.0.0`; decide whether the Python Scribe is archived or kept as a reference during 3.x

## 4. Order, gates and checkpoints

| Step | Slices | Gate |
| --- | --- | --- |
| 1 | 14 | Kitten benchmark and A/B recorded |
| 2 | 15, 16 | Structure and text parity on golden books |
| 3 | 17 | **Attribution gate** (numbers decide the NLP approach) |
| 4 | 18 | **Speed and bundle parity gate** |
| 5 | 19, 20 | CLI parity matrix; app completes an end-to-end build |
| 6 | 21, 22 | Release criteria |

## 5. v3.0 release criteria

- [ ] KittenTTS pack works on the Player `full` flavor and in the Rust Scribe, with licenses verified
- [ ] Rust Scribe produces spec-valid bundles identical in structure and text to Python on the golden books; audio durations and timings within tolerance
- [ ] Attribution accuracy within the agreed margin of the Python baseline on both gold sets
- [ ] Build speed at least equal to the Python version on CPU
- [ ] Full novel build, pause, kill and resume, and range builds work in the app
- [ ] Installer works on a clean Windows machine with no Python; models installed through the manager
- [ ] Licenses, NOTICE and SBOM complete; decisions logged; tagged `v3.0.0`

## 6. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Attribution quality drops without spaCy | Numeric gate in Slice 17; optional Python sidecar; rule improvements measured on gold sets |
| TTS runtime in Rust slower or harder to package | Slice 18 gate; sherpa-onnx or direct ONNX Runtime, whichever measures better; stop-loss option |
| Phonemizer and licensing (espeak-ng GPL) | Stay AGPL-compatible; document; same pattern as the Android `full` flavor |
| Kitten model license split (weights versus code) | Verify before bundling in KT0; keep the engine optional and `full`-only |
| ffmpeg bundling and license | Decision in Slice 22; consider native encoder later |
| Scope and duration of a full rewrite | Gates after Slices 17 and 18; fixtures catch drift early; stop-loss shell around Python Scribe |
| Kitten quality or speed disappoints on the tablet | It is optional; benchmark first; keep Piper and System TTS recommendations |
| Windows packaging problems (WebView2, code signing, SmartScreen) | Early installer smoke builds; unsigned for personal use, signing decision later |
| Drift between Python, Rust and Kotlin implementations | Shared fixtures and vectors as contract tests; parity harness |
| Time spent on v3 delays v4 Android work | v3 gates allow stopping; Slice 14 delivers value on its own |

## 7. Working with the agent

- Per-slice plans like the earlier ones (work packages with Verify steps) are written when each slice starts; start with Slice 14 and Slice 15
- Add a `rust-scribe` project skill (workspace layout, no panics across the boundary, fixture-driven tests, ffmpeg as a sidecar process) and update `AGENTS.md`
- The agent writes and tests code and runs the parity harness; **you** make the NLP and runtime gate calls, run the installer on a clean machine, and do every listening test
- No new dependency without a license check; log each decision (next free number: D-141)

## 8. After v3

v4 (Android platform: newer Android, `targetSdk`, word-level highlight on API 26+, storage changes), v5 (iOS and iPadOS Player with xtool, see `docs/V5-iOS-support.md`), and the v1.1 backlog: LLM attribution, real EPUB rendering, PDF Page view, Wi-Fi transfer, bookmarks, themes, auto-drafted cast.
