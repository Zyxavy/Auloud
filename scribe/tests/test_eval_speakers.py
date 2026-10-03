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

"""MV1: the speaker gold set loads and validates; the eval harness scores.

The real gold file pins the fragment truth (5 dialogue lines, all certain,
book ``crime-and-punishment``): count, unique ``(book, chapter, block,
quote)`` keys, and one spot-checked label. Synthetic gold (2-3 lines plus a
fake predictor) proves ``evaluate`` gets overall and by-level accuracy
right, including the uncertain-line exclusion. MV2 adds the ``book``/``source``
label (old files without it still load as ``""``), the predictor-context
text fields (all defaulted), and documents canonical-name exact matching.
"""

from __future__ import annotations

from collections.abc import Callable
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
    book: str = "",
    surface: str = "",
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
    if book:
        entry["book"] = book
    if surface:
        entry["surface"] = surface
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
    assert all(e.book == "crime-and-punishment" for e in entries)
    assert {e.speaker for e in entries} == {"Raskolnikov", "drunken man"}
    shouted = next(e for e in entries if e.key == ("crime-and-punishment", 1, 10, 1))
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


def test_validate_rejects_unknown_key() -> None:
    extra = _gold_dict()
    extra["chapterr"] = 1
    with pytest.raises(GoldError, match="unknown key"):
        validate_raw_entries([extra], "test.yaml")


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
    assert context.book == ""
    assert context.block_text == "" and context.prev_text == ""
    assert context.next_text == "" and context.full_quote == ""
    assert predict_baseline(context) == ("unknown", "low")
    assert predict(context) == ("unknown", "low")


def test_context_from_gold_carries_book() -> None:
    entry = GoldEntry(
        chapter=1, block=8, quote=1, excerpt="x", speaker="Raskolnikov", book="crime-and-punishment"
    )
    context = QuoteContext.from_gold(entry)
    assert context.book == "crime-and-punishment"
    assert context.block_text == ""  # MV3/MV4 fill the text fields


def _fake_predictor(
    mapping: dict[tuple[str, int, int, int], tuple[str, str]],
) -> Callable[[QuoteContext], tuple[str, str]]:
    def _predict(context: QuoteContext) -> tuple[str, str]:
        return mapping[(context.book, context.chapter, context.block, context.quote)]

    return _predict


def test_evaluate_overall_and_by_level_accuracy() -> None:
    entries = [
        GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="Ana"),
        GoldEntry(chapter=1, block=1, quote=2, excerpt="b", speaker="Marcus"),
        GoldEntry(chapter=1, block=2, quote=1, excerpt="c", speaker="Ana"),
    ]
    predictor = _fake_predictor(
        {
            ("", 1, 1, 1): ("Ana", "high"),  # right, high
            ("", 1, 1, 2): ("Ana", "high"),  # wrong, high
            ("", 1, 2, 1): ("ana", "low"),  # right (casefold), low
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
    predictor = _fake_predictor({("", 1, 1, 1): ("Ana", "low"), ("", 1, 1, 2): ("nobody", "low")})
    result = evaluate(entries, predictor)
    assert result.certain == 1 and result.uncertain == 1
    assert result.correct == 1 and result.accuracy == pytest.approx(1.0)
    assert result.by_level["low"] == (1, 1)  # the uncertain miss never scores
    assert result.uncertain_agree == 0


def test_evaluate_empty_certain_gives_none_accuracy() -> None:
    entries = [GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="???", uncertain=True)]
    result = evaluate(entries, _fake_predictor({("", 1, 1, 1): ("???", "high")}))
    assert result.accuracy is None
    assert result.uncertain_agree == 1
    report = format_result(result, predictor_name="fake", file_count=1)
    assert "n/a" in report


def test_evaluate_default_uses_rebound_predictor(monkeypatch: pytest.MonkeyPatch) -> None:
    import dev.eval_speakers as eval_mod

    entries = [GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="Ana")]

    def _custom(context: QuoteContext) -> tuple[str, str]:
        _ = context
        return ("Ana", "high")

    monkeypatch.setattr(eval_mod, "predict", _custom)
    result = eval_mod.evaluate(entries)
    assert result.correct == 1
    assert result.accuracy == pytest.approx(1.0)
    assert result.by_level["high"] == (1, 1)


# ---------------------------------------------------------------------------
# MV2: book/source labels and canonical-name matching
# ---------------------------------------------------------------------------


def test_book_defaults_empty_for_old_files() -> None:
    entries = validate_raw_entries([_gold_dict()], "old.yaml")
    assert entries[0].book == ""
    assert entries[0].key == ("", 1, 8, 1)


def test_book_accepted_and_in_key() -> None:
    entries = validate_raw_entries([_gold_dict(book="crime-and-punishment")], "new.yaml")
    assert entries[0].book == "crime-and-punishment"
    assert entries[0].key == ("crime-and-punishment", 1, 8, 1)


def test_source_alias_accepted() -> None:
    raw = _gold_dict()
    raw["source"] = "crime-and-punishment"
    entries = validate_raw_entries([raw], "alias.yaml")
    assert entries[0].book == "crime-and-punishment"


def test_book_and_source_must_agree() -> None:
    raw = _gold_dict(book="crime-and-punishment")
    raw["source"] = "other-book"
    with pytest.raises(GoldError, match="disagree"):
        validate_raw_entries([raw], "clash.yaml")


