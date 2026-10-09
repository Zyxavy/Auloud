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
