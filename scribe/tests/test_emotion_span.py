"""Task 3: emotion span propagation (no attribution regression)."""

from __future__ import annotations

from text.attribution import AttributionCue
from text.emotion import propagate_emotion_tags, emotion_tag_for_sid


def test_both_quoted_sentences_tagged():
    cue = AttributionCue(verb_lemma="shout", quote_sentence_ids=[1, 2])
    tags = propagate_emotion_tags([cue])
    assert tags[1] == "SHOUT"
    assert tags[2] == "SHOUT"


def test_tag_before_quote():
    cue = AttributionCue(verb_lemma="shout", quote_sentence_ids=[10])
    assert propagate_emotion_tags([cue])[10] == "SHOUT"


def test_attribution_clause_stays_neutral():
    cue = AttributionCue(verb_lemma="shout", quote_sentence_ids=[1, 2])
    tags = propagate_emotion_tags([cue])
    # narration sentence (attribution clause) is never in the span
    assert 99 not in tags
    assert emotion_tag_for_sid(99, [cue]) == "NEUTRAL"


def test_no_cross_quote_leakage():
    c1 = AttributionCue(verb_lemma="shout", quote_sentence_ids=[1, 2])
    c2 = AttributionCue(verb_lemma="whisper", quote_sentence_ids=[4])
    tags = propagate_emotion_tags([c1, c2])
    assert tags[1] == "SHOUT"
    assert tags[2] == "SHOUT"
    assert tags[4] == "WHISPER"
    # sentence 3 sits between the two quotes and must not inherit a tag
    assert emotion_tag_for_sid(3, [c1, c2]) == "NEUTRAL"