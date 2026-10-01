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

"""SW6: TTS cache tests with a fake engine, plus one real-engine smoke test.

The fake engine proves ``get_or_synth`` only depends on the
``TTSEngine`` interface (a new engine really is just a new subclass).
The smoke test needs ``kokoro-v1.0.onnx`` + ``voices-v1.0.bin`` and
SKIPS loudly when they are absent — it never fails for missing models.
"""

from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import pytest

from tts.base import TTSEngine
from tts.cache import MAX_SYNTH_CHARS, cache_key, get_or_synth, split_long_sentence

SCRATCH_MODELS = Path(r"D:\PROGRAMS\Websites\Auloud\scratch\temp")
MODEL_FILES: tuple[str, str] = ("kokoro-v1.0.onnx", "voices-v1.0.bin")


class FakeEngine(TTSEngine):
    """Deterministic stand-in: 10 samples per character, records calls."""

    def __init__(self, version: str = "fake-1", rate: int = 24_000) -> None:
        self.calls: list[tuple[str, str, float]] = []
        self._version = version
        self._rate = rate

    @property
    def sample_rate(self) -> int:
        return self._rate

    @property
    def engine_version(self) -> str:
        return self._version

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        self.calls.append((text, voice, float(speed)))
        return np.full((len(text) * 10,), 0.5, dtype=np.float32)


def _cached_files(cache_dir: Path) -> list[Path]:
    return sorted(cache_dir.glob("*.flac")) if cache_dir.is_dir() else []


def test_cache_hit_skips_synthesis(tmp_path: Path) -> None:
    engine = FakeEngine()
    first = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    second = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == 1
    assert np.array_equal(first, second)
    assert len(_cached_files(tmp_path)) == 1


def test_voice_change_invalidates(tmp_path: Path) -> None:
    engine = FakeEngine()
    get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    get_or_synth(engine, "Hello world.", "af_bella", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == 2
    assert engine.calls[0][1] == "af_heart"
    assert engine.calls[1][1] == "af_bella"


def test_speed_change_invalidates(tmp_path: Path) -> None:
    engine = FakeEngine()
    get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    get_or_synth(engine, "Hello world.", "af_heart", 1.1, 1.0, tmp_path)
    assert len(engine.calls) == 2


def test_pitch_change_invalidates(tmp_path: Path) -> None:
    engine = FakeEngine()
    get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.05, tmp_path)
    assert len(engine.calls) == 2


def test_engine_version_change_invalidates(tmp_path: Path) -> None:
    first_key = cache_key("Hello world.", "af_heart", 1.0, 1.0, "fake-1")
    second_key = cache_key("Hello world.", "af_heart", 1.0, 1.0, "fake-2")
    assert first_key != second_key


def test_empty_sentence_short_circuits_without_engine(tmp_path: Path) -> None:
    engine = FakeEngine()
    for text in ("", "   ", "\n\t "):
        audio = get_or_synth(engine, text, "af_heart", 1.0, 1.0, tmp_path)
        assert len(audio) == 0
        assert audio.dtype == np.float32
    assert engine.calls == []
    assert _cached_files(tmp_path) == []


def test_long_sentence_splits_on_commas(tmp_path: Path) -> None:
    text = ", ".join(f"clause {i} lorem ipsum dolor sit amet" for i in range(60))
    assert len(text) > MAX_SYNTH_CHARS
    parts = split_long_sentence(text)
    assert len(parts) == 60

    engine = FakeEngine()
    audio = get_or_synth(engine, text, "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == len(parts)
    assert all(len(call_text) <= MAX_SYNTH_CHARS for call_text, _, _ in engine.calls)
    assert len(audio) == sum(len(part) * 10 for part in parts)

    # Resumed build reuses every clause without touching the engine.
    again = get_or_synth(engine, text, "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == len(parts)
    assert np.array_equal(audio, again)


def test_long_sentence_without_commas_passes_through(tmp_path: Path) -> None:
    text = "word " * 300
    assert len(text) > MAX_SYNTH_CHARS
    assert split_long_sentence(text) == [text]
    engine = FakeEngine()
    audio = get_or_synth(engine, text, "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == 1
    assert len(audio) == len(text) * 10


def test_short_sentence_takes_single_synth_call(tmp_path: Path) -> None:
    engine = FakeEngine()
    audio = get_or_synth(engine, "A normal sentence.", "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == 1
    assert len(audio) == len("A normal sentence.") * 10


def _find_model_files() -> tuple[Path | None, Path | None, list[str]]:
    """Search canonical, override, and scratch locations for both files."""
    candidates: list[Path] = []
    override = os.environ.get("AULOUD_KOKORO_MODELS", "").strip()
    if override:
        candidates.append(Path(override))
    candidates.append(Path("models"))
    candidates.append(Path(__file__).resolve().parent.parent / "models")
    candidates.append(SCRATCH_MODELS)
    searched: list[str] = []
    for directory in candidates:
        searched.append(str(directory))
        model = directory / MODEL_FILES[0]
        voices = directory / MODEL_FILES[1]
        if model.is_file() and voices.is_file():
            return model, voices, searched
    return None, None, searched


@pytest.mark.slow
def test_kokoro_smoke_synthesizes_24khz_non_silent() -> None:
    model, voices, searched = _find_model_files()
    if model is None or voices is None:
        pytest.skip(
            "SKIP: Kokoro model files absent — no failure, just no models. "
            f"Searched {searched} for {', '.join(MODEL_FILES)}. "
            "Place them in models/ (see `scribe doctor --models-dir`) or set "
            "AULOUD_KOKORO_MODELS to a dir holding both files."
        )
    from tts.kokoro import KokoroEngine

    engine = KokoroEngine(model, voices)
    audio = engine.synth("Hello world.", "af_heart", 1.0)
    assert engine.sample_rate == 24_000
    assert audio.dtype == np.float32
    assert len(audio) > 2_400  # well over a tenth of a second of speech
    assert float(np.max(np.abs(audio))) > 0.01  # genuinely non-silent
