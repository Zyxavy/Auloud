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

"""Per-book exclusive lock (Slice 6 UI1): one render/draft per book.

A UI render and a CLI render must never touch the same book at once (plan
risk table). ``acquire_work_lock(work_dir)`` creates ``.scribe.lock`` in
``<work_root>/<book-id>/`` atomically (``O_CREAT | O_EXCL``). The holder
writes JSON ``{pid, started_at, cmd}``; the file is removed on release
(``finally`` in callers, so crashes still clean on the next acquire path).

Stale-lock detection (documented choice): PID liveness first, lock age
second. Liveness uses ``ctypes`` ``OpenProcess`` on Windows (no new
dependency; ``os.kill(pid, 0)`` is not reliable for liveness on Win32) and
``os.kill(pid, 0)`` on POSIX. A lock whose PID is gone is stale and is
replaced (old file removed, new lock taken). A lock whose PID is alive is
honored, unless its age exceeds ``STALE_AFTER_S`` (default 24 h: a render
never legitimately holds the lock that long, so it must be a dead holder
whose PID got recycled). No heartbeat thread: Windows file lifetimes plus
a second concurrent run failing fast matter more than sub-minute staleness
for a localhost UI plus CLI.

Second concurrent runs raise :class:`WorkLockError` naming the file and
the rule (``{file, rule, message}`` shape, no traceback at the CLI).
"""

from __future__ import annotations

import ctypes
import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

#: Lock file name inside ``<work_root>/<book-id>/``.
LOCK_FILENAME = ".scribe.lock"
#: Locks older than this are stale even when the PID looks alive (PID reuse).
STALE_AFTER_S = 24 * 3600


class WorkLockError(ValueError):
    """Another run holds this book's work lock (clean, no traceback)."""


@dataclass
class WorkLock:
    """Held lock: release with :meth:`release` (idempotent)."""

    work_dir: Path
    path: Path
    pid: int
    owned: bool = True

    def release(self) -> None:
        """Remove the lock file when it is still ours (never others').

        Nested same-PID re-entry (build -> draft) returns ``owned=False``;
        releasing a non-owned handle is a no-op so the outer holder keeps
        exclusivity until its own release.
        """
        if not self.owned:
            return
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

    def __enter__(self) -> WorkLock:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.release()


def _pid_alive(pid: int) -> bool:
    """True when a process with ``pid`` plausibly exists (no new deps).

    Windows: ``OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)`` via
    ``ctypes`` (documented Win32 approach; ``os.kill`` semantics differ on
    Windows). POSIX: ``os.kill(pid, 0)`` (``EPERM`` means alive, ``ESRCH``
    means gone).
    """
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        return False
    try:
        if os.name == "nt":
            kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
            PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
            handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
            if not handle:
                return False
            kernel32.CloseHandle(handle)
            return True
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except OSError:
        return False
    except Exception:
        return False


def _read_lock(path: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def acquire_work_lock(work_dir: Path | str, *, cmd: str = "") -> WorkLock:
    """Take the exclusive per-book lock (or raise :class:`WorkLockError`).

    :param work_dir: ``<work_root>/<book-id>/`` (created when missing).
    :param cmd: operator label for the lock file (``"draft"``/``"build"``).
    :raises WorkLockError: another live run holds the lock. Message shape
        is ``{file, rule, message}``: file is the lock path, rule is
        ``work-lock-held``, message names the holder PID and age.
    """
    folder = Path(work_dir)
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / LOCK_FILENAME
    mine = {
        "pid": os.getpid(),
        "started_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "cmd": cmd,
    }
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
    try:
        fd = os.open(str(path), flags, 0o644)
    except FileExistsError:
        pass
    else:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(mine, sort_keys=True) + "\n")
        return WorkLock(work_dir=folder, path=path, pid=mine["pid"])
    # Lock file exists: stale or held?
    data = _read_lock(path)
    if data is None:
        # Unreadable/corrupt lock: treat as stale (a kill mid-write can
        # leave torn bytes; the next holder replaces it).
        try:
            path.unlink()
        except OSError:
            pass
        return acquire_work_lock(folder, cmd=cmd)
    holder = data.get("pid")
    started = str(data.get("started_at") or "")
    age_s: float | None = None
    try:
        stamp = datetime.fromisoformat(started.replace("Z", "+00:00"))
        age_s = (datetime.now(timezone.utc) - stamp).total_seconds()
    except (ValueError, TypeError):
        age_s = None
    # Same-process re-entry (build -> ensure_script -> draft): the holder
    # is us, so return a non-owned handle (its release is a no-op; the
    # outer holder keeps exclusivity until its own release).
    if isinstance(holder, int) and holder == os.getpid():
        return WorkLock(work_dir=folder, path=path, pid=mine["pid"], owned=False)
    alive = _pid_alive(holder) if isinstance(holder, int) else False
    stale = (not alive) or (age_s is not None and age_s > STALE_AFTER_S)
    if stale:
        try:
            path.unlink()
        except OSError as exc:
            raise WorkLockError(
                f"{path.name}: work-lock-held: cannot clear stale lock "
                f"(holder pid {holder!r}): {exc}"
            ) from exc
        return acquire_work_lock(folder, cmd=cmd)
    age_txt = f"{age_s:.0f}s" if age_s is not None else "unknown age"
    raise WorkLockError(
        f"{path.name}: work-lock-held: book is locked by pid {holder} "
        f"({age_txt} old, cmd {str(data.get('cmd') or '?')}); "
        "another draft/build is running"
    )
