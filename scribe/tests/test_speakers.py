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

"""MV3: speaker candidate extraction (``text/speakers.py``).

Covers the speech-verb list, determiner/title normalization, tag-subject /
titled / descriptive / person-entity sources, alias rules (each rule plus
clustering), pronoun-adjacent gender hints, the no-tag empty case, the
``candidates_for`` predictor-slot shape, and the ``QuoteContext`` bridge
built from real dialogue output. Synthetic and C&P-fragment-shaped text
only. No confidence is asserted anywhere: MV4 owns high/medium/low.
"""

from __future__ import annotations

from bundle.models import Block, ChapterFile, Sentence
from dev.eval_speakers import QuoteContext
from text.dialogue import split_chapter_dialogue
from text.speakers import (
    SOURCES,
    SPEECH_VERBS,
    Candidate,
    are_aliases,
    build_quote_contexts,
    candidates_by_quote,
    candidates_for,
    cluster_aliases,
    extract_candidates,
    gender_hint_for,
    normalize_name,
)


def _surfaces(cands: list[Candidate]) -> list[str]:
    return [c.surface for c in cands]


def _by_surface(cands: list[Candidate]) -> dict[str, Candidate]:
    return {c.surface: c for c in cands}


# ---------------------------------------------------------------------------
# Speech verbs and normalization
# ---------------------------------------------------------------------------


def test_verb_list_covers_plan_set() -> None:
    required = {
        "say",
        "ask",
        "reply",
        "cry",
        "whisper",
        "shout",
        "mutter",
        "think",
        "answer",
        "exclaim",
        "call",
        "add",
        "continue",
        "begin",
    }
    assert required <= set(SPEECH_VERBS)


def test_normalize_strips_determiners_titles_and_case() -> None:
    assert normalize_name("the Queen") == normalize_name("Queen") == "queen"
    assert normalize_name("Mr. Darcy") == normalize_name("Darcy") == "darcy"
    assert normalize_name("  Alice  ") == "alice"
    assert normalize_name("Mrs. Bennet") == "bennet"
    assert normalize_name("the young man") == "young man"
    assert normalize_name("Dr. Watson") == "watson"


# ---------------------------------------------------------------------------
# Tag subjects: known sentences yield the expected candidate
# ---------------------------------------------------------------------------


def test_said_alice_yields_tag_subject() -> None:
    cands = extract_candidates("said Alice.")
    assert _surfaces(cands) == ["Alice"]
    first = cands[0]
    assert first.normalized == "alice"
    assert first.source == "tag-subject"
    assert first.gender == "female"


def test_queen_shouted_is_descriptive_with_determiner_key() -> None:
    cands = _by_surface(extract_candidates("the Queen shouted."))
    assert "the Queen" in cands
    assert cands["the Queen"].normalized == "queen"
    assert cands["the Queen"].source == "descriptive"


def test_he_muttered_yields_pronoun_candidate() -> None:
    cands = extract_candidates("he muttered.")
    assert _surfaces(cands) == ["he"]
    assert cands[0].source == "tag-subject"
    assert cands[0].gender == "male"


def test_she_said_is_female() -> None:
    cands = extract_candidates("she said.")
    assert _surfaces(cands) == ["she"]
    assert cands[0].gender == "female"


def test_he_thought_counts_as_speech_verb() -> None:
    cands = extract_candidates('"I knew it," he thought.')
    assert "he" in _surfaces(cands)
    assert _by_surface(cands)["he"].gender == "male"


def test_crime_fragment_shouted_tag_yields_he() -> None:
    cands = _by_surface(extract_candidates('"Hey there, German hatter," he shouted.'))
    assert "he" in cands
    assert cands["he"].source == "tag-subject"
    assert cands["he"].gender == "male"


def test_no_tag_sentence_yields_no_candidate() -> None:
    assert extract_candidates("The room was empty.") == []
    assert extract_candidates("Birds sang in the trees.") == []
    assert extract_candidates("") == []
    assert extract_candidates("   ") == []


