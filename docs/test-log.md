# Device test log

Confirmed runs on the Tab E only. Template per check (Slice 3 checks: see `docs/Slice3-Runbook.md`):
```
Date / build (commit) / device state:
Test: C#
Steps:
Expected:
Actual (include overlay readings):
Result: pass | fail | needs retest
```

---
## MV10 acceptance (2026-10-02, user listening + tablet sign-off)

- C&P 2-chapter (`CRIME AND PUNISHMENT.epub`, local): `scribe build` → 2 chapters, 58:32 audio, 632 sentences, validates clean; RTF 24.77x (mostly cache). Speakers: narrator 27 min, orator 16 min, Marmeladov/default_female 4 min each. User casting fixes applied (Marmeladov→am_eric, Raskolnikov→am_adam, old woman→bf_isabella, poor woman→af_bella).
- Alice fragment (107 sentences): 10:33 audio, RTF 4.13x fresh synth, validates clean. Alice (bf_isabella, 38 lines + 3 aliased "poor Alice") clearly distinct from narrator (am_onyx).
- Override round-trip (headline check): override on Alice (1,10,3) → rebuild rendered exactly 1 line (106 cached, 1 synthesized) with flipped voice; revert → 107 cached, 0 rendered, byte-identical duration. Caught and fixed on the way: `speaker: narrator` in overrides fell through to `default_female` (fixed, `79c9e9c`, 438 tests green).
- Tablet: multi-voice bundles import, play, and track highlight with taps per speaker (user-confirmed).
- Slice 4 "done when" signed off by the user: clearly multi-voice by ear; cast edits fix lines with build-only re-renders.

---
## CP1 gold set and tuning (2026-10-02)

- Gold: `spec/fixtures/speakers-gold/crime-and-punishment-2ch.yaml`, 107 dialogue lines from C&P Part I ch 1-2, every speaker hand-checked (machine pre-labels + user correction). Supersedes and deletes the 5-line MV1 seed (same keys, conflicting convention; see D-040).
- Real-text eval (`--predictor mv4-real`, strict): **99/107 = 92.5%** overall; high 13/13, medium 27/31, low 59/63; explicit 13/13, pronoun 25/29, continuation 22/22, fallback 39/42, unknown 0/1.
- All 8 gaps are lines the machine leaves `unknown` (interior thought, unattributed shouts); **zero wrong-person errors**. Alias-aware scores 100% but that matches machine-written surfaces, so 92.5% strict is the honest headline.
- Tuning: no rule changes (nothing to fix without overfitting 8 convention lines); low outscoring medium noted as calibration watch, not action.
- Weak spots (also in README): unattributed lines fall back to generics; crowded multi-speaker paragraphs; two ch-2 paragraphs with stray closers treated as narration.

---
## CP2 soak build (2026-10-02, 2-chapter C&P per user choice)

- No rebuild needed: CP0-CP1 changed no library code (dev scripts, tests, gold, docs only), so the MV10 bundle `0e4b290b...` is current.
- `scribe validate`: valid. `scribe inspect --speakers`: 2 chapters, 58:32, 8 voices (narrator 27 min, orator 16 min, Marmeladov/default_female 4 min each, old woman, poor woman, Raskolnikov, default_male); matches the MV10 numbers exactly.
- Note: with the 2-chapter book instead of a 10-hour novel there is no long build pole; the schedule's CP2 parallelism rationale does not apply. Copy to tablet whenever convenient; rebuild only if later Scribe changes affect audio or text output.

---
## CP3 chapter navigation (Player, JVM verified)

- Chapter list screen with titles, durations, current marker, tap-to-jump keeping shared position rules; next/previous controls and mode state on jumps.
- Automated: Player JVM suite green (340 tests reported for the session), incl. list state, jump seek, current marker. Found and fixed on the way: an off-by-one in chapter jump.
- Device remainder: jumping works in all three modes on the Tab E (user).

---
## CP4 error handling and import validation (Player, JVM verified)

- Import validation covers manifest, audio files, and each chapter text file; problems listed with file and rule; book still importable when only some chapters are bad. Storage loss pauses with "Storage unavailable - playback paused", Play retries. Corrupt/missing chapter at play time skips with a transient auto-dismissing notice; missing text stays reader-only while audio continues. SAF cover resolution fixed.
- Automated: 41 Player suites green (session count), incl. BundleValidatorTest, BundleResilienceTest (truncated JSON, wrong types, huge values), SkipNoticeTest, storage tests. See D-041 for the consume rule and consecutive-failure heuristic.
- Device remainder: eject-and-reinsert pause/resume, bad-bundle import with message, skip message on a real corrupt chapter (user).

---
## CP5 PDF extraction in Scribe (unit verified)

