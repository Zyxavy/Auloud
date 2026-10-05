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

"""Voice audition samples (MV0): one WAV per Kokoro voice, one folder.

``scribe voices`` lists the available voice ids; ``scribe voices --sample``
renders :data:`SAMPLE_TEXT` in every voice to ``<out-dir>/NN-<voice>.wav``
plus a ``voices.txt`` list, so the palette shortlist is a listening task,
not a code task. Self-made sentence, no copyrighted text. Samples are
throwaway audition files (WAV/PCM-16 for broad player support), never
cached and never committed — the default out dir lives under ``logs/``,
which the root ``.gitignore`` excludes.

Any :class:`tts.base.TTSEngine` exposing the optional ``voices``
contract (a sorted tuple of voice ids, as :class:`tts.kokoro.KokoroEngine`
provides) works here; unknown or empty voice lists fail loudly.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

from tts.base import TTSEngine

#: Fixed audition sentence (self-made): varied phonemes and natural
#: prosody in about a hundred characters, so one file per voice is
#: enough to compare timbre without a long render.
SAMPLE_TEXT = (
    "The old lighthouse keeper whispered a secret to the curious child "
    "as the foghorn sounded at dawn."
)

#: Rate used for every sample (the palette compares timbre, not speed).
SAMPLE_SPEED = 1.0

#: Voice list written next to the WAVs (one id per line, audition order).
VOICES_LIST_FILENAME = "voices.txt"

#: Kokoro voice archive name inside a models dir (same file the engine
#: loads; the names are its npz keys, readable without a model load).
VOICES_ARCHIVE_FILENAME = "voices-v1.0.bin"


def engine_voice_names(models_dir: Path | str) -> list[str] | None:
    """Voice ids from the ``voices-v1.0.bin`` archive keys (no model load).

    Only the npz directory is read (array data stays on disk), so this is
    milliseconds and never touches onnxruntime. Returns ``None`` when the
    archive is absent or unreadable — callers fall back to a curated list.
    """
    archive = Path(models_dir) / VOICES_ARCHIVE_FILENAME
    try:
        import numpy as np

        with np.load(str(archive), allow_pickle=False) as data:
            names = [str(name) for name in data.files]
    except Exception:
        return None
    names = sorted(set(names))
    return names or None


@dataclass
class VoicesSample:
    """Outcome of :func:`sample_voices` (all paths under ``out_dir``)."""

    out_dir: Path
    voices: list[str] = field(default_factory=list)
    files: list[Path] = field(default_factory=list)


def list_voices(engine: TTSEngine) -> list[str]:
    """Return the engine's voice ids, sorted (audition order).

    :raises TypeError: the engine exposes no ``voices`` list (None or missing).
    """
    try:
        voices = engine.voices
    except AttributeError:
        voices = None
    if voices is None:
        raise TypeError(
            f"{type(engine).__name__} exposes no 'voices'; "
            "audition needs a voice list (see TTSEngine.voices)."
        )
    return sorted(str(name) for name in voices)


def sample_voices(
    engine: TTSEngine,
    out_dir: Path | str,
    text: str = SAMPLE_TEXT,
    speed: float = SAMPLE_SPEED,
    voices: Sequence[str] | None = None,
) -> VoicesSample:
    """Render ``text`` once per voice into ``out_dir``; return the result.

    File names are zero-padded audition order (``01-af_heart.wav``) plus
    :data:`VOICES_LIST_FILENAME` with one voice id per line. Pass
    ``voices`` to render a subset (the slow smoke test does this); the
    default renders every voice the engine lists.

    :raises ValueError: no voices to render, or a requested voice unknown.
    """
    import soundfile as sf

    available = list_voices(engine)
    wanted = list(available) if voices is None else [str(v) for v in voices]
    if not wanted:
        raise ValueError("no voices to sample (engine lists none).")
    unknown = [v for v in wanted if v not in available]
    if unknown:
        raise ValueError(f"unknown voice(s): {', '.join(unknown)}.")
    if not text.strip():
        raise ValueError("sample_voices needs non-empty text.")

    folder = Path(out_dir)
    folder.mkdir(parents=True, exist_ok=True)
    width = len(str(len(wanted)))
    files: list[Path] = []
    for index, voice in enumerate(wanted, start=1):
        audio = engine.synth(text, voice, float(speed))
        path = folder / f"{index:0{width}d}-{voice}.wav"
        sf.write(str(path), audio, engine.sample_rate, format="WAV", subtype="PCM_16")
        files.append(path)
    (folder / VOICES_LIST_FILENAME).write_text(
        "".join(f"{voice}\n" for voice in wanted), encoding="utf-8"
    )
    return VoicesSample(out_dir=folder, voices=wanted, files=files)
