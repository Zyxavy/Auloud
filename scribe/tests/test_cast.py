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

"""MV5: cast.yaml model, merge and resolution (``text/cast.py``).

Synthetic casts only (never real books): resolution order end to end with
each layer winning in turn, merge preserving user edits while adding
newcomers, the top-N cutoff, generic fallback including the unknown-hint
path, first_person in both modes, every validation error, unknown-voice
rejection, and the thought hook staying behavior-free.
"""

from __future__ import annotations

import copy

import pytest
import yaml

from text.cast import (
    CHARACTER_PALETTE,
    CHARACTER_PALETTE_FEMALE,
    CHARACTER_PALETTE_MALE,
    DEFAULT_FEMALE_VOICE,
    DEFAULT_MALE_VOICE,
    NARRATOR,
    PALETTE_NARRATOR_VOICE,
    TOP_N_DEFAULT,
    ResolvedVoice,
    canonicalize_speaker,
    default_multivoice_cast,
    draft_cast,
    merge_cast,
    read_cast,
    resolve_speaker,
    validate_cast,
    write_cast,
)

KNOWN = {
    PALETTE_NARRATOR_VOICE,
    DEFAULT_FEMALE_VOICE,
    DEFAULT_MALE_VOICE,
    *CHARACTER_PALETTE,
}


def _cast() -> dict:
    """A valid multi-voice cast exercising every resolution layer."""
    cast = default_multivoice_cast()
    cast["characters"] = {
        "Alice": {"voice": "bf_isabella", "speed": 1.0},
        "Bob": {"voice": "bm_lewis", "speed": 0.95},
    }
    cast["aliases"] = {"Alice": ["Ally"], "Bob": ["Robert"]}
    cast["overrides"] = [
        {"chapter": 1, "block": 8, "quote": 1, "speaker": "Bob"},
        {"match": '^"Run!"', "speaker": "Alice"},
    ]
    return cast


def test_valid_cast_passes_with_known_voices() -> None:
    assert validate_cast(_cast(), known_voices=KNOWN) == []


def test_default_multivoice_cast_shape() -> None:
    cast = default_multivoice_cast()
    assert cast["narrator"]["voice"] == PALETTE_NARRATOR_VOICE
    assert cast["default_female"]["voice"] == DEFAULT_FEMALE_VOICE
    assert cast["default_male"]["voice"] == DEFAULT_MALE_VOICE
    assert cast["first_person"] == NARRATOR
    assert cast["characters"] == {} and cast["aliases"] == {} and cast["overrides"] == []
    assert validate_cast(cast, known_voices=KNOWN) == []


# ---------------------------------------------------------------------------
# Resolution order: each layer wins in turn
# ---------------------------------------------------------------------------


def test_resolution_order_end_to_end() -> None:
    cast = _cast()
    # 1. Quote-key override beats text-match, alias and character.
    hit = resolve_speaker(
        cast, raw_speaker="Ally", chapter=1, block=8, quote=1, text='"Run!" away.'
    )
    assert hit == ResolvedVoice(character="Bob", voice="bm_lewis", speed=0.95)
    # 2. Text-match beats alias and character.
    hit = resolve_speaker(cast, raw_speaker="Robert", chapter=2, block=3, quote=4, text='"Run!"')
    assert hit == ResolvedVoice(character="Alice", voice="bf_isabella", speed=1.0)
    # 3. Alias beats character and generic.
    hit = resolve_speaker(cast, raw_speaker="Ally", chapter=2, block=3, quote=4, text='"Walk."')
    assert hit == ResolvedVoice(character="Alice", voice="bf_isabella", speed=1.0)
    # 4. Bare character name resolves directly.
    hit = resolve_speaker(cast, raw_speaker="Bob", chapter=2, block=3, quote=4, text='"Walk."')
    assert hit == ResolvedVoice(character="Bob", voice="bm_lewis", speed=0.95)
    # 5. Unknown name falls to the gender-matched generic.
    hit = resolve_speaker(
        cast, raw_speaker="Zarathustra", gender="male", chapter=2, block=3, quote=4
    )
    assert hit == ResolvedVoice(character="default_male", voice="am_adam", speed=1.0)


