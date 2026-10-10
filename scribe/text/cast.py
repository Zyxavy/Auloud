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

"""cast.yaml model, merge and resolution (SW5 + Slice 4 MV5).

SW5 writes a minimal narrator-only cast (see :func:`default_cast`). MV5 adds
the full multi-voice model used by ``draft`` (MV6) and ``build`` (MV7):

```yaml
narrator: {engine: kokoro, voice: am_onyx, speed: 1.0}
characters:
  Raskolnikov: {voice: bm_lewis, speed: 1.0}
aliases:
  Raskolnikov: ["Raskolnikov", "the young man"]  # canonical key, then surfaces
default_female: {voice: af_bella, speed: 1.0}
default_male: {voice: am_adam, speed: 1.0}
first_person: narrator  # or a character key: who says "I said" lines
overrides:
  - {chapter: 1, block: 8, quote: 1, speaker: Raskolnikov}  # quote-key form
  - {match: '^"Run!"', speaker: Raskolnikov}  # text-match form (regex search)
```

Format notes (per ``05-ScribeDesign.md`` as refined by Slice 4 decisions 1-3):

- ``narrator`` carries ``engine``/``voice``/``speed``. ``characters`` maps a
canonical character key to ``voice``/``speed`` with an optional ``engine``
(a missing or garbled engine means Kokoro, so v1 files validate unchanged;
an optional ``pitch`` is accepted but ignored in v1: decision 4 is speed
offsets only, no pitch shifting). ``default_female``/``default_male``
carry ``voice`` and an optional ``speed`` (default 1.0) plus the same
optional ``engine``.
- ``aliases`` maps a character key to its alias surfaces (the reverse of a
  flat alias-to-character map, so a surface claimed by two characters is a
  detectable duplicate). Matching is exact on :func:`text.speakers
  .normalize_name` keys (strip, casefold, determiner/title dropped): no
  fuzzy last-name guessing at build time, so what you write is what matches.
- ``overrides`` anchor on the stable quote key ``(chapter, block, quote)``
  (decision 2), never on ``sid``: a ``sid`` key is a validation error. The
  text-match form is a Python regex searched (not full-matched) against the
  quote text, so ``'^"Run!"'`` anchors the start the way the design example
  does. The two forms never mix in one entry.
- ``first_person`` (decision 6) names who speaks ``"I said"``-style lines:
  ``"narrator"`` (the default), a character key, or an alias surface (alias
  surfaces are accepted and resolve to their owner, pinned by test).
- Override and ``first_person`` speakers accept character keys AND alias
  surfaces (``"Ally"`` for character ``Alice`` validates and resolves).
  An override whose speaker normalizes to ``"i"`` redirects through
  ``first_person`` exactly like a raw ``"I said"`` line; ``"narrator"``
  (any case) resolves to the narrator entry.

Resolution order (:func:`resolve_speaker`, pure, used by ``build``):

1. Quote-key override matching ``(chapter, block, quote)`` (first match in
   file order), else text-match override whose regex searches the quote
   text (first match in file order). Quote-key beats text-match.
2. First-person raw speaker (normalizes to ``"i"``) goes to the
   ``first_person`` target (narrator by default, else the named character
   or alias surface). An override speaker that normalizes to ``"i"``
   lands here too (override-to-``"I"`` redirects, pinned by test).
3. The raw speaker canonicalized through ``aliases`` (character key match
   first, then alias surfaces), resolving to that character's voice.
4. Otherwise the gender-matched generic: a ``male`` hint takes
   ``default_male``; ``female`` AND ``unknown`` hints take
   ``default_female``. Rationale: an unknown hint is the common case
   (surnames like Raskolnikov carry no hint, MV3 says so explicitly), and
   the plan's ``default_female`` is the safer house default; the choice is
   pinned by test so a change is deliberate. The returned character key is
   ``"default_female"``/``"default_male"`` (collapsed, so the MV8 voices
   map stays small: narrator + top-N characters + two generics).
5. The ``"unknown"`` fallback speaker from MV4 resolves sanely through step
   4 (generic by hint, default female for unknown hints) and never crashes.

Invalid casts resolve leniently, never raise: a non-mapping cast counts as
empty, missing sections default (generics fall back to D-036 voices,
``first_person`` to narrator, overrides to none), malformed override
entries (bad regex, bad types, missing ids) are skipped, and unknown names
fall through to the gender-matched generic. Run :func:`validate_cast` to
find the errors; :func:`resolve_speaker` stays total.

Back-compat: a minimal SW5 narrator-only cast (``default_cast``) remains
valid (missing characters/aliases/overrides/generics/``first_person``
default as above) and resolves (every dialogue line to a generic);
:func:`merge_cast` upgrades it non-destructively, keeping the legacy
narrator voice byte-for-value.

Merge rule (:func:`merge_cast`, decision 3): re-running ``draft`` merges,
never clobbers. Every existing user entry (narrator voice/speed,
character voices/speeds, aliases, overrides, generics, ``first_person``,
the ``thought`` hook) is preserved byte-for-value; newly discovered
characters are appended; characters that vanished from discovery are kept
(their lines may return after a rule tweak). Missing top-level sections on
legacy SW5 casts are filled with MV5 defaults (generics, ``first_person``,
empty characters/aliases/overrides) without touching present values.

Draft palette (D-036 listening shortlist): narrator ``am_onyx``; characters
``bf_isabella``, ``bm_lewis``, ``im_nicola``, ``jf_alpha``, ``zf_xiaoxiao``,
``am_eric``; generic female ``af_bella``; generic male ``am_adam``.
Assignment rule (:func:`draft_cast`, deterministic): discovery surfaces are
clustered with :func:`text.speakers.cluster_aliases`, clusters rank by
``(-lines, normalized-key)``, and only the top N (default 5) become
characters; the rest are omitted and resolve to generics at build (promote
one by adding a ``characters`` entry by hand; the merge keeps it). The
canonical key is the highest-count surface (ties: smallest normalized,
then smallest surface). Cluster gender is the line-weighted majority hint
(ties break alphabetically: female, male, unknown). Voices: female
clusters take :data:`CHARACTER_PALETTE_FEMALE` in order, male clusters
:data:`CHARACTER_PALETTE_MALE` in order, unknown-hint clusters the full
:data:`CHARACTER_PALETTE` in D-036 order; each pool skips voices already
taken and cycles (``pool[i % len(pool)]``) when exhausted. Every drafted
speed is 1.0.

Thought-vs-speech hook (MV1 finding 5, product question deferred): the
model reserves an optional top-level ``thought`` mapping and an optional
per-character ``thought`` sub-mapping (both validated for shape, both
ignored by resolution and merge). A future voicing decision can hang
distinct voices there without a schema break; v1 has no behavior.

Writes use ``yaml.safe_dump(..., sort_keys=True)`` so files stay
byte-identical across runs (no timestamps anywhere).
"""

