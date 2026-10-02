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

"""MV0: voice audition tests with a fake engine, plus one real-engine smoke test.

The fake engine proves ``sample_voices`` only depends on the
``TTSEngine`` interface plus a ``voices`` list. The smoke test needs
``kokoro-v1.0.onnx`` + ``voices-v1.0.bin`` and SKIPS loudly when they are
absent — it never fails for missing models. It renders a single voice so
the full suite stays fast; the 54-voice folder is produced by hand via
``scribe voices --sample`` (see MV0) and never committed.
"""

from __future__ import annotations

import os
from pathlib import Path

import numpy as np
import pytest

import cli
from tts.base import SAMPLE_RATE, TTSEngine
from tts.voices import (
    SAMPLE_TEXT,
    VOICES_LIST_FILENAME,
    VoicesSample,
    list_voices,
    sample_voices,
)
from typer.testing import CliRunner

MODEL_FILES: tuple[str, str] = ("kokoro-v1.0.onnx", "voices-v1.0.bin")


class FakeVoicesEngine(TTSEngine):
    """Deterministic stand-in: named voices, 10 samples per character."""

    def __init__(
        self,
        voices: tuple[str, ...] = ("af_heart", "af_bella", "am_adam"),
        version: str = "fake-voices-1",
    ) -> None:
        self._voices = voices
        self._version = version
        self.calls: list[tuple[str, str, float]] = []

    @property
    def sample_rate(self) -> int:
        return SAMPLE_RATE

    @property
    def engine_version(self) -> str:
        return self._version

    @property
    def voices(self) -> tuple[str, ...]:
        return self._voices

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        if voice not in self._voices:
            raise ValueError(f"Unknown voice {voice!r}.")
        self.calls.append((text, voice, float(speed)))
        return np.full((len(text) * 10,), 0.5, dtype=np.float32)


class VoicelessEngine(FakeVoicesEngine):
    """Engine without a voice list (adapter error path)."""

    def __init__(self) -> None:
        self.calls = []

    @property
    def sample_rate(self) -> int:
        return SAMPLE_RATE

    @property
    def engine_version(self) -> str:
        return "voiceless-1"

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        raise AssertionError("must not synth without voices")


def test_list_voices_sorted() -> None:
    engine = FakeVoicesEngine(voices=("am_adam", "af_heart", "af_bella"))
    assert list_voices(engine) == ["af_bella", "af_heart", "am_adam"]


def test_list_voices_without_attribute_is_type_error() -> None:
    with pytest.raises(TypeError, match="no 'voices'"):
        list_voices(VoicelessEngine())  # type: ignore[arg-type]


def test_sample_writes_one_wav_per_voice(tmp_path: Path) -> None:
    import soundfile as sf

    engine = FakeVoicesEngine()
    result = sample_voices(engine, tmp_path)
    assert isinstance(result, VoicesSample)
    assert result.voices == ["af_bella", "af_heart", "am_adam"]
    assert len(result.files) == 3
    assert len(engine.calls) == 3
    # Same text and speed for every voice; only the voice varies.
    assert {call[0] for call in engine.calls} == {SAMPLE_TEXT}
    assert {call[1] for call in engine.calls} == set(result.voices)

    expected_names = [f"{i}-{v}.wav" for i, v in enumerate(result.voices, start=1)]
    assert [p.name for p in result.files] == expected_names
    for path in result.files:
        assert path.is_file()
        data, rate = sf.read(str(path), dtype="float32", always_2d=False)
        assert rate == SAMPLE_RATE
        assert len(data) == len(SAMPLE_TEXT) * 10

    voice_list = (tmp_path / VOICES_LIST_FILENAME).read_text(encoding="utf-8")
    assert voice_list.splitlines() == result.voices


def test_sample_subset_renders_only_requested(tmp_path: Path) -> None:
    result = sample_voices(engine := FakeVoicesEngine(), tmp_path, voices=["am_adam"])
    assert result.voices == ["am_adam"]
    assert [p.name for p in result.files] == ["1-am_adam.wav"]
    assert [call[1] for call in engine.calls] == ["am_adam"]


