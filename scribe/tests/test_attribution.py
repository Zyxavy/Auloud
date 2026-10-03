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

"""MV4: attribution rules and confidence (``text/attribution.py``).

Each plan rule in isolation over synthetic paragraphs: explicit tags on
both sides (plus the following-first tie-break, inner-quote subjects, and
the person-entity down-weight), pronoun resolution (nearest match, gender
mismatch rejection, unknown-hint acceptance, no-history fallback),
alternation runs (pair completion, sustain, run breaks), continuation
chains (multi-block inherit, confidence inherit, cross-chapter break), and
fallback/unknown. Confidence levels are pinned on every case.
"""

from __future__ import annotations

from text.attribution import (
    RULE_ALTERNATION,
    RULE_CONTINUATION,
    RULE_EXPLICIT,
    RULE_FALLBACK,
    RULE_PRONOUN,
    RULE_UNKNOWN,
    Attribution,
    attribute_quotes,
)
from text.speakers import QuoteContext


def _ctx(
    block: int,
    quote: int,
    block_text: str,
    *,
    chapter: int = 1,
    book: str = "test-book",
    continued: bool = False,
) -> QuoteContext:
    return QuoteContext(
        chapter=chapter,
        block=block,
        quote=quote,
        excerpt="x",
        book=book,
        block_text=block_text,
        prev_text="",
        next_text="",
        full_quote="x",
        continued=continued,
    )


def _run(*contexts: QuoteContext) -> list[Attribution]:
    return attribute_quotes(list(contexts))


# ---------------------------------------------------------------------------
# Rule 1: explicit tag adjacent to the quote, either side (high)
# ---------------------------------------------------------------------------


def test_explicit_tag_after_quote_is_high() -> None:
    (att,) = _run(_ctx(8, 1, '"We should leave," Alice said.'))
    assert (att.speaker, att.confidence, att.rule) == ("Alice", "high", RULE_EXPLICIT)
    assert att.key == ("test-book", 1, 8, 1)


def test_explicit_tag_before_quote_is_high() -> None:
    (att,) = _run(_ctx(8, 1, 'Alice said, "We should leave."'))
    assert (att.speaker, att.confidence, att.rule) == ("Alice", "high", RULE_EXPLICIT)


def test_two_quotes_take_their_own_following_tag() -> None:
    first, second = _run(
        _ctx(8, 1, '"A," Alice said. "B," Robert said.'),
        _ctx(8, 2, '"A," Alice said. "B," Robert said.'),
    )
    assert (first.speaker, first.rule) == ("Alice", RULE_EXPLICIT)
    assert (second.speaker, second.rule) == ("Robert", RULE_EXPLICIT)
    assert first.confidence == second.confidence == "high"


def test_inner_quote_verb_subject_never_beats_the_tag() -> None:
    # MV3 leaves inner subjects in ("Alice said" here); the scoped re-parse
    # never looks inside the quote, so whole-block parsing would wrongly
    # pick Alice at high while the tag resolves to Robert at medium.
    _first, second = _run(
        _ctx(7, 1, '"Hello," Robert said.'),
        _ctx(8, 1, '"Alice said she would come," he whispered.'),
    )
    assert (second.speaker, second.confidence, second.rule) == (
        "Robert",
        "medium",
        RULE_PRONOUN,
    )


def test_person_entity_listener_never_beats_the_tag_subject() -> None:
    # "Bob" is emitted by MV3 for discovery but must not win attribution.
    (att,) = _run(_ctx(8, 1, '"Hi," Alice whispered softly to Bob.'))
    assert (att.speaker, att.confidence, att.rule) == ("Alice", "high", RULE_EXPLICIT)


def test_first_person_tag_falls_through_to_unknown() -> None:
    # "I said" is a pronoun tag but not he/she: MV5 first_person owns it.
    (att,) = _run(_ctx(8, 1, '"I am here," I said.'))
    assert (att.speaker, att.confidence, att.rule) == ("unknown", "low", RULE_UNKNOWN)


# ---------------------------------------------------------------------------
# Rule 2: pronoun tag resolved to the most recent matching speaker (medium)
# ---------------------------------------------------------------------------


def test_pronoun_resolves_to_matching_speaker() -> None:
    first, second = _run(
        _ctx(8, 1, '"Hello," Robert said.'),
        _ctx(9, 1, '"Hi," he said.'),
    )
    assert (first.speaker, first.rule) == ("Robert", RULE_EXPLICIT)
    assert (second.speaker, second.confidence, second.rule) == ("Robert", "medium", RULE_PRONOUN)


def test_pronoun_prefers_most_recent_gender_match() -> None:
    *_, third = _run(
        _ctx(8, 1, '"Hello," Robert said.'),
        _ctx(9, 1, '"Hi," Alice said.'),
        _ctx(10, 1, '"Hey," she said.'),
    )
    assert (third.speaker, third.confidence, third.rule) == ("Alice", "medium", RULE_PRONOUN)


def test_pronoun_gender_mismatch_rejects_and_falls_back() -> None:
    # "he" cannot be Alice (known female): no pronoun hit, fallback keeps Alice at low.
    *_, second = _run(
        _ctx(8, 1, '"Hello," Alice said.'),
        _ctx(9, 1, '"Hi," he said.'),
    )
    assert (second.speaker, second.confidence, second.rule) == ("Alice", "low", RULE_FALLBACK)


