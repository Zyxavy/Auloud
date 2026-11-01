"""Rust orchestrator integration for scribe build.

Writes a build manifest, spawns the Rust orchestrator binary, consumes
progress events on stdout, and reads back results for Python-side assembly.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable

from manifest import write_manifest


def find_orchestrator_binary() -> str | None:
    """Locate the scribe-orchestrator binary."""
    env = os.environ.get("SCRIBE_ORCHESTRATOR")
    if env and Path(env).is_file():
        return env
    # Packaged path (in scribe-rs workspace).
    candidate = Path(__file__).resolve().parent.parent / "scribe-rs" / "scribe-orchestrator" / "target" / "debug" / "scribe-orchestrator.exe"
    if candidate.is_file():
        return str(candidate)
    # Release build.
    candidate = Path(__file__).resolve().parent.parent / "scribe-rs" / "scribe-orchestrator" / "target" / "release" / "scribe-orchestrator.exe"
    if candidate.is_file():
        return str(candidate)
    return shutil.which("scribe-orchestrator")


def run_rust_build(
    book_id: str,
    chapters: list[dict],
    models_dir: str,
    device: str,
    output_dir: str,
    cache_dir: str,
    render_audio_dir: str,
    render_text_dir: str,
    workers: int = 2,
    batch_size: int = 32,
    progress_listener: Callable[[dict], None] | None = None,
) -> dict:
    """Run a build via the Rust orchestrator. Returns the results dict."""
    binary = find_orchestrator_binary()
    if binary is None:
        raise RuntimeError("scribe-orchestrator binary not found; set SCRIBE_ORCHESTRATOR or build with cargo")

    work_dir = Path(output_dir).parent
    manifest_path = work_dir / "build_manifest.json"
    write_manifest(
        manifest_path,
        book_id=book_id,
        chapters=chapters,
        models_dir=models_dir,
        device=device,
        output_dir=output_dir,
        cache_dir=cache_dir,
        render_audio_dir=render_audio_dir,
        render_text_dir=render_text_dir,
        workers=workers,
        batch_size=batch_size,
    )

    results_path = work_dir / "build_results.json"
    cmd = [
        binary,
        "--manifest", str(manifest_path),
        "--workers", str(workers),
        "--batch-size", str(batch_size),
        "--python", sys.executable,
        "--results", str(results_path),
    ]

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    # Consume progress events on stdout.
    if proc.stdout:
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            try:
                evt = json.loads(line)
            except json.JSONDecodeError:
                continue
            if progress_listener is not None:
                progress_listener(evt)

    proc.wait()
    if proc.returncode != 0:
        stderr = proc.stderr.read() if proc.stderr else ""
        raise RuntimeError(f"orchestrator failed (exit {proc.returncode}): {stderr[:500]}")

    if results_path.is_file():
        return json.loads(results_path.read_text(encoding="utf-8"))
    return {}