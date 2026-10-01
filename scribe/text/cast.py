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

"""cast.yaml read/write (SW5+).

SW5 writes a minimal narrator-only cast: one ``narrator`` entry pointing at
the D-023 voice (Kokoro ``af_heart``, speed 1.0). Character voices, aliases
and overrides arrive in Slice 4 (dialogue/speakers); the mapping stays a
plain ``{speaker: {engine, voice, speed}}`` dict so it extends cleanly.

Writes use ``yaml.safe_dump(..., sort_keys=True)`` so the file is
byte-identical across runs (SW5 requires twice-run identical output, so no
timestamps are ever written).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import yaml

NARRATOR = "narrator"
NARRATOR_ENGINE = "kokoro"  # D-023: kokoro-onnx runtime.
NARRATOR_VOICE = "af_heart"  # D-023 narrator voice.
NARRATOR_SPEED = 1.0


def default_cast() -> dict[str, dict[str, Any]]:
    """Minimal SW5 cast: narrator only (D-023 voice, speed 1.0)."""
    return {
        NARRATOR: {
            "engine": NARRATOR_ENGINE,
            "voice": NARRATOR_VOICE,
            "speed": NARRATOR_SPEED,
        }
    }


def write_cast(path: Path | str, cast: dict[str, dict[str, Any]] | None = None) -> Path:
    """Write ``cast.yaml`` deterministically; returns the path written."""
    out = Path(path)
    data = default_cast() if cast is None else cast
    text = yaml.safe_dump(data, sort_keys=True, allow_unicode=True)
    out.write_text(text, encoding="utf-8")
    return out


def read_cast(path: Path | str) -> dict[str, Any]:
    """Read a ``cast.yaml`` back as plain dicts (safe_load, no code exec)."""
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError(f"{path}: cast.yaml must be a mapping of speaker to voice")
    return raw
