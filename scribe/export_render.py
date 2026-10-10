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

"""Deterministic device-rendered AAC fixtures for Slice 10 RN1.

Two artifacts, both written by :func:`export_all` and both consumed by
Scribe's own suite (``tests/test_render_aac_golden.py``) and the Player
(``RenderedAacGoldenTest``):

- ``spec/fixtures/rendered-aac-golden/``: spec 2.0 ``complete`` book, both
  chapters AAC-LC mono 24 kHz about 64 kbps in M4A, per-chapter
  ``render_fingerprint``, manifest ``gain_db`` plus ``encoder_offset_ms``.
- ``spec/fixtures/partial-aac-golden/``: spec 2.0 ``partial`` book, first
  chapter rendered M4A with fingerprint, second chapter unrendered.

The audio stands in for tablet output: pure sine tones from ffmpeg's
``lavfi`` source (no TTS model, no network), encoded with the desktop
ffmpeg AAC encoder at the spec rate. Sentence timings split each chapter
evenly; the texts are original tiny lines written for these fixtures (no
book excerpt, nothing to license). The tones carry no speech, which is
fine: the fixtures pin format, validation and loading, never content.

Determinism: fixed ffmpeg args (``-fflags +bitexact`` so the M4A muxer
writes no timestamps), fixed tone frequencies and nominal durations,
fixed JSON (sorted keys, no timestamps except a fixed ``created_at``).
:func:`export_all` encodes every chapter twice and refuses to write when
the two runs differ, so a re-export on the same ffmpeg is byte-identical
(the freshness test pins this). Durations are nominal (4000/3000 ms);
the export fails loudly when the real probe disagrees by more than the
spec tolerance, instead of baking probe output into the JSON.

Run from ``scribe/`` with ``uv run --no-sync scribe
export-render-fixtures`` (thin CLI wrapper in ``cli.py``), or ``uv run
--no-sync python export_render.py`` directly.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path

#: Placeholder ``encoder_offset_ms`` for both fixtures (RN10 measures the
#: real platform-encoder constant on the Tab E; 0 here means the field is
#: present and shaped, not that the offset was measured).
FIXTURE_ENCODER_OFFSET_MS = 0

#: Manifest ``gain_db`` values (illustrative, prove the shape; RN6/RN10
#: confirm how real gains are derived and whether these magnitudes hold).
RENDERED_GAIN_DB = {"narrator": -1.5, "dialogue": 0.5}
PARTIAL_GAIN_DB = {"narrator": -1.0, "dialogue": 0.0}

#: Fingerprint speeds (dialogue slightly faster proves per-role values).
FINGERPRINT_SPEEDS = {"narrator": 1.0, "dialogue": 1.05}

#: Placeholder engine version (RN10 records the real Tab E strings).
FINGERPRINT_ENGINE_VERSIONS = {"system": "placeholder (RN10 records the real Tab E strings)"}

#: Fixed bundle timestamp (a real clock would break determinism).
FIXTURE_CREATED_AT = "2026-10-06T12:00:00Z"

#: Synthetic source placeholders (distinct per fixture; the manifest sha256
#: matches these bytes exactly, like unrendered-golden).
RENDERED_SOURCE_BYTES = b"auloud-rendered-aac-golden-source-v1"
PARTIAL_SOURCE_BYTES = b"auloud-partial-aac-golden-source-v1"

#: Device-namespace id prefix (spec section 3: PC and device imports of one
#: source stay separate library entries).
DEVICE_ID_PREFIX = "auloud:device-book:"

FFMPEG_HELP = (
    "Install ffmpeg (provides both ffmpeg and ffprobe), then re-run: winget install ffmpeg"
)


class ExportRenderError(Exception):
    """ffmpeg is missing, an encode is not deterministic, or a fixture fails validation."""


@dataclass(frozen=True)
class ChapterSpec:
    """One fixture chapter: tone, nominal duration, sentences."""

    title: str
    frequency_hz: int
    duration_ms: int
    sentences: tuple[tuple[str, str], ...]
    rendered: bool = True


RENDERED_CHAPTERS = (
    ChapterSpec(
        title="The Harbor Steps",
        frequency_hz=440,
        duration_ms=4000,
        sentences=(
            ("narrator", "The lantern swayed above the harbor steps. "),
            ("dialogue", '"Mind the last step," said the ferryman. '),
            ("narrator", "Gulls settled on the quiet masts."),
            ("dialogue", '"We leave at dawn," said the ferryman.'),
        ),
    ),
    ChapterSpec(
        title="The Quiet Masts",
        frequency_hz=520,
        duration_ms=3000,
        sentences=(
            ("narrator", "Morning fog covered the quiet masts."),
            ("dialogue", '"Hold the rope steady," said the sailor. '),
            ("narrator", "The tide turned without a sound."),
        ),
    ),
)

PARTIAL_CHAPTERS = (
    ChapterSpec(
        title="The Ferry Bell",
        frequency_hz=660,
        duration_ms=3000,
        sentences=(
            ("narrator", "The ferry bell rang across the water. "),
            ("dialogue", '"All aboard," called the captain. '),
            ("narrator", "Ropes dropped into willing hands."),
        ),
    ),
    ChapterSpec(
        title="The Far Shore",
        frequency_hz=0,
        duration_ms=0,
        sentences=(
            ("narrator", "The far shore stayed out of reach."),
            ("dialogue", '"Not yet," whispered the lookout.'),
        ),
        rendered=False,
    ),
)


def _repo_fixtures_dir() -> Path:
    return Path(__file__).resolve().parent.parent / "spec" / "fixtures"


def _require_tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        raise ExportRenderError(f"{name} not found on PATH ({FFMPEG_HELP})")
    return path


def _device_id_for_source(source_bytes: bytes) -> tuple[str, str]:
    """Device-namespace bundle id plus hex sha256 for placeholder bytes."""
    sha = hashlib.sha256(source_bytes).hexdigest()
    book_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{DEVICE_ID_PREFIX}{sha.lower()}"))
    return book_id, sha


def _encode_tone_m4a(frequency_hz: int, duration_ms: int) -> bytes:
    """Encode a sine tone to AAC-LC mono 24 kHz 64 kbps M4A; return the bytes.

    Runs the encode twice and returns the first run only when both runs
    are byte-identical (determinism self-check); raises otherwise.
    """
    import tempfile

    ffmpeg = _require_tool("ffmpeg")
    seconds = duration_ms / 1000.0
    runs: list[bytes] = []
    # The mp4 muxer needs seekable output (moov written last), so each
    # run encodes to a temp file whose bytes are compared afterwards.
    with tempfile.TemporaryDirectory(prefix="auloud-render-") as tmp:
        for run in range(2):
            out_path = Path(tmp) / f"tone-{run}.m4a"
            cmd = [
                ffmpeg,
                "-v",
                "error",
                "-y",
                "-fflags",
                "+bitexact",
                "-f",
                "lavfi",
                "-i",
                f"sine=frequency={frequency_hz}:duration={seconds}:sample_rate=24000",
                "-ac",
                "1",
                "-ar",
                "24000",
                "-c:a",
                "aac",
                "-b:a",
                "64k",
                str(out_path),
            ]
            try:
                proc = subprocess.run(cmd, capture_output=True, timeout=120, check=False)
            except subprocess.TimeoutExpired as exc:
                raise ExportRenderError(f"ffmpeg tone encode timed out: {exc}") from exc
            except OSError as exc:
                raise ExportRenderError(f"ffmpeg tone encode failed to start: {exc}") from exc
            if proc.returncode != 0:
                detail = proc.stderr.decode("utf-8", "replace").strip().splitlines()
                raise ExportRenderError(
                    f"ffmpeg tone encode failed: {detail[0] if detail else 'unknown error'}"
                )
            runs.append(out_path.read_bytes())
    if runs[0] != runs[1]:
        raise ExportRenderError(
            "ffmpeg tone encode is not deterministic "
            f"({len(runs[0])} vs {len(runs[1])} bytes across two runs)"
        )
    if not runs[0]:
        raise ExportRenderError("ffmpeg tone encode produced no bytes")
    return runs[0]


def _probe_duration_ms(path: Path) -> int:
    """ffprobe format duration of ``path`` in whole milliseconds."""
    ffprobe = _require_tool("ffprobe")
    try:
        proc = subprocess.run(
            [
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "json",
                str(path),
            ],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise ExportRenderError(f"ffprobe timed out on {path.name}: {exc}") from exc
    except OSError as exc:
        raise ExportRenderError(f"ffprobe failed to start: {exc}") from exc
    if proc.returncode != 0:
        detail = (proc.stderr or "").strip().splitlines()
        raise ExportRenderError(
            f"ffprobe failed on {path.name}: {detail[0] if detail else 'unknown error'}"
        )
    try:
        duration = float(json.loads(proc.stdout)["format"]["duration"])
    except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        raise ExportRenderError(f"ffprobe duration unreadable for {path.name}: {exc}") from exc
    return round(duration * 1000)


def _check_probe_agrees(audio_path: Path, duration_ms: int, tolerance_ms: int = 50) -> None:
    """The real probe must agree with the nominal duration (fail loudly)."""
    probed = _probe_duration_ms(audio_path)
    drift = abs(probed - duration_ms)
    if drift > tolerance_ms:
        raise ExportRenderError(
            f"{audio_path.name}: ffprobe duration {probed} ms differs from nominal "
            f"duration_ms {duration_ms} by {drift} ms (tolerance {tolerance_ms} ms)"
        )


def _chapter_json(index: int, spec: ChapterSpec) -> dict:
    """Chapter text JSON dict (timings split the nominal duration evenly)."""
    if spec.rendered:
        step = spec.duration_ms // len(spec.sentences)
        sentences = [
            {
                "sid": pos,
                "speaker": speaker,
                "start_ms": (pos - 1) * step,
                "end_ms": pos * step,
                "text": text,
            }
            for pos, (speaker, text) in enumerate(spec.sentences, start=1)
        ]
        return {
            "spec_version": "2.0",
            "chapter": index,
            "title": spec.title,
            "duration_ms": spec.duration_ms,
            "blocks": [
                {
                    "id": 1,
                    "type": "para",
                    "sentences": sentences,
                }
            ],
        }
    return {
        "spec_version": "2.0",
        "chapter": index,
        "title": spec.title,
        "blocks": [
            {
                "id": 1,
                "type": "para",
                "sentences": [
                    {"sid": pos, "speaker": speaker, "text": text}
                    for pos, (speaker, text) in enumerate(spec.sentences, start=1)
                ],
            }
        ],
    }


def _fingerprint_json() -> dict:
    return {
        "engine": "system",
        "voices": {"narrator": "default", "dialogue": "default"},
        "speeds": dict(FINGERPRINT_SPEEDS),
        "engine_versions": dict(FINGERPRINT_ENGINE_VERSIONS),
    }


def _manifest_json(
    *,
    title: str,
    book_id: str,
    sha: str,
    render_state: str,
    gain_db: dict[str, float],
    chapters: tuple[ChapterSpec, ...],
) -> dict:
    entries = []
    for pos, spec in enumerate(chapters, start=1):
        stem = f"ch{pos:03d}"
        if spec.rendered:
            entry: dict = {
                "index": pos,
                "title": spec.title,
                "audio": f"audio/{stem}.m4a",
                "text": f"text/{stem}.json",
                "duration_ms": spec.duration_ms,
                "render_fingerprint": _fingerprint_json(),
            }
        else:
            entry = {"index": pos, "title": spec.title, "text": f"text/{stem}.json"}
        entries.append(entry)
    return {
        "spec_version": "2.0",
        "render_state": render_state,
        "id": book_id,
        "title": title,
        "author": "Auloud Test",
        "type": "epub",
        "source": {"file": "source/book.epub", "sha256": sha},
        "audio": {
            "format": "m4a",
            "channels": 1,
            "sample_rate": 24000,
            "bitrate_kbps": 64,
            "cbr": True,
        },
        "gain_db": dict(gain_db),
        "encoder_offset_ms": FIXTURE_ENCODER_OFFSET_MS,
        "voices": {
            "narrator": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
            "dialogue": {"engine": "system", "voice": "default", "speed": 1.0, "pitch": 1.0},
        },
        "chapters": entries,
        "created_at": FIXTURE_CREATED_AT,
        "generator": "auloud-player 2.0",
    }


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def _export_one(
    fixtures_dir: Path,
    dirname: str,
    title: str,
    source_bytes: bytes,
    gain_db: dict[str, float],
    chapters: tuple[ChapterSpec, ...],
    readme: str,
) -> list[Path]:
    """Build one fixture directory; return every file written."""
    from bundle.validate import validate_bundle

    root = fixtures_dir / dirname
    book_id, sha = _device_id_for_source(source_bytes)
    written: list[Path] = []
    (root / "source").mkdir(parents=True, exist_ok=True)
    source_path = root / "source" / "book.epub"
    source_path.write_bytes(source_bytes)
    written.append(source_path)
    for pos, spec in enumerate(chapters, start=1):
        stem = f"ch{pos:03d}"
        chapter_path = root / "text" / f"{stem}.json"
        _write_json(chapter_path, _chapter_json(pos, spec))
        written.append(chapter_path)
        if spec.rendered:
            audio_path = root / "audio" / f"{stem}.m4a"
            audio_path.parent.mkdir(parents=True, exist_ok=True)
            audio_path.write_bytes(_encode_tone_m4a(spec.frequency_hz, spec.duration_ms))
            written.append(audio_path)
            _check_probe_agrees(audio_path, spec.duration_ms)
    manifest_path = root / "manifest.json"
    _write_json(
        manifest_path,
        _manifest_json(
            title=title,
            book_id=book_id,
            sha=sha,
            render_state="complete" if all(c.rendered for c in chapters) else "partial",
            gain_db=gain_db,
            chapters=chapters,
        ),
    )
    written.append(manifest_path)
    readme_path = root / "README.md"
    readme_path.write_text(readme, encoding="utf-8")
    written.append(readme_path)
    result = validate_bundle(root)
    if not result.ok:
        details = "\n".join(f"  - {error}" for error in result.errors)
        count = len(result.errors)
        raise ExportRenderError(
            f"exported fixture {dirname} failed validation ({count} error(s)):\n{details}"
        )
    return written


RENDERED_README = """# rendered-aac-golden (spec 2.0 part 2, Slice 10 RN1)

