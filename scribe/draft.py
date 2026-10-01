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

"""``scribe draft`` pipeline (SW5): EPUB -> work folder.

Parse (SW3 :func:`extract.clean.extract_epub_chapters`) then split sentences
(SW4 :func:`text.sentences.split_chapter`), then write the work folder
``.scribe/<book-id>/`` with:

- ``script.json`` — book metadata plus chapters as SW2
  :class:`bundle.models.ChapterFile` dicts (chapters > blocks > sentences,
  every sentence ``speaker == "narrator"``). No audio timings yet (SW7).
- ``cast.yaml`` — minimal narrator-only cast (see :mod:`text.cast`).
- ``draft_report.md`` — chapter list, word counts, dropped elements and a
  rough estimated audio duration.

Book id construction (deterministic, so rebuilds keep the id and the Player
keeps saved progress)::

    book_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "auloud:book:" + sha256_hex))

where ``sha256_hex`` is the lowercase hex sha256 of the source file bytes.
Same bytes always give the same id; different bytes give a different id.

No timestamps are written anywhere, so running draft twice on the same file
produces byte-identical output. No network access; everything runs locally.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from bundle.models import ChapterFile
from extract.clean import count_words, extract_epub_chapters
from text.cast import write_cast
from text.sentences import split_chapter

BOOK_ID_NAMESPACE = uuid.NAMESPACE_URL
BOOK_ID_PREFIX = "auloud:book:"

SCRIPT_FILENAME = "script.json"
CAST_FILENAME = "cast.yaml"
REPORT_FILENAME = "draft_report.md"

# Rough speaking-rate heuristic for the duration estimate: ~150 words/min at
# ~6 characters per word (5 letters + space) is ~900 chars/min, i.e. ~15
# characters per second. This is a guess at how fast the narrator voice
# *speaks*, deliberately separate from the SW0 real-time factor (RTF 1.38),
# which measures how fast the engine *synthesizes* — synthesis speed must
# never be conflated with speaking rate. The estimate is marked rough in the
# report and exists only to sanity-size a build.
EST_CHARS_PER_SEC = 15.0


class DraftError(ValueError):
    """``scribe draft`` cannot proceed (missing file, empty book, ...)."""


def sha256_of_file(path: Path | str) -> str:
    """Lowercase hex sha256 of a file's bytes (streamed, EPUBs can be big)."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def book_id_for_sha256(sha256_hex: str) -> str:
    """Deterministic book id: ``uuid5(NAMESPACE_URL, "auloud:book:<hex>")``."""
    return str(uuid.uuid5(BOOK_ID_NAMESPACE, f"{BOOK_ID_PREFIX}{sha256_hex.lower()}"))


def book_id_for_file(path: Path | str) -> tuple[str, str]:
    """``(book_id, sha256_hex)`` for a source file's bytes."""
    sha = sha256_of_file(path)
    return book_id_for_sha256(sha), sha


def read_book_metadata(epub_path: Path | str) -> tuple[str, str | None]:
    """``(title, author)`` from EPUB Dublin Core; title falls back to stem.

    Returns author ``None`` when the EPUB names none. Never raises for
    missing metadata — a draft must work on messy real-world EPUBs.
    """
    from ebooklib import epub as ebooklib_epub

    path = Path(epub_path)
    fallback = path.stem

    def _first(values: object) -> str | None:
        if not isinstance(values, (list, tuple)) or not values:
            return None
        first = values[0]
        if isinstance(first, (list, tuple)):
            first = first[0] if first else None
        if not isinstance(first, str):
            return None
        return first.strip() or None

    try:
        book = ebooklib_epub.read_epub(str(path))
        title = _first(book.get_metadata("DC", "title")) or fallback
        author = _first(book.get_metadata("DC", "creator"))
    except Exception:
        title, author = fallback, None
    return title, author


def _sentence_stats(chapter: ChapterFile) -> tuple[int, int, int]:
    """``(sentence_count, word_count, char_count)`` for one split chapter.

    Chars are stripped sentence lengths (spacing is not spoken, so it does
    not count toward the duration estimate).
    """
    sentences = chapter.sentences_in_order()
    words = sum(count_words(s.text) for s in sentences)
    chars = sum(len(s.text.strip()) for s in sentences)
    return len(sentences), words, chars


def estimate_seconds(total_chars: int) -> float:
    """Rough audio seconds for ``total_chars`` at EST_CHARS_PER_SEC."""
    return total_chars / EST_CHARS_PER_SEC