def test_pronoun_accepts_unknown_gender_speaker() -> None:
    # Bob has no gender hint (not in the name lists): agreement is vacuous.
    *_, second = _run(
        _ctx(8, 1, '"Hello," Bob said.'),
        _ctx(9, 1, '"Hi," he said.'),
    )
    assert (second.speaker, second.confidence, second.rule) == ("Bob", "medium", RULE_PRONOUN)


def test_pronoun_without_history_is_unknown() -> None:
    (att,) = _run(_ctx(9, 1, '"Hi," he said.'))
    assert (att.speaker, att.confidence, att.rule) == ("unknown", "low", RULE_UNKNOWN)


# ---------------------------------------------------------------------------
# Rule 3: alternation in dialogue-only runs between two known speakers
# ---------------------------------------------------------------------------


def test_alternation_starts_after_an_explicit_pair_and_sustains() -> None:
    first, second, third, fourth = _run(
        _ctx(1, 1, '"Hello," Alice said.'),
        _ctx(2, 1, '"Hi," Robert said.'),
        _ctx(3, 1, '"How are you?"'),
        _ctx(4, 1, '"Fine, thanks."'),
    )
    assert (first.rule, second.rule) == (RULE_EXPLICIT, RULE_EXPLICIT)
    assert (third.speaker, third.confidence, third.rule) == ("Alice", "medium", RULE_ALTERNATION)
    assert (fourth.speaker, fourth.confidence, fourth.rule) == (
        "Robert",
        "medium",
        RULE_ALTERNATION,
    )


def test_alternation_needs_two_distinct_known_speakers() -> None:
    # Same speaker twice: no alternation signal, fallback keeps Alice at low.
    *_, third = _run(
        _ctx(1, 1, '"Hello," Alice said.'),
        _ctx(2, 1, '"Again," Alice said.'),
        _ctx(3, 1, '"Bare."'),
    )
    assert (third.speaker, third.confidence, third.rule) == ("Alice", "low", RULE_FALLBACK)


def test_action_beat_breaks_the_run_into_fallback() -> None:
    # The "Hmm." quote has a preceding narration beat: its block is not
    # dialogue-only and its rule is fallback, so the next bare quote must
    # not alternate either (prev rule is fallback, prev block not bare).
    *_, beat, bare = _run(
        _ctx(1, 1, '"Hello," Alice said.'),
        _ctx(2, 1, '"Hi," Robert said.'),
        _ctx(3, 1, 'He left. "Hmm."'),
        _ctx(4, 1, '"Oh."'),
    )
    assert (beat.speaker, beat.rule) == ("Robert", RULE_FALLBACK)
    assert (bare.speaker, bare.confidence, bare.rule) == ("Robert", "low", RULE_FALLBACK)


# ---------------------------------------------------------------------------
# Rule 4: continuation of a multi-paragraph quote keeps its speaker
# ---------------------------------------------------------------------------


def test_continuation_inherits_speaker_and_confidence() -> None:
    head, link = _run(
        _ctx(1, 1, '"First part," Alice said.'),
        _ctx(2, 1, '"second part."', continued=True),
    )
    assert (head.speaker, head.confidence, head.rule) == ("Alice", "high", RULE_EXPLICIT)
    assert (link.speaker, link.confidence, link.rule) == ("Alice", "high", RULE_CONTINUATION)


def test_continuation_chain_links_block_to_block() -> None:
    *_, second, third = _run(
        _ctx(1, 1, '"First part," Alice said.'),
        _ctx(2, 1, '"second part,"', continued=True),
        _ctx(3, 1, '"third part."', continued=True),
    )
    assert (second.speaker, second.rule) == ("Alice", RULE_CONTINUATION)
    assert (third.speaker, third.rule) == ("Alice", RULE_CONTINUATION)


def test_continuation_inherits_low_confidence_honestly() -> None:
    # The head was a fallback guess; the link repeats it at low, not high.
    *_, link = _run(
        _ctx(1, 1, '"Hello," Alice said.'),
        _ctx(2, 1, '"Bare."'),
        _ctx(3, 1, '"More."', continued=True),
    )
    assert link.rule == RULE_CONTINUATION
    assert (link.speaker, link.confidence) == ("Alice", "low")


def test_continuation_does_not_cross_chapters() -> None:
    *_, other_chapter = _run(
        _ctx(1, 1, '"Hello," Alice said.'),
        _ctx(2, 1, '"More."', chapter=2, continued=True),
    )
    assert (other_chapter.speaker, other_chapter.confidence, other_chapter.rule) == (
        "Alice",
        "low",
        RULE_FALLBACK,
    )


# ---------------------------------------------------------------------------
# Rule 5: fallback to the previous dialogue speaker, else unknown (low)
# ---------------------------------------------------------------------------


def test_first_bare_quote_is_unknown_low() -> None:
    (att,) = _run(_ctx(3, 1, '"Hello."'))
    assert (att.speaker, att.confidence, att.rule) == ("unknown", "low", RULE_UNKNOWN)


def test_fallback_repeats_previous_speaker_at_low() -> None:
    *_, bare = _run(
        _ctx(1, 1, '"Hi," Alice said.'),
        _ctx(2, 1, '"Oh."'),
    )
    assert (bare.speaker, bare.confidence, bare.rule) == ("Alice", "low", RULE_FALLBACK)


def test_quoteless_block_text_is_unknown() -> None:
    (att,) = _run(_ctx(3, 1, "He left the room."))
    assert (att.speaker, att.confidence, att.rule) == ("unknown", "low", RULE_UNKNOWN)
