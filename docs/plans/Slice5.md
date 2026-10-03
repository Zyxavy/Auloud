# Slice 5 Implementation Plan: Complete Pass (v1 Release)

Projects: Auloud Player and Scribe. Follows `04-Roadmap.md` (Slice 5), `07-TestPlan.md`, `08-Licenses.md`. This is the "Complete" in SLC: finish what v1 promises, harden it, and prove it with a full-novel soak test.

## 1. Goal

From a fresh book (EPUB or PDF) to finishing it on the tablet with no workarounds: PDFs work, chapter navigation exists, bad input fails gracefully, the release build is clean and documented, and a 10+ hour multi-voice novel survives several days of real use.

**Done when** (v1 release criteria from the roadmap): the soak test passes.

## 2. Scope

| In | Out (after v1) |
| --- | --- |
| PDF to clean text (text layer only) through the existing pipeline and reader | OCR for scanned PDFs |
| PDF page view with audio sync (optional, gated, see CP5) | Word-level highlight in PDFs |
| Chapter navigation screen | Bookmarks, themes, Wi-Fi sync (v1.1 backlog) |
| Error handling and Player-side import validation | LLM speaker attribution |
| Release build, signing, licenses screen, NOTICE | On-device TTS (v2), newer Android (v3) |
| README, setup guide, docs cleanup | F-Droid / Play Store publishing |
| Full-novel gold set and attribution tuning |  |
| Soak test and v1 tag |  |

## 3. Carry-forwards from Slices 1-4

Triage each in CP0 as **fix now** or **defer to backlog** (log the choice):

- Scribe: cache size cap; progress-bar ETA coverage; packaging convergence (flat imports vs `scribe.*`); `remap` is EPUB-only (do not reuse for PDF as-is); quoteless narration doesn't break alternation; crowded blocks can be high-confidence wrong; thought-vs-speech voicing undecided
- Player: notice auto-dismiss; SAF cover resolution; preview/debug entries must not ship in the release build
- Docs: Scribe is AGPL-3.0 (D-024) but 01, 05, 08 and the README still say GPL; roadmap ticks
- Owed: the full-novel gold set (100-150 lines) for real accuracy numbers

## 4. Key design decisions (log as made)

1. **PDF approach: text path first.** Convert the PDF text layer to clean text and reuse the whole EPUB pipeline (attribution, cast, multi-voice, the Slice 3 reader). Page view is an optional second view.
2. **Additive spec change (v1.1):** a PDF chapter file keeps `blocks` and may add `pages: [{page, start_ms}]` and an optional `page` on each sentence. Old Players ignore the extra fields. Update `03-BundleSpec.md`, validator and shared fixtures first.
3. **Gate for the Page view:** try the Text view on a real PDF first. Build the Page view only if the PDFs you actually read have figures, tables or layout that clean text loses.
4. **Soak on the release candidate:** build the signed release APK (CP6) and run the soak on that, not on a debug build.
5. **Signing key:** generate once, store outside the repo with a backup; losing it means reinstalling the app.

## 5. Work packages

### CP0: Triage and housekeeping (S)

- Walk the carry-forward list; decide fix-now vs defer; log in `DECISIONS.md`
- Update docs to AGPL-3.0 (01-PRD, 05, 08, README); tick Slice 4 in the roadmap
- Pick the soak novel: public domain, 10+ hours, dialogue-heavy (for example *The Adventures of Sherlock Holmes* or *Pride and Prejudice*)
- **Verify:** docs consistent; triage decisions logged

### CP1: Full-novel gold set and tuning (M, time-boxed)

- Label 100-150 dialogue lines from the soak novel (chapter, block, quote number, true speaker) in `spec/fixtures/speakers-gold/`
- Run the evaluation, record real overall and by-confidence accuracy, tune rules within the time box
- **Verify:** real numbers in `docs/test-log.md` (the n=5 table is only wiring validation); known weak spots listed in the README limitations

### CP2: Start the soak build (S, then waits)

