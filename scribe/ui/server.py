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

"""UI2 server plumbing: bind guard, free-port pick, per-workspace lock, run.

Top-level imports stay stdlib-only (plus ``lock`` and ``typer``, both base
install) so port/lock tests run without the ``ui`` extra; FastAPI and
uvicorn are imported lazily inside :func:`run_server`, which the ``scribe
ui`` command only reaches after the friendly missing-extra hint.
"""

from __future__ import annotations

import json
import os
import socket
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import typer
from lock import STALE_AFTER_S, _pid_alive, _read_lock

#: The server never listens anywhere else (no ``--host`` flag on purpose).
BIND_HOST = "127.0.0.1"
#: First port tried; the next free one wins when busy (with a clear message).
DEFAULT_PORT = 8137
#: Highest port tried after ``DEFAULT_PORT`` before giving up.
MAX_PORT_OFFSET = 100
#: Lock file inside the workspace root marking a running ``scribe ui``.
UI_LOCK_FILENAME = ".scribe-ui.lock"


class UiLockError(ValueError):
    """Another ``scribe ui`` already holds this workspace (clean, no traceback)."""


@dataclass
class UiLock:
    """Held server lock: release with :meth:`release` (idempotent)."""

    workspace: Path
    path: Path
    pid: int

    def release(self) -> None:
        """Remove the lock file when it is still ours (never others')."""
        try:
            raw = self.path.read_text(encoding="utf-8")
            data = json.loads(raw)
        except (OSError, ValueError):
            return
        if isinstance(data, dict) and data.get("pid") == self.pid:
            try:
                self.path.unlink()
            except OSError:
                pass

    def __enter__(self) -> UiLock:
        return self

    def __exit__(self, *exc: object) -> None:
        self.release()


def assert_bind_host(host: str) -> None:
    """Refuse any non-local bind host (UI2 verify: never serve off-device)."""
    if host != BIND_HOST:
        raise ValueError(
            f"{host}: non-local-bind: the UI binds {BIND_HOST} only "
            "(no --host flag; remote access is out of scope)"
        )


def find_free_port(preferred: int, host: str = BIND_HOST) -> int:
    """Return ``preferred`` when free, else the next free port above it."""
    if not 1 <= preferred <= 65535:
        raise ValueError(f"{preferred}: bad-port: --port must be 1-65535")
    for port in range(preferred, min(preferred + MAX_PORT_OFFSET, 65536)):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind((host, port))
            except OSError:
                continue
            return port
    raise ValueError(f"{preferred}: bad-port: no free port nearby")


def acquire_ui_lock(workspace: Path | str) -> UiLock:
    """Take the single-instance-per-workspace lock (or raise :class:`UiLockError`).

    Same O_EXCL + ``{pid, started_at, cmd}`` + stale rules as the per-book
    :func:`lock.acquire_work_lock` pattern (PID liveness via
    :func:`lock._pid_alive`, 24 h age cap), but a second holder always fails,
    even in the same process: nested UI servers never make sense, so there
    is no same-PID re-entry shortcut here.

    Message shape is ``{file, rule, message}`` with rule ``ui-lock-held``.
    """
    folder = Path(workspace)
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / UI_LOCK_FILENAME
    mine = {
        "pid": os.getpid(),
        "started_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "cmd": "ui",
    }
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
    try:
        fd = os.open(str(path), flags, 0o644)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(mine, sort_keys=True) + "\n")
        return UiLock(workspace=folder, path=path, pid=mine["pid"])
    data = _read_lock(path)
    if data is None:
        try:
            path.unlink()
        except OSError:
            pass
        return acquire_ui_lock(folder)
    holder = data.get("pid")
    started = str(data.get("started_at") or "")
    age_s: float | None = None
    try:
        stamp = datetime.fromisoformat(started.replace("Z", "+00:00"))
        age_s = (datetime.now(timezone.utc) - stamp).total_seconds()
    except (ValueError, TypeError):
        age_s = None
    alive = _pid_alive(holder) if isinstance(holder, int) else False
    stale = (not alive) or (age_s is not None and age_s > STALE_AFTER_S)
    if stale:
        try:
            path.unlink()
        except OSError as exc:
            raise UiLockError(
                f"{path.name}: ui-lock-held: cannot clear stale lock "
                f"(holder pid {holder!r}): {exc}"
            ) from exc
        return acquire_ui_lock(folder)
    age_txt = f"{age_s:.0f}s" if age_s is not None else "unknown age"
    raise UiLockError(
        f"{path.name}: ui-lock-held: workspace is already served by pid {holder} "
        f"({age_txt} old); stop that `scribe ui` first"
    )


def build_url(port: int, host: str = BIND_HOST) -> str:
    """Public URL for a bound port (browser + printed line share it)."""
    return f"http://{host}:{port}/"


def run_server(workspace: Path | str, port: int = DEFAULT_PORT, open_browser: bool = True) -> None:
    """Serve the skeleton UI until Ctrl-C (holds the workspace lock).

    FastAPI/uvicorn import here, so importing this module never requires the
    ``ui`` extra; the CLI checks first and prints the install hint instead.
    """
    from ui.app import create_app

    import uvicorn

    assert_bind_host(BIND_HOST)
    folder = Path(workspace).resolve()
    folder.mkdir(parents=True, exist_ok=True)
    lock = acquire_ui_lock(folder)
    try:
        free = find_free_port(port, BIND_HOST)
        if free != port:
            typer.echo(f"Port {port} busy; using {free}.")
        app = create_app(folder)
        url = build_url(free)
        typer.echo(f"Serving Auloud Scribe UI at {url}")
        typer.echo(f"Workspace: {folder}")
        if open_browser:
            opened = False
            try:
                import webbrowser

                opened = bool(webbrowser.open(url))
            except Exception:
                opened = False
            if not opened:
                typer.echo(f"Open this URL in your browser: {url}")
        uvicorn.run(app, host=BIND_HOST, port=free)
    finally:
        lock.release()


# Re-exported names for ``scribe ui`` and the UI2 tests.
__all__ = [
    "BIND_HOST",
    "DEFAULT_PORT",
    "UI_LOCK_FILENAME",
    "UiLock",
    "UiLockError",
    "acquire_ui_lock",
    "assert_bind_host",
    "build_url",
    "find_free_port",
    "run_server",
]