def test_sample_unknown_voice_fails_fast(tmp_path: Path) -> None:
    engine = FakeVoicesEngine()
    with pytest.raises(ValueError, match="no_such_voice"):
        sample_voices(engine, tmp_path, voices=["no_such_voice"])
    assert engine.calls == []
    assert list(tmp_path.glob("*.wav")) == []


def test_sample_empty_text_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="non-empty text"):
        sample_voices(FakeVoicesEngine(), tmp_path, text="   ")


def test_voices_command_lists_without_rendering(tmp_path: Path, monkeypatch: object) -> None:
    import build

    mp = monkeypatch  # type: ignore[union-attr]
    engine = FakeVoicesEngine()
    mp.setattr(build, "create_engine", lambda _models_dir: engine)
    out_dir = tmp_path / "samples"
    result = CliRunner().invoke(cli.app, ["voices", "--out-dir", str(out_dir)])
    assert result.exit_code == 0
    assert result.output.splitlines() == ["af_bella", "af_heart", "am_adam"]
    assert not out_dir.exists()  # listing never renders
    assert engine.calls == []


def test_voices_command_sample_renders_and_lists(tmp_path: Path, monkeypatch: object) -> None:
    import build

    mp = monkeypatch  # type: ignore[union-attr]
    engine = FakeVoicesEngine(voices=("af_heart", "af_bella"))
    mp.setattr(build, "create_engine", lambda _models_dir: engine)
    out_dir = tmp_path / "samples"
    result = CliRunner().invoke(cli.app, ["voices", "--sample", "--out-dir", str(out_dir)])
    assert result.exit_code == 0
    assert "af_bella" in result.output and "af_heart" in result.output
    assert "wrote 2 WAVs" in result.output
    assert sorted(p.name for p in out_dir.glob("*.wav")) == [
        "1-af_bella.wav",
        "2-af_heart.wav",
    ]
    assert (out_dir / VOICES_LIST_FILENAME).is_file()


def test_voices_command_engine_failure_is_clean_exit_1(monkeypatch: object) -> None:
    import build

    mp = monkeypatch  # type: ignore[union-attr]

    def _boom(_models_dir: object) -> object:
        raise build.BuildError("cannot init TTS engine: no models here")

    mp.setattr(build, "create_engine", _boom)
    result = CliRunner().invoke(cli.app, ["voices"])
    assert result.exit_code == 1
    assert "voices failed" in result.output


def _find_model_files() -> tuple[Path | None, Path | None, list[str]]:
    """Same search as the SW6 smoke test: env override, then canonical dirs."""
    candidates: list[Path] = []
    override = os.environ.get("AULOUD_KOKORO_MODELS", "").strip()
    if override:
        candidates.append(Path(override))
    candidates.append(Path("models"))
    candidates.append(Path(__file__).resolve().parent.parent / "models")
    searched: list[str] = []
    for directory in candidates:
        searched.append(str(directory))
        model = directory / MODEL_FILES[0]
        voices = directory / MODEL_FILES[1]
        if model.is_file() and voices.is_file():
            return model, voices, searched
    return None, None, searched


@pytest.mark.slow
def test_kokoro_sample_smoke_renders_single_voice(tmp_path: Path) -> None:
    model, voices_path, searched = _find_model_files()
    if model is None or voices_path is None:
        pytest.skip(
            "SKIP: Kokoro model files absent — no failure, just no models. "
            f"Searched {searched} for {', '.join(MODEL_FILES)}. "
            "Place them in models/ (see `scribe doctor --models-dir`) or set "
            "AULOUD_KOKORO_MODELS to a dir holding both files."
        )
    from tts.kokoro import KokoroEngine

    engine = KokoroEngine(model, voices_path)
    assert "af_heart" in list_voices(engine)
    assert len(list_voices(engine)) > 10  # the full v1.0 voice set, not a stub
    result = sample_voices(engine, tmp_path, voices=["af_heart"])
    assert len(result.files) == 1 and result.files[0].is_file()

    import soundfile as sf

    data, rate = sf.read(str(result.files[0]), dtype="float32", always_2d=False)
    assert rate == SAMPLE_RATE
    assert len(data) > SAMPLE_RATE  # well over a second of speech
    assert float(np.max(np.abs(data))) > 0.01  # genuinely non-silent
