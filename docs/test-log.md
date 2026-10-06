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
