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

"""``scribe build`` pipeline (SW9): work folder -> rendered chapters -> bundle.

Chapter by chapter: script sentences -> SW6 cache/engine
(:func:`tts.cache.get_or_synth`) -> SW7 assembly
(:func:`audio.assemble.assemble_chapter`, timings come from that same
continuous buffer — never reimplemented here) -> SW7 encode
(:func:`audio.encode.encode_assembled_chapter`) -> SW8 writer
(:func:`bundle.writer.write_bundle`, which runs the SW2 validator gate).

Draft freshness: :func:`ensure_script` runs ``draft`` first when the work
folder lacks a fresh script. Fresh means ``script.json`` exists AND its
``source_sha256`` matches the source file's current sha256; anything else
(missing file, unreadable JSON, shape error, sha mismatch) re-drafts.

Resume: each chapter's encoded MP3 plus its timed chapter JSON live under
``<work-dir>/<book-id>/render/`` (``audio/chNNN.mp3``,
``text/chNNN.json``). A chapter is skipped when :func:`chapter_up_to_date`
finds a valid MP3 plus matching timings already on disk: same sentence
``(sid, speaker, text, spans)`` sequence as the script, same chapter title
and block structure (break blocks carry pauses but no sentences), same
cast voices/speeds, pitch and engine version, and the same
:data:`ASSEMBLY_VERSION` (bumped whenever pause/loudness/encode constants
or buffer construction change), plus an MP3 whose ffprobe duration agrees
within 50 ms and whose frames are intact CBR. Skipped chapters are never
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
from rich.progress import (
    BarColumn,
    Progress,
    TaskProgressColumn,
    TextColumn,
    TimeRemainingColumn,
)

from audio.assemble import AssembledChapter, apply_loudness_gain, assemble_chapter
from audio.encode import encode_assembled_chapter
from bundle.models import Block, ChapterFile, Sentence
from bundle.validate import AudioProbeError, DURATION_TOLERANCE_MS, probe_audio_ffprobe
from bundle.writer import VOICE_PITCH, write_bundle
from draft import (
    CAST_FILENAME,
    SCRIPT_FILENAME,
    book_id_for_file,
    format_duration,
    run_draft,
)
from text.cast import NARRATOR, read_cast
from tts.base import TTSEngine
from tts.cache import get_or_synth

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

#: Version of the chapter assembly pipeline baked into the render
#: fingerprint. Bump this integer whenever anything that can change a
#: rendered chapter changes: the pause lengths (PAUSE_*_MS in
#: audio.assemble), the loudness target (PEAK_TARGET_DBFS), the encode
#: settings (BITRATE_KBPS / codec flags in audio.encode), or the
#: buffer/timing construction itself (assemble_chapter, _with_timings
#: here). A bump re-renders every chapter once; forgetting a bump silently
#: reuses stale audio, so bump first and ask questions later.
ASSEMBLY_VERSION = 1


class BuildError(ValueError):
    """``scribe build`` cannot proceed or a chapter failed (see message)."""


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


def create_engine(models_dir: Path | str = Path("models")) -> TTSEngine:
    """Build the Slice 2 TTS engine (Kokoro, D-023) from ``models_dir``.

    :raises BuildError: runtime/models missing or unreadable (clean message,
        no traceback from deep inside the engine stack).
    """
    from tts.kokoro import resolve_model_paths

    try:
        from tts.kokoro import KokoroEngine

        model_path, voices_path = resolve_model_paths(models_dir)
        return KokoroEngine(model_path, voices_path)
    except (ImportError, FileNotFoundError, ValueError, OSError) as exc:
        raise BuildError(f"cannot init TTS engine: {exc}") from exc


def _read_json(path: Path) -> Any:
    """Parse ``path`` as UTF-8 JSON (any decode/shape error propagates)."""
    return json.loads(path.read_text(encoding="utf-8"))


def ensure_script(
    source: Path | str, *, work_root: Path | str = Path(".scribe")
) -> ScriptInfo:
    """Return a fresh script for ``source``, drafting first when stale.

    Fresh means ``<work_root>/<book-id>/script.json`` exists AND its
    ``source_sha256`` matches the source file's current sha256. Anything
    else (missing file, bad JSON, shape error, sha mismatch — e.g. the
    source changed since the draft) runs :func:`draft.run_draft` again.

    :raises BuildError: the source file is missing/unreadable.
    :raises DraftError: drafting a stale/missing script failed.
    """
    src = Path(source)
    if not src.is_file():
        raise BuildError(f"EPUB not found: {src}")
    try:
        book_id, sha = book_id_for_file(src)
    except OSError as exc:
        raise BuildError(f"{src.name}: cannot read source file: {exc}") from exc

    work_dir = Path(work_root) / book_id
    script_path = work_dir / SCRIPT_FILENAME
    if script_path.is_file():
        try:
            data = _read_json(script_path)
            if isinstance(data, dict) and data.get("source_sha256") == sha:
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
        raise BuildError(f"{cast_path.name}: no '{NARRATOR}' entry (Slice 2 is single-voice)")
    voice = entry.get("voice")
    if not isinstance(voice, str) or not voice.strip():
        raise BuildError(f"{cast_path.name}: narrator entry needs a 'voice' id")
    try:
        speed = float(entry.get("speed", 1.0))
    except (TypeError, ValueError) as exc:
        raise BuildError(f"{cast_path.name}: narrator 'speed' is not a number") from exc
    return voice, speed


def _fingerprint(
    cast: dict[str, Any], engine: TTSEngine, chapter: ChapterFile
) -> dict[str, Any]:
    """Render fingerprint for one chapter: everything (besides sentence
    text) that can change its audio or its shipped JSON. A cast edit
    re-renders; a chapter-title or block-structure edit (breaks carry
    pauses but no sentences) re-renders that chapter; an
    :data:`ASSEMBLY_VERSION` bump re-renders everything. Unchanged chapters
    skip."""
    voices = {
        speaker: {"voice": entry.get("voice"), "speed": entry.get("speed")}
        for speaker, entry in cast.items()
        if isinstance(entry, dict)
    }
    blocks = [
        [block.id, block.type, block.level, block.text]
        for block in chapter.blocks or []
    ]
    return {
        "cast": voices,
        "pitch": VOICE_PITCH,
        "engine_version": engine.engine_version,
        "assembly": ASSEMBLY_VERSION,
        "title": chapter.title,
        "blocks": blocks,
    }


def _sentence_key(sentence: Sentence) -> tuple[int, str, str, tuple[tuple[int, int, str], ...]]:
    """Identity of one script sentence for the up-to-date check (text and
    spans included: both flow into the chapter JSON the bundle ships)."""
    spans = tuple((s.start, s.end, s.style) for s in sentence.spans)
    return (sentence.sid, sentence.speaker, sentence.text, spans)


def chapter_up_to_date(
    script_chapter: ChapterFile,
    render_json: Path | str,
    render_mp3: Path | str,
    fingerprint: dict[str, Any],
) -> ChapterFile | None:
    """Return the stored timed chapter when the render artifacts are fresh.

    Fresh means: the render JSON parses with the same chapter number, the
    same sentence sequence (sid/speaker/text/spans) as ``script_chapter``,
    a positive duration, and the stored fingerprint equals ``fingerprint``
    (per-chapter: title, block structure, cast voices/speeds, pitch,
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
    if not probe.cbr_frames:
        # Truncated/torn MP3s keep the header duration but break frame
        # uniformity (the bundle validator rejects them for the same reason).
        return None
    if abs(probe.duration_ms - chapter.duration_ms) > DURATION_TOLERANCE_MS:
        return None
    return chapter


def _with_timings(
    chapter: ChapterFile, timings: list[Any], duration_ms: int
) -> ChapterFile:
    """Copy ``chapter`` blocks, filling sentence timings in document order."""
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
        spec_version="1.0",
        chapter=chapter.chapter,
        title=chapter.title,
        duration_ms=duration_ms,
        blocks=blocks,
    )


