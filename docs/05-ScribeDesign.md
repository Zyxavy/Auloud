# Scribe Design (PC/server tool)

Scribe turns an EPUB or PDF into a book bundle (see `03-BundleSpec.md`). Language: Python 3.11+. License: GPL-3.0.

## 1. Commands

| Command | What it does |
| --- | --- |
| `scribe draft book.epub` | Parse, clean, split, tag speakers. Writes a work folder with `script.json`, a draft `cast.yaml` and `cast_report.md`. No audio yet. |
| `scribe build book.epub` | Render audio from the work folder (using your edited `cast.yaml`) and write the bundle. Resumable. |
| `scribe validate <bundle>` | Run the bundle checks from the spec. |
| `scribe inspect <bundle>` | Print chapters, durations, speakers, sample sentences. |

Typical flow: `draft`, edit `cast.yaml`, `build`, `validate`, copy to the tablet.

## 2. Pipeline

```
EPUB/PDF -> extract -> clean -> split sentences -> detect dialogue -> attribute speakers
   -> cast.yaml (edit) -> synthesize per sentence -> assemble chapter buffer
   -> encode CBR MP3 -> write text JSON + manifest -> validate
```

Work folder (`.scribe/<book-id>/`):

```
script.json        # tagged text, no audio
cast.yaml          # characters and voices (you edit this)
cast_report.md     # low-confidence lines to review
cache/             # synthesized sentence audio, keyed by hash
out/               # the finished bundle
```

## 3. Data model

```python
Sentence(sid, text, speaker, spans, confidence)
Block(id, type, level, sentences)      # heading | para | quote | break
Chapter(index, title, blocks)
Book(id, title, author, type, chapters)
```

`script.json` is this model serialized. Audio timings (`start_ms`, `end_ms`) are added only at build time.

## 4. Extraction and cleaning

**EPUB:** `ebooklib` to read the spine in order, `BeautifulSoup` (lxml) to walk each chapter's HTML.

- `h1-h3` become headings; `p` become paragraphs; `blockquote` becomes quote; `hr` or centered asterisks become breaks.
- Keep `em/i` and `strong/b` as `spans`.
- Drop: nav pages, copyright boilerplate, empty paragraphs, footnote markers, image-only pages (log them).
- Merge tiny chapters (under about 200 words) into the next one; give untitled chapters "Chapter N".

**PDF:** PyMuPDF text extraction, then remove repeated headers and footers and page numbers, join hyphenated line breaks, rebuild paragraphs from line spacing. If the result looks bad (high ratio of short lines), fall back to page-level sync mode in the spec.

**Normalization:** NFC, curly quotes preserved, expand nothing automatically. Add a small replacement file (`pronounce.yaml`) for names and words the TTS mispronounces.

## 5. Sentence splitting

Use `pysbd`. Post-fix: do not split after common abbreviations (Mr., Mrs., Dr., St.), and never split inside a quotation mark pair unless the quote is over 3 sentences.

## 6. Dialogue detection

A small state machine over each paragraph:

- Quote openers: `"`, `“`. Closers: `"`, `”`. Single quotes `‘ ’` count only when no double quotes are in the paragraph.
- **Multi-paragraph quotes:** a paragraph that starts with an opener but never closes continues into the next paragraph, which starts with another opener.
- **Mixed sentences:** a sentence like `"We should leave," she said.` is split at quote boundaries into two sentence records: `"We should leave,"` (dialogue) and `she said.` (narration). The Player joins sentences in a paragraph inline, so the display looks unchanged.
- Apostrophes inside words and stray quote characters are ignored; unbalanced quotes are logged, and the paragraph is treated as narration.

Each sentence gets `kind = narration | dialogue`.

## 7. Speaker attribution (rules, in order)

1. **Explicit tag in the same or adjacent narration:** patterns like `"..." <Name> said/asked/replied/whispered/shouted`, `<Name> said, "..."`. Confidence high.
2. **Pronoun tag:** `he/she said`. Resolve to the most recently mentioned character whose gender matches (from name lists or previous pronouns). Confidence medium.
3. **Alternation:** in a run of short dialogue-only paragraphs between two known speakers, alternate. Confidence medium.
4. **Continuation:** a multi-paragraph quote keeps its speaker.
5. **Fallback:** the previous dialogue speaker, or `dialogue_default`. Confidence low.