def test_quote_key_override_beats_text_match() -> None:
    cast = _cast()
    hit = resolve_speaker(
        cast, raw_speaker="Alice", chapter=1, block=8, quote=1, text='"Run!" now.'
    )
    assert hit.character == "Bob"


def test_override_without_ids_still_text_matches() -> None:
    cast = _cast()
    # No chapter/block/quote given: quote-key overrides cannot match, but the
    # text-match override still applies.
    hit = resolve_speaker(cast, raw_speaker="Robert", text='"Run!"')
    assert hit.character == "Alice"


def test_character_match_forgives_case_and_titles() -> None:
    cast = _cast()
    assert canonicalize_speaker("alice", cast["characters"], cast["aliases"]) == "Alice"
    assert canonicalize_speaker("the Alice", cast["characters"], cast["aliases"]) == "Alice"
    assert canonicalize_speaker("ROBERT", cast["characters"], cast["aliases"]) == "Bob"
    assert canonicalize_speaker("Nobody", cast["characters"], cast["aliases"]) is None


def test_generic_fallback_by_hint_and_unknown_defaults_female() -> None:
    cast = _cast()
    assert resolve_speaker(cast, raw_speaker="X", gender="female").character == "default_female"
    assert resolve_speaker(cast, raw_speaker="X", gender="male").character == "default_male"
    # Unknown hints (the common MV3 case: surnames carry no hint) take the
    # female generic; garbled hints count as unknown too.
    assert resolve_speaker(cast, raw_speaker="X", gender="unknown").character == "default_female"
    assert resolve_speaker(cast, raw_speaker="X").character == "default_female"
    assert resolve_speaker(cast, raw_speaker="X", gender="other").character == "default_female"
    assert resolve_speaker(cast, raw_speaker="X", gender="female").voice == "af_bella"
    assert resolve_speaker(cast, raw_speaker="X", gender="male").voice == "am_adam"


def test_unknown_raw_speaker_resolves_sanely_never_crashes() -> None:
    cast = _cast()
    assert resolve_speaker(cast, raw_speaker="unknown").character == "default_female"
    assert resolve_speaker(cast, raw_speaker="unknown", gender="male").character == "default_male"
    assert resolve_speaker(cast, raw_speaker="").character == "default_female"
    assert resolve_speaker(cast, raw_speaker="   ").character == "default_female"
    assert resolve_speaker("not-a-cast", raw_speaker="unknown").character == "default_female"  # type: ignore[arg-type]


def test_first_person_defaults_to_narrator() -> None:
    cast = _cast()
    for raw in ("I", "i", " I "):
        hit = resolve_speaker(cast, raw_speaker=raw, chapter=1, block=1, quote=1)
        assert hit.character == NARRATOR
        assert (hit.voice, hit.speed) == (PALETTE_NARRATOR_VOICE, 1.0)


def test_first_person_named_character() -> None:
    cast = _cast()
    cast["first_person"] = "Alice"
    assert validate_cast(cast, known_voices=KNOWN) == []
    hit = resolve_speaker(cast, raw_speaker="I", chapter=1, block=1, quote=1)
    assert hit == ResolvedVoice(character="Alice", voice="bf_isabella", speed=1.0)


def test_thought_hook_has_no_behavior() -> None:
    cast = _cast()
    cast["thought"] = {"voice": "jf_alpha", "speed": 1.0}
    cast["characters"]["Alice"]["thought"] = {"voice": "zf_xiaoxiao"}
    assert validate_cast(cast, known_voices=KNOWN) == []
    assert resolve_speaker(cast, raw_speaker="Alice").voice == "bf_isabella"


# ---------------------------------------------------------------------------
# Draft generation: top N, palette assignment, clustering
# ---------------------------------------------------------------------------


def test_draft_top_n_cutoff_omits_the_rest() -> None:
    counts = {f"Speaker{i}": 10 - i for i in range(6)}  # 10..5 lines
    hints = {name: "male" for name in counts}
    cast = draft_cast(counts, hints)
    assert len(cast["characters"]) == TOP_N_DEFAULT == 5
    assert "Speaker5" not in cast["characters"]
    assert validate_cast(cast, known_voices=KNOWN) == []
    # Beyond top N resolves to generics (promote by hand to change that).
    hit = resolve_speaker(cast, raw_speaker="Speaker5", gender="male")
    assert hit.character == "default_male"