Hand-checkable tiny fixture for a device-rendered book: every chapter
rendered AAC-LC mono 24 kHz about 64 kbps in M4A, with per-chapter
`render_fingerprint` plus manifest `gain_db` and `encoder_offset_ms`.
Both sides consume it: Scribe pins it with the real `validate_bundle`
(including the real ffprobe AAC path), the Player with the real
`BundleParser`, `BundleValidator` and `ChapterTextLoader`.

Shape (spec `03-BundleSpec.md` sections 2-3, D-102):

- `manifest.json`: `spec_version` "2.0", `render_state` "complete",
  `audio.format` "m4a", `gain_db` per role in decibels,
  `encoder_offset_ms` 0, `voices` with the reserved `narrator` and
  `dialogue` keys, two chapter entries with `.m4a` audio, `duration_ms`
  and `render_fingerprint`.
- `text/ch001.json` (4 sentences, 4000 ms) and `text/ch002.json`
  (3 sentences, 3000 ms): `spec_version` "2.0", every sentence timed,
  sids run 1..N, speakers are `narrator`/`dialogue`.
- `audio/ch001.m4a` (440 Hz) and `audio/ch002.m4a` (520 Hz): real
  ffmpeg AAC encodes of sine tones (see provenance below).

Provenance: audio is `ffmpeg sine -> aac 64k mono 24k` (no speech, no
TTS model, nothing to license); sentence texts are original tiny lines
written for this fixture. `source/book.epub` is a synthetic placeholder;
the manifest `sha256` matches those bytes exactly. The bundle `id` is
the device-namespace id from the spec: UUIDv5 over
`auloud:device-book:<sha256-hex>`, so it can never collide with a
PC-rendered bundle of the same bytes.

