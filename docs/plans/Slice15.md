# Slice 15 Implementation Plan: Rust Workspace, Spec Core and Parity Harness

Project: Auloud Scribe, Rust port (D-133: Rust + Tauri v2, Windows 11 and Linux, Python canonical until parity, `spec/` unchanged, AGPL-3.0-or-later). Follows `docs/10-V3Roadmap.md` (Slice 15). Slice 14 (Kitten) is parked on device-only items and does not block this slice.

## 1. Goal

Stand up the Rust workspace and prove its **spec layer** against the Python Scribe: models for every bundle form, the version gate, and a validator that gives the same verdicts and the same error messages on every fixture. Add a parity harness that will carry the later slices (draft, attribution, build) and a doctor skeleton.

**Done when** (from the roadmap): all golden bundles validate identically in Rust and Python; each bad fixture fails with the same message.

## 2. What is known and what is not

**Known (verified 2026-10-11 in the repo)**

- The Python validator is `scribe/bundle/validate.py` over `scribe/bundle/models.py`. Errors are plain `"file: message"` strings; there are **no rule ids** in Python output, so RS1 must build the template-to-id map (see decision 5).
- Audio probing runs ffprobe as a subprocess. CBR for MP3 means one extra ffprobe call (`packet=size`) with max minus min frame size at most 1 byte; AAC chapters skip the frame check and use a 16 kbps average band. Duration tolerance is 50 ms for MP3 and provisionally 50 ms for M4A. Fixture MP3s are 0-byte placeholders, so tests inject a probe stub.
- Bundle fixtures with `manifest.json`: `valid-bundle`, `scribe-golden`, `multivoice-golden`, `partial-golden`, `partial-aac-golden`, `rendered-aac-golden`, `pdf-golden`, `unrendered-golden`, `epub2-minimal`, plus bad fixtures `bad-json`, `missing-field`, `missing-mp3`. Non-bundle dirs (`ingest-parity`, `timing-vectors`, `speakers-gold`, `dialogue-cases.json`) are not validate inputs.
- Both test suites address fixtures by explicit path, never by directory glob, so additive fixture files are safe.
- This machine has no Rust toolchain (no `rustc`, `cargo` or `rustup`), no `scribe-rs/` or `parity/` dirs, and the repo has no `.gitattributes`.
- `docs/DECISIONS.md` ends at D-140. Slice 14 is parked with its decisions unlogged.

**Not known (this slice must find out)**

- Whether Rust `unicode-normalization` and Python `unicodedata` ever disagree on edge characters (fixtures decide; versions get recorded).
- How much ffprobe output varies across versions on the CBR and bitrate paths.
- Windows toolchain friction (MSVC, WebView2 for the Tauri stub); RS0 gates on it first.

## 3. Scope

| In | Out (later slices) |
| --- | --- |
| Workspace, toolchain pin, lints, license policy | EPUB ingestion (Slice 16) |
| Models for every bundle form; spec 1.0 to 2.0 and the version gate | Attribution and cast (17) |
| Pure rule checks and filesystem loader; media probe as a `scribe-core` module | TTS, audio assembly, `build` (18) |
| `validate` and `doctor` skeleton in the CLI | PDF extraction and the full CLI matrix (19) |
| Parity harness (validate mode working; other modes stubbed) | The Tauri app itself (20) |
| Tauri hello-window toolchain check | Models and engines (21), installer (22) |

## 4. Key decisions (log from the next free D-number at write time)

D-141 is next as this plan is written, but Slice 14's parked decisions will also take numbers on close, so **always check the log tail first** and note the plan section in each entry. The eight decisions below are labeled S15-1 to S15-8 until logged.

