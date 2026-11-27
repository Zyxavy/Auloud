"""Audacity export: per-speaker WAV tracks + LOF project + labels.txt, zipped."""
from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path


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
            td = text_cache.setdefault(c["text"], json.loads((bundle / c["text"]).read_text(encoding="utf-8")))
            for b in td.get("blocks", []):
                for s in b.get("sentences", []):
                    speakers.add(s.get("speaker", "narrator"))
        speakers = sorted(speakers)

        labels_lines = []
        # per speaker wav via ffmpeg segment extraction per chapter
        for sp in speakers:
            # build concat list of segments across chapters using ffmpeg
            seg_files = []
            for c in rendered:
                audio = bundle / c["audio"]
                text_data = text_cache[c["text"]]
                for b in text_data.get("blocks", []):
                    for s in b.get("sentences", []):
                        if s.get("speaker") != sp:
                            continue
                        start = s.get("start_ms", 0) / 1000.0
                        end = s.get("end_ms", 0) / 1000.0
                        if end <= start:
                            continue
                        seg = td_path / f"{sp}_{c['index']}_{s['sid']}.wav"
                        proc = subprocess.run(
                            [ffmpeg, "-y", "-i", str(audio), "-ss", str(start), "-to", str(end), "-ar", "24000", "-ac", "1", str(seg)],
                            capture_output=True, check=False,
                        )
                        if proc.returncode != 0:
                            raise AudacityError(f"ffmpeg segment extraction failed (exit {proc.returncode}): {proc.stderr.decode('utf-8', 'replace').strip()[:200]}")
                        if seg.is_file():
                            seg_files.append(seg)
                        labels_lines.append(f"{start:.3f}\t{end:.3f}\t{sp}: {s.get('text','').strip()}")
            # concat segments into one wav
            out_wav = td_path / f"{sp}.wav"
            if seg_files:
                concat_file = td_path / f"{sp}_concat.txt"
                concat_file.write_text("\n".join(f"file '{p.resolve()}'" for p in seg_files) + "\n", encoding="utf-8")
                proc = subprocess.run(
                    [ffmpeg, "-y", "-f", "concat", "-safe", "0", "-i", str(concat_file), "-c", "pcm_s16le", str(out_wav)],
                    capture_output=True, check=False,
                )
                if proc.returncode != 0:
                    raise AudacityError(f"ffmpeg concat failed (exit {proc.returncode}): {proc.stderr.decode('utf-8', 'replace').strip()[:200]}")
            if not out_wav.is_file():
                # empty placeholder
                proc = subprocess.run([ffmpeg, "-y", "-f", "lavfi", "-i", "anullsrc=r=24000:cl=mono", "-t", "0.1", str(out_wav)], capture_output=True, check=False)
                if proc.returncode != 0:
                    raise AudacityError(f"ffmpeg placeholder failed (exit {proc.returncode}): {proc.stderr.decode('utf-8', 'replace').strip()[:200]}")

        # LOF project file
        lof = td_path / "project.lof"
        lof_lines = ["<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<project>"]
        for sp in speakers:
            lof_lines.append(f'  <track name="{sp}" file="{sp}.wav"/>')
        lof_lines.append("</project>")
        lof.write_text("\n".join(lof_lines) + "\n", encoding="utf-8")

        labels = td_path / "labels.txt"
        labels.write_text("\n".join(labels_lines) + "\n", encoding="utf-8")

        # zip
        with zipfile.ZipFile(resolved_output, "w", zipfile.ZIP_DEFLATED) as zf:
            for sp in speakers:
                wav = td_path / f"{sp}.wav"
                if wav.is_file():
                    zf.write(wav, f"{sp}.wav")
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