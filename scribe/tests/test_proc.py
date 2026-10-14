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

"""``proc.no_window_kwargs``: console stays hidden on Windows, no-op elsewhere."""

from __future__ import annotations

import os

from proc import no_window_kwargs


def test_hides_console_on_windows(monkeypatch) -> None:
    monkeypatch.setattr(os, "name", "nt")
    assert no_window_kwargs()["creationflags"] & 0x08000000


def test_empty_kwargs_elsewhere(monkeypatch) -> None:
    monkeypatch.setattr(os, "name", "posix")
    assert no_window_kwargs() == {}
