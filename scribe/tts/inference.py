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
"""

from __future__ import annotations

import os
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Any, Callable, Iterable, Sequence

# Re-export device policy so callers have one import surface.
from device import (  # type: ignore[import-not-found]
    DEVICE_CHOICES,
    create_engine_with_device,
    ensure_cuda_dlls,
    resolve_device_providers,
)

from tts.base import TTSEngine


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
    recorded on the result, the worker is replaced once (respawn is the
    caller's responsibility via ``respawn_worker``), and the task is retried
    on another worker. After ``max_retries`` failures the task stays failed
    and ``on_failure`` is called; the run continues so the resumable cache
    keeps finished work. Set ``max_retries=0`` to fail fast.
    """
    task_list = list(tasks)
    if not task_list:
        return []
    if not workers:
        raise ValueError("inference: no workers provided")

    # Results keyed by original position.
    results: dict[int, InferenceResult] = {}
    worker_list = list(workers)

    def _pick_worker(i: int) -> InferenceWorker:
        return worker_list[i % len(worker_list)]

    remaining = list(task_list)
    attempts: dict[int, int] = {t.index: 0 for t in task_list}

    while remaining:
        batch = remaining
        remaining = []
        with ThreadPoolExecutor(max_workers=len(worker_list)) as pool:
            future_to_task = {
                pool.submit(_pick_worker(i).infer, task): task
                for i, task in enumerate(batch)
            }
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
    "InferenceResult",
    "InferenceTask",
    "InferenceWorker",
    "create_engine_with_device",
    "create_workers",
    "device_policy",
    "ensure_cuda_dlls",
    "resolve_device_providers",
    "respawn_worker",
    "run_inference",
    "sort_by_length_window",
]
