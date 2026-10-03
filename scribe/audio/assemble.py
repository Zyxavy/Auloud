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
``docs/plans/Slice2.md`` SW7 — both agree: 250/500/800/1000 ms — plus the
MV7 tag pause):

- inside a paragraph/quote, between sentences: 250 ms (sentence pause)
- between a dialogue sentence and the narration tag split from the SAME
  original sentence (``Sentence.split_pair`` link from ``text.dialogue``):
  100 ms (tag pause); normal pauses everywhere else
- after the last sentence of a para/quote block: 500 ms (paragraph pause)
- after each sentence of a heading block: 800 ms (heading pause)
- for a break block (scene divider, no sentences): 1000 ms of silence,
  except before the first sentence (see below)

``quote`` blocks are treated like ``para``: the design doc names only
sentence/paragraph/heading/break, and a block quote is paragraph-level
prose for pause purposes. A sentence's ``end_ms`` EXCLUDES its trailing
pause. The chapter ``duration_ms`` INCLUDES the final trailing pause.

Spec section 6 rule 1 requires the first sentence's ``start_ms`` to be 0,
so no audio (not even break silence) may precede the first sentence: a
``break`` block before any sentence contributes NOTHING to the buffer
(the block itself still exists in the text JSON downstream; only its
pre-first-sentence silence is skipped). Mid- and trailing breaks keep
their 1000 ms.