def _write_json(path: Path, data: dict[str, Any]) -> None:
    """Write UTF-8 JSON deterministically (sorted keys, trailing newline)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def remove_stale_chapter_files(
    directory: Path | str, keep: int, suffix: str
) -> list[Path]:
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
    files_a = {
        p.relative_to(root_a).as_posix()
        for p in root_a.rglob("*")
        if p.is_file()
    }
    files_b = {
        p.relative_to(root_b).as_posix()
        for p in root_b.rglob("*")
        if p.is_file()
    }
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
    """Human-readable totals for :class:`BuildResult` (printed by the CLI)."""
    audio_s = result.total_audio_ms / 1000.0
    lines = [
        f"bundle: {result.bundle_dir} ({result.chapters_total} chapters)",
        f"rendered: {result.rendered}, skipped: {result.skipped}, "
        f"failed: {len(result.failed)}",
        f"total audio: {format_duration(audio_s)} ({result.total_audio_ms} ms)",
        f"wall time: {result.wall_seconds:.1f} s",
        f"real-time factor: {result.rtf:.2f}x",
        f"scribe.log: {result.log_path}",
    ]
    return "\n".join(lines)


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
) -> BuildResult:
    """Render ``source`` EPUB to a validated bundle; return the totals.

    Runs ``draft`` first when the work folder lacks a fresh script
    (:func:`ensure_script`), renders chapter by chapter with resume
    (:func:`chapter_up_to_date`), cleans orphaned chapters, writes the
    bundle via the SW8 writer (which validates), appends ``scribe.log``,
    and returns :class:`BuildResult` (totals + real-time factor).

    :param source: EPUB file to build from.
    :param work_root: work folder root (``<work_root>/<book-id>/`` holds
        script, cast, cache, render output and ``scribe.log``).
    :param out_dir: bundle root; defaults to ``bundles/<book-id>``.
    :param engine: TTS engine; ``None`` builds the Kokoro engine from
        ``models_dir``. Tests inject a fake (no real TTS in tests).
    :param strict: abort the whole build on the first chapter error.
        Non-strict renders every remaining chapter first, then raises
        :class:`BuildError` listing all failures (no bundle either way —
        gaps cannot be represented, so the failure is recorded instead).
    :param fail_after: test-only kill simulation — raise
        :class:`BuildError` after this many chapters render in this run
        (``None``/``<1`` disables). A real SIGKILL writes nothing; this
        hook logs its abort line, so it is louder, not quieter.
    :param show_progress: ``rich`` chapter bar with ETA (off in tests).
    :raises BuildError: missing source, bad cast, chapter failure(s),
        strict abort, or the simulated kill.
    :raises draft.DraftError: re-drafting a stale script failed.
    :raises bundle.writer.BundleWriteError: the written bundle failed validation.
    """
    wall_start = time.monotonic()
    script = ensure_script(source, work_root=work_root)
    chapters = script.chapters
    work_dir = script.work_dir

    cast_path = work_dir / CAST_FILENAME
    try:
        cast = read_cast(cast_path)
    except (OSError, ValueError) as exc:
        raise BuildError(f"{cast_path.name}: cannot read cast: {exc}") from exc
    voice, speed = _narrator_voice(cast_path)

    tts = engine if engine is not None else create_engine(models_dir)
    cache_dir = work_dir / CACHE_DIRNAME
    render_audio = work_dir / RENDER_DIRNAME / "audio"
    render_text = work_dir / RENDER_DIRNAME / "text"
    render_audio.mkdir(parents=True, exist_ok=True)
    render_text.mkdir(parents=True, exist_ok=True)

    width = 4 if len(chapters) > 999 else 3

    # Render orphan cleanup up front (not after the loop): a shorter script
    # must not leave stale chNNN files behind even when this run aborts.
    stale_render = remove_stale_chapter_files(render_audio, len(chapters), ".mp3")
    stale_render += remove_stale_chapter_files(render_text, len(chapters), ".json")

    def _synth_fn(chapter_index: int) -> Callable[[Sentence], np.ndarray]:
        def _synth(sentence: Sentence) -> np.ndarray:
            entry = cast.get(sentence.speaker)
            if not isinstance(entry, dict):
                raise ValueError(
                    f"chapter {chapter_index}: no voice for speaker "
                    f"'{sentence.speaker}' in {CAST_FILENAME}"
                )
            speaker_voice = entry.get("voice", voice)
            try:
                speaker_speed = float(entry.get("speed", speed))
            except (TypeError, ValueError) as exc:
                raise ValueError(
                    f"chapter {chapter_index}: bad speed for speaker "
                    f"'{sentence.speaker}' in {CAST_FILENAME}"
                ) from exc
            audio = get_or_synth(
                tts, sentence.text, speaker_voice, speaker_speed, VOICE_PITCH, cache_dir
            )
            return np.asarray(audio, dtype=np.float32)

        return _synth

    log_lines = [
        f"build start: book {script.book_id} source {Path(source).name} "
        f"strict={strict} chapters={len(chapters)}",
        f"script: {'redrafted' if script.redrafted else 'fresh'} "
        f"({len(chapters)} chapters)",
        f"cast: narrator voice={voice} speed={speed} "
        f"engine_version={tts.engine_version}",
    ]

    timed: dict[int, ChapterFile] = {}
    audio_paths: dict[int, Path] = {}
    rendered = skipped = 0
    rendered_this_run = 0
    failed: list[int] = []
    failure_reasons: dict[int, str] = {}

    columns = [
        TextColumn("{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        TimeRemainingColumn(),
    ]
    hook = fail_after if fail_after is not None and fail_after >= 1 else None
    with Progress(*columns, disable=not show_progress) as progress:
        task_id = progress.add_task("Rendering chapters", total=len(chapters))
        for chapter in chapters:
            index = chapter.chapter
            stem = f"ch{index:0{width}d}"
            progress.update(
                task_id, description=f"[{index}/{len(chapters)}] {chapter.title}"
            )
            mp3_path = render_audio / f"{stem}.mp3"
            json_path = render_text / f"{stem}.json"
            fingerprint = _fingerprint(cast, tts, chapter)
            hit = chapter_up_to_date(chapter, json_path, mp3_path, fingerprint)
            if hit is not None:
                timed[index] = hit
                audio_paths[index] = mp3_path
                skipped += 1
                log_lines.append(f"chapter {index} {chapter.title}: skipped (up-to-date)")
                progress.advance(task_id)
                continue
            try:
                assembled = assemble_chapter(chapter, _synth_fn(index))
                loud = apply_loudness_gain(assembled.pcm)
                to_encode = AssembledChapter(
                    pcm=loud,
                    timings=assembled.timings,
                    duration_ms=assembled.duration_ms,
                    sample_count=assembled.sample_count,
                    sample_rate=assembled.sample_rate,
                )
                try:
                    encode_assembled_chapter(
                        to_encode, mp3_path, chapter_label=stem
                    )
                except Exception:
                    mp3_path.unlink(missing_ok=True)
                    raise
                timed_chapter = _with_timings(
                    chapter, assembled.timings, assembled.duration_ms
                )
                payload = dict(timed_chapter.to_dict())
                payload[RENDER_FINGERPRINT_KEY] = fingerprint
                _write_json(json_path, payload)
                timed[index] = timed_chapter
                audio_paths[index] = mp3_path
                rendered += 1
                rendered_this_run += 1
                sentences = len(timed_chapter.sentences_in_order())
                log_lines.append(
                    f"chapter {index} {chapter.title}: rendered "
                    f"({assembled.duration_ms} ms, {sentences} sentences)"
                )
            except Exception as exc:  # chapter-level: strict aborts, else record
                reason = f"{type(exc).__name__}: {exc}"
                if strict:
                    log_lines.append(f"chapter {index} {chapter.title}: FAILED {reason}")
                    log_lines.append(f"build aborted (strict): chapter {index} failed")
                    _append_log(work_dir, log_lines)
                    raise BuildError(f"chapter {index} failed: {exc}") from exc
                failed.append(index)
                failure_reasons[index] = reason
                log_lines.append(f"chapter {index} {chapter.title}: FAILED {reason}")
            progress.advance(task_id)
            if hook is not None and rendered_this_run >= hook:
                log_lines.append(
                    f"build aborted: simulated failure after {hook} "
                    "chapter(s) (test hook; rerun resumes)"
                )
                _append_log(work_dir, log_lines)
                raise BuildError(
                    f"simulated failure after {hook} chapter(s) "
                    "(test hook; rerun resumes)"
                )

    if failed:
        details = ", ".join(f"chapter {i}: {failure_reasons[i]}" for i in failed)
        log_lines.append(f"build failed: {len(failed)} chapter(s) failed: {details}")
        _append_log(work_dir, log_lines)
        raise BuildError(
            f"{len(failed)} chapter(s) failed, no bundle written: {details}"
        )
    ordered_timed = [timed[i] for i in range(1, len(chapters) + 1)]
    ordered_audio = [audio_paths[i] for i in range(1, len(chapters) + 1)]
    bundle_dir = Path(out_dir) if out_dir is not None else Path("bundles") / script.book_id
    write_bundle(ordered_timed, ordered_audio, source, bundle_dir)

    stale_bundle = remove_stale_chapter_files(bundle_dir / "audio", len(chapters), ".mp3")
    stale_bundle += remove_stale_chapter_files(bundle_dir / "text", len(chapters), ".json")

    total_audio_ms = sum(c.duration_ms for c in ordered_timed)
    total_sentences = sum(len(c.sentences_in_order()) for c in ordered_timed)
    wall_seconds = time.monotonic() - wall_start
    audio_seconds = total_audio_ms / 1000.0
    rtf = audio_seconds / wall_seconds if wall_seconds > 0 else 0.0

    removed = [p.name for p in (*stale_render, *stale_bundle)]
    log_lines.append(
        f"orphans removed: {', '.join(removed) if removed else 'none'}"
    )
    log_lines.append(
        f"build done: chapters={len(chapters)} rendered={rendered} "
        f"skipped={skipped} failed=0 total_audio_ms={total_audio_ms} "
        f"wall_seconds={wall_seconds:.1f} real-time factor (RTF): {rtf:.2f}x"
    )
    try:
        _append_log(work_dir, log_lines)
    except OSError as exc:
        raise BuildError(
            f"bundle written to {bundle_dir} but {LOG_FILENAME} unwritable: {exc}"
        ) from exc

    return BuildResult(
        book_id=script.book_id,
        title=script.title,
        work_dir=work_dir,
        bundle_dir=bundle_dir,
        log_path=work_dir / LOG_FILENAME,
        chapters_total=len(chapters),
        rendered=rendered,
        skipped=skipped,
        failed=[],
        total_sentences=total_sentences,
        total_audio_ms=total_audio_ms,
        wall_seconds=wall_seconds,
        rtf=rtf,
        strict=strict,
    )
