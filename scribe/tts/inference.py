"""Inference device policy and IO-binding helper for Scribe's TTS pipeline.

``cache.py`` owns storage; this module owns GPU/CPU policy and the optional
IO-binding wrapper around the model session. Engine objects are created via
:func:`device.create_engine_with_device` (re-exported here for callers that
prefer one import surface).

Concurrency: the production path is ``--workers`` chapters sharing one ORT
session (thread-safe). Task 6 measured two sessions == two concurrent runs
on one shared session (no gain), so the old per-worker session pool was
removed as inert (D-206); ``--inference-workers``/``--prep-workers`` went
with it.
"""

from __future__ import annotations

import os
from typing import Any, Sequence

import numpy as np

# Re-export device policy so callers have one import surface.
from device import (  # type: ignore[import-not-found]
    DEVICE_CHOICES,
    create_engine_with_device,
    ensure_cuda_dlls,
    resolve_device_providers,
)

from tts.base import TTSEngine

#: Opt-in env flag (Task 6). Default off so the sequential CPU/CUDA path stays
#: exactly what it was before this task ("all new flags default off").
IO_BINDING_ENV = "AULOUD_IO_BINDING"


def env_flag(name: str, default: bool = False) -> bool:
    """Read a boolean env flag (``1/true/yes/on``)."""
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


class IOBindingSession:
    """Proxy that runs an ORT session through IOBinding instead of ``run``.

    ``kokoro_onnx`` only calls ``self.sess.run(None, inputs)``; wrapping the
    session keeps its tokenize/trim/pause logic untouched. Any IOBinding
    failure (unsupported provider, shape quirk) falls back to the wrapped
    ``run`` so a render degrades instead of raising (plan risk register).

    Opt-in and OFF by default: measured on RTX 4050 / ORT 1.29 it is ~7%
    slower than ``run`` here (per-call bindings outweigh the avoided syncs),
    so it exists for other providers/hardware, not this one (Task 6 report).
    """

    def __init__(self, session: Any) -> None:
        self._session = session

    def run(
        self,
        output_names: Sequence[str] | None,
        input_feed: dict[str, Any],
        run_options: Any = None,
    ) -> list[Any]:
        try:
            binding = self._session.io_binding()
            for name, value in input_feed.items():
                binding.bind_cpu_input(name, np.ascontiguousarray(value))
            for output in self._session.get_outputs():
                binding.bind_output(output.name)
            self._session.run_with_iobinding(binding)
            return list(binding.copy_outputs_to_cpu())
        except Exception:
            return self._session.run(output_names, input_feed, run_options)

    def __getattr__(self, item: str) -> Any:
        return getattr(self._session, item)


def enable_iobinding(engine: TTSEngine) -> bool:
    """Wrap ``engine``'s Kokoro session in :class:`IOBindingSession` in place.

    Returns ``False`` when there is no session to wrap or it is already
    wrapped, so callers can log the outcome without branching on types.
    """
    backend = getattr(engine, "_kokoro", None)
    session = getattr(backend, "sess", None)
    if session is None or isinstance(session, IOBindingSession):
        return False
    backend.sess = IOBindingSession(session)
    return True


__all__ = [
    "DEVICE_CHOICES",
    "IO_BINDING_ENV",
    "IOBindingSession",
    "create_engine_with_device",
    "enable_iobinding",
    "ensure_cuda_dlls",
    "env_flag",
    "resolve_device_providers",
]
