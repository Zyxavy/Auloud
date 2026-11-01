"""Build manifest writer for the Rust orchestrator.

Writes a JSON manifest describing a build so the Rust orchestrator can
spawn workers, schedule batches, and collect results without re-implementing
any text/audio logic.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def write_manifest(
    manifest_path: Path,
    *,
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
) -> Path:
    """Write a build manifest JSON file.

    chapters: list of dicts with keys: index, title, sentences (list of
        {sid, text, speaker, voice, speed, cache_key}).
    """
    manifest = {
        "v": 1,
        "book_id": book_id,
        "models_dir": models_dir,
        "device": device,
        "output_dir": output_dir,
        "cache_dir": cache_dir,
        "render_audio_dir": render_audio_dir,
        "render_text_dir": render_text_dir,
        "workers": workers,
        "batch_size": batch_size,
        "chapters": chapters,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest_path


def read_manifest(manifest_path: Path) -> dict:
    return json.loads(manifest_path.read_text(encoding="utf-8"))