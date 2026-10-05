# Slice 9 Implementation Plan: On-Device EPUB Ingestion

Project: Auloud Player (main), small Scribe additions for shared test data. Follows `16-V2-Roadmap.md` (Slice 9). Everything here lives in the `core` flavor (no GPL parts), so it is independent of the D-066 license question and of the engine work still waiting on device verification.

## 1. Goal
Import an EPUB directly on the tablet, with no PC. The Player parses it, cleans it, splits it into sentences, marks every sentence as **narration or dialogue**, keeps the original file, and shows a readable book with a chapter list. Audio comes later (Slice 10 renders it); this slice produces an **unrendered book**.

**Done when** (from the roadmap): an EPUB imported on the tablet shows a chapter list and readable text with dialogue marked, with no PC involved.

## 2. Scope

| In | Out (later) |
|---|---|
| EPUB container, OPF, TOC, cover, metadata | PDF on device (stays on Scribe) |
| Cleaning and structure (headings, paragraphs, quotes, breaks, italic/bold) | Speaker attribution (two-voice mode needs only narration vs dialogue) |
| Sentence splitting; quote detection with mixed-sentence splitting | Rendering audio (Slice 10), streaming (Slice 13) |
| Unrendered-book format (spec 2.0 part 1) | AAC audio format (Slice 10, spec 2.0 part 2) |
| Import UI, library chip, reader for unrendered books (read mode) | Voice settings polish (Slice 11) |
| Parity tests against Scribe | OCR, DRM-protected books |

## 3. Status of open items (not blockers for this slice)
- **SysLong verdict run** on the tablet decides streaming vs background rendering; needed for Slices 10 and 13, not here
- **D-066 license call** is needed before the v2 release (Slice 12), not here
- **Real-device proof** of Slice 8 (audition, adapter, pack listing) is still pending; Slice 9 can proceed in parallel
- Spike module deletion and the 10-minute Piper battery run remain on your list

## 4. Key design decisions (log from D-079 onward)

