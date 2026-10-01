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

"""TTS engine interface: one sentence in, mono audio out (SW6).

A new engine is a new :class:`TTSEngine` subclass only — no changes to
callers. Synthesis is one sentence at a time (per ``05-ScribeDesign.md``);
the sentence-audio cache in ``tts/cache.py`` owns batching, edge cases,
and reuse. Callers must go through ``cache.get_or_synth``, never the
engine directly, so empty sentences and very long sentences behave
consistently across engines.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

import numpy as np

#: Bundle-wide audio rate (Hz). Kokoro is native 24 kHz; any engine that
#: reports otherwise is resampled to this rate by its wrapper.
SAMPLE_RATE = 24_000


class TTSEngine(ABC):
    """Interface every TTS engine implements (D-023 names Kokoro-82M).

    ``synth`` takes a single non-empty sentence and returns mono float32
    audio in ``[-1, 1]``. ``pitch`` is deliberately *not* a synth
    parameter: it is part of the cache key (so a cast edit re-renders),
    and pitch shifting itself arrives in Slice 4 (``pyrubberband``).
    """

    @property
    @abstractmethod
    def sample_rate(self) -> int:
        """Native output rate in Hz (must be 24 000; see ``SAMPLE_RATE``)."""
        raise NotImplementedError

    @property
    @abstractmethod
    def engine_version(self) -> str:
        """Version string baked into the cache key (package + model)."""
        raise NotImplementedError

    @abstractmethod
    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        """Synthesize one non-empty sentence.

        :param text: single sentence, non-empty after stripping.
        :param voice: engine voice id (e.g. ``af_heart`` for Kokoro).
        :param speed: rate multiplier, typically 0.5-2.0.
        :returns: mono ``float32`` samples at :attr:`sample_rate`.
        """
        raise NotImplementedError