def test_draft_top_n_parameter() -> None:
    cast = draft_cast({"A": 3, "B": 2, "C": 1}, {"A": "female", "B": "male"}, top_n=2)
    assert sorted(cast["characters"]) == ["A", "B"]
    with pytest.raises(ValueError, match="top_n"):
        draft_cast({"A": 1}, top_n=0)


def test_draft_voice_assignment_pools_and_determinism() -> None:
    counts = {"Ana": 5, "Marcus": 4, "Riddle": 3}
    hints = {"Ana": "female", "Marcus": "male"}  # Riddle: unknown hint
    first = draft_cast(counts, hints)
    assert first["characters"]["Ana"]["voice"] in CHARACTER_PALETTE_FEMALE
    assert first["characters"]["Marcus"]["voice"] in CHARACTER_PALETTE_MALE
    assert first["characters"]["Riddle"]["voice"] in CHARACTER_PALETTE
    # Exact rule pin: first female takes the first female pool voice, first
    # male the first male pool voice, unknown the first free palette voice.
    assert first["characters"]["Ana"]["voice"] == CHARACTER_PALETTE_FEMALE[0]
    assert first["characters"]["Marcus"]["voice"] == CHARACTER_PALETTE_MALE[0]
    assert first["characters"]["Riddle"]["voice"] == CHARACTER_PALETTE[2]
    assert draft_cast(counts, hints) == first  # deterministic
    assert first["narrator"]["voice"] == PALETTE_NARRATOR_VOICE
    for entry in first["characters"].values():
        assert entry["speed"] == 1.0


def test_draft_pool_exhaustion_cycles_deterministically() -> None:
    counts = {f"F{i}": 10 - i for i in range(4)}
    hints = {name: "female" for name in counts}
    cast = draft_cast(counts, hints, top_n=4)
    voices = [cast["characters"][f"F{i}"]["voice"] for i in range(4)]
    assert voices[:3] == list(CHARACTER_PALETTE_FEMALE)
    assert voices[3] == CHARACTER_PALETTE_FEMALE[0]  # pool cycles


def test_draft_clusters_aliases_with_most_frequent_canonical() -> None:
    cast = draft_cast(
        {"Elizabeth Bennet": 6, "Bennet": 2, "Darcy": 4},
        {"Elizabeth Bennet": "female", "Bennet": "female", "Darcy": "male"},
        top_n=5,
    )
    assert "Elizabeth Bennet" in cast["characters"]
    assert "Bennet" not in cast["characters"]
    assert cast["aliases"]["Elizabeth Bennet"] == ["Bennet"]
    assert "Darcy" not in cast.get("aliases", {})


def test_draft_ignores_blank_surfaces() -> None:
    cast = draft_cast({"": 9, "   ": 9, "Ana": 1}, {"Ana": "female"})
    assert sorted(cast["characters"]) == ["Ana"]


# ---------------------------------------------------------------------------
# Merge: preserve user edits, add newcomers
# ---------------------------------------------------------------------------


def test_merge_preserves_edits_and_adds_newcomers() -> None:
    user = _cast()
    user["characters"]["Alice"] = {"voice": "zf_xiaoxiao", "speed": 1.1}
    user["aliases"]["Alice"].append("Alicia")
    user["first_person"] = "Bob"
    before = copy.deepcopy(user)
    discovered = draft_cast(
        {"Alice": 5, "Raskolnikov": 4}, {"Alice": "female", "Raskolnikov": "male"}
    )
    merged = merge_cast(user, discovered)
    assert user == before  # inputs never mutated
    assert merged["characters"]["Alice"] == {"voice": "zf_xiaoxiao", "speed": 1.1}
    assert merged["aliases"]["Alice"] == ["Ally", "Alicia"]
    assert merged["overrides"] == user["overrides"]
    assert merged["first_person"] == "Bob"
    assert merged["default_female"] == user["default_female"]
    assert "Raskolnikov" in merged["characters"]  # newcomer added
    assert validate_cast(merged, known_voices=KNOWN) == []


