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

"""Slice 6 UI6: Cast patch operations and read models (pure where possible).

``cast.yaml`` stays the source of truth. The UI never writes raw YAML: every
edit is a typed patch op applied to the freshly-read file through the MV5
:mod:`text.cast` functions, then validated with :func:`text.cast
.validate_cast` before anything hits disk. Full replaces (``PUT``) validate
the same way. Writes are guarded by the file's mtime: a hand edit made
meanwhile is a ``409`` conflict carrying the current state, never a silent
overwrite.

Supported ops (``PATCH`` ``ops`` list, applied in order, atomically — every
op validates before any write, so a bad op aborts the whole list)::

    {"op": "set_voice", "character": "Alice", "voice": "jf_alpha"}
    {"op": "set_voice", "character": "Bob", "voice": "en_US-lessac-low", "engine": "piper"}
    {"op": "set_speed", "character": "Alice", "speed": 1.1}
    {"op": "set_first_person", "value": "narrator"}   # or a character/alias
    {"op": "add_override", "chapter": 1, "block": 8, "quote": 1, "speaker": "Bob"}
    {"op": "add_override", "match": "^\\"Run!\\"", "speaker": "Alice"}
    {"op": "remove_override", "index": 0}             # or a quote key / match

Patch errors are shaped ``{file, rule, message}``. Errors that come out of
:func:`text.cast.validate_cast` are re-split verbatim (rejoining
``file + ": " + rule + ": " + message`` gives the CLI string exactly); op
structural errors use rule ``bad-op`` / ``override-not-found``.

YAML-comments verdict (plan D6, loud by design): the writer is PyYAML
(``yaml.safe_dump``), which cannot round-trip ``#`` comments — and adding a
round-trip library (ruamel.yaml) would be a new dependency, which needs
asking first. So a UI save preserves every hand-edited *value* (the patch
applies onto the freshly-read file) but drops ``#`` comments. This is
documented here, in the ``GET`` response (``has_comments`` plus
:const:`COMMENT_WARNING`, so the Cast view can banner it only when the file
actually has comments), and in ``docs/DECISIONS.md``. The mtime guard is what
protects hand edits from silent destruction, not comment preservation.

UI7 versioned audition cache (added, flat UI6 lookup kept for seeding):
clips are keyed by ``(voice, engine version, sample text)`` under
``<workspace>/.scribe/voice-samples/<safe-version>/<text-hash>/`` (see
:func:`versioned_samples_dir`). The engine version comes from package
metadata only (never a model load), the text hash from the fixed audition
sentence, and the ``GET`` path serves versioned first with a legacy flat
fallback. Generation runs as a detached ``voices-sample`` job (never inside
the server process); concurrent requests for the same missing clip coalesce
to one job (see the UI7 D-entry).
"""

from __future__ import annotations

import copy
import re
from pathlib import Path
from typing import Any

from text.cast import (
    ALIASES_KEY,
    CHARACTERS_KEY,
    DEFAULT_FEMALE_VOICE,
    DEFAULT_MALE_VOICE,
    FIRST_PERSON_KEY,
    NARRATOR,
    OVERRIDES_KEY,
    PALETTE_NARRATOR_VOICE,
    CHARACTER_PALETTE,
    DEFAULT_FEMALE_KEY,
    DEFAULT_MALE_KEY,
    resolve_speaker,
    validate_cast,
)

#: ``cast.yaml`` filename inside each ``.scribe/<book-id>/`` work dir.
CAST_FILENAME = "cast.yaml"
#: ``cast_report.md`` filename beside it (MV6 draft output, read-only here).
CAST_REPORT_FILENAME = "cast_report.md"
#: ``script.json`` filename beside it (MV6 draft output; quote picker source).
SCRIPT_FILENAME = "script.json"
#: Workspace-relative audition-clip cache (flat in UI6; UI7 versions it —
#: see :func:`find_sample_file` and the D-entry).
SAMPLES_DIRNAME = "voice-samples"

#: Shown (by the Cast view, only when ``has_comments``) because a UI save
#: rewrites ``cast.yaml`` with PyYAML and drops ``#`` comments (see module
#: docstring). Values are always preserved; comments are not.
COMMENT_WARNING = (
    "cast.yaml has # comments, and saving from this view rewrites the file "
    "with PyYAML, which drops comments. Your voices, speeds, aliases and "
    "overrides are preserved, but comment lines will be lost. "
    "Keep hand notes in a separate file if they matter."
)

#: Raw speaker surfaces at or below this line count land in the "minor"
#: collapsible (bit parts that resolve to generics; informational only).
MINOR_LINES = 3

#: Quote picker page size default / hard cap (full novels have thousands).
QUOTES_DEFAULT_PER_PAGE = 100
QUOTES_MAX_PER_PAGE = 500

