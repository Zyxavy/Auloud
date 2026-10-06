# Auloud Scribe - turns ebooks into multi-voice audiobooks (PC tool).
# Copyright (C) 2026 Zyxavy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Auloud Scribe command line interface (thin typer wrapper, no logic).

Feature code lives in the library subpackages (``extract/``, ``text/``,
``tts/``, ``audio/``, ``bundle/``, ``draft.py``, ``build.py``); this module
only parses arguments, runs environment checks, and delegates. SW1 ships
``doctor`` (plus ``version``); ``draft`` arrived in SW5, ``build`` and
``inspect`` in SW9; MV0 adds ``voices`` and the ``doctor`` spaCy check.
"""

from __future__ import annotations

import importlib.metadata
import importlib.util
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import typer

# Library modules use flat top-level imports (``from bundle.models import``),
# which resolve in dev via ``pythonpath=["."]``. The installed console script
# has no CWD on sys.path, so add this file's own directory: it mirrors the
# flat tree (draft.py, extract/, text/, ...) both in the source tree and in
# the installed ``scribe/`` package. Harmless duplicate entry in dev.
sys.path.insert(0, str(Path(__file__).resolve().parent))

__version__ = "0.1.0"

app = typer.Typer(
    name="scribe",
    help="Auloud Scribe: turn ebooks into multi-voice audiobooks.",
    no_args_is_help=True,
)

MIN_PYTHON: tuple[int, int] = (3, 11)

FFMPEG_HELP = (
    "Install ffmpeg (provides both ffmpeg and ffprobe), then re-run "
    "`scribe doctor`:\n"
    "  winget install ffmpeg\n"
    "Then make sure `ffmpeg.exe` and `ffprobe.exe` are on PATH "
    "(a new terminal picks PATH up)."
)

ESPEAK_MSI_PATH = Path(r"C:\Program Files\eSpeak NG\espeak-ng.exe")
ESPEAK_HELP = (
    "Install espeak-ng 1.52.0 from "
    "https://github.com/espeak-ng/espeak-ng/releases "
    "(use the .msi, keep the default install path), then re-run "
    "`scribe doctor`."
)

ENGINE_HELP = (
    "The Kokoro ONNX runtime is wired up in SW6. To install it now:\n"
    "  uv pip install kokoro-onnx onnxruntime\n"
    "(`doctor` only probes; it never installs or downloads anything.)"
)

MODEL_FILES: tuple[str, ...] = ("kokoro-v1.0.onnx", "voices-v1.0.bin")
MODELS_HELP = (
    "Place the Kokoro model files in the models dir "
    "(SW0 used kokoro-v1.0.onnx + voices-v1.0.bin from "
    "https://github.com/thewh1teagle/kokoro-onnx/releases). "
    "`doctor` never downloads anything."
)

PYMUPDF_HELP = (
    "PyMuPDF is a pinned dependency (PDF text extraction): run `uv sync` "
    "in scribe/, then re-run `scribe doctor`."
)

PIPER_HELP = (
    "Piper is the optional second TTS engine (Slice 8 SW1): run `uv sync` "
    "in scribe/, then place <voice>.onnx + <voice>.onnx.json pairs in "
    "<models-dir>/piper/ (e.g. from https://huggingface.co/rhasspy/piper-voices). "
    "`doctor` never downloads anything."
)

UI_EXTRA_HINT = (
    "The UI needs the optional 'ui' extra: run `uv sync --extra ui` "
    "in scribe/, then re-run the command."
)

SPACY_MODEL_HELP = (
    "The spaCy English model (about 12 MB) is URL-pinned in "
    "pyproject.toml + uv.lock; run `uv sync` in scribe/ first, "
    "then re-run `scribe doctor`.\n"
    "Offline fallback only: `uv run python -m spacy download en_core_web_sm`\n"
    "(a plain download is wiped by the next `uv sync`)."
)

PASS = "PASS"
FAIL = "FAIL"
INFO = "INFO"


@dataclass
class CheckResult:
    """Outcome of one `doctor` check."""

    name: str
    status: str  # PASS | FAIL | INFO
    detail: str
    hint: str = ""


def _first_line(output: str) -> str:
    for line in output.splitlines():
        stripped = line.strip()
        if stripped:
            return stripped
    return ""


def _run_version(argv: list[str], timeout_s: float = 10.0) -> str | None:
    """Run ``argv`` and return the first line of combined output, or None.

    External tools are only ever executed as subprocesses, never linked.
    """
    try:
        completed = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=timeout_s,
            check=False,
        )
    except (FileNotFoundError, OSError):
        return None
    except subprocess.TimeoutExpired:
        return None
    combined = (completed.stdout or "") + "\n" + (completed.stderr or "")
    return _first_line(combined)


def check_python(min_version: tuple[int, int] = MIN_PYTHON) -> CheckResult:
    """Check the running interpreter meets the minimum version."""
    current = (sys.version_info.major, sys.version_info.minor, sys.version_info.micro)
    detail = (
        f"{current[0]}.{current[1]}.{current[2]} (requires >={min_version[0]}.{min_version[1]})"
    )
    if (current[0], current[1]) >= (min_version[0], min_version[1]):
        return CheckResult(name="python", status=PASS, detail=detail)
    return CheckResult(
        name="python",
        status=FAIL,
        detail=detail,
        hint="Install Python 3.11+ from https://www.python.org/downloads/ "
        "(tick 'Add python.exe to PATH'), or let `uv` provision it: "
        "`uv python install 3.12`.",
    )


def check_tool(name: str, version_args: list[str], help_text: str) -> CheckResult:
    """Check a ``<name>`` CLI tool is on PATH and reports a version."""
    found = shutil.which(name)
    if found is None:
        return CheckResult(name=name, status=FAIL, detail="not found on PATH", hint=help_text)
    version = _run_version([found, *version_args])
    if not version:
        return CheckResult(
            name=name,
            status=FAIL,
            detail=f"found at {found} but did not respond",
            hint="It may be broken; reinstall it, then re-run `scribe doctor`.\n" + help_text,
        )
    short = version if len(version) <= 80 else version[:77] + "..."
    return CheckResult(name=name, status=PASS, detail=f"{short} [{found}]")


def check_espeak_ng(msi_path: Path = ESPEAK_MSI_PATH) -> CheckResult:
    """Check espeak-ng via PATH or the default .msi install location."""
    on_path = shutil.which("espeak-ng")
    if on_path is not None:
        version = _run_version([on_path, "--version"])
        if not version:
            return CheckResult(
                name="espeak-ng",
                status=FAIL,
                detail=f"found at {on_path} but did not respond",
                hint="It may be broken; reinstall it, then re-run `scribe doctor`.\n" + ESPEAK_HELP,
            )
        return CheckResult(name="espeak-ng", status=PASS, detail=f"{version} [{on_path}]")
    if msi_path.is_file():
        version = _run_version([str(msi_path), "--version"])
        if not version:
            return CheckResult(
                name="espeak-ng",
                status=FAIL,
                detail=f"found at {msi_path} but did not respond",
                hint="It may be broken; reinstall it, then re-run `scribe doctor`.\n" + ESPEAK_HELP,
            )
        return CheckResult(name="espeak-ng", status=PASS, detail=version)
    return CheckResult(
        name="espeak-ng",
        status=FAIL,
        detail="not on PATH and not at the default .msi path",
        hint=ESPEAK_HELP,
    )


def check_engine() -> CheckResult:
    """Check the chosen TTS runtime (Kokoro via kokoro-onnx, D-023).

    Probes importability only; the engine dependency itself lands in SW6,
    so a missing engine is a graceful FAIL with install help, never an error.
    """
    if importlib.util.find_spec("kokoro_onnx") is None:
        return CheckResult(
            name="tts-engine",
            status=FAIL,
            detail="kokoro-onnx not importable (expected before SW6)",
            hint=ENGINE_HELP,
        )
    try:
        dist_version = importlib.metadata.version("kokoro-onnx")
    except importlib.metadata.PackageNotFoundError:
        dist_version = "unknown version"
    return CheckResult(
        name="tts-engine",
        status=PASS,
        detail=f"kokoro-onnx {dist_version} importable",
    )


def check_models(models_dir: Path) -> CheckResult:
    """Check the Kokoro model files exist under ``models_dir`` (no download)."""
    missing = [name for name in MODEL_FILES if not (models_dir / name).is_file()]
    if not missing:
        return CheckResult(
            name="models",
            status=PASS,
            detail=f"{', '.join(MODEL_FILES)} present in {models_dir}",
        )
    return CheckResult(
        name="models",
        status=FAIL,
        detail=f"missing in {models_dir}: {', '.join(missing)}",
        hint=MODELS_HELP,
    )


def check_piper_models(models_dir: Path) -> CheckResult:
    """Check Piper voices exist under ``models_dir/piper`` (SW1, optional).

    Piper is the second engine, so absence is INFO (never FAIL): the Kokoro
    path is unaffected. A present-but-unreadable runtime is FAIL with the
    sync hint; a present runtime with no voices is INFO naming the dir.
    """
    if importlib.util.find_spec("piper") is None:
        return CheckResult(
            name="piper-models",
            status=FAIL,
            detail="piper-tts not importable (pinned dependency, SW1)",
            hint=PIPER_HELP,
        )
    from tts.piper import PIPER_DIR_NAME, discover_voices

    voices = discover_voices(models_dir)
    piper_dir = models_dir / PIPER_DIR_NAME
    if not voices:
        return CheckResult(
            name="piper-models",
            status=INFO,
            detail=f"no <voice>.onnx + <voice>.onnx.json pairs in {piper_dir} (optional)",
            hint=PIPER_HELP,
        )
    return CheckResult(
        name="piper-models",
        status=PASS,
        detail=f"{len(voices)} voice(s) in {piper_dir}: {', '.join(sorted(voices))}",
    )


def check_spacy() -> CheckResult:
    """Check spaCy plus the en_core_web_sm model (MV0; MV3 attributes with it).

    Reports present + versions, proven by loading the pipeline and parsing
    one short sentence. A missing model is a graceful FAIL with the
    download command, never an error.
    """
    try:
        from text.nlp import MODEL_NAME, load_model, model_version
    except ImportError as exc:
        return CheckResult(
            name="spacy",
            status=FAIL,
            detail=f"scribe install broken: cannot import text.nlp ({exc})",
            hint="The installed scribe wheel is missing text.nlp; reinstall it with "
            "`uv sync --reinstall-package auloud-scribe` in scribe/, "
            "then re-run `scribe doctor`.",
        )

    try:
        import spacy
    except ImportError:
        return CheckResult(
            name="spacy",
            status=FAIL,
            detail="spacy not importable",
            hint="spacy is a pinned dependency: run `uv sync` in scribe/, "
            "then re-run `scribe doctor`.",
        )
    spacy_ver = spacy.__version__
    try:
        nlp = load_model()
        doc = nlp("The quick brown fox jumps.")
        if len(doc) == 0:
            raise ValueError("model parsed zero tokens")
        model_ver = model_version() or "unknown version"
    except (OSError, ImportError, ValueError) as exc:
        return CheckResult(
            name="spacy",
            status=FAIL,
            detail=f"spacy {spacy_ver} present but model {MODEL_NAME} missing/unreadable ({exc})",
            hint=SPACY_MODEL_HELP,
        )
    return CheckResult(
        name="spacy",
        status=PASS,
        detail=f"spacy {spacy_ver} + {MODEL_NAME} {model_ver} (parse ok)",
    )


def check_pymupdf() -> CheckResult:
    """Check PyMuPDF imports and can open a document (CP5 PDF extraction).

    Probes importability plus a real in-memory open, mirroring the spaCy
    check's prove-it-parses ethos. Missing is a graceful FAIL with the
    sync hint, never an error.
    """
    try:
        import pymupdf
    except ImportError:
        return CheckResult(
            name="pymupdf",
            status=FAIL,
            detail="pymupdf not importable",
            hint=PYMUPDF_HELP,
        )
    version = getattr(pymupdf, "__version__", None) or "unknown version"
    try:
        doc = pymupdf.open()
        doc.close()
    except Exception as exc:
        return CheckResult(
            name="pymupdf",
            status=FAIL,
            detail=f"pymupdf {version} present but cannot open a document ({exc})",
            hint="The install may be broken; reinstall it with "
            "`uv sync --reinstall-package pymupdf` in scribe/, "
            "then re-run `scribe doctor`.",
        )
    return CheckResult(
        name="pymupdf",
        status=PASS,
        detail=f"pymupdf {version} (open ok)",
    )


def check_ui() -> CheckResult:
    """Check the UI extra (FastAPI + uvicorn) imports (Slice 6 UI2).

    Probes importability plus versions, mirroring the engine check: a
    missing extra is a graceful FAIL with the sync hint, never an error.
    """
    if importlib.util.find_spec("fastapi") is None:
        return CheckResult(
            name="ui",
            status=FAIL,
            detail="fastapi not importable (ui extra not installed)",
            hint=UI_EXTRA_HINT,
        )
    if importlib.util.find_spec("uvicorn") is None:
        return CheckResult(
            name="ui",
            status=FAIL,
            detail="uvicorn not importable (ui extra not installed)",
            hint=UI_EXTRA_HINT,
        )
    if importlib.util.find_spec("ui.app") is None:
        return CheckResult(
            name="ui",
            status=FAIL,
            detail="scribe install broken: ui.app missing",
            hint="The installed scribe wheel is missing the ui package; "
            "reinstall it with `uv sync --reinstall-package auloud-scribe` "
            "in scribe/, then re-run `scribe doctor`.",
        )
    try:
        fastapi_ver = importlib.metadata.version("fastapi")
    except importlib.metadata.PackageNotFoundError:
        fastapi_ver = "unknown version"
    try:
        uvicorn_ver = importlib.metadata.version("uvicorn")
    except importlib.metadata.PackageNotFoundError:
        uvicorn_ver = "unknown version"
    return CheckResult(
        name="ui",
        status=PASS,
        detail=f"fastapi {fastapi_ver} + uvicorn {uvicorn_ver} (import ok)",
    )


def check_gpu() -> CheckResult:
    """Report NVIDIA/CUDA presence. Informational: CPU-only builds work (SW0)."""
    nvidia_smi = shutil.which("nvidia-smi")
    if nvidia_smi is None:
        return CheckResult(
            name="gpu",
            status=INFO,
            detail="no NVIDIA GPU detected (nvidia-smi not found); "
            "CPU-only build works - SW0 measured RTF 1.38 on CPU",
        )
    line = _run_version([nvidia_smi, "-L"])
    if not line:
        return CheckResult(
            name="gpu",
            status=INFO,
            detail="nvidia-smi present but unreadable; assuming CPU-only build",
        )
    short = line if len(line) <= 100 else line[:97] + "..."
    return CheckResult(name="gpu", status=PASS, detail=short)


def run_checks(models_dir: Path) -> list[CheckResult]:
    """Run every `doctor` check in display order."""
    return [
        check_python(),
        check_tool("ffmpeg", ["-version"], FFMPEG_HELP),
        check_tool("ffprobe", ["-version"], FFMPEG_HELP),
        check_espeak_ng(),
        check_engine(),
        check_models(models_dir),
        check_piper_models(models_dir),
        check_spacy(),
        check_pymupdf(),
        check_ui(),
        check_gpu(),
    ]


def format_table(results: list[CheckResult]) -> str:
    """Render results as a plain-text table (no extra deps beyond typer)."""
    name_w = max([len("CHECK")] + [len(r.name) for r in results])
    status_w = max([len("STATUS")] + [len(r.status) for r in results])
    header = f"{'CHECK':<{name_w}}  {'STATUS':<{status_w}}  DETAIL"
    lines = [header, "-" * len(header)]
    for result in results:
        lines.append(f"{result.name:<{name_w}}  {result.status:<{status_w}}  {result.detail}")
    return "\n".join(lines)


@app.command()
def doctor(
    models_dir: Path = typer.Option(
        Path("models"),
        "--models-dir",
        help="Directory holding kokoro-v1.0.onnx + voices-v1.0.bin.",
    ),
) -> None:
    """Check Python, ffmpeg/ffprobe, espeak-ng, TTS engine, models, spaCy,
    PyMuPDF, UI extra, GPU."""
    results = run_checks(models_dir)
    typer.echo(format_table(results))
    failures = [r for r in results if r.status == FAIL]
    if not failures:
        typer.echo("\nAll required checks passed.")
        return
    typer.echo("\nTo fix:")
    for result in failures:
        typer.echo(f"\n[{result.name}] {result.detail}\n{result.hint}")
    raise typer.Exit(code=1)


@app.command()
def draft(
    book: Path = typer.Argument(
        ...,
        exists=True,
        dir_okay=False,
        readable=True,
        help="EPUB or PDF file to draft from.",
    ),
    work_dir: Path = typer.Option(
        Path(".scribe"),
        "--work-dir",
        help="Work folder root; writes <work-dir>/<book-id>/ under it.",
    ),
) -> None:
    """Parse, split, and write the work folder (script, cast, report)."""
    from draft import DraftError, run_draft

    try:
        result = run_draft(book, work_root=work_dir)
    except DraftError as exc:
        typer.echo(f"draft failed: {exc}", err=True)
        raise typer.Exit(code=1)
    typer.echo(f"book id: {result.book_id}")
    typer.echo(f"title: {result.title}")
    typer.echo(f"chapters: {len(result.chapters)}  sentences: {result.total_sentences}")
    typer.echo(f"words: {result.total_words}  drops: {len(result.drops)}")
    typer.echo(f"work folder: {result.work_dir}")


@app.command()
def build(
    book: Path = typer.Argument(
        ...,
        exists=True,
        dir_okay=False,
        readable=True,
        help="EPUB or PDF file to build from.",
    ),
    work_dir: Path = typer.Option(
        Path(".scribe"),
        "--work-dir",
        help="Work folder root; reads/writes <work-dir>/<book-id>/.",
    ),
    out_dir: Path | None = typer.Option(
        None,
        "--out-dir",
        help="Bundle output dir (default bundles/<book-id>, "
        "bundles/<range-id> for --chapters/--pages).",
    ),
    models_dir: Path = typer.Option(
        Path("models"),
        "--models-dir",
        help="Directory holding kokoro-v1.0.onnx + voices-v1.0.bin.",
    ),
    strict: bool = typer.Option(
        False,
        "--strict",
        help="Abort the whole build on the first chapter error.",
    ),
    no_progress: bool = typer.Option(
        False,
        "--no-progress",
        help="Disable the rich progress bar.",
    ),
    chapters: str | None = typer.Option(
        None,
        "--chapters",
        help="Chapter selection 1-based (e.g. --chapters 3-5,7); "
        "bundle lists only rendered chapters consecutively.",
    ),
    pages: str | None = typer.Option(
        None,
        "--pages",
        help="PDF page range (e.g. --pages 40-90); resolves to covering "
        "chapters via sentence page provenance (whole chapters render).",
    ),
    device: str = typer.Option(
        "auto",
        "--device",
        help="TTS device: auto (CUDA when onnxruntime reports it, else CPU), "
        "cpu, or cuda.",
    ),
    plan: bool = typer.Option(
        False,
        "--plan",
        help="Dry run: print cached vs to-render counts plus a time "
        "estimate from recent RTF; render no audio.",
    ),
    events_jsonl: Path | None = typer.Option(
        None,
        "--events-jsonl",
        help="Append progress events as JSON lines "
        "({event, chapter, sid, cached, rendered, audio_ms, wall_s}) "
        "for detached-job tailing.",
    ),
    stop_file: Path | None = typer.Option(
        None,
        "--stop-file",
        help="Cross-process stop sentinel (UI4 pause): when the file exists, "
        "the build stops at the next sentence boundary (partial cleaned).",
    ),
) -> None:
    """Render audio chapter by chapter (resumable) and write the bundle."""
    from build import BuildError, format_plan, format_summary, plan_build, run_build
    from bundle.writer import BundleWriteError
    from draft import DraftError

    if plan:
        try:
            preflight = plan_build(
                book,
                work_root=work_dir,
                chapters=chapters,
                pages=pages,
                models_dir=models_dir,
                device=device,
            )
        except (BuildError, DraftError, BundleWriteError, ValueError) as exc:
            typer.echo(f"build failed: {exc}", err=True)
            raise typer.Exit(code=1)
        typer.echo(format_plan(preflight))
        return
    jsonl_writer = None
    try:
        if events_jsonl is not None:
            from progress import JsonlProgressWriter

            jsonl_writer = JsonlProgressWriter(events_jsonl)
        result = run_build(
            book,
            work_root=work_dir,
            out_dir=out_dir,
            models_dir=models_dir,
            strict=strict,
            show_progress=not no_progress,
            chapters=chapters,
            pages=pages,
            device=device,
            progress_listener=jsonl_writer,
            stop_file=stop_file,
        )
    except (BuildError, DraftError, BundleWriteError, ValueError) as exc:
        typer.echo(f"build failed: {exc}", err=True)
        raise typer.Exit(code=1)
    finally:
        if jsonl_writer is not None:
            try:
                jsonl_writer.close()
            except Exception:
                pass
    typer.echo(f"book id: {result.book_id}")
    typer.echo(f"title: {result.title}")
    if result.page_resolution:
        typer.echo(result.page_resolution)
    typer.echo(format_summary(result))


@app.command()
def inspect(
    bundle: Path = typer.Argument(
        ...,
        exists=True,
        file_okay=False,
        readable=True,
        help="Bundle directory to inspect.",
    ),
    speakers: bool = typer.Option(
        False,
        "--speakers",
        help="Per-speaker lines and spoken seconds (from timings), "
        "with low-confidence counts where present.",
    ),
) -> None:
    """Print chapters, speakers and sample sentences of a bundle."""
    from bundle.inspect import InspectError, format_inspect, inspect_bundle

    try:
        result = inspect_bundle(bundle)
    except InspectError as exc:
        typer.echo(f"inspect failed: {exc}", err=True)
        raise typer.Exit(code=1)
    typer.echo(format_inspect(result, speakers_detail=speakers))


@app.command()
def validate(
    bundle: Path = typer.Argument(
        ...,
        exists=True,
        file_okay=False,
        readable=True,
        help="Bundle directory to validate.",
    ),
) -> None:
    """Run the spec's checks over a bundle (fails loudly, no traceback)."""
    from bundle.validate import validate_bundle

    try:
        result = validate_bundle(bundle)
    except Exception as exc:  # noqa: BLE001 - validate must never traceback
        typer.echo(f"validate failed: {exc}", err=True)
        raise typer.Exit(code=1)
    if result.ok:
        typer.echo(f"valid: {bundle}")
        return
    for error in result.errors:
        typer.echo(f"error: {error}", err=True)
    raise typer.Exit(code=1)