from __future__ import annotations

import copy
import math
import re
from collections.abc import Collection
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from text.speakers import (
    GENDER_FEMALE,
    GENDER_MALE,
    GENDER_UNKNOWN,
    cluster_aliases,
    normalize_name,
)

NARRATOR = "narrator"
NARRATOR_ENGINE = "kokoro"  # D-023: kokoro-onnx runtime.
#: Slice 8 SW2: every voice-carrying entry names one of these. A missing or
#: garbled ``engine`` resolves and validates as Kokoro (v1 files predate the
#: key), so existing casts and goldens validate byte-identically.
SUPPORTED_ENGINES = ("kokoro", "piper")
NARRATOR_VOICE = "af_heart"  # D-023 narrator voice (Slice 2 single-voice default).
NARRATOR_SPEED = 1.0

#: MV5 voice palette from the D-036 listening shortlist (exact Kokoro names).
PALETTE_NARRATOR_VOICE = "am_onyx"
CHARACTER_PALETTE: tuple[str, ...] = (
    "bf_isabella",
    "bm_lewis",
    "im_nicola",
    "jf_alpha",
    "zf_xiaoxiao",
    "am_eric",
)
#: Female character voices of the palette (Kokoro ``*f_*`` ids), in D-036 order.
CHARACTER_PALETTE_FEMALE: tuple[str, ...] = ("bf_isabella", "jf_alpha", "zf_xiaoxiao")
#: Male character voices of the palette (Kokoro ``*m_*`` ids), in D-036 order.
CHARACTER_PALETTE_MALE: tuple[str, ...] = ("bm_lewis", "im_nicola", "am_eric")
#: Generic fallback voices (D-036).
DEFAULT_FEMALE_VOICE = "af_bella"
DEFAULT_MALE_VOICE = "am_adam"

#: Top-level keys of a multi-voice cast (also the generic character keys).
CHARACTERS_KEY = "characters"
ALIASES_KEY = "aliases"
DEFAULT_FEMALE_KEY = "default_female"
DEFAULT_MALE_KEY = "default_male"
FIRST_PERSON_KEY = "first_person"
OVERRIDES_KEY = "overrides"
THOUGHT_KEY = "thought"

#: Resolved character keys for the generic fallbacks (the voices-map keys).
GENERIC_FEMALE = DEFAULT_FEMALE_KEY
GENERIC_MALE = DEFAULT_MALE_KEY

#: Character names that collide with the fixed top-level entries.
RESERVED_NAMES = frozenset({NARRATOR, DEFAULT_FEMALE_KEY, DEFAULT_MALE_KEY})

#: Top-N default: only the top 5 characters by line count get own voices.
TOP_N_DEFAULT = 5

#: Drafted speeds are always 1.0 (offsets are a user edit, never a guess).
DEFAULT_SPEED = NARRATOR_SPEED


def default_cast() -> dict[str, dict[str, Any]]:
    """Minimal SW5 cast: narrator only (D-023 voice, speed 1.0).

    Unchanged by MV5 so Slice 2 outputs keep validating; new multi-voice
    drafts start from :func:`default_multivoice_cast` (D-036 palette) and
    legacy files upgrade non-destructively in :func:`merge_cast`.
    """
    return {
        NARRATOR: {
            "engine": NARRATOR_ENGINE,
            "voice": NARRATOR_VOICE,
            "speed": NARRATOR_SPEED,
        }
    }


def default_multivoice_cast() -> dict[str, Any]:
    """Empty multi-voice cast: D-036 narrator + generics, no characters yet."""
    return {
        NARRATOR: {
            "engine": NARRATOR_ENGINE,
            "voice": PALETTE_NARRATOR_VOICE,
            "speed": NARRATOR_SPEED,
        },
        CHARACTERS_KEY: {},
        ALIASES_KEY: {},
        DEFAULT_FEMALE_KEY: {"voice": DEFAULT_FEMALE_VOICE, "speed": DEFAULT_SPEED},
        DEFAULT_MALE_KEY: {"voice": DEFAULT_MALE_VOICE, "speed": DEFAULT_SPEED},
        FIRST_PERSON_KEY: NARRATOR,
        OVERRIDES_KEY: [],
    }


