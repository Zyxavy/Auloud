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

"""Piper engine via ``piper-tts`` (Slice 8 SW1, P1), resampled to 24 kHz.

Voice layout (final answer): a ``piper/`` directory inside the models dir
(so ``models/piper/`` when you run from ``scribe/``), holding one
``<voice>.onnx`` + ``<voice>.onnx.json`` pair per voice — the same pair
``PiperVoice.load`` expects (config defaults to model path + ``.json``).
Voice ids are the model stems (``en_US-ryan-low``); engine namespacing
(``piper:<voice>``) arrives in SW2. Multi-speaker ``speaker_id`` selection
is not wired yet: one voice id means one model file, default speaker.

Phonemization needs no external install: ``piper-tts`` wheels bundle the
espeak-ng data (``PiperVoice.load`` defaults ``espeak_data_dir`` to its
internal copy). Piper models are usually 16 or 22.05 kHz, so unlike
Kokoro the :func:`tts.base.resample_mono` path triggers on every synth.

Speed maps to Piper's ``length_scale`` as ``1 / speed`` (faster speech is
shorter phonemes), matching the ``TTSEngine.synth`` speed contract.

No network calls at runtime; missing files raise ``FileNotFoundError``
naming the expected paths.
"""

from __future__ import annotations

import importlib.metadata
from pathlib import Path
from typing import Any, Callable

import numpy as np

from tts.base import SAMPLE_RATE, TTSEngine, resample_mono

#: Model file suffix scanned in the piper dir (config is model + ".json",
#: which for ``<voice>.onnx`` is ``<voice>.onnx.json`` — exactly what
#: ``PiperVoice.load`` defaults to).
MODEL_SUFFIX = ".onnx"

#: Canonical piper dir name inside a models dir (matches `doctor`).
PIPER_DIR_NAME = "piper"


def discover_voices(models_dir: Path | str) -> dict[str, tuple[Path, Path]]:
    """Map voice id (model stem) to ``(model, config)`` paths.

    Only pairs with both files present are listed; a lone ``.onnx``
    without its ``.onnx.json`` is skipped (phoneme map unknown).
    Empty (not missing-dir error) when nothing is installed — Piper is
    the optional second engine, so absence is normal.
    """
    piper_dir = Path(models_dir) / PIPER_DIR_NAME
    found: dict[str, tuple[Path, Path]] = {}
    if not piper_dir.is_dir():
        return found
    for model_path in sorted(piper_dir.glob(f"*{MODEL_SUFFIX}")):
        config_path = model_path.with_name(model_path.name + ".json")
        if config_path.is_file():
            found[model_path.stem] = (model_path, config_path)
    return found


class PiperEngine(TTSEngine):
    """:class:`TTSEngine` backed by ``piper_tts.PiperVoice`` (P1)."""

    def __init__(
        self,
        models_dir: Path | str = Path("models"),
        *,
        voice_loader: Callable[..., Any] | None = None,
    ) -> None:
        try:
            from piper import PiperVoice
        except ImportError as exc:
            raise ImportError(
                "piper-tts is not installed; run `uv sync` "
                "(see `scribe doctor` for the full checklist)."
            ) from exc
        self._loader = voice_loader or PiperVoice.load
        self._voice_files = discover_voices(models_dir)
        if not self._voice_files:
            raise FileNotFoundError(
                f"No Piper voices in {Path(models_dir) / PIPER_DIR_NAME} "
                f"(expected <voice>{MODEL_SUFFIX} + <voice>{MODEL_SUFFIX}.json pairs)."
            )
        self._loaded: dict[str, Any] = {}

    @property
    def sample_rate(self) -> int:
        return SAMPLE_RATE

    @property
    def voices(self) -> tuple[str, ...]:
        """Available voice ids, sorted (audition order for `scribe voices`)."""
        return tuple(sorted(self._voice_files))

    @property
    def engine_version(self) -> str:
        try:
            pkg = importlib.metadata.version("piper-tts")
        except importlib.metadata.PackageNotFoundError:
            pkg = "unknown"
        return f"piper-tts {pkg}"

    def _voice(self, voice: str) -> Any:
        if voice not in self._voice_files:
            raise ValueError(
                f"Unknown Piper voice {voice!r} (available: {', '.join(self.voices)})."
            )
        if voice not in self._loaded:
            model_path, config_path = self._voice_files[voice]
            self._loaded[voice] = self._loader(str(model_path), str(config_path))
        return self._loaded[voice]

    def synth(self, text: str, voice: str, speed: float = 1.0) -> np.ndarray:
        """Synthesize one sentence with ``voice`` at ``speed`` (24 kHz mono)."""
        if not text.strip():
            raise ValueError(
                "PiperEngine.synth needs non-empty text; "
                "use cache.get_or_synth for empty sentences."
            )
        if not speed or speed <= 0:
            raise ValueError(f"PiperEngine.synth needs speed > 0, got {speed!r}.")
        backend = self._voice(voice)
        from piper import SynthesisConfig

        config = SynthesisConfig(length_scale=1.0 / float(speed))
        chunks = list(backend.synthesize(text, config))
        parts = [
            np.asarray(chunk.audio_float_array, dtype=np.float32).ravel()
            for chunk in chunks
        ]
        mono = np.concatenate(parts) if parts else np.zeros(0, dtype=np.float32)
        # Native rate comes from the chunks (16 or 22.05 kHz on real
        # voices); fakes in tests may omit it, and then the audio is
        # already at bundle rate by construction.
        rate = int(getattr(chunks[0], "sample_rate", SAMPLE_RATE)) if chunks else SAMPLE_RATE
        if rate != SAMPLE_RATE:
            mono = resample_mono(mono, rate, SAMPLE_RATE)
        return mono.astype(np.float32, copy=False)
