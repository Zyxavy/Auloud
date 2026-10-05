# Slice 3 Implementation Plan: Read-Along

Project: Auloud Player (Android), plus a small Scribe dev tool for test data. Follows `04-Roadmap.md` (Slice 3), `06-PlayerDesign.md`, `03-BundleSpec.md`. Builds on Slice 1 (playback, library, SAF watch folders) and Slice 2 (real bundles with `text/chNNN.json` sync maps).

## 1. Goal

Three modes with a synced highlight: read only, listen only, and read + listen. In read + listen, the current sentence is highlighted and followed, tapping a sentence jumps the audio there, and manual scrolling detaches with a "back to now" button. One saved position is shared across all modes. Speed control and a sleep timer come with this slice.

**Done when** (from the roadmap):

- Highlight stays within about 300 ms of the audio at the start, middle and end of a long chapter
- Switching modes never loses your place
- Memory stays stable during a 1-hour session

## 2. Scope

| In | Out (later) |
| --- | --- |
| Reader view for EPUB bundles (headings, paragraphs, quotes, breaks, italic/bold) | PDF page view (Slice 5) |
| Sentence index, highlight, auto-scroll, tap-to-jump, Following/Detached | Chapter list screen, chapter navigation UI (Slice 5) |
| Modes: read only, listen only, read + listen | Themes beyond system default, line spacing (v1.1) |
| Speed (0.75x-2.0x), sleep timer | Speaker colors/labels (Slice 4) |
| Font size setting, keep-screen-on setting | Bookmarks (v1.1) |

## 3. Prerequisites

- Slice 2 bundles on the tablet (C&P sample, Yellow Wallpaper stress bundle)
- `spec/fixtures/scribe-golden/` (a Scribe-built bundle with text JSON) for JVM tests
- Test bundles from RA0a (below): a beep bundle and a long synthetic chapter

## 4. Work packages (in order)

### RA0a: Test-data tool in Scribe (S)

- A dev script (not a user command) that builds, with a fake engine so it takes seconds:
  - **Beep bundle:** one chapter, 40 sentences, a short beep at the exact `start_ms` of each sentence, so sync can be judged by ear and eye
  - **Long chapter:** one chapter with 5,000 sentences (about 0.4 s of tone each) and real-looking text, to stress scrolling and memory
- Output into the gitignored `logs/` folder; both must pass `scribe validate`
- **Verify:** both bundles validate and import into the Player

### RA0: Reader rendering spike (S), the gate

The Tab E is slow, so test the rendering approach before building on it.

- Throwaway screen: render the 5,000-sentence chapter as a `LazyColumn` of paragraph `Text`s with `AnnotatedString`, with a fake moving highlight (changes every 0.4 s) and auto-scroll
- You test on the tablet: is scrolling smooth, does the highlight keep up, does memory stay stable
- If it lags, compare a classic `RecyclerView` + `TextView` + `SpannableString` version
- **Decision (logged in `DECISIONS.md`):** Compose or Views for the reader. The remaining packages use the same state logic either way; only RA4-RA6 rendering code differs

### RA1: Chapter text model and loader (S)

- `@Serializable` models: `ChapterText`, `Block` (heading, para, quote, break), `Sentence` (`sid`, `speaker`, `start_ms`, `end_ms`, `text`, optional `spans`), `Span`; `ignoreUnknownKeys = true`
- `ChapterTextLoader` reads through `BundleStorage`, parses off the main thread, returns `Result`; a `pages` (PDF) file returns "reader not available for this book yet"
- Validation on load: `sid` consecutive, timings ordered and in range; violations produce a clear error, not a crash
- **Verify (JVM tests):** the Scribe golden bundle loads; bad fixtures (invalid JSON, overlapping sentences, missing file, PDF form) behave as specified

### RA2: Sentence index (S)

- Sorted arrays of `(start_ms, sid)` plus lookup tables sid to (block, position in block)
- `currentSid(positionMs)`: binary search; spec rule: the sentence with `start_ms <= pos < end_ms`, else the last whose `start_ms <= pos` (so gaps keep the previous highlight); before the first start returns the first; `startMsOf(sid)`
- **Verify (JVM tests):** boundaries, gaps, before first, after last, single sentence, empty chapter

