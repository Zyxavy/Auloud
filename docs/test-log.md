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
