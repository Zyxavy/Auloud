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

"""PID identity for locks (Slice 17): existence is not identity.

Regression net for the 27948 incident (a dead holder's recycled PID read
"alive" forever): a live pid with a wrong timestamp must read dead.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from lock import _pid_alive, _pid_start_time, acquire_work_lock


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _reaped_pid() -> int:
    """A certainly-dead pid with no open handles left (close our own handle
    explicitly: an unreaped handle keeps the object queryable, which is
    exactly half of the incident)."""
    import ctypes

    probe = subprocess.Popen(
        [sys.executable, "-c", "pass"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    dead = probe.pid
    probe.wait()
    handle = getattr(probe, "_handle", None)
    if handle:
        ctypes.windll.kernel32.CloseHandle(handle)  # type: ignore[attr-defined]
        probe._handle = None  # type: ignore[attr-defined]
    assert dead is not None
    return dead


def test_start_time_resolves_for_self() -> None:
    start = _pid_start_time(os.getpid())
    assert start is not None
    assert abs(start - datetime.now(timezone.utc).timestamp()) < 24 * 3600


def test_self_with_current_timestamp_is_alive() -> None:
    assert _pid_alive(os.getpid(), started_at=_now_iso()) is True


def test_self_with_ancient_timestamp_is_dead() -> None:
    # The incident shape: pid exists, but it cannot be the lock holder.
    assert _pid_alive(os.getpid(), started_at="2000-01-01T00:00:00Z") is False


def test_reaped_child_reads_dead() -> None:
    dead = _reaped_pid()
    assert _pid_alive(dead) is False
    assert _pid_alive(dead, started_at=_now_iso()) is False


def test_legacy_call_without_timestamp_keeps_working() -> None:
    assert _pid_alive(os.getpid()) is True
    assert _pid_alive(0) is False
    assert _pid_alive(-4) is False


def test_forged_lock_with_recycled_identity_clears(tmp_path: Path) -> None:
    from ui.server import UI_LOCK_FILENAME, acquire_ui_lock

    folder = tmp_path / "ws"
    folder.mkdir()
    # Live pid (ourselves) but an ancient timestamp: a recycled PID must
    # not hold the lock.
    (folder / UI_LOCK_FILENAME).write_text(
        json.dumps({"pid": os.getpid(), "started_at": "2000-01-01T00:00:00Z", "cmd": "ui"}),
        encoding="utf-8",
    )
    with acquire_ui_lock(folder, cmd="ui") as lock:
        assert lock.pid == os.getpid()


def test_forged_lock_with_dead_pid_clears(tmp_path: Path) -> None:
    from ui.server import UI_LOCK_FILENAME, acquire_ui_lock

    dead = _reaped_pid()
    folder = tmp_path / "ws"
    folder.mkdir()
    (folder / UI_LOCK_FILENAME).write_text(
        json.dumps({"pid": dead, "started_at": _now_iso(), "cmd": "ui"}),
        encoding="utf-8",
    )
    with acquire_ui_lock(folder, cmd="ui"):
        pass


def test_live_holder_still_refuses(tmp_path: Path) -> None:
    from ui.server import UiLockError, acquire_ui_lock

    folder = tmp_path / "ws"
    folder.mkdir()
    with acquire_ui_lock(folder, cmd="ui"):
        try:
            acquire_ui_lock(folder, cmd="ui")
        except UiLockError as exc:
            assert "ui-lock-held" in str(exc)
        else:
            raise AssertionError("second holder must fail")


def test_work_lock_recycled_identity_clears(tmp_path: Path) -> None:
    folder = tmp_path / "book"
    folder.mkdir()
    (folder / ".scribe.lock").write_text(
        json.dumps({"pid": os.getpid(), "started_at": "2000-01-01T00:00:00Z", "cmd": "x"}),
        encoding="utf-8",
    )
    with acquire_work_lock(folder, cmd="x"):
        pass
