# Slice 2 Implementation Plan: Scribe, Single Voice

Project: Auloud Scribe (Python CLI + library, Windows 11). Follows `04-Roadmap.md` (Slice 2), `05-ScribeDesign.md`, `03-BundleSpec.md`, and D-020. Player side is done (Slice 1), so this slice only has to produce bundles the Player already reads.

## 1. Goal

A real EPUB becomes a valid bundle: one narrator voice, per-chapter CBR MP3, text JSON with exact sentence timings, manifest. It validates, imports into the Player, and plays.

**Done when** (from the roadmap):

- A short public-domain book converts without errors and passes `scribe validate`
- The bundle imports into the Slice 1 Player and plays on the Tab E

## 2. Scope

| In | Out (later slices) |
| --- | --- |
| EPUB parse and cleaning (chapters, headings, paragraphs, italic/bold spans) | PDF (Slice 5) |
| Sentence splitting | Dialogue detection, speaker attribution, `cast.yaml` with characters (Slice 4) |
| One narrator voice (Kokoro or Piper, chosen by a spike) | Multiple voices, pitch offsets (Slice 4) |
| Sentence-level timings from one continuous stream | Read-along UI (Slice 3) |
| CBR MP3 encoding, manifest, chapter JSON | LLM speaker tagging, GUI |
| `doctor`, `draft`, `build`, `validate`, `inspect` | `voices --sample` may slip to Slice 4 |

## 3. Prerequisites (you)

- [ ] Python 3.11+ and `uv` installed
- [ ] `ffmpeg` and `ffprobe` on PATH (`winget install ffmpeg`)
- [ ] espeak-ng installed (needed by Kokoro and Piper phonemization)
- [ ] One short public-domain EPUB for testing (a novella or short story, 20-60 minutes of audio), plus one messy one for later
- [ ] A `scribe/` folder in the repo; `spec/fixtures/` from Slice 1 available

## 4. Work packages (in order)

### SW0: Engine spike (S), do this first

Biggest unknowns are Windows setup, speed and quality, so retire them before building anything.

- Install Kokoro (and separately Piper) in a scratch environment; check current install docs via Context7
- Synthesize the same 5 sentences with each; save WAVs
- Measure real-time factor (audio seconds / wall seconds) on your laptop, CPU and GPU if you have one
- Listen; pick the engine and a narrator voice
- **Verify:** written result (RTF, install pain, subjective quality) in `docs/test-log.md`; log the choice in `DECISIONS.md`
- **Rule of thumb:** a 10-hour book at RTF 0.5 (twice real time) takes about 5 hours, so know this number now

### SW1: Project setup (S)

- `scribe/` with `pyproject.toml` (uv), package layout from `05-ScribeDesign.md`, GPL-3.0 `LICENSE`, `pytest`, `ruff`
- `cli.py` (typer) with `scribe doctor`: checks Python, ffmpeg/ffprobe, espeak-ng, chosen TTS engine and model, GPU; prints exact install help for anything missing
- **Verify:** `uv run scribe doctor` gives a clear pass/fail table; `uv run pytest` runs

### SW2: Bundle models and validator (M)

Build the checker first; it defines "correct" for everything after.

- `bundle/models.py`: dataclasses or pydantic models for manifest, chapter file, sentence, block, span
- `bundle/validate.py`: every rule in `03-BundleSpec.md` section 7 (files exist, sha256, speakers in `voices`, sid consecutive, timing rules, MP3 mono CBR matches manifest via ffprobe, duration within 50 ms, UTF-8)
- Reuse the shared `spec/fixtures/` (same valid and bad bundles the Player tests use)
- Error messages name file and rule (`ch002.json: sentence 14 overlaps sentence 13`)
- **Verify (unit tests):** valid fixture passes; each bad fixture fails with the expected message

### SW3: EPUB extraction and cleaning (M)