#: Voice dropdown source for UI6: the D-036 palette only (the voices draft
#: ever assigns, plus the legacy Slice 2 narrator). No model is loaded, so
#: this stays fast and offline; :func:`available_voices` extends it to the
#: full engine list whenever a models dir holds the archive.
PALETTE_VOICES: tuple[str, ...] = tuple(
    sorted(
        {
            PALETTE_NARRATOR_VOICE,
            "af_heart",  # legacy Slice 2 narrator default (SW5 casts)
            DEFAULT_FEMALE_VOICE,
            DEFAULT_MALE_VOICE,
            *CHARACTER_PALETTE,
        }
    )
)

#: Audition-clip voice names are strict (same guard style as book/job ids).
_VOICE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]*")


class CastPatchError(ValueError):
    """A patch op (or full replace) failed validation; already shaped."""

    def __init__(self, file: str, rule: str, message: str) -> None:
        super().__init__(f"{file}: {rule}: {message}")
        self.file = file
        self.rule = rule
        self.message = message

    def shaped(self) -> dict[str, str]:
        """``{file, rule, message}`` body for the API layer."""
        return {"file": self.file, "rule": self.rule, "message": self.message}


class CastConflictError(Exception):
    """The file moved under us (mtime mismatch); carries the current state."""

    def __init__(
        self, current_mtime: float, current_cast: dict[str, Any], expected: float
    ) -> None:
        super().__init__(
            f"cast.yaml: conflict: file changed on disk "
            f"(expected mtime {expected!r}, now {current_mtime!r})"
        )
        self.current_mtime = current_mtime
        self.current_cast = current_cast
        self.expected = expected


def split_shaped(text: str, fallback_file: str, fallback_rule: str) -> dict[str, str]:
    """Split ``file: rule: message`` into the error shape (best effort).

    Mirrors the app-layer splitter so pure-module results rejoin verbatim:
    ``f"{file}: {rule}: {message}"`` is the :func:`validate_cast` string
    exactly (e.g. ``cast.yaml: characters.Alice.voice: unknown voice ...``).
    """
    parts = str(text).split(": ", 2)
    if len(parts) == 3:
        return {
            "file": parts[0] or fallback_file,
            "rule": parts[1] or fallback_rule,
            "message": parts[2],
        }
    if len(parts) == 2:
        return {"file": parts[0] or fallback_file, "rule": fallback_rule, "message": parts[1]}
    return {"file": fallback_file, "rule": fallback_rule, "message": str(text)}


def shaped_validation_errors(
    cast: dict[str, Any], *, source: str = "cast.yaml"
) -> list[dict[str, str]]:
    """Validate ``cast``; errors shaped ``{file, rule, message}`` (verbatim)."""
    return [split_shaped(e, source, "cast") for e in validate_cast(cast, source=source)]


def detect_comments(raw: str) -> bool:
    """Heuristic ``#``-comment detector for the save warning (loud, cheap).

    True when any line's first non-space character is ``#``. Trailing
    end-of-line comments are deliberately NOT detected (telling those apart
    from ``#`` inside quoted strings needs a real parser); the banner says
    comments, and full-line comments are the hand-annotation style that
    matters.
    """
    for line in str(raw).splitlines():
        if line.lstrip().startswith("#"):
            return True
    return False


def read_cast_state(work_dir: Path | str) -> tuple[dict[str, Any], float, bool]:
    """Read ``cast.yaml``; return ``(cast, mtime, has_comments)``.

    :raises FileNotFoundError: no ``cast.yaml`` (draft first).
    :raises CastPatchError: unreadable/invalid YAML (rule ``unreadable``).
    """
    path = Path(work_dir) / CAST_FILENAME
    if not path.is_file():
        raise FileNotFoundError(f"{CAST_FILENAME}: cast-not-found: no cast yet (draft first)")
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise CastPatchError(
            CAST_FILENAME, "unreadable", f"{CAST_FILENAME}: unreadable: {exc}"
        ) from exc
    try:
        import yaml

        data = yaml.safe_load(raw)
    except Exception as exc:
        raise CastPatchError(
            CAST_FILENAME, "unreadable", f"{CAST_FILENAME}: unreadable: {exc}"
        ) from exc
    if not isinstance(data, dict):
        raise CastPatchError(
            CAST_FILENAME,
            "unreadable",
            f"{CAST_FILENAME}: cast: must be a mapping (got {type(data).__name__})",
        )
    try:
        mtime = path.stat().st_mtime
    except OSError as exc:
        raise CastPatchError(
            CAST_FILENAME, "unreadable", f"{CAST_FILENAME}: unreadable: {exc}"
        ) from exc
    return data, mtime, detect_comments(raw)