1. **Crate split: four crates under `scribe-rs/`.** `scribe-spec` (pure data: serde models, spec version gate, error type `{file, rule, message}`, pure rule checks over in-memory data; no file or process access), `scribe-core` (filesystem loader, UTF-8 and NFC checks, hashing, media probe module, orchestration, doctor checks; later slices add extraction, attribution and build here), `scribe-cli` (binary named **`scribe-rs`** during the parity period, renamed at v3.0), `scribe-ui` (Tauri crate, a stub in this slice). The media probe is a `MediaProbe` trait plus an ffprobe subprocess implementation plus a fake for tests, kept as a module inside `scribe-core`: one trait with one real implementation does not need its own crate. The workspace sets `default-members` to the three non-UI crates so everyday `cargo test` stays fast.
2. **Harness location and form: `parity/`, a stdlib-only Python driver run with `uv`.** Python is canonical, so the driver imports the Python library directly (structured result from `validate_bundle` with a stub probe, no console scraping) and runs the Rust CLI with `--json`, then compares normalized results. Rust only has to emit stable JSON. No new Python dependency; the driver uses the stdlib only.
3. **Which books the harness runs: three tiers in `parity/books.toml`.** Tracked fixtures (always run): every bundle dir under `spec/fixtures/` named in section 2. Tracked sources (run in later slices): the ingest-parity EPUBs and PDF goldens, the speakers-gold passage, and the Alice passage once promoted to `spec/fixtures/` (planned for Slice 17). Local-only books: listed in gitignored `parity/books.local.toml` (never commit absolute local paths), run when present, skipped with a clear message otherwise. Validation mode runs on the first tier plus any Python-built bundles found locally.
4. **Expected results come from Python, stored under `parity/expected/`** (one file per fixture, mirroring the fixture name), generated by a small Python dev script. Keeping them out of `spec/fixtures/` leaves the contract dir clean for the Player and Scribe suites. Rust contract tests compare to those files; Python stays the source of truth, and changing a Python rule means regenerating expected files and updating Rust (the harness enforces this).
5. **What "identical" means.** RS1 writes the rule table in `docs/rust-port.md`: rule id, the Python message template it matches, which fixture covers it. The export script assigns each Python error a rule id by template match and fails loudly on any unmatched message, so a Python wording change breaks the export instead of silently passing. The harness compares sorted `(file, rule, message)` triples; the sort key is defined once in `parity/README.md`. Messages that embed a parser's own detail text (for example a JSON syntax error position) compare on rule id plus message prefix only. Whole-message comparison normalizes path separators to `/` and strips the local bundle-root prefix so Windows and Linux match. The Python list rendering in the spec-version message (`must be one of ['1.0', '1.1', '1.2', '2.0']`) is pinned byte-for-byte in the rule table.
6. **Binary name `scribe-rs`;** human output and exit codes match Python `validate` (`valid: <bundle>` on success, `error: ...` lines, exit 1 on failure) and `doctor` (table plus nonzero exit on any FAIL). `--json` is a Rust-only harness extra on both commands; the Python CLI is untouched.
7. **Dependency policy: small, permissively licensed crates only** (serde, serde_json, thiserror, clap, unicode-normalization, sha2; dev: tempfile and a CLI test helper). `cargo deny` enforces a license allow-list from RS0; no dependency is added without a deny pass.
8. **Line endings: add `.gitattributes`.** Fixture JSON, YAML, Markdown and TOML stay LF; audio, PDF, image and model files are binary. This stops Windows checkouts from changing bytes (the same noise already shows in Player diffs).

## 5. Work packages

### RS0: Toolchain and workspace skeleton (S-M), do first

- Install Rust with rustup and the MSVC build tools on Windows 11 (the "Desktop development with C++" workload); pin the toolchain in `rust-toolchain.toml`; install `cargo-deny`.
- Create the workspace and the four crates; each crate declares `AGPL-3.0-or-later` with SPDX headers like the Python files.
- `rustfmt` and `clippy` (warnings are errors); `cargo deny` config with the license allow-list; `.gitattributes` (decision 8).
- `scribe-ui` hello window with Tauri v2 (last step of RS0, after the workspace gates pass, so WebView2 friction cannot block spec work).
- Add the project skill `rust-scribe` (workspace layout, no panics across crate boundaries, fixture-driven tests, ffmpeg as a subprocess) and update `AGENTS.md` (repo layout, cargo commands, Rust+Linux line next to the Python Scribe line).
- **Verify:** `cargo build`, `cargo test`, `cargo clippy -D warnings`, `cargo deny check` pass on a Windows machine with no prior Rust install; the hello window opens (owner confirms); decisions S15-1, S15-7, S15-8 logged.

