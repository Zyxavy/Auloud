# Auloud Scribe — turns ebooks into multi-voice audiobooks (PC tool).
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

"""Streaming CBR MP3 encode via ffmpeg subprocess + ffprobe check (SW7).

One chapter = ONE ffmpeg process reading raw PCM (mono 24 kHz int16) on
stdin and writing ``-codec:a libmp3lame -b:a 64k`` (CBR), exactly as the
bundle spec requires ("encode from one continuous buffer"). ffmpeg runs as
a subprocess only — never linked.

Memory bound: the caller (:mod:`audio.assemble`) holds one chapter's
float32 buffer; this module adds only ONE int16 chunk (``CHUNK_SAMPLES``
samples, ~94 KB at the default 48 000 samples) plus the ffmpeg process's
own streaming buffers. PCM is converted float32 -> int16 and written to
stdin chunk by chunk, so a 60-minute chapter never needs a second
full-size copy. Per-sentence files are never written; the whole book is
never held.

Post-encode check: the MP3 duration (via the shared SW2 probe helper
:func:`bundle.validate.probe_audio_ffprobe` — reused by import, not
duplicated) must agree with the sample-count duration within 50 ms. On
mismatch a loud :class:`DurationMismatchError` names the chapter and both
durations; timings are NEVER silently adjusted (encoder delay/padding gets
investigated, and the MP3 Info/Xing header stays — stripping it would break
gapless/seek metadata).

No network access.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import numpy as np

from audio.assemble import AssembledChapter, ms_for_samples
from bundle.validate import AudioProbeError, probe_audio_ffprobe
from tts.base import SAMPLE_RATE

#: MP3 bitrate (spec: 64 kbps CBR).
BITRATE_KBPS = 64
#: Post-encode tolerance (spec section 6 rule 4; mirrors the validator).
DURATION_TOLERANCE_MS = 50
#: PCM samples per stdin write: 2 s at 24 kHz -> 96 000 bytes int16.
#: Small enough to bound memory, large enough to avoid pipe-chatter.
CHUNK_SAMPLES = 48_000

FFMPEG_HELP = (
    "Install ffmpeg (provides both ffmpeg and ffprobe), then re-run: "
    "winget install ffmpeg"
)


class EncodeError(Exception):
    """ffmpeg is missing, the encode failed, or the MP3 cannot be probed."""


class DurationMismatchError(EncodeError):
    """MP3 duration disagrees with the sample-count duration (> 50 ms)."""


def _ffmpeg_path() -> str:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise EncodeError(f"ffmpeg not found on PATH ({FFMPEG_HELP})")
    return ffmpeg


def _float_to_int16_bytes(chunk: np.ndarray) -> bytes:
    """Clip ``[-1, 1]`` float32 to int16 little-endian bytes (deterministic)."""
    clipped = np.clip(chunk, -1.0, 1.0) * 32767.0
    return clipped.astype(np.int16).tobytes()


def encode_chapter_pcm(
    pcm: np.ndarray,
    out_path: Path | str,
    *,
    expected_duration_ms: int,
    sample_rate: int = SAMPLE_RATE,
    chapter_label: str = "chapter",
) -> int:
    """Stream ``pcm`` into ONE ffmpeg process and check the MP3 duration.

    :param pcm: mono float32 audio at ``sample_rate`` (the exact
        :class:`AssembledChapter` buffer — timings come from this same
        data, never from re-measuring the MP3).
    :param out_path: destination MP3 (parent dirs created).
    :param expected_duration_ms: sample-count duration
        (``AssembledChapter.duration_ms``).
    :param chapter_label: name used in error messages (e.g. ``ch001``).
    :returns: the ffprobe-measured MP3 duration in ms (within 50 ms of
        ``expected_duration_ms``).
    :raises EncodeError: ffmpeg missing/failed or MP3 unprobable.
    :raises DurationMismatchError: durations disagree — investigate
        encoder delay/padding; timings are left untouched.
    """
    if int(sample_rate) != SAMPLE_RATE:
        raise EncodeError(
            f"{chapter_label}: sample_rate must be {SAMPLE_RATE}, got {sample_rate}."
        )
    if expected_duration_ms <= 0:
        raise EncodeError(
            f"{chapter_label}: expected_duration_ms must be positive, "
            f"got {expected_duration_ms}."
        )
    audio = np.asarray(pcm, dtype=np.float32).ravel()
    if audio.size == 0:
        raise EncodeError(f"{chapter_label}: nothing to encode (empty buffer).")
    if not np.all(np.isfinite(audio)):
        raise EncodeError(f"{chapter_label}: buffer is not finite (NaN or inf).")

    ffmpeg = _ffmpeg_path()
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        ffmpeg,
        "-v",
        "error",
        "-y",
        "-f",
        "s16le",
        "-ac",
        "1",
        "-ar",
        str(int(sample_rate)),
        "-i",
        "pipe:0",
        "-codec:a",
        "libmp3lame",
        "-b:a",
        f"{BITRATE_KBPS}k",
        str(out),
    ]
    try:
        proc = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
    except OSError as exc:
        raise EncodeError(f"{chapter_label}: could not start ffmpeg: {exc}") from exc
    assert proc.stdin is not None
    try:
        for start in range(0, audio.size, CHUNK_SAMPLES):
            try:
                proc.stdin.write(_float_to_int16_bytes(audio[start : start + CHUNK_SAMPLES]))
            except BrokenPipeError as exc:
                _drain_and_raise(proc, chapter_label, exc)
        proc.stdin.close()
        _, stderr = proc.communicate(timeout=300)
    except DurationMismatchError:
        raise
    except EncodeError:
        raise
    except Exception as exc:  # timeouts, broken pipes mid-stream
        proc.kill()
        raise EncodeError(f"{chapter_label}: ffmpeg encode failed: {exc}") from exc
    if proc.returncode != 0:
        detail = (stderr or b"").decode("utf-8", "replace").strip().splitlines()
        raise EncodeError(
            f"{chapter_label}: ffmpeg failed "
            f"(exit {proc.returncode}): {detail[0] if detail else 'unknown error'}"
        )

    try:
        probe = probe_audio_ffprobe(out)
    except AudioProbeError as exc:
        raise EncodeError(f"{chapter_label}: {exc}") from exc
    if probe.duration_ms is None:
        raise EncodeError(f"{chapter_label}: could not determine MP3 duration.")
    drift = abs(probe.duration_ms - int(expected_duration_ms))
    if drift > DURATION_TOLERANCE_MS:
        raise DurationMismatchError(
            f"{chapter_label}: MP3 duration {probe.duration_ms} ms differs from "
            f"sample-count duration {expected_duration_ms} ms by {drift} ms "
            f"(tolerance {DURATION_TOLERANCE_MS} ms) — encoder delay/padding "
            "suspected; investigate (MP3 Info/Xing header kept), do not adjust timings."
        )
    return probe.duration_ms


def _drain_and_raise(
    proc: subprocess.Popen[bytes], chapter_label: str, exc: Exception
) -> None:
    """ffmpeg died mid-stream: collect stderr and raise a loud error."""
    try:
        _, stderr = proc.communicate(timeout=30)
    except Exception:
        stderr = b""
    detail = stderr.decode("utf-8", "replace").strip().splitlines()
    raise EncodeError(
        f"{chapter_label}: ffmpeg stdin broke "
        f"(exit {proc.returncode}): {detail[0] if detail else 'unknown error'}"
    ) from exc


def encode_assembled_chapter(
    assembled: AssembledChapter,
    out_path: Path | str,
    *,
    chapter_label: str = "chapter",
) -> int:
    """Encode an :class:`AssembledChapter` buffer; check its own duration.

    Thin wrapper so SW9 cannot pass a mismatched duration: the expected
    duration always comes from ``assembled.duration_ms`` (which itself came
    from ``ms_for_samples(sample_count)``).
    """
    expected = ms_for_samples(assembled.sample_count, assembled.sample_rate)
    if expected != assembled.duration_ms:
        raise EncodeError(
            f"{chapter_label}: assembled duration {assembled.duration_ms} ms "
            f"does not match its sample count ({expected} ms); refusing to encode."
        )
    return encode_chapter_pcm(
        assembled.pcm,
        out_path,
        expected_duration_ms=assembled.duration_ms,
        sample_rate=assembled.sample_rate,
        chapter_label=chapter_label,
    )