def write_cast(path: Path | str, cast: dict[str, Any] | None = None) -> Path:
    """Write ``cast.yaml`` deterministically; returns the path written."""
    out = Path(path)
    data = default_cast() if cast is None else cast
    text = yaml.safe_dump(data, sort_keys=True, allow_unicode=True)
    out.write_text(text, encoding="utf-8")
    return out


def read_cast(path: Path | str) -> dict[str, Any]:
    """Read a ``cast.yaml`` back as plain dicts (safe_load, no code exec)."""
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError(f"{path}: cast.yaml must be a mapping (see text.cast for the model)")
    return raw


@dataclass(frozen=True)
class ResolvedVoice:
    """Final voice for one dialogue quote: engine, character key, voice id, speed."""

    character: str
    voice: str
    speed: float
    engine: str = NARRATOR_ENGINE


def canonicalize_speaker(
    raw_speaker: str,
    characters: dict[str, Any],
    aliases: dict[str, Any],
) -> str | None:
    """Map a raw speaker string to its canonical character key, or None.

    Exact match on :func:`text.speakers.normalize_name` keys: character
    keys first, then alias surfaces. Case, determiners and titles are
    forgiven (``"the Alice"`` finds ``Alice``); last-name guessing is not
    (``"Bennet"`` does not find ``"Elizabeth Bennet"`` unless you alias it).
    """
    want = normalize_name(raw_speaker or "")
    if not want:
        return None
    for key in characters:
        if isinstance(key, str) and normalize_name(key) == want:
            return key
    for key, surfaces in aliases.items():
        if not isinstance(key, str) or key not in characters:
            continue
        if not isinstance(surfaces, list):
            continue
        for surface in surfaces:
            if isinstance(surface, str) and normalize_name(surface) == want:
                return key
    return None


def _entry_engine(entry: Any) -> str:
    """Engine id for a voice-carrying entry (SW2, never raises).

    Missing or garbled ``engine`` means Kokoro: v1 casts predate the key
    (only the narrator carried it), and resolution stays total. Validation
    is the layer that reports a bad engine string.
    """
    if isinstance(entry, dict):
        engine = entry.get("engine")
        if isinstance(engine, str) and engine.strip() in SUPPORTED_ENGINES:
            return engine.strip()
    return NARRATOR_ENGINE


def cast_engines(cast: dict[str, Any]) -> list[str]:
    """Engine ids the cast needs, in :data:`SUPPORTED_ENGINES` order.

    Reads the narrator, every character, and both generics (missing
    engines count as Kokoro, same as resolution). Pure; :mod:`build` uses
    it to construct exactly the engines a render needs. Never raises.
    """
    if not isinstance(cast, dict):
        return [NARRATOR_ENGINE]
    needed: set[str] = set()
    narrator = cast.get(NARRATOR)
    needed.add(_entry_engine(narrator) if isinstance(narrator, dict) else NARRATOR_ENGINE)
    characters = cast.get(CHARACTERS_KEY)
    if isinstance(characters, dict):
        for entry in characters.values():
            needed.add(_entry_engine(entry))
    for key in (DEFAULT_FEMALE_KEY, DEFAULT_MALE_KEY):
        entry = cast.get(key)
        if isinstance(entry, dict):
            needed.add(_entry_engine(entry))
    return [engine for engine in SUPPORTED_ENGINES if engine in needed]


def _entry_voice(entry: Any, *, fallback_voice: str) -> tuple[str, float]:
    """``(voice, speed)`` from a cast entry, defensively (never raises).

    Invalid casts resolve to fallbacks here; :func:`validate_cast` is the
    layer that reports the errors.
    """
    voice, speed = fallback_voice, DEFAULT_SPEED
    if isinstance(entry, dict):
        raw_voice = entry.get("voice")
        if isinstance(raw_voice, str) and raw_voice.strip():
            voice = raw_voice
        raw_speed = entry.get("speed", DEFAULT_SPEED)
        if (
            isinstance(raw_speed, (int, float))
            and not isinstance(raw_speed, bool)
            and math.isfinite(float(raw_speed))
            and float(raw_speed) > 0
        ):
            speed = float(raw_speed)
    return voice, speed


def _generic_voice(cast: dict[str, Any], *, male: bool) -> ResolvedVoice:
    """Generic fallback: ``default_male`` for male hints, else default female.

    Unknown (and garbled) hints deliberately fall back to the female
    generic; see the module docstring for the rationale.
    """
    if male:
        entry = cast.get(DEFAULT_MALE_KEY)
        voice, speed = _entry_voice(entry, fallback_voice=DEFAULT_MALE_VOICE)
        return ResolvedVoice(
            character=GENERIC_MALE, voice=voice, speed=speed, engine=_entry_engine(entry)
        )
    entry = cast.get(DEFAULT_FEMALE_KEY)
    voice, speed = _entry_voice(entry, fallback_voice=DEFAULT_FEMALE_VOICE)
    return ResolvedVoice(
        character=GENERIC_FEMALE, voice=voice, speed=speed, engine=_entry_engine(entry)
    )


