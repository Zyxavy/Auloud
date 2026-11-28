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
import threading
import time
from pathlib import Path

import numpy as np
import pytest

from tts.base import TTSEngine
from tts.cache import (
    MAX_SYNTH_CHARS,
    cache_key,
    cache_path,
    get_or_synth,
    split_long_sentence,
)

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


class SineEngine(FakeEngine):
    """Realistic float data: dual sine, irrational sample values."""

    def __init__(self) -> None:
        super().__init__(version="sine-1")

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        self.calls.append((text, voice, float(speed)))
        n = len(text) * 10
        t = np.arange(n, dtype=np.float64) / 24_000
        wave = 0.7 * np.sin(2 * np.pi * 440 * t) + 0.1 * np.sin(2 * np.pi * 1320 * t)
        return wave.astype(np.float32)


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


@pytest.mark.parametrize("payload", [b"not-a-flac-at-all", b"", b"\x00" * 64])
def test_corrupt_cache_file_resynthesizes(tmp_path: Path, payload: bytes) -> None:
    """A torn write is a miss, never valid audio: re-synth and repair."""
    engine = FakeEngine()
    key = cache_key("Hello world.", "af_heart", 1.0, 1.0, engine.engine_version)
    poisoned = cache_path(tmp_path, key)
    tmp_path.mkdir(parents=True, exist_ok=True)
    poisoned.write_bytes(payload)

    audio = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == 1  # corrupt hit ignored, engine ran
    assert len(audio) == len("Hello world.") * 10

    # The repaired file is now a real hit: no more synth, identical audio.
    again = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == 1
    assert np.array_equal(audio, again)


def test_hit_output_deterministic_on_realistic_data(tmp_path: Path) -> None:
    """Every hit decodes identically; cache stays within ~1e-6 of float32."""
    engine = SineEngine()
    original = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    hit1 = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    hit2 = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    assert len(engine.calls) == 1
    assert np.array_equal(hit1, hit2)  # resume determinism: hits never differ
    assert float(np.max(np.abs(original - hit1))) < 1e-6  # PCM_24, not PCM_16


def test_concurrent_miss_same_sentence_stays_intact(tmp_path: Path) -> None:
    """Threads synthesizing the SAME sentence at once (RT2 chapters share
    lines) must each get complete audio, leave no temp files behind, and
    store a file that later decodes to the same samples."""

    class SlowSineEngine(SineEngine):
        """SineEngine with a sleep in synth, so the writers overlap."""

        def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
            time.sleep(0.05)
            return super().synth(text, voice, speed)

    engine = SlowSineEngine()
    results: list[np.ndarray] = []
    errors: list[BaseException] = []
    started = threading.Barrier(8)

    def worker() -> None:
        try:
            started.wait(timeout=10)
            results.append(
                get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
            )
        except BaseException as exc:  # noqa: BLE001 - recorded for the assert
            errors.append(exc)

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)

    assert errors == []
    assert len(results) == 8
    reference = results[0]
    assert all(np.array_equal(reference, r) for r in results)
    assert len(_cached_files(tmp_path)) == 1
    assert list(tmp_path.glob(".*tmp*")) == []  # no temp left behind
    hit = get_or_synth(engine, "Hello world.", "af_heart", 1.0, 1.0, tmp_path)
    # The hit is the PCM_24 store, the reference the raw float32 synth: the
    # documented ~1e-6 quantization gap, not a torn read.
    assert float(np.max(np.abs(reference - hit))) < 1e-6


@pytest.mark.parametrize(
    "speed,pitch",
    [
        (float("nan"), 1.0),
        (float("inf"), 1.0),
        (float("-inf"), 1.0),
        (1.0, float("nan")),
        (1.0, float("inf")),
        (1.0, float("-inf")),
    ],
)
def test_non_finite_speed_pitch_rejected(
    tmp_path: Path, speed: float, pitch: float
) -> None:
    engine = FakeEngine()
    with pytest.raises(ValueError):
        get_or_synth(engine, "Hello world.", "af_heart", speed, pitch, tmp_path)
    assert engine.calls == []
    assert _cached_files(tmp_path) == []


class _StubKokoro:
    """Stands in for ``kokoro_onnx.Kokoro``: named voices, records creates."""

    def __init__(self) -> None:
        self.voices: dict[str, np.ndarray] = {
            "af_heart": np.zeros(3, dtype=np.float32)
        }
        self.seen: list[tuple[str, str, float]] = []

    def create(
        self, text: str, voice: str, speed: float, lang: str
    ) -> tuple[np.ndarray, int]:
        self.seen.append((text, voice, speed))
        return np.zeros(240, dtype=np.float32), 24_000


def _kokoro_with_stub() -> tuple[object, _StubKokoro]:
    from tts.kokoro import KokoroEngine

    engine = KokoroEngine.__new__(KokoroEngine)
    stub = _StubKokoro()
    engine._kokoro = stub  # type: ignore[attr-defined]
    return engine, stub


