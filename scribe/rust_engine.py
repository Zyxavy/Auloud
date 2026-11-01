"""Rust orchestrator integration for scribe build.

Writes a build manifest, runs the Rust orchestrator, consumes its stdout
events and raises on failure. Results are informational only: the audio
the workers wrote lands in the sentence cache, which is what Python reads
back when it assembles the bundle.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable

from manifest import write_manifest


def find_orchestrator_binary() -> str | None:
    """Locate the scribe-orchestrator binary, or ``None``."""
    env = os.environ.get("SCRIBE_ORCHESTRATOR")
    if env and Path(env).is_file():
        return env
    # Cargo's workspace target dir lives at scribe-rs/target; a standalone
    # build of the package puts it in scribe-rs/scribe-orchestrator/target.
    package = Path(__file__).resolve().parent.parent / "scribe-rs" / "scribe-orchestrator"
    for target in (package.parent / "target", package / "target"):
        for profile in ("debug", "release"):
            candidate = target / profile / "scribe-orchestrator.exe"
            if candidate.is_file():
                return str(candidate)
    return shutil.which("scribe-orchestrator")


def run_rust_build(
    *,
    book_id: str,
    chapters: list[dict],
    models_dir: str,
    device: str,
    work_dir: Path | str,
    cache_dir: str,
    workers: int = 2,
    batch_size: int = 32,
    progress_listener: Callable[[dict], None] | None = None,
) -> None:
    """Fill the sentence cache through the Rust orchestrator.

    Raises :class:`RuntimeError` when the binary is missing, the orchestrator
    reports an error, or it exits non-zero. Progress events are forwarded to
    ``progress_listener`` as they arrive; non-JSON lines (logs) are ignored.
    """
    binary = find_orchestrator_binary()
    if binary is None:
        raise RuntimeError(
            "scribe-orchestrator binary not found; set SCRIBE_ORCHESTRATOR or build with cargo"
        )

    work_dir = Path(work_dir)
    manifest_path = write_manifest(
        work_dir / "build_manifest.json",
        book_id=book_id,
        chapters=chapters,
        models_dir=models_dir,
        device=device,
        cache_dir=cache_dir,
        workers=workers,
        batch_size=batch_size,
    )
    cmd = [
        binary,
        "--manifest", str(manifest_path),
        "--workers", str(workers),
        "--batch-size", str(batch_size),
        "--python", sys.executable,
        "--worker-dir", str(Path(__file__).resolve().parent),
        "--results", str(work_dir / "build_results.json"),
    ]

    error: str | None = None
    # stderr stays inherited: the orchestrator and its workers log there, and
    # a piped-but-unread stderr would block them once the pipe fills.
    with subprocess.Popen(cmd, stdout=subprocess.PIPE) as proc:
        for raw in proc.stdout or ():
            line = raw.decode("utf-8", "replace").strip()
            if not line:
                continue
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("type") == "error":
                # Keep draining stdout so the child cannot block on a full pipe.
                error = error or str(event.get("message", "orchestrator error"))
            elif progress_listener is not None:
                progress_listener(event)
        proc.wait()

    if error is not None:
        raise RuntimeError(error)
    if proc.returncode != 0:
        raise RuntimeError(f"orchestrator failed (exit {proc.returncode})")
