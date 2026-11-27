# Auloud

Auloud turns books into multi-voice audiobooks and lets them play on Android with read-along text.

- Scribe builds the audiobook bundle on a PC.
- Player plays the bundle on Android.
- The bundle format is defined in [docs/03-BundleSpec.md](docs/03-BundleSpec.md).

## What is in this repo?

- [scribe/](scribe/) — Python CLI for drafting, rendering, validating, and exporting bundles.
- [player/](player/) — Android app for playback and read-along.
- [spec/](spec/) — shared bundle contract and fixtures.
- [docs/](docs/) — roadmap, design, test plan, and runbooks.
- [parity/](parity/) — comparison harness for Python vs. Rust behavior.

## Quick start

1. Install the dependencies for Scribe on Windows 11:
   - Python 3.11+
   - `uv`
   - `ffmpeg` and `ffprobe` on `PATH`
   - `espeak-ng` 1.52.0
   - Kokoro model files: `kokoro-v1.0.onnx` and `voices-v1.0.bin`
2. In the repo:

```powershell
cd scribe
uv sync
uv run scribe doctor
uv run scribe draft ..\MyBook.epub
uv run scribe build ..\MyBook.epub
uv run scribe validate bundles\<book-id>
```

3. Copy the bundle to the Android device and open it in Player.
4. For the browser UI:

```powershell
uv run scribe ui
```

## CLI commands and flags

The main commands are exposed through the `scribe` CLI.

| Command | Key flags | What it does |
| --- | --- | --- |
| `doctor` | `--models-dir` | Checks Python, ffmpeg/ffprobe, espeak-ng, TTS engine, model files, and dependencies. |
| `draft` | `--work-dir` | Parses a book and creates the work folder with script, cast, and report files. |
| `build` | `--work-dir`, `--out-dir`, `--models-dir`, `--strict`, `--no-progress`, `--engine`, `--chapters`, `--pages`, `--device`, `--render-speed`, `--plan`, `--events-jsonl`, `--stop-file`, `--force-chapters` | Renders chapters and writes the audiobook bundle. |
| `inspect` | `--speakers` | Prints bundle chapters, speakers, and sample sentences. |
| `validate` | none | Runs the bundle validation checks against the spec. |
| `voices` | `--sample`, `--out-dir`, `--models-dir`, `--speed`, `--voice`, `--engine` | Lists TTS voices or audition them. |
| `ui` | `--port`, `--workspace`, `--no-browser` | Starts the local web UI for the desktop workflow. |
| `version` | none | Prints the Scribe version. |
| Global | `--help`, `--install-completion`, `--show-completion` | Shows help or shell completion options. |

### Common build flags

- `--out-dir`: where the rendered bundle is written.
- `--models-dir`: directory containing the Kokoro model files.
- `--strict`: stop immediately on the first chapter error.
- `--no-progress`: disable the progress bar.
- `--engine`: choose `rust` or `python` orchestration.
- `--chapters`: render only selected chapters, such as `3-5,7`.
- `--pages`: render a page range from a PDF.
- `--device`: choose `auto`, `cpu`, or `cuda`.
- `--render-speed`: choose `slow`, `balanced`, or `fast`.
- `--plan`: preview work without rendering audio.
- `--force-chapters`: re-render selected chapters even if cached.

## Use with agents

Auloud works well with coding agents because the pipeline is simple and command-driven.

Recommended loop:

```powershell
uv run scribe doctor
uv run scribe draft MyBook.epub
uv run scribe build MyBook.epub
uv run scribe validate bundles\<book-id>
```

Use one clear objective per run: check setup, draft the book, build the bundle, then validate it. This keeps agent output easy to read, and it makes failures visible at the exact step that broke.

## Docs and more information

- [docs/01-PRD.md](docs/01-PRD.md) — product goals
- [docs/04-Roadmap.md](docs/04-Roadmap.md) — current roadmap
- [docs/05-ScribeDesign.md](docs/05-ScribeDesign.md) — Scribe architecture
- [docs/06-PlayerDesign.md](docs/06-PlayerDesign.md) — Player architecture
- [docs/ui-runbook.md](docs/ui-runbook.md) — first-run setup and troubleshooting
- [docs/08-Licenses.md](docs/08-Licenses.md) — licenses and model attribution
- [docs/DECISIONS.md](docs/DECISIONS.md) — design decisions

## Book and copyright

Use only books you are allowed to process. This repo ships code and test fixtures, not copyrighted books or generated audiobook copies of protected works.

## License

- Player `core`: Apache-2.0
- Player `full`: GPL-3.0
- Scribe: AGPL-3.0-or-later
- Voice models and packs may carry their own licenses; see [docs/08-Licenses.md](docs/08-Licenses.md).