def test_merge_skips_already_covered_and_keeps_vanished() -> None:
    user = _cast()  # knows Alice (alias Ally) and Bob
    discovered = draft_cast(
        {"Ally": 9, "Bob": 8, "Gone": 1},
        {"Ally": "female", "Bob": "male", "Gone": "male"},
        top_n=5,
    )
    merged = merge_cast(user, discovered)
    assert sorted(merged["characters"]) == ["Alice", "Bob", "Gone"]  # no dupes
    assert "Ally" not in merged["characters"]
    vanished = merge_cast(merged, draft_cast({"Alice": 1}, {"Alice": "female"}))
    assert "Bob" in vanished["characters"]  # vanished entries are kept


def test_merge_upgrades_legacy_narrator_only_cast() -> None:
    legacy = {"narrator": {"engine": "kokoro", "voice": "af_heart", "speed": 1.0}}
    discovered = draft_cast({"Ana": 2}, {"Ana": "female"})
    merged = merge_cast(legacy, discovered)
    assert merged["narrator"] == legacy["narrator"]  # legacy narrator untouched
    assert merged["default_female"]["voice"] == DEFAULT_FEMALE_VOICE
    assert merged["default_male"]["voice"] == DEFAULT_MALE_VOICE
    assert merged["first_person"] == NARRATOR
    assert "Ana" in merged["characters"]


# ---------------------------------------------------------------------------
# Validation: every error names file + key + rule
# ---------------------------------------------------------------------------


def _errors(cast: dict, **kwargs) -> list[str]:
    kwargs.setdefault("known_voices", KNOWN)
    return validate_cast(cast, **kwargs)


def test_unknown_voice_rejected_everywhere() -> None:
    cast = _cast()
    cast["narrator"]["voice"] = "xx_nope"
    assert any("narrator.voice" in e and "xx_nope" in e for e in _errors(cast))
    cast = _cast()
    cast["characters"]["Alice"]["voice"] = "xx_nope"
    assert any("characters.Alice.voice" in e for e in _errors(cast))
    cast = _cast()
    cast["default_female"]["voice"] = "xx_nope"
    assert any("default_female.voice" in e for e in _errors(cast))
    for err in _errors(cast):
        assert err.startswith("cast.yaml: ")  # file + key + rule


def test_voice_shape_only_without_known_voices() -> None:
    cast = _cast()
    assert validate_cast(cast, known_voices=None) == []
    cast["characters"]["Alice"]["voice"] = ""
    assert any("characters.Alice.voice" in e for e in validate_cast(cast, known_voices=None))


def test_alias_points_nowhere() -> None:
    cast = _cast()
    cast["aliases"]["Nobody"] = ["Ghost"]
    (err,) = [e for e in _errors(cast) if "aliases.Nobody" in e]
    assert "points nowhere" in err


def test_duplicate_alias_across_characters() -> None:
    cast = _cast()
    cast["aliases"]["Bob"].append("Ally")
    assert any("duplicate alias" in e and "Ally" in e for e in _errors(cast))


def test_alias_colliding_with_character_key() -> None:
    cast = _cast()
    cast["aliases"]["Alice"].append("Bob")
    assert any("collides" in e for e in _errors(cast))


def test_duplicate_character_after_normalization() -> None:
    cast = _cast()
    cast["characters"]["the Alice"] = {"voice": "jf_alpha", "speed": 1.0}
    assert any("duplicates" in e for e in _errors(cast))


def test_first_person_unknown_rejected() -> None:
    cast = _cast()
    cast["first_person"] = "Nobody"
    assert any("first_person" in e for e in _errors(cast))


def test_override_speaker_points_nowhere() -> None:
    cast = _cast()
    cast["overrides"].append({"chapter": 2, "block": 1, "quote": 1, "speaker": "Nobody"})
    assert any("speaker" in e and "points nowhere" in e for e in _errors(cast))


def test_override_sid_rejected() -> None:
    cast = _cast()
    cast["overrides"].append({"chapter": 3, "sid": 41, "speaker": "Bob"})
    assert any("never sid" in e for e in _errors(cast))