Character names come from spaCy `PERSON` entities (`en_core_web_sm`), filtered by frequency near speech verbs. Only the top N characters (default 5) get their own voice; everyone else uses the gender-matched generic voice.

`cast_report.md` lists low-confidence lines with a bit of context so you can fix them in `cast.yaml` (per-line overrides by `sid` or by a regex).

## 8. `cast.yaml`

```yaml
narrator:
  engine: kokoro
  voice: af_sarah
  speed: 1.0
characters:
  Ana:     { voice: bf_emma,  speed: 1.0, pitch: 1.05 }
  Marcus:  { voice: am_adam,  speed: 0.95, pitch: 0.97 }
default_female: { voice: af_bella }
default_male:   { voice: am_michael }
aliases:
  Ana: [Anastasia, "Miss Vega"]
overrides:
  - { chapter: 3, sid: 41, speaker: Marcus }
  - { match: "^\"Run!\"", speaker: Ana }
```

Voice names above are examples; check the names your installed Kokoro or Piper version provides. Pitch offsets are small (about 10% max), applied only if you enable `pyrubberband`; otherwise ignored.

## 9. Synthesis

```python
class TTSEngine:
    sample_rate: int
    def synth(self, text: str, voice: str, speed: float) -> np.ndarray: ...
```

Implementations: `KokoroEngine` (native 24 kHz, matching the spec) and `PiperEngine` (resampled to 24 kHz). Adding an engine means one new class.

- **Cache:** each sentence's audio is stored under a hash of (text, voice, speed, pitch, engine version). Editing `cast.yaml` re-synthesizes only changed lines. Builds resume after a crash.
- **Pauses (silence inserted after each sentence):** 250 ms sentence, 500 ms paragraph, 800 ms heading, 1000 ms scene break. Configurable.
- **Loudness:** normalize each chapter buffer to a consistent level (for example, `ffmpeg loudnorm` targeting -16 LUFS) so voices don't jump in volume.
- **Long text:** synthesize one sentence at a time (keeps engines within their limits and gives exact timings).

## 10. Assembly and timings

For each chapter: concatenate sentence audio and pauses into one buffer. A sentence's `start_ms` is its offset in the buffer; `end_ms` is its offset plus its own audio length (not including the following pause). Write the buffer as WAV, then `ffmpeg -ac 1 -ar 24000 -b:a 64k -codec:a libmp3lame` to MP3. Verify the MP3 duration with `ffprobe` against the buffer length (within 50 ms) before writing JSON.

## 11. Module layout

```
scribe/
  cli.py            # typer commands
  extract/          # epub.py, pdf.py, clean.py
  text/             # sentences.py, dialogue.py, speakers.py, cast.py
  tts/              # base.py, kokoro.py, piper.py, cache.py
  audio/            # assemble.py, encode.py
  bundle/           # writer.py, validate.py, models.py
  tests/
```

## 12. Dependencies

`ebooklib`, `beautifulsoup4`, `lxml`, `pysbd`, `spacy` (+ `en_core_web_sm`), `pyyaml`, `numpy`, `soundfile`, `typer`, `rich`, `pytest`; TTS: `kokoro` and/or `piper-tts` (both need `espeak-ng`); `ffmpeg` and `ffprobe` on PATH; optional `pyrubberband`, `pymupdf`.

## 13. Errors and logging

- Fail early on missing ffmpeg or models; print how to install.
- A bad chapter logs a warning and, in strict mode (`--strict`), aborts.
- Every run writes `scribe.log` with counts: chapters, sentences, dialogue lines, low-confidence lines, seconds of audio, real-time factor.

## 14. Testing

- Unit tests: quote splitting (curly, straight, multi-paragraph, unbalanced), abbreviations, speaker rules.
- Golden test: a small public-domain EPUB whose script JSON and timing structure are checked in.
- Bundle test: `validate` on every produced bundle in CI.