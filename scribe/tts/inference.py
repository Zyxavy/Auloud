"""Inference device policy and worker logic for Scribe's TTS pipeline.

``cache.py`` owns storage; this module owns GPU/CPU policy and any
concurrency around the model itself. Engine objects are created via
:func:`device.create_engine_with_device` (re-exported here for callers that
prefer one import surface).

Design (Task 3, Option B2 candidate): ``K`` independent workers, each
holding its own inference session (and thus its own model copy). Workers
run sentences concurrently; results are returned in input order. A worker
that dies (OOM, driver reset) is respawned once and its in-flight
sentences retried; a second failure aborts cleanly so the resumable cache
keeps everything already finished.

shortcut: ``run_inference``/``create_workers`` are not called by ``build``
yet; production concurrency is ``--workers`` chapters sharing one ORT
session. Task 6 measured two sessions == two concurrent runs on one session
(no gain), so a K-session pool is not worth wiring until a provider needs it.
"""

from __future__ import annotations

import os
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable, Sequence

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


@dataclass
class InferenceTask:
    """One sentence to synthesize."""

    index: int
    text: str
    voice: str
    speed: float


@dataclass
class InferenceResult:
    """Outcome of one task."""

    index: int
    audio: Any = None  # numpy array (kept Any to avoid import cost)
    error: BaseException | None = None


@dataclass
class InferenceWorker:
    """Wraps a single engine (one session) used by one worker slot."""

    worker_id: int
    engine: TTSEngine
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def infer(self, task: InferenceTask) -> InferenceResult:
        try:
            with self._lock:
                audio = self.engine.synth(task.text, task.voice, task.speed)
            return InferenceResult(index=task.index, audio=audio)
        except BaseException as exc:  # noqa: BLE001 - recorded for retry/abort
            return InferenceResult(index=task.index, error=exc)


def sort_by_length_window(
    tasks: Sequence[InferenceTask], window: int = 32
) -> list[InferenceTask]:
    """Sort tasks by text length within fixed windows to reduce padding waste.

    Returns a new list; the caller restores original order via the
    ``index`` field. No-op when ``window <= 1``.
    """
    if window <= 1:
        return list(tasks)
    out: list[InferenceTask] = []
    for start in range(0, len(tasks), window):
        chunk = list(tasks[start : start + window])
        chunk.sort(key=lambda t: len(t.text))
        out.extend(chunk)
    return out


def run_inference(
    tasks: Iterable[InferenceTask],
    workers: Sequence[InferenceWorker],
    *,
    max_retries: int = 1,
    on_failure: Callable[[InferenceResult], None] | None = None,
) -> list[InferenceResult]:
    """Run ``tasks`` across ``workers``, preserving input order.

    Each task is handed to the next free worker. A worker exception is
    recorded on the result and the task is retried on another worker (up to
    ``max_retries`` times). After ``max_retries`` failures the task stays
    failed and ``on_failure`` is called; the run continues so the resumable
    cache keeps finished work. Set ``max_retries=0`` to fail fast.

    Note: worker respawn (creating a fresh engine after a crash) is the
    caller's responsibility via ``respawn_worker``. This function retries
    on surviving workers only.
    """
    task_list = list(tasks)
    if not task_list:
        return []
    if not workers:
        raise ValueError("inference: no workers provided")

    # Results keyed by original position.
    results: dict[int, InferenceResult] = {}
    worker_list = list(workers)
    n_workers = len(worker_list)

    remaining = list(task_list)
    attempts: dict[int, int] = {t.index: 0 for t in task_list}
    # Track which worker index ran each task, so retries avoid that worker.
    last_worker: dict[int, int] = {}

    while remaining:
        batch = remaining
        remaining = []
        with ThreadPoolExecutor(max_workers=n_workers) as pool:
            future_to_task: dict[Any, InferenceTask] = {}
            for i, task in enumerate(batch):
                prev = last_worker.get(task.index)
                if prev is None:
                    w_idx = i % n_workers
                else:
                    w_idx = (prev + 1) % n_workers
                last_worker[task.index] = w_idx
                future_to_task[pool.submit(worker_list[w_idx].infer, task)] = task
            for future in as_completed(future_to_task):
                task = future_to_task[future]
                try:
                    res = future.result()
                except BaseException as exc:  # noqa: BLE001
                    res = InferenceResult(index=task.index, error=exc)
                if res.error is not None:
                    attempts[task.index] += 1
                    if attempts[task.index] <= max_retries:
                        remaining.append(task)
                        continue
                    if on_failure is not None:
                        on_failure(res)
                results[task.index] = res

    # Preserve input order.
    ordered = [results[t.index] for t in task_list]
    return ordered


def respawn_worker(worker: InferenceWorker, factory: Callable[[], TTSEngine]) -> InferenceWorker:
    """Replace a dead worker's engine with a fresh one."""
    try:
        new_engine = factory()
    except BaseException:
        raise
    return InferenceWorker(worker_id=worker.worker_id, engine=new_engine)


def create_workers(
    k: int,
    factory: Callable[[], TTSEngine],
) -> list[InferenceWorker]:
    """Build ``k`` workers, each with its own session via ``factory``."""
    if k < 1:
        raise ValueError(f"inference: bad workers: {k!r} must be >= 1")
    return [InferenceWorker(worker_id=i, engine=factory()) for i in range(k)]


# Convenience alias for device policy consumers.
def device_policy(device: str) -> list[str]:
    return resolve_device_providers(device)


__all__ = [
    "DEVICE_CHOICES",
    "IO_BINDING_ENV",
    "IOBindingSession",
    "InferenceResult",
    "InferenceTask",
    "InferenceWorker",
    "create_engine_with_device",
    "create_workers",
    "device_policy",
    "enable_iobinding",
    "ensure_cuda_dlls",
    "env_flag",
    "resolve_device_providers",
    "respawn_worker",
    "run_inference",
    "sort_by_length_window",
]