def check_voice_name(voice: str) -> str:
    """Validate an audition voice id (strict shape, traversal-proof)."""
    text = str(voice or "")
    if _VOICE_RE.fullmatch(text) is None:
        raise ValueError(f"{text}: path-traversal: bad voice id")
    return text


def samples_dir(workspace: Path | str) -> Path:
    """``<workspace>/.scribe/voice-samples`` (UI6 flat clip cache)."""
    return Path(workspace) / ".scribe" / SAMPLES_DIRNAME


def find_sample_file(directory: Path | str, voice: str) -> Path | None:
    """Locate a cached audition clip for ``voice`` (None when absent).

    Exact ``<voice>.wav`` wins; otherwise the first ``*-<voice>.wav`` (the
    ``scribe voices --sample`` zero-padded ``NN-<voice>.wav`` shape), so the
    workspace cache can be seeded by pointing ``--out-dir`` at it or by
    copying files in. UI7 keeps this lookup for the legacy flat cache and
    adds a versioned lookup (see :func:`find_versioned_sample`); the ``GET``
    path serves versioned first with this flat shape as the fallback.
    """
    name = check_voice_name(voice)
    folder = Path(directory)
    exact = folder / f"{name}.wav"
    if exact.is_file():
        return exact
    candidates = sorted(folder.glob(f"*-{name}.wav"))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


# --- UI7 versioned audition cache -------------------------------------------
# Keyed by (voice, engine version, sample text) so an engine upgrade
# regenerates exactly once. The server never loads the TTS model: the
# version comes from package metadata, the text hash from the fixed
# audition sentence, and synthesis runs in a detached job.


def sample_text() -> str:
    """Fixed audition sentence (no model load; mirrors ``tts.voices``)."""
    from tts.voices import SAMPLE_TEXT as _TEXT

    return str(_TEXT)


def sample_text_hash(text: str | None = None) -> str:
    """12-hex sha256 of the audition sentence (cache-key segment)."""
    import hashlib

    content = text if text is not None else sample_text()
    return hashlib.sha256(str(content).encode("utf-8")).hexdigest()[:12]


def get_engine_version() -> str:
    """Engine version identity without loading the model (package metadata).

    Matches :attr:`tts.kokoro.KokoroEngine.engine_version`
    (``kokoro-onnx <pkg>``) without importing the engine, onnxruntime, or
    any model file, so the server process stays model-free. Tests inject a
    fake version through the app seam instead of patching this.
    """
    import importlib.metadata

    try:
        pkg = importlib.metadata.version("kokoro-onnx")
    except Exception:
        pkg = "unknown"
    return f"kokoro-onnx {pkg}"


def sanitize_engine_version(version: str) -> str:
    """Filesystem-safe version segment ( traversal-proof, never empty)."""
    text = re.sub(r"[^A-Za-z0-9]+", "-", str(version or "").strip())
    text = text.strip("-")
    return text or "unknown-version"


def versioned_samples_dir(
    workspace: Path | str,
    engine_version: str | None = None,
    text_hash: str | None = None,
) -> Path:
    """``<workspace>/.scribe/voice-samples/<safe-version>/<text-hash>/``."""
    version = sanitize_engine_version(
        engine_version if engine_version is not None else get_engine_version()
    )
    thash = str(text_hash or sample_text_hash())
    if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", thash) is None:
        raise ValueError(f"{thash}: path-traversal: bad sample text hash")
    return samples_dir(workspace) / version / thash


def find_versioned_sample(
    workspace: Path | str,
    voice: str,
    engine_version: str | None = None,
    text_hash: str | None = None,
) -> Path | None:
    """Versioned clip lookup (exact then ``*-<voice>.wav``; None absent)."""
    name = check_voice_name(voice)
    folder = versioned_samples_dir(workspace, engine_version, text_hash)
    exact = folder / f"{name}.wav"
    if exact.is_file():
        return exact
    for candidate in sorted(folder.glob(f"*-{name}.wav")):
        if candidate.is_file():
            return candidate
    return None


def find_cached_clip(
    workspace: Path | str,
    voice: str,
    engine_version: str | None = None,
    text_hash: str | None = None,
) -> tuple[Path | None, bool]:
    """``(path, is_versioned)``: versioned hit wins, else the legacy flat.

    Returns ``(None, False)`` when absent. The flag drives caching headers
    (versioned clips are immutable; legacy flat clips are not).
    """
    hit = find_versioned_sample(workspace, voice, engine_version, text_hash)
    if hit is not None:
        return hit, True
    legacy = find_sample_file(samples_dir(workspace), voice)
    if legacy is not None:
        return legacy, False
    return None, False


#: Kokoro voice-prefix locales (first id letter; heuristic, display only).
_VOICE_LOCALES = {
    "a": "American",
    "b": "British",
    "e": "Spanish",
    "h": "Hindi",
    "i": "Italian",
    "j": "Japanese",
    "p": "Portuguese",
    "z": "Chinese",
}