1. **Ingestion rules are written down first.** The Scribe code and tests are the reference. Before porting, produce a language-neutral `docs/ingestion-rules.md` (element map, drop rules, merge threshold, whitespace and NFC rules, quote rules, spacing rule). The Kotlin port implements the document, and the Scribe tests become shared test vectors.
2. **Parity is measured at the right level.** Exact sentence boundaries will differ (Python's pysbd vs Android's sentence rules), and that is acceptable. Compare instead: chapters (count, titles, word counts), blocks (type and text), and **runs of narration/dialogue per paragraph** (kind and text, with adjacent same-kind sentences merged). Report sentence-boundary agreement as a percentage, not a pass/fail.
3. **Sentence splitting approach:** start with the platform's sentence iterator plus Scribe's post-fixes (abbreviations, initials, ellipses, quote attachment, exact round trip, span rebase); only port a fuller rule set if the agreement numbers are poor.
4. **Spec 2.0, part 1: unrendered books.** A book with text and sentence structure but no audio and no timings. `audio`, `duration_ms`, `start_ms` and `end_ms` become conditional on a `render_state` field (`none`, `partial`, `complete`); on-device speakers are the reserved keys `narrator` and `dialogue`. Because required fields become conditional, treat it as a **major version (2.0)** unless the existing version gate says otherwise. Spec text, validator, shared fixtures and an `unrendered-golden` bundle come first.
5. **Position without timings:** an unrendered book has no milliseconds, so progress is stored as chapter + sentence id; when audio is rendered it converts using the timings. This needs a Room migration (add a column, exported schema); migrations can't be run on the JVM here, so it needs an on-device check.
6. **Book ids:** derive from the source hash like Scribe, but in a device namespace, so a PC-rendered bundle of the same EPUB appears as a separate library entry instead of clobbering the unrendered book. Merging them would need sentence-level progress mapping, which parity does not guarantee. Logged as a known trade-off.
7. **Where imported books live:** the book folder uses the same layout as any bundle (`manifest.json`, `source/book.epub`, `text/chNNN.json`, cover) so the library, validator and later rendering work unchanged. Check how the current storage layer writes (shared `/Auloud` via the write permission, or the app's own folder) and use the existing path; import writes to a temp folder and renames on success.
8. **Dependency:** an HTML parser is needed for sloppy XHTML. jsoup (MIT) is the usual choice; confirm current versions support `minSdk 24` and Java 8 level APIs before pinning. No other new dependencies.
9. **Safety limits:** read zip entries by name (no extraction to arbitrary paths), reject entries with `..`, cap per-entry and total uncompressed size, detect `encryption.xml` and refuse DRM with a clear message.

## 5. Work packages

### IN0: Rules document and pins (S-M)
- Read Scribe's extraction, cleaning, splitting and dialogue code and tests; write `docs/ingestion-rules.md` including edge cases and the spacing rule for split sentences
- Confirm jsoup and its `minSdk 24` support; record its license in `08-Licenses.md`; decide the sentence-splitting starting point and the storage path (decisions 3 and 7)
- **Verify:** rules document reviewed; decisions logged

### IN1: Spec 2.0 part 1 and fixtures (M)
- Amend `03-BundleSpec.md` (decision 4); update Scribe's validator and the Player's validator and loaders; add the `unrendered-golden` fixture (tiny, public-domain text) to `spec/fixtures/`
- Update the version gate so older Players refuse 2.0 books cleanly
- Add the sid-based progress column and Room migration (decision 5)
- **Verify (tests, both sides):** unrendered golden validates and loads; a rendered 1.x bundle still loads; invalid combinations are rejected with file and rule

### IN2: Shared test data from Scribe (S)
- A small dev command exporting (a) dialogue test vectors (input paragraphs and expected narration/dialogue runs, including multi-paragraph, singles, apostrophes, scare quotes, unbalanced, mixed sentences) from the existing Scribe tests, and (b) parity expectations for the golden EPUBs (chapters, blocks, runs)
- Deterministic output in `spec/fixtures/ingest-parity/` and `spec/fixtures/dialogue-cases.json`
- **Verify (tests):** running it twice gives identical files; Scribe's own tests consume the vectors too

### IN3: EPUB container reader (M)
- Zip access with the safety limits (decision 9); `container.xml`, OPF metadata (title, author, language), manifest and spine; table of contents from both the EPUB 3 nav document and the older NCX; cover image lookup; streaming SHA-256 of the source
- DRM detection, missing or corrupt OPF, non-English language warning
- **Verify (JVM tests):** golden EPUBs (EPUB 2 and 3), malformed archives (zip traversal attempt, oversize entry, missing OPF, encrypted), spine order, titles, cover

### IN4: Structure and cleaning (M-L)
- jsoup walker producing blocks per IN0 rules: headings, paragraphs, block quotes, scene breaks (rules and centered asterisks); italic and bold spans as character offsets after whitespace normalization; NFC; curly quotes preserved
- Drops with a report (navigation pages, copyright boilerplate, empty paragraphs, footnote markers, image-only pages); chapter titles from the TOC or first heading; "Chapter N" for untitled; tiny chapters (under about 200 words) merged into the next
- Process one spine document at a time to keep memory low
- **Verify (JVM tests):** parity expectations for chapters and blocks on the golden EPUBs; span offsets; each drop rule; memory-safe on a large synthetic chapter

### IN5: Sentence splitting (M)
- Per decision 3: split each paragraph; do not split after abbreviations (Mr., Mrs., Dr., St., initials), handle ellipses, keep quotation attachment, headings as one sentence each; exact round trip (concatenating with the stored spacing reproduces the paragraph); sids from 1 across the chapter; span rebasing
- Report agreement with Scribe's boundaries on the golden books
- **Verify (JVM tests):** round trip, abbreviations, ellipses, quotes, spans; agreement percentage recorded in `docs/test-log.md`

### IN6: Dialogue detection port (M)
- Port the quote state machine: straight and curly double quotes; single quotes only when a paragraph has no double quotes; multi-paragraph continuation; apostrophes never boundaries; scare-quote rule; unbalanced quotes treated as narration with a logged warning
- Mixed sentences split at quote boundaries into separate narration and dialogue sentences, with the same spacing rule (including the split-pair spacing fix from Scribe)
- Each sentence gets `kind` and the reserved speaker key (`narrator` or `dialogue`)
- **Verify (JVM tests):** every shared vector passes; run-level parity with Scribe on the golden EPUBs

### IN7: Pipeline and bundle writer (M)
- `IngestPipeline` combining IN3-IN6 with progress events (per chapter), cancellation, and an import report (chapters, words, dropped elements, warnings, elapsed time)
- Writes the unrendered book per spec 2.0: manifest (`render_state: none`, `source` with hash, default `voices` for the two roles), `source/book.epub`, `text/chNNN.json`, cover; temp folder then atomic rename; library upsert; duplicate detection by hash
- All off the main thread; errors shaped as file and rule
- **Verify (JVM tests):** end-to-end on golden EPUBs through a fake storage; cancel mid-import leaves nothing behind; duplicate import is detected

### IN8: Import UI and library (M)
- "Import EPUB" action using the system document picker; progress screen with cancel; result summary (chapters, words, what was dropped, warnings); clear failure messages (DRM, corrupt, unsupported)
- Library shows a "Not rendered" chip; delete book removes its folder
- **Verify (JVM tests):** view-model states for success, cancel, each error; **device:** import from the tablet's storage

### IN9: Reader for unrendered books (M)
- Loader variant for text without timings; reading mode only; progress saved by sentence id and restored; chapter list works
- Dialogue marking: dialogue sentences drawn in a distinct accent color (with a setting to turn it off), checked for contrast
- Mode switcher shows Listen and Read + listen as unavailable with a "render audio to listen" hint (Slice 10 supplies it)
- No change to rendered books; the existing Player suite must stay green
- **Verify (JVM tests):** state, position save and restore, mode gating; **device:** visual check of dialogue marking on the tablet

### IN10: Acceptance (you)
- [ ] Import the C&P and Alice EPUBs on the tablet; chapter counts and titles match Scribe's report
- [ ] Text reads correctly (italics, headings, scene breaks); dialogue is marked; a mixed sentence like `"We should leave," she said.` shows the quote and the tag as separate colors
- [ ] Close and reopen: position restored; the original EPUB is present in the book folder
- [ ] Record the import time for each book in `docs/test-log.md`
- [ ] Try a corrupt EPUB and (if you have one) a DRM EPUB; both fail with clear messages and leave no partial book
- [ ] Re-import the same file; it is detected as a duplicate
- [ ] Existing rendered books still play with read-along
- [ ] Parity report: chapters and blocks match; run-level agreement and sentence-boundary agreement recorded

## 6. Order and check-ins

| Step | Work packages | You can then... |
|---|---|---|
| 1 | IN0, IN1, IN2 | have the rules, the spec change and the shared test data |
| 2 | IN3, IN4 | parse a real EPUB into clean blocks (JVM only) |
| 3 | IN5, IN6 | see sentences and dialogue runs match Scribe's |
| 4 | IN7, IN8 | import an EPUB on the tablet |
| 5 | IN9, IN10 | read it with dialogue marked and accept |

## 7. Definition of done
- [ ] All verifications passed; Player and Scribe suites green; no regression for rendered books
- [ ] Parity levels met: chapters and blocks exact; kind-run agreement recorded with any differences explained; sentence-boundary agreement reported
- [ ] Device acceptance passed on the Tab E
- [ ] Spec 2.0 part 1, migration, and decisions logged; `08-Licenses.md` updated (jsoup); roadmap ticked only for verified items; tagged `slice-9`

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Real-world EPUBs are messier than the golden ones | Rules from Scribe's hard-won fixes; import report lists drops and warnings; test on several books you actually own |
| Sentence splitting differs from Scribe | Parity measured at the run level; boundary agreement reported; improve rules only if the numbers are poor |
| Import is slow on the Tab E | One spine document at a time, progress per chapter, off the main thread; record real times and set expectations |
| Memory pressure on 1.5 GB | Stream the zip, parse one document at a time, write chapters incrementally |
| Malicious or broken archives | Entry-name checks, size caps, DRM refusal, atomic temp-then-rename |
| Room migration breaks existing libraries | Real migration with exported schema, tested on a device with an existing library; the library can be re-imported if all else fails |
| Spec 2.0 confuses older Players | Version gate refuses 2.0 books with a clear message |
| Unrendered and PC-rendered copies of one book | Separate ids in a device namespace; documented trade-off |
| Non-English or right-to-left books | Language warning; English only for v2 |

## 9. Working with the agent
- One work package per session; it implements and tests on the JVM; **you** run the import and visual checks on the tablet
- Port from the rules document and shared vectors, not from memory of the Python code
- No dependency beyond jsoup without asking; license check first
- Commit per work package (`INn: summary`); tag `slice-9`

## 10. Next
Slice 10 (background rendering) turns an unrendered book into a playable one on the device. It uses the voice roles from Slice 8, the sentence structure from this slice, and the SysLong verdict to decide how aggressively it renders ahead of your listening.