"""Task 4: emotion modifier table and apply_speed composition."""
from __future__ import annotations

from text.emotion import (
    EMOTION_MODIFIERS,
    Modifier,
    SPEED_CLAMP_MAX,
    SPEED_CLAMP_MIN,
    apply_speed,
)


def test_neutral_identity():
    assert apply_speed(1.0, "NEUTRAL") == 1.0
    assert EMOTION_MODIFIERS["NEUTRAL"] == Modifier(1.0, 0.0)


def test_defaults():
    assert EMOTION_MODIFIERS["ANGRY"].speed_mult == 1.08
    assert EMOTION_MODIFIERS["EXCITED"].speed_mult == 1.10
    assert EMOTION_MODIFIERS["FEARFUL"].speed_mult == 1.05
    assert EMOTION_MODIFIERS["SAD"].speed_mult == 0.92
    assert EMOTION_MODIFIERS["GENTLE"].speed_mult == 0.94
    assert EMOTION_MODIFIERS["WHISPER"].speed_mult == 0.90
    assert EMOTION_MODIFIERS["SHOUT"].speed_mult == 1.0
    assert EMOTION_MODIFIERS["SHOUT"].gain_db == 3.0
    assert EMOTION_MODIFIERS["WHISPER"].gain_db == -3.0


def test_composition_multiplicative():
    # base 0.9 * ANGRY 1.08 = 0.972
    import pytest
    assert apply_speed(0.9, "ANGRY") == pytest.approx(0.972)


def test_clamp():
    assert apply_speed(10.0, "EXCITED") == SPEED_CLAMP_MAX
    assert apply_speed(0.01, "SAD") == SPEED_CLAMP_MIN