def _match_quote_override(
    entry: Any, *, chapter: int | None, block: int | None, quote: int | None
) -> str | None:
    """Speaker of a quote-key override matching all three ids, or None."""
    if not isinstance(entry, dict):
        return None
    if OVERRIDES_KEY in entry or "match" in entry or "sid" in entry:
        if not all(k in entry for k in ("chapter", "block", "quote")):
            return None
    if not all(k in entry for k in ("chapter", "block", "quote", "speaker")):
        return None
    if chapter is None or block is None or quote is None:
        return None
    for key in ("chapter", "block", "quote"):
        value = entry[key]
        if not isinstance(value, int) or isinstance(value, bool):
            return None
    if entry["chapter"] == chapter and entry["block"] == block and entry["quote"] == quote:
        speaker = entry.get("speaker")
        return speaker if isinstance(speaker, str) and speaker.strip() else None
    return None


def _match_text_override(entry: Any, *, text: str) -> str | None:
    """Speaker of a text-match override whose regex searches ``text``."""
    if not isinstance(entry, dict):
        return None
    if "match" not in entry or "speaker" not in entry:
        return None
    pattern = entry.get("match")
    speaker = entry.get("speaker")
    if not isinstance(pattern, str) or not pattern:
        return None
    if not isinstance(speaker, str) or not speaker.strip():
        return None
    try:
        matched = re.search(pattern, text or "")
    except re.error:
        return None
    return speaker if matched else None


def _voice_for_canonical(cast: dict[str, Any], canonical: str) -> ResolvedVoice | None:
    """Voice for a canonical name (narrator, character, or alias surface)."""
    characters = cast.get(CHARACTERS_KEY)
    if not isinstance(characters, dict):
        characters = {}
    aliases = cast.get(ALIASES_KEY)
    if not isinstance(aliases, dict):
        aliases = {}
    if canonical == NARRATOR:
        entry = cast.get(NARRATOR)
        voice, speed = _entry_voice(entry, fallback_voice=NARRATOR_VOICE)
        return ResolvedVoice(
            character=NARRATOR, voice=voice, speed=speed, engine=_entry_engine(entry)
        )
    if isinstance(canonical, str) and canonical in characters:
        entry = characters[canonical]
        voice, speed = _entry_voice(entry, fallback_voice=DEFAULT_FEMALE_VOICE)
        return ResolvedVoice(
            character=canonical, voice=voice, speed=speed, engine=_entry_engine(entry)
        )
    target = canonicalize_speaker(canonical, characters, aliases)
    if target is not None:
        entry = characters[target]
        voice, speed = _entry_voice(entry, fallback_voice=DEFAULT_FEMALE_VOICE)
        return ResolvedVoice(
            character=target, voice=voice, speed=speed, engine=_entry_engine(entry)
        )
    return None


def resolve_speaker(
    cast: dict[str, Any],
    *,
    raw_speaker: str,
    gender: str = GENDER_UNKNOWN,
    chapter: int | None = None,
    block: int | None = None,
    quote: int | None = None,
    text: str = "",
) -> ResolvedVoice:
    """Resolve one quote to its final ``(character, voice, speed)``.

    Layer order (first hit wins): quote-key override, text-match override,
    first-person (raw normalizes to ``"i"``), alias/character match, then
    the gender-matched generic (male hint to ``default_male``; female and
    unknown hints to ``default_female``). Never raises: malformed entries
    and unknown names fall through to the generic instead of crashing (run
    :func:`validate_cast` to find the errors). ``gender`` accepts
    ``male``/``female``/``unknown``; anything else counts as unknown.
    """
    if not isinstance(cast, dict):
        cast = {}
    characters = cast.get(CHARACTERS_KEY)
    if not isinstance(characters, dict):
        characters = {}
    aliases = cast.get(ALIASES_KEY)
    if not isinstance(aliases, dict):
        aliases = {}
    overrides = cast.get(OVERRIDES_KEY)
    if not isinstance(overrides, list):
        overrides = []

    effective = raw_speaker if isinstance(raw_speaker, str) else ""
    for entry in overrides:
        hit = _match_quote_override(entry, chapter=chapter, block=block, quote=quote)
        if hit is not None:
            effective = hit
            break
    else:
        for entry in overrides:
            hit = _match_text_override(entry, text=text or "")
            if hit is not None:
                effective = hit
                break

    if normalize_name(effective) == "i":
        target = cast.get(FIRST_PERSON_KEY, NARRATOR)
        if not isinstance(target, str) or not target.strip():
            target = NARRATOR
        resolved = _voice_for_canonical(cast, target)
        if resolved is not None:
            return resolved
        entry = cast.get(NARRATOR)
        voice, speed = _entry_voice(entry, fallback_voice=NARRATOR_VOICE)
        return ResolvedVoice(
            character=NARRATOR, voice=voice, speed=speed, engine=_entry_engine(entry)
        )

    if normalize_name(effective) == NARRATOR:
        entry = cast.get(NARRATOR)
        voice, speed = _entry_voice(entry, fallback_voice=NARRATOR_VOICE)
        return ResolvedVoice(
            character=NARRATOR, voice=voice, speed=speed, engine=_entry_engine(entry)
        )

    canonical = canonicalize_speaker(effective, characters, aliases)
    if canonical is not None:
        entry = characters.get(canonical)
        voice, speed = _entry_voice(entry, fallback_voice=DEFAULT_FEMALE_VOICE)
        return ResolvedVoice(
            character=canonical, voice=voice, speed=speed, engine=_entry_engine(entry)
        )
    return _generic_voice(cast, male=(gender == GENDER_MALE))