@app.command()
def voices(
    sample: bool = typer.Option(
        False,
        "--sample",
        help="Render one WAV per voice into --out-dir (plus a voices.txt list).",
    ),
    out_dir: Path = typer.Option(
        Path("logs/voice-samples"),
        "--out-dir",
        help="Folder for audition WAVs (under logs/, never committed).",
    ),
    models_dir: Path = typer.Option(
        Path("models"),
        "--models-dir",
        help="Kokoro pair dir (piper/ pairs inside it for --engine piper).",
    ),
    speed: float = typer.Option(
        1.0,
        "--speed",
        help="Speech rate for the samples (the palette compares timbre).",
    ),
    voice: list[str] | None = typer.Option(
        None,
        "--voice",
        help="Render only this voice (repeatable for a subset; default all).",
    ),
    engine: str = typer.Option(
        "kokoro",
        "--engine",
        help="TTS engine to list or sample (kokoro or piper).",
    ),
) -> None:
    """List engine voices, or audition them all with --sample (MV0, SW3)."""
    from build import BuildError, create_engine
    from tts.voices import SAMPLE_TEXT, list_voices, sample_voices

    if speed <= 0:
        typer.echo(f"voices failed: --speed must be > 0 (got {speed})", err=True)
        raise typer.Exit(code=1)
    normalized_engine = str(engine or "").strip().lower()
    if normalized_engine not in ("kokoro", "piper"):
        typer.echo(
            f"voices failed: unknown engine {engine!r} (expected kokoro or piper)",
            err=True,
        )
        raise typer.Exit(code=1)
    try:
        if normalized_engine == "piper":
            from tts.piper import PiperEngine

            tts_engine = PiperEngine(models_dir)
        else:
            tts_engine = create_engine(models_dir)
    except (BuildError, ImportError, FileNotFoundError, OSError, ValueError) as exc:
        typer.echo(f"voices failed: {exc}", err=True)
        raise typer.Exit(code=1)
    wanted = [str(v) for v in (voice or []) if str(v).strip()] or None
    if not sample:
        names = list_voices(tts_engine)
        if wanted is not None:
            unknown = [v for v in wanted if v not in names]
            if unknown:
                typer.echo(f"voices failed: unknown voice(s): {', '.join(unknown)}.", err=True)
                raise typer.Exit(code=1)
            names = [v for v in names if v in set(wanted)]
        for name in names:
            typer.echo(name)
        return
    try:
        result = sample_voices(tts_engine, out_dir, text=SAMPLE_TEXT, speed=speed, voices=wanted)
    except ValueError as exc:
        typer.echo(f"voices failed: {exc}", err=True)
        raise typer.Exit(code=1)
    for name in result.voices:
        typer.echo(name)
    typer.echo(f"wrote {len(result.files)} WAVs to {result.out_dir}")


