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
over every line, and prints accuracy overall, by predicted confidence
level (high/medium/low), and by attribution rule.

``QuoteContext`` (the predictor input) lives in ``text/speakers.py`` and is
re-exported here, so ``from dev.eval_speakers import QuoteContext`` keeps
working; the library never imports ``dev`` (the wheel ships no ``dev/``).

Predictors MUST output CANONICAL speaker names exactly as the gold
``speaker`` labels them (``"Raskolnikov"``, ``"drunken man"``), scored by
default in strict mode: exact match after stripping and casefolding, with
NO alias matching. MV4 predictors emit tag surfaces instead (``"he"``,
``"the young man"``), which strict mode scores wrong; run those with
``--alias-aware`` (or ``evaluate(..., alias_aware=True)``), which also
accepts each entry's optional hand-recorded ``surface`` field. A genuinely
different canonical name is still wrong in both modes. MV5 resolution
(``cast.yaml``) will let predictors output canonical names again; until
then matching is exact after stripping and casefolding.

``evaluate`` accepts predictors returning ``(speaker, confidence)`` or
``(speaker, confidence, rule)``; the rule element feeds the by-rule table.
Lines labelled ``uncertain: true`` are excluded from accuracy (a predictor
should not be punished for human guesses) and only reported as counts.

Run directly (NOT a ``scribe`` command)::

    uv run python dev/eval_speakers.py [--gold-dir <dir>]
    uv run python dev/eval_speakers.py --predictor mv4 [--alias-aware]
    uv run python dev/eval_speakers.py --predictor mv4-real --script <script.json>
        [--book <book-id>] [--alias-aware]
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

import yaml

# Dev script: make the scribe/ tree importable so ``text.*`` resolves when
# run as ``uv run python dev/eval_speakers.py`` (mirrors the cli.py bootstrap).
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from text.speakers import QuoteContext  # noqa: E402  (re-exported for back-compat)

#: Confidence levels MV4 rules produce, in display order. Unknown levels a
#: future predictor invents are shown after these, alphabetically.
CONFIDENCE_LEVELS: tuple[str, ...] = ("high", "medium", "low")

#: Attribution rules in plan priority order, for the by-rule table display.
#: Predictors returning a 2-tuple record no rule and are omitted there.
RULE_LEVELS: tuple[str, ...] = (
    "explicit",
    "pronoun",
    "alternation",
    "continuation",
    "fallback",
    "unknown",
)


class GoldError(ValueError):
    """A gold file is missing, unparsable, or fails validation."""


@dataclass(frozen=True)
class GoldEntry:
    """One hand-labelled dialogue line (the eval unit).

    ``book`` is the source-book id (e.g. ``"crime-and-punishment"``); older
    gold files omit it (and its ``source`` alias) and load as ``""``.
    ``surface`` is the optional hand-recorded surface form the text actually
    uses (``"he"``, ``"the young man"``); alias-aware scoring accepts it,
    strict scoring ignores it.
    """

    chapter: int
    block: int
    quote: int
    excerpt: str
    speaker: str
    uncertain: bool = False
    book: str = ""
    surface: str = ""

    @property
    def key(self) -> tuple[str, int, int, int]:
        """Stable anchor: ``(book, chapter, block, quote number)``, never ``sid``."""
        return (self.book, self.chapter, self.block, self.quote)


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


def predictions_from_script(
    script_path: Path | str, book: str = ""
) -> dict[tuple[str, int, int, int], tuple[str, str, str]]:
    """Run the MV4 rules over real contexts rebuilt from ``script.json``.

    Rebuilds full :class:`QuoteContext` entries via
    :func:`dev.real_contexts.contexts_from_script` (real paragraph texts,
    not the empty :meth:`QuoteContext.from_gold` shells) and scores them
    with :func:`text.attribution.attribute_quotes` in reading order.
    Returns ``{(book, chapter, block, quote): (speaker, confidence, rule)}``.
    """
    from dev.real_contexts import contexts_from_script
    from text.attribution import attribute_quotes

    by_key: dict[tuple[str, int, int, int], tuple[str, str, str]] = {}
    for att in attribute_quotes(contexts_from_script(script_path, book=book)):
        by_key[att.key] = (att.speaker, att.confidence, att.rule)
    return by_key


