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

"""Keep Windows awake while a job runs (Slice 6 UI4).

While any job runs (and the persisted setting is on), the server holds
``SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)`` so a
3-hour render is not slept away; the flag is released when idle. The
setting lives in ``<workspace>/.scribe-ui-settings.json`` as
``{"keep_awake": bool}`` (default on when the file is missing or
unreadable). Non-Windows or a missing API degrades cleanly: every call
is a best-effort no-op that never raises.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Any

#: Workspace settings filename (next to the jobs/ store, never committed).
SETTINGS_FILENAME = ".scribe-ui-settings.json"

#: ``SetThreadExecutionState`` flags (Win32, documented values).
_ES_CONTINUOUS = 0x80000000
_ES_SYSTEM_REQUIRED = 0x00000001

_lock = threading.Lock()
_holds = 0


def settings_path(workspace: Path | str) -> Path:
    """Settings file inside ``workspace`` (may be absent; default applies)."""
    return Path(workspace).resolve() / SETTINGS_FILENAME


def is_enabled(workspace: Path | str) -> bool:
    """True unless the persisted setting explicitly disables keep-awake."""
    path = settings_path(workspace)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return True
    if not isinstance(data, dict):
        return True
    value = data.get("keep_awake", True)
    return bool(value) if isinstance(value, bool) else True


def get_settings(workspace: Path | str) -> dict[str, Any]:
    """``{"keep_awake": bool}`` (default on; never raises)."""
    return {"keep_awake": is_enabled(workspace)}


def set_enabled(workspace: Path | str, enabled: bool) -> dict[str, Any]:
    """Persist the keep-awake toggle (atomic write; never raises)."""
    path = settings_path(workspace)
    payload = {"keep_awake": bool(enabled)}
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(payload, sort_keys=True) + "\n", encoding="utf-8")
        tmp.replace(path)
    except OSError:
        pass
    return dict(payload)


def _apply_system_required() -> None:
    """Ask Windows to stay awake (best effort; never raises)."""
    try:
        import ctypes  # noqa: PLC0415 -- local import keeps import time stdlib-only

        kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
        kernel32.SetThreadExecutionState(_ES_CONTINUOUS | _ES_SYSTEM_REQUIRED)
    except Exception:
        pass


def _apply_release() -> None:
    """Release the awake request (best effort; never raises)."""
    try:
        import ctypes  # noqa: PLC0415

        kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
        kernel32.SetThreadExecutionState(_ES_CONTINUOUS)
    except Exception:
        pass


def acquire(workspace: Path | str | None = None) -> None:
    """Hold one keep-awake reference (released by :func:`release`).

    The OS call runs only on the 0 -> 1 transition; nested holders share
    one system request. When ``workspace`` is given and the persisted
    setting disables keep-awake, this is a no-op (the matching
    :func:`release` stays balanced).
    """
    global _holds
    if workspace is not None and not is_enabled(workspace):
        return
    with _lock:
        if _holds == 0:
            _apply_system_required()
        _holds += 1


def release() -> None:
    """Drop one keep-awake reference (idempotent at zero; never raises)."""
    global _holds
    with _lock:
        if _holds <= 0:
            _holds = 0
            return
        _holds -= 1
        if _holds == 0:
            _apply_release()


def reset_for_tests() -> None:
    """Drop all references without an OS call (tests only)."""
    global _holds
    with _lock:
        _holds = 0
