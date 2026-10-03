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

"""MV2: dialogue detection and sentence splitting (``text/dialogue.py``).

Covers straight/curly doubles, single-quote mode, nested singles,
apostrophes, multi-paragraph continuation, multi-sentence quotes staying
one entry, exact round trips (the Player spacing rule: concatenate as
stored), unbalanced/stray handling, the scare-quote rule (gold block 12
contributes zero quotes), consecutive sids with preserved block ids, and
the gold-shaped chapter (blocks 8/10/11 quote keys, block 12 empty).
Synthetic text only; the real EPUB check runs outside the suite.
"""

from __future__ import annotations

import logging

from bundle.models import Block, ChapterFile, Sentence, Span
from text.dialogue import (
    DIALOGUE,
    NARRATION,
    ChapterDialogue,
    is_scare_quote,
    split_chapter_dialogue,
    split_paragraph_dialogue,
)

LD = "\u201c"
RD = "\u201d"
RS = "\u2019"


def _para(block_id: int, text: str, kind: str = "para") -> Block:
    return Block(
        id=block_id,
        type=kind,
        sentences=[Sentence(sid=1, speaker="narrator", start_ms=0, end_ms=0, text=text, spans=[])],
    )


def _chapter(*blocks: Block) -> ChapterFile:
    return ChapterFile(
        spec_version="1.0", chapter=1, title="Test", duration_ms=0, blocks=list(blocks)
    )


def _kinds(result: ChapterDialogue) -> list[str]:
    return [t.kind for t in result.tagged]


# ---------------------------------------------------------------------------
# Mixed sentences split at quote boundaries
# ---------------------------------------------------------------------------


def test_straight_mixed_splits_with_quote_key() -> None:
    result = split_paragraph_dialogue('"We should leave," she said.', chapter=1, block=8)
    assert [(s.text, s.kind, s.quote) for s in result.sentences] == [
        ('"We should leave,"', DIALOGUE, 1),
        (" she said.", NARRATION, None),
    ]
    assert result.quotes[0].key == (1, 8, 1)
    assert "".join(s.text for s in result.sentences) == '"We should leave," she said.'


def test_curly_mixed_splits_with_quote_key() -> None:
    text = f"{LD}I am tired. Let us rest.{RD} Then they slept."
    result = split_paragraph_dialogue(text, chapter=1, block=8)
    assert [s.kind for s in result.sentences] == [DIALOGUE, NARRATION]
    assert result.sentences[0].quote == 1
    assert result.quotes[0].key == (1, 8, 1)
    assert "".join(s.text for s in result.sentences) == text


def test_two_quotes_numbered_in_open_order() -> None:
    text = f"{LD}I knew it,{RD} he muttered, {LD}listen well!{RD}"
    result = split_paragraph_dialogue(text, chapter=1, block=11)
    assert [q.quote for q in result.quotes] == [1, 2]
    assert [q.key for q in result.quotes] == [(1, 11, 1), (1, 11, 2)]
    assert "".join(s.text for s in result.sentences) == text


# ---------------------------------------------------------------------------
# Single quotes only when no doubles; nested singles belong to the outer
# ---------------------------------------------------------------------------


def test_single_quotes_form_dialogue_without_doubles() -> None:
    result = split_paragraph_dialogue("'Hello there.' He left.", chapter=1, block=2)
    assert result.sentences[0].kind == DIALOGUE
    assert result.sentences[0].quote == 1
    assert result.sentences[-1].kind == NARRATION
    assert len(result.quotes) == 1
    assert "".join(s.text for s in result.sentences) == "'Hello there.' He left."


def test_nested_singles_inside_doubles_belong_to_outer() -> None:
    result = split_paragraph_dialogue(
        "He said \"hi 'there' loudly.\" He nodded.", chapter=1, block=2
    )
    assert len(result.quotes) == 1
    assert result.quotes[0].quote == 1
    dialogue = [s for s in result.sentences if s.kind == DIALOGUE]
    assert len(dialogue) == 1
    assert "'there'" in dialogue[0].text
    assert "".join(s.text for s in result.sentences) == "He said \"hi 'there' loudly.\" He nodded."