- Draft, review `cast_report.md`, edit `cast.yaml`, build the full novel, validate, copy to the tablet
- A 10-hour book is roughly 2.5-3 hours of build at your measured speed; run it in a detached window and poll in short bursts
- Rebuild later only if Scribe changes affect audio or text output
- **Verify:** `scribe validate` passes; `scribe inspect --speakers` looks sane

### CP3: Player chapter navigation (M)

- Chapter list screen: titles, durations, current chapter marked, tap to jump (keeps the shared position rules); reachable from the reader and Listen screens
- Next/previous chapter controls already exist; make sure highlight and mode state behave on jumps
- **Verify (JVM tests):** list state, jump seek, current marker; **device:** jumping works in all three modes

### CP4: Player error handling and import validation (M)

- Import validation covers manifest, audio files and a quick check of each chapter's text file; problems are listed with file and rule, and the book is still importable when only some chapters are bad
- Storage removed (microSD ejected or watch folder unavailable): pause with a clear message, resume when available
- Corrupt or missing chapter at play time: skip with a message (auto-dismissing notice), keep going; missing text means Listen still works
- Fix SAF cover resolution; make sure no input crashes the app (add fixtures for truncated JSON, wrong types, huge values)
- **Verify (JVM tests):** each failure mode; **device:** eject-and-reinsert test, bad-bundle import

### CP5: PDF extraction in Scribe (L)