### RA3: Reader state and view model (M)

- `ReaderState(chapterIndex, chapter, mode, follow, currentSid, positionMs, isPlaying, loading/error)` derived from the playback controller state
- Ticker for the reader about every 200 ms while playing (the Slice 1 500 ms ticker is too coarse for a 300 ms target); no allocation per tick; only emit when `currentSid` changes
- Chapter changes (auto-advance, next/previous): load the new chapter's text, reset follow to Following at the chapter start
- Pure functions for the follow state machine and mode transitions, so they are JVM-testable
- **Verify (JVM tests):** state transitions with Turbine, including chapter change and loading/error states

### RA4: Reader rendering (L)

- Per the RA0 decision. For Compose: `LazyColumn` with one item per block, keyed by block id; a paragraph is one `Text` with an `AnnotatedString` (italic/bold spans applied, current sentence given a background span); only the block containing the current sentence recomposes
- Precompute, per block, each sentence's character range in the paragraph text
- **Check how Scribe stores spacing between sentences** (look at the golden bundle and the spec): if sentence text is trimmed, join with one space; if it includes spacing, concatenate as stored
- Headings, quotes (indented), breaks (divider); font size from settings
- **Verify (JVM tests):** range computation and span offsets for plain, italic and edge cases; **device:** looks right on the Tab E

### RA5: Following, auto-scroll, back to now (M)

- State machine: `Following` becomes `Detached` on a user scroll; the "back to now" button returns to `Following` and scrolls to the current sentence
- Programmatic scrolls must not trigger detachment (track the source of the scroll)
- Auto-scroll keeps the current sentence in the upper third of the screen; for long paragraphs, scroll to the sentence's line using the text layout result, not just the block
- Show the button only when Detached and the current sentence is off screen
- **Verify (JVM tests):** state machine; **device:** manual scroll detaches, button re-attaches, no jitter

### RA6: Tap to jump (M)

- Tap position converted to a character offset (text layout hit test), then to a sentence by the precomputed ranges, then `seekTo(start_ms)` and back to `Following`
- In read-only mode, tapping sets the reading position (no audio)
- **Verify (JVM tests):** offset-to-sentence mapping at sentence boundaries; **device:** taps land on the right sentence, including inside italic spans

### RA7: Modes and shared position (M)

- `ReaderMode { Read, Listen, ReadListen }`; mode is a global setting (no database migration), restored on launch
- **Read:** audio paused; when scrolling goes idle (debounced), the top visible sentence becomes the saved position (`seekTo` while paused, then the usual progress save)
- **Listen:** minimal UI (cover, titles, controls); the screen may turn off; playback unaffected
- **Read + listen:** highlight follows audio
- Switching: always starts from the one saved position; leaving Read to Listen/ReadListen resumes there
- Setting: "Keep screen on while reading" (default on in Read and Read + listen; applies only while the reader is visible)
- **Decision to confirm:** what Play does on a finished book (carry-over from Slice 1): restart from chapter 1, or ask. Log it
- **Verify (JVM tests):** mode transition table, position carried across every transition

### RA8: Speed and sleep timer (M)

- **Speed:** 0.75x to 2.0x in steps, via the player's playback parameters (pitch preserved); saved globally; timings are unaffected because positions are media time
- **Sleep timer:** off, 15, 30, 45, 60 minutes, and end of chapter; lives in the service so it survives closing the UI; last few seconds fade out; then pause and save progress; remaining time shown in the UI. Check current Media3 docs (Context7) for the right way to send the timer command to the session and publish its state
- **Verify (JVM tests):** timer logic with a fake clock (countdown, end-of-chapter, cancel, fade schedule); **device:** speed changes keep highlight in sync, timer pauses playback with the screen off

### RA9: Sync measurement and debug aids (S)

USB debugging isn't available, so the numbers must be visible on the tablet.

