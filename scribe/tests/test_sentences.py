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

"""SW4: sentence-splitting tests for ``text/sentences.py`` (synthetic text only).

Covers abbreviation/initial/ellipsis post-fixes, short-quote attachment vs
long-quote splitting, exact round-trip preservation on tricky spacing,
heading-to-single-sentence, and span rebasing (crossing, nested, inside).
"""

from __future__ import annotations

from bundle.models import Block, ChapterFile, Sentence, Span
from text.sentences import split_chapter, split_paragraph


def _para(
    text: str, spans: list[Span] | None = None, sid: int = 1, speaker: str = "narrator"
) -> Block:
    return Block(
        id=1,
        type="para",
        sentences=[
            Sentence(
                sid=sid,
                speaker=speaker,
                start_ms=0,
                end_ms=0,
                text=text,
                spans=spans or [],
            )
        ],
    )


def _chapter(*blocks: Block) -> ChapterFile:
    return ChapterFile(
        spec_version="1.0", chapter=1, title="Test", duration_ms=0, blocks=list(blocks)
    )


def _texts(sentences: list[Sentence]) -> list[str]:
    return [s.text for s in sentences]


# ---------------------------------------------------------------------------
# Abbreviations and initials
# ---------------------------------------------------------------------------


def test_mr_not_split() -> None:
    out = split_paragraph("Mr. Smith went home. He slept.")
    assert _texts(out) == ["Mr. Smith went home. ", "He slept."]


def test_mrs_dr_st_not_split() -> None:
    out = split_paragraph("Mrs. Jones and Dr. Brown met St. Mary. They talked.")
    assert len(out) == 2
    assert "St. Mary." in out[0].text
    assert out[1].text == "They talked."


def test_initials_kept_together() -> None:
    out = split_paragraph("J. K. Rowling arrived early. She smiled.")
    assert len(out) == 2
    assert "J. K. Rowling" in out[0].text
    assert out[1].text == "She smiled."


# ---------------------------------------------------------------------------
# Ellipses
# ---------------------------------------------------------------------------


def test_ellipsis_merges_with_next() -> None:
    assert _texts(split_paragraph("She trailed off... Then she left.")) == [
        "She trailed off... Then she left."
    ]


def test_ellipsis_chain_stays_whole() -> None:
    assert _texts(split_paragraph("He left... She stayed... They met.")) == [
        "He left... She stayed... They met."
    ]


def test_unicode_ellipsis_merges() -> None:
    out = split_paragraph("He waited… and waited. Then he left.")
    assert len(out) == 2
    assert "…" in out[0].text
    assert out[1].text == "Then he left."


# ---------------------------------------------------------------------------
# Quotations
# ---------------------------------------------------------------------------


def test_short_quote_stays_attached() -> None:
    out = split_paragraph('He said, "I am tired. Let us rest." Then they slept.')
    assert _texts(out) == ['He said, "I am tired. Let us rest." ', "Then they slept."]


def test_three_sentence_quote_stays_attached() -> None:
    out = split_paragraph('"One. Two. Three." They left.')
    assert _texts(out) == ['"One. Two. Three." ', "They left."]


def test_long_quote_splits_inside() -> None:
    text = '"One. Two. Three. Four." They left.'
    out = split_paragraph(text)
    assert _texts(out) == ['"One. ', "Two. ", "Three. ", 'Four." ', "They left."]
    assert "".join(_texts(out)) == text


def test_unbalanced_quote_splits_as_narration() -> None:
    text = '"Unclosed quote here. It keeps going. And going.'
    out = split_paragraph(text)
    assert len(out) == 3
    assert "".join(_texts(out)) == text


# ---------------------------------------------------------------------------
# Round-trip preservation
# ---------------------------------------------------------------------------


def test_round_trip_tricky_spacing() -> None:
    text = "  Leading space.  Double  inside.\tTabbed. Trailing.  "
    out = split_paragraph(text)
    assert len(out) == 4
    assert "".join(_texts(out)) == text


def test_round_trip_newlines() -> None:
    text = "First sentence.\nSecond on new line.  Third  spaced."
    out = split_paragraph(text)
    assert "".join(_texts(out)) == text
    assert len(out) == 3