### RS1: Rule catalog and expected results (M)

- Read the Python models and validator plus the spec; write the rule table in `docs/rust-port.md`: rule id, Python message template, which fixture covers it.
- Python dev script (`scribe/dev/export_expected.py`) that writes `parity/expected/<fixture>.json`: `{valid, errors: [{file, rule, message}]}` with rule ids from template match (loud failure on unmatched), normalized and sorted per decision 5.
- Add fixtures for any rule without coverage (valid and invalid; additive test data only, no spec change).
- **Verify:** every rule has at least one fixture; expected files committed; the Python suites still pass unchanged (no validator behavior change; a suspicious rule gets logged and raised, never fixed in one implementation only).

### RS2: Spec models and version gate (M)

- Serde models for: manifest (EPUB, PDF, unrendered, partial, MP3 and AAC audio entries, per-voice engine/voice/speed/pitch, manifest-level `gain_db` and `encoder_offset_ms`, per-chapter `render_fingerprint`, `source_index`, `render_state`), chapter text (blocks, sentences with kind and speaker, optional timings, spans, page), PDF `pages` marks.
- Unknown keys ignored; non-integer numbers where integers are required rejected with the right rule; spec version gate (accept 1.0 to 2.0, refuse newer, handle missing and malformed) with wording pinned to the Python template including the list rendering.
- **Verify (tests):** every valid fixture deserializes; gate cases (1.0, 1.1, 1.2, 2.0, a newer minor, a newer major, missing, malformed).

### RS3: Pure rule checks (M-L)

- All non-media rules on in-memory data: conditional required fields by `render_state`, consecutive sentence ids, timing rules (first start 0, `0 <= start < end <= duration`, ordered, no overlap), speakers present in `voices`, voice entry shapes, span offsets inside the text, page marks, `source_index` and partial-book rules, manifest-versus-chapter duration agreement within tolerance.
- Deterministic error ordering and the exact message templates from RS1.
- **Verify (tests):** unit tests per rule; contract test over the non-media fixture subset against `parity/expected/`.

### RS4: Filesystem loader and media probe (M)

- Bundle loader: reads files, checks UTF-8 and NFC, existence, source hash, path safety (no traversal out of the bundle root).
- `MediaProbe` trait with the ffprobe implementation using the same method as Python (stream codec/channels/rate/bitrate/duration plus the MP3-only packet-size CBR check, max minus min at most 1); MP3 and AAC tolerance rules from the spec; clear error when ffprobe is missing.
- **Verify (tests):** contract test over **all** fixtures including media rules (stub probe for the 0-byte fixture audio, real ffprobe on generated MP3/M4A for the media paths); unit tests with the fake probe; tests skip with a clear message when ffprobe is absent (plus one environment test that fails when it is absent, so the skips cannot hide lost coverage).

### RS5: `validate` and `doctor` in the CLI (S-M)

- `scribe-rs validate <bundle> [--json]` with exit codes and human output matching Python; `--json` emits the normalized form the harness compares.
- `scribe-rs doctor [--json]` skeleton: ffmpeg and ffprobe (presence and version), workspace writable, toolchain info; row names and pass/fail wording follow the Python doctor so the later parity matrix is simple.
- **Verify (tests):** CLI tests for every fixture's exit code and JSON; doctor behaves with and without ffmpeg on the path.

### RS6: Parity harness (M)

- `parity/` driver (stdlib only, `uv run python parity/run.py`): `books.toml` plus the `books.local.toml` overlay, a mode registry (validate implemented; draft, attribute and build present as "not implemented" so later slices only add a function), normalization and diff reporting, `--update-expected`, exit code 1 on any difference.
- Per-case report table; machine-readable summary; comparison rules (decision 5) in `parity/README.md`, including the sort key, the parser-detail prefix rule, and path normalization.
- **Verify:** runs green on all current fixtures; a deliberately broken fixture copy and a deliberately altered message each produce a clear diff (generated in temp dirs as harness self-tests, never committed as fixtures); runtime recorded.