- PyMuPDF (AGPL, compatible with Scribe's license): text per page with layout blocks
- Cleaning: remove repeated headers/footers and page numbers, join hyphenated line breaks, rebuild paragraphs from spacing, drop footnotes if detectable
- Chapters from the PDF outline when present, else heading heuristics, else fixed page ranges
- Detect pages without a text layer (scans): fail with a clear message, no OCR
- Quality report in `draft_report.md`: pages, dropped lines, short-line ratio, a "this PDF looks messy" warning
- Deterministic block ids so quote keys stay stable
- **Verify (unit tests):** synthetic and real PDFs (clean, with headers/footers, two-column, scanned); same input gives identical output

### CP6: PDF through the pipeline and the spec (M)

- Spec 1.1 amendment (decision 2), validator rules, shared fixtures, a tiny golden PDF bundle
- Manifest `type: pdf`, `source/book.pdf`; each sentence records its source page; `pages` marks computed from sentence timings (start of the first sentence on each page)
- Attribution, cast and multi-voice work unchanged; make the speaker remap PDF-safe instead of reusing the EPUB-only one
- **Verify:** golden PDF bundle validates; the Player contract test parses it

### CP7: Player PDF support (M, Page view optional)

- **Text view (required):** remove the "arrives later" message for bundles with `blocks`; everything from Slice 3 works as is
- **Page view (only if the gate says yes):** `PdfRenderer` renders one page at a time (downsampled bitmap, recycled on change); the page changes when playback crosses the next `pages[].start_ms`; turning a page seeks the audio to that page's start; a Text/Page toggle in the reader
- **Verify (JVM tests):** page lookup from position; page-to-seek mapping; **device:** read + listen on a real PDF; memory stable in Page view (1.5 GB RAM)

### CP8: Release build and packaging (M)

- R8/minify with keep rules for kotlinx.serialization, Room and Media3; test the release APK thoroughly, since minify bugs only show up there
- `versionName 1.0.0`, `versionCode`, app name and icon
- Signing: keystore stored outside the repo, backed up; build a signed release APK
- Preview and debug entries absent; no `INTERNET` permission in the release manifest (check the merged manifest)
- In-app licenses screen, `NOTICE`, `THIRD_PARTY_LICENSES.md`; tick every "Verified" box in `08-Licenses.md`; per-voice license table complete
- Sideload on the Tab E (enable unknown sources); confirm upgrade-install works
- **Verify:** release APK passes the Slice 1-4 smoke checks on the tablet

### CP9: Scribe release hygiene (S)

- Fresh-environment install test on Windows 11 following the README literally; `scribe doctor` passes
- Decide packaging convergence (only if low risk); remember to force-include new modules
- Cache prune or size cap if triaged in; ETA coverage fix if triaged in
- **Verify:** a clean `uv` environment builds a short bundle end to end

### CP10: Docs and README (S)

- README with quick start, requirements, modes, limitations (speaker accuracy, PDF quality, English only), copyright position
- Setup guide for Scribe and for getting bundles onto the tablet (microSD and watch folders)
- Docs index, `DECISIONS.md` up to date, roadmap ticks, `docs/test-log.md` complete
- **Verify:** someone else (or you, fresh) can follow the README from zero to a playing book

### CP11: Soak test (you), 3-7 days

Use `07-TestPlan.md` section 7 on the release APK with the soak novel. Log daily in `docs/soak-log.md`.

- [ ] 10+ hour multi-voice novel converted, validated, copied to the tablet
- [ ] Listened over several days in all three modes
- [ ] At least two screen-off stretches of 2+ hours
- [ ] No crashes or service kills; resume correct after every restart
- [ ] Sync spot-checks pass in early, middle and late chapters (overlay lag)
- [ ] Voices distinguishable; mislabelled lines noted and fixable via `cast.yaml`
- [ ] A real PDF read in Text view (and Page view if built)
- [ ] Battery: charge to 100%, one hour screen-off at fixed volume, note the percentage lost (use Android's battery usage screen since adb isn't available)

### CP12: Release (S)

- Run the release checklist (`07-TestPlan.md` section 10 and `08-Licenses.md` section 7)
- Tag `slice-5` and `v1.0.0`; optional GitHub release with the APK and Scribe install instructions
- **Verify:** every box in section 7 below ticked

## 6. Schedule and critical path

| Step | Work packages | Notes |
| --- | --- | --- |
| 1 | CP0, CP1, CP2 | Start the long novel build early; it runs while you work on the Player |
| 2 | CP3, CP4 | Player work in parallel with the build |
| 3 | CP5, CP6, CP7 | PDF; try Text view first, then the Page-view gate |
| 4 | CP8, CP9, CP10 | Release candidate and docs |
| 5 | CP11 | Soak on the release APK (the longest step in calendar time) |
| 6 | CP12 | Tag and release |

If the schedule slips, cut the PDF Page view first (SLC: simple and complete beats extra features).

## 7. Definition of done for v1

- [ ] All work-package verifications passed; Scribe and Player suites green; release APK built and installed
- [ ] Soak test (CP11) fully passed and logged
- [ ] PDF Text view works on a real PDF; scanned PDFs fail with a clear message
- [ ] Chapter navigation works; bad bundles and removed storage handled without crashes
- [ ] Licenses verified, NOTICE and in-app licenses present; no network permission
- [ ] README followed successfully from scratch
- [ ] Decisions logged; roadmap ticked only for verified items; tagged `slice-5` and `v1.0.0`

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| PDF text quality (columns, footnotes, tables) | Quality report and warning; Text view first; fixtures from real PDFs; honest README limitation |
| `PdfRenderer` memory on 1.5 GB RAM | One page at a time, downsampled, recycled; optional feature behind the gate |
| Release build breaks something minify-related | Keep rules, test the release APK, not just debug |
| Signing key lost | Back it up outside the repo; document where |
| Soak exposes a Samsung service kill | Logs and overlay, battery whitelist flow, record the exact setting; fix before tagging |
| Novel build time or Scribe bug forces a rebuild | Start early; validate early; only rebuild when output changes |
| Attribution accuracy lower than hoped on a full novel | Real numbers from CP1; cast report and overrides; document limits |
| Scope creep | Cut the Page view first; defer anything not on this list to the v1.1 backlog |

## 9. Working with the agent

- One work package per session; it writes code, tests and docs; **you** do all listening, tablet and soak testing, and you own the signing key
- Don't let it change the bundle format outside CP6's spec amendment
- Commit per work package (`CPn: summary`); tag as in CP12

## 10. After v1

v1.1 backlog (LLM attribution, real EPUB rendering, Wi-Fi transfer, bookmarks, themes, auto-drafted cast), then v2 (on-device TTS) and v3 (newer Android), as in `04-Roadmap.md`.