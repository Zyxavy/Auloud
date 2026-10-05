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

"""TTS device selection (Slice 6 UI1): ``--device auto/cpu/cuda``.

Default ``auto`` means the CUDA provider only when ``onnxruntime`` reports
it, else CPU (plan D5, CPU default in spirit: no CUDA packaging fight, no
new dependency). ``cpu`` forces ``CPUExecutionProvider``; ``cuda`` forces
``CUDAExecutionProvider`` and fails cleanly when it is unavailable.

How the engine inits providers today (read for UI1): ``kokoro_onnx``
``session.create_session`` picks providers via ``resolve_providers``
(``ONNX_PROVIDER`` env wins, else all available when an accelerated
``onnxruntime-*`` distribution is installed, else plain CPU). UI1 does NOT
change that default path: :func:`resolve_device_providers` implements the
explicit ``--device`` mapping above, and :func:`create_engine_with_device`
builds an ``onnxruntime.InferenceSession`` with those providers and wraps
it via ``KokoroEngine.from_session`` (same ``TTSEngine`` contract as the
plain CPU path, no new packaging, no GPU deps).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

#: Valid ``--device`` values (CLI ``Choice`` mirrors this tuple).
DEVICE_CHOICES = ("auto", "cpu", "cuda")


def resolve_device_providers(device: str) -> list[str]:
    """Provider list for ``device`` (``auto``/``cpu``/``cuda``).

    :raises ValueError: unknown ``device`` (shaped
        ``device: bad-device: ...``) or ``cuda`` unavailable on this
        ``onnxruntime`` build (shaped ``device: cuda-unavailable: ...``).
    """
    normalized = str(device or "").strip().lower()
    if normalized not in DEVICE_CHOICES:
        raise ValueError(
            f"device: bad-device: {device!r} must be one of {list(DEVICE_CHOICES)}"
        )
    try:
        import onnxruntime as rt  # type: ignore[import-untyped]
    except ImportError:
        available: list[str] = ["CPUExecutionProvider"]
    else:
        try:
            available = list(rt.get_available_providers())
        except Exception:
            available = ["CPUExecutionProvider"]
    if normalized == "cpu":
        return ["CPUExecutionProvider"]
    if normalized == "cuda":
        if "CUDAExecutionProvider" not in available:
            raise ValueError(
                "device: cuda-unavailable: CUDAExecutionProvider not in "
                f"onnxruntime providers {available} (use --device auto or cpu)"
            )
        return ["CUDAExecutionProvider"]
    # auto: CUDA only when reported, else CPU (D5 default-safe).
    if "CUDAExecutionProvider" in available:
        return ["CUDAExecutionProvider"]
    return ["CPUExecutionProvider"]


def _nvidia_dll_dirs() -> list[str]:
    """``bin`` dirs of NVIDIA pip wheels in this interpreter (Windows only).

    ``onnxruntime-gpu`` needs CUDA/cuDNN DLLs on the loader path, but pip
    wheels do not touch ``PATH``. Returns existing ``.../nvidia/<pkg>/bin``
    dirs under ``sys.prefix`` (usually ``cublas``, ``cudnn``,
    ``cuda_runtime``); empty when absent or off Windows. Never raises.
    """
    if os.name != "nt":
        return []
    try:
        root = Path(sys.prefix) / "Lib" / "site-packages" / "nvidia"
        if not root.is_dir():
            return []
        found = [str(d) for d in (root / pkg / "bin" for pkg in os.listdir(root)) if d.is_dir()]
        return sorted(found)
    except Exception:
        return []


def ensure_cuda_dlls() -> list[str]:
    """Put NVIDIA wheel DLLs on the Windows loader path (best effort).

    Uses :func:`os.add_dll_directory` plus a ``PATH`` prepend (the latter
    covers child processes inheriting the environment). Returns the dirs
    registered; empty means nothing was done (off Windows, no wheels, or
    any error — callers must not branch on this, only log it).
    """
    dirs = _nvidia_dll_dirs()
    if not dirs:
        return []
    try:
        if os.name == "nt" and hasattr(os, "add_dll_directory"):
            for entry in dirs:
                try:
                    os.add_dll_directory(entry)
                except Exception:
                    pass
        path = os.environ.get("PATH", "")
        missing = [entry for entry in dirs if entry.lower() not in path.lower()]
        if missing:
            os.environ["PATH"] = os.pathsep.join([*missing, path])
    except Exception:
        return []
    return dirs


def create_engine_with_device(
    models_dir: Path | str = Path("models"), *, device: str = "auto"
) -> Any:
    """Build the Kokoro engine on ``device`` providers (no packaging change).

    :raises ValueError: bad ``device`` or unavailable CUDA (clean, no
        traceback at the CLI; wraps as :class:`build.BuildError` there).
    :raises FileNotFoundError/ImportError/OSError: missing runtime/models
        (same shapes as :func:`build.create_engine`).
    """
    from tts.kokoro import resolve_model_paths

    providers = resolve_device_providers(device)
    model_path, voices_path = resolve_model_paths(models_dir)
    try:
        from kokoro_onnx import Kokoro
    except ImportError as exc:
        raise ImportError(
            "kokoro-onnx is not installed; run `uv add kokoro-onnx` "
            "(see `scribe doctor` for the full checklist)."
        ) from exc
    if providers == ["CPUExecutionProvider"]:
        # Plain path stays exactly the old constructor (byte-identical
        # behavior for the common CPU case; same KokoroEngine type as
        # build.create_engine always returned before UI1).
        from tts.kokoro import KokoroEngine

        return KokoroEngine(str(model_path), str(voices_path))
    # CUDA path: pip wheels ship the DLLs but never touch PATH, so register
    # them first (no-op without the wheels; measured 2026-10-05: warmed
    # CUDA 3.95x vs CPU 4.03x on RTX 4050 — no gain for Kokoro-82M, CPU stays
    # the recommendation, but an installed GPU stack must work).
    ensure_cuda_dlls()
    try:
        import onnxruntime as rt  # type: ignore[import-untyped]
    except ImportError as exc:
        raise ValueError(
            "device: cuda-unavailable: onnxruntime not importable "
            "(use --device auto or cpu)"
        ) from exc
    session = rt.InferenceSession(str(model_path), providers=providers)
    backend = Kokoro.from_session(session, str(voices_path))
    from tts.kokoro import KokoroEngine as _KokoroEngine

    return _KokoroEngine.from_session(backend, str(model_path), str(voices_path))
