"""Task 4: cast emotion overrides parse and round-trip."""
from __future__ import annotations

import yaml

from text.cast import (
    EMOTION_KEY,
    EMOTION_MODIFIERS_KEY,
    character_emotion_enabled,
    default_multivoice_cast,
    parse_emotion_modifiers,
    validate_cast,
    write_cast,
    read_cast,
)


def test_per_character_emotion_off():
    cast = default_multivoice_cast()
    cast["characters"] = {"Alice": {"voice": "bf_isabella", "speed": 1.0, EMOTION_KEY: "off"}}
    assert validate_cast(cast) == []
    assert character_emotion_enabled(cast, "Alice") is False
    assert character_emotion_enabled(cast, "narrator") is True


def test_top_level_emotion_modifiers_parse():
    cast = default_multivoice_cast()
    cast[EMOTION_MODIFIERS_KEY] = {"ANGRY": {"speed_mult": 1.2, "gain_db": 1.0}}
    assert validate_cast(cast) == []
    mods = parse_emotion_modifiers(cast)
    assert mods["ANGRY"].speed_mult == 1.2
    assert mods["ANGRY"].gain_db == 1.0


def test_round_trip_yaml():
    cast = default_multivoice_cast()
    cast[EMOTION_MODIFIERS_KEY] = {"SAD": 0.88}
    cast["characters"] = {"Alice": {"voice": "bf_isabella", "speed": 1.0, EMOTION_KEY: "off"}}
    import tempfile, pathlib
    d = pathlib.Path(tempfile.mkdtemp())
    path = write_cast(d / "cast.yaml", cast)
    loaded = read_cast(path)
    assert loaded == cast
    assert yaml.safe_load(path.read_text(encoding="utf-8")) == cast


def test_draft_does_not_write_defaults():
    from text.cast import draft_cast
    cast = draft_cast({"Ana": 4}, {"Ana": "female"})
    assert EMOTION_MODIFIERS_KEY not in cast
    for entry in cast.get("characters", {}).values():
        assert EMOTION_KEY not in entry
