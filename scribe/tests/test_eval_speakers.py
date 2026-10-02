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

"""MV1: the speaker gold set loads and validates; the eval harness scores.

The real gold file pins the fragment truth (5 dialogue lines, all certain):
count, unique ``(chapter, block, quote)`` keys, and one spot-checked label.
Synthetic gold (2-3 lines plus a fake predictor) proves ``evaluate`` gets
overall and by-level accuracy right, including the uncertain-line exclusion.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from dev.eval_speakers import (
    GoldEntry,
    GoldError,
    QuoteContext,
    evaluate,
    format_result,
    load_gold_entries,
    predict,
    predict_baseline,
    validate_raw_entries,
)

GOLD_DIR = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "speakers-gold"


def _gold_dict(
    chapter: int = 1,
    block: int = 8,
    quote: int = 1,
    excerpt: str = "some words",
    speaker: str = "Raskolnikov",
    uncertain: bool = False,
) -> dict[str, object]:
    entry: dict[str, object] = {
        "chapter": chapter,
        "block": block,
        "quote": quote,
        "excerpt": excerpt,
        "speaker": speaker,
    }
    if uncertain:
        entry["uncertain"] = True
    return entry


def _write_gold(tmp_path: Path, name: str, entries: list[dict[str, object]]) -> Path:
    path = tmp_path / name
    path.write_text(yaml.safe_dump(entries, allow_unicode=True), encoding="utf-8")
    return path


def test_real_gold_loads_with_five_certain_lines() -> None:
    entries, file_count = load_gold_entries(GOLD_DIR)
    assert file_count == 1
    assert len(entries) == 5
    assert [e.key for e in entries] == sorted(e.key for e in entries)
    assert len({e.key for e in entries}) == 5  # anchored, unique, never on sid
    assert all(not e.uncertain for e in entries)
    assert {e.speaker for e in entries} == {"Raskolnikov", "drunken man"}
    shouted = next(e for e in entries if e.key == (1, 10, 1))
    assert shouted.speaker == "drunken man"
    assert shouted.excerpt == "Hey there, German hatter"


def test_gold_excerpts_are_short_substrings() -> None:
    entries, _ = load_gold_entries(GOLD_DIR)
    for entry in entries:
        assert entry.excerpt.strip()  # validated non-empty
        assert len(entry.excerpt) <= 100  # short excerpt, not whole chapters


def test_validate_rejects_missing_key() -> None:
    bad = _gold_dict()
    del bad["speaker"]
    with pytest.raises(GoldError, match="speaker"):
        validate_raw_entries([bad], "test.yaml")


def test_validate_rejects_duplicate_quote_key() -> None:
    with pytest.raises(GoldError, match="duplicate quote key"):
        validate_raw_entries([_gold_dict(), _gold_dict()], "test.yaml")


def test_validate_rejects_non_bool_uncertain() -> None:
    bad = _gold_dict()
    bad["uncertain"] = "yes"
    with pytest.raises(GoldError, match="uncertain"):
        validate_raw_entries([bad], "test.yaml")


def test_validate_rejects_empty_speaker_and_zero_chapter() -> None:
    with pytest.raises(GoldError, match="speaker"):
        validate_raw_entries([_gold_dict(speaker="  ")], "test.yaml")
    with pytest.raises(GoldError, match="chapter"):
        validate_raw_entries([_gold_dict(chapter=0)], "test.yaml")


def test_load_rejects_duplicate_key_across_files(tmp_path: Path) -> None:
    _write_gold(tmp_path, "a.yaml", [_gold_dict()])
    _write_gold(tmp_path, "b.yaml", [_gold_dict()])
    with pytest.raises(GoldError, match="duplicate quote key"):
        load_gold_entries(tmp_path)


def test_load_rejects_missing_dir_and_empty_dir(tmp_path: Path) -> None:
    with pytest.raises(GoldError, match="not found"):
        load_gold_entries(tmp_path / "nope")
    with pytest.raises(GoldError, match="no gold YAML"):
        load_gold_entries(tmp_path)


def test_baseline_predictor_is_unknown_low() -> None:
    context = QuoteContext(chapter=1, block=8, quote=1, excerpt="x")
    assert predict_baseline(context) == ("unknown", "low")
    assert predict(context) == ("unknown", "low")


def _fake_predictor(mapping: dict[tuple[int, int, int], tuple[str, str]]) -> object:
    def _predict(context: QuoteContext) -> tuple[str, str]:
        return mapping[(context.chapter, context.block, context.quote)]

    return _predict


def test_evaluate_overall_and_by_level_accuracy() -> None:
    entries = [
        GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="Ana"),
        GoldEntry(chapter=1, block=1, quote=2, excerpt="b", speaker="Marcus"),
        GoldEntry(chapter=1, block=2, quote=1, excerpt="c", speaker="Ana"),
    ]
    predictor = _fake_predictor(
        {
            (1, 1, 1): ("Ana", "high"),  # right, high
            (1, 1, 2): ("Ana", "high"),  # wrong, high
            (1, 2, 1): ("ana", "low"),  # right (casefold), low
        }
    )
    result = evaluate(entries, predictor)
    assert (result.total, result.certain, result.uncertain) == (3, 3, 0)
    assert result.correct == 2
    assert result.accuracy == pytest.approx(2 / 3)
    assert result.by_level["high"] == (1, 2)
    assert result.by_level["low"] == (1, 1)


def test_evaluate_excludes_uncertain_lines() -> None:
    entries = [
        GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="Ana"),
        GoldEntry(chapter=1, block=1, quote=2, excerpt="b", speaker="???", uncertain=True),
    ]
    predictor = _fake_predictor({(1, 1, 1): ("Ana", "low"), (1, 1, 2): ("nobody", "low")})
    result = evaluate(entries, predictor)
    assert result.certain == 1 and result.uncertain == 1
    assert result.correct == 1 and result.accuracy == pytest.approx(1.0)
    assert result.by_level["low"] == (1, 1)  # the uncertain miss never scores
    assert result.uncertain_agree == 0


def test_evaluate_empty_certain_gives_none_accuracy() -> None:
    entries = [GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="???", uncertain=True)]
    result = evaluate(entries, _fake_predictor({(1, 1, 1): ("???", "high")}))
    assert result.accuracy is None
    assert result.uncertain_agree == 1
    report = format_result(result, predictor_name="fake", file_count=1)
    assert "n/a" in report
