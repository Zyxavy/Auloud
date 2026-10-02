# Auloud Scribe turns ebooks into multi-voice audiobooks (PC tool).
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

"""MV1 speaker-attribution eval harness: score a predictor over the gold set.

The gold lives in ``spec/fixtures/speakers-gold/`` (one YAML file per book
chapter; entries keyed by ``(book, chapter, block, quote number)``, never by
``sid`` (see that folder's README). This script loads it, runs attribution
over every line, and prints accuracy overall and by predicted confidence
level (high/medium/low).

Attribution rules do NOT exist yet (MV2-MV4), so the predictor is a trivial
baseline: always ``("unknown", "low")``. Running this now reports ~0%, that
IS the MV1 verify condition (the harness runs end to end, the number comes
later). MV2-MV4 only swap the predictor function::

    predict(quote_context) -> (speaker, confidence)

``QuoteContext`` will grow (surrounding text, candidate speakers, ...) as
the rules arrive; the signature stays. A prediction counts as correct when
it matches the gold speaker after stripping and casefolding (speaker keys
are normalized again at MV5 resolution, so ``"raskolnikov"`` matches
``"Raskolnikov"``). Lines labelled ``uncertain: true`` are excluded from
accuracy (a predictor should not be punished for human guesses) and only
reported as counts.

Predictors MUST output CANONICAL speaker names exactly as the gold
``speaker`` labels them (``"Raskolnikov"``, ``"drunken man"``). Alias
resolution (``"the young man"`` -> ``"Raskolnikov"``) arrives in MV5 with
``cast.yaml``; until then matching is exact after stripping and
casefolding, with NO alias matching. A predictor emitting a non-canonical
variant is simply wrong.

Run directly (NOT a ``scribe`` command)::

    uv run python dev/eval_speakers.py [--gold-dir <dir>]
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

import yaml

#: Confidence levels MV4 rules produce, in display order. Unknown levels a
#: future predictor invents are shown after these, alphabetically.
CONFIDENCE_LEVELS: tuple[str, ...] = ("high", "medium", "low")


class GoldError(ValueError):
    """A gold file is missing, unparsable, or fails validation."""


@dataclass(frozen=True)
class GoldEntry:
    """One hand-labelled dialogue line (the eval unit).

    ``book`` is the source-book id (e.g. ``"crime-and-punishment"``); older
    gold files omit it (and its ``source`` alias) and load as ``""``.
    """

    chapter: int
    block: int
    quote: int
    excerpt: str
    speaker: str
    uncertain: bool = False
    book: str = ""

    @property
    def key(self) -> tuple[str, int, int, int]:
        """Stable anchor: ``(book, chapter, block, quote number)``, never ``sid``."""
        return (self.book, self.chapter, self.block, self.quote)


@dataclass(frozen=True)
class QuoteContext:
    """What the predictor sees for one gold line (MV2 expands this).

    Only ``chapter``/``block``/``quote``/``excerpt`` (plus ``book``) are
    filled from the gold file itself; MV3/MV4 fill ``block_text`` (the full
    paragraph holding the quote), ``prev_text``/``next_text`` (neighbouring
    paragraphs), and ``full_quote`` (the whole quote when the excerpt is a
    prefix of a multi-sentence speech). All default to ``""`` so older
    callers keep working.
    """

    chapter: int
    block: int
    quote: int
    excerpt: str
    book: str = ""
    block_text: str = ""
    prev_text: str = ""
    next_text: str = ""
    full_quote: str = ""

    @classmethod
    def from_gold(cls, entry: GoldEntry) -> QuoteContext:
        """Context for a gold line (anchor plus excerpt; text fields empty)."""
        return cls(
            chapter=entry.chapter,
            block=entry.block,
            quote=entry.quote,
            excerpt=entry.excerpt,
            book=entry.book,
        )


def predict_baseline(context: QuoteContext) -> tuple[str, str]:
    """Trivial MV1 predictor: every line is ``unknown`` at ``low`` confidence.

    MV2-MV4 replace the body (or rebind :func:`predict`); the signature and
    the ``(speaker, confidence)`` contract stay.
    """
    _ = context
    return ("unknown", "low")


def predict(context: QuoteContext) -> tuple[str, str]:
    """Attribution entry point the harness scores (currently the baseline)."""
    return predict_baseline(context)


def default_gold_dir() -> Path:
    """``spec/fixtures/speakers-gold/`` under the repo root."""
    return Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "speakers-gold"


def validate_raw_entries(raw: object, source: str) -> list[GoldEntry]:
    """Validate decoded YAML ``raw`` from ``source``; return sorted entries.

    Raises :class:`GoldError` on any shape problem: not a list, missing or
    mistyped required keys (``chapter``/``block``/``quote`` ints,
    ``excerpt``/``speaker`` non-empty strings), an unknown key, a present-but-non-bool
    ``uncertain`` flag, a present-but-empty ``book``/``source`` label, both
    ``book`` and ``source`` present but disagreeing, or a duplicate
    ``(book, chapter, block, quote)`` key. ``book`` (alias ``source``) is
    optional and defaults to ``""`` so MV1-era files without it still load.
    """
    if not isinstance(raw, list):
        raise GoldError(f"{source}: gold file must hold a YAML list of entries")
    entries: list[GoldEntry] = []
    seen: set[tuple[str, int, int, int]] = set()
    allowed = {"chapter", "block", "quote", "excerpt", "speaker", "uncertain", "book", "source"}
    for index, item in enumerate(raw):
        where = f"{source} entry #{index + 1}"
        if not isinstance(item, dict):
            raise GoldError(f"{where}: must be a mapping, got {type(item).__name__}")
        unknown = set(item) - allowed
        if unknown:
            raise GoldError(f"{where}: unknown key(s) {sorted(unknown)}")
        for key in ("chapter", "block", "quote"):
            value = item.get(key)
            if not isinstance(value, int) or isinstance(value, bool):
                raise GoldError(f"{where}: {key!r} must be an int")
            if value < 1:
                raise GoldError(f"{where}: {key!r} must be >= 1")
        for key in ("excerpt", "speaker"):
            value = item.get(key)
            if not isinstance(value, str) or not value.strip():
                raise GoldError(f"{where}: {key!r} must be a non-empty string")
        uncertain = item.get("uncertain", False)
        if not isinstance(uncertain, bool):
            raise GoldError(f"{where}: 'uncertain' must be a bool when present")
        book = _book_label(item, where)
        key = (book, item["chapter"], item["block"], item["quote"])
        if key in seen:
            raise GoldError(f"{where}: duplicate quote key {key}")
        seen.add(key)
        entries.append(
            GoldEntry(
                chapter=item["chapter"],
                block=item["block"],
                quote=item["quote"],
                excerpt=item["excerpt"],
                speaker=item["speaker"],
                uncertain=uncertain,
                book=book,
            )
        )
    entries.sort(key=lambda e: e.key)
    return entries


def _book_label(item: dict[object, object], where: str) -> str:
    """Gold ``book`` label: ``book`` (or its ``source`` alias), else ``""``."""
    has_book = "book" in item
    has_source = "source" in item
    if not has_book and not has_source:
        return ""
    if has_book and has_source:
        book, source = item["book"], item["source"]
        if not isinstance(book, str) or not book.strip():
            raise GoldError(f"{where}: 'book' must be a non-empty string when present")
        if not isinstance(source, str) or not source.strip():
            raise GoldError(f"{where}: 'source' must be a non-empty string when present")
        if book.strip() != source.strip():
            raise GoldError(f"{where}: 'book' and 'source' disagree")
        return book.strip()
    label = item["book"] if has_book else item["source"]
    name = "book" if has_book else "source"
    if not isinstance(label, str) or not label.strip():
        raise GoldError(f"{where}: {name!r} must be a non-empty string when present")
    return label.strip()


def load_gold_entries(gold_dir: Path | str) -> tuple[list[GoldEntry], int]:
    """Load and validate every ``*.yaml``/``*.yml`` in ``gold_dir``.

    Returns ``(entries, file_count)`` sorted by quote key. Raises
    :class:`GoldError` when the dir is missing, holds no gold files, or any
    file fails to parse or validate (duplicate keys across files also fail).
    """
    directory = Path(gold_dir)
    if not directory.is_dir():
        raise GoldError(f"gold dir not found: {directory}")
    files = sorted([*directory.glob("*.yaml"), *directory.glob("*.yml")])
    if not files:
        raise GoldError(f"no gold YAML files in {directory}")
    entries: list[GoldEntry] = []
    seen: set[tuple[str, int, int, int]] = set()
    for path in files:
        try:
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
        except yaml.YAMLError as exc:
            raise GoldError(f"{path.name}: invalid YAML ({exc})") from exc
        for entry in validate_raw_entries(raw, path.name):
            if entry.key in seen:
                raise GoldError(f"{path.name}: duplicate quote key {entry.key} (seen before)")
            seen.add(entry.key)
            entries.append(entry)
    entries.sort(key=lambda e: e.key)
    return entries, len(files)


def _matches(predicted: str, truth: str) -> bool:
    """Speaker equality after stripping and casefolding (see module docstring).

    Exact canonical-name match only: no alias, fuzzy, or substring matching
    (MV5 resolves aliases via ``cast.yaml``).
    """
    return predicted.strip().casefold() == truth.strip().casefold()


@dataclass
class EvalResult:
    """Scores for one predictor run (accuracy over certain lines only)."""

    total: int = 0
    certain: int = 0
    uncertain: int = 0
    correct: int = 0
    accuracy: float | None = None
    by_level: dict[str, tuple[int, int]] = field(default_factory=dict)
    uncertain_agree: int = 0


def evaluate(
    entries: list[GoldEntry],
    predictor: Callable[[QuoteContext], tuple[str, str]] | None = None,
) -> EvalResult:
    """Score ``predictor`` over ``entries`` (certain lines count, see docstring)."""
    if predictor is None:
        predictor = predict
    result = EvalResult(total=len(entries))
    for entry in entries:
        speaker, confidence = predictor(QuoteContext.from_gold(entry))
        level = str(confidence).lower()
        if entry.uncertain:
            result.uncertain += 1
            if _matches(speaker, entry.speaker):
                result.uncertain_agree += 1
            continue
        result.certain += 1
        correct, total = result.by_level.get(level, (0, 0))
        hit = _matches(speaker, entry.speaker)
        result.by_level[level] = (correct + (1 if hit else 0), total + 1)
        if hit:
            result.correct += 1
    if result.certain:
        result.accuracy = result.correct / result.certain
    return result


def _pct(correct: int, total: int) -> str:
    if not total:
        return "n/a (0 lines)"
    return f"{correct}/{total} = {100.0 * correct / total:.1f}%"


def format_result(result: EvalResult, *, predictor_name: str, file_count: int) -> str:
    """Human-readable report: overall accuracy plus the by-level breakdown."""
    lines = [
        f"predictor: {predictor_name}",
        f"gold: {file_count} file(s), {result.total} line(s): "
        f"{result.certain} certain, {result.uncertain} uncertain",
        f"overall (certain lines): {_pct(result.correct, result.certain)}",
        "by predicted confidence (certain lines):",
    ]
    ordered = [lv for lv in CONFIDENCE_LEVELS if lv in result.by_level]
    ordered += sorted(lv for lv in result.by_level if lv not in CONFIDENCE_LEVELS)
    for level in ordered:
        correct, total = result.by_level[level]
        lines.append(f"  {level}: {_pct(correct, total)}")
    for level in CONFIDENCE_LEVELS:
        if level not in result.by_level:
            lines.append(f"  {level}: n/a (0 lines)")
    lines.append(
        f"uncertain lines: {result.uncertain} excluded from accuracy "
        f"(predictor agreed on {result.uncertain_agree})"
    )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    """Load the gold set, run the baseline predictor, print the report."""
    parser = argparse.ArgumentParser(description="Score speaker attribution over the gold set.")
    parser.add_argument(
        "--gold-dir",
        type=Path,
        default=default_gold_dir(),
        help="gold YAML folder (default: spec/fixtures/speakers-gold/)",
    )
    args = parser.parse_args(argv)
    try:
        entries, file_count = load_gold_entries(args.gold_dir)
    except GoldError as exc:
        print(f"eval failed: {exc}", file=sys.stderr)
        return 1
    result = evaluate(entries)
    report = format_result(
        result, predictor_name="baseline (always 'unknown', low)", file_count=file_count
    )
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
