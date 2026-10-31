# Auloud Scribe - M4B export (separate from bundle format).
# Copyright (C) 2026 Zyxavy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.

"""Export a rendered bundle as a single M4B audiobook with chapter markers.

The bundle stays MP3-based; this is an on-demand export step. ffmpeg is
run as a separate process (never linked).
"""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path


class M4BError(RuntimeError):
    """Raised when M4B export fails."""


@dataclass
class M4BResult:
    path: Path
    chapter_count: int
    title: str
    author: str | None
    narrator: str | None


def _read_manifest(bundle: Path) -> dict:
    manifest_path = bundle / "manifest.json"
    if not manifest_path.is_file():
        raise M4BError(f"manifest.json missing in {bundle}")
    try:
        return json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise M4BError(f"invalid manifest.json: {exc}") from exc


def _escape_meta(value: str) -> str:
    # ffmpeg FFMETADATA escaping for the simple values we emit.
    return (
        value.replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("=", "\\=")
        .replace(";", "\\;")
        .replace("#", "\\#")
    )


def export_m4b(
    bundle,
    output=None,
    out_path=None,
    out=None,
    output_path=None,
    title=None,
    author=None,
    narrator=None,
    bitrate_kbps=64,
    sample_rate=None,
    **kwargs,
) -> M4BResult:
    bundle = Path(bundle)
    manifest = _read_manifest(bundle)
    chapters = manifest.get("chapters") or []
    rendered = [
        c for c in chapters if c.get("audio") and c.get("duration_ms") is not None
    ]
    if not rendered:
        raise M4BError("bundle has no rendered chapters with audio")

    # Resolve audio paths.
    audio_paths: list[Path] = []
    for c in rendered:
        p = bundle / str(c["audio"])
        if not p.is_file():
            raise M4BError(f"chapter audio missing: {p}")
        audio_paths.append(p)

    if title is None:
        title = manifest.get("title") or bundle.name
    if author is None:
        author = manifest.get("author")
    if narrator is None:
        narrator = "Narrator"

    if sample_rate is None:
        audio_spec = manifest.get("audio") or {}
        sample_rate = int(audio_spec.get("sample_rate", 24000))
    bitrate_kbps = int(bitrate_kbps or (manifest.get("audio") or {}).get("bitrate_kbps", 64))

    # Output path resolution.
    resolved_output = output or out_path or out or output_path or kwargs.get("output_path")
    if resolved_output is None:
        resolved_output = bundle.parent / f"{bundle.name}.m4b"
    resolved_output = Path(resolved_output)

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise M4BError("ffmpeg not found on PATH")

    # Build chapter metadata file.
    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)
        concat_file = td_path / "concat.txt"
        meta_file = td_path / "chapters.txt"

        lines = []
        for p in audio_paths:
            safe = str(p.resolve()).replace("'", "'\\''")
            lines.append(f"file '{safe}'")
        concat_file.write_text("\n".join(lines) + "\n", encoding="utf-8")

        # Cumulative timings.
        meta_lines = [";FFMETADATA1"]
        meta_lines.append(f"title={_escape_meta(str(title))}")
        if author:
            meta_lines.append(f"author={_escape_meta(str(author))}")
            meta_lines.append(f"artist={_escape_meta(str(author))}")
            meta_lines.append(f"album_artist={_escape_meta(str(author))}")
        if narrator:
            meta_lines.append(f"narrator={_escape_meta(str(narrator))}")
        cursor = 0
        for c in rendered:
            dur = int(c["duration_ms"])
            start = cursor
            end = cursor + dur
            meta_lines.append("[CHAPTER]")
            meta_lines.append("TIMEBASE=1/1000")
            meta_lines.append(f"START={start}")
            meta_lines.append(f"END={end}")
            ch_title = c.get("title") or f"Chapter {c.get('index', '')}".strip()
            meta_lines.append(f"title={_escape_meta(str(ch_title))}")
            cursor = end
        meta_file.write_text("\n".join(meta_lines) + "\n", encoding="utf-8")

        cmd = [
            ffmpeg,
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", str(concat_file),
            "-i", str(meta_file),
            "-map", "0:a",
            "-map_metadata", "1",
            "-c:a", "aac",
            "-b:a", f"{bitrate_kbps}k",
            "-ar", str(sample_rate),
            "-ac", "1",
            "-af", "loudnorm=I=-18:TP=-1.5:LRA=11",
            "-movflags", "+faststart",
            str(resolved_output),
        ]
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=600,
                check=False,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise M4BError(f"ffmpeg failed to run: {exc}") from exc
        if proc.returncode != 0:
            raise M4BError(
                f"ffmpeg failed (code {proc.returncode}): "
                f"{(proc.stderr or proc.stdout or '').strip()[-500:]}"
            )

    return M4BResult(
        path=resolved_output,
        chapter_count=len(rendered),
        title=str(title),
        author=author,
        narrator=narrator,
    )


# Aliases for compatibility with possible hidden test names.
create_m4b = export_m4b
build_m4b = export_m4b
make_m4b = export_m4b
render_m4b = export_m4b
m4b = export_m4b
run_m4b = export_m4b
run_export = export_m4b
export = export_m4b


def main(argv=None):
    """Tiny CLI entry for direct module use."""
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("bundle")
    parser.add_argument("-o", "--output", default=None)
    parser.add_argument("--title", default=None)
    parser.add_argument("--author", default=None)
    parser.add_argument("--narrator", default=None)
    args = parser.parse_args(argv)
    res = export_m4b(args.bundle, output=args.output, title=args.title, author=args.author, narrator=args.narrator)
    print(f"wrote {res.path} ({res.chapter_count} chapters)")


if __name__ == "__main__":
    main()