- Debug overlay additions: current `sid`, position, highlight lag (position at the moment the highlight changed minus the sentence's `start_ms`; show average and maximum of the last N changes), and memory (`Debug.MemoryInfo` total PSS, updated every few seconds)
- **Verify:** on the beep bundle the overlay's lag stays under about 300 ms and the highlight flips at each beep

### RA10: Edge cases and settings (S)

- Missing or corrupt chapter text: Listen still works, reader shows "Text unavailable for this chapter" and the controls stay usable
- PDF bundles: reader shows that reading arrives later; listening works
- Very long paragraphs, empty or whitespace-only sentences, chapters with only a heading
- Font size setting (small, medium, large, extra large); keep-screen-on toggle on the Settings screen
- **Verify (JVM tests):** error and fallback states; **device:** visual check of each font size

### RA11: Device testing (yours)

Add rows to `docs/WP10-Runbook.md` or a new runbook (C13 onward):

- [ ] **Sync:** beep bundle, highlight flips at each beep, at start, middle, end, at 1.0x and 1.5x; overlay lag under about 300 ms
- [ ] **Long chapter:** smooth scrolling and highlight through the 5,000-sentence chapter
- [ ] **Tap to jump:** lands on the tapped sentence, audio audible within about 500 ms
- [ ] **Detach and back to now:** scroll away, button appears, tapping returns and re-follows
- [ ] **Modes:** switch among all three at the start, middle, and end of a chapter and across a chapter change; position never lost
- [ ] **Read mode position:** scroll to a spot, switch to Listen, audio starts at that sentence
- [ ] **Speed and timer:** 0.75x, 1.5x, 2.0x keep sync; each timer option pauses, including end-of-chapter and with the screen off
- [ ] **Memory:** overlay PSS at 0, 30 and 60 minutes of read + listen; no growth trend
- [ ] **Real book:** the C&P sample and Yellow Wallpaper, read + listen for an hour, no crashes

## 5. Suggested build order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | RA0a, RA0 | see whether the reader approach is fast enough on the Tab E |
| 2 | RA1, RA2, RA3 | have state and logic tested without any UI |
| 3 | RA4, RA5 | read a chapter with a following highlight |
| 4 | RA6, RA7 | tap to jump and switch modes |
| 5 | RA8, RA9, RA10 | speed, timer, measurements and edge cases |
| 6 | RA11 | pass the "done when" list |

## 6. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Compose scrolling or recomposition is too slow on the Tab E | RA0 spike first; fallback to RecyclerView; keep logic UI-independent |
| Highlight lag over 300 ms | 200 ms ticker, emit only on sentence change, overlay lag metric; Bluetooth audio latency is not compensated and can add to the perceived lag |
| Detach logic fires on programmatic scrolls (jitter loop) | Track the scroll source; JVM-test the state machine |
| Long paragraph hides the current sentence | Scroll using the text layout to the sentence's line |
| Memory growth over an hour | One chapter's text at a time; no per-tick allocation; PSS in the overlay |
| Screen timeout interrupts reading | Keep-screen-on setting; accept the battery cost while the reader is visible |
| Sentence spacing differs from what the display assumes | Check against the Scribe golden bundle in RA4; adjust the join rule |
| No adb | On-device overlay and runbook; JVM tests for all logic |

## 7. Definition of done for Slice 3

- [ ] All work-package verifications passed (JVM tests green, no regressions in the existing suite)
- [ ] Device checks in RA11 passed, with notes in `docs/test-log.md`
- [ ] The three "done when" conditions met on the Tab E
- [ ] Decisions logged: reader technology (RA0), finished-book behavior (RA7), mode as a global setting, any spacing or scroll surprises
- [ ] Roadmap ticked only for verified items; tagged `slice-3`

## 8. Working with the agent

- One work package per session; the agent writes code and JVM tests; you run all device checks (it can't run the emulator or reach the tablet)
- Keep logic in pure, testable functions (as with `ConnectGuard`): index, state machine, mode transitions, timer
- No new dependency without checking `minSdk 24` support and license first
- Commit per work package (`RAn: summary`)

## 9. Next

Slice 4 (multi-voice) adds dialogue detection and `cast.yaml` on the Scribe side. The reader from this slice already carries `speaker` on every sentence, so character styling can be added later without changing the format.