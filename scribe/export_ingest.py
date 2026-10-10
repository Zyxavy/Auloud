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

"""Shared Slice 9 ingestion test data, exported from Scribe (IN2).

Two artifacts, both deterministic (sorted keys, pipeline order, content
hashes; no timestamps anywhere), both consumed by the Kotlin port (IN6)
and by Scribe's own suite:

- ``spec/fixtures/dialogue-cases.json``: dialogue test vectors. Each case
  is self-describing (id, scope, input paragraphs, expected narration /
  dialogue runs with kind plus text, notes on the rule exercised) and
  covers one behavior pinned by ``tests/test_dialogue.py``: mixed
  sentences, single-quote mode, apostrophes, scare quotes, unbalanced
  quotes, multi-paragraph continuation.
- ``spec/fixtures/ingest-parity/``: per-golden-EPUB parity expectations
  (chapters with titles and word counts, blocks with type and text, runs
  of narration/dialogue per block) plus ``sources.json`` recording which
  fixture sources are covered, skipped, or out of scope.

The case inputs below are transcribed from the pre-existing
``tests/test_dialogue.py`` assertions; the expected runs are computed by
the real splitter (``text.dialogue``) and were checked against those
assertions at creation time. Afterwards the committed JSON is the
contract: ``tests/test_dialogue.py`` consumes it (no duplicated
literals), and ``tests/test_export_ingest.py`` pins freshness (a code
change without re-export fails).

Run from ``scribe/`` with ``uv run --no-sync scribe
export-ingest-fixtures`` (thin CLI wrapper in ``cli.py``), or ``uv run
--no-sync python export_ingest.py`` directly.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

from bundle.models import Block, ChapterFile, Sentence
from extract.clean import count_words, extract_epub_chapters
from text.dialogue import split_chapter_dialogue, split_paragraph_dialogue

#: Version of the dialogue-cases contract (bump on schema change).
DIALOGUE_CASES_VERSION = 1

#: How to evaluate a case (also stamped into the JSON for the Kotlin port).
CONVENTIONS = (
    "Feed input paragraphs in order with quote-state carry: a paragraph "
    "ending mid-quote continues into the next paragraph, and a leading "
    "opener on a continued paragraph is a continuation marker that stays in "
    "the text. Quote numbers restart at 1 per paragraph. Within one "
    "paragraph, merge adjacent same-kind sentences into runs by plain "
    "concatenation (spacing is stored, so concatenation is exact). The run "
    "texts of a paragraph concatenate to the input paragraph exactly. "
    "Scope 'paragraphs' stops there. Scope 'chapter' additionally applies "
    "the chapter-end rule: a trailing unclosed quote chain flips to "
    "narration (see docs/ingestion-rules.md sections 9.5 and 9.10)."
)

#: Paragraph-threading scope (carry continues, no end-of-chapter flip).
SCOPE_PARAGRAPHS = "paragraphs"

#: Full-chapter scope (threaded carry plus the chapter-end flip).
SCOPE_CHAPTER = "chapter"

_LD = "\u201c"
_RD = "\u201d"
_RS = "\u2019"


@dataclass(frozen=True)
class DialogueCase:
    """One dialogue vector: inputs plus the rule it pins.

    ``input`` is one or more paragraphs in order. ``expected`` is never
    stored here: the exporter computes it with the real splitter, so the
    committed JSON always matches verified code at export time.
    """

    id: str
    scope: str
    notes: str
    input: tuple[str, ...] = field(default_factory=tuple)


#: Canonical vector inputs, transcribed from tests/test_dialogue.py.
DIALOGUE_CASES: tuple[DialogueCase, ...] = (
    DialogueCase(
        id="mixed-straight",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Straight double quotes, mixed sentence: splits at the quote "
            "boundary into one dialogue run plus its narration tag."
        ),
        input=('"We should leave," she said.',),
    ),
    DialogueCase(
        id="mixed-curly-multisentence",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Curly double quotes holding several sentences stay one dialogue "
            "run; the trailing narration is a second run."
        ),
        input=(f"{_LD}I am tired. Let us rest.{_RD} Then they slept.",),
    ),
    DialogueCase(
        id="two-quotes-open-order",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Two quotes in one paragraph number in open order: dialogue, "
            "narration tag, dialogue."
        ),
        input=(f"{_LD}I knew it,{_RD} he muttered, {_LD}listen well!{_RD}",),
    ),
    DialogueCase(
        id="singles-no-doubles",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Single quotes form dialogue only when the paragraph has no "
            "double-quote characters at all."
        ),
        input=("'Hello there.' He left.",),
    ),
    DialogueCase(
        id="nested-singles-in-doubles",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "In double-quote mode, single quotes are nested emphasis belonging "
            "to the outer quote: one dialogue region, narration on both sides."
        ),
        input=('He said "hi \'there\' loudly." He nodded.',),
    ),
    DialogueCase(
        id="apostrophe-in-double-mode",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "A curly apostrophe inside a double-quote paragraph never opens a "
            "quote; only the curly double pair counts."
        ),
        input=(f"He thought of Jack{_RS}s tale, {_LD}brave boy,{_RD} and smiled.",),
    ),
    DialogueCase(
        id="curly-apostrophes",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Curly apostrophes with word characters on both sides are never "
            "quote boundaries: no quotes, one narration run."
        ),
        input=(f"The man{_RS}s hat is here. She left.",),
    ),
    DialogueCase(
        id="straight-apostrophes",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Straight apostrophes with word characters on both sides are never "
            "quote boundaries: no quotes, one narration run."
        ),
        input=("The man's hat is here. She left.",),
    ),
    DialogueCase(
        id="multisentence-singles-one-quote",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "A single-quoted region spanning many sentences stays one quote: "
            "several dialogue sentences merge into one run."
        ),
        input=("'One. Two. Three. Four. Five.' They all left.",),
    ),
    DialogueCase(
        id="multisentence-doubles-one-quote",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "A curly double-quoted region spanning five sentences stays one "
            "quote (gold block 11 shape); every sentence shares the run."
        ),
        input=(
            f"{_LD}I thought so! That{_RS}s the worst of all! "
            f"Why, a stupid thing like this might spoil the whole plan. "
            f"Yes, my hat is too noticeable. It looks absurd.{_RD} He sighed.",
        ),
    ),
    DialogueCase(
        id="mixed-spacing-roundtrip",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Mixed sentence with double spaces: runs keep stored spacing, so "
            "concatenation reproduces the paragraph byte-exactly."
        ),
        input=('"Hello."  Double  spaced.  She left.',),
    ),
    DialogueCase(
        id="leading-trailing-space-roundtrip",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Leading and trailing padding plus double inner gaps survive the "
            "split: whitespace-only edges attach, never lost."
        ),
        input=(f"  Leading space {_LD}quoted words.{_RD}  Trailing.  ",),
    ),
    DialogueCase(
        id="spacing-tabs-newlines",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Tabs and newlines are spacing like any other: the split keeps "
            "them stored, concatenation round-trips."
        ),
        input=('Tabs\tinside quotes "hi there." New\nlines. Mixed   spacing.',),
    ),
    DialogueCase(
        id="inch-mark-stray",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Unbalanced: a straight quote after a digit is an inch mark, never "
            "a boundary. Logged and treated as narration."
        ),
        input=('He bought a 5" nail. It hurt.',),
    ),
    DialogueCase(
        id="intact-pair-with-stray",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Unbalanced with an intact pair: the real quote is still honored, "
            "only the stray inch mark is logged."
        ),
        input=('"Hi. Bye." She waved. He bought a 5" nail. It hurt.',),
    ),
    DialogueCase(
        id="scare-quotes-block12",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Scare-quote rule (gold block 12 shape): short closed fragments "
            "with no terminal punctuation and no trailing comma are emphasis, "
            "not dialogue. Zero quotes."
        ),
        input=(
            "He had counted them once when he had been lost in dreams. "
            f"He had come to regard this {_LD}hideous{_RD} dream as an exploit. "
            f"He was going now for a {_LD}rehearsal{_RD} of his project, "
            "and at every step his excitement grew.",
        ),
    ),
    DialogueCase(
        id="gold-block-8",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Gold shape block 8: two dialogue quotes around one narration tag."
        ),
        input=(
            f"{_LD}I want to attempt a thing like that,{_RD} he thought. "
            f"{_LD}Hm, yes, indeed.{_RD}",
        ),
    ),
    DialogueCase(
        id="gold-block-10",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Gold shape block 10: a four-word quoted fragment is real dialogue "
            "(over the two-word scare limit), with narration on both sides."
        ),
        input=(f"He shouted as he drove past: {_LD}Hey there, German hatter{_RD} loudly.",),
    ),
    DialogueCase(
        id="gold-block-11",
        scope=SCOPE_PARAGRAPHS,
        notes=(
            "Gold shape block 11: short quote, tag sentence, then a "
            "multi-sentence quote as the second region."
        ),
        input=(f"{_LD}I knew it,{_RD} he muttered. {_LD}I thought so! Ruin!{_RD}",),
    ),
    DialogueCase(
        id="multiparagraph-continuation",
        scope=SCOPE_CHAPTER,
        notes=(
            "Multi-paragraph quote: the first paragraph ends open, the next "
            "starts with an opener consumed as a continuation marker (kept in "
            "the text). Both paragraphs read as dialogue, the second flagged "
            "continued in Scribe."
        ),
        input=(
            f"{_LD}We march on through the night with heavy hearts.",
            f"{_LD}and on until the morning comes.{_RD} He sighed.",
        ),
    ),
    DialogueCase(
        id="unclosed-single-paragraph",
        scope=SCOPE_CHAPTER,
        notes=(
            "Unbalanced at chapter end: a single paragraph that opens but "
            "never closes flips to narration with a logged warning."
        ),
        input=('"Unclosed quote here. It keeps going.',),
    ),
    DialogueCase(
        id="unclosed-multiparagraph-chain",
        scope=SCOPE_CHAPTER,
        notes=(
            "Unbalanced chain: two paragraphs linked by continuation with no "
            "closing quote anywhere flip wholly to narration at chapter end."
        ),
        input=(
            f"{_LD}We march on through the night.",
            f"{_LD}and on, never stopping.",
        ),
    ),
    DialogueCase(
        id="chapter-round-trip",
        scope=SCOPE_CHAPTER,
        notes=(
            "Plain narration around a mixed mid paragraph: every block "
            "concatenates back to its input exactly (Player spacing rule)."
        ),
        input=(
            "On a hot evening a young man came out of his garret. He hesitated.",
            f"{_LD}I want to attempt a thing like that,{_RD} he thought. {_LD}Hm, yes.{_RD}",
            "The heat in the street was terrible. He walked on.",
        ),
    ),
)


class ExportError(ValueError):
    """The ingest export cannot proceed (missing fixtures, bad source)."""


def merge_runs(kinds_and_texts: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """Merge adjacent same-kind sentences into runs by concatenation.

    Texts already carry their spacing (the Player spacing rule), so plain
    concatenation reproduces the paragraph exactly. One entry per maximal
    same-kind stretch, in order.
    """
    runs: list[tuple[str, str]] = []
    for kind, text in kinds_and_texts:
        if runs and runs[-1][0] == kind:
            runs[-1] = (kind, runs[-1][1] + text)
        else:
            runs.append((kind, text))
    return runs


def runs_for_paragraphs(paragraphs: list[str]) -> list[dict[str, object]]:
    """Thread paragraphs through the splitter with quote-state carry.

    Mirrors ``split_chapter_dialogue`` without the chapter-end flip:
    ``in_quote`` carries forward, quote numbers restart at 1 per
    paragraph. Returns run dicts (paragraph index, kind, text).
    """
    runs: list[dict[str, object]] = []
    in_quote = False
    for index, paragraph in enumerate(paragraphs):
        result = split_paragraph_dialogue(
            paragraph, chapter=1, block=index + 1, start_quote=1, in_quote=in_quote
        )
        in_quote = result.out_quote
        for kind, text in merge_runs([(s.kind, s.text) for s in result.sentences]):
            runs.append({"paragraph": index, "kind": kind, "text": text})
    return runs


def _chapter_file(paragraphs: list[str]) -> ChapterFile:
    """Build a para-only chapter (block ids 1..N) from input paragraphs."""
    blocks = [
        Block(
            id=pos,
            type="para",
            sentences=[
                Sentence(sid=1, speaker="narrator", start_ms=0, end_ms=0, text=text, spans=[])
            ],
        )
        for pos, text in enumerate(paragraphs, start=1)
    ]
    return ChapterFile(spec_version="1.0", chapter=1, title="Vector", duration_ms=0, blocks=blocks)


def runs_for_chapter(paragraphs: list[str]) -> list[dict[str, object]]:
    """Run a full chapter split (threaded carry plus the chapter-end flip)."""
    result = split_chapter_dialogue(_chapter_file(paragraphs))
    by_block: dict[int, list[tuple[str, str]]] = {}
    for tagged in result.tagged:
        by_block.setdefault(tagged.block, []).append((tagged.kind, tagged.text))
    runs: list[dict[str, object]] = []
    for pos in range(1, len(paragraphs) + 1):
        for kind, text in merge_runs(by_block.get(pos, [])):
            runs.append({"paragraph": pos - 1, "kind": kind, "text": text})
    return runs


def expected_runs(case: DialogueCase) -> list[dict[str, object]]:
    """Expected runs for one case under its scope, with round-trip check."""
    paragraphs = list(case.input)
    if case.scope == SCOPE_CHAPTER:
        runs = runs_for_chapter(paragraphs)
    elif case.scope == SCOPE_PARAGRAPHS:
        runs = runs_for_paragraphs(paragraphs)
    else:
        raise ExportError(f"case {case.id!r}: unknown scope {case.scope!r}")
    for index, paragraph in enumerate(paragraphs):
        joined = "".join(str(r["text"]) for r in runs if r["paragraph"] == index)
        if joined != paragraph:
            raise ExportError(f"case {case.id!r}: runs do not round-trip paragraph {index}")
    return runs


def build_dialogue_cases() -> dict[str, object]:
    """Build the dialogue-cases document (JSON-able, deterministic order)."""
    cases: list[dict[str, object]] = []
    seen: set[str] = set()
    for case in DIALOGUE_CASES:
        if case.id in seen:
            raise ExportError(f"duplicate dialogue case id {case.id!r}")
        seen.add(case.id)
        cases.append(
            {
                "id": case.id,
                "scope": case.scope,
                "notes": case.notes,
                "input": list(case.input),
                "expected": expected_runs(case),
            }
        )
    return {"version": DIALOGUE_CASES_VERSION, "conventions": CONVENTIONS, "cases": cases}


def _sha256_of_file(path: Path) -> str:
    """Lowercase hex sha256 of a file's bytes (streamed, 1 MiB chunks).

    Same scheme as ``draft.sha256_of_file`` (kept local so this module
    never imports the draft pipeline).
    """
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _block_text(block: Block) -> str:
    """Readable text of a chapter block (stored text, else joined sentences)."""
    if block.text:
        return block.text
    return "".join(s.text for s in block.sentences)


def build_book_parity(epub_path: Path, *, fixture: str, rel_file: str) -> dict[str, object]:
    """Build parity expectations for one golden EPUB through the real pipeline.

    Extraction (``extract.clean``) then dialogue splitting
    (``text.dialogue``); sentence splitting rides inside the dialogue step,
    which rebuilds each paragraph by joining. Chapters carry index, title
    and word counts; blocks carry id, type and text; para/quote blocks also
    carry merged narration/dialogue runs (kind plus text).
    """
    extracted = extract_epub_chapters(epub_path)
    chapters: list[dict[str, object]] = []
    for chapter_file in extracted.chapters:
        dialogue = split_chapter_dialogue(chapter_file)
        by_block: dict[int, list[tuple[str, str]]] = {}
        for tagged in dialogue.tagged:
            by_block.setdefault(tagged.block, []).append((tagged.kind, tagged.text))
        blocks: list[dict[str, object]] = []
        words = 0
        for block in dialogue.chapter.blocks or []:
            text = _block_text(block)
            if block.type in ("heading", "para", "quote"):
                words += count_words(text)
            entry: dict[str, object] = {"id": block.id, "type": block.type, "text": text}
            if block.type in ("para", "quote"):
                entry["runs"] = [
                    {"kind": kind, "text": run_text}
                    for kind, run_text in merge_runs(by_block.get(block.id, []))
                ]
            blocks.append(entry)
        chapters.append(
            {
                "index": chapter_file.chapter,
                "title": chapter_file.title,
                "words": words,
                "blocks": blocks,
            }
        )
    return {
        "source": {
            "fixture": fixture,
            "file": rel_file,
            "sha256": _sha256_of_file(epub_path),
        },
        "chapters": chapters,
    }


def canonical_json(document: dict[str, object]) -> str:
    """Deterministic JSON text: sorted keys, 2-space indent, UTF-8, newline."""
    return json.dumps(document, sort_keys=True, indent=2, ensure_ascii=False) + "\n"


def _write_json(path: Path, document: dict[str, object]) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(canonical_json(document), encoding="utf-8")
    return path


def default_fixtures_dir() -> Path:
    """``spec/fixtures`` under the repo root (this file lives in ``scribe/``)."""
    return Path(__file__).resolve().parents[1] / "spec" / "fixtures"


def export_all(fixtures_dir: Path | str | None = None) -> list[Path]:
    """Write dialogue vectors plus ingest-parity files; return written paths.

    Discovers ``*/source/book.epub`` (and ``book.pdf``) under
    ``spec/fixtures`` in sorted order: parseable EPUBs get a parity file,
    anything else is recorded in ``sources.json`` with its reason, so later
    packages add books by dropping in fixtures (no code change).
    """
    root = Path(fixtures_dir) if fixtures_dir is not None else default_fixtures_dir()
    if not root.is_dir():
        raise ExportError(f"fixtures dir not found: {root}")
    parity_dir = root / "ingest-parity"
    written: list[Path] = [_write_json(root / "dialogue-cases.json", build_dialogue_cases())]

    epubs = sorted(root.glob("*/source/book.epub"))
    pdfs = sorted(root.glob("*/source/book.pdf"))
    if not epubs and not pdfs:
        raise ExportError(f"no fixture sources under {root}")
    sources: list[dict[str, object]] = []
    for source in epubs:
        fixture = source.parents[1].name
        rel_file = f"source/{source.name}"
        try:
            document = build_book_parity(source, fixture=fixture, rel_file=rel_file)
        except Exception as exc:  # noqa: BLE001 - reason recorded, never raised
            reason = f"{type(exc).__name__}: {exc}"[:160]
            sources.append(
                {
                    "fixture": fixture,
                    "file": rel_file,
                    "sha256": _sha256_of_file(source),
                    "status": "skipped",
                    "reason": reason,
                }
            )
            continue
        out_name = f"{fixture}.json"
        _write_json(parity_dir / out_name, document)
        written.append(parity_dir / out_name)
        sources.append(
            {
                "fixture": fixture,
                "file": rel_file,
                "sha256": _sha256_of_file(source),
                "status": "covered",
                "parity": out_name,
            }
        )
    for source in pdfs:
        fixture = source.parents[1].name
        sources.append(
            {
                "fixture": fixture,
                "file": f"source/{source.name}",
                "sha256": _sha256_of_file(source),
                "status": "out-of-scope",
                "reason": "Slice 9 ingestion is EPUB-only; PDF stays on Scribe",
            }
        )
    written.append(
        _write_json(
            parity_dir / "sources.json",
            {"generator": "scribe export-ingest-fixtures", "sources": sources},
        )
    )
    return sorted(written)


def main(argv: list[str] | None = None) -> int:
    """Entry point for ``python export_ingest.py`` (the CLI wraps this)."""
    parser = argparse.ArgumentParser(description="Export shared Slice 9 ingestion test data.")
    parser.add_argument(
        "--fixtures-dir",
        type=Path,
        default=None,
        help="spec/fixtures dir (default: <repo-root>/spec/fixtures).",
    )
    args = parser.parse_args(argv)
    try:
        written = export_all(args.fixtures_dir)
    except ExportError as exc:
        print(f"export failed: {exc}", file=sys.stderr)
        return 1
    for path in written:
        print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
