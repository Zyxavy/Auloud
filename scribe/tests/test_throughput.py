# Auloud Scribe — Task 6 throughput: inference concurrency + IO binding.
#
# Fast tests (no model) cover the IOBindingSession fallback contract and the
# env opt-in. GPU tests are skipped unless the Kokoro models are present and
# the CUDA provider actually engages.
#
# Note on "bit-identical": Kokoro-82M synthesis is *not* reproducible run to
# run (the waveform differs between two identical calls; the duration output
# is deterministic). So Tier A here compares durations bitwise and the
# waveform by shape/dtype plus a loose envelope, which is the strongest check
# this model allows (see the Task 6 report).

from __future__ import annotations

import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tts.inference import (
    IOBindingSession,
    enable_iobinding,
    env_flag,
)

MODELS_DIR = Path(__file__).resolve().parent.parent / "models"
MODEL = MODELS_DIR / "kokoro-v1.0.onnx"
VOICES = MODELS_DIR / "voices-v1.0.bin"


def _has_models() -> bool:
    return MODEL.is_file() and VOICES.is_file()


# ---------------------------------------------------------------------------
# Fakes (no onnxruntime, no models)
# ---------------------------------------------------------------------------


class _FakeBinding:
    def __init__(self) -> None:
        self.bound_inputs: dict[str, np.ndarray] = {}
        self.bound_outputs: list[str] = []
        self._outputs = [np.ones(4, np.float32), np.array([1, 2], np.int64)]

    def bind_cpu_input(self, name, value) -> None:
        self.bound_inputs[name] = value

    def bind_output(self, name, *args) -> None:
        self.bound_outputs.append(name)

    def copy_outputs_to_cpu(self):
        return self._outputs


class _FakeSession:
    def __init__(self, *, fail_binding: bool = False) -> None:
        self._model_path = "model.onnx"
        self.calls: list[str] = []
        self.binding: _FakeBinding | None = None
        self._fail_binding = fail_binding

    def get_outputs(self):
        from types import SimpleNamespace

        return [SimpleNamespace(name="waveform"), SimpleNamespace(name="duration")]

    def io_binding(self):
        if self._fail_binding:
            raise RuntimeError("iobinding unsupported")
        self.calls.append("io_binding")
        self.binding = _FakeBinding()
        return self.binding

    def run_with_iobinding(self, binding) -> None:
        self.calls.append("run_with_iobinding")

    def run(self, output_names, input_feed, run_options=None):
        self.calls.append("run")
        return [np.zeros(4, np.float32), np.array([1, 2], np.int64)]


# ---------------------------------------------------------------------------
# Fast tests
# ---------------------------------------------------------------------------


def test_env_flag_parsing(monkeypatch):
    monkeypatch.delenv("AULOUD_TEST_FLAG", raising=False)
    assert env_flag("AULOUD_TEST_FLAG") is False
    assert env_flag("AULOUD_TEST_FLAG", default=True) is True
    for value in ("1", "true", "YES", "on"):
        monkeypatch.setenv("AULOUD_TEST_FLAG", value)
        assert env_flag("AULOUD_TEST_FLAG") is True
    monkeypatch.setenv("AULOUD_TEST_FLAG", "0")
    assert env_flag("AULOUD_TEST_FLAG") is False


def test_iobinding_session_runs_through_binding():
    session = _FakeSession()
    outputs = IOBindingSession(session).run(None, {"input_ids": np.array([[1, 2]])})
    assert session.calls == ["io_binding", "run_with_iobinding"]
    assert set(session.binding.bound_inputs) == {"input_ids"}
    assert session.binding.bound_outputs == ["waveform", "duration"]
    assert np.array_equal(outputs[0], np.ones(4, np.float32))


def test_iobinding_falls_back_to_standard_run():
    session = _FakeSession(fail_binding=True)
    outputs = IOBindingSession(session).run(None, {"input_ids": np.array([[1, 2]])})
    assert session.calls == ["run"]
    assert np.array_equal(outputs[1], np.array([1, 2], np.int64))


def test_iobinding_forwards_unknown_attributes():
    proxy = IOBindingSession(_FakeSession())
    assert proxy._model_path == "model.onnx"
    assert [o.name for o in proxy.get_outputs()] == ["waveform", "duration"]


