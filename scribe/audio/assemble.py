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

"""Chapter buffer assembly and sentence timings (SW7).

For each chapter, build the audio as ONE continuous stream: sentence audio
(via the SW6 cache/engine interface) plus deterministic silences. Timings
come from RUNNING SAMPLE COUNTS on that same buffer, so they agree with
what :mod:`audio.encode` writes by construction (spec section 6 rule 5).

Pauses (source: ``docs/05-ScribeDesign.md`` section 9, which governs over
``docs/plans/Slice2.md`` SW7 — both agree: 250/500/800/1000 ms):

- inside a paragraph/quote, between sentences: 250 ms (sentence pause)
- after the last sentence of a para/quote block: 500 ms (paragraph pause)
- after each sentence of a heading block: 800 ms (heading pause)
- for a break block (scene divider, no sentences): 1000 ms of silence

``quote`` blocks are treated like ``para``: the design doc names only
sentence/paragraph/heading/break, and a block quote is paragraph-level
prose for pause purposes. A sentence's ``end_ms`` EXCLUDES its trailing
pause. The chapter ``duration_ms`` INCLUDES the final trailing pause.

Loudness (:func:`apply_loudness_gain`): one deterministic numpy scalar gain
per chapter to a peak target of -1 dBFS (``PEAK_TARGET``). Gain ONLY — no
compression, limiting, or time-stretch, so duration never changes, and the
same input array always yields bit-identical output. Note: the design doc
mentions ``ffmpeg loudnorm`` to -16 LUFS only as an example ("for
example"); a filter-based loudness pass is deliberately NOT used here
because it is version-dependent and complicates determinism. Single-voice
peak gain is enough for Slice 2; revisit perceptual leveling when multiple
voices arrive (Slice 4).

Memory: one chapter's float32 mono buffer is held (4 bytes/sample at
24 kHz, i.e. ~96 KB per second, ~5.8 MB per minute). Sentence arrays are
released after the single concatenate. Only one chapter is ever held;
:mod:`audio.encode` then streams that buffer to ffmpeg in small int16
chunks. Never write per-sentence files; never hold the whole book.

No network access; numpy only (already pinned).
"""

from __future__ import annotations

import math
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import numpy as np

from bundle.models import ChapterFile, Sentence
from tts.base import SAMPLE_RATE

#: Pause after a mid-block sentence (para/quote internal boundary).
PAUSE_SENTENCE_MS = 250
#: Pause after the last sentence of a para/quote block.
PAUSE_PARA_MS = 500
#: Pause after each sentence of a heading block.
PAUSE_HEADING_MS = 800
#: Silence for a break block (scene divider, carries no sentences).
PAUSE_BREAK_MS = 1000

#: Peak loudness target in dBFS (design-doc loudnorm example NOT used; see module doc).
PEAK_TARGET_DBFS = -1.0
#: Linear peak target: 10^(-1/20).
PEAK_TARGET = float(10.0 ** (PEAK_TARGET_DBFS / 20.0))

#: Block types that behave like paragraphs for pause purposes.
_PARA_LIKE = ("para", "quote")


@dataclass
class SentenceTiming:
    """Timing for one sentence: ``end_ms`` excludes the trailing pause."""

    sid: int
    start_ms: int
    end_ms: int


@dataclass
class AssembledChapter:
    """One chapter's continuous audio plus its sentence timings.

    ``pcm`` is mono float32 at ``sample_rate`` (the exact buffer
    :mod:`audio.encode` must write). ``timings`` are in document order.
    ``duration_ms`` covers the whole buffer INCLUDING trailing pauses.
    """

    pcm: np.ndarray
    timings: list[SentenceTiming] = field(default_factory=list)
    duration_ms: int = 0
    sample_count: int = 0
    sample_rate: int = SAMPLE_RATE