def _pick_palette_voice(pool: Collection[str], used: set[str], index: int) -> str:
    """First pool voice not yet taken, else ``pool[index % len(pool)]``."""
    ordered = list(pool)
    for voice in ordered:
        if voice not in used:
            return voice
    return ordered[index % len(ordered)]


def draft_cast(
    line_counts: dict[str, int],
    gender_hints: dict[str, str] | None = None,
    *,
    top_n: int = TOP_N_DEFAULT,
) -> dict[str, Any]:
    """Draft a full cast from discovery counts (pure; MV6 wires it to files).

    :param line_counts: raw speaker surface to dialogue-line count (blank
        surfaces are ignored).
    :param gender_hints: raw surface to MV3 hint (``male``/``female``/
        ``unknown``); missing or garbled hints count as unknown.
    :param top_n: how many ranked clusters become characters (default 5);
        the rest are omitted and resolve to generics at build.
    :raises ValueError: ``top_n`` below 1.
    """
    if not isinstance(top_n, int) or isinstance(top_n, bool) or top_n < 1:
        raise ValueError(f"top_n must be a positive int (got {top_n!r})")
    hints = gender_hints if isinstance(gender_hints, dict) else {}
    counts = {
        surface: lines
        for surface, lines in (line_counts or {}).items()
        if isinstance(surface, str) and normalize_name(surface) and isinstance(lines, int)
    }

    clusters = cluster_aliases(list(counts))
    ranked: list[tuple[int, str, list[str]]] = []
    for norm_key, members in clusters.items():
        total = sum(counts[m] for m in members)
        ranked.append((total, norm_key, sorted(members)))
    ranked.sort(key=lambda item: (-item[0], item[1]))

    cast = default_multivoice_cast()
    characters: dict[str, dict[str, Any]] = {}
    aliases: dict[str, list[str]] = {}
    used: set[str] = set()
    female_used = male_used = unknown_used = 0
    for total, _norm_key, members in ranked[:top_n]:
        canonical = sorted(members, key=lambda s: (-counts[s], normalize_name(s), s))[0]
        votes = {GENDER_FEMALE: 0, GENDER_MALE: 0, GENDER_UNKNOWN: 0}
        for member in members:
            hint = hints.get(member)
            hint = hint if hint in (GENDER_FEMALE, GENDER_MALE) else GENDER_UNKNOWN
            votes[hint] += counts[member]
        gender = sorted(votes.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]
        if gender == GENDER_FEMALE:
            voice = _pick_palette_voice(CHARACTER_PALETTE_FEMALE, used, female_used)
            female_used += 1
        elif gender == GENDER_MALE:
            voice = _pick_palette_voice(CHARACTER_PALETTE_MALE, used, male_used)
            male_used += 1
        else:
            voice = _pick_palette_voice(CHARACTER_PALETTE, used, unknown_used)
            unknown_used += 1
        used.add(voice)
        characters[canonical] = {"voice": voice, "speed": DEFAULT_SPEED}
        others = sorted(m for m in members if m != canonical)
        if others:
            aliases[canonical] = others
    cast[CHARACTERS_KEY] = characters
    cast[ALIASES_KEY] = aliases
    return cast


def merge_cast(existing: dict[str, Any], discovered: dict[str, Any]) -> dict[str, Any]:
    """Merge a fresh :func:`draft_cast` into a user-edited cast (pure).

    Existing entries always win: narrator, character voices/speeds,
    aliases, overrides, generics, ``first_person`` and the ``thought``
    hook are preserved exactly. Discovered characters whose normalized key
    (or any alias surface) is already known are skipped; the rest are
    appended with their drafted voices. Missing top-level sections on
    legacy casts are filled with MV5 defaults. Neither input is mutated.
    """
    base = copy.deepcopy(existing) if isinstance(existing, dict) else {}
    fresh = discovered if isinstance(discovered, dict) else {}

    narrator = base.get(NARRATOR)
    if not isinstance(narrator, dict):
        base[NARRATOR] = {
            "engine": NARRATOR_ENGINE,
            "voice": PALETTE_NARRATOR_VOICE,
            "speed": NARRATOR_SPEED,
        }
    else:
        narrator.setdefault("engine", NARRATOR_ENGINE)
        narrator.setdefault("voice", PALETTE_NARRATOR_VOICE)
        narrator.setdefault("speed", NARRATOR_SPEED)
    for key, voice in (
        (DEFAULT_FEMALE_KEY, DEFAULT_FEMALE_VOICE),
        (DEFAULT_MALE_KEY, DEFAULT_MALE_VOICE),
    ):
        entry = base.get(key)
        if not isinstance(entry, dict):
            base[key] = {"voice": voice, "speed": DEFAULT_SPEED}
        else:
            entry.setdefault("voice", voice)
            entry.setdefault("speed", DEFAULT_SPEED)
    if not isinstance(base.get(CHARACTERS_KEY), dict):
        base[CHARACTERS_KEY] = {}
    if not isinstance(base.get(ALIASES_KEY), dict):
        base[ALIASES_KEY] = {}
    if not isinstance(base.get(OVERRIDES_KEY), list):
        base[OVERRIDES_KEY] = []
    if FIRST_PERSON_KEY not in base:
        base[FIRST_PERSON_KEY] = NARRATOR

    known: set[str] = set()
    for key in base[CHARACTERS_KEY]:
        if isinstance(key, str) and normalize_name(key):
            known.add(normalize_name(key))
    for surfaces in base[ALIASES_KEY].values():
        if isinstance(surfaces, list):
            for surface in surfaces:
                if isinstance(surface, str) and normalize_name(surface):
                    known.add(normalize_name(surface))

    fresh_characters = fresh.get(CHARACTERS_KEY)
    fresh_aliases = fresh.get(ALIASES_KEY)
    if not isinstance(fresh_characters, dict):
        return base
    if not isinstance(fresh_aliases, dict):
        fresh_aliases = {}
    for key, entry in fresh_characters.items():
        if not isinstance(key, str) or not normalize_name(key):
            continue
        surfaces = fresh_aliases.get(key)
        candidates = [key] + (list(surfaces) if isinstance(surfaces, list) else [])
        if any(
            isinstance(c, str) and normalize_name(c) and normalize_name(c) in known
            for c in candidates
        ):
            continue
        base[CHARACTERS_KEY][key] = copy.deepcopy(entry)
        if isinstance(surfaces, list) and surfaces:
            base[ALIASES_KEY][key] = copy.deepcopy(surfaces)
        for candidate in candidates:
            if isinstance(candidate, str) and normalize_name(candidate):
                known.add(normalize_name(candidate))
    return base


