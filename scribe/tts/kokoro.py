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

"""Kokoro-82M engine via ``kokoro-onnx`` (SW0 winner, D-023), native 24 kHz.

Model location (final answer; SW1 left ``models/`` provisional):

- Default: a ``models/`` directory under the current working directory
  (so ``scribe/models/`` when you run from ``scribe/``), holding exactly
  ``kokoro-v1.0.onnx`` + ``voices-v1.0.bin`` — the same pair ``doctor``
  checks (``scribe doctor --models-dir <dir>`` overrides it).
- Override: pass explicit ``model_path``/``voices_path`` to
  :class:`KokoroEngine`, or point the smoke test at scratch copies with
  ``AULOUD_KOKORO_MODELS``. This module never downloads anything.

No network calls at runtime; missing files raise ``FileNotFoundError``
naming the expected paths.
"""

from __future__ import annotations

import importlib.metadata
from pathlib import Path
from typing import Any

import numpy as np

from tts.base import SAMPLE_RATE, TTSEngine, resample_mono

#: Narrator voice chosen in D-023 (American English).
DEFAULT_VOICE = "af_heart"

#: Exact model files expected in the models directory.
MODEL_FILES: tuple[str, ...] = ("kokoro-v1.0.onnx", "voices-v1.0.bin")

#: Canonical default models dir, relative to CWD (matches `doctor`).
DEFAULT_MODEL_DIR = Path("models")

#: Language tag passed to the kokoro-onnx phonemizer (English only, v1).
LANG = "en-us"


def resolve_model_paths(models_dir: Path | str = DEFAULT_MODEL_DIR) -> tuple[Path, Path]:
    """Return ``(model_path, voices_path)`` or raise listing what is missing."""
    directory = Path(models_dir)
    model_path = directory / MODEL_FILES[0]
    voices_path = directory / MODEL_FILES[1]
    missing = [str(p) for p in (model_path, voices_path) if not p.is_file()]
    if missing:
        raise FileNotFoundError(
            "Kokoro model files missing: "
            + ", ".join(missing)
            + f" (expected {', '.join(MODEL_FILES)} in {directory})."
        )
    return model_path, voices_path


class KokoroEngine(TTSEngine):
    """:class:`TTSEngine` backed by ``kokoro_onnx.Kokoro`` (D-023)."""

    def __init__(self, model_path: Path | str, voices_path: Path | str) -> None:
        try:
            from kokoro_onnx import Kokoro
        except ImportError as exc:
            raise ImportError(
                "kokoro-onnx is not installed; run `uv add kokoro-onnx` "
                "(see `scribe doctor` for the full checklist)."
            ) from exc
        self._model_path = Path(model_path)
        self._voices_path = Path(voices_path)
        for path in (self._model_path, self._voices_path):
            if not path.is_file():
                raise FileNotFoundError(f"Kokoro file not found: {path}")
        self._kokoro: Any = Kokoro(str(self._model_path), str(self._voices_path))

    @property
    def sample_rate(self) -> int:
        return SAMPLE_RATE

    @property
    def voices(self) -> tuple[str, ...]:
        """Available voice ids, sorted (audition order for `scribe voices`)."""
        return tuple(sorted(self._kokoro.voices))

    @property
    def engine_version(self) -> str:
        try:
            pkg = importlib.metadata.version("kokoro-onnx")
        except importlib.metadata.PackageNotFoundError:
            pkg = "unknown"
        return f"kokoro-onnx {pkg}"

    def synth(self, text: str, voice: str = DEFAULT_VOICE, speed: float = 1.0) -> np.ndarray:
        """Synthesize one sentence with ``voice`` at ``speed`` (24 kHz mono)."""
        if not text.strip():
            raise ValueError(
                "KokoroEngine.synth needs non-empty text; "
                "use cache.get_or_synth for empty sentences."
            )
        if voice not in self._kokoro.voices:
            raise ValueError(f"Unknown Kokoro voice {voice!r} for this voices file.")
        audio, reported_rate = self._kokoro.create(text, voice, float(speed), LANG)
        mono = np.asarray(audio, dtype=np.float32).ravel()
        if int(reported_rate) != SAMPLE_RATE:
            mono = resample_mono(mono, int(reported_rate), SAMPLE_RATE)
        return mono.astype(np.float32, copy=False)