def samples_for_ms(duration_ms: int, sample_rate: int = SAMPLE_RATE) -> int:
    """Exact sample count for a pause length (no rounding loss)."""
    if duration_ms < 0:
        raise ValueError(f"pause must be non-negative, got {duration_ms}.")
    if sample_rate <= 0:
        raise ValueError(f"sample_rate must be positive, got {sample_rate}.")
    return int(duration_ms) * int(sample_rate) // 1000


def ms_for_samples(samples: int, sample_rate: int = SAMPLE_RATE) -> int:
    """Integer milliseconds for a sample offset (deterministic round-half-even).

    At 24 kHz, 1 ms is exactly 24 samples, so pause boundaries and any
    sentence length that is a multiple of 24 samples map exactly; other
    lengths round to the nearest millisecond.
    """
    if samples < 0:
        raise ValueError(f"samples must be non-negative, got {samples}.")
    if sample_rate <= 0:
        raise ValueError(f"sample_rate must be positive, got {sample_rate}.")
    return int(round(samples * 1000 / sample_rate))


def pause_after_sentence(block_type: str, is_last_in_block: bool) -> int:
    """Pause in ms after one sentence of ``block_type``.

    Heading sentences always take the heading pause; para/quote sentences
    take the sentence pause internally and the paragraph pause at the end
    of the block.
    """
    if block_type == "heading":
        return PAUSE_HEADING_MS
    if block_type in _PARA_LIKE:
        return PAUSE_PARA_MS if is_last_in_block else PAUSE_SENTENCE_MS
    raise ValueError(
        f"block type '{block_type}' carries no sentences; "
        "break blocks contribute silence without timings."
    )


def apply_loudness_gain(
    pcm: np.ndarray, target_peak: float = PEAK_TARGET
) -> np.ndarray:
    """Deterministic peak gain: scale so the peak hits ``target_peak``.

    Gain ONLY — one scalar multiply, no compression/limiting/time-stretch,
    so ``len(out) == len(pcm)`` always. Same input array gives bit-identical
    output (single numpy multiply, no randomness, no version-dependent
    filter). Digital silence (peak 0) and empty input return unchanged
    (as float32 copies). Input is never modified in place.
    """
    try:
        target = float(target_peak)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"target_peak must be a number, got {target_peak!r}.") from exc
    if not math.isfinite(target) or target <= 0:
        raise ValueError(f"target_peak must be finite and positive, got {target_peak!r}.")
    audio = np.asarray(pcm, dtype=np.float32).ravel()
    if audio.size == 0:
        return audio.copy()
    peak = float(np.max(np.abs(audio)))
    if not math.isfinite(peak):
        raise ValueError("pcm contains NaN or inf; refusing to normalize.")
    if peak == 0.0:
        return audio.copy()
    gain = target / peak
    return (audio * gain).astype(np.float32, copy=False)


def _check_sentence_audio(audio: np.ndarray, sentence: Sentence, chapter: Any) -> np.ndarray:
    """Validate one synth result: 1-D mono float32, non-empty, finite."""
    mono = np.asarray(audio, dtype=np.float32).ravel()
    if mono.size == 0:
        label = getattr(chapter, "chapter", "?")
        raise ValueError(
            f"chapter {label}: sentence {sentence.sid} synthesized to empty audio; "
            "empty sentences cannot take timings (need start_ms < end_ms)."
        )
    if not np.all(np.isfinite(mono)):
        label = getattr(chapter, "chapter", "?")
        raise ValueError(
            f"chapter {label}: sentence {sentence.sid} audio is not finite "
            "(NaN or inf from the engine)."
        )
    return mono


