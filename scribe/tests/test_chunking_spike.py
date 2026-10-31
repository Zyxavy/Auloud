# Auloud Scribe — chunking spike logic tests (Task 7, no model needed).
#
# Covers the pure parts of experiments/chunking_spike.py: same-speaker
# grouping, phoneme-span alignment, and boundary math from timings. The model
# path (synthesis, A/B WAVs, the real-chapter run) stays in the spike script.

from __future__ import annotations

from collections import namedtuple

import numpy as np

from experiments.chunking_spike import (
    acoustic_onset,
    align_phoneme_spans,
    boundary_error_ms,
    group_sentences,
    sentence_speech_spans,
)

Timing = namedtuple("Timing", "phoneme start end")


def test_group_same_speaker_short_run_is_one_group():
    sentences = [("narrator", "One. "), ("narrator", "Two. "), ("narrator", "Three. ")]
    assert group_sentences(sentences) == [[0, 1, 2]]


def test_group_splits_on_speaker_change():
    sentences = [("narrator", "One. "), ("Sebastian", "Two. "), ("narrator", "Three. ")]
    assert group_sentences(sentences) == [[0], [1], [2]]


def test_group_splits_when_char_budget_would_overflow():
    sentences = [("a", "x" * 6), ("a", "y" * 6)]
    assert group_sentences(sentences, max_chars=10) == [[0], [1]]


def test_group_keeps_run_that_exactly_fills_budget():
    sentences = [("a", "x" * 5), ("a", "y" * 5)]
    assert group_sentences(sentences, max_chars=10) == [[0, 1]]


def test_group_single_over_budget_sentence_is_its_own_group():
    sentences = [("a", "x" * 20), ("a", "y")]
    assert group_sentences(sentences, max_chars=10) == [[0], [1]]


def test_group_measures_stripped_text():
    # Trailing whitespace must not count against the budget.
    sentences = [("a", "x" * 5 + "   "), ("a", "y" * 5)]
    assert group_sentences(sentences, max_chars=10) == [[0, 1]]


def test_group_realistic_alternating_speakers():
    sentences = [
        ("narrator", "He waited."),
        ("Kyle", "Go."),
        ("Kyle", "Now."),
        ("narrator", "He left."),
    ]
    assert group_sentences(sentences) == [[0], [1, 2], [3]]


def test_align_spans_exact_with_inter_sentence_spaces():
    assert align_phoneme_spans("abc def", ["abc", "def"]) == [(0, 3), (4, 7)]


def test_align_spans_skips_leading_spaces():
    assert align_phoneme_spans("  abc  def", ["abc", "def"]) == [(2, 5), (7, 10)]


def test_align_spans_returns_none_when_a_sentence_does_not_match():
    assert align_phoneme_spans("abc xyz", ["abc", "def"]) is None


def test_align_spans_last_sentence_runs_to_the_end():
    chunk = "hEllo wOrld"
    spans = align_phoneme_spans(chunk, ["hEllo", "wOrld"])
    assert spans[-1][1] == len(chunk)


def test_sentence_speech_spans_from_timings():
    timings = [
        Timing("a", 0.0, 0.1),
        Timing("b", 0.1, 0.2),
        Timing(" ", 0.2, 0.25),
        Timing("c", 0.25, 0.4),
    ]
    assert sentence_speech_spans(timings, [(0, 2), (3, 4)]) == [(0.0, 0.2), (0.25, 0.4)]


def test_sentence_speech_spans_empty_span_is_none():
    timings = [Timing("a", 0.0, 0.1)]
    assert sentence_speech_spans(timings, [(0, 0)]) == [None]


def test_boundary_sample_from_aligned_spans_is_next_sentence_start():
    timings = [Timing("a", 0.0, 0.1), Timing("b", 0.1, 0.2), Timing("c", 0.5, 0.6)]
    spans = align_phoneme_spans("abc", ["ab", "c"])
    assert spans == [(0, 2), (2, 3)]
    sample = int(timings[spans[1][0]].start * 24000)
    assert sample == int(0.5 * 24000)


def _silence_then_tone(sample_rate=24000, quiet_s=0.5, tone_s=0.2):
    audio = np.zeros(int(sample_rate * (quiet_s + tone_s)), dtype=np.float32)
    onset = int(sample_rate * quiet_s)
    audio[onset:] = 0.8 * np.sin(np.linspace(0, 40 * np.pi, audio.size - onset))
    return audio, onset


def test_acoustic_onset_finds_the_tone_start():
    audio, onset = _silence_then_tone()
    found = acoustic_onset(audio, sample=onset + 240, sample_rate=24000)
    assert found is not None and abs(found - onset) < 60


def test_boundary_error_is_zero_when_split_sits_on_the_onset():
    audio, onset = _silence_then_tone()
    assert abs(boundary_error_ms(audio, onset, 24000)) < 3.0


def test_boundary_error_sign_is_late_for_a_split_past_the_onset():
    audio, onset = _silence_then_tone()
    error = boundary_error_ms(audio, onset + 240, 24000)  # 10 ms late
    assert error is not None and 0 < error < 20


def test_acoustic_onset_none_on_all_silence():
    audio = np.zeros(2000, dtype=np.float32)
    assert acoustic_onset(audio, 1000, 24000) is None