def test_curly_singles_ignored_in_double_paragraph() -> None:
    text = f"He thought of Jack\u2019s tale, {LD}brave boy,{RD} and smiled."
    result = split_paragraph_dialogue(text, chapter=1, block=2)
    assert len(result.quotes) == 1  # the apostrophe never opens a quote
    assert "".join(s.text for s in result.sentences) == text


# ---------------------------------------------------------------------------
# Apostrophes are never quote boundaries
# ---------------------------------------------------------------------------


def test_curly_apostrophes_never_split() -> None:
    text = f"The man{RS}s hat is here. She left."
    result = split_paragraph_dialogue(text, chapter=1, block=2)
    assert result.quotes == []
    assert all(s.kind == NARRATION for s in result.sentences)
    assert "".join(s.text for s in result.sentences) == text


def test_straight_apostrophes_never_split() -> None:
    result = split_paragraph_dialogue("The man's hat is here. She left.", chapter=1, block=2)
    assert result.quotes == []
    assert all(s.kind == NARRATION for s in result.sentences)


# ---------------------------------------------------------------------------
# Multi-paragraph quotes
# ---------------------------------------------------------------------------


def test_multiparagraph_quote_continues_with_marker() -> None:
    chapter = _chapter(
        Block(id=1, type="heading", level=1, text="Chapter One"),
        _para(2, f"{LD}We march on through the night with heavy hearts."),
        _para(3, f"{LD}and on until the morning comes.{RD} He sighed."),
    )
    result = split_chapter_dialogue(chapter)
    by_block = {t.block: t for t in result.tagged if t.kind == DIALOGUE}
    assert by_block[2].quote == 1 and not by_block[2].continued
    assert by_block[3].quote == 1 and by_block[3].continued
    assert [q.key for q in result.quotes] == [(1, 2, 1), (1, 3, 1)]
    assert [s.sid for b in result.chapter.blocks for s in b.sentences] == [1, 2, 3, 4]


# ---------------------------------------------------------------------------
# Multi-sentence quotes stay ONE quote
# ---------------------------------------------------------------------------


def test_multisentence_single_quotes_stay_one_quote() -> None:
    text = "'One. Two. Three. Four. Five.' They all left."
    result = split_paragraph_dialogue(text, chapter=1, block=5)
    assert len(result.quotes) == 1
    dialogue = [s for s in result.sentences if s.kind == DIALOGUE]
    assert len(dialogue) > 1  # several sids ...
    assert {s.quote for s in dialogue} == {1}  # ... sharing one quote key
    assert result.quotes[0].key == (1, 5, 1)
    assert "".join(s.text for s in result.sentences) == text


def test_multisentence_double_quote_stays_one_entry() -> None:
    text = (
        f"{LD}I thought so! That{RS}s the worst of all! "
        f"Why, a stupid thing like this might spoil the whole plan. "
        f"Yes, my hat is too noticeable. It looks absurd.{RD} He sighed."
    )
    result = split_paragraph_dialogue(text, chapter=1, block=11)
    assert len(result.quotes) == 1
    dialogue = [s for s in result.sentences if s.kind == DIALOGUE]
    assert len(dialogue) == 5
    assert {s.quote for s in dialogue} == {1}
    assert "".join(s.text for s in result.sentences) == text


# ---------------------------------------------------------------------------
# Exact round trip (Player spacing rule: concatenate as stored)
# ---------------------------------------------------------------------------


def test_round_trip_tricky_spacing_with_quotes() -> None:
    paragraphs = [
        '"Hello."  Double  spaced.  She left.',
        f"  Leading space {LD}quoted words.{RD}  Trailing.  ",
        'Tabs\tinside quotes "hi there." New\nlines. Mixed   spacing.',
        f"{LD}One. Two. Three. Four.{RD} They left.",
        "'One. Two. Three. Four. Five.' They all left.",
    ]
    for text in paragraphs:
        result = split_paragraph_dialogue(text, chapter=1, block=1)
        # Player spacing rule: sentences concatenate exactly as stored.
        assert "".join(s.text for s in result.sentences) == text, f"round trip failed: {text!r}"


