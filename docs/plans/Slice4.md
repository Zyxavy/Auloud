# Slice 4 Implementation Plan: Multi-Voice

Project: Auloud Scribe (main work), with one small Player check. Follows `04-Roadmap.md` (Slice 4), `05-ScribeDesign.md`, `03-BundleSpec.md`. Builds on Slice 2 (Kokoro via kokoro-onnx, hash-keyed cache, resumable build, `draft`/`build`/`inspect`/`validate`) and Slice 3 (reader that already carries `speaker` on every sentence).

## 1. Goal

Dialogue gets its own voices. Scribe detects quoted speech, guesses who is speaking, assigns each character a voice from an editable `cast.yaml`, and renders narrator and characters differently. Mistakes are fixed by editing `cast.yaml` and re-running, with no code changes.

**Done when** (from the roadmap):

- A dialogue-heavy chapter sounds clearly different for narrator and characters
- Mislabelled speakers can be fixed by editing `cast.yaml` and re-running, with no code changes

## 2. Scope

| In | Out (later) |
| --- | --- |
| Dialogue detection (straight and curly quotes, multi-paragraph quotes) | LLM speaker attribution (v1.1 backlog) |
| Splitting mixed sentences at quote boundaries | Dash-style dialogue, non-English (English only) |
| Rule-based speaker attribution with confidence | In-app cast editing, speaker colors in the reader |
| `cast.yaml` (draft, merge, aliases, overrides, voice palette) | Pitch shifting (see decision below) |
| Multi-voice rendering with per-voice leveling | Emotion or style control |
| `voices --sample` audition, `inspect` speaker stats, `cast_report.md` | PDF (Slice 5) |

## 3. Prerequisites

