# KT4 A/B listening protocol: Kitten vs System TTS vs Piper

## Passage

`docs/archive/v2/kitten-ab-passage-clean.txt` (604 words, Alice ch.1 opening, public domain). `docs/archive/v2/kitten-ab-passage.txt` is the same text with `[speaker/kind]` labels per sentence (Alice dialogue vs narrator) for checking voice distinction. Render the clean text with each engine below; the bracket file never goes into an engine.

## Engines (same passage each)

- System TTS (installed offline voice, note engine + voice name)
- Piper (whatever packs are installed, note variant: low or medium)
- Kitten nano int8 (two sids: pick the listening-confirmed female + male pair if KT0 mapped them, else sids 1 and 0)
- Kitten micro (same two-sid setup)

## Procedure (yours)

1. Render or audition the passage with each engine (Voice lab audition for short checks, one-chapter render for the 10-minute listens).
2. Listen in random order, 10 minutes per engine: once on headphones, once on the tablet speaker.
3. Score each engine 1-5 on: clarity, naturalness, fatigue (5 = no fatigue), narrator-vs-dialogue distinction. Count mispronunciations.
4. Give special attention to the tricky items below; note exactly how each engine says them.

## Tricky items (in the passage)

- "four thousand miles down, I think" (number words plus hedge)
- "ORANGE MARMALADE" (all caps on a label)
- "waistcoat-pocket", "Rabbit-Hole" (hyphenated compounds)
- "How brave they'll all think me at home!" (exclamation with contraction)
- "the centre of the earth" (British spelling)
- Nested punctuation: quoted questions inside narration ("I wonder how many miles I've fallen by this time?" she said aloud)
- Em-dashes and parenthetical asides ("(Which was very likely true.)", "Down, down, down.")

## Score sheet (copy into `docs/test-log.md`)

| Engine / voices | Clarity | Naturalness | Fatigue | Distinction | Mispronunciations | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| System TTS (...) | /5 | /5 | /5 | /5 | n: ... | |
| Piper (... variant) | /5 | /5 | /5 | /5 | n: ... | |
| Kitten nano int8 (sids .., ..) | /5 | /5 | /5 | /5 | n: ... | |
| Kitten micro (sids .., ..) | /5 | /5 | /5 | /5 | n: ... | |

Conclusion (one paragraph): which engine wins for the tablet, and whether Kitten should become the recommended neural tier (KT5 input).