def test_chapter_round_trip_per_block() -> None:
    originals = [
        "On a hot evening a young man came out of his garret. He hesitated.",
        f"{LD}I want to attempt a thing like that,{RD} he thought. {LD}Hm, yes.{RD}",
        "The heat in the street was terrible. He walked on.",
    ]
    chapter = _chapter(*[_para(i + 1, text) for i, text in enumerate(originals)])
    result = split_chapter_dialogue(chapter)
    assert result.chapter.blocks is not None
    for block, original in zip([b for b in result.chapter.blocks if b.type == "para"], originals):
        assert "".join(s.text for s in block.sentences) == original


# ---------------------------------------------------------------------------
# Unbalanced and stray quotes: log and treat as narration
# ---------------------------------------------------------------------------


def test_unclosed_single_paragraph_chapter_is_narration(caplog) -> None:
    chapter = _chapter(_para(1, '"Unclosed quote here. It keeps going.'))
    with caplog.at_level(logging.WARNING, logger="text.dialogue"):
        result = split_chapter_dialogue(chapter)
    assert result.quotes == []
    assert all(s.kind == NARRATION for s in result.tagged)
    assert any("unclosed" in r.message or "unbalanced" in r.message for r in caplog.records)


def test_stray_closer_is_narration_with_warning(caplog) -> None:
    text = 'He bought a 5" nail. It hurt.'
    with caplog.at_level(logging.WARNING, logger="text.dialogue"):
        result = split_paragraph_dialogue(text, chapter=1, block=4)
    assert result.quotes == []
    assert all(s.kind == NARRATION for s in result.sentences)
    assert any("unbalanced" in r.message for r in caplog.records)
    assert "".join(s.text for s in result.sentences) == text


def test_intact_pair_with_stray_inch_honored(caplog) -> None:
    text = '"Hi. Bye." She waved. He bought a 5" nail. It hurt.'
    with caplog.at_level(logging.WARNING, logger="text.dialogue"):
        result = split_paragraph_dialogue(text, chapter=1, block=4)
    assert len(result.quotes) == 1  # the intact pair still counts
    assert result.sentences[0].kind == DIALOGUE
    assert any("stray" in r.message for r in caplog.records)
    assert "".join(s.text for s in result.sentences) == text


def test_unclosed_multiparagraph_chain_flips_to_narration(caplog) -> None:
    chapter = _chapter(
        _para(1, f"{LD}We march on through the night."),
        _para(2, f"{LD}and on, never stopping."),
    )
    with caplog.at_level(logging.WARNING, logger="text.dialogue"):
        result = split_chapter_dialogue(chapter)
    assert result.quotes == []  # the never-closed chain contributes nothing
    assert all(s.kind == NARRATION for s in result.tagged)
    assert any("unclosed" in r.message for r in caplog.records)


# ---------------------------------------------------------------------------
# Scare-quote rule (gold block 12 contributes zero quotes)
# ---------------------------------------------------------------------------


def test_scare_rule_short_plain_fragments() -> None:
    assert is_scare_quote("hideous")
    assert is_scare_quote("rehearsal")
    assert is_scare_quote("the plan")


def test_scare_rule_keeps_real_dialogue() -> None:
    assert not is_scare_quote("Hey there, German hatter")  # four words
    assert not is_scare_quote("I knew it,")  # trailing comma: tag continuation
    assert not is_scare_quote("Hi,")  # trailing comma beats word count
    assert not is_scare_quote("Stop.")  # terminal punctuation beats word count
    assert not is_scare_quote("Run!")  # terminal punctuation beats word count
    assert not is_scare_quote("Hm\u2026 yes, indeed")  # ellipsis beats word count


