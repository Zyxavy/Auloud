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

"""Windows-quiet subprocess spawns (no console window flashes).

Console-subsystem exes (ffmpeg, ffprobe) pop a window when started from
a windowless parent unless ``CREATE_NO_WINDOW`` is passed per ``Popen``:
the flag is per-spawn, never inherited. Plain ``DETACHED_PROCESS`` is
wrong here (we pipe and wait on these children), so this is the one
flag only, matching the Rust helper spawn.
"""

from __future__ import annotations

import os
import subprocess
from typing import Any


def no_window_kwargs() -> dict[str, Any]:
    """``Popen`` kwargs hiding the console on Windows; ``{}`` elsewhere."""
    if os.name != "nt":
        return {}
    flags = 0
    for attr, fallback in (("CREATE_NO_WINDOW", 0x08000000),):
        flags |= int(getattr(subprocess, attr, fallback))
    return {"creationflags": flags}
