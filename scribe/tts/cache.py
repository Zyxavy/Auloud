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

"""Sentence-audio cache: compressed files keyed by the full synth hash (SW6).

Key = sha256(text, voice, speed, pitch, engine version); one FLAC file
per key under the cache dir (``<key>.flac``). A hit returns the stored
audio without touching the engine, so cast edits re-render only changed
lines and crashed builds resume.

Crash safety: writes go to a temp file in the same directory and are
atomically renamed into place, so a kill mid-write (SW9 kills builds on
purpose) can only ever leave a hidden ``.<key>.tmp-<pid>.flac`` behind —
never a truncated ``<key>.flac``. And a hit that fails to decode (torn
file from an older version, foreign bytes, 0-byte file) is deleted and
treated as a miss: re-synthesized, never returned partial.

Determinism contract: what resume requires is that every HIT decodes
identically — the same ``<key>.flac`` bytes give the same samples on
every read, so resumed builds produce identical bundles. The stored
format is FLAC with an explicit ``PCM_24`` subtype (libsndfile's default
is 16-bit, which would quantize harder); 24-bit keeps the cached audio
within ~1e-6 of the engine's float32 output. Float bit-exactness between
the pre-write array and the decoded hit is NOT promised and NOT needed.

Notes:

- ``pitch`` is key-only in v1 (Slice 4 applies it with ``pyrubberband``);
  changing it still invalidates, which is exactly what a later pitch-aware
  build needs.
- ``soundfile`` (BSD-3) needs system libsndfile; the bundled wheels ship
  it (verified libsndfile 1.2.2 on Windows), so no separate install step.
  If ``import soundfile`` ever fails, install the wheel's requirements and
  re-run ``scribe doctor``.
- Follow-up (not this slice): no size cap or eviction yet — a whole book
  is a few thousand small FLACs, fine for v1; add LRU pruning if the
  cache dir ever matters on disk. Stale ``.tmp-*.flac`` files from killed
  builds are ignored by readers (only ``<key>.flac`` is ever read).
"""

from __future__ import annotations

import hashlib
import math
import os
import re
from pathlib import Path

import numpy as np

from tts.base import TTSEngine, resample_mono

#: Sentences longer than this fall back to comma/semicolon splitting.
#: Why this value: ordinary prose sentences are ~100-300 characters, so
#: 1000 chars (≈150-200 words, ≈1 min of audio) only ever catches run-on
#: outliers; it keeps a single cache entry small and avoids one gigantic
#: phonemize/ONNX call, while Kokoro's own 510-phoneme window still
#: handles anything shorter internally. Documented, not tuned.
MAX_SYNTH_CHARS = 1000

_SPLIT_RE = re.compile(r"(?<=[,;])")


def cache_key(
    text: str, voice: str, speed: float, pitch: float, engine_version: str
) -> str:
    """Full-hash key over every input that can change the audio."""
    canonical = "|".join(
        [text, voice, repr(float(speed)), repr(float(pitch)), engine_version]
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def cache_path(cache_dir: Path | str, key: str) -> Path:
    """File holding one cached sentence (compressed FLAC, lossless)."""
    return Path(cache_dir) / f"{key}.flac"


def split_long_sentence(text: str) -> list[str]:
    """Split an over-long sentence on commas/semicolons (fallback).

    Delimiters stay attached to the preceding clause; parts are stripped
    and empties dropped. If there is no comma/semicolon to split on, the
    text is returned whole — the engine's own phoneme chunker then owns
    the length problem rather than this layer inventing word splits.
    """
    parts = [p.strip() for p in _SPLIT_RE.split(text)]
    parts = [p for p in parts if p]
    if len(parts) <= 1:
        return [text]
    return parts


def _read_flac(path: Path, sample_rate: int) -> np.ndarray:
    import soundfile as sf

    data, file_rate = sf.read(str(path), dtype="float32", always_2d=False)
    mono = np.asarray(data, dtype=np.float32)
    if mono.ndim > 1:
        mono = mono.mean(axis=1).astype(np.float32)
    mono = mono.ravel().astype(np.float32, copy=False)
    if int(file_rate) != int(sample_rate):
        mono = resample_mono(mono, int(file_rate), int(sample_rate))
    return mono


def _check_finite(name: str, value: float) -> None:
    """Reject NaN/inf at the cache boundary (they'd key but never synth)."""
    try:
        number = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be a number, got {value!r}.") from exc
    if not math.isfinite(number):
        raise ValueError(f"{name} must be finite, got {value!r}.")


def _write_flac_atomic(path: Path, audio: np.ndarray, sample_rate: int) -> None:
    """Write ``audio`` to ``path`` atomically (temp file + rename).

    ``os.replace`` within one directory is atomic, so readers only ever
    see the complete file or nothing. A failed write removes the temp
    file and re-raises; stale temps from killed builds are ignored.
    """
    import soundfile as sf

    tmp = path.with_name(f".{path.name}.tmp-{os.getpid()}.flac")
    try:
        sf.write(str(tmp), audio, sample_rate, format="FLAC", subtype="PCM_24")
        os.replace(tmp, path)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def get_or_synth(
    engine: TTSEngine,
    text: str,
    voice: str,
    speed: float,
    pitch: float,
    cache_dir: Path | str,
) -> np.ndarray:
    """Return sentence audio, synthesizing only on cache miss.

    Empty/whitespace-only sentences return zero-length audio without
    touching the engine (or the cache). Over-long sentences split via
    :func:`split_long_sentence` and each clause is cached individually,
    so a resumed build reuses the clauses it already rendered.
    Non-finite ``speed``/``pitch`` raise ``ValueError`` before any key
    is built. A present-but-undecodable file is deleted and treated as
    a miss, never returned.
    """
    if not text.strip():
        return np.zeros(0, dtype=np.float32)
    _check_finite("speed", speed)
    _check_finite("pitch", pitch)

    if len(text) > MAX_SYNTH_CHARS:
        parts = split_long_sentence(text)
        if len(parts) > 1:
            chunks = [
                get_or_synth(engine, part, voice, speed, pitch, cache_dir)
                for part in parts
                if part.strip()
            ]
            if not chunks:
                return np.zeros(0, dtype=np.float32)
            return np.concatenate(chunks).astype(np.float32, copy=False)

    key = cache_key(text, voice, speed, pitch, engine.engine_version)
    path = cache_path(cache_dir, key)
    if path.is_file():
        try:
            return _read_flac(path, engine.sample_rate)
        except Exception:
            # Torn write, foreign bytes, 0-byte file: drop it and
            # re-synthesize below. Broad catch is deliberate — any
            # decode failure means "not a valid hit".
            path.unlink(missing_ok=True)

    audio = np.asarray(engine.synth(text, voice, speed), dtype=np.float32).ravel()
    audio = audio.astype(np.float32, copy=False)
    directory = Path(cache_dir)
    directory.mkdir(parents=True, exist_ok=True)
    _write_flac_atomic(path, audio, engine.sample_rate)
    return audio