def test_enable_iobinding_wraps_once():
    from types import SimpleNamespace

    engine = SimpleNamespace(_kokoro=SimpleNamespace(sess=_FakeSession()))
    assert enable_iobinding(engine) is True
    assert isinstance(engine._kokoro.sess, IOBindingSession)
    assert enable_iobinding(engine) is False  # already wrapped
    assert enable_iobinding(SimpleNamespace()) is False  # no session


# ---------------------------------------------------------------------------
# GPU / model tests (skipped when unavailable)
# ---------------------------------------------------------------------------


def _cuda_session_providers():
    """Build one CUDA session (shared by the GPU tests) or skip."""
    if not _has_models():
        pytest.skip("Kokoro model files absent")
    try:
        from device import ensure_cuda_dlls

        ensure_cuda_dlls()
        import onnxruntime as rt  # type: ignore[import-untyped]
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"onnxruntime unavailable: {exc}")
    try:
        session = rt.InferenceSession(
            str(MODEL),
            providers=["CUDAExecutionProvider", "CPUExecutionProvider"],
        )
    except Exception as exc:  # pragma: no cover - environment dependent
        pytest.skip(f"CUDA session init failed: {exc}")
    if "CUDAExecutionProvider" not in session.get_providers():
        pytest.skip("CUDAExecutionProvider not active on this machine")
    return session


@pytest.fixture(scope="module")
def kokoro():
    from kokoro_onnx import Kokoro

    session = _cuda_session_providers()
    return Kokoro.from_session(session, str(VOICES))


def _tokens_and_style(kokoro_backend, text):
    tokens = kokoro_backend.tokenizer.tokenize(kokoro_backend.tokenizer.phonemize(text, "en-us"))
    style = kokoro_backend._style_for(kokoro_backend.get_voice_style("af_heart"), len(tokens))
    return tokens, style


def test_iobinding_matches_standard_run_on_cuda(kokoro):
    """Step 2: IOBinding returns the same duration and an equivalent waveform.

    The waveform is stochastic run to run, so bit-equality is unachievable;
    duration is deterministic and compared exactly.
    """
    tokens, style = _tokens_and_style(kokoro, "The quiet alder fox crosses the hill.")

    plain_audio, plain_duration = kokoro._infer(tokens, style, 1.0)
    kokoro.sess = IOBindingSession(kokoro.sess)
    bound_audio, bound_duration = kokoro._infer(tokens, style, 1.0)

    assert np.array_equal(plain_duration, bound_duration)
    assert plain_audio.shape == bound_audio.shape
    assert plain_audio.dtype == bound_audio.dtype
    assert float(np.abs(plain_audio - bound_audio).max()) < 0.5


def test_concurrent_sessions_k2_match_k1_on_cuda(kokoro):
    """Step 1: two sessions in two threads give the same durations as one.

    Duration output is deterministic, so this is a real equivalence check for
    the concurrent path (production renders chapters this way).
    """
    import onnxruntime as rt  # type: ignore[import-untyped]

    session2 = rt.InferenceSession(
        str(MODEL), providers=["CUDAExecutionProvider", "CPUExecutionProvider"]
    )
    from kokoro_onnx import Kokoro

    backend2 = Kokoro.from_session(session2, str(VOICES))
    backends = [kokoro, backend2]

    texts = [
        "Yes.",
        "The quiet alder fox crosses the mossy hill at dawn.",
        "She said nothing for a long moment, and the silence grew heavy.",
        "Run.",
    ]
    items = [_tokens_and_style(kokoro, t) for t in texts]

    serial = [kokoro._infer(tokens, style, 1.0) for tokens, style in items]

    def _run(index: int):
        tokens, style = items[index]
        return backends[index % 2]._infer(tokens, style, 1.0)

    with ThreadPoolExecutor(max_workers=2) as pool:
        concurrent = list(pool.map(_run, range(len(items))))

    for (serial_audio, serial_duration), (con_audio, con_duration) in zip(serial, concurrent):
        assert np.array_equal(serial_duration, con_duration)
        assert serial_audio.shape == con_audio.shape
