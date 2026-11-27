"""TTS engine factory integrated with the inference device policy.

Thin facade over :mod:`device` and :mod:`tts.inference` so callers (and the
Web UI) import one module for both engine construction and device policy.
"""

from __future__ import annotations

from pathlib import Path

from device import (  # type: ignore[import-not-found]
    DEVICE_CHOICES,
    create_engine_with_device,
    resolve_device_providers,
)

from tts.base import TTSEngine

__all__ = [
    "DEVICE_CHOICES",
    "TTSEngine",
    "create_engine",
    "create_engine_with_device",
    "resolve_device_providers",
]


def create_engine(
    models_dir: Path | str = Path("models"),
    *,
    device: str = "auto",
    iobinding: bool | None = None,
) -> TTSEngine:
    """Build a TTS engine honoring ``device`` (delegates to ``device``).

    ``iobinding`` is a Task 6 CUDA opt-in; ``None`` reads the
    ``AULOUD_IO_BINDING`` env flag (default off).
    """
    normalized = str(device or "auto").strip().lower()
    if normalized == "cpu":
        # Preserve the byte-identical CPU constructor path.
        from tts.kokoro import KokoroEngine, resolve_model_paths

        model_path, voices_path = resolve_model_paths(models_dir)
        return KokoroEngine(str(model_path), str(voices_path))
    return create_engine_with_device(
        models_dir, device=normalized, iobinding=iobinding
    )