#: Kokoro voice-prefix genders (second id letter; heuristic, display only).
_VOICE_GENDERS = {"f": "female", "m": "male"}


def describe_voice(voice: str) -> dict[str, str]:
    """``{name, locale, gender}`` from the Kokoro id prefix (display only).

    ``af_bella`` reads American female, ``bm_lewis`` British male,
    ``jf_alpha`` Japanese female, ``zf_xiaoxiao`` Chinese female,
    ``im_nicola`` Italian male. Unknown prefixes stay ``unknown`` rather
    than failing; the audition never depends on these hints.
    """
    name = str(voice or "")
    prefix = name.split("_", 1)[0].lower() if "_" in name else name[:2].lower()
    locale = _VOICE_LOCALES.get(prefix[:1], "unknown") if prefix else "unknown"
    gender = _VOICE_GENDERS.get(prefix[1:2], "unknown") if len(prefix) >= 2 else "unknown"
    return {"name": name, "locale": locale, "gender": gender}


def palette_details() -> list[dict[str, str]]:
    """One :func:`describe_voice` entry per :data:`PALETTE_VOICES` (sorted)."""
    return [describe_voice(voice) for voice in PALETTE_VOICES]


def resolve_models_dir(workspace: Path | str) -> Path:
    """``<workspace>/models`` when present, else the CWD ``models``.

    Same rule the plan/preflight path uses (see the ``models_dir`` lines in
    ``ui/app.py``): the workspace wins so accept workspaces are hermetic,
    the CLI default covers running from ``scribe/``.
    """
    candidate = Path(workspace) / "models"
    return candidate if candidate.is_dir() else Path("models")


def available_voices(workspace: Path | str) -> tuple[list[str], str]:
    """Voice ids for dropdowns and grids plus where they came from.

    The full engine list (archive keys, no model load — see
    :func:`tts.voices.engine_voice_names`) when a models dir holds the
    archive, else the D-036 :data:`PALETTE_VOICES` fallback. Returns
    ``(voices, source)`` with source ``"engine"`` or ``"palette"``.
    """
    names = None
    try:
        from tts.voices import engine_voice_names

        names = engine_voice_names(resolve_models_dir(workspace))
    except Exception:
        names = None
    if names:
        return names, "engine"
    return list(PALETTE_VOICES), "palette"


def available_voice_details(workspace: Path | str) -> tuple[list[dict[str, str]], str]:
    """One :func:`describe_voice` entry per available voice, plus source."""
    voices, source = available_voices(workspace)
    return [describe_voice(voice) for voice in voices], source


# --- patch ops (pure: dict in, new dict out, never mutating the input) -------


def _require_mapping(op: Any, name: str) -> dict[str, Any]:
    if not isinstance(op, dict):
        raise CastPatchError("cast.yaml", "bad-op", f"op must be a mapping (got {op!r})")
    if op.get("op") != name:
        raise CastPatchError(
            "cast.yaml", "bad-op", f"unknown op {op.get('op')!r} (expected {name!r})"
        )
    return op


def _known_speakers(cast: dict[str, Any]) -> set[str]:
    """Names a ``first_person``/override speaker may point at (mirrors validate)."""
    characters = cast.get(CHARACTERS_KEY)
    characters = characters if isinstance(characters, dict) else {}
    aliases = cast.get(ALIASES_KEY)
    aliases = aliases if isinstance(aliases, dict) else {}
    known = {NARRATOR} | {k for k in characters if isinstance(k, str)}
    for surfaces in aliases.values():
        if isinstance(surfaces, list):
            known.update(s for s in surfaces if isinstance(s, str))
    return known


def _character_entry(cast: dict[str, Any], character: str) -> dict[str, Any]:
    """Mutable character/narrator/generic entry, or raise (never invent one)."""
    if character == NARRATOR:
        entry = cast.get(NARRATOR)
    elif character in (DEFAULT_FEMALE_KEY, DEFAULT_MALE_KEY):
        entry = cast.get(character)
    else:
        characters = cast.get(CHARACTERS_KEY)
        entry = characters.get(character) if isinstance(characters, dict) else None
    if not isinstance(entry, dict):
        raise CastPatchError(
            "cast.yaml",
            f"characters.{character}",
            f"unknown character {character!r} "
            "(add it to characters by hand, or promote a minor voice, first)",
        )
    return entry


