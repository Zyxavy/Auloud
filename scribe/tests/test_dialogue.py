# Auloud Scribe - turns ebooks into multi-voice audiobooks (PC tool).
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

Slice 9 IN2: the behavior pins live in the shared vectors
(``spec/fixtures/dialogue-cases.json``, exported by
``export_ingest.py``), and this module consumes them instead of
duplicating their literals. The parametrized harness below replays every
vector through the real splitter and asserts the exact runs plus the
Player spacing round trip. The remaining tests cover Scribe-side
mechanics the vectors do not spell out (quote keys, continued flags,
span rebasing, sid order, log warnings); they load their inputs from the
vectors by id wherever the text overlaps, so no paragraph literal exists
in two places.

Synthetic text only; the real EPUB check runs outside the suite.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

import pytest

from bundle.models import Block, ChapterFile, Sentence, Span
from export_ingest import runs_for_chapter, runs_for_paragraphs
from text.dialogue import (
    DIALOGUE,
    NARRATION,
    ChapterDialogue,
    is_scare_quote,
    split_chapter_dialogue,
    split_paragraph_dialogue,
)

VECTORS_PATH = (
    Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "dialogue-cases.json"
)


def _load_vectors() -> dict:
    return json.loads(VECTORS_PATH.read_text(encoding="utf-8"))


VECTORS = _load_vectors()
CASE_IDS = [case["id"] for case in VECTORS["cases"]]
BY_ID = {case["id"]: case for case in VECTORS["cases"]}


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


def _actual_runs(case: dict) -> list[dict]:
    if case["scope"] == "chapter":
        return runs_for_chapter(list(case["input"]))
    return runs_for_paragraphs(list(case["input"]))


# ---------------------------------------------------------------------------
# Shared-vector harness: every case replays exactly, and round-trips
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("case_id", CASE_IDS)
def test_vector_runs_match_and_round_trip(case_id: str) -> None:
    case = BY_ID[case_id]
    assert _actual_runs(case) == case["expected"], f"vector drift: {case_id}"
    for index, paragraph in enumerate(case["input"]):
        joined = "".join(r["text"] for r in case["expected"] if r["paragraph"] == index)
        # Player spacing rule: run texts concatenate exactly as stored.
        assert joined == paragraph, f"round trip failed: {case_id} paragraph {index}"


# ---------------------------------------------------------------------------
# Scribe-side mechanics (quote keys, flags, spans, sids, warnings)
# ---------------------------------------------------------------------------


def test_multiparagraph_continuation_keys_and_flags() -> None:
    paragraphs = list(BY_ID["multiparagraph-continuation"]["input"])
    chapter = _chapter(*[_para(i + 1, text) for i, text in enumerate(paragraphs)])
    result = split_chapter_dialogue(chapter)
    by_block = {t.block: t for t in result.tagged if t.kind == DIALOGUE}
    assert by_block[1].quote == 1 and not by_block[1].continued
    assert by_block[2].quote == 1 and by_block[2].continued
    assert [q.key for q in result.quotes] == [(1, 1, 1), (1, 2, 1)]


def test_multisentence_quotes_share_one_quote_number() -> None:
    for case_id in ("multisentence-singles-one-quote", "multisentence-doubles-one-quote"):
        (text,) = BY_ID[case_id]["input"]
        result = split_paragraph_dialogue(text, chapter=1, block=5)
        assert len(result.quotes) == 1, case_id
        dialogue = [s for s in result.sentences if s.kind == DIALOGUE]
        assert len(dialogue) > 1, case_id  # several sids ...
        assert {s.quote for s in dialogue} == {1}, case_id  # ... sharing one quote key


def test_gold_shaped_chapter_quote_keys() -> None:
    chapter = _chapter(
        _para(8, BY_ID["gold-block-8"]["input"][0]),
        _para(10, BY_ID["gold-block-10"]["input"][0]),
        _para(11, BY_ID["gold-block-11"]["input"][0]),
        _para(
            12,
            "He eyed this \u201chideous\u201d dream before the "
            "\u201crehearsal\u201d calmly. He went on.",
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
    assert all(s.kind == NARRATION for s in [t for t in result.tagged if t.block == 12])


def test_unbalanced_paragraph_cases_log_and_narrate(caplog) -> None:
    for case_id in ("inch-mark-stray", "intact-pair-with-stray"):
        (text,) = BY_ID[case_id]["input"]
        with caplog.at_level(logging.WARNING, logger="text.dialogue"):
            result = split_paragraph_dialogue(text, chapter=1, block=4)
        if case_id == "inch-mark-stray":
            assert result.quotes == []
            assert all(s.kind == NARRATION for s in result.sentences)
            assert any("unbalanced" in r.message for r in caplog.records)
        else:
            assert len(result.quotes) == 1  # the intact pair still counts
            assert result.sentences[0].kind == DIALOGUE
            assert any("stray" in r.message for r in caplog.records)
        caplog.clear()


def test_unclosed_chapter_cases_flip_to_narration(caplog) -> None:
    for case_id in ("unclosed-single-paragraph", "unclosed-multiparagraph-chain"):
        paragraphs = list(BY_ID[case_id]["input"])
        chapter = _chapter(*[_para(i + 1, text) for i, text in enumerate(paragraphs)])
        with caplog.at_level(logging.WARNING, logger="text.dialogue"):
            result = split_chapter_dialogue(chapter)
        assert result.quotes == [], case_id
        assert all(s.kind == NARRATION for s in result.tagged), case_id
        assert any("unclosed" in r.message for r in caplog.records), case_id
        caplog.clear()


# ---------------------------------------------------------------------------
# Scare-quote helper units (fragment level, no paragraph duplication)
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
# Span rebasing across quote splits (vector input, Scribe-side spans)
# ---------------------------------------------------------------------------


def test_spans_rebased_across_quote_split() -> None:
    (text,) = BY_ID["mixed-straight"]["input"]
    span = Span(start=1, end=15, style="italic")  # "We should lea" inside the quote
    result = split_paragraph_dialogue(text, [span], chapter=1, block=1)
    assert result.sentences[0].kind == DIALOGUE
    assert result.sentences[0].spans == [Span(start=1, end=15, style="italic")]
    assert result.sentences[1].spans == []