def test_round_trip_many_tricky_paragraphs() -> None:
    paragraphs = [
        "Mr. Smith waited... and waited. Then Mrs. Jones arrived.",
        'She said, "Go home. Now." and left. It was late.',
        '"One. Two. Three. Four. Five." They all left.',
        "J. K. Rowling dreamed... of dragons. She woke.",
        "Hello.  World with  double spaces!  Really?  Yes.",
        "  Padded start and end.  ",
        "Tabs\tinside. New\nlines. Mixed   spacing.",
    ]
    for text in paragraphs:
        out = split_paragraph(text)
        assert out, f"no sentences for {text!r}"
        assert "".join(_texts(out)) == text, f"round-trip failed for {text!r}"


def test_blank_paragraph_yields_no_sentences() -> None:
    assert split_paragraph("") == []
    assert split_paragraph("   ") == []


# ---------------------------------------------------------------------------
# Headings and chapters
# ---------------------------------------------------------------------------


def test_split_chapter_heading_para_break() -> None:
    chapter = _chapter(
        Block(id=1, type="heading", level=1, text="Chapter One: The Beginning"),
        _para("First. Second."),
        Block(id=3, type="break"),
    )
    out = split_chapter(chapter)
    assert out.blocks is not None
    heading, para, break_block = out.blocks
    assert [s.text for s in heading.sentences] == ["Chapter One: The Beginning"]
    assert _texts(para.sentences) == ["First. ", "Second."]
    assert break_block.sentences == []
    assert [s.sid for b in out.blocks for s in b.sentences] == [1, 2, 3]


def test_split_chapter_sids_consecutive_across_blocks() -> None:
    chapter = _chapter(
        Block(id=1, type="heading", level=2, text="A Title"),
        _para("One. Two. Three."),
        Block(
            id=3,
            type="quote",
            sentences=[
                Sentence(sid=1, speaker="narrator", start_ms=0, end_ms=0, text="Quoted. Once.")
            ],
        ),
    )
    out = split_chapter(chapter)
    assert out.blocks is not None
    sids = [s.sid for b in out.blocks for s in b.sentences]
    assert sids == list(range(1, len(sids) + 1))
    assert len(sids) == 1 + 3 + 2


def test_speaker_preserved() -> None:
    out = split_paragraph("First. Second.", speaker="Ana")
    assert all(s.speaker == "Ana" for s in out)
    chapter = _chapter(_para("One. Two.", speaker="narrator"))
    assert all(s.speaker == "narrator" for s in split_chapter(chapter).blocks[0].sentences)


# ---------------------------------------------------------------------------
# Span rebasing
# ---------------------------------------------------------------------------


def test_span_crossing_boundary_is_clipped_to_both_sides() -> None:
    text = "Anna loved old maps. She kept one."
    span = Span(start=text.index("maps."), end=text.index("She") + len("She"), style="italic")
    out = split_paragraph(text, [span])
    assert len(out) == 2
    assert "".join(_texts(out)) == text
    first_len = len(out[0].text)
    assert out[0].spans == [Span(start=span.start, end=first_len, style="italic")]
    assert out[1].spans == [Span(start=0, end=span.end - first_len, style="italic")]


def test_nested_spans_stay_independent() -> None:
    text = "Anna loved old maps. She kept one."
    spans = [Span(start=0, end=4, style="italic"), Span(start=0, end=4, style="bold")]
    out = split_paragraph(text, spans)
    assert out[0].spans == spans
    assert out[1].spans == []


def test_span_inside_single_sentence() -> None:
    text = "Anna loved old maps. She kept one."
    span = Span(start=text.index("kept"), end=text.index("kept") + len("kept"), style="bold")
    out = split_paragraph(text, [span])
    assert out[0].spans == []
    first_len = len(out[0].text)
    assert out[1].spans == [
        Span(start=span.start - first_len, end=span.end - first_len, style="bold")
    ]


def test_span_across_long_quote_split() -> None:
    text = '"One. Two. Three. Four." They left.'
    span = Span(start=0, end=text.index('"', 1) + 1, style="italic")
    out = split_paragraph(text, [span])
    assert len(out) == 5
    offset = 0
    for sent in out[:4]:
        start = max(span.start, offset)
        end = min(span.end, offset + len(sent.text))
        assert sent.spans == [Span(start=start - offset, end=end - offset, style="italic")]
        offset += len(sent.text)
    assert out[4].spans == []
    assert "".join(_texts(out)) == text