def validate_raw_entries(raw: object, source: str) -> list[GoldEntry]:
    """Validate decoded YAML ``raw`` from ``source``; return sorted entries.

    Raises :class:`GoldError` on any shape problem: not a list, missing or
    mistyped required keys (``chapter``/``block``/``quote`` ints,
    ``excerpt``/``speaker`` non-empty strings), an unknown key, a present-but-non-bool
    ``uncertain`` flag, a present-but-empty ``book``/``source``/``surface`` label, both
    ``book`` and ``source`` present but disagreeing, or a duplicate
    ``(book, chapter, block, quote)`` key. ``book`` (alias ``source``) is
    optional and defaults to ``""`` so MV1-era files without it still load;
    ``surface`` is optional and defaults to ``""`` (alias-aware scoring only).
    """
    if not isinstance(raw, list):
        raise GoldError(f"{source}: gold file must hold a YAML list of entries")
    entries: list[GoldEntry] = []
    seen: set[tuple[str, int, int, int]] = set()
    allowed = {
        "chapter",
        "block",
        "quote",
        "excerpt",
        "speaker",
        "uncertain",
        "book",
        "source",
        "surface",
    }
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
        surface = item.get("surface", "")
        if "surface" in item and (not isinstance(surface, str) or not surface.strip()):
            raise GoldError(f"{where}: 'surface' must be a non-empty string when present")
        entries.append(
            GoldEntry(
                chapter=item["chapter"],
                block=item["block"],
                quote=item["quote"],
                excerpt=item["excerpt"],
                speaker=item["speaker"],
                uncertain=uncertain,
                book=book,
                surface=surface.strip() if isinstance(surface, str) else "",
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
    by_rule: dict[str, tuple[int, int]] = field(default_factory=dict)
    uncertain_agree: int = 0


#: A predictor maps one context to ``(speaker, confidence)`` or to
#: ``(speaker, confidence, rule)``; the rule element feeds the by-rule table.
Predictor = Callable[[QuoteContext], tuple[str, str] | tuple[str, str, str]]


def evaluate(
    entries: list[GoldEntry],
    predictor: Predictor | None = None,
    *,
    alias_aware: bool = False,
) -> EvalResult:
    """Score ``predictor`` over ``entries`` (certain lines count, see docstring).

    Strict mode (default) matches the canonical ``speaker`` only;
    ``alias_aware=True`` also accepts the entry's ``surface`` form, so MV4
    surface-emitting predictors (``"he"``, ``"the young man"``) are not
    scored wrong against canonical gold (``"Raskolnikov"``).
    """
    if predictor is None:
        predictor = predict
    result = EvalResult(total=len(entries))
    for entry in entries:
        outcome = predictor(QuoteContext.from_gold(entry))
        if len(outcome) == 3:
            speaker, confidence, rule = outcome
            rule_label = str(rule).lower()
        else:
            speaker, confidence = outcome
            rule_label = ""
        level = str(confidence).lower()
        if entry.uncertain:
            result.uncertain += 1
            if _matches(speaker, entry.speaker):
                result.uncertain_agree += 1
            continue
        result.certain += 1
        hit = _matches(speaker, entry.speaker)
        if not hit and alias_aware and entry.surface:
            hit = _matches(speaker, entry.surface)
        correct, total = result.by_level.get(level, (0, 0))
        result.by_level[level] = (correct + (1 if hit else 0), total + 1)
        if rule_label:
            correct, total = result.by_rule.get(rule_label, (0, 0))
            result.by_rule[rule_label] = (correct + (1 if hit else 0), total + 1)
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
    """Human-readable report: overall plus by-level and by-rule breakdowns."""
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
    if result.by_rule:
        lines.append("by attribution rule (certain lines):")
        ordered_rules = [rule for rule in RULE_LEVELS if rule in result.by_rule]
        ordered_rules += sorted(rule for rule in result.by_rule if rule not in RULE_LEVELS)
        for rule in ordered_rules:
            correct, total = result.by_rule[rule]
            lines.append(f"  {rule}: {_pct(correct, total)}")
    lines.append(
        f"uncertain lines: {result.uncertain} excluded from accuracy "
        f"(predictor agreed on {result.uncertain_agree})"
    )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    """Load the gold set, run the chosen predictor, print the report."""
    parser = argparse.ArgumentParser(description="Score speaker attribution over the gold set.")
    parser.add_argument(
        "--gold-dir",
        type=Path,
        default=default_gold_dir(),
        help="gold YAML folder (default: spec/fixtures/speakers-gold/)",
    )
    parser.add_argument(
        "--predictor",
        choices=("baseline", "mv4", "mv4-real"),
        default="baseline",
        help="baseline always predicts ('unknown', low); mv4 runs the MV4 "
        "attribution rules over the gold contexts (empty texts, so this "
        "validates the wiring format, not accuracy); mv4-real rebuilds "
        "real contexts from --script and runs the MV4 rules over those "
        "(real accuracy; gold lines with no script quote fall back to "
        "('unknown', low)).",
    )
    parser.add_argument(
        "--script",
        type=Path,
        default=None,
        help="draft script.json for --predictor mv4-real (required there).",
    )
    parser.add_argument(
        "--book",
        default="",
        help="book label stamped on rebuilt contexts (must match the gold "
        "'book' ids to join; default: empty).",
    )
    parser.add_argument(
        "--alias-aware",
        action="store_true",
        help="also accept each gold entry's surface form, not just the "
        "canonical speaker (for MV4 surface-emitting predictors).",
    )
    args = parser.parse_args(argv)
    try:
        entries, file_count = load_gold_entries(args.gold_dir)
    except GoldError as exc:
        print(f"eval failed: {exc}", file=sys.stderr)
        return 1
    if args.predictor == "mv4":
        from text.attribution import attribute_quotes

        contexts = [QuoteContext.from_gold(entry) for entry in entries]
        by_key = {}
        for att in attribute_quotes(contexts):
            by_key[att.key] = (att.speaker, att.confidence, att.rule)

        def _mv4_predict(context: QuoteContext) -> tuple[str, str, str]:
            return by_key[(context.book, context.chapter, context.block, context.quote)]

        result = evaluate(entries, _mv4_predict, alias_aware=args.alias_aware)
        predictor_name = "mv4 rules" + (" (alias-aware)" if args.alias_aware else " (strict)")
    elif args.predictor == "mv4-real":
        if args.script is None:
            parser.error("--predictor mv4-real requires --script <script.json>")
        try:
            by_key = predictions_from_script(args.script, book=args.book)
        except ValueError as exc:
            print(f"eval failed: {exc}", file=sys.stderr)
            return 1
        matched = sum(1 for entry in entries if entry.key in by_key)

        def _mv4_real_predict(context: QuoteContext) -> tuple[str, str, str]:
            return by_key.get(
                (context.book, context.chapter, context.block, context.quote),
                ("unknown", "low", "unknown"),
            )

        result = evaluate(entries, _mv4_real_predict, alias_aware=args.alias_aware)
        predictor_name = (
            f"mv4-real rules from {args.script.name}"
            + (" (alias-aware)" if args.alias_aware else " (strict)")
        )
        report = format_result(result, predictor_name=predictor_name, file_count=file_count)
        print(report)
        print(
            f"real contexts: {len(by_key)} script quote(s) (book={args.book!r}), "
            f"{matched}/{len(entries)} gold line(s) matched"
        )
        return 0
    else:
        result = evaluate(entries, alias_aware=args.alias_aware)
        predictor_name = "baseline (always 'unknown', low)"
        if args.alias_aware:
            predictor_name += " (alias-aware)"
    report = format_result(result, predictor_name=predictor_name, file_count=file_count)
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
