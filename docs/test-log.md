# Test log (v2)

Automated and device evidence for the v2 slices. The v1 log is archived at
`docs/archive/v1/test-log.md` and is left untouched.

---
## Slice 9 IN5 sentence-boundary agreement (2026-10-06, JVM only, no device claimed)

Method: every para/quote block text in
`spec/fixtures/ingest-parity/*.json` (the same texts IN4 parity proves
equal to the Kotlin pipeline's own block texts) was split with the real
Scribe `split_paragraph` and with Kotlin `SentenceSplitter.splitParagraph`;
per-paragraph sentence-text sequences were compared exactly. Both sides
round-trip every paragraph byte for byte (asserted in the probe).

Corpus: 15 paragraphs (scribe-golden, partial-golden, multivoice-golden,
epub2-minimal; no quote blocks exist in the goldens), 161 Scribe sentences.

Result: 15/15 paragraphs exact (100.0%), 161/161 sentences identical,
same sentence count on every paragraph. Verdict: keep the D-080 rule set
(platform iterator plus Scribe post-fixes); no fuller port. Recorded in D-086.

Environment: Windows 11, OpenJDK 21 (JDK `BreakIterator`, not Android ICU).
The tablet runs different BreakIterator tables, so IN10 re-checks these
numbers on device.

Unit tests: `SentenceSplitterTest`, 41 tests (round trip incl. double
spaces, tabs, newlines, padding, curly quotes, abbreviations at
boundaries, span edge-trim interaction; abbreviations; initials;
ellipses incl. ellipsis-plus-quote; short/long/curly/nested/single
quotes; headings; chapter sid runs; span crossing/nested/inside/split).
Full Player suite green (see the IN5 report).

---

## Slice 9 IN6 dialogue run agreement (2026-10-06, JVM only, no device claimed)

Method: the full on-device chain (container reader plus structure
pipeline plus `SentenceSplitter` plus `DialogueTagger.tagChapter`) ran
over every covered parity fixture; per-block narration/dialogue runs
(kind plus text, adjacent same-kind sentences merged) were compared
exactly with `spec/fixtures/ingest-parity/*.json`. Every block's run
texts were also joined back to its block text (rule 10.1 spacing check).

Corpus: 4 covered fixtures (scribe-golden, partial-golden,
multivoice-golden, epub2-minimal), 8 chapters, 22 blocks, 15 blocks
with runs, 16 runs total (7 heading/break blocks carry sentences but no
runs by parity-file design; their join-to-text was checked instead).

Result: 16/16 runs exact (100%), 22/22 block joins exact, zero
differences. The `multivoice-golden` anchor is pinned: `"We should
leave,"` as dialogue plus ` Alice said.` as narration. Verdict: run
parity met, no extra rules needed. Recorded in D-087.

Sentence-count note: run agreement is the acceptance bar (plan decision
2), and sentence counts agree on all golden blocks. One known
sentence-level difference lives only in synthetic vectors, not the
goldens: a multi-sentence single-quoted region yields one glued dialogue
sentence on the device versus N sentences in Scribe (the IN5
singles-glue compensation, reused never forked), with identical runs.
See D-087.

Environment: Windows 11, OpenJDK 21 (JDK `BreakIterator`, not Android
ICU). Same caveat as IN5: IN10 re-checks on device with real books.

Unit tests: `DialogueTaggerTest`, 24 tests (all 23 shared vectors replay
exactly with round trip, plus mechanics: continuation keys/flags, gold
quote keys, unbalanced/stray/unclosed warnings, scare units, chapter
sids with headings/breaks, span rebasing, five split-pair cases,
apostrophes, nested singles); `DialogueParityTest`, 2 tests (covered
fixtures plus the multivoice anchor). Full Player suite green: 660
tests, 0 failures (634 baseline plus 26 new).

## Slice 9 IN10 device acceptance (2026-10-06, Galaxy Tab E, owner-run)

Build: dev/v2 debug APK (`app-debug.apk`, Slice 8 + 9). Scribe range
bundle used for playback regression: Throne of Magical Arcana ch 278-280
(30:12 audio, RTF 4.38x, bundle validates).

- Rendered-bundle playback on the tablet: pass (278-280 plays).
- EPUB import on the tablet: pass (chapter list, readable text,
  dialogue marked).
- Imported book correctly offers Read + "render audio to listen" hint;
  no Listen path, no crash, no fake playback: pass (IN9 gate holds on
  device).
- Corrupt/DRM failure messages, duplicate re-import, close/reopen
  position restore: pass per owner ("tested everything and it works
  well").
- Import times per book: not recorded.
- Remaining device backlog (not Slice 9): Room v1-v2 migration upgrade
  path, NoClassDefFoundError long-run proof, Slice 8 audition/adapter/
  pack listing, sentence/run agreement re-check on quote-heavy books.

---

## Slice 10 RN10 beep self-check (2026-10-06, JVM only, no device claimed)

What RN10 proved on the JVM: the debug beep engine is deterministic
(same input gives byte-identical PCM), tones are 500 ms at 24 kHz with
440 Hz plus 110 Hz per sentence index, and the debug action renders the
4-tone test chapter through the real RN4 spool plus RN5 assembly plus
RN6 encoder chain with known positions (tone starts 0/1000/2000/3000
ms, ends 500 ms later, duration 4000 ms, 96000 samples). The emitted
manifest plus chapter JSON validate against the RN1 spec rules with
zero errors. The offset computation (`BeepOffset`) is pinned on
synthetic data: constant offsets recovered exactly, even-count x.5
medians round half-even, jitter reports offset plus max deviation, and
the onset detector finds synthetic tone starts within 3 samples.
Release behavior is unchanged (gate tests pin the debug-only wiring
plus registry exclusion both ways).

Provisional fixture value: `encoder_offset_ms` 0 (unchanged until the
Tab E measurement below replaces it). AAC 50 ms tolerance unchanged.

Full Player suite green: 988 tests, 0 failures (954 baseline plus 34
new: 12 BeepTtsEngineTest, 11 BeepOffsetTest, 5 BeepSelfCheckTest, 6
BeepDebugGateTest). Lint clean (0 issues).

---

## Slice 10 RN11 beep chapter (owner device procedure, NOT run)

Do this on the Galaxy Tab E with a debug APK (release builds hide the
beep card by design):

1. Open Settings, then Voices. Confirm the engine row lists `beep`
   (debug only) and a "Beep self-check (debug)" card sits at the
   bottom of the screen.
2. Tap "Render beep chapter". The status line reports the bundle path
   under cache (`beep-check/`), the 4 tone starts, the duration, and
   that validation is clean. If it reports a failure instead, copy the
   exact line into this log and stop.
3. Ear and eye check (RN11 acceptance): import or open the rendered
   chapter so it plays with read-along. Each beep must sound distinct
   (440/550/660/770 Hz) and the highlight must flip at each beep
   within about 300 ms.
4. Offset measurement: copy `audio/ch001.m4a` to the PC, decode to PCM
   (`ffmpeg -i ch001.m4a -ac 1 -ar 24000 beep.wav`), and find the four
   tone onsets (threshold 0.08 with 200 ms re-arm silence, same rule as
   `BeepOffset.detectOnsets`, or by eye in an audio editor). Known
   starts are 0/1000/2000/3000 ms.
5. Feed known vs measured starts into `BeepOffset.diagnose` (or compute
   the median difference by hand): the median is the new
   `encoder_offset_ms` constant (0 when measured zero), and the max
   deviation decides the tolerance (small keeps the provisional 50 ms
   AAC rule; large means RN1 records a looser AAC number with this
   evidence).
6. Record here: build, the four measured onsets, the diagnosed offset
   and deviation, and the highlight verdict (pass or fail with notes).

Result: not run (owner job).

---

## Slice 11 VS7 device acceptance (2026-10-09, Galaxy Tab E, owner-run)

Build: dev/v2 debug APK (`app-debug.apk`, 136.7 MB, built
2026-10-09, slices 8+9+10+11 through VS6).

Owner verdict on issue #18: "everything works great",
proceed. All checklist boxes pass: dialogue-only voice
change stales only dialogue chapters; narrator speed
change stales all rendered with plausible impact numbers;
re-render while listening elsewhere glitch-free; position
preserved through re-render of the current chapter;
cancel keeps old audio playing with the chapter Stale;
real-line audition plus A/B compare good by ear for both
roles; Scribe book voices visible with editing disabled;
chips/badges/counts match reality; Voices link opens the
book voice screen; mixed-voice banner with one-tap
finish; engine mapping preview sensible.

Two verdicts with consequences:
- KEEP choice: owner wants save-without-rendering, not
  discard. Implemented post-run as c1e231a (KEEP shares
  the LATER persist path, no render start; D-123
  updated; LATER-vs-KEEP behavior-identical, stated
  honestly, no fake distinction).
- Estimates: owner reports predicted time "pretty close
  to the real time". No precise predicted-vs-actual
  numbers recorded, so the Slice 10 provisional
  constants (RTF band, 400 ms/word) stand untuned.

---

## Slice 10 RN11 device acceptance (2026-10-08, Galaxy Tab E, owner-run)

Build: dev/v2 debug APK (`app-debug.apk`, 136.6 MB, built
2026-10-08 21:36, slices 8+9+10 plus the RN9 hub-routing
fix 57f480b).

Owner verdict on issue #16: all boxes pass, "no issue",
"everything is perfect". Functional pass covers: beep
chapter render + validation on device with highlight
flipping at each beep within about 300 ms; System TTS
chapter render with read-along (narrator/dialogue distinct
and level-matched, tag pauses natural); multi-chapter
render while charging with screen off; kill mid-render
then resume without redoing finished sentences;
unplug/replug with charging-only on (pause + resume);
partial-book playback with unrendered chapters marked and
the end-of-rendered-portion message; swipe-away with the
render continuing; existing Scribe books still play with
read-along.

Not recorded: the four measured beep onsets, the precise
`encoder_offset_ms` number, max onset deviation,
estimate-vs-actual figures, RTF numbers. The highlight
verdict passing by ear and eye bounds the real offset
inside the 300 ms check window; the fixture value
`encoder_offset_ms` 0 stands as provisional, the 50 ms AAC
tolerance stands, and the D-109 spec question (nonzero
offset vs first-start-0) stays open for lack of a nonzero
measurement — nothing observed contradicts first-start-0,
so no spec amendment was needed.

## VC1 license implementation (agent, 2026-10-09, D-126 option A)

Commands (from player/, all with --no-daemon):

- `.\gradlew.bat :app:assembleCoreDebug` - BUILD SUCCESSFUL (1m 55s)
- `.\gradlew.bat :app:assembleFullDebug` - BUILD SUCCESSFUL (1m 45s)
- `.\gradlew.bat :app:licenseScan` - PASS both:
  core app-core-debug.apk (256 entries, no lib markers);
  full app-full-debug.apk (16 lib entries, notices present).
  Cross-checked by unzipping both APKs: core has no
  sherpa/onnx/espeak/gpl entries; full has 16 .so files
  (libonnxruntime + 3 sherpa libs x 4 ABIs) plus
  assets/gpl-3.0.txt, assets/SOURCE_OFFER.txt, assets/NOTICE.txt.
- `.\gradlew.bat :app:testCoreDebugUnitTest :app:testFullDebugUnitTest`
  - first run: 1340 core tests, 1 failure in
    UnrenderedReaderViewModelTest.jumpToChapter_loadsStartAndSaves
    (the known pre-existing debounce flake, VC5 owns the fix);
    it passes in isolation, unrelated to VC1.
  - full rerun with --rerun-tasks: 243 result files, 2688 tests,
    0 failures, 0 errors across both flavors. Includes the moved
    SherpaPiperEngineTest (testFull only) and the new per-flavor
    FlavorLicensesTest suites (testCore + testFull).

The scan is intentionally not wired into `check` (every test run
would then build two APKs); run the exact assemble + licenseScan
command above to re-verify. Release-build R8/manifest checks and all
device checks stay with VC2/VC7 (owner).

## VC2 release engineering (agent, 2026-10-09, D-129)

Version 2.0.0, versionCode 2 (v1 was 1.0.0/code 1). Signing keeps the
v1 convention (local.properties key, debug fallback + warning); the
release doc is rewritten flavor-aware as `docs/ReleaseSigning.md` (the
v1 sheet stays in `docs/archive/v1/`). No new dependency, no new
permission, minSdk 24 unchanged.

Commands (from player/, all with --no-daemon):

- `.\gradlew.bat :app:assembleCoreRelease :app:assembleFullRelease`
  - BUILD SUCCESSFUL (5m 37s, R8 minify + resource shrink on both).
    One added keep rule (sherpa-onnx JNI bridge for `full`); no other
    keep change needed. Both APKs are debug-signed: no release
    keystore is configured on this machine, so the owner signs the
    distribution build with the real key (same key as v1 for the VC3
    over-install proof).
- `.\gradlew.bat :app:licenseScan :app:releaseManifestCheck`
  - licenseScan PASS both: core app-core-release.apk (224 entries, no
    lib markers); full app-full-release.apk (8 lib entries, notices
    present). The new releaseManifestCheck PASSes every merged release
    manifest (no INTERNET).
- `.\gradlew.bat :app:testCoreDebugUnitTest :app:testFullDebugUnitTest`
  - 2688 tests (1340 core + 1348 full), 0 failures, 0 errors.
    (Stale pre-flavor result dirs ignored in the count.)

Sizes (release APKs):

- app-core-release.apk: 3.5 MB. Only native lib is
  libandroidx.graphics.path.so (Compose, 10 KB); no sherpa/onnx/espeak.
- app-full-release.apk: 55.4 MB. Per-ABI native weight (uncompressed):
  arm64-v8a 30.5 MB, armeabi-v7a 21.2 MB (4 sherpa/onnx libs each).
  x86/x86_64 excluded by the new `abiFilters` (about 70 MB raw saved
  against the 4-ABI spike build at about 130 MB). No per-ABI splits:
  one universal APK per flavor keeps sideloading simple (D-129).

Debug gating unchanged and pinned (`BeepDebugGateTest`,
`ReleaseGuardsTest`, `RenderDebugGateTest` all green in the suite
above); the spike entry stays `BuildConfig.DEBUG`-gated (spike deletion
waits for the SysLong verdict in VC0).

Not claimed: anything on the tablet. The smoke checklist in
`docs/ReleaseSigning.md` is OWNER-RUN; the VC3 over-install proof and
the VC7 soak stay with the owner (VC8).

---

## VC3 upgrade path, JVM parts (agent, 2026-10-09, no device claimed)

Commands (from player/, all with --no-daemon):

- `.\gradlew.bat :app:testCoreDebugUnitTest --tests
  "app.auloud.player.bundle.VC3CompatibilityMatrixTest"` - 4/4 green
  (first run, before docs edits).
- `.\gradlew.bat :app:testCoreDebugUnitTest
  :app:testFullDebugUnitTest` - full flavor failed once in
  `UnrenderedReaderViewModelTest.initialLoad_missingSavedSid_fallsBackToChapterStart`
  (expected saved (0, sid 1), got sentenceSid=99); passes in
  isolation. Same known-flaky class as the VC1 run (VC5 owns the
  debounce fix); unrelated to VC3 (this change only adds a test
  class with no shared state).
- Same command with `--rerun-tasks`: BUILD SUCCESSFUL, 2696 tests
  (1344 core + 1352 full), 0 failures, 0 errors. VC2 baseline was
  2688; the +8 is the new `VC3CompatibilityMatrixTest` (4 tests x 2
  flavors).

Compatibility matrix (format x reader-version x result, all on JVM):

- v1 MP3 bundle (`spec/fixtures/valid-bundle/`, spec 1.0) x v2
  reader: parse OK, `BundleValidator.validate` clean, gate Playable,
  `buildPlayable` 2 items (1832400 ms, 1640100 ms), all paths and
  URIs non-blank. PASS.
- Scribe (PC) bundle (`spec/fixtures/scribe-golden/`, spec 1.1, real
  Scribe pipeline output) x v2 reader: parse OK, validate clean,
  gate Playable, `buildPlayable` 2 items (7450 ms each). PASS.
- PC multi-voice bundle (`spec/fixtures/multivoice-golden/`, spec
  1.0, narrator plus Alice voices) x v2 reader: parse OK, validate
  clean, gate Playable, `buildPlayable` 1 item (1550 ms). PASS.
- 2.0 book (`spec/fixtures/unrendered-golden/`, spec 2.0) x v1
  reader: the v1.0.0 tree is not checked out here, so its gate
  cannot run on this JVM. Read instead at tag `v1.0.0`
  (`player/.../bundle/BundleValidator.kt`): specVersion must be
  "1.0", "1.1" or "1.2", else the named error
  `manifest.json: spec_version "2.0" must be "1.0", "1.1" or "1.2"`.
  A 2.0 book therefore takes the refusal branch; refusal
  cleanliness (named error with file plus rule plus version, no
  throw, fixture dir byte-identical before/after) is proven through
  the same-shaped refusal path in this tree. PASS at the version
  boundary, stated honestly.

Room v1-to-v2 migration: NO JVM test, infeasible with this
harness, and no fake test written instead. Exact reasons: the v1
schema was never exported (`exportSchema = false` in v1; only
`player/app/schemas/.../2.json` exists), and unit tests are plain
JVM JUnit with no Robolectric or room-testing (adding either is a
new dependency needing owner approval). The ms-to-NULL-sid rule the
device check must confirm comes from `ProgressEntity`: `sentenceSid`
is null for ms-based positions (every row written before v2), which
is what the nullable `ADD COLUMN` in `MIGRATION_1_2` yields for
existing rows. Data survival is proven by the owner-run over-install
procedure (`v1.0.0` install, import plus progress, install `2.0.0`
over it with the same key), now written as unticked OWNER-RUN steps
in `docs/ReleaseSigning.md` ("VC3 over-install procedure").

No new dependency, no permission change, no bundle-format change.
Not claimed: anything on the tablet.

---

## VC5 quality sweep (agent, 2026-10-09, JVM only, no device claimed)

### Sentence agreement (quote-heavy probe)

Method: 18 self-authored quote-heavy paragraphs (straight and curly
dialogue with tags, multi-sentence quotes short and long, abbreviations
and initials in dialogue, ellipsis dialogue, scare quotes,
apostrophes, single-quote-mode dialogue, a multi-paragraph open-quote
continuation, a stray inch-mark, question/exclamation dialogue, plus
one narration-only control) ran through the real Scribe splitters
(`text.sentences.split_paragraph` per paragraph plus
`text.dialogue.split_chapter_dialogue` over the chapter) and through
the real on-device chain (`SentenceSplitter.splitChapter` plus
`DialogueTagger.tagChapter`) via a throwaway probe test (deleted after
the run; no fixture committed, no copyrighted text used). Per-block
sentence-text sequences, tagged (text, kind) sequences, and
narration/dialogue runs (adjacent same-kind sentences merged, the IN6
acceptance shape) were compared exactly. Both sides round-trip every
paragraph byte for byte (asserted in both harnesses).

Result:

- Sentences: 17/18 blocks exact; 41 of 44 Scribe sentences identical
  (Kotlin yields 46: the one differing block splits after closing
  `?"`/`!"` before the dialogue tag where pysbd glues -
  `"Are you coming?"` / `he asked.` / `"Yes!"` / `she cried.`).
  Baseline-iterator behavior, erased downstream: the tagged texts on
  that block agree exactly on both sides.
- Tagged sentences: 17/18 blocks exact. The one diff is the known
  accepted singles-glue compensation (multi-sentence single-quoted
  region: 1 glued dialogue sentence on device vs 4 in Scribe,
  recorded in the IN6 entry and D-087).
- Runs: 18/18 blocks, 34/34 runs exact (100%).

Verdict: numbers are good, Android rules left alone (no code change).
The sentence-level `?"`-tag split is BreakIterator baseline behavior,
not a ported rule, and dialogue splitting normalizes it away; adding a
glue rule would fork from Scribe for no user-facing gain.

Environment: Windows 11, OpenJDK 21 (JDK `BreakIterator`, not Android
ICU). The tablet runs different BreakIterator tables; the device
re-check stays owner-deferred with the soak (VC7).

### Flake fix (UnrenderedReaderViewModelTest on virtual time)

Root cause: the class used `Dispatchers.Unconfined` with real-time
`delay` debounces plus a 2 s wall-clock poll loop (`awaitSaved`). Under
full-suite load the settle or the initial save did not land inside the
window (VC1 run: `jumpToChapter_loadsStartAndSaves`; VC3 run:
`initialLoad_missingSavedSid_fallsBackToChapterStart` with the seeded
sid 99 still stored at timeout). Both pass in isolation.

Fix: the whole class now runs on `runTest` with a
`StandardTestDispatcher` injected through the existing `dispatcher`
constructor seam (production code untouched). Real-time waits are gone:
initial loads and saves settle via `advanceUntilIdle`, and the debounce
test proves last-report-wins explicitly (report, advance 25 of 50 ms,
report again, advance past the debounce, assert sid 2 plus the saved
row). The class runs in 0.2 s wall time with zero timing dependence.

One test-only artifact added: `kotlinx-coroutines-test` 1.9.0
(version-matched to the pinned coroutines 1.9.0, same Apache-2.0
license; `testImplementation` only, never ships in either APK, so no
minSdk, permission, or license-scan impact). The Slice 12 plan
prescribes virtual time for this fix, which needs this artifact; no
production dependency added. `ReaderViewModelTest` keeps the same
real-time pattern but has no recorded flake, so it is left alone.

Proof (from player/, all with --no-daemon):

- `.\gradlew.bat :app:testCoreDebugUnitTest --tests
  "app.auloud.player.reader.UnrenderedReaderViewModelTest"
  --rerun-tasks` - 13/13 green.
- `.\gradlew.bat :app:testCoreDebugUnitTest
  :app:testFullDebugUnitTest --rerun-tasks` - three consecutive full
  runs, each 2696 tests (1344 core + 1352 full), 0 failures, 0 errors.
- `.\gradlew.bat :app:lintCoreDebug :app:lintFullDebug` - BUILD
  SUCCESSFUL, 0 errors on both flavors (46 warnings + 2 info per
  flavor, all pre-existing classes: pinned-version notices, which
  need owner approval to change, plus prior Compose/service notes;
  none in files this package touches except the generic version
  notice on the new test artifact).

### TODO/FIXME sweep and backlog

- `TODO|FIXME` over the repo: no hits in Player code or tests. The
  only matches are the Slice 12 plan prose naming this sweep and a
  Scribe UI8 stub note (owner future work, not cheap). Nothing to fix.
- Cheap backlog item (D-124 follow-up: complete-book bulk
  delete-stale-audio routing): judged NOT small and left out. It needs
  a delete path in `BookScreen` (which has no panel VM today), a
  manifest reload to flip complete to partial mid-screen, safe
  handling of the live playback/reader controllers holding deleted
  audio, confirm dialogs on both the Listen and Read branches, and
  tests. Honest deferral, no stub left behind.

No new production dependency, no permission change, no bundle-format
change. Not claimed: anything on the tablet (listening checks and the
device sentence re-check stay owner-deferred).

---

## VC6 docs pass (agent, 2026-10-09, docs only, no device claimed)

Prose only: no code, no bundle-format, no dependency change. README
gains the v2 on-device section (EPUB import to playing rendered book,
requirements, core/full flavors plus model packs, offline System TTS
voice prep, limitations, OWNER-RUN walkthrough with unchecked boxes);
new `docs/07-TestPlan.md` holds the v2 test plan (suites, owner device
rounds, VC8 gate) so the `07-TestPlan.md` pointers in
`docs/plans/Slice12.md` resolve; short v2
addendum appended to `docs/01-PRD.md`; Slice 12 status plus the decided
DCO box in `docs/09-V2Roadmap.md`; v2 status notes in `README.md` and
`docs/04-Roadmap.md`; the open `08-Licenses.md` box annotated, none
ticked. No new DECISIONS entry (no new product decision). Link check:
every path named above exists at head (`docs/07-TestPlan.md`,
`docs/ReleaseSigning.md`, `docs/constants.md`, `docs/test-log.md`,
`docs/plans/Slice12.md`, `docs/08-Licenses.md`,
`docs/09-V2Roadmap.md`, `docs/04-Roadmap.md`, `docs/01-PRD.md`,
`docs/archive/v1/07-Testplan.md`). The README walkthrough is written
for a fresh install but unrun: do not claim it until the owner ticks
its boxes on the Tab E.

---

## UX1 tablet fix batch (agent, 2026-10-10, JVM only, no device claimed)

Owner-reported batch (D-130): hub Listen entry, charging-gate removal,
voice-dropdown feedback, real buttons, synth IPC saving. No bundle-format,
dependency, or permission change. All device proof stays owner-run.

- Charging gate deleted outright (`PAUSE_CHARGER`, `chargingOnly`,
  `isCharging`, ChargingRow, prefs key, service message, error mapping);
  temperature plus storage guards untouched. Renders run unplugged.
- Hub Listen button plus per-row Play buttons ride the existing
  `openChapter`/`partialChapterTarget` path (gating single-sourced).
- Voice dropdown names its empty state ("Loading voices..." while the
  engines start, "No voices available" plus Reload after) reusing the
  existing `refresh()` load path; no new engine code.
- Synth overhead, safe part only: `SystemTtsAdapter.synthesize` no
  longer calls `installedVoices()` per sentence (the driver validates
  the name and fails the render the same way); `AndroidSystemTtsDriver`
  skips redundant `setSpeechRate`/`setVoice` calls when voice plus rate
  are unchanged. No sentence batching, no timing-math touch.
- Remaining per-sentence cost (~3 s/sentence on the Tab E per the
  owner) is engine-bound until a device RTF re-measure (owner job):
  re-record the System TTS RTF band from the debug overlay during the
  soak and retune `RENDER_RTF_LOW`/`RENDER_RTF_HIGH` plus
  `EST_MS_PER_WORD` from real renders (`docs/constants.md` procedure).

Not claimed: anything on the tablet (listen path by ear/eye, dropdown
on cold open, render RTF from the debug overlay, unplugged render runs).

---

## Slice 12 close-out waivers (owner, 2026-10-10, no device numbers)

Three gates closed by owner waiver instead of measurement (D-131, D-132):

- SysLong bench waived. Hypothesis verdict from Tab E specs (quad
  Cortex-A7 1.3 GHz, about 1.5 GB RAM, 32-bit) plus bands in hand
  (System TTS 0.86x to 2.56x, Piper 0.36x, owner-measured about
  3 s/sentence): streaming NOT feasible on the Tab E; v2 is
  background rendering, render-ahead stays NextN 5, Slice 13 is
  faster-devices-only. Flagged as hypothesis: no bench ran, and the
  `:spike` module is retained so a measured SysLong can overturn it.
- Beep onset measurement waived. Offset gate closed with the standing
  default (offset 0, 50 ms AAC tolerance, finalize still refuses
  nonzero). No evidence contradicts first-start-0.
- Source offer filled: Name Zyxary, Email kentandrewparejas@gmail.com,
  URL https://github.com/Zyxavy/Auloud (tag v2.0.0). Embedded in the
  rebuilt `full` release below; owner confirm of the NOTICE.full GPL
  paragraphs still owed before any `full` distribution.

Soak: owner reports multi-day soak good (one-line verdict, no
per-box numbers recorded).

Still owner-open (batched device round): VC2 smoke checklist, VC3
over-install proof, VC8 release tagging.

NOTICE.full confirm recorded 2026-10-10: owner confirmed both GPL
paragraphs (redistribute/modify under GPL-3.0-or-later with text in
assets/gpl-3.0.txt; Corresponding Source via assets/SOURCE_OFFER.txt
valid 3+ years) may ship with the filled offer (Zyxary /
kentandrewparejas@gmail.com / github Zyxavy/Auloud tag v2.0.0).

2026-10-10 correction to the SysLong waiver above: owner evidence
(ReadEra reads aloud on the tablet via live system TTS) challenges
the infeasibility hypothesis. ReadEra uses a live `speak()`
architecture; our band numbers describe the `synthesizeToFile` render
path and do not transfer. Streaming feasibility returns to OPEN for
a live architecture, gated on Slice 13 design plus measurement
(D-131 amended). Background rendering stays the v2.0 story.

---

## VC8 release (2026-10-10): smoke + over-install pass, tagged

Owner verdict: smoke checklist and over-install procedure both
"good and complete" (flavor not specified). Boxes ticked in
`docs/ReleaseSigning.md` on that verdict (the conditional fallback
box marked N/A, nothing lost). Release criteria (`09-V2Roadmap.md`
section 6) all ticked with evidence bases; `07-TestPlan.md` device
rounds ticked. Release APKs at head: core 3.5 MB, full 55.4 MB,
`licenseScan` + `releaseManifestCheck` green, suites green both
flavors. Tags: `slice-12`, `v2.0.0`. Not pushed (push needs owner
permission); until `dev/v2` plus tags are pushed, the SOURCE_OFFER
URL (tag v2.0.0) does not resolve. GitHub tracker admin still owed
(tools down).

---

## Slice 13 ST0: live-`speak()` gate tooling ready (agent, 2026-10-10)

Agent half done; gate numbers are owner device work (not claimed here).
- New pure-Kotlin `StreamGapStats` (`player/app/.../tts/`, thresholds
  median 150 ms / p95 400 ms / max 1 s per Slice13 decision 1): 10/10
  JVM tests green (`:app:testCoreDebugUnitTest --tests
  "...StreamGapStatsTest"`, BUILD SUCCESSFUL).
- Spike app (throwaway, `:spike:assembleDebug` green) gains four live
  buttons using `speak()` to audio out, not `synthesizeToFile`:
  Live20 (20 sentences at 1.0x + 1.5x, start latency + gap verdict),
  LiveSw (narrator/dialogue alternation on one instance, overall +
  switch-only gaps), Live2x (same alternation across two instances,
  sequential hand-off), LiveSil (silent-utterance accuracy at
  100/250/1000 ms).
- Owner run sheet (cool tablet, ReadEra engine + other engine if any):
  tap Live20, LiveSw, Live2x, LiveSil with the screen on, Export, paste
  `spike-results.txt` back; then Live20 once more with the screen off
  and report whether audio continued (a kill is itself gate data for
  ST4). Still owed: stop/restart latency and the 30-minute screen-off
  run (ST0 list), recorded when the numbers land.

## Slice 13 ST0 verdict: NO-GO on the Tab E (owner numbers, 2026-10-10)

Spike `09:54` run, Tab E confirmed (sdk 25, 7.1.1, 32-bit, 1427 MB,
battery 98 to 96%, temp 33.9 to 38.7 C). Thresholds: median 150 ms,
p95 400 ms, max 1 s; start-listen goal a second or two.

- Live20 single voice at 1.0x: n=19, med 526 ms, p95 1648 ms, max
  1648 ms, start latency 10241 ms. NO-GO.
- Live20 single voice at 1.5x: n=19, med 401 ms, p95 658 ms, max
  658 ms, start latency 51307 ms. NO-GO (the 51 s start smells of
  engine warmup variance, not architecture; the gaps alone fail).
- LiveSw one-instance alternation: all med 476 ms, switch-only med
  483 ms, p95 622 ms. NO-GO.
- Live2x two-instance hand-off: med 963 ms, p95 5012 ms, max 5012 ms.
  Dramatically worse; one instance is the less-bad variant by 4-5x.
- LiveSil pause accuracy: 100 ms reads 101, 250 reads 250, 1000 reads
  1000. Silent utterances are exact; pacing is not the problem.
- Context: file-synthesis SysLong on the same run 0.40 RTF
  (NEITHER); the old `systemWarmedRtf` line reads 14.07 with a 20 s
  per-switch gap, confirming those numbers were switch-cost-polluted
  (Samsung voice switching is catastrophic) and never described live
  speech. Piper file path about 0.30x, two models 274 MB (fits RAM,
  still far too slow).

Caveats: the spike picked ru-ru/es-es voices (first offline pair, not
English); a re-run with English voices is available on request but the
margins (best med 2.7x over, best start latency 5x over the goal) do
not justify more device time for the verdict. The screen-off
continuation and 30-minute run were not reported and are moot: with
half-second median gaps the path fails sitting still.

Verdict (D-137): NO-GO, including the single-voice reduced go (Live20
single-voice also fails). Background rendering stays the listening
path. The ST1-ST6 code stays behind `GATE_PASSED=false` as the
faster-device head start, not deleted; `docs/streaming.md` is the
retained design. Slice 13 ends here per plan section 6.

2026-10-10 override (D-138): owner ordered the gate GO anyway ("good
enough", variant (a)). Numbers above stand unchanged; verdict and
`GATE_PASSED` flip, `slice-13` re-tags, #19 reopens to ST7. ST7 by ear
is the next verdict.

Gate-flip verification (agent, 2026-10-10, commit 416b65e tree):
core suite 1417 green + `lintCoreDebug` green; full suite 1425 green
+ `lintFullDebug` green. Both flavors build (`app-core-debug.apk`
14.8 MB, gate-passed, for the ST7 round).
