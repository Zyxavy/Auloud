"""Tests for speaker alias transitive resolution and cycle detection."""

from text.cast import canonicalize_speaker


def test_direct_alias_resolves() -> None:
    characters = {"Alice": {"voice": "bf_isabella", "speed": 1.0}}
    aliases = {"Alice": ["Ally"]}
    assert canonicalize_speaker("Ally", characters, aliases) == "Alice"
    assert canonicalize_speaker("Alice", characters, aliases) == "Alice"


def test_transitive_resolution_a_to_b_to_c() -> None:
    # Flat surface -> canonical form, chained.
    characters = {"C": {"voice": "bf_isabella", "speed": 1.0}}
    aliases = {"A": "B", "B": "C"}
    assert canonicalize_speaker("A", characters, aliases) == "C"
    assert canonicalize_speaker("B", characters, aliases) == "C"


def test_transitive_list_form() -> None:
    # canonical -> surfaces form cannot chain via a character key, but a flat
    # chain expressed as lists still resolves one level; ensure no crash.
    characters = {"C": {"voice": "bf_isabella", "speed": 1.0}}
    aliases = {"C": ["B"], "B": ["A"]}  # B, A are surfaces (B not a char)
    # A is a surface of B, but B is not a character, so only C's own surface B resolves.
    assert canonicalize_speaker("B", characters, aliases) == "C"


def test_cycle_detection_returns_none() -> None:
    characters = {"C": {"voice": "bf_isabella", "speed": 1.0}}
    aliases = {"A": "B", "B": "A"}
    assert canonicalize_speaker("A", characters, aliases) is None
    assert canonicalize_speaker("B", characters, aliases) is None


def test_self_cycle_returns_none() -> None:
    characters = {"C": {"voice": "bf_isabella", "speed": 1.0}}
    aliases = {"A": "A"}
    assert canonicalize_speaker("A", characters, aliases) is None


def test_depth_limit() -> None:
    characters = {"Z": {"voice": "bf_isabella", "speed": 1.0}}
    # chain longer than 10
    aliases = {}
    cur = "A0"
    for i in range(1, 12):
        aliases[f"A{i - 1}"] = f"A{i}"
    aliases["A11"] = "Z"
    assert canonicalize_speaker("A0", characters, aliases) is None


def test_unknown_returns_none() -> None:
    characters = {"Alice": {"voice": "bf_isabella", "speed": 1.0}}
    aliases = {"Alice": ["Ally"]}
    assert canonicalize_speaker("Nobody", characters, aliases) is None
    assert canonicalize_speaker("", characters, aliases) is None
    assert canonicalize_speaker(None, characters, aliases) is None