@app.command()
def export_ingest_fixtures(
    fixtures_dir: Path | None = typer.Option(
        None,
        "--fixtures-dir",
        help="spec/fixtures dir (default: <repo-root>/spec/fixtures).",
    ),
) -> None:
    """Export shared Slice 9 ingestion test data (dev only, IN2).

    Writes ``spec/fixtures/dialogue-cases.json`` plus
    ``spec/fixtures/ingest-parity/`` from the real pipeline. Deterministic:
    re-runs are byte-identical. Library logic lives in ``export_ingest``.
    """
    from export_ingest import ExportError, export_all

    try:
        written = export_all(fixtures_dir)
    except ExportError as exc:
        typer.echo(f"export-ingest-fixtures failed: {exc}", err=True)
        raise typer.Exit(code=1)
    for path in written:
        typer.echo(f"wrote {path}")


@app.command()
def export_render_fixtures(
    fixtures_dir: Path | None = typer.Option(
        None,
        "--fixtures-dir",
        help="spec/fixtures dir (default: <repo-root>/spec/fixtures).",
    ),
) -> None:
    """Generate the Slice 10 AAC/partial fixtures (dev only, RN1).

    Writes ``spec/fixtures/rendered-aac-golden/`` plus
    ``spec/fixtures/partial-aac-golden/`` with ffmpeg sine-tone M4A audio.
    Deterministic: re-runs are byte-identical (the export encodes twice
    and refuses on any difference). Library logic lives in
    ``export_render``.
    """
    from export_render import ExportRenderError, export_all

    try:
        written = export_all(fixtures_dir)
    except ExportRenderError as exc:
        typer.echo(f"export-render-fixtures failed: {exc}", err=True)
        raise typer.Exit(code=1)
    for path in written:
        typer.echo(f"wrote {path}")


