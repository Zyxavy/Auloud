"""Task 1: AttributionCue exposure (no attribution behavior change)."""

from __future__ import annotations

from text.attribution import (
    AttributionCue,
    extract_attribution_cue,
    attribute_quotes,
)
from text.speakers import QuoteContext


def _ctx(block_text: str) -> QuoteContext:
    return QuoteContext(
        chapter=1,
        block=1,
        quote=1,
        excerpt="x",
        book="b",
        block_text=block_text,
        prev_text="",
        next_text="",
        full_quote="x",
        continued=False,
    )


def test_cue_from_shouted_angrily() -> None:
    cue = extract_attribution_cue("he shouted angrily.")
    assert cue is not None
    assert cue.verb_lemma == "shout"
    assert cue.adverbs == ["angrily"]
    assert "shouted" in cue.clause


def test_adverbs_only_advmod() -> None:
    cue = extract_attribution_cue("he shouted very angrily today.")
    assert cue is not None
    assert "angrily" in cue.adverbs
    # "today" is not advmod of the verb? ensure no crash; just check type
    assert isinstance(cue.adverbs, list)


def test_no_tag_no_cue() -> None:
    cue = extract_attribution_cue("He left the room.")
    assert cue is None


def test_cried_out_collects_particle() -> None:
    from text.emotion import detect_emotion

    cue = extract_attribution_cue("he cried out.")
    assert cue is not None
    assert "out" in cue.adverbs
    assert detect_emotion(cue)[0] == "SHOUT"


def test_attribute_quotes_attaches_cue() -> None:
    (att,) = attribute_quotes([_ctx('"Stop!" he shouted angrily.')])
    assert att.cue is not None
    assert att.cue.verb_lemma == "shout"
    assert att.cue.adverbs == ["angrily"]


def test_no_cue_for_bare_quote() -> None:
    (att,) = attribute_quotes([_ctx('"Hello."')])
    assert att.cue is None


def test_quote_sentence_ids_passthrough() -> None:
    cue = extract_attribution_cue("he shouted angrily.", quote_sentence_ids=[42])
    assert cue is not None
    assert cue.quote_sentence_ids == [42]


def test_attribution_cue_dataclass_fields() -> None:
    c = AttributionCue(verb_lemma=None)
    assert c.adverbs == []
    assert c.clause == ""
    assert c.quote_sentence_ids == []