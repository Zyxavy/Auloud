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

"""The installed package must contain every flat module.

The wheel remaps this flat tree into a ``scribe/`` package (pyproject
``force-include``), and the installed ``cli.py`` puts its own directory on
``sys.path`` so flat imports such as ``from build import ...`` resolve
there. A module or package missing from that list breaks only the installed
CLI: the tests run from the source tree (``pythonpath = ["."]``) and would
never notice (the ``proc.py`` miss in commit 9ba053d).
"""

from __future__ import annotations

import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

#: Root directories that hold .py files but are not shipped.
NOT_SHIPPED = {"dev", "tests", "scratch"}


def test_force_include_covers_every_flat_module() -> None:
    config = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))
    included = set(config["tool"]["hatch"]["build"]["targets"]["wheel"]["force-include"])

    modules = {path.name for path in ROOT.glob("*.py")}
    packages = {
        path.name
        for path in ROOT.iterdir()
        if path.is_dir() and path.name not in NOT_SHIPPED and next(path.glob("*.py"), None)
    }
    missing = sorted((modules | packages) - included)
    assert not missing, f"not in wheel force-include: {missing}"

    absent = sorted(source for source in included if not (ROOT / source).exists())
    assert not absent, f"force-include lists paths that do not exist: {absent}"
