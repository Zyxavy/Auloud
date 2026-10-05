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

"""``scribe build`` pipeline (SW9, MV6 staleness): work folder -> chapters -> bundle.

Chapter by chapter: script sentences -> SW6 cache/engine
(:func:`tts.cache.get_or_synth`) -> SW7 assembly
(:func:`audio.assemble.assemble_chapter`, timings come from that same
continuous buffer — never reimplemented here) -> SW7 encode
(:func:`audio.encode.encode_assembled_chapter`) -> SW8 writer
(:func:`bundle.writer.write_bundle`, which runs the SW2 validator gate).

Draft freshness (MV6, decision 1): :func:`ensure_script` runs ``draft``
first when the work folder lacks a fresh script. Fresh means
``script.json`` exists AND its ``source_sha256`` matches the source file's
current sha256 AND its ``attribution_rules_version`` matches
:data:`text.attribution.ATTRIBUTION_RULES_VERSION`; anything else
(missing file, unreadable JSON, shape error, sha mismatch, version
mismatch or missing version on legacy scripts) re-drafts. ``cast.yaml``
edits NEVER trigger a re-draft (editing cast needs only build).

Multi-voice rendering (MV7, decisions 4-5): every sentence resolves to
``(character, voice, speed)`` via :func:`resolve_sentence_voice`
(narration sentences take the narrator entry; dialogue sentences go
through :func:`text.cast.resolve_speaker` with their chapter/block/quote
key and text). The gender hint is re-derived at build with
:func:`text.speakers.gender_hint_for` from the raw speaker plus the
sentence text: it only steers the generic fallback, so the lightweight
hint is enough — threading the draft's full-context hint through
``script.json`` would reshape the work file and force every book to
re-draft for no audible difference, and running the spaCy tagger at build
would drag the model into packaging (the MV4 lesson). Accepted v1 risk:
build sees only the single sentence text while draft votes from paragraph
context (prev plus block plus next), so the two hints can disagree on
edge cases and pick different generics; the resolved voice still lands in
the fingerprint, so any flip re-renders that chapter. ``"unknown"`` and
any other raw surface resolve to a generic, never crash. Speed offsets
only, no pitch shifting (decision 4). Per-voice leveling happens in
:func:`audio.assemble.assemble_chapter` (decision 5), never in the cache.

Resume: each chapter's encoded MP3 plus its timed chapter JSON live under
``<work-dir>/<book-id>/render/`` (``audio/chNNN.mp3``,
``text/chNNN.json``). A chapter is skipped when :func:`chapter_up_to_date`
finds a valid MP3 plus matching timings already on disk: same sentence
``(sid, speaker, text, spans)`` sequence as the script
(see :func:`_sentence_key`), same chapter title
and block structure (break blocks carry pauses but no sentences), same
cast voices/speeds, pitch and engine version, and the same assembly
fingerprint (:func:`_assembly_fingerprint` — pause/loudness/encode
constant values read live from their modules plus manual
:data:`BUFFER_VERSION` for construction-code changes), plus an MP3 whose
ffprobe duration agrees within 50 ms and whose frames are intact CBR. Skipped chapters are never
re-synthesized, re-assembled, or re-encoded — finished files are untouched,
and sentence-audio cache hits make even re-rendered chapters cheap.
Render files are only written after a successful encode, so a kill
mid-chapter can only leave a torn MP3 (duration drift or broken CBR frames,
re-rendered) or a missing/corrupt JSON (re-rendered), never a silently
stale chapter.

Strictness: ``--strict`` aborts the whole build on the first chapter error
(no bundle is written). Non-strict renders every remaining chapter, then
raises :class:`BuildError` listing all failed chapters (still no bundle:
the SW8 writer requires contiguous 1..N chapters, so a bundle with gaps
cannot be represented — the failure is recorded in ``scribe.log`` and in
the exception message instead).

Orphan cleanup: reruns never leave orphaned chapters.
:func:`remove_stale_chapter_files` deletes ``chNNN`` audio/text files whose
index exceeds the current chapter count, in both the render dir and the
output bundle dir (reusing one output dir for a shorter book is the usual
trigger; a per-book render dir can only go stale if extraction rules
change under the same source bytes). Render cleanup runs before the
render loop, so it holds on abort paths too; bundle cleanup runs after
the writer succeeds.

Identical-bundle definition: killing a build and rerunning it produces a
bundle that is byte-identical to an uninterrupted build EXCEPT
``manifest.json``'s ``created_at`` (UTC now, seconds precision — different
by design, see the SW8 writer docs). :func:`compare_bundles` implements
exactly this comparison and the kill-simulation test uses it.

Progress and totals: a ``rich`` progress bar with ETA tracks chapter
rendering (disable with ``show_progress=False``); every terminal run
appends to ``<work-dir>/<book-id>/scribe.log``, and
:func:`format_summary` renders the total-audio / wall-time / real-time
factor lines the CLI prints. Real kills (SIGKILL) write no log entry;
the ``fail_after`` test hook below raises cleanly *and* logs, so it is a
louder stand-in, not a silent one.

No network access; ffmpeg/ffprobe run as subprocesses only.
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from audio.assemble import AssembledChapter, apply_loudness_gain, assemble_chapter
from audio.encode import encode_assembled_chapter
import audio.assemble as assemble_mod
import audio.encode as encode_mod
from bundle.models import Block, ChapterFile, Sentence, Voice
from bundle.validate import AudioProbeError, DURATION_TOLERANCE_MS, probe_audio_ffprobe
from bundle.writer import VOICE_PITCH, write_bundle
from draft import (
    CAST_FILENAME,
    SCRIPT_FILENAME,
    book_id_for_file,
    book_id_for_range,
    format_duration,
    format_range_compact,
    run_draft,
)
from text.cast import (
    NARRATOR,
    NARRATOR_ENGINE,
    ResolvedVoice,
    read_cast,
    resolve_speaker,
    validate_cast,
)
from text.speakers import gender_hint_for
from tts.base import TTSEngine
from tts.cache import CacheStats, get_or_synth

#: Work-folder layout under ``<work_root>/<book-id>/``.
CACHE_DIRNAME = "cache"
RENDER_DIRNAME = "render"
LOG_FILENAME = "scribe.log"

#: Extra top-level key in render text JSON holding the render fingerprint
#: (per-chapter: title, block structure, cast voices/speeds, pitch,
#: engine version, assembly version). Never reaches the bundle:
#: render JSON is re-parsed with :meth:`ChapterFile.from_dict` (which
#: ignores unknown keys) and the bundle is written from those objects.
RENDER_FINGERPRINT_KEY = "render"

#: ``chNNN`` file stems, with numeric index (any width, e.g. ch001/ch1000).
_CHAPTER_STEM_RE = re.compile(r"^ch(\d+)$")

#: Manual version for chapter-buffer/timing construction baked into the
#: render fingerprint. Bump this integer ONLY when you change how the PCM
#: buffer or sentence timings are built in a way no fingerprinted constant
#: captures — e.g. the concatenation/silence-skipping order in
#: ``audio.assemble.assemble_chapter``, the timing math
#: (``ms_for_samples``/``samples_for_ms`` usage), the loudness-gain
#: application order in ``run_build``, or ``_with_timings`` here.
#: Do NOT bump for pause lengths, loudness target, or encode settings
#: (those are fingerprinted automatically from their constants — see
#: :func:`_assembly_fingerprint`), nor for cast/engine/script edits (those
#: are separate fingerprint fields). A bump re-renders every chapter once.
#: Version 2: MV7 review fix carries ``kind``/``split_pair`` in the
#: fingerprint voices (see :func:`_fingerprint`), so pre-fix renders
#: (which ignored the tag link) invalidate exactly once.
BUFFER_VERSION = 2


def _assembly_fingerprint() -> dict[str, Any]:
    """Assembly fingerprint from the ACTUAL constant values (no hand bump).

    Reads pause lengths (``PAUSE_*_MS``), the loudness target
    (``PEAK_TARGET_DBFS``/``PEAK_TARGET``) via ``audio.assemble``, and the
    encode settings (``AUDIO_CODEC``/``BITRATE_KBPS``) via ``audio.encode``
    — imported live from those modules (never duplicated here), so any
    constant edit invalidates renders automatically. ``BUFFER_VERSION``
    covers only construction-code changes constants cannot capture.
    """
    return {
        "pauses_ms": {
            "sentence": assemble_mod.PAUSE_SENTENCE_MS,
            "para": assemble_mod.PAUSE_PARA_MS,
            "heading": assemble_mod.PAUSE_HEADING_MS,
            "break": assemble_mod.PAUSE_BREAK_MS,
            "tag": assemble_mod.PAUSE_TAG_MS,
        },
        "peak_target_dbfs": assemble_mod.PEAK_TARGET_DBFS,
        "peak_target": assemble_mod.PEAK_TARGET,
        "encode": {
            "codec": encode_mod.AUDIO_CODEC,
            "bitrate_kbps": encode_mod.BITRATE_KBPS,
        },
        "buffer": BUFFER_VERSION,
    }


class BuildError(ValueError):
    """``scribe build`` cannot proceed or a chapter failed (see message)."""


class BuildStoppedError(BuildError):
    """Cooperative stop at a sentence boundary (pause/cancel, UI1).

    Subclasses :class:`BuildError` so existing ``except BuildError`` paths
    stay intact; callers needing the distinction catch this first. The
    partial chapter's render files are removed before raising, so resume
    re-renders it fully (atomicity) while sentence-cache hits keep the
    resume cheap (only uncached sentences re-synthesize, the MV7 path).
    """


@dataclass
class ScriptInfo:
    """Outcome of :func:`ensure_script` (fresh script or a just-run draft)."""

    chapters: list[ChapterFile] = field(default_factory=list)
    work_dir: Path = Path(".")
    book_id: str = ""
    sha256: str = ""
    title: str = ""
    redrafted: bool = False


@dataclass
class BuildResult:
    """Outcome of :func:`run_build` (the bundle passed the writer's gate)."""

    book_id: str
    title: str
    work_dir: Path
    bundle_dir: Path
    log_path: Path
    chapters_total: int
    rendered: int
    skipped: int
    failed: list[int] = field(default_factory=list)
    total_sentences: int = 0
    total_audio_ms: int = 0
    wall_seconds: float = 0.0
    rtf: float = 0.0
    strict: bool = False
    cache_hits: int = 0
    cache_misses: int = 0
    # UI1 range fields (full builds: selected == all source chapters).
    source_chapters_total: int = 0
    selected_chapters: list[int] = field(default_factory=list)
    page_resolution: str = ""
    is_range: bool = False


@dataclass
class PlanResult:
    """Outcome of :func:`plan_build` (dry run, no audio rendered).

    ``cached_sentences`` counts sentences in up-to-date chapters (skip);
    ``to_render_sentences`` counts sentences in chapters needing render.
    ``estimated_seconds`` uses recent RTF from ``build_stats.json`` (last
    20 chapter timings in the work dir); ``None`` when no history exists.
    """

    book_id: str
    title: str
    work_dir: Path
    source_chapters_total: int = 0
    selected_chapters: list[int] = field(default_factory=list)
    cached_chapters: int = 0
    to_render_chapters: int = 0
    cached_sentences: int = 0
    to_render_sentences: int = 0
    total_audio_ms_cached: int = 0
    estimated_seconds: float | None = None
    rtf_used: float | None = None
    page_resolution: str = ""
    is_range: bool = False


def create_engine(
    models_dir: Path | str = Path("models"), *, device: str = "auto"
) -> TTSEngine:
    """Build the Slice 2 TTS engine (Kokoro, D-023) from ``models_dir``.

    :param device: UI1 ``--device`` (``auto``/``cpu``/``cuda``; default
        ``auto`` = CUDA provider only when ``onnxruntime`` reports it,
        else CPU). ``cpu`` forces CPU (the old path, byte-identical);
        ``cuda`` forces CUDA or fails cleanly. No packaging change.
    :raises BuildError: runtime/models missing or unreadable (clean message,
        no traceback from deep inside the engine stack).
    :raises BuildError: bad ``device`` or unavailable CUDA (``device:
        bad-device`` / ``device: cuda-unavailable`` rule names).
    """
    from tts.kokoro import resolve_model_paths

    normalized = str(device or "auto").strip().lower()
    if normalized not in ("auto", "cpu", "cuda"):
        raise BuildError(
            f"device: bad-device: {device!r} must be one of ['auto', 'cpu', 'cuda']"
        )
    if normalized in ("auto", "cpu"):
        # CPU path stays exactly the old constructor when CUDA is absent
        # (byte-identical behavior for the common case). ``auto`` with a
        # CUDA-capable onnxruntime goes through the device module.
        if normalized == "cpu":
            try:
                from tts.kokoro import KokoroEngine

                model_path, voices_path = resolve_model_paths(models_dir)
                return KokoroEngine(model_path, voices_path)
            except (ImportError, FileNotFoundError, ValueError, OSError) as exc:
                raise BuildError(f"cannot init TTS engine: {exc}") from exc
    from device import create_engine_with_device

    try:
        return create_engine_with_device(models_dir, device=normalized)
    except (ImportError, FileNotFoundError, ValueError, OSError) as exc:
        raise BuildError(f"cannot init TTS engine: {exc}") from exc


def _read_json(path: Path) -> Any:
    """Parse ``path`` as UTF-8 JSON (any decode/shape error propagates)."""
    return json.loads(path.read_text(encoding="utf-8"))


#: Recent-RTF window for ``plan_build`` estimates (last N chapter timings).
PLAN_RTF_WINDOW = 20
#: Persisted chapter timings for RTF estimates (next to the work dir).
BUILD_STATS_FILENAME = "build_stats.json"


def _chapter_page_ranges(chapters: list[ChapterFile]) -> dict[int, tuple[int, int]]:
    """``{source_chapter: (min_page, max_page)}`` from sentence provenance.

    Chapters with no paged sentences are absent (EPUB books resolve no
    pages; ``--pages`` on them is a clean error naming the rule).
    """
    ranges: dict[int, tuple[int, int]] = {}
    for chapter in chapters:
        pages = [
            s.page
            for s in chapter.sentences_in_order()
            if isinstance(s.page, int) and not isinstance(s.page, bool)
        ]
        if pages:
            ranges[chapter.chapter] = (min(pages), max(pages))
    return ranges


def _resolve_selection(
    chapters: list[ChapterFile],
    *,
    chapters_spec: str | None,
    pages_spec: str | None,
) -> tuple[list[int], str]:
    """Source chapter numbers to render plus a human resolution string.

    ``draft`` ALWAYS processes the whole book; this only selects what
    ``build`` renders. ``--chapters`` parses directly; ``--pages`` resolves
    via sentence ``page`` provenance to covering chapters (whole chapters
    render). Both together raise :class:`BuildError` naming
    ``chapters-pages-exclusive``. Empty resolution (pages with no paged
    chapters, e.g. EPUB) raises naming ``pages-need-pdf``.
    """
    from selection import chapters_for_pages, parse_range_spec

    total = len(chapters)
    if chapters_spec and pages_spec:
        raise BuildError(
            "build: chapters-pages-exclusive: --chapters and --pages "
            "cannot be used together (pick one range form)"
        )
    if pages_spec:
        ranges = _chapter_page_ranges(chapters)
        if not ranges:
            raise BuildError(
                "build: pages-need-pdf: --pages needs PDF page provenance "
                "(this book has no sentence pages; EPUB books use --chapters)"
            )
        max_page = max(last for _, last in ranges.values())
        wanted_pages = parse_range_spec(pages_spec, total=max_page, kind="pages")
        covering = chapters_for_pages(ranges, wanted_pages)
        if not covering:
            raise BuildError(
                f"build: pages-no-cover: pages {pages_spec!r} cover no chapters "
                f"(book pages 1..{max_page})"
            )
        compact_pages = pages_spec.strip()
        resolution = (
            f"pages {compact_pages} map to chapter(s) "
            f"{','.join(str(c) for c in covering)} "
            f"(whole covering chapters render)"
        )
        return covering, resolution
    if chapters_spec:
        selected = parse_range_spec(chapters_spec, total=total, kind="chapters")
        return selected, ""
    return list(range(1, total + 1)), ""


def _load_build_stats(work_dir: Path) -> list[dict[str, Any]]:
    """Recent chapter timings from ``build_stats.json`` (empty when absent)."""
    path = Path(work_dir) / BUILD_STATS_FILENAME
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    if not isinstance(data, list):
        return []
    return [e for e in data if isinstance(e, dict)]


def _record_build_stats(
    work_dir: Path, entries: list[dict[str, Any]], *, keep: int = 100
) -> None:
    """Append chapter timings to ``build_stats.json`` (best-effort, no raise).

    Choice documented for UI1: a small JSON next to the work dir (not cache
    metadata: cache files know hits but not wall time or audio length, and
    a sidecar stays readable for the UI preflight without scanning
    thousands of FLACs). Keeps the last ``keep`` entries.
    """
    if not entries:
        return
    path = Path(work_dir) / BUILD_STATS_FILENAME
    try:
        existing = _load_build_stats(path.parent)
        merged = (existing + entries)[-keep:]
        path.write_text(json.dumps(merged, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    except OSError:
        pass


def _recent_rtf(work_dir: Path, *, window: int = PLAN_RTF_WINDOW) -> float | None:
    """Mean RTF over the last ``window`` chapter timings, or ``None``."""
    stats = _load_build_stats(work_dir)[-window:]
    ratios: list[float] = []
    for entry in stats:
        try:
            audio_ms = float(entry.get("audio_ms", 0))
            wall_s = float(entry.get("wall_s", 0))
        except (TypeError, ValueError):
            continue
        if audio_ms > 0 and wall_s > 0:
            ratios.append((audio_ms / 1000.0) / wall_s)
    if not ratios:
        return None
    return sum(ratios) / len(ratios)


def ensure_script(source: Path | str, *, work_root: Path | str = Path(".scribe")) -> ScriptInfo:
    """Return a fresh script for ``source``, drafting first when stale.

    Fresh means ``<work_root>/<book-id>/script.json`` exists AND its
    ``source_sha256`` matches the source file's current sha256 AND its
    ``attribution_rules_version`` matches
    :data:`text.attribution.ATTRIBUTION_RULES_VERSION`. Anything else
    (missing file, bad JSON, shape error, sha mismatch, version mismatch
    or missing version — e.g. the source changed since the draft, or MV2-MV4
    logic changed) runs :func:`draft.run_draft` again. ``cast.yaml`` is
    never consulted here: editing it needs only build, never a re-draft.

    :raises BuildError: the source file is missing/unreadable.
    :raises DraftError: drafting a stale/missing script failed.
    """
    from text.attribution import ATTRIBUTION_RULES_VERSION

    src = Path(source)
    if not src.is_file():
        kind = "PDF" if src.suffix.lower() == ".pdf" else "EPUB"
        raise BuildError(f"{kind} not found: {src}")
    try:
        book_id, sha = book_id_for_file(src)
    except OSError as exc:
        raise BuildError(f"{src.name}: cannot read source file: {exc}") from exc

    work_dir = Path(work_root) / book_id
    script_path = work_dir / SCRIPT_FILENAME
    if script_path.is_file():
        try:
            data = _read_json(script_path)
            if (
                isinstance(data, dict)
                and data.get("source_sha256") == sha
                and data.get("attribution_rules_version") == ATTRIBUTION_RULES_VERSION
            ):
                chapters = [ChapterFile.from_dict(c) for c in data["chapters"]]
                if chapters:
                    return ScriptInfo(
                        chapters=chapters,
                        work_dir=work_dir,
                        book_id=book_id,
                        sha256=sha,
                        title=str(data.get("title") or src.stem),
                        redrafted=False,
                    )
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            pass  # Anything off about the old script -> re-draft below.

    result = run_draft(src, work_root=work_root)
    return ScriptInfo(
        chapters=list(result.chapters),
        work_dir=result.work_dir,
        book_id=result.book_id,
        sha256=result.sha256,
        title=result.title,
        redrafted=True,
    )


def _narrator_voice(cast_path: Path) -> tuple[str, float]:
    """``(voice, speed)`` for the narrator entry of ``cast.yaml``."""
    if not cast_path.is_file():
        raise BuildError(f"cast file missing: {cast_path} (re-run `scribe draft`)")
    try:
        cast = read_cast(cast_path)
    except (OSError, ValueError) as exc:
        raise BuildError(f"{cast_path.name}: cannot read cast: {exc}") from exc
    entry = cast.get(NARRATOR)
    if not isinstance(entry, dict):
        raise BuildError(f"{cast_path.name}: no '{NARRATOR}' entry (needs engine, voice, speed)")
    voice = entry.get("voice")
    if not isinstance(voice, str) or not voice.strip():
        raise BuildError(f"{cast_path.name}: narrator entry needs a 'voice' id")
    try:
        speed = float(entry.get("speed", 1.0))
    except (TypeError, ValueError) as exc:
        raise BuildError(f"{cast_path.name}: narrator 'speed' is not a number") from exc
    return voice, speed


def resolve_sentence_voice(
    cast: dict[str, Any],
    sentence: Sentence,
    *,
    chapter_index: int,
    narrator_voice: str,
    narrator_speed: float,
) -> ResolvedVoice:
    """Resolve one script sentence to its final ``(character, voice, speed)``.

    Narration (any ``kind`` other than ``dialogue``, which covers legacy
    pre-MV6 scripts whose sentences all default to narration) takes the
    narrator entry. Dialogue goes through :func:`text.cast.resolve_speaker`
    with the sentence's quote key (``(chapter, block, quote)`` when the
    script carries one, else the current chapter with no block/quote), its
    text (for text-match overrides), and a gender hint re-derived here
    with :func:`text.speakers.gender_hint_for` (see the module docstring
    for why the hint is not threaded through ``script.json``). Total, like
    resolution itself: ``"unknown"`` and any other surface fall through to
    a generic, never raise.
    """
    if sentence.kind != "dialogue":
        return ResolvedVoice(character=NARRATOR, voice=narrator_voice, speed=narrator_speed)
    quote = sentence.quote if isinstance(sentence.quote, dict) else {}
    chapter = quote.get("chapter", chapter_index)
    block = quote.get("block")
    number = quote.get("quote")
    if not isinstance(chapter, int) or isinstance(chapter, bool):
        chapter = chapter_index
    if not isinstance(block, int) or isinstance(block, bool):
        block = None
    if not isinstance(number, int) or isinstance(number, bool):
        number = None
    raw = sentence.speaker if isinstance(sentence.speaker, str) else ""
    return resolve_speaker(
        cast,
        raw_speaker=raw,
        gender=gender_hint_for(raw, sentence.text),
        chapter=chapter,
        block=block,
        quote=number,
        text=sentence.text,
    )


def resolve_chapter(
    cast: dict[str, Any],
    chapter: ChapterFile,
    *,
    narrator_voice: str,
    narrator_speed: float,
) -> dict[int, ResolvedVoice]:
    """``sid -> ResolvedVoice`` for a chapter, in document order.

    The single source of truth for synthesis (which voice/speed to render),
    assembly leveling (which voice group a sentence belongs to), and the
    render fingerprint (which sentences a cast edit affects), so the three
    can never disagree.
    """
    plan: dict[int, ResolvedVoice] = {}
    for sentence in chapter.sentences_in_order():
        plan[sentence.sid] = resolve_sentence_voice(
            cast,
            sentence,
            chapter_index=chapter.chapter,
            narrator_voice=narrator_voice,
            narrator_speed=narrator_speed,
        )
    return plan


def _cast_engine(cast: dict[str, Any]) -> str:
    """Engine id for the manifest voices map (narrator entry, else Kokoro)."""
    narrator = cast.get(NARRATOR) if isinstance(cast, dict) else None
    if isinstance(narrator, dict):
        engine = narrator.get("engine")
        if isinstance(engine, str) and engine.strip():
            return engine
    return NARRATOR_ENGINE


def build_voices_map(
    cast: dict[str, Any],
    plans: list[dict[int, ResolvedVoice]],
    *,
    pitch: float = VOICE_PITCH,
) -> dict[str, Voice]:
    """Manifest ``voices`` from the RESOLVED cast (MV8).

    Keys are the resolved character keys (``narrator``, characters,
    collapsed ``default_female``/``default_male`` generics — never raw
    surfaces), one entry per character actually used across ``plans``
    (plus ``narrator`` always, so narration-only books keep the legacy
    single entry). ``(engine, voice, speed)`` come from the resolution
    itself (engine is the cast narrator engine for every entry; voice and
    speed are the resolved values), ``pitch`` is the writer constant.
    Narrator first, then sorted others, for deterministic manifests.
    """
    engine = _cast_engine(cast if isinstance(cast, dict) else {})
    by_character: dict[str, ResolvedVoice] = {}
    for plan in plans:
        for resolved in plan.values():
            by_character.setdefault(resolved.character, resolved)
    if NARRATOR not in by_character:
        voice, speed = _narrator_voice_fallback(cast)
        by_character[NARRATOR] = ResolvedVoice(character=NARRATOR, voice=voice, speed=speed)
    ordered = [NARRATOR, *[k for k in sorted(by_character) if k != NARRATOR]]
    return {
        name: Voice(
            engine=engine,
            voice=by_character[name].voice,
            speed=by_character[name].speed,
            pitch=pitch,
        )
        for name in ordered
        if name in by_character
    }


def _narrator_voice_fallback(cast: dict[str, Any]) -> tuple[str, float]:
    """Narrator ``(voice, speed)`` for the voices map when unused (never raises)."""
    from text.cast import NARRATOR_SPEED, NARRATOR_VOICE

    narrator = cast.get(NARRATOR) if isinstance(cast, dict) else None
    voice = NARRATOR_VOICE
    speed = NARRATOR_SPEED
    if isinstance(narrator, dict):
        raw_voice = narrator.get("voice")
        if isinstance(raw_voice, str) and raw_voice.strip():
            voice = raw_voice
        try:
            speed = float(narrator.get("speed", speed))
        except (TypeError, ValueError):
            speed = NARRATOR_SPEED
    return voice, speed


def remap_chapter_speakers(chapter: ChapterFile, plan: dict[int, ResolvedVoice]) -> ChapterFile:
    """Copy ``chapter`` with sentence ``speaker`` set to the resolved key.

    Timings, text, spans, page and draft fields ride along untouched; only
    ``speaker`` changes (raw surface -> resolved character key). Render
    artifacts keep the raw speakers (so :func:`chapter_up_to_date` still
    compares raw against raw); the bundle chapters are separate copies
    made from the timed chapters just before :func:`write_bundle`.
    EPUB-only in the D-039 triage sense: it carries ``page`` through when
    present but never validates it and drops ``ChapterFile.pages`` (EPUB
    has none; the writer recomputes authoritative marks from timings).
    PDF blocks-form chapters must use :func:`remap_pdf_chapter_speakers`.
    """
    blocks: list[Block] = []
    for block in chapter.blocks or []:
        sentences = [
            Sentence(
                sid=s.sid,
                speaker=plan[s.sid].character if s.sid in plan else s.speaker,
                start_ms=s.start_ms,
                end_ms=s.end_ms,
                text=s.text,
                spans=list(s.spans),
                kind=s.kind,
                confidence=s.confidence,
                quote=dict(s.quote) if s.quote is not None else None,
                split_pair=s.split_pair,
                page=s.page,
            )
            for s in block.sentences
        ]
        blocks.append(
            Block(
                id=block.id,
                type=block.type,
                level=block.level,
                text=block.text,
                sentences=sentences,
            )
        )
    return ChapterFile(
        spec_version=chapter.spec_version,
        chapter=chapter.chapter,
        title=chapter.title,
        duration_ms=chapter.duration_ms,
        blocks=blocks,
    )


def remap_pdf_chapter_speakers(
    chapter: ChapterFile, plan: dict[int, ResolvedVoice]
) -> ChapterFile:
    """PDF-safe speaker remap: like :func:`remap_chapter_speakers` plus page rules.

    Block/sid addressing is identical (deterministic 1..N ids in both EPUB
    and PDF text path), so the sid-keyed ``plan`` lookup is shared logic.
    The exact difference (D-043): the EPUB function drops ``pages`` and
    never validates ``page`` (EPUB has neither); this variant requires
    every sentence to carry a 1-based ``page`` (else :class:`BuildError`,
    since the writer could not compute ``pages`` marks) and carries
    ``ChapterFile.pages`` through (the writer recomputes authoritative
    marks from final timings, so this is a placeholder, never trusted).
    Attribution/cast/multivoice resolution is otherwise unchanged.
    """
    for sentence in chapter.sentences_in_order():
        page = sentence.page
        if not isinstance(page, int) or isinstance(page, bool) or page < 1:
            raise BuildError(
                f"chapter {chapter.chapter}: sentence {sentence.sid} "
                f"missing 1-based page (got {page!r}): PDF remap needs provenance"
            )
    remapped = remap_chapter_speakers(chapter, plan)
    return ChapterFile(
        spec_version=remapped.spec_version,
        chapter=remapped.chapter,
        title=remapped.title,
        duration_ms=remapped.duration_ms,
        blocks=remapped.blocks,
        pages=list(chapter.pages) if chapter.pages is not None else None,
    )


def _fingerprint(cast: dict[str, Any], engine: TTSEngine, chapter: ChapterFile) -> dict[str, Any]:
    """Render fingerprint for one chapter: everything (besides sentence
    text) that can change its audio or its shipped JSON. The cast half is
    the per-sentence resolution (``[sid, character, voice, speed, kind,
    split_pair]`` in document order via :func:`resolve_chapter` plus the
    script ``kind``/``split_pair``), so a cast edit re-renders
    exactly the chapters holding affected sentences — and within them the
    sentence cache re-synthesizes only the changed lines. ``kind`` selects
    the narrator versus character voice and ``split_pair`` selects the
    short tag pause in assembly: carrying them here (rather than in
    :func:`_sentence_key`) is the cleaner seam because render JSON is
    written without draft fields, so a sentence-identity key could never
    see them on the stored side. A chapter-title
    or block-structure edit (breaks carry pauses but no sentences)
    re-renders that chapter; any pause/loudness/encode constant edit or
    :data:`BUFFER_VERSION` bump (see :func:`_assembly_fingerprint`)
    re-renders everything. Unchanged chapters skip."""
    narrator = cast.get(NARRATOR) if isinstance(cast, dict) else None
    voice = narrator.get("voice") if isinstance(narrator, dict) else None
    speed = narrator.get("speed") if isinstance(narrator, dict) else None
    try:
        narrator_speed = float(speed if speed is not None else 1.0)
    except (TypeError, ValueError):
        narrator_speed = 1.0
    plan = resolve_chapter(
        cast if isinstance(cast, dict) else {},
        chapter,
        narrator_voice=voice if isinstance(voice, str) and voice.strip() else "narrator",
        narrator_speed=narrator_speed,
    )
    by_sid = {s.sid: s for s in chapter.sentences_in_order()}
    voices = [
        [
            sid,
            resolved.character,
            resolved.voice,
            resolved.speed,
            by_sid.get(sid).kind if by_sid.get(sid) is not None else "narration",
            by_sid.get(sid).split_pair if by_sid.get(sid) is not None else None,
        ]
        for sid, resolved in plan.items()
    ]
    blocks = [[block.id, block.type, block.level, block.text] for block in chapter.blocks or []]
    return {
        "voices": voices,
        "pitch": VOICE_PITCH,
        "engine_version": engine.engine_version,
        "assembly": _assembly_fingerprint(),
        "title": chapter.title,
        "blocks": blocks,
    }


def _sentence_key(
    sentence: Sentence,
) -> tuple[int, str, str, tuple[tuple[int, int, str], ...], int | None]:
    """Identity of one script sentence for the up-to-date check (text and
    spans included: both flow into the chapter JSON the bundle ships).

    ``kind``/``split_pair`` are intentionally NOT part of this key: render
    JSON is written without draft fields (see ``Sentence.to_dict``), so
    the stored side could never match them. Assembly inputs ride in the
    render fingerprint instead (``_fingerprint`` voices carry
    ``kind``/``split_pair`` per sentence). CP6 ``page`` IS part of the key:
    provenance flows into the shipped JSON (and the writer's ``pages``
    marks), so a page-only change must re-render, never reuse stale timing.
    """
    spans = tuple((s.start, s.end, s.style) for s in sentence.spans)
    return (sentence.sid, sentence.speaker, sentence.text, spans, sentence.page)


def chapter_up_to_date(
    script_chapter: ChapterFile,
    render_json: Path | str,
    render_mp3: Path | str,
    fingerprint: dict[str, Any],
) -> ChapterFile | None:
    """Return the stored timed chapter when the render artifacts are fresh.

    Fresh means: the render JSON parses with the same chapter number, the
    same sentence sequence (sid/speaker/text/spans, see
    :func:`_sentence_key`) as ``script_chapter``,
    a positive duration, and the stored fingerprint equals ``fingerprint``
    (per-chapter: title, block structure, cast voices/speeds plus
    kind/split_pair, pitch,
    engine version, assembly version); plus the render MP3 exists with an
    ffprobe duration within 50 ms of the stored duration and intact
    constant-bitrate frames. ANY deviation (missing/corrupt files,
    title/text/cast/engine/assembly edits, duration drift, torn MP3 with
    broken CBR frames) returns ``None`` -> re-render.
    """
    json_path, mp3_path = Path(render_json), Path(render_mp3)
    if not (json_path.is_file() and mp3_path.is_file()):
        return None
    try:
        raw = _read_json(json_path)
        if not isinstance(raw, dict) or raw.get(RENDER_FINGERPRINT_KEY) != fingerprint:
            return None
        chapter = ChapterFile.from_dict(raw)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None
    if chapter.chapter != script_chapter.chapter or chapter.duration_ms <= 0:
        return None
    if chapter.blocks is None or script_chapter.blocks is None:
        return None
    stored = [_sentence_key(s) for s in chapter.sentences_in_order()]
    current = [_sentence_key(s) for s in script_chapter.sentences_in_order()]
    if not current or stored != current:
        return None
    try:
        probe = probe_audio_ffprobe(mp3_path)
    except (AudioProbeError, OSError):
        return None
    if probe.duration_ms is None:
        return None
    if not getattr(probe, "cbr_frames", None):
        # Truncated/torn MP3s keep the header duration but break frame
        # uniformity (the bundle validator rejects them for the same reason).
        # A missing/None attribute (e.g. an older probe object without the
        # gate) also means "not known-CBR" -> re-render, never abort.
        return None
    if abs(probe.duration_ms - chapter.duration_ms) > DURATION_TOLERANCE_MS:
        return None
    return chapter


def _with_timings(chapter: ChapterFile, timings: list[Any], duration_ms: int) -> ChapterFile:
    """Copy ``chapter`` blocks, filling sentence timings in document order.

    Draft fields (``kind``/``confidence``/``quote``/``split_pair``) and CP6
    ``page`` ride along untouched: assembly reads ``kind``/``split_pair``
    off the script sentences, and the timed copy stays faithful to them
    (the bundle dict still excludes draft fields but includes ``page`` —
    spec v1.1, see ``Sentence.to_dict``). New timed chapters are
    ``spec_version`` "1.1" (readers accept "1.0" and "1.1").
    """
    timing_iter = iter(timings)
    blocks: list[Block] = []
    for block in chapter.blocks or []:
        sentences = [
            Sentence(
                sid=s.sid,
                speaker=s.speaker,
                start_ms=t.start_ms,
                end_ms=t.end_ms,
                text=s.text,
                spans=list(s.spans),
                kind=s.kind,
                confidence=s.confidence,
                quote=dict(s.quote) if s.quote is not None else None,
                split_pair=s.split_pair,
                page=s.page,
            )
            for s, t in zip(block.sentences, [next(timing_iter) for _ in block.sentences])
        ]
        blocks.append(
            Block(
                id=block.id,
                type=block.type,
                level=block.level,
                text=block.text,
                sentences=sentences,
            )
        )
    return ChapterFile(
        spec_version="1.1",
        chapter=chapter.chapter,
        title=chapter.title,
        duration_ms=duration_ms,
        blocks=blocks,
        pages=list(chapter.pages) if chapter.pages is not None else None,
    )


def _write_json(path: Path, data: dict[str, Any]) -> None:
    """Write UTF-8 JSON deterministically (sorted keys, trailing newline)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def remove_stale_chapter_files(directory: Path | str, keep: int, suffix: str) -> list[Path]:
    """Delete ``chNNN<suffix>`` files in ``directory`` with index > ``keep``.

    Used after rendering (and after writing the bundle) so a rerun with
    fewer chapters leaves no orphaned audio/text files. Only exact
    ``ch<digits>`` stems match; anything else is left alone. Missing
    directories are a no-op. Returns the removed paths, sorted.
    """
    folder = Path(directory)
    if not folder.is_dir():
        return []
    removed: list[Path] = []
    for path in sorted(folder.glob(f"*{suffix}")):
        if not path.is_file() or path.suffix != suffix:
            continue
        match = _CHAPTER_STEM_RE.match(path.stem)
        if match is None:
            continue
        if int(match.group(1)) > int(keep):
            path.unlink()
            removed.append(path)
    return removed


def compare_bundles(first: Path | str, second: Path | str) -> list[str]:
    """Diff two bundle dirs under the identical-bundle definition.

    Returns one line per difference; ``[]`` means byte-identical EXCEPT
    ``manifest.json``'s ``created_at`` (which differs by design — UTC now
    at write time — so it is compared with that key ignored). File sets
    must match exactly; every other file, including MP3s and chapter JSON,
    must be byte-identical. Sorted for determinism.
    """
    root_a, root_b = Path(first), Path(second)
    files_a = {p.relative_to(root_a).as_posix() for p in root_a.rglob("*") if p.is_file()}
    files_b = {p.relative_to(root_b).as_posix() for p in root_b.rglob("*") if p.is_file()}
    diffs = [f"only in {root_a}: {name}" for name in sorted(files_a - files_b)]
    diffs += [f"only in {root_b}: {name}" for name in sorted(files_b - files_a)]
    for name in sorted(files_a & files_b):
        data_a = (root_a / name).read_bytes()
        data_b = (root_b / name).read_bytes()
        if name == "manifest.json":
            try:
                json_a = json.loads(data_a.decode("utf-8"))
                json_b = json.loads(data_b.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                if data_a != data_b:
                    diffs.append(f"{name}: bytes differ (unparseable JSON)")
                continue
            json_a.pop("created_at", None)
            json_b.pop("created_at", None)
            if json_a != json_b:
                diffs.append(f"{name}: differs (ignoring created_at)")
            continue
        if data_a != data_b:
            diffs.append(f"{name}: bytes differ")
    return sorted(diffs)


def _append_log(work_dir: Path, lines: list[str]) -> None:
    """Append timestamped ``lines`` to ``scribe.log`` (one run = one block)."""
    stamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    text = "".join(f"[{stamp}] {line}\n" for line in lines)
    with open(work_dir / LOG_FILENAME, "a", encoding="utf-8") as handle:
        handle.write(text)


def format_summary(result: BuildResult) -> str:
    """Human-readable totals for :class:`BuildResult` (printed by the CLI).

    The ``cached``/``rendered`` sentence-audio counts cover this run's
    renders only: skipped (up-to-date) chapters add no synth calls, so
    they contribute neither hits nor misses.
    """
    audio_s = result.total_audio_ms / 1000.0
    lines = [
        f"bundle: {result.bundle_dir} ({result.chapters_total} chapters)",
        f"rendered: {result.rendered}, skipped: {result.skipped}, failed: {len(result.failed)}",
        f"sentence audio (this run's renders only): {result.cache_hits} cached, "
        f"{result.cache_misses} rendered",
        f"total audio: {format_duration(audio_s)} ({result.total_audio_ms} ms)",
        f"wall time: {result.wall_seconds:.1f} s",
        f"real-time factor: {result.rtf:.2f}x",
        f"scribe.log: {result.log_path}",
    ]
    if result.is_range:
        lines.insert(
            1,
            f"range: source chapters {format_range_compact(result.selected_chapters)} "
            f"-> bundle 1..{result.chapters_total}",
        )
    if result.page_resolution:
        lines.append(result.page_resolution)
    return "\n".join(lines)


def format_plan(plan: PlanResult) -> str:
    """Human-readable preflight for :class:`PlanResult` (``--plan`` output)."""
    lines = [
        f"book id: {plan.book_id}",
        f"title: {plan.title}",
        f"chapters: {plan.source_chapters_total} total, "
        f"{len(plan.selected_chapters)} selected "
        f"({format_range_compact(plan.selected_chapters) if plan.is_range else 'full book'})",
        f"chapters cached: {plan.cached_chapters}, to render: {plan.to_render_chapters}",
        f"sentences cached: {plan.cached_sentences}, "
        f"to render: {plan.to_render_sentences}",
    ]
    if plan.estimated_seconds is None:
        lines.append(
            "estimate: unknown (no recent RTF data; render once to calibrate)"
        )
    else:
        lines.append(
            f"estimate: ~{format_duration(plan.estimated_seconds)} "
            f"({plan.estimated_seconds:.0f} s at {plan.rtf_used:.2f}x RTF, "
            f"last {PLAN_RTF_WINDOW} chapters)"
        )
    if plan.page_resolution:
        lines.append(plan.page_resolution)
    if plan.is_range:
        lines.append(
            "range bundle: chapters renumbered 1.."
            f"{len(plan.selected_chapters)} with source_index, "
            "range-aware id (full rebuilds keep the existing id)"
        )
    return "\n".join(lines)


def plan_build(
    source: Path | str,
    *,
    work_root: Path | str = Path(".scribe"),
    chapters: str | None = None,
    pages: str | None = None,
    engine: TTSEngine | None = None,
    models_dir: Path | str = Path("models"),
    device: str = "auto",
) -> PlanResult:
    """Dry run: cached vs to-render counts plus a time estimate (no audio).

    Computes per-chapter fingerprints (needs the cast plus the engine
    version, but never synthesizes) and calls :func:`chapter_up_to_date`
    for each selected chapter. No render files are written, no bundle is
    produced, no lock is taken. The estimate divides to-render audio by the
    recent RTF (see :func:`_recent_rtf`); to-render audio for uncached
    chapters is approximated from draft text (chars / 15 per second, the
    same heuristic as ``draft_report.md``) when no timing exists yet, else
    from the stored render duration when present but stale? Simpler and
    documented: estimate from sentence counts via the draft heuristic
    (chars/15) divided by 1.0 when no RTF history, else by recent RTF.
    Actually implemented: estimate = (to_render_chars / 15) / 1.0? No:
    audio estimate is chars/15 seconds of AUDIO; wall estimate is audio /
    RTF. When no RTF history, wall estimate is unknown (None).

    :raises BuildError: bad selection (same shapes as :func:`run_build`).
    """
    from text.cast import read_cast as _read_cast

    script = ensure_script(source, work_root=work_root)
    normalized_device = str(device or "auto").strip().lower()
    if normalized_device not in ("auto", "cpu", "cuda"):
        raise BuildError(
            f"device: bad-device: {device!r} must be one of ['auto', 'cpu', 'cuda']"
        )
    selected, resolution = _resolve_selection(
        script.chapters, chapters_spec=chapters, pages_spec=pages
    )
    is_range = selected != list(range(1, len(script.chapters) + 1))
    work_dir = script.work_dir
    try:
        cast = _read_cast(work_dir / CAST_FILENAME)
    except (OSError, ValueError) as exc:
        raise BuildError(f"{CAST_FILENAME}: cannot read cast: {exc}") from exc
    tts = engine
    if tts is None:
        # Plan needs only the engine version for fingerprints; a missing
        # model dir must not fail preflight, so fall back to a stable
        # placeholder version (counts still correct, estimate may be None).
        try:
            tts = create_engine(models_dir, device=normalized_device)
        except (BuildError, TypeError):
            tts = None

    width = 4 if len(script.chapters) > 999 else 3
    cached_ch = to_render_ch = 0
    cached_sent = to_render_sent = 0
    to_render_chars = 0
    for chapter in script.chapters:
        if chapter.chapter not in selected:
            continue
        stem = f"ch{chapter.chapter:0{width}d}"
        mp3_path = work_dir / RENDER_DIRNAME / "audio" / f"{stem}.mp3"
        json_path = work_dir / RENDER_DIRNAME / "text" / f"{stem}.json"
        fingerprint: dict[str, Any] | None = None
        if tts is not None:
            try:
                fingerprint = _fingerprint(cast, tts, chapter)
            except Exception:
                fingerprint = None
        hit = None
        if fingerprint is not None:
            try:
                hit = chapter_up_to_date(chapter, json_path, mp3_path, fingerprint)
            except Exception:
                hit = None
        sentences = chapter.sentences_in_order()
        if hit is not None:
            cached_ch += 1
            cached_sent += len(sentences)
        else:
            to_render_ch += 1
            to_render_sent += len(sentences)
            to_render_chars += sum(len(s.text.strip()) for s in sentences)
    rtf = _recent_rtf(work_dir)
    estimated: float | None = None
    if to_render_sent and rtf:
        # Draft heuristic: chars/15 = audio seconds; wall = audio / RTF.
        audio_s = to_render_chars / 15.0
        estimated = audio_s / rtf if rtf > 0 else None
    return PlanResult(
        book_id=(book_id_for_range(script.sha256, selected) if is_range else script.book_id),
        title=script.title,
        work_dir=work_dir,
        source_chapters_total=len(script.chapters),
        selected_chapters=selected,
        cached_chapters=cached_ch,
        to_render_chapters=to_render_ch,
        cached_sentences=cached_sent,
        to_render_sentences=to_render_sent,
        estimated_seconds=estimated,
        rtf_used=rtf,
        page_resolution=resolution,
        is_range=is_range,
    )


def run_build(
    source: Path | str,
    *,
    work_root: Path | str = Path(".scribe"),
    out_dir: Path | str | None = None,
    engine: TTSEngine | None = None,
    models_dir: Path | str = Path("models"),
    strict: bool = False,
    fail_after: int | None = None,
    show_progress: bool = True,
    chapters: str | None = None,
    pages: str | None = None,
    device: str = "auto",
    progress_listener: Callable[[dict[str, Any]], None] | None = None,
    stop_event: Any | None = None,
    stop_file: Path | str | None = None,
) -> BuildResult:
    """Render ``source`` EPUB or PDF to a validated bundle; return the totals.

    Runs ``draft`` first when the work folder lacks a fresh script
    (:func:`ensure_script`), renders chapter by chapter with resume
    (:func:`chapter_up_to_date`), cleans orphaned chapters, writes the
    bundle via the SW8 writer (which validates), appends ``scribe.log``,
    and returns :class:`BuildResult` (totals + real-time factor).

    UI1 additions (library only, CLI outputs identical for existing cases):
    chapter selection (``chapters``/``pages``), ``device``, progress
    ``progress_listener`` (the CLI Rich bar is one such listener),
    cooperative ``stop_event`` (checked at sentence boundaries; partial
    chapter files removed so resume re-renders fully), per-book lock file,
    and range-aware bundle ids (D7: full keeps the existing id).

    :param source: EPUB or PDF file to build from.
    :param work_root: work folder root (``<work_root>/<book-id>/`` holds
        script, cast, cache, render output and ``scribe.log``).
    :param out_dir: bundle root; defaults to ``bundles/<book-id>`` (full)
        or ``bundles/<range-id>`` (range).
    :param engine: TTS engine; ``None`` builds the Kokoro engine from
        ``models_dir`` (honoring ``device``). Tests inject a fake.
    :param strict: abort the whole build on the first chapter error.
        Non-strict renders every remaining chapter first, then raises
        :class:`BuildError` listing all failures (no bundle either way —
        gaps cannot be represented, so the failure is recorded instead).
    :param fail_after: test-only kill simulation — raise
        :class:`BuildError` after this many chapters render in this run
        (``None``/``<1`` disables). A real SIGKILL writes nothing; this
        hook logs its abort line, so it is louder, not quieter.
    :param show_progress: ``rich`` chapter bar with ETA (off in tests).
        Implemented as a :class:`progress.RichProgressListener` (same
        columns/wording as before); combined with ``progress_listener``
        via fanout when both are given.
    :param chapters: UI1 ``--chapters`` spec (``"3-5,7"``); ``None`` = all.
    :param pages: UI1 ``--pages`` spec for PDFs (resolves to covering
        chapters); mutually exclusive with ``chapters``.
    :param device: UI1 ``--device`` (``auto``/``cpu``/``cuda``).
    :param progress_listener: optional callable receiving event dicts
        (see :mod:`progress`); called at sentence/chapter boundaries.
    :param stop_event: optional ``threading.Event`` (or any object with
        ``is_set()``); when set, the build stops at the next sentence
        boundary, cleans the partial chapter, and raises
        :class:`BuildStoppedError`.
    :param stop_file: optional cross-process stop sentinel (UI4 pause):
        when the path exists, the build stops at the next sentence
        boundary exactly like ``stop_event`` (partial chapter cleaned,
        :class:`BuildStoppedError`). The file is never created or deleted
        here; the job manager creates it to request a graceful stop and
        removes it before a resume. Checked alongside ``stop_event``.
    :raises BuildError: missing source, bad cast, bad selection/device,
        work-lock held, chapter failure(s), strict abort, simulated kill.
    :raises BuildStoppedError: cooperative stop (subclass of BuildError).
    :raises draft.DraftError: re-drafting a stale script failed.
    :raises bundle.writer.BundleWriteError: the written bundle failed validation.
    """
    from lock import WorkLockError, acquire_work_lock

    def _stopped() -> bool:
        try:
            if stop_event is not None and stop_event.is_set():
                return True
        except Exception:
            pass
        if stop_file is not None:
            try:
                if Path(stop_file).exists():
                    return True
            except OSError:
                pass
        return False

    wall_start = time.monotonic()
    # Selection parsing needs the script; ensure_script may draft (which
    # takes the same per-book lock — same-PID re-entry is allowed, so take
    # our lock AFTER the script is fresh to keep the work_dir stable, then
    # hold it for render+write). Compute the book work_dir first without
    # holding (cheap hash), then lock, then ensure fresh under lock.
    prelim_id: str | None = None
    try:
        prelim_id, _ = book_id_for_file(Path(source))
    except OSError:
        prelim_id = None
    lock: Any = None
    if prelim_id is not None:
        try:
            lock = acquire_work_lock(Path(work_root) / prelim_id, cmd="build")
        except WorkLockError as exc:
            raise BuildError(str(exc)) from exc
        except OSError as exc:
            raise BuildError(
                f"{Path(work_root) / prelim_id}: cannot write work folder: {exc}"
            ) from exc
    try:
        return _run_build_locked(
            source,
            work_root=work_root,
            out_dir=out_dir,
            engine=engine,
            models_dir=models_dir,
            strict=strict,
            fail_after=fail_after,
            show_progress=show_progress,
            chapters=chapters,
            pages=pages,
            device=device,
            progress_listener=progress_listener,
            stop_event=stop_event,
            wall_start=wall_start,
            stopped_fn=_stopped,
        )
    finally:
        if lock is not None:
            try:
                lock.release()
            except Exception:
                pass


def _run_build_locked(
    source: Path | str,
    *,
    work_root: Path | str,
    out_dir: Path | str | None,
    engine: TTSEngine | None,
    models_dir: Path | str,
    strict: bool,
    fail_after: int | None,
    show_progress: bool,
    chapters: str | None,
    pages: str | None,
    device: str,
    progress_listener: Callable[[dict[str, Any]], None] | None,
    stop_event: Any | None,
    wall_start: float,
    stopped_fn: Callable[[], bool],
) -> BuildResult:
    """Inner build (lock already held); see :func:`run_build`."""
    from progress import FanoutProgress, RichProgressListener, make_event

    script = ensure_script(source, work_root=work_root)
    all_chapters = script.chapters
    work_dir = script.work_dir
    # UI1 device shape check first (invalid names fail even with an
    # injected fake engine; CUDA availability is checked at creation).
    normalized_device = str(device or "auto").strip().lower()
    if normalized_device not in ("auto", "cpu", "cuda"):
        raise BuildError(
            f"device: bad-device: {device!r} must be one of ['auto', 'cpu', 'cuda']"
        )
    selected, page_resolution = _resolve_selection(
        all_chapters, chapters_spec=chapters, pages_spec=pages
    )
    is_range = selected != list(range(1, len(all_chapters) + 1))
    selected_set = set(selected)
    render_chapters = [c for c in all_chapters if c.chapter in selected_set]
    bundle_id = book_id_for_range(script.sha256, selected) if is_range else script.book_id

    cast_path = work_dir / CAST_FILENAME
    try:
        cast = read_cast(cast_path)
    except (OSError, ValueError) as exc:
        raise BuildError(f"{cast_path.name}: cannot read cast: {exc}") from exc

    if engine is not None:
        tts = engine
    else:
        try:
            tts = create_engine(models_dir, device=normalized_device)
        except TypeError:
            # Back-compat for test monkeypatches replacing create_engine
            # with a single-arg lambda (pre-UI1 signature).
            tts = create_engine(models_dir)

    # Validate against the engine's real voice list, not a synthetic set.
    # Unknown voices fail the build here with file plus key plus rule
    # errors. When the engine exposes no voice list (``voices is None``),
    # validation checks shape only (non-empty voice strings).
    cast_errors = validate_cast(cast, source=CAST_FILENAME, known_voices=tts.voices)
    if cast_errors:
        raise BuildError("; ".join(cast_errors))
    voice, speed = _narrator_voice(cast_path)

    cache_dir = work_dir / CACHE_DIRNAME
    render_audio = work_dir / RENDER_DIRNAME / "audio"
    render_text = work_dir / RENDER_DIRNAME / "text"
    render_audio.mkdir(parents=True, exist_ok=True)
    render_text.mkdir(parents=True, exist_ok=True)

    width = 4 if len(all_chapters) > 999 else 3

    # Render orphan cleanup up front (not after the loop): a shorter script
    # must not leave stale chNNN files behind even when this run aborts.
    # UI1: keep is the FULL chapter count (range builds must not delete
    # unselected chapters' render files; they are resume cache).
    stale_render = remove_stale_chapter_files(render_audio, len(all_chapters), ".mp3")
    stale_render += remove_stale_chapter_files(render_text, len(all_chapters), ".json")

    # Progress: the CLI Rich bar is one listener implementation (same
    # columns/wording as before); fan out to the caller's listener when
    # both are present so numbers stay identical.
    rich_bar = RichProgressListener(total=len(render_chapters), enabled=show_progress)
    if progress_listener is not None and show_progress:
        listener: Callable[[dict[str, Any]], None] | None = FanoutProgress(
            rich_bar, progress_listener
        )
    elif progress_listener is not None:
        listener = FanoutProgress(progress_listener)
        # Still record Rich events disabled (for tests asserting parity).
        rich_bar.enabled = False
    else:
        listener = rich_bar if show_progress else None
    # When neither sink is enabled, use a null recorder so sentence counts
    # stay testable without terminal output.
    null_events: list[dict[str, Any]] = []

    def _emit(event: dict[str, Any]) -> None:
        if listener is not None:
            listener(event)
        else:
            null_events.append(dict(event))
        # Disabled Rich bar still records for parity tests.
        if listener is not rich_bar and show_progress is False and rich_bar.enabled is False:
            rich_bar.events.append(dict(event))

    cum_cached = cum_rendered = 0
    cum_audio_ms = 0

    def _synth_fn(
        plan: dict[int, ResolvedVoice],
        stats: CacheStats,
        chapter_index: int,
    ) -> Callable[[Sentence], np.ndarray]:
        def _synth(sentence: Sentence) -> np.ndarray:
            if stopped_fn():
                raise BuildStoppedError(
                    f"build: stopped: cooperative stop at chapter {chapter_index} "
                    f"sentence {sentence.sid} (partial chapter cleaned; resume re-renders)"
                )
            resolved = plan[sentence.sid]
            audio = get_or_synth(
                tts,
                sentence.text,
                resolved.voice,
                resolved.speed,
                VOICE_PITCH,
                cache_dir,
                stats=stats,
            )
            return np.asarray(audio, dtype=np.float32)

        return _synth

    log_lines = [
        f"build start: book {script.book_id} source {Path(source).name} "
        f"strict={strict} chapters={len(all_chapters)} "
        f"selected={(format_range_compact(selected) if is_range else 'all')} "
        f"bundle_id={bundle_id} device={device}",
        f"script: {'redrafted' if script.redrafted else 'fresh'} ({len(all_chapters)} chapters)",
        f"cast: narrator voice={voice} speed={speed} engine_version={tts.engine_version}",
    ]
    if page_resolution:
        log_lines.append(page_resolution)

    timed: dict[int, ChapterFile] = {}
    audio_paths: dict[int, Path] = {}
    plans: dict[int, dict[int, ResolvedVoice]] = {}
    chapter_audio_ms: dict[int, int] = {}
    rendered = skipped = 0
    rendered_this_run = 0
    cache_hits = cache_misses = 0
    failed: list[int] = []
    failure_reasons: dict[int, str] = {}
    stats_entries: list[dict[str, Any]] = []

    hook = fail_after if fail_after is not None and fail_after >= 1 else None
    rich_ctx = rich_bar if show_progress else None
    # Enter Rich context manually so events flow even when disabled.
    if rich_ctx is not None:
        rich_ctx.__enter__()
    try:
        for chapter in render_chapters:
            if stopped_fn():
                log_lines.append("build stopped: cooperative stop before chapter "
                                 f"{chapter.chapter} (no bundle written)")
                _append_log(work_dir, log_lines)
                raise BuildStoppedError(
                    f"build: stopped: cooperative stop before chapter {chapter.chapter} "
                    "(no bundle written; resume re-renders uncached)"
                )
            index = chapter.chapter
            stem = f"ch{index:0{width}d}"
            mp3_path = render_audio / f"{stem}.mp3"
            json_path = render_text / f"{stem}.json"
            plan = resolve_chapter(cast, chapter, narrator_voice=voice, narrator_speed=speed)
            fingerprint = _fingerprint(cast, tts, chapter)
            hit = chapter_up_to_date(chapter, json_path, mp3_path, fingerprint)
            if hit is not None:
                timed[index] = hit
                audio_paths[index] = mp3_path
                plans[index] = plan
                chapter_audio_ms[index] = hit.duration_ms
                skipped += 1
                cum_audio_ms += hit.duration_ms
                log_lines.append(f"chapter {index} {chapter.title}: skipped (up-to-date)")
                _emit(
                    make_event(
                        "chapter_skipped",
                        chapter=index,
                        cached=cum_cached,
                        rendered=cum_rendered,
                        audio_ms=hit.duration_ms,
                        wall_s=time.monotonic() - wall_start,
                    )
                    | {"title": chapter.title, "chapters_total": len(render_chapters)}
                )
                continue
            chapter_stats = CacheStats()
            chapter_wall = time.monotonic()
            try:
                # Sentence-level stop + progress: wrap synth to emit per
                # sentence so the Rich bar and the JSONL tail see the same
                # cumulative numbers (previous chapters + this chapter so far).
                base_synth = _synth_fn(plan, chapter_stats, index)

                def _emit_synth(sentence: Sentence) -> np.ndarray:
                    audio = base_synth(sentence)
                    _emit(
                        make_event(
                            "sentence",
                            chapter=index,
                            sid=sentence.sid,
                            cached=cum_cached + chapter_stats.hits,
                            rendered=cum_rendered + chapter_stats.misses,
                            audio_ms=cum_audio_ms,
                            wall_s=time.monotonic() - wall_start,
                        )
                    )
                    return audio

                assembled = assemble_chapter(
                    chapter,
                    _emit_synth,
                    voice_of=lambda sentence: plan[sentence.sid].voice,
                )
                loud = apply_loudness_gain(assembled.pcm)
                to_encode = AssembledChapter(
                    pcm=loud,
                    timings=assembled.timings,
                    duration_ms=assembled.duration_ms,
                    sample_count=assembled.sample_count,
                    sample_rate=assembled.sample_rate,
                )
                try:
                    encode_assembled_chapter(to_encode, mp3_path, chapter_label=stem)
                except Exception:
                    mp3_path.unlink(missing_ok=True)
                    raise
                timed_chapter = _with_timings(chapter, assembled.timings, assembled.duration_ms)
                payload = dict(timed_chapter.to_dict())
                payload[RENDER_FINGERPRINT_KEY] = fingerprint
                _write_json(json_path, payload)
                timed[index] = timed_chapter
                audio_paths[index] = mp3_path
                plans[index] = plan
                chapter_audio_ms[index] = assembled.duration_ms
                rendered += 1
                rendered_this_run += 1
                # Cumulative sentence-audio counts (same numbers the Rich
                # bar and JSONL tail report).
                cum_cached += chapter_stats.hits
                cum_rendered += chapter_stats.misses
                cum_audio_ms += assembled.duration_ms
                cache_hits += chapter_stats.hits
                cache_misses += chapter_stats.misses
                stats_entries.append(
                    {
                        "audio_ms": assembled.duration_ms,
                        "wall_s": round(time.monotonic() - chapter_wall, 3),
                        "chapter": index,
                        "sentences": len(timed_chapter.sentences_in_order()),
                    }
                )
                sentences = len(timed_chapter.sentences_in_order())
                log_lines.append(
                    f"chapter {index} {chapter.title}: rendered "
                    f"({assembled.duration_ms} ms, {sentences} sentences; "
                    f"audio {chapter_stats.hits} cached, "
                    f"{chapter_stats.misses} rendered)"
                )
                _emit(
                    make_event(
                        "chapter_done",
                        chapter=index,
                        cached=cum_cached,
                        rendered=cum_rendered,
                        audio_ms=assembled.duration_ms,
                        wall_s=time.monotonic() - wall_start,
                    )
                    | {"title": chapter.title, "chapters_total": len(render_chapters)}
                )
            except BuildStoppedError:
                # Atomicity: partial chapter output removed so resume
                # re-renders it fully (render files are only complete after
                # a successful encode; a stop mid-sentences leaves nothing,
                # a stop mid-encode leaves a torn MP3 — both deleted).
                mp3_path.unlink(missing_ok=True)
                json_path.unlink(missing_ok=True)
                timed.pop(index, None)
                audio_paths.pop(index, None)
                plans.pop(index, None)
                log_lines.append(
                    f"chapter {index} {chapter.title}: stopped (partial cleaned)"
                )
                _append_log(work_dir, log_lines)
                raise
            except Exception as exc:  # chapter-level: strict aborts, else record
                # A stop checked late (e.g. encode took long) still cleans.
                if stopped_fn():
                    mp3_path.unlink(missing_ok=True)
                    json_path.unlink(missing_ok=True)
                    timed.pop(index, None)
                    audio_paths.pop(index, None)
                    plans.pop(index, None)
                    log_lines.append(
                        f"chapter {index} {chapter.title}: stopped (partial cleaned)"
                    )
                    _append_log(work_dir, log_lines)
                    raise BuildStoppedError(
                        f"build: stopped: cooperative stop at chapter {index} "
                        "(partial chapter cleaned; resume re-renders)"
                    ) from exc
                reason = f"{type(exc).__name__}: {exc}"
                if strict:
                    log_lines.append(f"chapter {index} {chapter.title}: FAILED {reason}")
                    log_lines.append(f"build aborted (strict): chapter {index} failed")
                    _append_log(work_dir, log_lines)
                    raise BuildError(f"chapter {index} failed: {exc}") from exc
                failed.append(index)
                failure_reasons[index] = reason
                log_lines.append(f"chapter {index} {chapter.title}: FAILED {reason}")
            if hook is not None and rendered_this_run >= hook:
                log_lines.append(
                    f"build aborted: simulated failure after {hook} "
                    "chapter(s) (test hook; rerun resumes)"
                )
                _append_log(work_dir, log_lines)
                raise BuildError(
                    f"simulated failure after {hook} chapter(s) (test hook; rerun resumes)"
                )
    finally:
        if rich_ctx is not None:
            rich_ctx.__exit__(None, None, None)

    if failed:
        details = ", ".join(f"chapter {i}: {failure_reasons[i]}" for i in failed)
        log_lines.append(f"build failed: {len(failed)} chapter(s) failed: {details}")
        _append_log(work_dir, log_lines)
        raise BuildError(f"{len(failed)} chapter(s) failed, no bundle written: {details}")
    ordered_audio = [audio_paths[i] for i in selected]
    ordered_plans = [plans[i] for i in selected]
    # MV8: bundle speakers are the RESOLVED character keys (reuse the exact
    # plans used for synthesis/leveling/fingerprint — never re-resolve),
    # and the manifest voices map ships exactly those characters.
    voices_map = build_voices_map(cast, ordered_plans)

    def _remap_for_bundle(
        timed_chapter: ChapterFile, plan: dict[int, ResolvedVoice]
    ) -> ChapterFile:
        """EPUB remap, or the PDF-safe variant when pages are present.

        Blocks-form PDF chapters share block/sid addressing, so the same
        sid-keyed plan applies; the PDF variant additionally requires
        1-based ``page`` on every sentence (provenance for the writer's
        ``pages`` marks). EPUB chapters (no ``page`` anywhere) use the
        original.
        """
        sentences = timed_chapter.sentences_in_order()
        if any(s.page is not None for s in sentences):
            return remap_pdf_chapter_speakers(timed_chapter, plan)
        return remap_chapter_speakers(timed_chapter, plan)

    # Range bundles renumber consecutively for the writer (1..K in
    # selection order); the writer attaches source_index + range id.
    # Full builds pass through numbered 1..N (writer keeps 1.1 + existing id).
    bundle_numbered: list[ChapterFile] = []
    if is_range:
        for pos, src in enumerate(selected, start=1):
            remapped = _remap_for_bundle(timed[src], plans[src])
            bundle_numbered.append(
                ChapterFile(
                    spec_version=remapped.spec_version,
                    chapter=pos,
                    title=remapped.title,
                    duration_ms=remapped.duration_ms,
                    blocks=remapped.blocks,
                    pages=remapped.pages,
                )
            )
    else:
        bundle_numbered = [_remap_for_bundle(timed[i], plans[i]) for i in selected]
    if out_dir is not None:
        bundle_dir = Path(out_dir)
    else:
        bundle_dir = Path("bundles") / bundle_id
    write_bundle(
        bundle_numbered,
        ordered_audio,
        source,
        bundle_dir,
        voices=voices_map,
        source_indices=selected if is_range else None,
        bundle_id=bundle_id,
    )

    stale_bundle = remove_stale_chapter_files(bundle_dir / "audio", len(selected), ".mp3")
    stale_bundle += remove_stale_chapter_files(bundle_dir / "text", len(selected), ".json")

    total_audio_ms = sum(chapter_audio_ms[i] for i in selected)
    total_sentences = sum(len(timed[i].sentences_in_order()) for i in selected)
    wall_seconds = time.monotonic() - wall_start
    audio_seconds = total_audio_ms / 1000.0
    rtf = audio_seconds / wall_seconds if wall_seconds > 0 else 0.0

    removed = [p.name for p in (*stale_render, *stale_bundle)]
    log_lines.append(f"orphans removed: {', '.join(removed) if removed else 'none'}")
    log_lines.append(
        f"build done: chapters={len(selected)} rendered={rendered} "
        f"skipped={skipped} failed=0 total_audio_ms={total_audio_ms} "
        f"cached={cache_hits} synthesized={cache_misses} "
        f"wall_seconds={wall_seconds:.1f} real-time factor (RTF): {rtf:.2f}x"
    )
    if page_resolution:
        log_lines.append(page_resolution)
    try:
        _append_log(work_dir, log_lines)
    except OSError as exc:
        raise BuildError(
            f"bundle written to {bundle_dir} but {LOG_FILENAME} unwritable: {exc}"
        ) from exc
    _record_build_stats(work_dir, stats_entries)
    _emit(
        make_event(
            "build_done",
            chapter=0,
            cached=cum_cached,
            rendered=cum_rendered,
            audio_ms=total_audio_ms,
            wall_s=wall_seconds,
        )
    )

    return BuildResult(
        book_id=bundle_id,
        title=script.title,
        work_dir=work_dir,
        bundle_dir=bundle_dir,
        log_path=work_dir / LOG_FILENAME,
        chapters_total=len(selected),
        rendered=rendered,
        skipped=skipped,
        failed=[],
        total_sentences=total_sentences,
        total_audio_ms=total_audio_ms,
        wall_seconds=wall_seconds,
        rtf=rtf,
        strict=strict,
        cache_hits=cache_hits,
        cache_misses=cache_misses,
        source_chapters_total=len(all_chapters),
        selected_chapters=list(selected),
        page_resolution=page_resolution,
        is_range=is_range,
    )
