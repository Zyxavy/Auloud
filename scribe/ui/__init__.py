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

"""Localhost web UI (Slice 6 UI2 skeleton; real views land in UI3+).

The ``scribe ui`` command (see ``cli.py``) lazy-imports this package so the
plain CLI install stays light. ``security.py`` is stdlib-only (safe to import
without the ``ui`` extra); ``app.py`` needs FastAPI and ``server.py`` needs
uvicorn only inside ``run_server``.
"""