def test_candidates_carry_no_confidence() -> None:
    for cand in extract_candidates('said Alice. The Queen shouted. "Hi," he muttered.'):
        assert not hasattr(cand, "confidence")


def test_sources_stay_within_documented_set() -> None:
    texts = [
        "said Alice.",
        "the Queen shouted.",
        "he muttered.",
        "Mr. Darcy said hello.",
        "the young man said hello.",
        "Alice whispered softly to Bob.",
    ]
    for text in texts:
        for cand in extract_candidates(text):
            assert cand.source in SOURCES


# ---------------------------------------------------------------------------
# Named vs descriptive characters (do not rely on PERSON alone)
# ---------------------------------------------------------------------------


def test_titled_names_recovered_despite_truncated_ner() -> None:
    by = _by_surface(extract_candidates("Mr. Darcy said hello."))
    assert "Mr. Darcy" in by
    assert by["Mr. Darcy"].source == "titled"
    assert by["Mr. Darcy"].normalized == "darcy"


def test_all_plan_titles_recognized() -> None:
    for text, surface in [
        ("Mrs. Bennet cried out.", "Mrs. Bennet"),
        ("Miss Bennet said hello.", "Miss Bennet"),
        ("Ms. Smith asked a question.", "Ms. Smith"),
        ("Dr. Watson exclaimed loudly.", "Dr. Watson"),
    ]:
        assert surface in _surfaces(extract_candidates(text)), text


def test_descriptive_subjects_kept_despite_missing_person() -> None:
    for text, surface in [
        ("the young man said hello.", "the young man"),
        ("drunken man shouted hey.", "drunken man"),
        ("the Hatter said hi.", "the Hatter"),
    ]:
        by = _by_surface(extract_candidates(text))
        assert surface in by, text
        assert by[surface].source == "descriptive", text


def test_person_entity_covers_non_subject_mention() -> None:
    by = _by_surface(extract_candidates("Alice whispered softly to Bob."))
    assert by["Alice"].source == "tag-subject"
    assert "Bob" in by
    assert by["Bob"].source == "person-entity"


def test_conjoined_subjects_yield_both() -> None:
    surfaces = _surfaces(extract_candidates("Mr. Darcy and Miss Bennet said hello."))
    assert "Mr. Darcy" in surfaces
    assert "Miss Bennet" in surfaces


# ---------------------------------------------------------------------------
# Alias clustering rules
# ---------------------------------------------------------------------------


def test_alias_case_insensitive() -> None:
    assert are_aliases("Alice", "alice")


def test_alias_determiner_stripped() -> None:
    assert are_aliases("the Queen", "Queen")


def test_alias_title_plus_name() -> None:
    assert are_aliases("Mr. Darcy", "Darcy")
    assert are_aliases("Mrs. Bennet", "Bennet")


def test_alias_last_name_match() -> None:
    assert are_aliases("Elizabeth Bennet", "Bennet")
    assert not are_aliases("Elizabeth Bennet", "Darcy")


def test_alias_rejects_empty_and_unrelated() -> None:
    assert not are_aliases("Alice", "Bob")
    assert not are_aliases("", "Alice")
    assert not are_aliases("Alice", "")


def test_cluster_aliases_groups_variants() -> None:
    clusters = cluster_aliases(["Mr. Darcy", "Darcy", "Alice", "alice"])
    assert clusters == {"alice": ["Alice", "alice"], "darcy": ["Darcy", "Mr. Darcy"]}


def test_cluster_aliases_is_deterministic() -> None:
    names = ["Darcy", "Mr. Darcy", "alice", "Alice", "Bennet", "Elizabeth Bennet"]
    assert cluster_aliases(names) == cluster_aliases(list(reversed(names)))


# ---------------------------------------------------------------------------
# Gender hints (never hard decisions)
# ---------------------------------------------------------------------------


def test_gender_pronoun_surfaces_direct() -> None:
    assert gender_hint_for("he") == "male"
    assert gender_hint_for("She") == "female"
    assert gender_hint_for("his") == "male"
    assert gender_hint_for("her") == "female"