def _apply_set_voice(cast: dict[str, Any], op: dict[str, Any]) -> None:
    character = op.get("character")
    voice = op.get("voice")
    engine = op.get("engine")
    if not isinstance(character, str) or not character.strip():
        raise CastPatchError(
            "cast.yaml", "bad-op", "set_voice needs {character: non-empty string, voice: ...}"
        )
    if not isinstance(voice, str) or not voice.strip():
        raise CastPatchError(
            "cast.yaml",
            f"characters.{character}.voice",
            f"voice must be a non-empty string (got {voice!r})",
        )
    entry = _character_entry(cast, character)
    entry["voice"] = voice
    if engine is not None:
        from text.cast import SUPPORTED_ENGINES

        if not isinstance(engine, str) or engine.strip() not in SUPPORTED_ENGINES:
            raise CastPatchError(
                "cast.yaml",
                f"characters.{character}.engine",
                f"engine must be one of {list(SUPPORTED_ENGINES)} (got {engine!r})",
            )
        entry["engine"] = engine.strip()


def _apply_set_speed(cast: dict[str, Any], op: dict[str, Any]) -> None:
    import math

    character = op.get("character")
    speed = op.get("speed")
    if not isinstance(character, str) or not character.strip():
        raise CastPatchError(
            "cast.yaml", "bad-op", "set_speed needs {character: non-empty string, speed: ...}"
        )
    if (
        not isinstance(speed, (int, float))
        or isinstance(speed, bool)
        or not math.isfinite(float(speed))
        or float(speed) <= 0
    ):
        raise CastPatchError(
            "cast.yaml",
            f"characters.{character}.speed",
            f"speed must be a number above 0 (got {speed!r})",
        )
    _character_entry(cast, character)["speed"] = float(speed)


def _apply_set_first_person(cast: dict[str, Any], op: dict[str, Any]) -> None:
    value = op.get("value")
    if not isinstance(value, str) or not value.strip():
        raise CastPatchError(
            "cast.yaml", "bad-op", "set_first_person needs {value: character, alias or 'narrator'}"
        )
    if value not in _known_speakers(cast):
        raise CastPatchError(
            "cast.yaml",
            "first_person",
            f"must name 'narrator' or a character/alias (got {value!r})",
        )
    cast[FIRST_PERSON_KEY] = value


def _check_override_ids(chapter: Any, block: Any, quote: Any) -> tuple[int, int, int]:
    """Validate a quote-key triple with the CLI's range rules (verbatim)."""
    for key, value, minimum in (
        ("chapter", chapter, 1),
        ("block", block, 0),
        ("quote", quote, 1),
    ):
        if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
            tail = ">= 0" if key == "block" else ">= 1"
            raise CastPatchError(
                "cast.yaml",
                "bad-op",
                f"add_override: {key} must be an int {tail} (got {value!r})",
            )
    return chapter, block, quote


def _apply_add_override(cast: dict[str, Any], op: dict[str, Any]) -> None:
    speaker = op.get("speaker")
    if not isinstance(speaker, str) or not speaker.strip():
        raise CastPatchError(
            "cast.yaml", "bad-op", "add_override needs {speaker: ...} plus a quote key or match"
        )
    has_key = any(k in op for k in ("chapter", "block", "quote"))
    has_match = "match" in op
    if has_key and has_match:
        raise CastPatchError(
            "cast.yaml",
            "bad-op",
            "add_override combines the quote-key and text-match forms (use one or the other)",
        )
    if has_key:
        chapter, block, quote = _check_override_ids(
            op.get("chapter"), op.get("block"), op.get("quote")
        )
        entry: dict[str, Any] = {
            "chapter": chapter,
            "block": block,
            "quote": quote,
            "speaker": speaker,
        }
    elif has_match:
        import re as _re

        pattern = op.get("match")
        if not isinstance(pattern, str) or not pattern:
            raise CastPatchError(
                "cast.yaml", "bad-op", "add_override: match must be a non-empty regex"
            )
        try:
            _re.compile(pattern)
        except _re.error as exc:
            raise CastPatchError(
                "cast.yaml", "bad-op", f"add_override: match: bad regex ({exc})"
            ) from exc
        entry = {"match": pattern, "speaker": speaker}
    else:
        raise CastPatchError(
            "cast.yaml",
            "bad-op",
            "add_override needs chapter+block+quote+speaker, or match+speaker",
        )
    overrides = cast.get(OVERRIDES_KEY)
    if not isinstance(overrides, list):
        overrides = cast[OVERRIDES_KEY] = []
    overrides.append(entry)