def _check_voice(
    voice: Any,
    *,
    key: str,
    source: str,
    errors: list[str],
    known_voices: Collection[str] | None,
    engine: str = NARRATOR_ENGINE,
) -> None:
    """Validate one voice field (non-empty; real engine voice when known)."""
    if not isinstance(voice, str) or not voice.strip():
        errors.append(f"{source}: {key}: voice must be a non-empty string (got {voice!r})")
    elif known_voices is not None and voice not in known_voices:
        errors.append(
            f"{source}: {key}: unknown voice {voice!r} (must be a real {engine} voice)"
        )


def _check_engine(
    engine: Any,
    *,
    key: str,
    source: str,
    errors: list[str],
    required: bool,
) -> str:
    """Validate one engine field; return the effective engine (never raises).

    Voice-carrying entries name a :data:`SUPPORTED_ENGINES` member. The
    narrator entry requires it (v1 files all carry it); characters and
    generics leave it optional, missing meaning Kokoro. A bad value is an
    error and resolves as Kokoro downstream.
    """
    if engine is None:
        if required:
            errors.append(
                f"{source}: {key}: missing (must be one of {list(SUPPORTED_ENGINES)})"
            )
        return NARRATOR_ENGINE
    if not isinstance(engine, str) or engine.strip() not in SUPPORTED_ENGINES:
        errors.append(
            f"{source}: {key}: must be one of {list(SUPPORTED_ENGINES)} (got {engine!r})"
        )
        return NARRATOR_ENGINE
    return engine.strip()


def _voices_for_engine(
    engine: str,
    known_voices: Collection[str] | None,
    known_voices_per_engine: dict[str, Collection[str] | None] | None,
) -> Collection[str] | None:
    """Voice allow-list for ``engine`` (None means shape-only).

    An explicit per-engine map wins (even a None value: shape-only for
    that engine); otherwise Kokoro falls back to ``known_voices`` and any
    other engine validates shape only (its list is unknown here).
    """
    if known_voices_per_engine is not None and engine in known_voices_per_engine:
        return known_voices_per_engine[engine]
    if engine == NARRATOR_ENGINE:
        return known_voices
    return None


def _check_speed(speed: Any, *, key: str, source: str, errors: list[str]) -> None:
    """Validate one speed field (finite number above zero)."""
    if (
        not isinstance(speed, (int, float))
        or isinstance(speed, bool)
        or not math.isfinite(float(speed))
        or float(speed) <= 0
    ):
        errors.append(f"{source}: {key}: speed must be a number above 0 (got {speed!r})")