Placeholders, not measurements: `encoder_offset_ms` 0 means the field
is present and shaped (RN10 measures the real platform-encoder constant
on the Tab E); `gain_db` values are illustrative (RN6/RN10 confirm how
real gains are derived); the fingerprint engine version is a placeholder
string (RN10 records the real Tab E strings). Regenerate with
`scribe export-render-fixtures` (deterministic: fixed tones, bitexact
mux, fixed JSON; the export encodes twice and refuses on any
difference).
"""

PARTIAL_README = """# partial-aac-golden (spec 2.0 part 2, Slice 10 RN1)

Hand-checkable tiny fixture for a partially rendered book: chapter 1
rendered AAC-LC mono 24 kHz about 64 kbps in M4A with a
`render_fingerprint`, chapter 2 unrendered (text plus sentence
structure, no audio, no timings), `render_state` "partial". Both sides
consume it: Scribe pins it with the real `validate_bundle`, the Player
with the real `BundleParser`, `BundleValidator` and
`ChapterTextLoader`.

Shape (spec `03-BundleSpec.md` sections 2-3, D-102):

- `manifest.json`: `spec_version` "2.0", `render_state` "partial",
  `audio.format` "m4a", `gain_db` per role in decibels,
  `encoder_offset_ms` 0. Chapter 1 carries `.m4a` audio, `duration_ms`
  and `render_fingerprint`; chapter 2 carries `text` only.