def test_override_malformed_forms() -> None:
    for bad in (
        {"chapter": 1, "block": 1, "speaker": "Bob"},  # missing quote
        {"chapter": 1, "block": 1, "quote": 1, "match": "x", "speaker": "Bob"},  # mixed
        {"chapter": "one", "block": 1, "quote": 1, "speaker": "Bob"},  # bad type
        {"chapter": 0, "block": 1, "quote": 1, "speaker": "Bob"},  # bad range
        {"match": "(", "speaker": "Bob"},  # bad regex
        {"match": "", "speaker": "Bob"},  # empty regex
        {"chapter": 1, "block": 1, "quote": 1},  # missing speaker
        "not-a-mapping",
    ):
        cast = _cast()
        cast["overrides"].append(bad)
        assert _errors(cast), f"no error for {bad!r}"


def test_malformed_structure_errors() -> None:
    assert _errors([])  # type: ignore[arg-type]  # not a mapping
    cast = _cast()
    cast["nonsense"] = 1
    assert any("unknown section" in e for e in _errors(cast))
    cast = _cast()
    del cast["narrator"]
    assert any("narrator" in e and "missing" in e for e in _errors(cast))
    cast = _cast()
    cast["narrator"]["engine"] = "piper"
    assert any("narrator.engine" in e for e in _errors(cast))
    cast = _cast()
    cast["narrator"]["speed"] = -1.0
    assert any("narrator.speed" in e for e in _errors(cast))
    cast = _cast()
    cast["characters"]["narrator"] = {"voice": "jf_alpha", "speed": 1.0}
    assert any("reserved" in e for e in _errors(cast))
    cast = _cast()
    cast["characters"]["Alice"]["vocie"] = "x"
    assert any("unknown key" in e for e in _errors(cast))
    cast = _cast()
    cast["aliases"] = ["Alice"]
    assert any("aliases" in e and "mapping" in e for e in _errors(cast))
    cast = _cast()
    cast["overrides"] = {"chapter": 1}
    assert any("overrides" in e and "list" in e for e in _errors(cast))
    cast = _cast()
    cast["thought"] = ["loud"]
    assert any("thought" in e for e in _errors(cast))
    cast = _cast()
    cast["characters"]["Alice"]["thought"] = "loud"
    assert any("characters.Alice.thought" in e for e in _errors(cast))


def test_override_alias_surface_accepted() -> None:
    cast = _cast()
    cast["overrides"].append({"chapter": 2, "block": 2, "quote": 2, "speaker": "Ally"})
    assert _errors(cast) == []
    hit = resolve_speaker(cast, raw_speaker="Bob", chapter=2, block=2, quote=2)
    assert hit.character == "Alice"


def test_write_read_roundtrip(tmp_path) -> None:
    drafted = draft_cast({"Ana": 4, "Marcus": 2}, {"Ana": "female", "Marcus": "male"})
    path = write_cast(tmp_path / "cast.yaml", drafted)
    assert read_cast(path) == drafted
    assert yaml.safe_load(path.read_text(encoding="utf-8")) == drafted


# ---------------------------------------------------------------------------
# MV5-review pins: bad-regex leniency, thought survival, merge order,
# tie-breaks, first-person override/alias
# ---------------------------------------------------------------------------


def test_bad_regex_override_falls_through_never_throws() -> None:
    """A text-match override with an uncompilable regex never crashes resolve.

    Validation still rejects it (see test_override_malformed_forms); at
    resolve time the bad entry is skipped and the next layer wins.
    """
    cast = _cast()
    cast["overrides"].append({"match": "(", "speaker": "Alice"})
    hit = resolve_speaker(cast, raw_speaker="Bob", text='"Walk."')
    assert hit.character == "Bob"
    hit = resolve_speaker(cast, raw_speaker="Zarathustra", gender="male", text="hi")
    assert hit.character == "default_male"


def test_thought_hook_survives_merge() -> None:
    """Top-level and per-character thought mappings are preserved byte-for-value."""
    user = _cast()
    user["thought"] = {"voice": "jf_alpha", "speed": 1.0}
    user["characters"]["Alice"]["thought"] = {"voice": "zf_xiaoxiao"}
    before = copy.deepcopy(user)
    discovered = draft_cast({"Alice": 5, "Raskolnikov": 4}, {"Alice": "female"})
    merged = merge_cast(user, discovered)
    assert user == before
    assert merged["thought"] == {"voice": "jf_alpha", "speed": 1.0}
    assert merged["characters"]["Alice"]["thought"] == {"voice": "zf_xiaoxiao"}
    assert "Raskolnikov" in merged["characters"]