def test_unknown_voice_raises_clean_error() -> None:
    engine, stub = _kokoro_with_stub()
    with pytest.raises(ValueError, match="no_such_voice") as exc_info:
        engine.synth("Hello.", "no_such_voice", 1.0)  # type: ignore[attr-defined]
    assert len(str(exc_info.value)) < 200  # names the voice, dumps no list
    assert stub.seen == []  # rejected before any synthesis work


def test_known_voice_passes_through() -> None:
    engine, stub = _kokoro_with_stub()
    audio = engine.synth("Hello.", "af_heart", 1.0)  # type: ignore[attr-defined]
    assert len(stub.seen) == 1
    assert audio.dtype == np.float32


def _find_model_files() -> tuple[Path | None, Path | None, list[str]]:
    """Search the env override then the canonical models dirs for both files."""
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


def test_warm_reads_decode_concurrently(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Warm hits decode in parallel: the decode is not held under _IO_LOCK."""
    from tts import cache as cache_mod

    engine = FakeEngine()
    texts = [f"Warm sentence number {i}." for i in range(4)]
    for text in texts:
        get_or_synth(engine, text, "af_heart", 1.0, 1.0, tmp_path)
    engine.calls.clear()  # warm-up synthesized; the measured reads must not

    real_decode = cache_mod._decode_flac
    gauge = threading.Lock()
    inside = {"now": 0, "peak": 0}

    def slow_decode(data: bytes, sample_rate: int) -> np.ndarray:
        with gauge:
            inside["now"] += 1
            inside["peak"] = max(inside["peak"], inside["now"])
        try:
            time.sleep(0.2)
            return real_decode(data, sample_rate)
        finally:
            with gauge:
                inside["now"] -= 1

    monkeypatch.setattr(cache_mod, "_decode_flac", slow_decode)
    results: dict[str, np.ndarray] = {}

    def worker(text: str) -> None:
        results[text] = get_or_synth(engine, text, "af_heart", 1.0, 1.0, tmp_path)

    threads = [threading.Thread(target=worker, args=(t,)) for t in texts]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=30)
    assert inside["peak"] > 1, inside
    assert engine.calls == [], "warm hits must not synthesize"
    assert set(results) == set(texts)


def _entry_path(tmp_path: Path, text: str) -> Path:
    engine = FakeEngine()
    return cache_path(tmp_path, cache_key(text, "af_heart", 1.0, 1.0, engine.engine_version))


def test_open_permission_error_retries_once_and_keeps_entry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A PermissionError while opening (rename in flight) is retried, never deleted."""
    from tts import cache as cache_mod

    engine = FakeEngine()
    get_or_synth(engine, "Retry me.", "af_heart", 1.0, 1.0, tmp_path)
    path = _entry_path(tmp_path, "Retry me.")
    real_open = cache_mod._open_entry
    attempts = {"n": 0}

    def flaky(p: Path) -> bytes:
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise PermissionError("entry is being replaced")
        return real_open(p)

    monkeypatch.setattr(cache_mod, "_open_entry", flaky)
    audio = cache_mod._read_cached(path, engine.sample_rate)
    assert audio is not None and audio.size > 0
    assert attempts["n"] == 2
    assert path.is_file()


def test_locked_open_is_a_miss_and_never_deletes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from tts import cache as cache_mod

    engine = FakeEngine()
    get_or_synth(engine, "Locked entry.", "af_heart", 1.0, 1.0, tmp_path)
    path = _entry_path(tmp_path, "Locked entry.")
    attempts = {"n": 0}

    def locked(p: Path) -> bytes:
        attempts["n"] += 1
        raise PermissionError("still locked")

    monkeypatch.setattr(cache_mod, "_open_entry", locked)
    assert cache_mod._read_cached(path, engine.sample_rate) is None
    assert attempts["n"] == 2
    assert path.is_file(), "a locked open is not evidence of a torn file"


def test_undecodable_entry_is_deleted(tmp_path: Path) -> None:
    """Real corruption (opens fine, will not decode) is still deleted as a miss."""
    from tts import cache as cache_mod

    engine = FakeEngine()
    path = _entry_path(tmp_path, "Torn entry.")
    path.write_bytes(b"not a flac stream")
    assert cache_mod._read_cached(path, engine.sample_rate) is None
    assert not path.exists()


def test_locked_rename_never_loses_audio(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """A rename that stays locked drops the entry, not the render."""
    import logging

    from tts import cache as cache_mod

    engine = FakeEngine()
    attempts = {"n": 0}

    def locked(src: object, dst: object) -> None:
        attempts["n"] += 1
        raise PermissionError("entry held open")

    monkeypatch.setattr(cache_mod.os, "replace", locked)
    text = "Locked store."
    with caplog.at_level(logging.WARNING):
        audio = get_or_synth(engine, text, "af_heart", 1.0, 1.0, tmp_path)
    assert audio.size == len(text) * 10
    assert attempts["n"] == cache_mod._REPLACE_ATTEMPTS
    assert list(tmp_path.glob(".*tmp*")) == []
    assert _cached_files(tmp_path) == []
    assert "not stored" in caplog.text