- `text/ch001.json` (3 sentences, 3000 ms, every sentence timed) and
  `text/ch002.json` (2 sentences, no timings).
- `audio/ch001.m4a` (660 Hz): a real ffmpeg AAC encode of a sine tone.
  No `audio/ch002` file exists.

Provenance: same as `rendered-aac-golden` (synthetic tones, original
tiny texts, placeholder source bytes with matching sha256,
device-namespace bundle id). Placeholders `encoder_offset_ms` 0,
illustrative `gain_db`, placeholder fingerprint engine version (see the
rendered golden README). Regenerate with
`scribe export-render-fixtures`.
"""


def export_all(fixtures_dir: Path | str | None = None) -> list[Path]:
    """Generate both RN1 fixtures; validate each with the real probe.

    :param fixtures_dir: ``spec/fixtures`` dir (default: the repo one).
    :returns: every file written, in write order per fixture.
    :raises ExportRenderError: tools missing, encode not deterministic,
        or a fixture fails validation.
    """
    root = Path(fixtures_dir) if fixtures_dir is not None else _repo_fixtures_dir()
    written: list[Path] = []
    written.extend(
        _export_one(
            root,
            "rendered-aac-golden",
            "Rendered AAC Golden",
            RENDERED_SOURCE_BYTES,
            dict(RENDERED_GAIN_DB),
            RENDERED_CHAPTERS,
            RENDERED_README,
        )
    )
    written.extend(
        _export_one(
            root,
            "partial-aac-golden",
            "Partial AAC Golden",
            PARTIAL_SOURCE_BYTES,
            dict(PARTIAL_GAIN_DB),
            PARTIAL_CHAPTERS,
            PARTIAL_README,
        )
    )
    return written


def main(argv: list[str] | None = None) -> int:
    """``python export_render.py [--fixtures-dir DIR]`` (dev entry point)."""
    parser = argparse.ArgumentParser(description="Generate the RN1 AAC/partial fixtures.")
    parser.add_argument("--fixtures-dir", default=None, help="spec/fixtures dir.")
    args = parser.parse_args(argv)
    try:
        written = export_all(args.fixtures_dir)
    except ExportRenderError as exc:
        print(f"export-render-fixtures failed: {exc}", file=sys.stderr)
        return 1
    for path in written:
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
