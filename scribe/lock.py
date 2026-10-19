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
whose PID got recycled). PIDs alone cannot prove identity (Windows reuses
them, and a held handle keeps a dead object queryable), so acquirers also
compare the holder's process start time against the lock timestamp
(:func:`_pid_start_time`, one-sided within ``PID_IDENTITY_WINDOW_S``): a
recycled PID is always younger than the lock that named the process it
replaced, so it fails the match and the lock clears, while a holder that had
already been running when it took the lock is honored however long it had
been up. A dead PID kept queryable by a
lingering handle reports its original start time (passing that match), so
the exit code is checked as well (anything but ``STILL_ACTIVE`` means
gone). No heartbeat thread: Windows file lifetimes plus a second
concurrent run failing fast matter more than sub-minute staleness for a
localhost UI plus CLI.

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
#: A lock holder counts as the same process unless it STARTED more than this
#: after the lock timestamp (clock-skew slack; a recycled PID is younger than
#: the lock, so it fails and the lock clears). See :func:`_pid_alive`.
PID_IDENTITY_WINDOW_S = 120.0


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


def _pid_alive(pid: int, *, started_at: str | float | None = None) -> bool:
    """True when a process with ``pid`` plausibly exists (no new deps).

    Windows: ``OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION)`` via
    ``ctypes`` (documented Win32 approach; ``os.kill`` semantics differ on
    Windows). POSIX: ``os.kill(pid, 0)`` (``EPERM`` means alive, ``ESRCH``
    means gone).

    Existence is not identity: PIDs get recycled, and a held handle keeps a
    dead object queryable. When ``started_at`` (lock timestamp, ISO string
    or unix seconds) is given, the holder must not have STARTED AFTER it
    (plus ``PID_IDENTITY_WINDOW_S`` slack for clock skew): a recycled PID is
    always younger than the lock that named the process it replaced, so it
    fails the match, while a holder that had already been running when it
    took the lock is honored however long it had been up. Unknowable start
    times fall back to plain existence (legacy behavior).

    A dead process with an open handle elsewhere still answers existence
    probes with its original start time (passing the identity match), so on
    Windows the exit code is checked too: anything but ``STILL_ACTIVE``
    means gone, however queryable the handle is.
    """
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        return False
    try:
        if os.name == "nt":
            import ctypes.wintypes

            kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
            PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
            STILL_ACTIVE = 259
            handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
            if not handle:
                return False
            try:
                code = ctypes.wintypes.DWORD()
                if kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                    if code.value != STILL_ACTIVE:
                        return False
            finally:
                kernel32.CloseHandle(handle)
            exists = True
        else:
            os.kill(pid, 0)
            exists = True
    except ProcessLookupError:
        return False
    except PermissionError:
        exists = True
    except OSError:
        return False
    except Exception:
        return False
    if not exists or started_at is None:
        return exists
    try:
        if isinstance(started_at, (int, float)) and not isinstance(started_at, bool):
            stamp = float(started_at)
        else:
            stamp = (
                datetime.fromisoformat(str(started_at).replace("Z", "+00:00"))
                .replace(tzinfo=timezone.utc)
                .timestamp()
            )
    except (ValueError, TypeError, OverflowError):
        return True
    start = _pid_start_time(pid)
    if start is None:
        return True
    return start <= stamp + PID_IDENTITY_WINDOW_S


def _pid_start_time(pid: int) -> float | None:
    """Process start as unix seconds, or None when unknowable (no new deps).

    Windows: ``GetProcessTimes`` creation time via ``ctypes``. POSIX:
    ``/proc/<pid>/stat`` field 22 against ``/proc/stat`` btime. Anything
    unexpected means None (the caller falls back to plain existence).
    """
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        return None
    try:
        if os.name == "nt":
            import ctypes.wintypes

            kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
            handle = kernel32.OpenProcess(0x1000, False, pid)
            if not handle:
                return None
            try:
                creation = ctypes.wintypes.FILETIME()
                discard = ctypes.wintypes.FILETIME()
                if not kernel32.GetProcessTimes(
                    handle,
                    ctypes.byref(creation),
                    ctypes.byref(discard),
                    ctypes.byref(discard),
                    ctypes.byref(discard),
                ):
                    return None
                ticks = (creation.dwHighDateTime << 32) | creation.dwLowDateTime
                return ticks / 10_000_000 - 11_644_473_600.0
            finally:
                kernel32.CloseHandle(handle)
        with open(f"/proc/{pid}/stat", encoding="utf-8") as handle:
            after = handle.read().rsplit(")", 1)[1].split()
        start_ticks = int(after[20])
        ticks_per_sec = os.sysconf(os.sysconf_names["SC_CLK_TCK"])
        with open("/proc/stat", encoding="utf-8") as handle:
            btime = next(
                int(line.split()[1]) for line in handle if line.startswith("btime")
            )
        return btime + start_ticks / ticks_per_sec
    except Exception:
        return None


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
    alive = (
        _pid_alive(holder, started_at=started) if isinstance(holder, int) else False
    )
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
