# Auloud Scribe
# Copyright (C) 2026 Zyxavy
#
# GNU Affero General Public License v3 or later.

"""Precision-first emotion tagging from an :class:`AttributionCue`.

Consumes the cue only; never reads dialogue text. Verb table is primary;
adverbs modify only neutral verbs. Negated adverbs yield NEUTRAL.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from text.attribution import AttributionCue

TAGS = ("NEUTRAL", "ANGRY", "SAD", "FEARFUL", "EXCITED", "GENTLE", "WHISPER", "SHOUT")

# Verb table (primary). Lemma -> tag.
VERB_TABLE: dict[str, str] = {
    "shout": "SHOUT",
    "yell": "SHOUT",
    "scream": "SHOUT",
    "exclaim": "SHOUT",
    "whisper": "WHISPER",
    "murmur": "WHISPER",
    "mumble": "WHISPER",
}

# Adverb table (modifier, neutral verbs only). Lemma -> tag.
ADVERB_TABLE: dict[str, str] = {
    "angrily": "ANGRY",
    "furiously": "ANGRY",
    "sadly": "SAD",
    "sorrowfully": "SAD",
    "mournfully": "SAD",
    "fearfully": "FEARFUL",
    "nervously": "FEARFUL",
    "anxiously": "FEARFUL",
    "excitedly": "EXCITED",
    "eagerly": "EXCITED",
    "joyfully": "EXCITED",
    "softly": "GENTLE",
    "gently": "GENTLE",
    "tenderly": "GENTLE",
}

VERB_CONFIDENCE = 0.95
ADVERB_CONFIDENCE = 0.75
DEFAULT_THRESHOLD = 0.5


@dataclass(frozen=True)
class Modifier:
    speed_mult: float
    gain_db: float


# Starting points; tuned later (Task 8).
EMOTION_MODIFIERS: dict[str, Modifier] = {
    "NEUTRAL": Modifier(1.0, 0.0),
    "ANGRY": Modifier(1.08, 0.0),
    "EXCITED": Modifier(1.10, 0.0),
    "FEARFUL": Modifier(1.05, 0.0),
    "SAD": Modifier(0.92, 0.0),
    "GENTLE": Modifier(0.94, 0.0),
    "WHISPER": Modifier(0.90, -4.0),
    "SHOUT": Modifier(1.0, 3.0),
}


def _negated(clause: str, adverb: str) -> bool:
    words = clause.lower().split()
    for i, w in enumerate(words):
        if adverb in w.strip(".,!?;:\"'()"):
            window = words[max(0, i - 3) : i]
            if any(
                x in ("not", "never", "no") or x.endswith("n't") or x == "n't"
                for x in window
            ):
                return True
            return False
    return False


def detect_emotion(cue: AttributionCue, threshold: float = DEFAULT_THRESHOLD) -> tuple[str, float]:
    if cue is None:
        return ("NEUTRAL", 0.0)

    verb = (cue.verb_lemma or "").lower() if cue.verb_lemma else None
    adverbs = [a.lower() for a in (cue.adverbs or [])]

    # Verb primary.
    if verb is not None and verb in VERB_TABLE:
        conf = VERB_CONFIDENCE
        if conf < threshold:
            return ("NEUTRAL", conf)
        return (VERB_TABLE[verb], conf)

    # Special: "cried out" -> SHOUT.
    if verb == "cry" and "out" in adverbs:
        conf = VERB_CONFIDENCE
        if conf < threshold:
            return ("NEUTRAL", conf)
        return ("SHOUT", conf)

    # Neutral verb: adverb modifier only.
    # Drop negated adverbs.
    live_adverbs = [a for a in adverbs if not _negated(cue.clause or "", a)]

    for adv in live_adverbs:
        if adv in ADVERB_TABLE:
            conf = ADVERB_CONFIDENCE
            if conf < threshold:
                return ("NEUTRAL", conf)
            return (ADVERB_TABLE[adv], conf)

    return ("NEUTRAL", 0.0)


def apply_speed(base_speed: float, tag: str) -> float:
    mod = EMOTION_MODIFIERS.get(tag, EMOTION_MODIFIERS["NEUTRAL"])
    return base_speed * mod.speed_mult


__all__ = [
    "TAGS",
    "VERB_TABLE",
    "ADVERB_TABLE",
    "detect_emotion",
    "apply_speed",
    "EMOTION_MODIFIERS",
    "Modifier",
]
