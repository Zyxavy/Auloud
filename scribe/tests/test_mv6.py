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

"""MV6: draft extension and cast report (``draft.py`` + staleness).

Synthetic dialogue EPUBs only (never real books): script.json carries raw
speaker/confidence/kind/quote per sentence with consecutive sids,
twice-draft byte-identical, cast merge never clobbers user edits,
cast_report.md lists characters then low-confidence quotes with keys,
and build re-drafts only on source/version change, never on cast edits.
"""

from __future__ import annotations

import json
from pathlib import Path

import yaml
from ebooklib import epub

from build import ensure_script
from draft import CAST_REPORT_FILENAME, run_draft
from text.attribution import ATTRIBUTION_RULES_VERSION
from text.cast import read_cast, write_cast


def _long_para(repeats: int = 25) -> str:
    return " ".join(["The quiet alder fox crosses the mossy hill at dawn."] * repeats)


def _make_dialogue_epub(path: Path) -> Path:
    """Two-chapter synthetic EPUB with explicit, alternating and bare quotes."""
    book = epub.EpubBook()
    book.set_identifier("test-mv6-book")
    book.set_title("MV6 Dialogue Book")
    book.set_language("en")
    book.add_author("MV6 Author")

    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = (
        "<h1>Chapter One</h1>"
        f"<p>{_long_para()}</p>"
        '<p>"Hello," Alice said.</p>'
        '<p>"Hi," Robert said.</p>'
        '<p>"How are you?"</p>'
        '<p>"Fine, thanks."</p>'
    )
    ch2 = epub.EpubHtml(title="Chapter Two", file_name="ch2.xhtml", lang="en")
    ch2.content = (
        "<h1>Chapter Two</h1>"
        f"<p>{_long_para()}</p>"
        '<p>"Bare first."</p>'
        '<p>"Second bare."</p>'
    )
    for item in (ch1, ch2):
        book.add_item(item)
    book.toc = [
        epub.Link("ch1.xhtml", "Chapter One", "ch1"),
        epub.Link("ch2.xhtml", "Chapter Two", "ch2"),
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [ch1, ch2]
    epub.write_epub(str(path), book)
    return path


def _script_data(result) -> dict:
    return json.loads(result.script_path.read_text(encoding="utf-8"))


def test_script_has_raw_speaker_confidence_quote_and_consecutive_sids(
    tmp_path: Path,
) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    result = run_draft(epub_path, work_root=tmp_path / ".scribe")
    data = _script_data(result)

    assert data["attribution_rules_version"] == ATTRIBUTION_RULES_VERSION
    assert data["book_id"] == result.book_id
    assert "created_at" not in data and "timestamp" not in data

    from bundle.models import ChapterFile

    for raw in data["chapters"]:
        chapter = ChapterFile.from_dict(raw)
        sentences = chapter.sentences_in_order()
        assert [s.sid for s in sentences] == list(range(1, len(sentences) + 1))
        for sent in sentences:
            assert sent.kind in ("narration", "dialogue")
            assert sent.confidence in ("high", "medium", "low")
            if sent.kind == "narration":
                assert sent.speaker == "narrator"
                assert sent.confidence == "high"
                assert sent.quote is None
            else:
                assert sent.speaker.strip()
                assert sent.quote is not None
                assert set(sent.quote) == {"chapter", "block", "quote"}
                assert sent.quote["chapter"] == chapter.chapter

    ch1 = ChapterFile.from_dict(data["chapters"][0])
    dialogue = [s for s in ch1.sentences_in_order() if s.kind == "dialogue"]
    assert len(dialogue) == 4
    by_text = {s.text.strip('" ,'): s for s in dialogue}
    assert by_text["Hello"].speaker == "Alice"
    assert by_text["Hello"].confidence == "high"
    assert by_text["Hi"].speaker == "Robert"
    assert by_text["Hi"].confidence == "high"
    speakers = {s.speaker for s in dialogue}
    assert speakers == {"Alice", "Robert"}


def test_twice_draft_dialogue_book_byte_identical(tmp_path: Path) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    first = run_draft(epub_path, work_root=tmp_path / "work-a")
    second = run_draft(epub_path, work_root=tmp_path / "work-b")
    rerun = run_draft(epub_path, work_root=tmp_path / "work-a")
    assert first.book_id == second.book_id == rerun.book_id
    for name in ("script.json", "cast.yaml", "draft_report.md", CAST_REPORT_FILENAME):
        assert (first.work_dir / name).read_bytes() == (second.work_dir / name).read_bytes()
        assert (first.work_dir / name).read_bytes() == (rerun.work_dir / name).read_bytes()


def test_merge_no_clobber_on_second_draft_with_user_edits(tmp_path: Path) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    work_root = tmp_path / "work"
    first = run_draft(epub_path, work_root=work_root)
    script_before = first.script_path.read_bytes()

    cast = read_cast(first.cast_path)
    assert "Alice" in cast["characters"]
    cast["characters"]["Alice"]["voice"] = "zf_xiaoxiao"
    cast["characters"]["Alice"]["speed"] = 1.1
    cast["aliases"].setdefault("Alice", []).append("Alicia")
    cast["first_person"] = "narrator"
    cast["overrides"].append({"chapter": 1, "block": 3, "quote": 1, "speaker": "Alice"})
    write_cast(first.cast_path, cast)
    edited = read_cast(first.cast_path)

    second = run_draft(epub_path, work_root=work_root)
    assert second.script_path.read_bytes() == script_before
    merged = read_cast(second.cast_path)
    assert merged["characters"]["Alice"] == {"voice": "zf_xiaoxiao", "speed": 1.1}
    assert "Alicia" in merged["aliases"]["Alice"]
    assert merged["overrides"] == edited["overrides"]
    assert "Robert" in merged["characters"]
    assert merged["default_female"] == edited["default_female"]


def test_cast_report_lists_characters_then_low_confidence_with_keys(
    tmp_path: Path,
) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    result = run_draft(epub_path, work_root=tmp_path / "work")
    report = (result.work_dir / CAST_REPORT_FILENAME).read_text(encoding="utf-8")

    assert "# Cast report" in report
    assert "Alice" in report and "Robert" in report
    assert "Lines" in report
    assert "Low-confidence" in report
    assert "Chapter 2" in report
    assert "(2," in report
    assert "speaker: <Name>" in report
    assert "Override:" in report
    assert "Context" in report


def test_staleness_cast_edit_does_not_redraft(tmp_path: Path) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    work_root = tmp_path / "work"
    run_draft(epub_path, work_root=work_root)
    info = ensure_script(epub_path, work_root=work_root)
    assert info.redrafted is False

    cast_path = info.work_dir / "cast.yaml"
    cast = read_cast(cast_path)
    cast["characters"]["Alice"]["speed"] = 0.9
    write_cast(cast_path, cast)

    again = ensure_script(epub_path, work_root=work_root)
    assert again.redrafted is False


def test_staleness_rules_version_change_redrafts(tmp_path: Path) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    work_root = tmp_path / "work"
    result = run_draft(epub_path, work_root=work_root)
    data = json.loads(result.script_path.read_text(encoding="utf-8"))
    data["attribution_rules_version"] = 0
    result.script_path.write_text(json.dumps(data, sort_keys=True) + "\n", encoding="utf-8")

    info = ensure_script(epub_path, work_root=work_root)
    assert info.redrafted is True
    fixed = json.loads(result.script_path.read_text(encoding="utf-8"))
    assert fixed["attribution_rules_version"] == ATTRIBUTION_RULES_VERSION


def test_staleness_missing_version_redrafts_legacy_script(tmp_path: Path) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    work_root = tmp_path / "work"
    result = run_draft(epub_path, work_root=work_root)
    data = json.loads(result.script_path.read_text(encoding="utf-8"))
    del data["attribution_rules_version"]
    result.script_path.write_text(json.dumps(data, sort_keys=True) + "\n", encoding="utf-8")

    info = ensure_script(epub_path, work_root=work_root)
    assert info.redrafted is True


def test_staleness_source_change_redrafts(tmp_path: Path) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    work_root = tmp_path / "work"
    first = run_draft(epub_path, work_root=work_root)
    data = json.loads(first.script_path.read_text(encoding="utf-8"))
    data["source_sha256"] = "0" * 64
    first.script_path.write_text(json.dumps(data, sort_keys=True) + "\n", encoding="utf-8")

    info = ensure_script(epub_path, work_root=work_root)
    assert info.redrafted is True
    assert len(info.chapters) == 2


def test_draft_report_still_has_sw5_sections(tmp_path: Path) -> None:
    epub_path = _make_dialogue_epub(tmp_path / "dialogue.epub")
    result = run_draft(epub_path, work_root=tmp_path / "work")
    report = result.report_path.read_text(encoding="utf-8")
    assert "Chapter One" in report and "Chapter Two" in report
    assert "Estimated audio" in report
    assert "rough" in report.lower()
    assert "RTF" in report
    cast = yaml.safe_load(result.cast_path.read_text(encoding="utf-8"))
    assert cast["first_person"] == "narrator"
