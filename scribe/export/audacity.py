"""Audacity export: per-speaker WAV tracks + LOF project + labels.txt, zipped."""
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from xml.sax.saxutils import escape

import numpy as np
import soundfile as sf


class AudacityError(RuntimeError):
    pass


@dataclass
class AudacityResult:
    path: Path


def _read_manifest(bundle: Path) -> dict:
    p = bundle / "manifest.json"
    if not p.is_file():
        raise AudacityError(f"manifest.json missing in {bundle}")
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise AudacityError(f"invalid manifest: {exc}") from exc


SAMPLE_RATE = 24000


def _attr(value: str) -> str:
    return escape(value, {'"': "&quot;"})


def _write_silence(w: sf.SoundFile, frames: int) -> None:
    while frames > 0:
        n = min(frames, SAMPLE_RATE)
        w.write(np.zeros(n, dtype=np.float32))
        frames -= n


def export_audacity(bundle, output=None, out_path=None, out=None, output_path=None, **kwargs) -> AudacityResult:
    bundle = Path(bundle)
    manifest = _read_manifest(bundle)
    chapters = manifest.get("chapters") or []
    rendered = [c for c in chapters if c.get("audio") and c.get("text")]
    if not rendered:
        raise AudacityError("bundle has no rendered chapters")

    resolved_output = output or out_path or out or output_path or kwargs.get("output_path")
    if resolved_output is None:
        resolved_output = bundle.parent / f"{bundle.name}.audacity.zip"
    resolved_output = Path(resolved_output)

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise AudacityError("ffmpeg not found")

    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)
        # collect speakers and cache text JSON reads (avoid O(n²) file reads)
        text_cache: dict[str, dict] = {}
        speakers = set()
        for c in rendered:
            data = text_cache.setdefault(c["text"], json.loads((bundle / c["text"]).read_text(encoding="utf-8")))
            for b in data.get("blocks", []):
                for s in b.get("sentences", []):
                    speakers.add(s.get("speaker", "narrator"))
        speakers = sorted(speakers)
        # tracks are named by index so a speaker name never reaches a file name
        wav_names = {sp: f"spk{i}.wav" for i, sp in enumerate(speakers, 1)}
        writers: dict[str, sf.SoundFile] = {}
        cursor = {sp: 0 for sp in speakers}  # frames written so far per track
        labels_lines = []
        offset = 0  # frames of all prior chapters: the project timeline
        try:
            for sp in speakers:
                writers[sp] = sf.SoundFile(td_path / wav_names[sp], "w", SAMPLE_RATE, 1, "PCM_16")
            for n, c in enumerate(rendered):
                # one ffmpeg decode per chapter; segments are sliced from the decoded WAV
                decoded = td_path / f"chapter_{n}.wav"
                proc = subprocess.run(
                    [ffmpeg, "-y", "-i", str(bundle / c["audio"]), "-ar", str(SAMPLE_RATE), "-ac", "1", "-c:a", "pcm_s16le", str(decoded)],
                    capture_output=True, check=False,
                )
                if proc.returncode != 0:
                    raise AudacityError(f"ffmpeg decode failed (exit {proc.returncode}): {proc.stderr.decode('utf-8', 'replace').strip()[:200]}")
                with sf.SoundFile(decoded) as src:
                    length = len(src)
                    for b in text_cache[c["text"]].get("blocks", []):
                        for s in b.get("sentences", []):
                            if s.get("end_ms", 0) <= s.get("start_ms", 0):
                                continue
                            sp = s.get("speaker", "narrator")
                            start = min(round(s.get("start_ms", 0) * SAMPLE_RATE / 1000), length)
                            end = min(round(s.get("end_ms", 0) * SAMPLE_RATE / 1000), length)
                            at = offset + start
                            label = " ".join(f"{sp}: {s.get('text', '')}".split())  # tabs and newlines would break labels.txt
                            labels_lines.append(f"{at / SAMPLE_RATE:.3f}\t{(offset + end) / SAMPLE_RATE:.3f}\t{label}")
                            if end > start:
                                src.seek(start)
                                _write_silence(writers[sp], at - cursor[sp])
                                clip = src.read(end - start, dtype="float32")
                                writers[sp].write(clip)
                                cursor[sp] = max(cursor[sp], at) + len(clip)
                offset += length
                decoded.unlink()
            # pad every track to the full project length so labels line up across tracks
            for sp in speakers:
                _write_silence(writers[sp], offset - cursor[sp])
        finally:
            for w in writers.values():
                w.close()

        # LOF project file
        lof = td_path / "project.lof"
        lof_lines = ['<?xml version="1.0" encoding="UTF-8"?>', "<project>"]
        for sp in speakers:
            lof_lines.append(f'  <track name="{_attr(sp)}" file="{wav_names[sp]}"/>')
        lof_lines.append("</project>")
        lof.write_text("\n".join(lof_lines) + "\n", encoding="utf-8")

        labels = td_path / "labels.txt"
        labels.write_text("\n".join(labels_lines) + "\n", encoding="utf-8")

        # zip
        with zipfile.ZipFile(resolved_output, "w", zipfile.ZIP_DEFLATED) as zf:
            for sp in speakers:
                zf.write(td_path / wav_names[sp], wav_names[sp])
            zf.write(lof, "project.lof")
            zf.write(labels, "labels.txt")

    return AudacityResult(path=resolved_output)


# aliases
export = export_audacity
run = export_audacity
run_export = export_audacity
create = export_audacity
build = export_audacity
main_export = export_audacity

def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle")
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args(argv)
    res = export_audacity(args.bundle, output=args.output)
    print(f"wrote {res.path}")

if __name__ == "__main__":
    main()