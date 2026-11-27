"""Unit tests for detect_emotion (Task 2)."""

from __future__ import annotations

from text.attribution import AttributionCue
from text.emotion import detect_emotion


def _cue(verb=None, adverbs=None, clause=""):
    return AttributionCue(verb_lemma=verb, adverbs=adverbs or [], clause=clause)


def test_shout_verb():
    assert detect_emotion(_cue("shout"))[0] == "SHOUT"


def test_whisper_verb():
    assert detect_emotion(_cue("whisper"))[0] == "WHISPER"


def test_neutral_verb_plus_angrily():
    assert detect_emotion(_cue("say", ["angrily"], "he said angrily."))[0] == "ANGRY"


def test_neutral_verb_plus_sadly():
    assert detect_emotion(_cue("say", ["sadly"], "he said sadly."))[0] == "SAD"


def test_no_signal_neutral():
    assert detect_emotion(_cue("say"))[0] == "NEUTRAL"
    assert detect_emotion(_cue(None))[0] == "NEUTRAL"


def test_verb_wins_over_conflicting_adverb():
    assert detect_emotion(_cue("shout", ["softly"], "he shouted softly."))[0] == "SHOUT"


def test_cried_out_shout():
    assert detect_emotion(_cue("cry", ["out"], "he cried out."))[0] == "SHOUT"


def test_bare_cried_neutral():
    assert detect_emotion(_cue("cry"))[0] == "NEUTRAL"


def test_bare_cried_with_adverb():
    assert detect_emotion(_cue("cry", ["angrily"], "he cried angrily."))[0] == "ANGRY"


def test_negated_adverb_neutral():
    assert detect_emotion(_cue("say", ["angrily"], "he said, not angrily."))[0] == "NEUTRAL"


def test_confidence_verb_gt_adverb():
    v = detect_emotion(_cue("shout"))[1]
    a = detect_emotion(_cue("say", ["angrily"], "he said angrily."))[1]
    assert v > a


def test_none_cue():
    assert detect_emotion(None)[0] == "NEUTRAL"  # type: ignore[arg-type]
