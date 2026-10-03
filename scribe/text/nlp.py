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

"""spaCy English pipeline wrapper (MV0 installs it, MV3 attribution uses it).

MV0 needs spaCy importable with ``en_core_web_sm`` working in both the
dev environment (``uv run`` from ``scribe/``) and the installed wheel,
plus a ``scribe doctor`` check proving it. MV3 will call
:func:`load_model` per paragraph for speech-verb subjects and ``PERSON``
entities — so this module owns the model name and the load path, and the
CLI stays a thin wrapper. ``import spacy`` is lazy (inside functions) to
keep every other ``scribe`` command's startup fast.
"""

from __future__ import annotations

import functools
import importlib.metadata
from typing import Any

#: English pipeline MV3 attributes with (URL-pinned in
#: ``pyproject.toml`` + ``uv.lock`` via ``tool.uv.sources`` because the
#: model is not on PyPI — ``doctor`` fails loudly with ``uv sync`` when
#: it is missing; ``spacy download`` is an offline fallback only).
MODEL_NAME = "en_core_web_sm"

#: Distribution name of the model wheel (what ``importlib.metadata``
#: reports the installed model version under).
MODEL_DIST_NAME = "en-core-web-sm"


def spacy_version() -> str | None:
    """Installed spaCy version, or None when spacy is not importable."""
    try:
        return importlib.metadata.version("spacy")
    except importlib.metadata.PackageNotFoundError:
        return None


def model_version() -> str | None:
    """Installed ``en_core_web_sm`` version, or None when absent."""
    try:
        return importlib.metadata.version(MODEL_DIST_NAME)
    except importlib.metadata.PackageNotFoundError:
        return None


@functools.lru_cache(maxsize=1)
def load_model() -> Any:
    """Load and return the English pipeline (raises ``OSError`` if missing).

    Blessed accessor for MV3: cached, so every call returns the same
    pipeline object - call it wherever a model is needed and reuse the
    result across paragraphs (loading takes a second and MV3 parses many
    paragraphs per chapter).
    """
    import spacy

    return spacy.load(MODEL_NAME)