### RS7: Gates and close-out (S)

- Full Python suite (`uv run pytest` in `scribe/`) and full Rust suite on Windows, and on Linux or WSL if available (path and line-ending differences show up here).
- Final `docs/rust-port.md` (architecture, rule table, parity levels, how to add a rule); `AGENTS.md` and the `rust-scribe` skill current; decisions, roadmap and test-log updated.
- Tag `slice-15`; list the inputs Slice 16 needs (`ingestion-rules.md` export, ingest-parity fixtures, dialogue cases).
- **Verify:** every box in section 7 ticked.

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | RS0 | build and test an empty Rust workspace and see the hello window |
| 2 | RS1 | have the rule catalog and expected files with coverage gaps filled |
| 3 | RS2, RS3 | see every fixture pass pure rules in Rust |
| 4 | RS4, RS5 | validate whole bundles including audio from the CLI |
| 5 | RS6, RS7 | run the harness and close the slice |

## 7. Definition of done

- [ ] Toolchain pinned; `build`, `test`, `clippy` and `cargo deny` pass; hello window verified
- [ ] Rule catalog documented; every rule covered by a fixture with a Python-generated expected file
- [ ] All golden bundles validate identically in Rust and Python; each bad fixture fails with the same `(file, rule, message)` under the comparison rules
- [ ] Version gate matches Python on all cases; `doctor` skeleton works
- [ ] Harness runs on Windows (and Linux or WSL if available), and detects deliberate differences
- [ ] Python suites unchanged and green; decisions logged; roadmap ticked only for verified items; tagged `slice-15`

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Message wording drifts between implementations | Rule catalog, Python-generated expected files, harness comparison on every fixture; export fails loud on unmatched templates |
| Python has no rule ids to compare against | Template-to-id map in RS1; rule ids assigned at export, compared as part of the triple |
| Parser-specific error text (JSON syntax) differs | Compare rule id and prefix only for those messages (decision 5) |
| Unicode normalization differs between Python tables and the Rust crate | Fixtures with edge characters; record both Unicode versions in the report; any difference is a finding |
| ffprobe output or CBR detection differs across versions | Same detection method as Python, pinned in RS4; log the ffprobe version in reports; tests skip cleanly when absent with one environment test guarding coverage |
| Windows path separators and CRLF checkouts change results | Normalize separators and strip the bundle root in comparison; `.gitattributes`; run on both platforms |
| Rust toolchain friction on Windows (MSVC, WebView2) | RS0 first with the hello window gate last inside it; written install steps; owner installs |
| Python rules change while the port proceeds | Any Python rule change breaks the export or the harness until Rust matches; never fix in one implementation only |
| Scope creep into ingestion | Out of scope; stubs only; Slice 16 owns it |
| License slip from a new crate | `cargo deny` allow-list in RS0; no dependency without a deny pass |
| D-number collision with parked Slice 14 | Check the log tail at each write; note the plan section in every entry |

## 9. Working with the agent

- One work package per session on `dev/v3`; it writes Rust, runs `cargo` checks and the harness, and edits the Python dev scripts; **you** install the toolchain, confirm the hello window, and run the Linux or WSL pass.
- Do not change Python validator behavior; if a rule looks wrong, log it and raise it rather than "fixing" it in only one implementation.
- No new dependency without a `cargo deny` pass; no bundle-format change (fixture adds are additive test data only).
- Commit per work package (`RSn: summary`); tag `slice-15`.
- Rust code and docs both avoid the two banned glyphs per `AGENTS.md` (em-dash and unicode arrow).

## 10. Next

Slice 16 (ingestion port) reuses this harness by adding a `draft` mode: chapters and blocks exact against Python on the golden EPUBs, dialogue runs matching, sentence-boundary agreement reported.