def format_duration(seconds: float) -> str:
    """``H:MM:SS`` (or ``M:SS`` under an hour) for a rough estimate."""
    total = int(round(seconds))
    hours, rest = divmod(total, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"


@dataclass
class DraftResult:
    """Outcome of :func:`run_draft` (paths plus the numbers in the report)."""

    book_id: str
    sha256: str
    title: str
    author: str | None
    work_dir: Path
    script_path: Path
    cast_path: Path
    report_path: Path
    chapters: list[ChapterFile] = field(default_factory=list)
    drops: list[str] = field(default_factory=list)
    total_sentences: int = 0
    total_words: int = 0
    total_chars: int = 0
    estimated_seconds: float = 0.0


def _write_script(
    path: Path,
    *,
    book_id: str,
    title: str,
    author: str | None,
    source_file: str,
    source_sha256: str,
    chapters: list[ChapterFile],
) -> None:
    """Write script.json deterministically (sorted keys, no timestamps)."""
    data = {
        "author": author,
        "book_id": book_id,
        "chapters": [c.to_dict() for c in chapters],
        "source_file": source_file,
        "source_sha256": source_sha256,
        "title": title,
    }
    path.write_text(
        json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def _write_report(
    path: Path,
    *,
    result: DraftResult,
    source_name: str,
    per_chapter: list[tuple[int, str, int, int, float]],
) -> None:
    """Write draft_report.md (chapter list, counts, drops, rough estimate)."""
    lines = [
        f"# Draft report: {result.title}",
        "",
        f"- Book id: `{result.book_id}`",
        "  (UUIDv5 with namespace `NAMESPACE_URL` over the name "
        "`\"auloud:book:<sha256-hex>\"`, where `<sha256-hex>` is the "
        "source file's sha256 — same bytes always give the same id.)",
        f"- Source: `{source_name}` (sha256 `{result.sha256}`)",
        f"- Author: {result.author or '(unknown)'}",
        f"- Chapters: {len(result.chapters)}, "
        f"sentences: {result.total_sentences}, "
        f"words: {result.total_words}, "
        f"characters: {result.total_chars}",
        f"- Estimated audio: ~{format_duration(result.estimated_seconds)} "
        f"({result.estimated_seconds:.0f} s) — ROUGH, see basis below.",
        "",
        "## Chapters",
        "",
        "| # | Title | Sentences | Words | Est. audio |",
        "| --- | --- | --- | --- | --- |",
    ]
    for index, title, sentences, words, seconds in per_chapter:
        safe_title = title.replace("|", "\\|")
        lines.append(
            f"| {index} | {safe_title} | {sentences} | {words} "
            f"| ~{format_duration(seconds)} |"
        )
    lines += [
        "",
        "## Dropped elements",
        "",
    ]
    if result.drops:
        lines += [f"- {drop}" for drop in result.drops]
    else:
        lines.append("None — every spine element survived cleaning.")
    lines += [
        "",
        "## Estimation basis (rough)",
        "",
        f"- Heuristic: stripped sentence characters / {EST_CHARS_PER_SEC:.0f} "
        "chars-per-second (~150 words/min at ~6 chars/word).",
        "- This is a guess at speaking rate, NOT a measurement — actual "
        "audio length comes from synthesis (SW7 timings).",
        "- Do not confuse this with the SW0 real-time factor (RTF 1.38 on "
        "CPU): RTF measures how fast the engine *synthesizes* audio, not "
        "how fast the narrator *speaks*.",
        "",
        "Next: review `script.json`, edit `cast.yaml` if needed, then run "
        "`scribe build` (SW9) to render audio.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def run_draft(
    epub_path: Path | str, *, work_root: Path | str = Path(".scribe")
) -> DraftResult:
    """Parse, split, and write ``<work_root>/<book-id>/``; return the result.

    Raises :class:`DraftError` when the source is missing or yields no
    chapters. Writing is deterministic: same input bytes produce
    byte-identical ``script.json``, ``cast.yaml`` and ``draft_report.md``.
    """
    source = Path(epub_path)
    if not source.is_file():
        raise DraftError(f"EPUB not found: {source}")

    book_id, sha = book_id_for_file(source)
    title, author = read_book_metadata(source)

    extracted = extract_epub_chapters(source)
    chapters = [split_chapter(c) for c in extracted.chapters]
    if not chapters:
        raise DraftError(f"{source.name}: no chapters survived extraction")

    work_dir = Path(work_root) / book_id
    work_dir.mkdir(parents=True, exist_ok=True)
    script_path = work_dir / SCRIPT_FILENAME
    cast_path = work_dir / CAST_FILENAME
    report_path = work_dir / REPORT_FILENAME

    _write_script(
        script_path,
        book_id=book_id,
        title=title,
        author=author,
        source_file=source.name,
        source_sha256=sha,
        chapters=chapters,
    )
    write_cast(cast_path)

    per_chapter: list[tuple[int, str, int, int, float]] = []
    total_sentences = total_words = total_chars = 0
    for chapter in chapters:
        sentences, words, chars = _sentence_stats(chapter)
        seconds = estimate_seconds(chars)
        per_chapter.append((chapter.chapter, chapter.title, sentences, words, seconds))
        total_sentences += sentences
        total_words += words
        total_chars += chars
    estimated = estimate_seconds(total_chars)

    result = DraftResult(
        book_id=book_id,
        sha256=sha,
        title=title,
        author=author,
        work_dir=work_dir,
        script_path=script_path,
        cast_path=cast_path,
        report_path=report_path,
        chapters=chapters,
        drops=list(extracted.drops),
        total_sentences=total_sentences,
        total_words=total_words,
        total_chars=total_chars,
        estimated_seconds=estimated,
    )
    _write_report(report_path, result=result, source_name=source.name, per_chapter=per_chapter)
    return result
