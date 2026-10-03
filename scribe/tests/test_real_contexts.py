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

"""CP1: real contexts rebuilt from script.json plus the pre-label sampler.

A tiny synthetic two-chapter script.json pins the wiring: block/prev/next
paragraph joins, per-quote excerpts, the best-effort ``continued`` flag,
and the ``(book, chapter, block, quote)`` anchoring (never ``sid``). The
pre-label tests prove the emitted YAML validates through
``load_gold_entries``, is byte-identical on re-run, spans both chapters,
and keeps keys unique.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from dev.eval_speakers import load_gold_entries
from dev.make_gold_prelabels import (
    collect_labeled,
    render_prelabels,
    stratified_sample,
    write_prelabels,
)
from dev.real_contexts import contexts_from_script


def _sent(
    sid: int,
    text: str,
    kind: str = "narration",
    speaker: str = "narrator",
    quote: dict | None = None,
    pair: int | None = None,
) -> dict:
    return {
        "sid": sid,
        "speaker": speaker,
        "start_ms": 0,
        "end_ms": 0,
        "text": text,
        "kind": kind,
        "confidence": "high" if kind == "narration" else "low",
        "quote": quote,
        "split_pair": pair,
    }


def _quote(chapter: int, block: int, number: int) -> dict:
    return {"chapter": chapter, "block": block, "quote": number}


def _script_dict() -> dict:
    """Two chapters, six dialogue quotes (sids deliberately non-sequential)."""
    return {
        "chapters": [
            {
                "spec_version": "1.0",
                "chapter": 1,
                "title": "One",
                "duration_ms": 0,
                "blocks": [
                    {
                        "id": 5,
                        "type": "para",
                        "text": None,
                        "sentences": [
                            _sent(
                                101,
                                "\u201cHello,\u201d",
                                kind="dialogue",
                                speaker="unknown",
                                quote=_quote(1, 5, 1),
                                pair=1,
                            ),
                            _sent(57, " said Alice.", pair=1),
                        ],
                    },
                    {
                        "id": 6,
                        "type": "para",
                        "text": None,
                        "sentences": [
                            _sent(
                                303,
                                "\u201cHi there.\u201d",
                                kind="dialogue",
                                speaker="unknown",
                                quote=_quote(1, 6, 1),
                            ),
                        ],
                    },
                    {
                        "id": 7,
                        "type": "para",
                        "text": None,
                        "sentences": [
                            _sent(
                                12,
                                "\u201cFirst.\u201d",
                                kind="dialogue",
                                speaker="unknown",
                                quote=_quote(1, 7, 1),
                            ),
                        ],
                    },
                    {
                        "id": 8,
                        "type": "para",
                        "text": None,
                        "sentences": [
                            _sent(
                                44,
                                "\u201cSecond half.\u201d",
                                kind="dialogue",
                                speaker="unknown",
                                quote=_quote(1, 8, 1),
                            ),
                            _sent(45, " she added."),
                        ],
                    },
                ],
            },
            {
                "spec_version": "1.0",
                "chapter": 2,
                "title": "Two",
                "duration_ms": 0,
                "blocks": [
                    {
                        "id": 4,
                        "type": "para",
                        "text": None,
                        "sentences": [
                            _sent(
                                7,
                                "\u201cRun!\u201d",
                                kind="dialogue",
                                speaker="unknown",
                                quote=_quote(2, 4, 1),
                                pair=9,
                            ),
                            _sent(8, " shouted Bob.", pair=9),
                        ],
                    },
                    {
                        "id": 5,
                        "type": "para",
                        "text": None,
                        "sentences": [
                            _sent(
                                9,
                                "\u201cAlone here.\u201d",
                                kind="dialogue",
                                speaker="unknown",
                                quote=_quote(2, 5, 1),
                            ),
                        ],
                    },
                ],
            },
        ]
    }


@pytest.fixture()
def script_path(tmp_path: Path) -> Path:
    path = tmp_path / "script.json"
    path.write_text(json.dumps(_script_dict()), encoding="utf-8")
    return path


def _by_key(script_path: Path, book: str = "b"):
    return {(c.chapter, c.block, c.quote): c for c in contexts_from_script(script_path, book)}


def test_block_prev_next_excerpt_wiring(script_path: Path) -> None:
    contexts = _by_key(script_path)
    assert sorted(contexts) == [
        (1, 5, 1),
        (1, 6, 1),
        (1, 7, 1),
        (1, 8, 1),
        (2, 4, 1),
        (2, 5, 1),
    ]
    first = contexts[(1, 5, 1)]
    assert first.book == "b"
    assert first.block_text == "\u201cHello,\u201d said Alice."
    assert first.excerpt == "\u201cHello,\u201d"
    assert first.full_quote == "\u201cHello,\u201d"
    assert first.prev_text == ""
    assert first.next_text == "\u201cHi there.\u201d"
    assert contexts[(1, 6, 1)].prev_text == "\u201cHello,\u201d said Alice."
    assert contexts[(1, 6, 1)].next_text == "\u201cFirst.\u201d"
    # Chapter boundary: no leakage across chapters.
    assert contexts[(1, 8, 1)].next_text == ""
    assert contexts[(2, 4, 1)].prev_text == ""
    assert contexts[(2, 4, 1)].block_text == "\u201cRun!\u201d shouted Bob."


def test_continued_marks_dialogue_chains_only(script_path: Path) -> None:
    contexts = _by_key(script_path)
    # Block 8 starts with dialogue after block 7 ends with dialogue: chain.
    assert contexts[(1, 8, 1)].continued is True
    # Block 6 follows a narration tag ending: a new quote, not continued.
    assert contexts[(1, 6, 1)].continued is False
    # First block of the chapter has no predecessor.
    assert contexts[(1, 5, 1)].continued is False
    # Block 5 of chapter 2 follows the "shouted Bob." tag: not continued.
    assert contexts[(2, 5, 1)].continued is False


def test_never_anchors_on_sid(script_path: Path, tmp_path: Path) -> None:
    """Renumbering every sid leaves the rebuilt contexts unchanged."""
    raw = json.loads(script_path.read_text(encoding="utf-8"))
    sid = 1000
    for chapter in raw["chapters"]:
        for block in chapter["blocks"]:
            for sentence in block["sentences"]:
                sentence["sid"] = sid
                sid += 1
    other = tmp_path / "renumbered.json"
    other.write_text(json.dumps(raw), encoding="utf-8")
    assert contexts_from_script(other, "b") == contexts_from_script(script_path, "b")


def test_rejects_non_script(tmp_path: Path) -> None:
    bad = tmp_path / "bad.json"
    bad.write_text("[]", encoding="utf-8")
    with pytest.raises(ValueError, match="script.json"):
        contexts_from_script(bad)


_CAST = {
    "narrator": {"engine": "kokoro", "voice": "am_onyx", "speed": 1.0},
    "characters": {
        "Alice": {"voice": "bf_isabella", "speed": 1.0},
        "Bob": {"voice": "bm_lewis", "speed": 1.0},
    },
    "aliases": {},
    "default_female": {"voice": "af_bella", "speed": 1.0},
    "default_male": {"voice": "am_adam", "speed": 1.0},
    "first_person": "narrator",
    "overrides": [],
}


def test_prelabels_validate_and_carry_machine_notes(
    script_path: Path, tmp_path: Path
) -> None:
    records = collect_labeled(script_path, "b", _CAST)
    assert len(records) == 6
    explicit = next(r for r in records if (r["chapter"], r["block"]) == (1, 5))
    assert explicit["surface"] == "Alice"  # tag surface, not canonical
    assert explicit["speaker"] == "Alice"  # resolved through the cast
    assert explicit["rule"] == "explicit"
    sampled = stratified_sample(records, 4)
    text = render_prelabels(sampled, book="b")
    assert text.startswith(
        "# UNVERIFIED MACHINE PRE-LABELS - correct speaker fields by hand\n"
    )
    assert "# machine: Alice (explicit, high)" in text
    out = tmp_path / "pre.yaml"
    out.write_text(text, encoding="utf-8")
    entries, _ = load_gold_entries(tmp_path)
    assert len(entries) == len(sampled)
    assert all(e.book == "b" for e in entries)
    assert len({e.key for e in entries}) == len(sampled)  # keys unique


def test_sample_is_deterministic_spans_chapters_and_rewrites(
    script_path: Path, tmp_path: Path
) -> None:
    records = collect_labeled(script_path, "b", _CAST)
    first = stratified_sample(records, 4)
    second = stratified_sample(records, 4)
    assert first == second
    assert render_prelabels(first, book="b") == render_prelabels(second, book="b")
    assert {r["chapter"] for r in first} == {1, 2}
    assert len(first) == 4
    out = tmp_path / "cp-prelabels.yaml"
    write_prelabels(script_path, book="b", cast_path=_cast_path(tmp_path), out_path=out, target_n=4)
    before = out.read_bytes()
    write_prelabels(script_path, book="b", cast_path=_cast_path(tmp_path), out_path=out, target_n=4)
    assert out.read_bytes() == before  # byte-identical on re-run


def _cast_path(tmp_path: Path) -> Path:
    import yaml

    path = tmp_path / "cast.yaml"
    path.write_text(yaml.safe_dump(_CAST, sort_keys=True), encoding="utf-8")
    return path


def test_target_covers_all_quotes(script_path: Path) -> None:
    records = collect_labeled(script_path, "b", _CAST)
    assert len(stratified_sample(records, 100)) == len(records)
    with pytest.raises(ValueError, match="target_n"):
        stratified_sample(records, 0)