def test_merge_newcomer_order_and_discovered_unmutated() -> None:
    """Newcomers append in discovered rank order; neither input mutates."""
    user = _cast()
    user_before = copy.deepcopy(user)
    discovered = draft_cast(
        {"Zara": 6, "Mike": 5, "Anna": 4},
        {"Zara": "female", "Mike": "male", "Anna": "female"},
        top_n=5,
    )
    discovered_before = copy.deepcopy(discovered)
    merged = merge_cast(user, discovered)
    assert user == user_before
    assert discovered == discovered_before
    newcomers = [k for k in merged["characters"] if k not in ("Alice", "Bob")]
    assert newcomers == ["Zara", "Mike", "Anna"]


def test_draft_canonical_tie_break_is_deterministic() -> None:
    """Same-count cluster members: smallest normalized, then smallest surface."""
    cast = draft_cast({"Bob": 3, "bob": 3}, {"Bob": "male", "bob": "male"}, top_n=5)
    assert sorted(cast["characters"]) == ["Bob"]
    canonical = next(iter(cast["characters"]))
    assert canonical == "Bob"
    assert cast["aliases"]["Bob"] == ["bob"]
    assert draft_cast({"Bob": 3, "bob": 3}, {"Bob": "male", "bob": "male"}) == cast


def test_draft_gender_tie_break_prefers_alphabetical() -> None:
    """Equal male/female votes in one cluster break alphabetically (female first)."""
    cast = draft_cast(
        {"Elizabeth Bennet": 2, "Bennet": 2},
        {"Elizabeth Bennet": "female", "Bennet": "male"},
        top_n=5,
    )
    assert sorted(cast["characters"]) == ["Bennet"]
    assert cast["aliases"]["Bennet"] == ["Elizabeth Bennet"]
    assert cast["characters"]["Bennet"]["voice"] == CHARACTER_PALETTE_FEMALE[0]


def test_override_to_i_redirects_through_first_person() -> None:
    """An override naming I redirects like a raw first-person line (lenient).

    Validation still points nowhere (``I`` is a raw surface, not a
    character); resolve stays total and lands on the ``first_person``
    target anyway.
    """
    cast = _cast()
    cast["first_person"] = "Alice"
    cast["overrides"].append({"chapter": 9, "block": 9, "quote": 9, "speaker": "I"})
    assert any("points nowhere" in e and "'I'" in e for e in _errors(cast))
    hit = resolve_speaker(cast, raw_speaker="Bob", chapter=9, block=9, quote=9)
    assert hit.character == "Alice"


def test_override_to_narrator_resolves_narrator() -> None:
    """An override naming narrator (any case) resolves to the narrator entry."""
    for variant in ("narrator", "Narrator", "NARRATOR"):
        cast = _cast()
        cast["overrides"].append({"chapter": 1, "block": 10, "quote": 3, "speaker": variant})
        hit = resolve_speaker(
            cast,
            raw_speaker="Alice",
            gender="unknown",
            chapter=1,
            block=10,
            quote=3,
            text="...",
        )
        assert hit == ResolvedVoice(character=NARRATOR, voice=PALETTE_NARRATOR_VOICE, speed=1.0), (
            f"variant {variant!r} got {hit!r}"
        )


def test_override_to_character_still_wins_and_garbage_still_generic() -> None:
    """Narrator special-case changes nothing else: characters win, garbage generic."""
    cast = _cast()
    cast["overrides"].append({"chapter": 1, "block": 10, "quote": 3, "speaker": "Alice"})
    hit = resolve_speaker(cast, raw_speaker="Bob", chapter=1, block=10, quote=3, text="...")
    assert hit == ResolvedVoice(character="Alice", voice="bf_isabella", speed=1.0)
    cast = _cast()
    cast["overrides"].append({"chapter": 1, "block": 10, "quote": 3, "speaker": "Nobody"})
    hit = resolve_speaker(
        cast,
        raw_speaker="Alice",
        gender="unknown",
        chapter=1,
        block=10,
        quote=3,
        text="...",
    )
    assert hit.character == "default_female"