def _apply_remove_override(cast: dict[str, Any], op: dict[str, Any]) -> None:
    overrides = cast.get(OVERRIDES_KEY)
    overrides = overrides if isinstance(overrides, list) else []
    if "index" in op:
        index = op.get("index")
        if not isinstance(index, int) or isinstance(index, bool):
            raise CastPatchError(
                "cast.yaml", "bad-op", f"remove_override: index must be an int (got {index!r})"
            )
        if not 0 <= index < len(overrides):
            raise CastPatchError(
                "cast.yaml",
                "override-not-found",
                f"remove_override: index {index} out of range (0..{len(overrides) - 1})",
            )
        del overrides[index]
        cast[OVERRIDES_KEY] = overrides
        return
    has_key = any(k in op for k in ("chapter", "block", "quote"))
    has_match = "match" in op
    if has_key and ("chapter" not in op or "block" not in op or "quote" not in op):
        raise CastPatchError(
            "cast.yaml",
            "bad-op",
            "remove_override by key needs chapter+block+quote (or index, or match)",
        )
    for pos, entry in enumerate(overrides):
        if not isinstance(entry, dict):
            continue
        if has_key:
            if (
                entry.get("chapter") == op.get("chapter")
                and entry.get("block") == op.get("block")
                and entry.get("quote") == op.get("quote")
            ):
                del overrides[pos]
                cast[OVERRIDES_KEY] = overrides
                return
        elif has_match:
            if entry.get("match") == op.get("match") and "match" in entry:
                del overrides[pos]
                cast[OVERRIDES_KEY] = overrides
                return
    raise CastPatchError(
        "cast.yaml",
        "override-not-found",
        "remove_override: no override matches (nothing removed)",
    )


_OP_HANDLERS = {
    "set_voice": _apply_set_voice,
    "set_speed": _apply_set_speed,
    "set_first_person": _apply_set_first_person,
    "add_override": _apply_add_override,
    "remove_override": _apply_remove_override,
}


def apply_ops(cast: dict[str, Any], ops: list[Any]) -> dict[str, Any]:
    """Apply ``ops`` to a deep copy of ``cast``; return the new cast.

    Pure (the input is never mutated). Structural op errors raise
    :class:`CastPatchError` immediately. Semantic errors (unknown speaker,
    bad voice/speed values) surface when the caller runs
    :func:`shaped_validation_errors` on the result — the wording is then the
    CLI's verbatim, not a paraphrase. Callers that need fail-fast per-op
    semantics validate the intermediate result after each op.
    """
    if not isinstance(ops, list) or not ops:
        raise CastPatchError("cast.yaml", "bad-op", "PATCH needs a non-empty ops list")
    working = copy.deepcopy(cast)
    if not isinstance(working, dict):
        raise CastPatchError("cast.yaml", "bad-op", "stored cast is not a mapping")
    for pos, raw in enumerate(ops):
        if not isinstance(raw, dict) or not isinstance(raw.get("op"), str):
            raise CastPatchError(
                "cast.yaml", "bad-op", f"ops[{pos}]: each op needs an 'op' name (got {raw!r})"
            )
        handler = _OP_HANDLERS.get(raw["op"])
        if handler is None:
            raise CastPatchError(
                "cast.yaml",
                "bad-op",
                f"ops[{pos}]: unknown op {raw['op']!r} "
                f"(expected one of {sorted(_OP_HANDLERS)})",
            )
        _require_mapping(raw, raw["op"])
        handler(working, raw)
    return working


def validate_ops_atomic(
    cast: dict[str, Any], ops: list[Any], *, source: str = "cast.yaml"
) -> tuple[dict[str, Any], list[dict[str, str]]]:
    """Apply ``ops`` atomically: all ops validate before anything is returned.

    Returns ``(new_cast, [])`` when every op applies AND the merged result
    passes :func:`validate_cast`; otherwise ``(old_cast, errors)`` with the
    CLI-verbatim shaped errors and no partial application (the caller writes
    nothing). Structural errors become a single ``bad-op`` shaped error.
    """
    try:
        working = copy.deepcopy(cast)
        staged = apply_ops(working, ops)
    except CastPatchError as exc:
        return cast, [exc.shaped()]
    errors = shaped_validation_errors(staged, source=source)
    if errors:
        return cast, errors
    return staged, []


