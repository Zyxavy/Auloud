# Emotion A/B listening gate (Task 8)

Status: pending. Gain implemented (Task 6); listening not yet done.

## How to prepare 20 pairs

1. Pick a book with dialogue variety and two voices (e.g. narrator + one character).
2. Render the same 20 lines twice:
   - A (untagged): `scribe build <book> --emotion off`
   - B (tagged+gain): `scribe build <book> --emotion speed+gain`
   Use `--chapters` to limit to the relevant chapter(s).
3. Extract the matching sentences from each render (MP3 + timings from `text/chNNN.json`).
4. Randomize order per pair; label files `pair_01_a.mp3` / `pair_01_b.mp3` without revealing which is tagged.
5. Listen on speakers and headphones; record below.

## Results table

| Pair | Tag type | Voice | Preferred (A/B) | Emotion match (1-5) | Artifacts |
|------|----------|-------|-----------------|---------------------|-----------|
| 1    |          |       |                 |                     |           |

## Decision

- Tagged preferred in clear majority? (yes/no)
- Artifacts observed? (yes/no)
- Action: flip default / keep opt-in / remove (record in DECISIONS.md)
PY