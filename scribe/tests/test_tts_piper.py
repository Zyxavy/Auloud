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

"""Slice 8 SW1: Piper engine behind the TTSEngine ABC (fake backend).

Real-model synthesis is a manual proof (needs a ~60 MB voice download),
so every test here runs against an injected fake voice loader: no model
files, no onnxruntime session, no espeak. One test asserts the real
``piper`` package imports (the pinned dependency), nothing more.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import numpy as np
import pytest

import cli
from tts.base import SAMPLE_RATE, TTSEngine
from tts.piper import PIPER_DIR_NAME, PiperEngine, discover_voices


class _FakeChunk:
    def __init__(self, samples: np.ndarray, rate: int = 16_000) -> None:
        self.audio_float_array = samples
        self.sample_rate = rate


class _FakeVoice:
    """Stand-in for ``piper.PiperVoice``: records configs, returns chunks."""

    def __init__(self, rate: int = 16_000) -> None:
        self.rate = rate
        self.configs: list[Any] = []
        self.calls = 0

    def synthesize(self, text: str, config: Any) -> Any:
        self.calls += 1
        self.configs.append(config)
        # One chunk per word: proves concatenation across chunks.
        return [
            _FakeChunk(np.full(100, 0.5, dtype=np.float32), self.rate)
            for _ in text.split()
        ]


def _pair(models_dir: Path, stem: str) -> tuple[Path, Path]:
    piper_dir = models_dir / PIPER_DIR_NAME
    piper_dir.mkdir(parents=True, exist_ok=True)
    model = piper_dir / f"{stem}.onnx"
    config = piper_dir / f"{stem}.onnx.json"
    model.write_bytes(b"fake-onnx")
    config.write_text("{}", encoding="utf-8")
    return model, config


def _engine(models_dir: Path, **kwargs: Any) -> PiperEngine:
    voices: dict[str, _FakeVoice] = {}

    def _loader(model_path: str, config_path: str) -> _FakeVoice:
        voice = _FakeVoice()
        voices[model_path] = voice
        return voice

    engine = PiperEngine(models_dir, voice_loader=_loader)
    engine._fakes = voices  # type: ignore[attr-defined]
    return engine


def test_discover_empty_without_piper_dir(tmp_path: Path) -> None:
    assert discover_voices(tmp_path) == {}


def test_discover_lists_pairs_sorted(tmp_path: Path) -> None:
    _pair(tmp_path, "b-voice")
    _pair(tmp_path, "a-voice")
    found = discover_voices(tmp_path)
    assert sorted(found) == ["a-voice", "b-voice"]
    model, config = found["a-voice"]
    assert model.suffix == ".onnx" and config.name.endswith(".onnx.json")


def test_discover_skips_lone_onnx_without_config(tmp_path: Path) -> None:
    piper_dir = tmp_path / PIPER_DIR_NAME
    piper_dir.mkdir()
    (piper_dir / "lonely.onnx").write_bytes(b"fake")
    assert discover_voices(tmp_path) == {}


def test_engine_is_a_tts_engine(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    assert isinstance(_engine(tmp_path), TTSEngine)


def test_engine_missing_voices_is_file_not_found(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match=PIPER_DIR_NAME):
        PiperEngine(tmp_path, voice_loader=lambda *a: None)


def test_engine_missing_runtime_is_import_error(
    tmp_path: Path, monkeypatch: object
) -> None:
    import sys

    _pair(tmp_path, "v")
    mp = monkeypatch  # type: ignore[union-attr]
    mp.setitem(sys.modules, "piper", None)  # `from piper import ...` raises
    with pytest.raises(ImportError, match="piper-tts"):
        PiperEngine(tmp_path)


def test_voices_sorted_tuple(tmp_path: Path) -> None:
    _pair(tmp_path, "b-voice")
    _pair(tmp_path, "a-voice")
    assert _engine(tmp_path).voices == ("a-voice", "b-voice")


def test_engine_version_names_package(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    version = _engine(tmp_path).engine_version
    assert version.startswith("piper-tts ")
    assert "unknown" not in version  # pinned dep installed in project env


def test_synth_concatenates_and_resamples(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    audio = _engine(tmp_path).synth("one two three", "v", 1.0)
    assert audio.dtype == np.float32
    # 3 words x 100 samples at 16 kHz -> 24 kHz.
    assert len(audio) == round(300 * SAMPLE_RATE / 16_000)


def test_synth_speed_maps_to_length_scale(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    engine = _engine(tmp_path)
    engine.synth("hello world", "v", 2.0)
    assert engine._fakes[next(iter(engine._fakes))].configs[0].length_scale == pytest.approx(0.5)


def test_synth_empty_text_is_value_error(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    with pytest.raises(ValueError, match="non-empty"):
        _engine(tmp_path).synth("   ", "v", 1.0)


def test_synth_unknown_voice_names_available(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    with pytest.raises(ValueError, match="Unknown Piper voice.*available: v"):
        _engine(tmp_path).synth("hi", "nope", 1.0)


def test_synth_bad_speed_is_value_error(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    with pytest.raises(ValueError, match="speed > 0"):
        _engine(tmp_path).synth("hi", "v", 0.0)


def test_voice_loads_once_per_voice(tmp_path: Path) -> None:
    _pair(tmp_path, "a")
    _pair(tmp_path, "b")
    engine = _engine(tmp_path)
    engine.synth("hi there", "a", 1.0)
    engine.synth("hi again", "a", 1.0)
    engine.synth("hi there", "b", 1.0)
    assert len(engine._fakes) == 2  # type: ignore[attr-defined]


def test_engine_works_through_cache(tmp_path: Path) -> None:
    # The ABC contract exists so callers go through the cache, never the
    # engine: prove a PiperEngine survives that path end to end.
    from tts.cache import get_or_synth

    _pair(tmp_path, "v")
    engine = _engine(tmp_path)
    first = get_or_synth(engine, "hello world", "v", 1.0, 0.0, tmp_path / "cache")
    second = get_or_synth(engine, "hello world", "v", 1.0, 0.0, tmp_path / "cache")
    assert len(first) > 0
    assert np.array_equal(first, second)


def test_piper_package_importable() -> None:
    # Pinned SW1 dependency: import only, no model load.
    import piper

    assert hasattr(piper, "PiperVoice") and hasattr(piper, "SynthesisConfig")


def test_doctor_lists_piper_row(tmp_path: Path) -> None:
    from typer.testing import CliRunner

    result = CliRunner().invoke(cli.app, ["doctor", "--models-dir", str(tmp_path)])
    assert "piper-models" in result.output


def test_check_piper_models_pass_with_pair(tmp_path: Path) -> None:
    _pair(tmp_path, "v")
    result = cli.check_piper_models(tmp_path)
    assert result.status == cli.PASS
    assert "v" in result.detail


def test_check_piper_models_info_without_pairs(tmp_path: Path) -> None:
    result = cli.check_piper_models(tmp_path)
    assert result.status == cli.INFO
    assert "optional" in result.detail


def test_check_piper_models_missing_runtime_is_fail(
    tmp_path: Path, monkeypatch: object
) -> None:
    import sys

    mp = monkeypatch  # type: ignore[union-attr]
    mp.setitem(sys.modules, "piper", None)
    # find_spec("piper") still true with sys.modules patched? Force the
    # lookup path instead: hide the spec.
    mp.setattr(cli.importlib.util, "find_spec", lambda _name: None)
    result = cli.check_piper_models(tmp_path)
    assert result.status == cli.FAIL
    assert result.hint
