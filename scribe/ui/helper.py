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

"""HP1: headless helper entry point for the v3 native app (Slice 15).

The helper is the same FastAPI app as ``scribe ui`` with three differences:
the token is required on every request (not just state-changing ones), it
prints one ready line instead of opening a browser, and a watchdog exits it
when the parent process disappears. Detached build/draft jobs are separate
processes and are unaffected by helper exit. ``scribe ui`` behavior is
unchanged (it keeps the default token scope).
"""

from __future__ import annotations

import json
import logging
import socket
import threading
from collections.abc import Callable
from pathlib import Path
from typing import Any

from lock import _pid_alive
from ui.server import BIND_HOST, acquire_ui_lock, find_free_port

#: Environment variable carrying the per-run token (never argv: visible).
HELPER_ENV_TOKEN = "AULOUD_HELPER_TOKEN"
#: Helper API version spoken here (Slice 16 pins the typed client to it).
HELPER_API_VERSION = 1
#: Token required on every request in helper mode (vs state-changing only).
ALL_METHODS = frozenset({"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
#: Parent-liveness poll interval for the watchdog thread.
WATCHDOG_POLL_S = 5.0


class HelperError(ValueError):
    """Helper startup or runtime failure (clean, no traceback)."""


def format_ready(port: int, versions: dict[str, Any]) -> str:
    """The single ready line printed once the helper serves (JSON, one line)."""
    return json.dumps({"ready": True, "port": port, "versions": versions}, sort_keys=True)


def helper_versions() -> dict[str, Any]:
    """Version triple for the ready line (Scribe, spec, helper API)."""
    from bundle.validate import ALLOWED_SPEC_VERSIONS
    from cli import __version__ as scribe_version

    return {
        "scribe": scribe_version,
        "spec": list(ALLOWED_SPEC_VERSIONS),
        "api": HELPER_API_VERSION,
    }


def pick_port(preferred: int) -> int:
    """Resolve the serve port: 0 means OS-assigned, else next-free-up."""
    if preferred == 0:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind((BIND_HOST, 0))
            return int(sock.getsockname()[1])
    return find_free_port(preferred, BIND_HOST)


def parent_gone(parent_pid: int, *, pid_alive_fn: Callable[[int], bool] | None = None) -> bool:
    """True when the parent process id is dead (watchdog predicate)."""
    if isinstance(parent_pid, bool) or not isinstance(parent_pid, int) or parent_pid <= 0:
        raise HelperError(f"bad parent pid {parent_pid!r} (need a positive integer)")
    alive = pid_alive_fn or _pid_alive
    return not alive(parent_pid)


def build_helper_app(workspace: Path | str, token: str) -> Any:
    """The UI app with token required on every request (helper mode)."""
    from ui.app import create_app

    if not token or not token.strip():
        raise HelperError("missing token: set AULOUD_HELPER_TOKEN (never empty)")
    return create_app(workspace, token=token, token_methods=ALL_METHODS)


def watchdog_once(
    server: Any, parent_pid: int, *, pid_alive_fn: Callable[[int], bool] | None = None
) -> bool:
    """One watchdog check: stop the server when the parent died (tested)."""
    if parent_gone(parent_pid, pid_alive_fn=pid_alive_fn):
        server.should_exit = True
        return True
    return False


def _watch_parent(
    server: Any,
    parent_pid: int,
    poll_s: float = WATCHDOG_POLL_S,
    pid_alive_fn: Callable[[int], bool] | None = None,
) -> None:
    """Watchdog thread body: poll until the parent disappears, then stop."""
    import time

    while not watchdog_once(server, parent_pid, pid_alive_fn=pid_alive_fn):
        time.sleep(poll_s)


def run_helper(
    *,
    workspace: Path | str,
    log_dir: Path | str | None,
    port: int,
    parent_pid: int | None,
    token: str | None,
    serve_fn: Callable[..., None] | None = None,
    watchdog_poll_s: float = WATCHDOG_POLL_S,
) -> None:
    """Serve the helper until Ctrl-C or the parent disappears (HP1).

    Models follow the existing convention (``<workspace>/models``, else
    ``models``); job history lives under the workspace; only the log
    folder is a separate argument. ``serve_fn`` injects the server runner
    (tests); production runs uvicorn with a watchdog thread when
    ``parent_pid`` is given.
    """
    folder = Path(workspace).resolve()
    folder.mkdir(parents=True, exist_ok=True)
    logs = Path(log_dir).resolve() if log_dir is not None else folder / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    lock = acquire_ui_lock(folder, cmd="helper")
    handler = logging.FileHandler(logs / "helper.log", encoding="utf-8")
    logging.getLogger().addHandler(handler)
    try:
        app = build_helper_app(folder, token or "")
        picked = pick_port(port)
        print(format_ready(picked, helper_versions()), flush=True)
        if serve_fn is not None:
            serve_fn(app, BIND_HOST, picked)
            return
        import uvicorn

        config = uvicorn.Config(app, host=BIND_HOST, port=picked, log_level="info")
        server = uvicorn.Server(config)
        watcher: threading.Thread | None = None
        if parent_pid is not None:
            watcher = threading.Thread(
                target=_watch_parent,
                args=(server, parent_pid, watchdog_poll_s),
                daemon=True,
            )
            watcher.start()
        server.run()
    finally:
        logging.getLogger().removeHandler(handler)
        handler.close()
        lock.release()