@app.command()
def version() -> None:
    """Print the Scribe version."""
    typer.echo(__version__)


@app.command()
def ui(
    port: int = typer.Option(
        8137,
        "--port",
        help="Preferred port; the next free port is used when busy.",
    ),
    workspace: Path = typer.Option(
        Path("."),
        "--workspace",
        help="Workspace root; all file access stays inside it.",
    ),
    no_browser: bool = typer.Option(
        False,
        "--no-browser",
        help="Print the URL without opening a browser.",
    ),
) -> None:
    """Start the localhost web UI (thin operator over the library).

    Binds 127.0.0.1 only (there is deliberately no --host flag); FastAPI
    and uvicorn lazy-import here so the plain CLI install never needs the
    optional ``ui`` extra.
    """
    if (
        importlib.util.find_spec("fastapi") is None
        or importlib.util.find_spec("uvicorn") is None
    ):
        typer.echo(f"ui failed: the 'ui' extra is not installed.\n{UI_EXTRA_HINT}", err=True)
        raise typer.Exit(code=1)
    try:
        from ui.server import run_server
    except ImportError as exc:
        typer.echo(
            f"ui failed: scribe install broken: cannot import ui.server ({exc}); "
            "reinstall it with `uv sync --reinstall-package auloud-scribe` "
            "in scribe/, then re-run `scribe ui`.",
            err=True,
        )
        raise typer.Exit(code=1)
    try:
        run_server(workspace, port=port, open_browser=not no_browser)
    except ValueError as exc:
        typer.echo(f"ui failed: {exc}", err=True)
        raise typer.Exit(code=1)


if __name__ == "__main__":
    app()