- Read the spine in order; walk each document's HTML with BeautifulSoup (lxml)
- **Decision point (ask the user):** `ebooklib` (easy, AGPL-3.0) or a small own parser using `zipfile` + OPF (a bit more code, avoids the AGPL question). Log in `DECISIONS.md`
- Map elements: `h1-h3` heading; `p` para; `blockquote` quote; `hr` or centered asterisks break; `em/i` and `strong/b` become spans (character offsets into the cleaned text)
- Drop: nav/TOC pages, copyright boilerplate, empty paragraphs, footnote markers, image-only pages (log each drop)
- Chapter titles from the nav/TOC or first heading; untitled become "Chapter N"; merge tiny chapters (under about 200 words) into the next
- Normalize to NFC, collapse whitespace, keep curly quotes
- **Verify (unit tests):** a generated mini-EPUB with known structure produces the expected chapters/blocks/spans; boilerplate is dropped

### SW4: Sentence splitting (S)

- `pysbd` per paragraph; post-fixes for Mr./Mrs./Dr./St., ellipses, initials
- Do not split inside a quotation unless it exceeds about 3 sentences (dialogue splitting proper is Slice 4)
- Preserve original text exactly (concatenating sentences with the original spacing reproduces the paragraph)
- Headings become one sentence each (they will be read aloud)
- **Verify (unit tests):** abbreviation, ellipsis, quote, and round-trip cases

### SW5: `scribe draft` (S)