def test_block12_style_paragraph_has_zero_quotes() -> None:
    text = (
        "He had counted them once when he had been lost in dreams. "
        f"He had come to regard this {LD}hideous{RD} dream as an exploit. "
        f"He was going now for a {LD}rehearsal{RD} of his project, "
        "and at every step his excitement grew."
    )
    result = split_paragraph_dialogue(text, chapter=1, block=12)
    assert result.quotes == []
    assert all(s.kind == NARRATION for s in result.sentences)
    assert "".join(s.text for s in result.sentences) == text


# ---------------------------------------------------------------------------
# Sids consecutive, block ids preserved (headings count)
# ---------------------------------------------------------------------------


def test_chapter_sids_consecutive_and_block_ids_preserved() -> None:
    chapter = _chapter(
        Block(id=1, type="heading", level=1, text="Chapter One"),
        _para(2, "First. Second."),
        _para(3, '"Quoted words," she said. Then more.'),
        Block(id=4, type="break"),
        _para(5, "Last. Words."),
    )
    result = split_chapter_dialogue(chapter)
    assert result.chapter.blocks is not None
    assert [b.id for b in result.chapter.blocks] == [1, 2, 3, 4, 5]
    assert [b.type for b in result.chapter.blocks] == ["heading", "para", "para", "break", "para"]
    sids = [s.sid for b in result.chapter.blocks for s in b.sentences]
    assert sids == list(range(1, len(sids) + 1))
    assert [t.sid for t in result.tagged] == sids
    assert result.chapter.blocks[3].sentences == []


def test_pages_form_chapter_passes_through() -> None:
    chapter = ChapterFile(
        spec_version="1.0", chapter=1, title="Pages", duration_ms=0, blocks=None, pages=[]
    )
    result = split_chapter_dialogue(chapter)
    assert result.chapter.blocks is None
    assert result.tagged == [] and result.quotes == []


# ---------------------------------------------------------------------------
# Span rebasing across quote splits
# ---------------------------------------------------------------------------


def test_spans_rebased_across_quote_split() -> None:
    text = '"We should leave," she said.'
    span = Span(start=1, end=15, style="italic")  # "We should lea" inside the quote
    result = split_paragraph_dialogue(text, [span], chapter=1, block=1)
    assert result.sentences[0].kind == DIALOGUE
    assert result.sentences[0].spans == [Span(start=1, end=15, style="italic")]
    assert result.sentences[1].spans == []


# ---------------------------------------------------------------------------
# Gold-shaped chapter: blocks 8/10/11 keys, block 12 empty
# ---------------------------------------------------------------------------


def test_gold_shaped_chapter_quote_keys() -> None:
    chapter = _chapter(
        _para(
            8, f"{LD}I want to attempt a thing like that,{RD} he thought. {LD}Hm, yes, indeed.{RD}"
        ),
        _para(10, f"He shouted as he drove past: {LD}Hey there, German hatter{RD} loudly."),
        _para(11, f"{LD}I knew it,{RD} he muttered. {LD}I thought so! Ruin!{RD}"),
        _para(
            12,
            f"He eyed this {LD}hideous{RD} dream before the {LD}rehearsal{RD} calmly. He went on.",
        ),
    )
    result = split_chapter_dialogue(chapter)
    assert [q.key for q in result.quotes] == [
        (1, 8, 1),
        (1, 8, 2),
        (1, 10, 1),
        (1, 11, 1),
        (1, 11, 2),
    ]
    assert [t.key for t in result.tagged if t.kind == DIALOGUE and t.block == 12] == []
    block12 = next(b for b in result.chapter.blocks if b.id == 12)
    assert all(s.kind == NARRATION for s in [t for t in result.tagged if t.block == 12])
    assert "".join(s.text for s in block12.sentences) == (
        f"He eyed this {LD}hideous{RD} dream before the {LD}rehearsal{RD} calmly. He went on."
    )