def validate_cast(
    cast: dict[str, Any],
    *,
    source: str = "cast.yaml",
    known_voices: Collection[str] | None = None,
    known_voices_per_engine: dict[str, Collection[str] | None] | None = None,
) -> list[str]:
    """Validate a parsed cast; returns error strings (empty when valid).

    Every error names the file (``source``), the dotted key, and the rule,
    e.g. ``cast.yaml: characters.Ana.voice: unknown voice 'xx' (must be a
    real kokoro voice)``. Checks: mapping shape and known top-level keys;
    narrator engine/voice/speed; character names (non-empty, unreserved,
    unique once normalized) with engine/voice/speed (``pitch`` allowed but
    ignored in v1); aliases pointing at real characters with no duplicate
    or character-colliding surfaces; generics engine/voice/speed;
    ``first_person`` naming ``narrator`` or a character/alias; overrides in
    exactly one of the two forms (quote-key or text-match, never ``sid``,
    compilable regex, known speaker); ``thought`` hook shape only. Voice
    names are checked per entry engine: an explicit
    ``known_voices_per_engine`` map wins per engine, else Kokoro falls back
    to ``known_voices`` and other engines check shape only; ``None``
    everywhere checks shape only.
    """
    errors: list[str] = []
    if not isinstance(cast, dict):
        return [f"{source}: cast: must be a mapping (got {type(cast).__name__})"]

    allowed_top = {
        NARRATOR,
        CHARACTERS_KEY,
        ALIASES_KEY,
        DEFAULT_FEMALE_KEY,
        DEFAULT_MALE_KEY,
        FIRST_PERSON_KEY,
        OVERRIDES_KEY,
        THOUGHT_KEY,
    }
    for key in cast:
        if key not in allowed_top:
            errors.append(
                f"{source}: {key}: unknown section (expected one of {sorted(allowed_top)})"
            )

    narrator = cast.get(NARRATOR)
    if narrator is None:
        errors.append(f"{source}: narrator: missing entry (needs engine, voice, speed)")
    elif not isinstance(narrator, dict):
        errors.append(f"{source}: narrator: must be a mapping (got {type(narrator).__name__})")
    else:
        for key in narrator:
            if key not in ("engine", "voice", "speed"):
                errors.append(
                    f"{source}: narrator.{key}: unknown key (expected engine, voice, speed)"
                )
        narrator_engine = _check_engine(
            narrator.get("engine"),
            key="narrator.engine",
            source=source,
            errors=errors,
            required=True,
        )
        narrator_voices = _voices_for_engine(
            narrator_engine, known_voices, known_voices_per_engine
        )
        if "voice" not in narrator:
            errors.append(
                f"{source}: narrator.voice: missing (must name a {narrator_engine} voice)"
            )
        else:
            _check_voice(
                narrator["voice"],
                key="narrator.voice",
                source=source,
                errors=errors,
                known_voices=narrator_voices,
                engine=narrator_engine,
            )
        if "speed" not in narrator:
            errors.append(f"{source}: narrator.speed: missing (must be a number above 0)")
        else:
            _check_speed(narrator["speed"], key="narrator.speed", source=source, errors=errors)

    characters = cast.get(CHARACTERS_KEY, {})
    if CHARACTERS_KEY in cast and not isinstance(characters, dict):
        errors.append(f"{source}: characters: must be a mapping (got {type(characters).__name__})")
        characters = {}
    seen_character_names: dict[str, str] = {}
    for name, entry in characters.items():
        if not isinstance(name, str) or not name.strip():
            errors.append(f"{source}: characters.{name!r}: name must be a non-empty string")
            continue
        if name in RESERVED_NAMES:
            errors.append(
                f"{source}: characters.{name}: reserved name (collides with the {name!r} section)"
            )
            continue
        folded = normalize_name(name)
        if folded in seen_character_names:
            errors.append(
                f"{source}: characters.{name}: duplicates "
                f"characters.{seen_character_names[folded]} after normalization"
            )
            continue
        seen_character_names[folded] = name
        if not isinstance(entry, dict):
            errors.append(
                f"{source}: characters.{name}: must be a mapping (got {type(entry).__name__})"
            )
            continue
        for key in entry:
            if key not in ("engine", "voice", "speed", "pitch", THOUGHT_KEY):
                errors.append(
                    f"{source}: characters.{name}.{key}: "
                    "unknown key (expected engine, voice, speed)"
                )
        entry_engine = _check_engine(
            entry.get("engine"),
            key=f"characters.{name}.engine",
            source=source,
            errors=errors,
            required=False,
        )
        entry_voices = _voices_for_engine(
            entry_engine, known_voices, known_voices_per_engine
        )
        if "voice" not in entry:
            errors.append(
                f"{source}: characters.{name}.voice: missing "
                f"(must name a {entry_engine} voice)"
            )
        else:
            _check_voice(
                entry["voice"],
                key=f"characters.{name}.voice",
                source=source,
                errors=errors,
                known_voices=entry_voices,
                engine=entry_engine,
            )
        if "speed" not in entry:
            errors.append(f"{source}: characters.{name}.speed: missing (must be a number above 0)")
        else:
            _check_speed(
                entry["speed"], key=f"characters.{name}.speed", source=source, errors=errors
            )
        if "pitch" in entry and (
            not isinstance(entry["pitch"], (int, float))
            or isinstance(entry["pitch"], bool)
            or not math.isfinite(float(entry["pitch"]))
            or float(entry["pitch"]) <= 0
        ):
            errors.append(
                f"{source}: characters.{name}.pitch: must be a number above 0 "
                f"(got {entry['pitch']!r}; ignored in v1)"
            )

    aliases = cast.get(ALIASES_KEY, {})
    if ALIASES_KEY in cast and not isinstance(aliases, dict):
        errors.append(
            f"{source}: aliases: must be a mapping of character to surfaces "
            f"(got {type(aliases).__name__})"
        )
        aliases = {}
    alias_claims: dict[str, str] = {}
    for owner, surfaces in aliases.items():
        if owner not in characters:
            errors.append(f"{source}: aliases.{owner}: points nowhere (no such character)")
            continue
        if not isinstance(surfaces, list):
            errors.append(
                f"{source}: aliases.{owner}: must be a list of surfaces "
                f"(got {type(surfaces).__name__})"
            )
            continue
        for surface in surfaces:
            if not isinstance(surface, str) or not surface.strip():
                errors.append(
                    f"{source}: aliases.{owner}: alias must be a non-empty string (got {surface!r})"
                )
                continue
            folded = normalize_name(surface)
            if folded in seen_character_names and seen_character_names[folded] != owner:
                errors.append(
                    f"{source}: aliases.{owner}: alias {surface!r} collides with "
                    f"character {seen_character_names[folded]!r}"
                )
                continue
            if folded in alias_claims and alias_claims[folded] != owner:
                errors.append(
                    f"{source}: aliases.{owner}: duplicate alias {surface!r} "
                    f"(also claimed by characters.{alias_claims[folded]})"
                )
                continue
            alias_claims.setdefault(folded, owner)

    for key in (DEFAULT_FEMALE_KEY, DEFAULT_MALE_KEY):
        entry = cast.get(key)
        if entry is None:
            continue
        if not isinstance(entry, dict):
            errors.append(f"{source}: {key}: must be a mapping (got {type(entry).__name__})")
            continue
        for sub in entry:
            if sub not in ("engine", "voice", "speed"):
                errors.append(f"{source}: {key}.{sub}: unknown key (expected engine, voice, speed)")
        generic_engine = _check_engine(
            entry.get("engine"),
            key=f"{key}.engine",
            source=source,
            errors=errors,
            required=False,
        )
        generic_voices = _voices_for_engine(
            generic_engine, known_voices, known_voices_per_engine
        )
        if "voice" not in entry:
            errors.append(
                f"{source}: {key}.voice: missing (must name a {generic_engine} voice)"
            )
        else:
            _check_voice(
                entry["voice"],
                key=f"{key}.voice",
                source=source,
                errors=errors,
                known_voices=generic_voices,
                engine=generic_engine,
            )
        if "speed" in entry:
            _check_speed(entry["speed"], key=f"{key}.speed", source=source, errors=errors)

    known_speakers = {NARRATOR} | set(characters)
    for surfaces in aliases.values():
        if isinstance(surfaces, list):
            for surface in surfaces:
                if isinstance(surface, str):
                    known_speakers.add(surface)
    first_person = cast.get(FIRST_PERSON_KEY, NARRATOR)
    if FIRST_PERSON_KEY in cast and (
        not isinstance(first_person, str) or first_person not in known_speakers
    ):
        errors.append(
            f"{source}: first_person: must name {NARRATOR!r} or a character/alias "
            f"(got {first_person!r})"
        )

    overrides = cast.get(OVERRIDES_KEY, [])
    if OVERRIDES_KEY in cast and not isinstance(overrides, list):
        errors.append(f"{source}: overrides: must be a list (got {type(overrides).__name__})")
        overrides = []
    for pos, entry in enumerate(overrides):
        label = f"{OVERRIDES_KEY}[{pos}]"
        if not isinstance(entry, dict):
            errors.append(f"{source}: {label}: must be a mapping (got {type(entry).__name__})")
            continue
        if "sid" in entry:
            errors.append(f"{source}: {label}: anchors on (chapter, block, quote), never sid")
            continue
        has_key_form = any(k in entry for k in ("chapter", "block", "quote"))
        has_match_form = "match" in entry
        if has_key_form and has_match_form:
            errors.append(
                f"{source}: {label}: combines the quote-key and text-match forms "
                "(use one or the other)"
            )
            continue
        if has_key_form:
            for key in entry:
                if key not in ("chapter", "block", "quote", "speaker"):
                    errors.append(
                        f"{source}: {label}.{key}: unknown key "
                        "(expected chapter, block, quote, speaker)"
                    )
            for key in ("chapter", "block", "quote"):
                if key not in entry:
                    errors.append(f"{source}: {label}: missing {key!r} (quote-key form)")
                elif (
                    not isinstance(entry[key], int)
                    or isinstance(entry[key], bool)
                    or (entry[key] < 1 if key != "block" else entry[key] < 0)
                ):
                    errors.append(
                        f"{source}: {label}.{key}: must be an int "
                        f"{'>= 0' if key == 'block' else '>= 1'} (got {entry[key]!r})"
                    )
            if "speaker" not in entry:
                errors.append(f"{source}: {label}: missing 'speaker'")
            elif entry["speaker"] not in known_speakers:
                errors.append(
                    f"{source}: {label}.speaker: points nowhere "
                    f"(no such character or alias: {entry['speaker']!r})"
                )
        elif has_match_form:
            for key in entry:
                if key not in ("match", "speaker"):
                    errors.append(f"{source}: {label}.{key}: unknown key (expected match, speaker)")
            pattern = entry.get("match")
            if not isinstance(pattern, str) or not pattern:
                errors.append(f"{source}: {label}.match: must be a non-empty regex")
            else:
                try:
                    re.compile(pattern)
                except re.error as exc:
                    errors.append(f"{source}: {label}.match: bad regex ({exc})")
            if "speaker" not in entry:
                errors.append(f"{source}: {label}: missing 'speaker'")
            elif entry["speaker"] not in known_speakers:
                errors.append(
                    f"{source}: {label}.speaker: points nowhere "
                    f"(no such character or alias: {entry['speaker']!r})"
                )
        else:
            errors.append(
                f"{source}: {label}: malformed override "
                "(needs chapter+block+quote+speaker, or match+speaker)"
            )

    thought = cast.get(THOUGHT_KEY)
    if thought is not None and not isinstance(thought, dict):
        errors.append(f"{source}: thought: must be a mapping (got {type(thought).__name__})")
    for name, entry in characters.items():
        if (
            isinstance(entry, dict)
            and THOUGHT_KEY in entry
            and not isinstance(entry[THOUGHT_KEY], dict)
        ):
            errors.append(
                f"{source}: characters.{name}.thought: must be a mapping "
                f"(got {type(entry[THOUGHT_KEY]).__name__})"
            )
    return errors
