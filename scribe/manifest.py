"""Build manifest writer for the Rust orchestrator.

Writes a JSON manifest describing the sentences to synthesise so the Rust
orchestrator can spawn workers and schedule batches without re-implementing
any text or audio logic. The workers write the same cache files Python
writes, so the handoff back to Python is the cache itself.
"""
from __future__ import annotations

import json
from pathlib import Path


def write_manifest(
    manifest_path: Path,
    *,
    book_id: str,
    chapters: list[dict],
    models_dir: str,
    device: str,
    cache_dir: str,
    workers: int = 2,
    batch_size: int = 32,
) -> Path:
    """Write a build manifest JSON file and return its path.

    chapters: list of dicts with keys ``index``, ``title`` and ``sentences``
    (a list of ``{sid, text, voice, speed}``).
    """
    manifest = {
        "v": 1,
        "book_id": book_id,
        "models_dir": models_dir,
        "device": device,
        "cache_dir": cache_dir,
        "workers": workers,
        "batch_size": batch_size,
        "chapters": chapters,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest_path
