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

"""Sample UNVERIFIED machine pre-labels for the CP1 gold set (dev-only).

Loads real contexts from a draft ``script.json`` (see
:mod:`dev.real_contexts`), runs :func:`text.attribution.attribute_quotes`,
resolves each machine surface to a CANONICAL name with
:func:`text.cast.resolve_speaker` against the given ``cast.yaml``, and
emits a deterministic stratified sample as gold-shaped YAML for humans to
correct by hand.

Run directly (NOT a ``scribe`` command)::

    uv run python dev/make_gold_prelabels.py --script <script.json> \\
        --book <book-id> --cast <cast.yaml> --out <file.yaml> [--target-n 130]

Canonical names via :func:`text.cast.resolve_speaker` (same call the MV7
build uses): the raw surface, the attribution gender hint, the quote key,
and the quote text go in; the returned ``ResolvedVoice.character`` (a
character key, an alias owner, ``narrator``, or a ``default_female`` /
``default_male`` generic for unknown surfaces) is what the gold ``speaker``
field must hold. Quote-key/text-match overrides and the ``first_person``
redirect in the given cast are honored exactly as at build time -- which
also means a stale cast can stamp wrong-but-valid names here; the human
correction pass is the backstop, and every line ships UNVERIFIED.

Stratification (deterministic: sort keys, fixed stride, no randomness, no
wall-clock anywhere in the output): groups are ``(chapter, confidence,
rule)`` sorted ascending; quotas are proportional to group size
(largest-remainder, at least one per group while the target allows; when
the target is smaller than the group count the earliest groups win); each
group contributes evenly spaced quotes (``i * n // quota``) so long
chapters do not collapse to their opening lines. Output is sorted by
``(chapter, block, quote)`` and written with ``sort_keys=True``, so
re-runs are byte-identical.

Dev-only: the library never imports this module.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

# Dev script: make the scribe/ tree importable so ``text.*``/``dev.*``
# resolve when run as ``uv run python dev/make_gold_prelabels.py``.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from dev.real_contexts import contexts_from_script  # noqa: E402
from text.attribution import attribute_quotes  # noqa: E402
from text.cast import read_cast, resolve_speaker  # noqa: E402

#: Header stamped on every emitted file: the labels are machine guesses.
HEADER_COMMENT = "# UNVERIFIED MACHINE PRE-LABELS - correct speaker fields by hand"


def collect_labeled(
    script_path: Path | str, book: str, cast: dict
) -> list[dict]:
    """Attribute every script quote and resolve it to a canonical name.

    Returns one record per ``(chapter, block, quote)`` key in reading
    order: ``chapter``/``block``/``quote`` ints, ``excerpt`` (the full
    quote text), ``speaker`` (canonical guess for the gold field),
    ``surface`` (the machine tag surface), ``rule`` and ``confidence``.
    """
    contexts = contexts_from_script(script_path, book=book)
    excerpts = {
        (ctx.book, ctx.chapter, ctx.block, ctx.quote): ctx.full_quote for ctx in contexts
    }
    records: list[dict] = []
    for att in attribute_quotes(contexts):
        excerpt = excerpts.get(att.key, "")
        resolved = resolve_speaker(
            cast,
            raw_speaker=att.speaker,
            gender=att.gender,
            chapter=att.chapter,
            block=att.block,
            quote=att.quote,
            text=excerpt,
        )
        records.append(
            {
                "chapter": att.chapter,
                "block": att.block,
                "quote": att.quote,
                "excerpt": excerpt,
                "speaker": resolved.character,
                "surface": att.speaker,
                "rule": att.rule,
                "confidence": att.confidence,
            }
        )
    records.sort(key=lambda r: (r["chapter"], r["block"], r["quote"]))
    return records


def _apportion(sizes: dict, total: int) -> dict:
    """Largest-remainder quotas for ``sizes`` summing to ``total``.

    Deterministic: keys sorted, leftover seats go to the largest
    fractional part (ties: smaller key). Every non-empty group keeps at
    least one seat while the target allows (``total`` >= group count);
    groups are capped at their size. Takes all when ``total`` covers them.
    """
    keys = sorted(sizes)
    capacity = sum(sizes.values())
    quotas = dict(sizes) if total >= capacity else {k: 0 for k in keys}
    if total >= capacity:
        return quotas
    remaining = total
    if total >= len(keys):
        for key in keys:
            quotas[key] = 1
        remaining -= len(keys)
    if remaining > 0:
        raws = {key: sizes[key] * remaining / capacity for key in keys}
        floors = {
            key: min(sizes[key] - quotas[key], int(raws[key])) for key in keys
        }
        for key in keys:
            quotas[key] += floors[key]
        leftover = remaining - sum(floors.values())
        # Leftover seats to the largest fractional parts (ties: smaller key).
        by_frac: dict[float, list] = {}
        for key in keys:
            if quotas[key] < sizes[key]:
                by_frac.setdefault(raws[key] - int(raws[key]), []).append(key)
        ordered: list = []
        for frac in sorted(by_frac, reverse=True):
            ordered.extend(sorted(by_frac[frac]))
        for key in ordered[:leftover]:
            quotas[key] += 1
    return quotas


def stratified_sample(records: list[dict], target_n: int) -> list[dict]:
    """Deterministic stratified sample (~``target_n`` records).

    Two levels: chapters first (every chapter keeps a seat while the
    target allows, the rest proportional to chapter size), then
    ``(confidence, rule)`` within each chapter the same way. Each leaf
    group contributes evenly spaced quotes (``i * n // quota``) so long
    chapters do not collapse to their opening lines. Returns the picks
    sorted by ``(chapter, block, quote)``. Takes all records when
    ``target_n`` covers them. Raises :class:`ValueError` for
    ``target_n < 1``. Sort keys, fixed stride, no randomness, no
    wall-clock.
    """
    if not isinstance(target_n, int) or isinstance(target_n, bool) or target_n < 1:
        raise ValueError(f"target_n must be a positive int (got {target_n!r})")
    ordered = sorted(records, key=lambda r: (r["chapter"], r["block"], r["quote"]))
    if target_n >= len(ordered):
        return ordered
    by_chapter: dict[int, list[dict]] = {}
    for record in ordered:
        by_chapter.setdefault(record["chapter"], []).append(record)
    chapter_quotas = _apportion({ch: len(m) for ch, m in by_chapter.items()}, target_n)
    picks: list[dict] = []
    for chapter in sorted(by_chapter):
        members = by_chapter[chapter]
        quota = chapter_quotas[chapter]
        subgroups: dict[tuple[str, str], list[dict]] = {}
        for record in members:
            subgroups.setdefault((record["confidence"], record["rule"]), []).append(record)
        sub_quotas = _apportion({k: len(v) for k, v in subgroups.items()}, quota)
        for key in sorted(subgroups):
            group = subgroups[key]
            take = sub_quotas[key]
            for i in range(take):
                picks.append(group[i * len(group) // take])
    picks.sort(key=lambda r: (r["chapter"], r["block"], r["quote"]))
    return picks


def render_prelabels(records: list[dict], *, book: str) -> str:
    """Render sampled records as gold-shaped YAML with machine comments.

    Every entry holds ``book``/``chapter``/``block``/``quote``/``excerpt``/
    ``speaker`` (canonical guess) / ``surface`` (machine surface) -- exactly
    what :func:`dev.eval_speakers.load_gold_entries` validates -- preceded
    by a ``# machine: <surface> (<rule>, <confidence>)`` comment. Output
    is deterministic (sorted keys, no timestamps).
    """
    lines = [HEADER_COMMENT]
    for record in records:
        entry = {
            "book": book,
            "block": record["block"],
            "chapter": record["chapter"],
            "excerpt": record["excerpt"],
            "quote": record["quote"],
            "speaker": record["speaker"],
            "surface": record["surface"],
        }
        dumped = yaml.safe_dump(entry, sort_keys=True, allow_unicode=True).splitlines()
        lines.append(
            f"# machine: {record['surface']} ({record['rule']}, {record['confidence']})"
        )
        lines.append("- " + dumped[0])
        lines.extend("  " + line for line in dumped[1:])
    return "\n".join(lines) + "\n"


def write_prelabels(
    script_path: Path | str,
    *,
    book: str,
    cast_path: Path | str,
    out_path: Path | str,
    target_n: int,
) -> tuple[list[dict], list[dict]]:
    """Full pipeline: label, sample, write ``out_path`` (parents created).

    Returns ``(sampled, all_records)`` for the CLI summary. Byte-identical
    across re-runs on the same inputs.
    """
    cast = read_cast(cast_path)
    all_records = collect_labeled(script_path, book, cast)
    sampled = stratified_sample(all_records, target_n)
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(render_prelabels(sampled, book=book), encoding="utf-8")
    return sampled, all_records


def main(argv: list[str] | None = None) -> int:
    """Parse args, write the pre-label file, print the sample summary."""
    parser = argparse.ArgumentParser(
        description="Sample UNVERIFIED machine pre-labels for the CP1 gold set."
    )
    parser.add_argument("--script", type=Path, required=True, help="draft script.json")
    parser.add_argument("--book", required=True, help="book id for quote keys")
    parser.add_argument("--cast", type=Path, required=True, help="cast.yaml to resolve with")
    parser.add_argument("--out", type=Path, required=True, help="output YAML path")
    parser.add_argument("--target-n", type=int, default=130, help="sample size (default 130)")
    args = parser.parse_args(argv)
    try:
        sampled, all_records = write_prelabels(
            args.script,
            book=args.book,
            cast_path=args.cast,
            out_path=args.out,
            target_n=args.target_n,
        )
    except (ValueError, OSError) as exc:
        print(f"pre-labels failed: {exc}", file=sys.stderr)
        return 1
    from collections import Counter

    by_chapter = Counter(r["chapter"] for r in sampled)
    by_conf = Counter(r["confidence"] for r in sampled)
    by_rule = Counter(r["rule"] for r in sampled)
    print(f"total dialogue quotes in script: {len(all_records)}")
    print(f"sampled: {len(sampled)} (target {args.target_n}) -> {args.out}")
    print("by chapter: " + ", ".join(f"{k}={v}" for k, v in sorted(by_chapter.items())))
    print("by confidence: " + ", ".join(f"{k}={v}" for k, v in sorted(by_conf.items())))
    print("by rule: " + ", ".join(f"{k}={v}" for k, v in sorted(by_rule.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