def write_cast_guarded(
    work_dir: Path | str,
    new_cast: dict[str, Any],
    *,
    expected_mtime: float | None,
    source: str = "cast.yaml",
) -> tuple[float, bool]:
    """Validate, mtime-guard and write ``cast.yaml``; return ``(mtime, had_comments)``.

    The guard is read-modify-write: the file is re-read, its mtime compared
    to ``expected_mtime`` (``None`` means "must be absent-or-unchanged" is
    NOT allowed — callers must pass the mtime from ``GET``; ``None`` raises
    ``bad-op``), then the validated cast is dumped. A mismatch raises
    :class:`CastConflictError` carrying the current on-disk state and writes
    nothing. Returns the new mtime plus whether the *overwritten* file had
    ``#`` comments (so the response can say so loudly).
    """
    if not isinstance(expected_mtime, (int, float)) or isinstance(expected_mtime, bool):
        raise CastPatchError(
            "cast.yaml",
            "bad-op",
            "PUT/PATCH needs expected_mtime from GET /api/books/{id}/cast (conflict guard)",
        )
    path = Path(work_dir) / CAST_FILENAME
    if not path.is_file():
        raise CastPatchError(
            "cast.yaml", "cast-not-found", "cast.yaml: cast-not-found: no cast yet (draft first)"
        )
    errors = shaped_validation_errors(new_cast, source=source)
    if errors:
        first = errors[0]
        raise CastPatchError(first["file"], first["rule"], first["message"])
    try:
        current_raw = path.read_text(encoding="utf-8")
        current_mtime = path.stat().st_mtime
    except OSError as exc:
        raise CastPatchError(
            "cast.yaml", "unreadable", f"cast.yaml: unreadable: {exc}"
        ) from exc
    if abs(current_mtime - float(expected_mtime)) > 1e-6:
        import yaml

        try:
            current_cast = yaml.safe_load(current_raw)
        except Exception:
            current_cast = None
        if not isinstance(current_cast, dict):
            current_cast = {}
        raise CastConflictError(current_mtime, current_cast, float(expected_mtime))
    had_comments = detect_comments(current_raw)
    import yaml

    path.write_text(
        yaml.safe_dump(new_cast, sort_keys=True, allow_unicode=True), encoding="utf-8"
    )
    try:
        return path.stat().st_mtime, had_comments
    except OSError as exc:
        raise CastPatchError(
            "cast.yaml", "unreadable", f"cast.yaml: unreadable: {exc}"
        ) from exc


# --- script.json readers: quotes, stats --------------------------------------


def read_script_quotes(script: dict[str, Any]) -> list[dict[str, Any]]:
    """Group dialogue sentences into quotes keyed ``(chapter, block, quote)``.

    Reading order is preserved (chapter, then block, then quote number).
    Each entry: ``{chapter, block, quote, text, speaker, confidence, rule}``
    where ``speaker`` is the RAW draft surface (what an override retargets),
    ``text`` is the display-joined quote (whitespace-collapsed; never used
    as a match key), and ``rule`` is the attribution rule when known (the
    draft pipeline does not persist it today, so usually ``""``).
    """
    grouped: dict[tuple[int, int, int], dict[str, Any]] = {}
    order: list[tuple[int, int, int]] = []
    chapters = script.get("chapters")
    if not isinstance(chapters, list):
        return []
    for chapter in chapters:
        if not isinstance(chapter, dict):
            continue
        for block in chapter.get("blocks") or []:
            if not isinstance(block, dict):
                continue
            for sentence in block.get("sentences") or []:
                if not isinstance(sentence, dict):
                    continue
                if sentence.get("kind") != "dialogue":
                    continue
                key_raw = sentence.get("quote")
                if not isinstance(key_raw, dict):
                    continue
                try:
                    key = (
                        int(key_raw["chapter"]),
                        int(key_raw["block"]),
                        int(key_raw["quote"]),
                    )
                except (KeyError, TypeError, ValueError):
                    continue
                entry = grouped.get(key)
                if entry is None:
                    entry = {
                        "chapter": key[0],
                        "block": key[1],
                        "quote": key[2],
                        "texts": [],
                        "speaker": str(sentence.get("speaker") or "unknown"),
                        "confidence": str(sentence.get("confidence") or "unknown"),
                        "rule": "",
                    }
                    grouped[key] = entry
                    order.append(key)
                text = sentence.get("text")
                if isinstance(text, str) and text.strip():
                    entry["texts"].append(text.strip())
    order.sort()
    out: list[dict[str, Any]] = []
    for key in order:
        entry = grouped[key]
        full = " ".join(" ".join(entry["texts"]).split())
        out.append(
            {
                "chapter": entry["chapter"],
                "block": entry["block"],
                "quote": entry["quote"],
                "text": full,
                "speaker": entry["speaker"],
                "confidence": entry["confidence"],
                "rule": entry["rule"],
            }
        )
    return out


def resolve_quote_speakers(
    cast: dict[str, Any], quotes: list[dict[str, Any]]
) -> dict[tuple[int, int, int], str]:
    """Map each quote key to its resolved character (never raises).

    Gender hints are not persisted in ``script.json``, so resolution runs
    with ``unknown`` gender — the same fallback the build uses for hintless
    lines. Malformed casts resolve leniently (see :mod:`text.cast`).
    """
    resolved: dict[tuple[int, int, int], str] = {}
    for entry in quotes:
        try:
            hit = resolve_speaker(
                cast,
                raw_speaker=entry.get("speaker", "unknown"),
                chapter=entry.get("chapter"),
                block=entry.get("block"),
                quote=entry.get("quote"),
                text=entry.get("text", ""),
            )
            resolved[(entry["chapter"], entry["block"], entry["quote"])] = hit.character
        except Exception:
            resolved[(entry["chapter"], entry["block"], entry["quote"])] = (
                DEFAULT_FEMALE_KEY
            )
    return resolved