def test_book_rejects_empty_and_non_string() -> None:
    with pytest.raises(GoldError, match="book"):
        validate_raw_entries([_gold_dict(book="  ")], "empty.yaml")
    bad = _gold_dict()
    bad["book"] = 7
    with pytest.raises(GoldError, match="book"):
        validate_raw_entries([bad], "type.yaml")


def test_duplicate_key_includes_book() -> None:
    with pytest.raises(GoldError, match="duplicate quote key"):
        validate_raw_entries([_gold_dict(book="b"), _gold_dict(book="b")], "dup.yaml")
    # Same chapter/block/quote in different books is fine.
    entries = validate_raw_entries([_gold_dict(book="a"), _gold_dict(book="b")], "ok.yaml")
    assert [e.key for e in entries] == [("a", 1, 8, 1), ("b", 1, 8, 1)]


def test_load_rejects_duplicate_book_key_across_files(tmp_path: Path) -> None:
    _write_gold(tmp_path, "a.yaml", [_gold_dict(book="b")])
    _write_gold(tmp_path, "b.yaml", [_gold_dict(book="b")])
    with pytest.raises(GoldError, match="duplicate quote key"):
        load_gold_entries(tmp_path)


def test_canonical_match_has_no_alias_matching() -> None:
    entries = [GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="Raskolnikov")]
    # Case and surrounding space are fine; a different name is simply wrong.
    assert (
        evaluate(entries, _fake_predictor({("", 1, 1, 1): ("raskolnikov ", "high")})).correct == 1
    )
    assert evaluate(entries, _fake_predictor({("", 1, 1, 1): ("the student", "high")})).correct == 0
    assert (
        evaluate(entries, _fake_predictor({("", 1, 1, 1): ("Raskolnikovs", "high")})).correct == 0
    )


# ---------------------------------------------------------------------------
# MV4: surface field, alias-aware scoring, by-rule table
# ---------------------------------------------------------------------------


def test_surface_accepted_and_in_key_validation() -> None:
    raw = _gold_dict(book="crime-and-punishment")
    raw["surface"] = "he"
    entries = validate_raw_entries([raw], "surface.yaml")
    assert entries[0].surface == "he"
    assert entries[0].key == ("crime-and-punishment", 1, 8, 1)


def test_surface_rejects_empty_and_non_string() -> None:
    with pytest.raises(GoldError, match="surface"):
        validate_raw_entries([_gold_dict(surface="  ")], "empty.yaml")
    bad = _gold_dict()
    bad["surface"] = 7
    with pytest.raises(GoldError, match="surface"):
        validate_raw_entries([bad], "type.yaml")


def test_surface_scoring_strict_vs_alias_aware() -> None:
    entries = [
        GoldEntry(chapter=1, block=8, quote=1, excerpt="a", speaker="Raskolnikov", surface="he"),
        GoldEntry(chapter=1, block=8, quote=2, excerpt="b", speaker="Raskolnikov", surface=""),
    ]
    predictor = _fake_predictor({("", 1, 8, 1): ("he", "low"), ("", 1, 8, 2): ("he", "low")})
    strict = evaluate(entries, predictor)
    assert strict.correct == 0  # the surface alone never scores in strict mode
    aware = evaluate(entries, predictor, alias_aware=True)
    assert aware.correct == 1  # only the entry carrying the surface scores
    assert aware.by_level["low"] == (1, 2)


def test_wrong_canonical_rejected_in_both_modes() -> None:
    entries = [
        GoldEntry(chapter=1, block=8, quote=1, excerpt="a", speaker="Raskolnikov", surface="he")
    ]
    predictor = _fake_predictor({("", 1, 8, 1): ("Svidrigailov", "high")})
    assert evaluate(entries, predictor).correct == 0
    assert evaluate(entries, predictor, alias_aware=True).correct == 0


def test_canonical_still_scores_in_alias_aware_mode() -> None:
    entries = [
        GoldEntry(chapter=1, block=8, quote=1, excerpt="a", speaker="Raskolnikov", surface="he")
    ]
    predictor = _fake_predictor({("", 1, 8, 1): ("Raskolnikov", "high")})
    assert evaluate(entries, predictor, alias_aware=True).correct == 1


def test_three_tuple_predictor_feeds_the_by_rule_table() -> None:
    entries = [
        GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="Ana"),
        GoldEntry(chapter=1, block=1, quote=2, excerpt="b", speaker="Marcus"),
    ]

    def _predict(context: QuoteContext) -> tuple[str, str, str]:
        if context.quote == 1:
            return ("Ana", "high", "explicit")
        return ("Ana", "low", "fallback")

    result = evaluate(entries, _predict)
    assert result.correct == 1
    assert result.by_rule["explicit"] == (1, 1)
    assert result.by_rule["fallback"] == (0, 1)
    report = format_result(result, predictor_name="fake", file_count=1)
    assert "by attribution rule (certain lines):" in report
    assert "explicit: 1/1 = 100.0%" in report
    assert "fallback: 0/1 = 0.0%" in report


def test_two_tuple_predictor_has_no_by_rule_section() -> None:
    entries = [GoldEntry(chapter=1, block=1, quote=1, excerpt="a", speaker="Ana")]
    result = evaluate(entries, _fake_predictor({("", 1, 1, 1): ("Ana", "high")}))
    assert result.by_rule == {}
    report = format_result(result, predictor_name="fake", file_count=1)
    assert "by attribution rule" not in report
