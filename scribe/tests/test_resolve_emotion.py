"""Task 5: emotion speed wiring into resolve/manifest."""

from __future__ import annotations

from bundle.models import Sentence
from build import resolve_sentence_voice
from text.cast import default_multivoice_cast, resolve_speaker


def _cast():
    cast = default_multivoice_cast()
    cast["characters"] = {"Kyle": {"voice": "bf_isabella", "speed": 1.0}}
    return cast


def test_angry_tagged_kyle_higher_speed():
    cast = _cast()
    base = resolve_speaker(cast, raw_speaker="Kyle", gender="male")
    tagged = resolve_speaker(cast, raw_speaker="Kyle", gender="male", emotion="ANGRY")
    assert tagged.speed > base.speed


def test_off_identical_to_baseline():
    cast = _cast()
    base = resolve_speaker(cast, raw_speaker="Kyle", gender="male")
    off = resolve_speaker(cast, raw_speaker="Kyle", gender="male", emotion="off")
    assert off.speed == base.speed
    assert off == base


def test_same_text_two_tags_two_cache_keys():
    from tts.cache import cache_key
    cast = _cast()
    s = Sentence(sid=1, speaker="Kyle", start_ms=0, end_ms=0, text="Hello.", spans=[], kind="dialogue")
    s.kind = "dialogue" if hasattr(s, "kind") else s.kind
    # ensure dialogue
    if not hasattr(s, "kind"):
        pass
    rv1 = resolve_sentence_voice(cast, s, chapter_index=1, narrator_voice="am_onyx",
                                 narrator_speed=1.0, emotion="speed", emotion_tag="ANGRY")
    rv2 = resolve_sentence_voice(cast, s, chapter_index=1, narrator_voice="am_onyx",
                                 narrator_speed=1.0, emotion="speed", emotion_tag="SAD")
    k1 = cache_key("Hello.", rv1.voice, rv1.speed, 1.0, "kokoro")
    k2 = cache_key("Hello.", rv2.voice, rv2.speed, 1.0, "kokoro")
    assert k1 != k2


def test_untagged_cache_key_unchanged():
    from tts.cache import cache_key
    cast = _cast()
    s = Sentence(sid=1, speaker="Kyle", start_ms=0, end_ms=0, text="Hello.", spans=[], kind="dialogue")
    rv_base = resolve_sentence_voice(cast, s, chapter_index=1, narrator_voice="am_onyx",
                                    narrator_speed=1.0, emotion="off")
    rv_untagged = resolve_sentence_voice(cast, s, chapter_index=1, narrator_voice="am_onyx",
                                        narrator_speed=1.0, emotion="speed", emotion_tag="NEUTRAL")
    k_base = cache_key("Hello.", rv_base.voice, rv_base.speed, 1.0, "kokoro")
    k_untagged = cache_key("Hello.", rv_untagged.voice, rv_untagged.speed, 1.0, "kokoro")
    assert k_base == k_untagged
    assert rv_base.speed == 1.0