def _voice_speed_for(cast: dict[str, Any], character: str) -> tuple[str, float]:
    if character == NARRATOR:
        entry = cast.get(NARRATOR)
        fallback = PALETTE_NARRATOR_VOICE
    elif character == DEFAULT_FEMALE_KEY:
        entry = cast.get(DEFAULT_FEMALE_KEY)
        fallback = DEFAULT_FEMALE_VOICE
    elif character == DEFAULT_MALE_KEY:
        entry = cast.get(DEFAULT_MALE_KEY)
        fallback = DEFAULT_MALE_VOICE
    else:
        characters = cast.get(CHARACTERS_KEY)
        entry = characters.get(character) if isinstance(characters, dict) else None
        fallback = DEFAULT_FEMALE_VOICE
    voice = fallback
    speed = 1.0
    if isinstance(entry, dict):
        raw_voice = entry.get("voice")
        if isinstance(raw_voice, str) and raw_voice.strip():
            voice = raw_voice
        raw_speed = entry.get("speed", 1.0)
        if isinstance(raw_speed, (int, float)) and not isinstance(raw_speed, bool):
            speed = float(raw_speed)
    return voice, speed


def build_cast_stats(
    cast: dict[str, Any],
    quotes: list[dict[str, Any]],
    narration_lines: int,
    speaker_ms: dict[str, int] | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Per-character table rows plus the minor-voices collapsible.

    Rows cover the narrator, every ``characters`` entry and both generics
    (voice/speed from the cast, lines from quote resolution over the draft,
    minutes from bundle timings when given, low-confidence counts from the
    draft). The minor list holds raw surfaces at or below :const:`MINOR_LINES`
    lines (bit parts resolving to generics; informational — promoting one
    means adding a ``characters`` entry by hand).
    """
    speaker_ms = speaker_ms or {}
    resolved = resolve_quote_speakers(cast, quotes)
    lines: dict[str, int] = {}
    low: dict[str, int] = {}
    for entry in quotes:
        key = (entry["chapter"], entry["block"], entry["quote"])
        character = resolved.get(key, DEFAULT_FEMALE_KEY)
        lines[character] = lines.get(character, 0) + 1
        if entry.get("confidence") == "low":
            low[character] = low.get(character, 0) + 1

    characters = cast.get(CHARACTERS_KEY)
    characters = characters if isinstance(characters, dict) else {}
    ordered = (
        [NARRATOR]
        + sorted(k for k in characters if isinstance(k, str))
        + [DEFAULT_FEMALE_KEY, DEFAULT_MALE_KEY]
    )
    rows: list[dict[str, Any]] = []
    for character in ordered:
        voice, speed = _voice_speed_for(cast, character)
        count = narration_lines if character == NARRATOR else lines.get(character, 0)
        ms = int(speaker_ms.get(character, 0))
        rows.append(
            {
                "character": character,
                "kind": (
                    "narrator"
                    if character == NARRATOR
                    else (
                        "generic"
                        if character in (DEFAULT_FEMALE_KEY, DEFAULT_MALE_KEY)
                        else "character"
                    )
                ),
                "voice": voice,
                "speed": speed,
                "lines": count,
                "minutes": round(ms / 60000.0, 1),
                "low": 0 if character == NARRATOR else low.get(character, 0),
            }
        )

    by_surface: dict[str, int] = {}
    for entry in quotes:
        surface = entry.get("speaker", "unknown")
        by_surface[surface] = by_surface.get(surface, 0) + 1
    minor: list[dict[str, Any]] = []
    for surface in sorted(by_surface, key=lambda s: (by_surface[s], s.lower())):
        count = by_surface[surface]
        if count > MINOR_LINES:
            continue
        first = next(e for e in quotes if e.get("speaker") == surface)
        key = (first["chapter"], first["block"], first["quote"])
        minor.append(
            {
                "speaker": surface,
                "lines": count,
                "resolved": resolved.get(key, DEFAULT_FEMALE_KEY),
                "example": {
                    "chapter": first["chapter"],
                    "block": first["block"],
                    "quote": first["quote"],
                },
            }
        )
    return rows, minor


def count_changed_quotes(
    old_cast: dict[str, Any], new_cast: dict[str, Any], quotes: list[dict[str, Any]]
) -> int:
    """How many quote keys resolve differently under ``new_cast`` (preflight).

    Shown after a save as "re-voices N line(s)": the actual re-render happens
    in the Render step, where the MV7 sentence cache reuses every untouched
    line, so this count is exactly the delta the next build will synth.
    """
    before = resolve_quote_speakers(old_cast, quotes)
    after = resolve_quote_speakers(new_cast, quotes)
    return sum(1 for key in before if before.get(key) != after.get(key))