def test_gender_name_list_with_documented_gap() -> None:
    assert gender_hint_for("Alice") == "female"
    assert gender_hint_for("Elizabeth Bennet") == "female"
    # Bob is not in the small list: incompleteness is explicit, hint stays unknown.
    assert gender_hint_for("Bob") == "unknown"


def test_gender_pronoun_adjacent_name() -> None:
    assert gender_hint_for("Mr. Darcy", "Mr. Darcy smiled. He said hello.") == "male"
    assert gender_hint_for("Darcy", "Mr. Darcy smiled. He said hello.") == "male"
    assert gender_hint_for("the Queen", "The Queen shouted loudly.") == "unknown"
    assert gender_hint_for("Raskolnikov", "Raskolnikov thought he knew it.") == "male"


# ---------------------------------------------------------------------------
# Predictor-slot shape: candidates_for(quote context)
# ---------------------------------------------------------------------------


def test_candidates_for_reads_block_text() -> None:
    ctx = QuoteContext(
        chapter=1,
        block=8,
        quote=1,
        excerpt="We should leave,",
        book="test-book",
        block_text='"We should leave," she said.',
        prev_text="",
        next_text="The room was empty.",
        full_quote='"We should leave,"',
    )
    by = _by_surface(candidates_for(ctx))
    assert "she" in by
    assert by["she"].gender == "female"


def test_candidates_for_empty_block_text_yields_nothing() -> None:
    ctx = QuoteContext(chapter=1, block=8, quote=1, excerpt="We should leave,")
    assert candidates_for(ctx) == []


# ---------------------------------------------------------------------------
# Bridge: QuoteContext from real dialogue output
# ---------------------------------------------------------------------------


def _chapter(*paras: tuple[int, str]) -> ChapterFile:
    return ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Test",
        duration_ms=0,
        blocks=[
            Block(
                id=block_id,
                type="para",
                sentences=[
                    Sentence(sid=1, speaker="narrator", start_ms=0, end_ms=0, text=text, spans=[])
                ],
            )
            for block_id, text in paras
        ],
    )


def test_bridge_builds_contexts_with_tag_sentences() -> None:
    chapter = _chapter(
        (8, '"We should leave," she said.'),
        (9, "The room was empty."),
        (10, '"Hey there," he shouted.'),
    )
    dialogue = split_chapter_dialogue(chapter, chapter_index=1)
    contexts = build_quote_contexts(dialogue, book="test-book")
    assert [(c.chapter, c.block, c.quote) for c in contexts] == [(1, 8, 1), (1, 10, 1)]
    first, second = contexts
    assert first.book == "test-book"
    assert first.block_text == '"We should leave," she said.'
    assert first.prev_text == ""
    assert first.next_text == "The room was empty."
    assert first.full_quote == '"We should leave,"'
    assert second.prev_text == "The room was empty."
    assert second.next_text == ""
    assert second.full_quote == '"Hey there,"'


def test_bridge_keys_join_gold_shape() -> None:
    chapter = _chapter(
        (8, '"We should leave," she said.'),
        (9, "The room was empty."),
        (10, '"Hey there," he shouted.'),
    )
    dialogue = split_chapter_dialogue(chapter, chapter_index=1)
    contexts = build_quote_contexts(dialogue, book="test-book")
    by_key = {(c.book, c.chapter, c.block, c.quote): c for c in contexts}
    per_quote = candidates_by_quote(dialogue, book="test-book")
    assert set(by_key) == set(per_quote)
    assert "she" in _surfaces(per_quote[("test-book", 1, 8, 1)])
    assert "he" in _surfaces(per_quote[("test-book", 1, 10, 1)])


def test_bridge_is_deterministic() -> None:
    chapter = _chapter((8, '"We should leave," she said.'))
    dialogue = split_chapter_dialogue(chapter, chapter_index=1)
    assert build_quote_contexts(dialogue, book="b") == build_quote_contexts(dialogue, book="b")
    assert candidates_by_quote(dialogue, book="b") == candidates_by_quote(dialogue, book="b")