- Writes the work folder `.scribe/<book-id>/`: `script.json` (chapters > blocks > sentences, all speaker = `narrator`), minimal `cast.yaml` (narrator voice only), `draft_report.md` (chapter list, word counts, dropped elements, estimated audio duration)
- **Book id:** derive it deterministically (UUID v5 from the source file's sha256), so rebuilding the same book keeps the same id and the Player keeps its saved progress
- **Verify:** draft on the test EPUB produces sensible chapters; running twice gives identical output

### SW6: TTS engine and cache (M)

- `tts/base.py`: `TTSEngine.synth(text, voice, speed) -> np.ndarray`, `sample_rate`
- Engine class for the winner of SW0; output at 24 kHz (resample if needed)
- `tts/cache.py`: key = hash(text, voice, speed, pitch, engine version); store as compressed audio; get-or-synthesize
- Handle empty/whitespace-only sentences and very long sentences (split on commas as a fallback)
- **Verify (unit tests with a fake engine):** cache hit skips synthesis; changing the voice invalidates; **plus one real-engine smoke test** (marked slow) synthesizing a short sentence

### SW7: Assembly, timings and encoding (M)

- For each chapter, stream audio into one `ffmpeg` process (stdin, raw PCM, mono 24 kHz, `-codec:a libmp3lame -b:a 64k`, constant bitrate). Streaming keeps memory small on long chapters and is one continuous encode, as the spec requires
- Timings from sample counts: `start_ms`/`end_ms` from running sample offsets; silence after each sentence per design (250 ms sentence, 500 ms paragraph, 800 ms heading, 1000 ms break); `end_ms` excludes the pause
- Loudness: simple deterministic gain to a target RMS/peak in numpy (no duration change)
- After encoding: `ffprobe` duration must match the sample-count duration within 50 ms; if it doesn't, investigate encoder delay/padding (keep the MP3 Info/Xing header) before shipping
- **Verify (unit tests):** timing arithmetic with a fake engine (known lengths) gives exact offsets; ordered, non-overlapping; **integration:** two-chapter mini-book encodes to mono 24 kHz 64 kbps CBR

### SW8: Bundle writer and manifest (S)

- Writes `manifest.json` (`spec_version`, deterministic `id`, title/author from the EPUB, `type: epub`, `source` with sha256, `audio`, `voices` with the narrator, chapters with durations), copies the original to `source/book.epub`, extracts the cover if present, `audio/chNNN.mp3`, `text/chNNN.json`
- Runs `validate` at the end and fails loudly if it does not pass
- **Verify:** output of the mini-book passes `scribe validate`

### SW9: `scribe build`, `inspect`, progress (M)

- `scribe build book.epub`: runs draft if needed, then renders chapter by chapter; resumable (finished chapters skipped; cache reuse), `--strict` aborts on any chapter error
- Rich progress bar with ETA; on finish print total audio time, wall time and real-time factor; write `scribe.log`
- `scribe inspect <bundle>`: chapters, durations, speakers, sample sentences
- **Verify:** kill a build mid-way and rerun; it resumes and produces an identical bundle

### SW10: Contract test with the Player (S)

- Commit one tiny golden bundle built by Scribe (a few seconds, synthetic or short public-domain text) to `spec/fixtures/scribe-golden/`
- Add a Player JVM test that parses and validates it with the real `BundleParser`/`BundleValidator`
- **Verify:** both `uv run pytest` and `./gradlew :app:testDebugUnitTest` pass; this catches format drift between the halves

### SW11: Acceptance run (you)

- Convert the real test EPUB with `scribe build`
- `scribe validate` passes; `scribe inspect` looks right
- Copy the bundle to the tablet (microSD sneakernet or the Player's watch folder), import, and play
- Listen: chapter starts and ends sit at the right places, no gaps or clicks between chapters, seeking is accurate
- **Verify:** all items in section 6

## 5. Suggested build order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | SW0 | know which engine, how fast, and how it sounds |
| 2 | SW1, SW2 | run `doctor` and validate the Slice 1 fixtures from Python |
| 3 | SW3, SW4, SW5 | see a drafted script from a real EPUB |
| 4 | SW6, SW7, SW8 | build a real chapter and validate it |
| 5 | SW9, SW10 | full `build` with resume, plus the cross-check with the Player |
| 6 | SW11 | play it on the tablet |

## 6. Definition of done for Slice 2

- [ ] `scribe build` converts a short public-domain EPUB end to end without manual edits
- [ ] `scribe validate` passes; MP3s are mono 24 kHz 64 kbps CBR; durations agree within 50 ms
- [ ] Killing and resuming a build works and gives the same output
- [ ] The Slice 1 Player imports and plays the bundle on the tablet (your test)
- [ ] The golden-bundle contract test passes on both sides
- [ ] Real-time factor recorded in `docs/test-log.md`
- [ ] Decisions logged (see section 8); roadmap ticked only for verified items; tagged `slice-2`

## 7. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Neural TTS on your laptop is too slow (a novel takes a day) | SW0 measures it first; consider a GPU, a smaller voice model, or Piper; the cache and resume make long builds survivable |
| Windows install trouble (espeak-ng, PyTorch size, Python versions) | `scribe doctor`, install steps written down during SW0 |
| MP3 encoder delay shifts timings | Check ffprobe duration in SW7; keep the Info/Xing header; investigate if over 50 ms |
| Messy EPUB structure (odd chapters, missing TOC, front matter) | Draft report lists chapters and dropped items; fix rules against 2-3 books |
| Sentence splitting errors (abbreviations, dialogue) | Focused unit tests; quotes stay attached for now |
| Long chapters use too much memory | Stream PCM to ffmpeg instead of holding a whole-chapter buffer |
| Format drift with the Player | Shared fixtures and the golden-bundle test (SW10) |
| AGPL dependency (ebooklib, later PyMuPDF) | Decision point in SW3; see `08-Licenses.md` |

## 8. Decisions to log during this slice

- TTS engine and narrator voice (after SW0)
- EPUB parser: ebooklib vs own (SW3)
- Deterministic book id from the source hash (SW5)
- Streaming encode and the pause lengths (SW7)
- Loudness method (SW7)

## 9. Working with the agent

- One work package per session; tell it which one. Use the `scribe-python`, `auloud-bundle` and `slice-workflow` skills
- It writes code, tests and runs `pytest` and `ffprobe` checks; **you** do the listening tests and the tablet import, since it can't judge how a voice sounds
- No dependency added without checking license and Windows support first (SW0 sets the TTS one)
- Commit per work package (`SWn: summary`); tag `slice-2` at the end

## 10. Next

Slice 3 (read-along) builds the Player's reader view on the text JSON this slice produces, so a bundle from a real book is also your test data for it.