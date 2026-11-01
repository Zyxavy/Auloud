"""Task 5: emotion speed wiring into resolve/manifest."""

from __future__ import annotations

import pytest

from bundle.models import Sentence
from build import resolve_sentence_voice
from text.cast import EMOTION_MODIFIERS_KEY, default_multivoice_cast, resolve_speaker


def _cast():
    cast = default_multivoice_cast()
    cast["characters"] = {"Kyle": {"voice": "bf_isabella", "speed": 1.0}}
    return cast


def _sentence(emotion=None) -> Sentence:
    return Sentence(
        sid=1,
        speaker="Kyle",
        start_ms=0,
        end_ms=0,
        text="Hello.",
        spans=[],
        kind="dialogue",
        emotion=emotion,
    )


def test_angry_tagged_kyle_higher_speed():
    cast = _cast()
    base = resolve_speaker(cast, raw_speaker="Kyle", gender="male")
    tagged = resolve_speaker(cast, raw_speaker="Kyle", gender="male", emotion="ANGRY")
    assert tagged.speed > base.speed


def test_cast_modifier_override_changes_speed():
    cast = _cast()
    cast[EMOTION_MODIFIERS_KEY] = {"ANGRY": {"speed_mult": 1.12}}
    resolved = resolve_sentence_voice(
        cast,
        _sentence("ANGRY"),
        chapter_index=1,
        narrator_voice="am_onyx",
        narrator_speed=1.0,
        emotion="speed",
    )
    assert resolved.speed == pytest.approx(1.12)


def test_off_identical_to_baseline():
    cast = _cast()
    base = resolve_speaker(cast, raw_speaker="Kyle", gender="male")
    off = resolve_speaker(cast, raw_speaker="Kyle", gender="male", emotion="off")
    assert off.speed == base.speed
    assert off == base


def test_same_text_two_tags_two_cache_keys():
    from tts.cache import cache_key

    cast = _cast()
    rv1 = resolve_sentence_voice(
        cast, _sentence("ANGRY"), chapter_index=1, narrator_voice="am_onyx",
        narrator_speed=1.0, emotion="speed",
    )
    rv2 = resolve_sentence_voice(
        cast, _sentence("SAD"), chapter_index=1, narrator_voice="am_onyx",
        narrator_speed=1.0, emotion="speed",
    )
    k1 = cache_key("Hello.", rv1.voice, rv1.speed, 1.0, "kokoro")
    k2 = cache_key("Hello.", rv2.voice, rv2.speed, 1.0, "kokoro")
    assert k1 != k2


def test_untagged_cache_key_unchanged():
    from tts.cache import cache_key

    cast = _cast()
    sentence = _sentence(None)
    rv_base = resolve_sentence_voice(
        cast, sentence, chapter_index=1, narrator_voice="am_onyx",
        narrator_speed=1.0, emotion="off",
    )
    rv_untagged = resolve_sentence_voice(
        cast, sentence, chapter_index=1, narrator_voice="am_onyx",
        narrator_speed=1.0, emotion="speed",
    )
    k_base = cache_key("Hello.", rv_base.voice, rv_base.speed, 1.0, "kokoro")
    k_untagged = cache_key("Hello.", rv_untagged.voice, rv_untagged.speed, 1.0, "kokoro")
    assert k_base == k_untagged
    assert rv_base.speed == 1.0
