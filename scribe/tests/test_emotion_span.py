"""Emotion tags reach every sentence of a multi-sentence quote (draft path)."""

from __future__ import annotations

import json
from pathlib import Path

from ebooklib import epub

from bundle.models import ChapterFile
from draft import SCRIPT_FILENAME, run_draft


def _epub(path: Path) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-emotion-span")
    book.set_title("Emotion Span")
    book.set_language("en")
    book.add_author("Test Author")
    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    # Single quotes: a quoted region spanning two sentences splits into two
    # dialogue sentences that share one quote key (MV2).
    ch1.content = (
        "<h1>Chapter One</h1>"
        "<p>'Stop. You shall not pass.' he shouted angrily.</p>"
    )
    book.add_item(ch1)
    book.toc = [epub.Link("ch1.xhtml", "Chapter One", "ch1")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [ch1]
    epub.write_epub(str(path), book)
    return path


def test_every_sentence_of_a_quote_gets_the_cue_emotion(tmp_path: Path) -> None:
    result = run_draft(_epub(tmp_path / "span.epub"), work_root=tmp_path / "work")
    data = json.loads(
        (tmp_path / "work" / result.book_id / SCRIPT_FILENAME).read_text(encoding="utf-8")
    )
    sentences = [
        s
        for chapter in data["chapters"]
        for s in ChapterFile.from_dict(chapter).sentences_in_order()
    ]
    quoted = [s for s in sentences if s.kind == "dialogue" and s.quote is not None]
    # One quote spanning two sentences; the cue tag covers both.
    assert len(quoted) >= 2
    assert {s.quote["quote"] for s in quoted} == {1}
    assert {s.emotion for s in quoted} == {"SHOUT"}
    # The attribution clause is narration and carries no tag.
    assert all(s.emotion is None for s in sentences if s.kind == "narration")