- A dialogue-heavy public-domain test book (suggestion: *Alice's Adventures in Wonderland* or a Sherlock Holmes short story from Project Gutenberg; Yellow Wallpaper has almost no dialogue, so it is not enough)
- Slice 3 closed out and tagged
- spaCy and `en_core_web_sm` installable on your Windows setup (checked in MV0)

## 4. Key design decisions (log in `DECISIONS.md` as they are made)

1. **Two-layer speaker data.** `draft` writes the raw attribution into `script.json` (candidate speaker string, confidence, a stable quote key). `build` resolves the final speaker and voice using `cast.yaml` (aliases, overrides, top-N characters, generic fallbacks). Editing `cast.yaml` then needs only `build`, not a new `draft`, and the sentence cache re-renders only the changed lines.
2. **Overrides anchor on a stable key**, not on `sid`: `(chapter, block id, quote number within the block)`, or a text-match rule. Sentence ids shift whenever splitting rules change; block ids and quote order don't.
3. **`draft` never clobbers your `cast.yaml`.** On re-run it merges: your entries stay, newly discovered characters are added.
4. **Speed offsets only, no pitch shifting in v1.** Kokoro has many distinct voices and a native speed parameter. Pitch shifting would need an extra tool (for example Rubber Band) and tends to sound artificial. Revisit only if voices prove too similar.
5. **Per-voice loudness leveling** at assembly time (not in the cache), so changing gain never invalidates cached audio.
6. **First-person narration:** "I said" maps to the narrator voice by default, or to a named character via `cast.yaml`.

## 5. Work packages (in order)

### MV0: Voice audition and spaCy check (S)

- Implement `scribe voices --sample` (deferred from Slice 2): renders one test sentence in every available Kokoro voice to a folder of WAVs, plus a quick text list of voice names
- You listen and shortlist a palette: 1 narrator, 4-6 character voices, plus one generic female and one generic male
- Install spaCy + `en_core_web_sm`; check it works in the project environment and in the packaged wheel (remember the force-include lesson from Slice 2)
- **Verify:** sample folder produced; palette written down in `docs/test-log.md`; `scribe doctor` gains a spaCy-model check

### MV1: Speaker gold set (M)

You can't improve attribution without measuring it.

- Hand-label about 100-150 dialogue lines from the test book(s): chapter, block, quote number, true speaker
- Store under `spec/fixtures/speakers-gold/` as YAML; add `scribe/dev/eval_speakers.py` that runs attribution and prints accuracy overall and by confidence level
- Targets to tune (not hard promises): at least 80% overall; at least 90% on high-confidence lines
- **Verify:** the evaluation script runs and reports a baseline (even if low)

### MV2: Dialogue detection and sentence splitting (M)

- State machine over paragraphs: straight and curly double quotes; single quotes only when a paragraph has no double quotes; multi-paragraph quotes (open quote with no close continues into the next paragraph)
- Mixed sentences split at quote boundaries into separate sentence records (`"We should leave,"` dialogue; `she said.` narration), each tagged `kind: narration | dialogue` with its quote key
- Exact round trip: the Player's spacing rule applied to the split sentences must reproduce the original paragraph; check the rule used in Slice 2/3 and keep it
- Unbalanced or stray quotes are logged and the paragraph is treated as narration
- **Verify (unit tests):** curly, straight, multi-paragraph, nested single, apostrophes, unbalanced, round trip; sids stay consecutive from 1

### MV3: Candidate extraction (M)

- spaCy per paragraph: for tag sentences containing a speech verb (said, asked, replied, cried, whispered, and so on), take the subject (dependency parse) and its noun chunk as the speaker candidate; strip determiners and normalize ("the Queen" and "Queen" match)
- Named characters from `PERSON` entities and titled names (Mr., Mrs., Miss, Dr.); descriptive characters ("the Hatter", "the Cat") from subjects of speech verbs
- Alias clustering by simple rules (last-name match, title + name, case-insensitive)
- Gender hints from pronouns near the name and a small name list
- **Verify (unit tests):** known sentences yield the expected candidate and normalized key

### MV4: Attribution rules and confidence (M)

In priority order, each producing a confidence (high, medium, low):

1. Explicit tag adjacent to the quote, either side: high
2. Pronoun tag ("he/she said"): resolved to the most recent matching speaker: medium
3. Alternation in runs of dialogue-only paragraphs between two known speakers: medium
4. Continuation of a multi-paragraph quote keeps its speaker
5. Fallback to the previous dialogue speaker, or `unknown`: low

- **Verify:** run the MV1 evaluation after each rule; keep a table of accuracy by rule in `docs/test-log.md`

### MV5: `cast.yaml` model, merge and resolution (M)

- Format as in `05-ScribeDesign.md`: narrator, characters (voice, speed), `aliases`, `default_female`, `default_male`, `first_person`, `overrides` (by quote key or text match)
- Resolution function used by `build`: raw speaker, then alias, then override, then character (top N by line count, default 5), else the gender-matched generic, else the default female/male
- Draft generation and merge: new characters added with auto-assigned palette voices (gender guess); existing user entries preserved
- Validation with clear errors (unknown voice name, alias pointing nowhere, duplicate alias)
- **Verify (unit tests):** resolution order, merge preserves edits, bad voice name rejected

### MV6: `draft` extension and cast report (S)

- `draft` runs MV2-MV4 and writes `script.json` with raw speaker, confidence, quote key; writes or merges `cast.yaml`
- `cast_report.md`: characters with line counts, then low-confidence lines grouped by chapter with a few lines of context and the quote key to paste into `overrides`
- Staleness rule for `build`'s auto-draft: re-draft only if the source hash or the attribution rules version changed, never because `cast.yaml` changed
- **Verify:** draft twice gives identical output; editing `cast.yaml` does not trigger a re-draft

### MV7: Multi-voice rendering and leveling (M)

- `build` resolves each sentence to its voice and speed offset and synthesizes (cache key already includes voice and speed)
- Per-voice loudness leveling: measure a representative loudness for each voice's audio and apply a gain at assembly; keep the chapter peak limit; deterministic
- Pauses: a short pause (about 100 ms, configurable) between a dialogue sentence and the narration tag that was split from the same sentence; normal pauses between real sentences and paragraphs
- Re-run after a cast edit re-synthesizes only affected sentences; print how many were cache hits
- **Verify (unit tests with a fake engine):** timing arithmetic with mixed voices and short pauses; ordering and non-overlap preserved; cache hit counts after a cast edit

### MV8: Bundle voices map, validation, inspect (S)

- Manifest `voices` filled from the resolved cast (engine, voice, speed); each sentence's `speaker` is the resolved character key
- Validator: every sentence speaker exists in `voices`; each `voices` entry names a real voice; no unused-voice warning as error
- `scribe inspect --speakers`: lines and seconds per speaker, characters with low-confidence counts
- Add a small multi-voice golden bundle to `spec/fixtures/` (tiny, public-domain text) for contract tests
- **Verify:** `scribe validate` passes on the golden bundle and rejects an unknown speaker

### MV9: Player contract check (S)

- Add a Player JVM test that loads the multi-voice golden bundle through the real `ChapterTextLoader` and checks the layout/spacing round trip on split sentences
- Check on the tablet that highlight and tap-to-jump behave with many short sentences inside one paragraph (the highlight will change quickly mid-paragraph); if anything misbehaves, record it and fix in this slice
- No format change is expected; the spec already carries `speaker` and `voices`
- **Verify:** both test suites green; your device check in MV10

### MV10: Acceptance (you)

- Build a dialogue-heavy chapter or the full test book with `draft`, review `cast_report.md`, edit `cast.yaml`, `build`
- Check:
  - [ ] Narrator and characters are clearly distinguishable by ear; no character voices that sound the same
  - [ ] Loudness is comparable across voices
  - [ ] A deliberately mislabelled line is fixed by adding an override and rerunning `build`, and only that line is re-rendered
  - [ ] `scribe validate` passes; the bundle imports and plays on the tablet; highlight correct on rapid dialogue
  - [ ] Real-time factor recorded and still acceptable
- **Verify:** all items in section 7

## 6. Suggested order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | MV0, MV1 | choose voices and have a measurable accuracy baseline |
| 2 | MV2, MV3, MV4 | see dialogue split and speakers guessed, with accuracy numbers |
| 3 | MV5, MV6 | edit `cast.yaml` and read a cast report |
| 4 | MV7, MV8 | hear multi-voice audio and validate bundles |
| 5 | MV9, MV10 | confirm everything on the tablet |

## 7. Definition of done for Slice 4

- [ ] All work-package verifications passed; Scribe and Player suites green
- [ ] Gold-set accuracy recorded in `docs/test-log.md` (overall and by confidence)
- [ ] Cast-edit round trip works: edit `cast.yaml`, run `build`, only affected lines re-render
- [ ] A dialogue-heavy chapter judged clearly multi-voice by your listening test, on the tablet
- [ ] Decisions in section 4 logged; roadmap ticked only for verified items; tagged `slice-4`

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Attribution accuracy is poor on unusual books | Gold set to measure, confidence levels, `cast_report.md` review loop, overrides; LLM pass stays in the v1.1 backlog |
| Descriptive speakers ("the White Rabbit") missed by NER | Take speech-verb subjects from the dependency parse, not only `PERSON` entities |
| Voices sound too similar or tiring | MV0 audition; limit to the top 4-6 characters; speed offsets |
| Loudness differs between voices | Per-voice leveling at assembly |
| Overrides break after rule changes | Anchor on chapter/block/quote number, not `sid` |
| Short sentences slow synthesis (call overhead) | Measure RTF in MV10; batch adjacent same-voice sentences only if needed |
| spaCy or its model fails to install or package on Windows | Check in MV0 and in `doctor`; remember to force-include any new module and reinstall before trusting `uv run scribe` |
| Quote-style edge cases (nested quotes, unbalanced) | Log and fall back to narration; unit tests |
| Licenses | spaCy and its English model are MIT as far as I know; verify and update `08-Licenses.md` |

## 9. Working with the agent

- One work package per session; it writes code and tests, runs `pytest` and the gold evaluation; **you** do all listening tests and the tablet check
- Run the evaluation script after each attribution change and paste the numbers into the report
- No new dependency without license and Windows checks (spaCy first, in MV0)
- Commit per work package (`MVn: summary`); tag `slice-4`

## 10. Next

Slice 5 (complete pass): PDF support, chapter navigation, error handling, the full-novel soak test, and the README. Multi-voice makes the soak test more interesting: use a full dialogue-heavy novel.