- PyMuPDF 1.28.2 text-layer extraction (`get_text("blocks")`, image blocks dropped; two-column left-first; hyphen rejoin on lowercase continuations; header/footer, page-number, footnote, heading rules), chapters from outline else headings else 20-page ranges, scanned detection (over half pages without text fails as DraftError, no OCR), PDF quality section in `draft_report.md`, deterministic block ids.
- Automated: Scribe suite green (464 tests reported for the session), incl. synthetic clean/header/two-column/scanned PDFs plus determinism. `scribe doctor` gains the pymupdf row. See D-042.
- Device remainder: none (PC-side); real-PDF quality judged in CP11 soak.

---
## CP6 PDF through the pipeline and the spec (both suites verified)

- Spec v1.1 additive: PDF text-path chapters keep `blocks`, MAY add `pages: [{page, start_ms}]` plus optional sentence `page` (1-based, absent for EPUB, never null); readers accept "1.0" and "1.1". Validator enforces sorted/pages-match-first-sentence/page-in-range plus coverage. PDF-safe speaker remap variant carries `page`/`pages`. Golden PDF bundle `spec/fixtures/pdf-golden/` (synthetic 2-page, 4 sentences, tones not speech) validates; Player contract parses it.
- Automated: Scribe 482 + Player 399 green (session counts); pdf-golden pinned in both suites. See D-043.
- Device remainder: none (contract); playback of a real PDF text bundle is CP7/CP11.

---
## CP7 Player PDF support, Text view (JVM verified, gate closed)

- Blocks-form PDF chapters read through the unchanged EPUB reader path (no playback/reader logic change). Pure-pages message reworded to "Page-only chapter - listening still works". Chapter list gains display-ready optional `pageRange` (still unpopulated: filling it would load every chapter JSON against the one-at-a-time rule).
- Automated: Player JVM suite green (413 tests reported for the session), incl. new pdf-golden reader tests and page lookup/seek mapping. Gate: user judged clean text sufficient, so no `PdfRenderer`, no toggle (D-045); Page view deferred to v1.1 as a reader option (D-046).
- Device remainder: read + listen on a real PDF, memory stability on 1.5 GB RAM (user).

---
## CP8 release build and packaging (JVM verified, device pending)

- Release `minifyEnabled` + `shrinkResources` with keeps for kotlinx.serialization, Room, Media3, Guava futures, Coil; `versionName 1.0.0`, `versionCode 1`. Signing via gitignored `local.properties` (`auloud.keystore.*`), debug fallback with loud warning. Hand-made vector launcher icon. Preview/debug entries gated behind `BuildConfig.DEBUG`. Licenses: Settings entry, `player/NOTICE`, `player/THIRD_PARTY_LICENSES.md`, per-voice table. Merged release manifest and `aapt dump badging` show no INTERNET.
- Automated: release APK 2.98 MB (session report), 417 release tests green (session count), incl. ReleaseManifestTest + ReleaseGuardsTest. See D-047 and `docs/ReleaseSigning.md`.
- Device remainder: sideload on the Tab E (unknown sources), upgrade-install over debug, Slice 1-4 smoke on the release build, licenses legibility, icon render (user).

---
## CP9 Scribe release hygiene (clean-install proof)

- Fresh-environment install verified end to end on Windows 11 following the README literally: clone (1.2 s) + `uv sync` (8.5 s, 73 resolved/72 installed) + models copied locally (0.1 s; download step NOT clean-tested) + `scribe doctor` all 9 PASS (29.7 s) + `draft` (9.1 s, 1 chapter/6 sentences/22 words/0 drops) + `build --no-progress` (15.6 s CLI wall, 9.0 s in-process, RTF 1.28x, 6/6 real Kokoro synth) + `validate` PASS (3.9 s) on a synthetic 1-page PDF.
- Packaging convergence NOT done, deferred per the D-039 zero-friction gate (flat tree maps correctly; 27 modules ship). Working `scribe/.venv` noted stale (predates CP5 pymupdf row); refresh with `uv sync --reinstall-package auloud-scribe`. Suite green at proof time (482 passed, 2 deselected, `ruff check` clean). See D-048.
- Device remainder: none (PC-side).

---
## MV0 voice palette (2026-10-02, user listening test over 54 samples)

- Narrator: `am_onyx` (18)
- Characters: `bf_isabella` (23), `bm_lewis` (28), `im_nicola` (38), `jf_alpha` (39), `zf_xiaoxiao` (49), `am_eric` (14)
- Generic female: `af_bella` (03); generic male: `am_adam` (12)
- User note: current voices sound good and are enough for v1; v2/v3 should add more diverse voices (logged in D-036).

---
## RA11 acceptance (2026-10-01, Tab E, user run)

Build: debug APK from `dev/slice3` (through `13a1366`, incl. the Listen dead-end fix). Bundles: `ra-beep`, `ra-long`, C&P sample, Yellow Wallpaper.