Loudness: per-voice peak leveling at assembly time (MV7) plus the chapter
peak limit (:func:`apply_loudness_gain`). Each voice present in the
chapter is measured (peak over its sentence audios, pauses excluded) and
scaled by one gain so every voice peaks at the same target; the chapter
gain then still caps the whole buffer at -1 dBFS (a no-op when leveling
already hit it, a guard otherwise). Gain ONLY — no compression, limiting,
or time-stretch, so duration never changes, and the same input always
yields bit-identical output. Leveling lives here, never in the sentence
cache, so gain changes cannot invalidate cached audio. Note: the design
doc mentions ``ffmpeg loudnorm`` to -16 LUFS only as an example ("for
example"); a filter-based loudness pass is deliberately NOT used here
because it is version-dependent and complicates determinism. Perceptual
(LUFS) leveling stays a future option; v1 equalizes peaks.

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
#: Pause between a dialogue sentence and the narration tag split from the
#: SAME original sentence (adjacent ``split_pair`` match, same block).
#: Fixed at ~100 ms in v1; the natural next knob is a CLI flag or cast
#: entry, but no caller reads one yet, so this fingerprinted constant
#: (build re-renders when its live value changes) is the whole surface.
PAUSE_TAG_MS = 100

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
    ``voice_gains`` maps each voice id seen (via ``voice_of``) to the
    applied per-voice gain (1.0 for silence-only voices); empty when no
    ``voice_of`` was given.
    """

    pcm: np.ndarray
    timings: list[SentenceTiming] = field(default_factory=list)
    duration_ms: int = 0
    sample_count: int = 0
    sample_rate: int = SAMPLE_RATE
    voice_gains: dict[str, float] = field(default_factory=dict)


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


def voice_peak_levels(
    audios_by_voice: dict[str, list[np.ndarray]],
) -> dict[str, float]:
    """Representative loudness per voice: peak absolute sample.

    Measured over each voice's sentence audios only (silence pauses are
    added after leveling, so padding never skews the measurement).
    Voices with no audio measure 0.0 (their gain stays 1.0 downstream).
    """
    peaks: dict[str, float] = {}
    for voice, chunks in audios_by_voice.items():
        peak = 0.0
        for chunk in chunks:
            mono = np.asarray(chunk, dtype=np.float32).ravel()
            if mono.size:
                peak = max(peak, float(np.max(np.abs(mono))))
        peaks[voice] = peak
    return peaks


def gains_for_peaks(
    peaks: dict[str, float], target_peak: float = PEAK_TARGET
) -> dict[str, float]:
    """One gain per voice: ``target_peak / peak`` (silence peaks give 1.0).

    Peak, not RMS, by decision: the same metric as the chapter peak
    limiter end to end, a deterministic single pass with no silence
    threshold to tune (RMS over raw synth output would weight each
    engine's leading/trailing silence differently), and exact test
    numbers. Same peaks always give bit-identical gains (pure float
    division, no randomness).
    """
    try:
        target = float(target_peak)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"target_peak must be a number, got {target_peak!r}.") from exc
    if not math.isfinite(target) or target <= 0:
        raise ValueError(f"target_peak must be finite and positive, got {target_peak!r}.")
    gains: dict[str, float] = {}
    for voice, peak in peaks.items():
        try:
            level = float(peak)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"voice {voice!r}: peak must be a number, got {peak!r}.") from exc
        if not math.isfinite(level):
            raise ValueError(f"voice {voice!r}: peak is not finite.")
        gains[voice] = target / level if level > 0 else 1.0
    return gains


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
    voice_of: Callable[[Sentence], str] | None = None,
) -> AssembledChapter:
    """Build one chapter's continuous buffer plus sentence timings.

    :param chapter: split chapter (SW4 output: blocks with ``sentences``,
        sids consecutive from 1 across the chapter).
    :param synth_fn: ``(sentence) -> mono float32 audio`` at ``sample_rate``.
        Callers wire this to ``tts.cache.get_or_synth`` (speaker-to-voice
        mapping lives with the caller/cast); tests pass a fake of known
        lengths. Called exactly once per sentence, in document order.
    :param sample_rate: bundle rate (must be 24 000; anything else raises).
    :param voice_of: ``(sentence) -> voice id`` for MV7 per-voice leveling
        (callers pass the resolved voice, not the raw speaker). ``None``
        keeps the Slice 2 path: every gain is 1.0 and ``voice_gains`` is
        empty. Pauses never scale (they are digital silence either way).
    :returns: :class:`AssembledChapter` whose ``pcm`` is the exact buffer to
        encode and whose ``timings`` satisfy the SW2 validator rules
        (ordered, non-overlapping, ``end_ms`` excludes the trailing pause).
        Adjacent same-block sentences sharing a ``split_pair`` (the two
        halves of one quote-split sentence) take the short
        :data:`PAUSE_TAG_MS` pause between them.
    :raises ValueError: empty chapter (no audio at all), empty/non-finite
        sentence audio, non-24 kHz rate, sids out of order, or degenerate
        timings (e.g. sub-millisecond audio rounding to start_ms == end_ms).
    """
    if int(sample_rate) != SAMPLE_RATE:
        raise ValueError(
            f"sample_rate must be {SAMPLE_RATE} (bundle contract), got {sample_rate}."
        )
    if chapter.blocks is None:
        raise ValueError("assemble_chapter needs EPUB-form blocks, not PDF pages.")

    # First pass: synthesize every sentence in document order (exactly one
    # synth call per sentence). Gains need all of a voice's audio before
    # any sample is placed, while timings only depend on lengths, so the
    # buffer itself is built in the second pass below.
    plans: list[tuple[Any, list[tuple[Sentence, np.ndarray, str | None]]]] = []
    seen_sids: list[int] = []
    for block in chapter.blocks:
        if block.type == "break":
            plans.append((block, []))
            continue
        if block.type not in ("heading", "para", "quote"):
            raise ValueError(f"unknown block type '{block.type}' (block {block.id}).")
        items: list[tuple[Sentence, np.ndarray, str | None]] = []
        for sentence in block.sentences:
            audio = _check_sentence_audio(
                np.asarray(synth_fn(sentence), dtype=np.float32), sentence, chapter
            )
            voice = voice_of(sentence) if voice_of is not None else None
            items.append((sentence, audio, voice))
            seen_sids.append(sentence.sid)
        plans.append((block, items))

    # Per-voice leveling (MV7, never in the cache): one gain per voice from
    # its representative peak, applied to that voice's sentence segments.
    voice_gains: dict[str, float] = {}
    if voice_of is not None:
        by_voice: dict[str, list[np.ndarray]] = {}
        for _, items in plans:
            for _, audio, voice in items:
                if voice is not None:
                    by_voice.setdefault(voice, []).append(audio)
        voice_gains = gains_for_peaks(voice_peak_levels(by_voice))

    parts: list[np.ndarray] = []
    timings: list[SentenceTiming] = []
    offset = 0

    for block, items in plans:
        if block.type == "break":
            if not timings:
                # Spec section 6 rule 1: the first sentence starts at 0,
                # so pre-first-sentence break silence is dropped from the
                # audio (the block stays in the text JSON downstream).
                continue
            pause = np.zeros(samples_for_ms(PAUSE_BREAK_MS, sample_rate), dtype=np.float32)
            parts.append(pause)
            offset += pause.size
            continue
        for pos, (sentence, audio, voice) in enumerate(items):
            gain = voice_gains.get(voice, 1.0) if voice is not None else 1.0
            scaled = audio if gain == 1.0 else (audio * gain).astype(np.float32)
            start_ms = ms_for_samples(offset, sample_rate)
            offset += int(scaled.size)
            end_ms = ms_for_samples(offset, sample_rate)
            timings.append(SentenceTiming(sid=sentence.sid, start_ms=start_ms, end_ms=end_ms))
            parts.append(scaled)
            if pos + 1 < len(items):
                following = items[pos + 1][0]
                if (
                    sentence.split_pair is not None
                    and sentence.split_pair == following.split_pair
                ):
                    pause_ms = PAUSE_TAG_MS
                else:
                    pause_ms = pause_after_sentence(block.type, False)
            else:
                pause_ms = pause_after_sentence(block.type, True)
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
    # Ordered and non-overlapping (spec section 6 rule 3 allows gaps, so a
    # leading break block may shift the first sentence's start past 0).
    # Violations raise ValueError (catchable) rather than AssertionError.
    prev_end: int | None = None
    prev_sid = 0
    for timing in timings:
        if timing.start_ms < 0 or not timing.start_ms < timing.end_ms <= duration_ms:
            raise ValueError(
                f"chapter {chapter.chapter}: sentence {timing.sid} timing "
                f"[{timing.start_ms}, {timing.end_ms}] outside duration {duration_ms}."
            )
        if prev_end is not None and timing.start_ms < prev_end:
            raise ValueError(
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
        voice_gains=dict(voice_gains),
    )
