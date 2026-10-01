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

"""SW5: ``scribe draft`` tests (synthetic mini-EPUBs only, never real books).

Covers script.json structure/speakers/sids, narrator-only cast.yaml, report
contents (chapters, counts, drops, rough estimate), twice-run byte-identical
output, book-id determinism, and thin CLI wiring.
"""

from __future__ import annotations

import json
import uuid
from pathlib import Path

import yaml
from ebooklib import epub
from typer.testing import CliRunner

import cli
from bundle.models import ChapterFile
from draft import (
    BOOK_ID_NAMESPACE,
    BOOK_ID_PREFIX,
    DraftError,
    book_id_for_file,
    book_id_for_sha256,
    run_draft,
    sha256_of_file,
)


def _long_para(word: str, repeats: int = 25) -> str:
    """Synthetic long paragraph (~10 words per sentence, test-only text)."""
    return " ".join(["The quiet alder fox crosses the mossy hill at dawn."] * repeats)


def _make_draft_epub(path: Path) -> Path:
    """Two-chapter synthetic EPUB, each chapter over the tiny-merge limit."""
    book = epub.EpubBook()
    book.set_identifier("test-draft-book")
    book.set_title("Draft Test Book")
    book.set_language("en")
    book.add_author("Draft Author")

    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = (
        "<h1>Chapter One</h1>"
        f"<p>{_long_para('alder')}</p>"
        "<p>Mr. Smith went home. He slept.</p>"
        "<p>Copyright © 2026 Example Press. All rights reserved.</p>"
    )
    ch2 = epub.EpubHtml(title="Chapter Two", file_name="ch2.xhtml", lang="en")
    ch2.content = f"<h1>Chapter Two</h1><p>{_long_para('bracken')}</p>"

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


def _run(tmp_path: Path, name: str = "mini.epub") -> tuple[Path, object]:
    epub_path = _make_draft_epub(tmp_path / name)
    result = run_draft(epub_path, work_root=tmp_path / ".scribe")
    return epub_path, result


def test_script_structure_speakers_and_sids(tmp_path: Path) -> None:
    _, result = _run(tmp_path)
    data = json.loads(result.script_path.read_text(encoding="utf-8"))

    assert data["book_id"] == result.book_id
    assert data["title"] == "Draft Test Book"
    assert data["author"] == "Draft Author"
    assert data["source_sha256"] == result.sha256
    assert "created_at" not in data and "timestamp" not in data

    chapters = data["chapters"]
    assert [c["title"] for c in chapters] == ["Chapter One", "Chapter Two"]
    for raw in chapters:
        # script.json chapters are ChapterFile JSON form (no parallel schema).
        chapter = ChapterFile.from_dict(raw)
        sentences = chapter.sentences_in_order()
        assert sentences, "each chapter keeps at least one sentence"
        assert [s.sid for s in sentences] == list(range(1, len(sentences) + 1))
        assert {s.speaker for s in sentences} == {"narrator"}
        assert all(s.text.strip() for s in sentences)


def test_cast_yaml_narrator_only(tmp_path: Path) -> None:
    _, result = _run(tmp_path)
    cast = yaml.safe_load(result.cast_path.read_text(encoding="utf-8"))
    assert cast == {"narrator": {"engine": "kokoro", "voice": "af_heart", "speed": 1.0}}


def test_report_contains_chapters_counts_drops_estimate(tmp_path: Path) -> None:
    _, result = _run(tmp_path)
    report = result.report_path.read_text(encoding="utf-8")

    assert "Chapter One" in report and "Chapter Two" in report
    assert str(result.total_words) in report
    assert "boilerplate" in report  # the copyright paragraph was dropped
    assert "Estimated audio" in report
    lowered = report.lower()
    assert "rough" in lowered  # estimate basis documented as rough
    assert "chars-per-second" in lowered
    # SW0 RTF is synthesis speed, not speaking rate: must not be conflated.
    assert "RTF" in report


def test_twice_run_byte_identical(tmp_path: Path) -> None:
    epub_path = _make_draft_epub(tmp_path / "mini.epub")
    first = run_draft(epub_path, work_root=tmp_path / "work-a")
    second = run_draft(epub_path, work_root=tmp_path / "work-b")
    rerun = run_draft(epub_path, work_root=tmp_path / "work-a")

    assert first.book_id == second.book_id == rerun.book_id
    for name in ("script.json", "cast.yaml", "draft_report.md"):
        assert (first.work_dir / name).read_bytes() == (second.work_dir / name).read_bytes()
        assert (first.work_dir / name).read_bytes() == (rerun.work_dir / name).read_bytes()


def test_book_id_deterministic_and_exact_construction(tmp_path: Path) -> None:
    epub_path = _make_draft_epub(tmp_path / "mini.epub")
    sha = sha256_of_file(epub_path)
    book_id, sha2 = book_id_for_file(epub_path)

    assert sha == sha2
    assert book_id == book_id_for_sha256(sha)
    # Exact construction: UUIDv5 over NAMESPACE_URL + "auloud:book:<sha256hex>".
    assert BOOK_ID_NAMESPACE == uuid.NAMESPACE_URL
    assert book_id == str(uuid.uuid5(uuid.NAMESPACE_URL, f"{BOOK_ID_PREFIX}{sha}"))
    assert uuid.UUID(book_id).version == 5

    # Same bytes -> same id (copy), different bytes -> different id.
    twin = tmp_path / "twin.epub"
    twin.write_bytes(epub_path.read_bytes())
    assert book_id_for_file(twin)[0] == book_id

    other = tmp_path / "other.epub"
    other.write_bytes(epub_path.read_bytes() + b"\x00")
    assert book_id_for_file(other)[0] != book_id


def test_draft_missing_file_raises(tmp_path: Path) -> None:
    try:
        run_draft(tmp_path / "nope.epub", work_root=tmp_path / ".scribe")
    except DraftError:
        return
    raise AssertionError("expected DraftError for a missing EPUB")


def test_cli_draft_writes_work_folder(tmp_path: Path) -> None:
    epub_path = _make_draft_epub(tmp_path / "mini.epub")
    work_root = tmp_path / "workdir"
    invoked = CliRunner().invoke(cli.app, ["draft", str(epub_path), "--work-dir", str(work_root)])
    assert invoked.exit_code == 0, invoked.output
    assert "book id" in invoked.output.lower()
    assert "work folder" in invoked.output.lower()
    folders = [p for p in work_root.iterdir() if p.is_dir()]
    assert len(folders) == 1
    assert {p.name for p in folders[0].iterdir()} == {
        "script.json",
        "cast.yaml",
        "draft_report.md",
    }


def test_cli_draft_defaults_to_cwd_scribe(tmp_path: Path, monkeypatch: object) -> None:
    epub_path = _make_draft_epub(tmp_path / "mini.epub")
    monkeypatch.chdir(tmp_path)  # type: ignore[union-attr]
    invoked = CliRunner().invoke(cli.app, ["draft", str(epub_path)])
    assert invoked.exit_code == 0, invoked.output
    assert (tmp_path / ".scribe").is_dir()
    assert len(list((tmp_path / ".scribe").iterdir())) == 1