- C13 beep sync (start/middle/end, 1.0x + 1.5x, lag avg/max under ~300 ms): pass
- C14 long chapter scroll/highlight (5,000 sentences, flings): pass
- C15 tap to jump (incl. italic sentences, audible within ~500 ms): pass
- C16 detach + back to now (no fighting/jitter): pass
- C17 mode switches at start/middle/end + across chapter change, position kept: pass
- C18 Read scroll-settle, then Listen starts at that sentence: pass
- C19 speeds (0.75x/1.5x/2.0x in sync) + every timer option incl. end-of-chapter and screen-off pause: pass
- C20 PSS at 0/30/60 min, no growth trend: pass
- C21 real-book hour (C&P + Yellow Wallpaper, mixed interaction): pass
- Listen dead end found in testing (no way back to Read modes): fixed (`13a1366`, mode switcher slot on the Listen screen) and retested: pass

Note: qualitative passes; exact overlay lag/PSS numbers were not recorded. Slice 3 "done when" (300 ms sync, lossless switches, stable hour) signed off by the user.
## SW11 acceptance (2026-10-01)

- C&P sample (67 sentences, fragment): `scribe build` → 1 chapter, 7:20 audio in 105.9 s wall (**RTF 4.16x**); `scribe validate` valid; `scribe inspect` correct; imports into Slice 1 Player and **plays on the Tab E, user-confirmed** (listen-only; read-along is Slice 3).
- Yellow Wallpaper stress (local-only, 6621 words): 2 chapters, 36:00 audio in 700.6 s wall (**RTF 3.08x**); 0 skipped, 0 failed; cover extracted; bundle validates.
- Real-time factors recorded for slice close-out.
## SW0 engine spike (2026-10-01, laptop CPU, RTX 4050 present but unused)

- Kokoro-82M via kokoro-onnx 0.6.1 + onnxruntime 1.23.2, voice af_heart: 23.3 s audio in 16.8 s wall over 5 varied sentences (narration, dialogue, abbreviations, ellipses, long) -> **RTF 1.38** (model load excluded).
- Install pain: official `kokoro==0.9.4` uninstallable (tokenizers 0.10.3 sdist, no cp310/cp314 Windows wheels, needs Rust); kokoro-onnx installed cleanly (21 wheels). espeak-ng 1.52.0 via .msi, no issues. Model kokoro-v1.0.onnx + voices-v1.0.bin from thewh1teagle releases.
- Subjective: voice good enough; Piper comparison skipped. Choice: Kokoro/af_heart (see D-023).
## Slice 1 device verification (user run, 2026-09-29, Tab E Android 7.1.1)

Build: debug APK from `slice-1` (HEAD f77bbc6 + watch-folders). Bundle: hand-made TestBook (2 tone chapters) + TestBook-Bad (missing ch002.mp3).

- C1 import (valid + corrupt with reason, no crash): pass
- C2 playback basics: pass
- C3 battery prompt once + settings entry: pass; prompt leads to app > optimize battery usage
- C4 30-min screen-off: pass, position correct, no restarts
- C5 2-h screen-off: pass
- C6 bad chapter skipped with message: pass
- C7 recents swipe: recorded, no issue
- C8 lock-screen + notification controls: pass
- C9 interruptions: pass
- C10 resume after restart + reboot: pass
- C11 overlay matches audio: pass
- C12 battery drain: no issue
- Overall: all pass, nothing odd
## MV4 attribution eval (2026-10-02, laptop, `dev/slice4` commit MV4)

- Harness: `uv run python dev/eval_speakers.py --predictor mv4` (strict) and
  `--predictor mv4 --alias-aware`, over the n=5 seed
  (`spec/fixtures/speakers-gold/crime-and-punishment-ch01.yaml`).
- The seed carries anchors and excerpts only, no paragraph texts, so every
  gold context arrives with empty `block_text`: no tag is visible and rules
  1-4 cannot fire. First line falls to `unknown`, the rest repeat it via
  `fallback`. This run validates the wiring format, not accuracy.

| predictor | overall | high | medium | low |
| --- | --- | --- | --- | --- |
| baseline | 0/5 = 0.0% | n/a | n/a | 0/5 = 0.0% |
| mv4 strict | 0/5 = 0.0% | n/a | n/a | 0/5 = 0.0% |
| mv4 alias-aware | 0/5 = 0.0% | n/a | n/a | 0/5 = 0.0% |

Accuracy by rule (mv4 strict; alias-aware identical, the seed has no
`surface` fields yet):

| rule | accuracy |
| --- | --- |
| explicit | n/a (0 lines) |
| pronoun | n/a (0 lines) |
| alternation | n/a (0 lines) |
| continuation | n/a (0 lines) |
| fallback | 0/4 = 0.0% |
| unknown | 0/1 = 0.0% |

- The 80%/90% targets from the Slice 4 plan are explicitly NOT promised on
  n=5: the set is a format seed, and 0/5 here reflects missing paragraph
  texts, not rule quality. Rule quality is pinned by 21 unit tests in
  `scribe/tests/test_attribution.py` (each rule in isolation, both tag
  sides, gender-mismatch rejection, alternation sustain/break, continuation
  chains, fallback/unknown, confidence levels). Retune and re-record when
  the 100-150 line full-novel gold set lands (still owed).