def assemble_chapter(
    chapter: ChapterFile,
    synth_fn: Callable[[Sentence], np.ndarray],
    *,
    sample_rate: int = SAMPLE_RATE,
) -> AssembledChapter:
    """Build one chapter's continuous buffer plus sentence timings.

    :param chapter: split chapter (SW4 output: blocks with ``sentences``,
        sids consecutive from 1 across the chapter).
    :param synth_fn: ``(sentence) -> mono float32 audio`` at ``sample_rate``.
        Callers wire this to ``tts.cache.get_or_synth`` (speaker-to-voice
        mapping lives with the caller/cast); tests pass a fake of known
        lengths. Called exactly once per sentence, in document order.
    :param sample_rate: bundle rate (must be 24 000; anything else raises).
    :returns: :class:`AssembledChapter` whose ``pcm`` is the exact buffer to
        encode and whose ``timings`` satisfy the SW2 validator rules
        (ordered, non-overlapping, ``end_ms`` excludes the trailing pause).
    :raises ValueError: empty chapter (no audio at all), empty/non-finite
        sentence audio, non-24 kHz rate, or sids out of order.
    """
    if int(sample_rate) != SAMPLE_RATE:
        raise ValueError(
            f"sample_rate must be {SAMPLE_RATE} (bundle contract), got {sample_rate}."
        )
    if chapter.blocks is None:
        raise ValueError("assemble_chapter needs EPUB-form blocks, not PDF pages.")

    parts: list[np.ndarray] = []
    timings: list[SentenceTiming] = []
    offset = 0
    seen_sids: list[int] = []

    for block in chapter.blocks:
        if block.type == "break":
            pause = np.zeros(samples_for_ms(PAUSE_BREAK_MS, sample_rate), dtype=np.float32)
            parts.append(pause)
            offset += pause.size
            continue
        if block.type not in ("heading", "para", "quote"):
            raise ValueError(f"unknown block type '{block.type}' (block {block.id}).")
        sentences = list(block.sentences)
        for pos, sentence in enumerate(sentences):
            audio = _check_sentence_audio(
                np.asarray(synth_fn(sentence), dtype=np.float32), sentence, chapter
            )
            start_ms = ms_for_samples(offset, sample_rate)
            offset += int(audio.size)
            end_ms = ms_for_samples(offset, sample_rate)
            timings.append(SentenceTiming(sid=sentence.sid, start_ms=start_ms, end_ms=end_ms))
            seen_sids.append(sentence.sid)
            parts.append(audio)
            pause_ms = pause_after_sentence(block.type, pos == len(sentences) - 1)
            pause = np.zeros(samples_for_ms(pause_ms, sample_rate), dtype=np.float32)
            parts.append(pause)
            offset += pause.size

    if not parts or offset == 0:
        raise ValueError(f"chapter {chapter.chapter}: no audio assembled (no sentences).")
    pcm = np.concatenate(parts).astype(np.float32, copy=False)
    duration_ms = ms_for_samples(offset, sample_rate)

    # Sids must be exactly 1..N in encounter order (validator rule).
    if seen_sids != list(range(1, len(seen_sids) + 1)):
        raise ValueError(
            f"chapter {chapter.chapter}: sids out of order: {seen_sids[:8]}"
            f"{'...' if len(seen_sids) > 8 else ''} (need 1..{len(seen_sids)})."
        )
    # Ordered and non-overlapping (validator rules); gaps are the pauses.
    prev_end: int | None = None
    prev_sid = 0
    for timing in timings:
        if timing.start_ms < 0 or not timing.start_ms < timing.end_ms <= duration_ms:
            raise AssertionError(
                f"chapter {chapter.chapter}: sentence {timing.sid} timing "
                f"[{timing.start_ms}, {timing.end_ms}] outside duration {duration_ms}."
            )
        if prev_end is None and timing.start_ms != 0:
            raise AssertionError(
                f"chapter {chapter.chapter}: first sentence starts at "
                f"{timing.start_ms}, need 0."
            )
        if prev_end is not None and timing.start_ms < prev_end:
            raise AssertionError(
                f"chapter {chapter.chapter}: sentence {timing.sid} overlaps "
                f"sentence {prev_sid}."
            )
        prev_end = timing.end_ms
        prev_sid = timing.sid

    return AssembledChapter(
        pcm=pcm,
        timings=timings,
        duration_ms=duration_ms,
        sample_count=int(offset),
        sample_rate=int(sample_rate),
    )
