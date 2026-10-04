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

"""Bundle validation (SW2): every rule in ``docs/03-BundleSpec.md`` section 7.

Entry point :func:`validate_bundle` returns a :class:`ValidationResult`
(errors list) so the later ``scribe validate`` CLI and the bundle writer's
fail-loud check can reuse it. This module only CHECKS timings (they are
built from one continuous buffer in SW7); it never synthesizes or encodes.

Audio probing runs ``ffprobe`` as a subprocess (never linked), consistent
with the codebase rule. The ``probe`` parameter of :func:`validate_bundle`
is a seam for tests (the shared ``spec/fixtures/`` MP3s are 0-byte
placeholders, so fixture tests inject a stub while dedicated tests exercise
the real ffprobe path on generated MP3s).
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import unicodedata
from collections.abc import Callable, Collection
from dataclasses import dataclass, field
from pathlib import Path

from bundle.models import AudioSpec, BundleError, ChapterEntry, ChapterFile, Manifest, Sentence

DURATION_TOLERANCE_MS = 50
STREAM_BITRATE_TOLERANCE_BPS = 2000
FORMAT_BITRATE_TOLERANCE_BPS = 8000
ALLOWED_SAMPLE_RATES = (24000, 22050)
ALLOWED_SPAN_STYLES = ("italic", "bold")
#: Bundle contract versions accepted (spec v1.1 additive: new writes "1.1").
ALLOWED_SPEC_VERSIONS = ("1.0", "1.1")

FFPROBE_HELP = "Install ffmpeg (provides ffprobe), then re-run: winget install ffmpeg"


@dataclass
class ValidationResult:
    """Structured outcome of :func:`validate_bundle` (errors list)."""

    errors: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        """True when the bundle passed every check."""
        return not self.errors


@dataclass
class AudioProbe:
    """Measured properties of one chapter MP3 (from ffprobe or a test stub)."""

    codec: str
    channels: int
    sample_rate: int
    bit_rate_bps: int | None
    bit_rate_from_stream: bool
    duration_ms: int | None
    cbr_frames: bool


class AudioProbeError(Exception):
    """ffprobe is missing, the file is unreadable, or has no audio stream.

    Every message starts with ``ffprobe`` so callers can report it bare
    (``<file>: <message>``) with a single unified prefix.
    """


def _run_ffprobe(ffprobe: str, args: list[str], timeout_s: int) -> subprocess.CompletedProcess[str]:
    """Run one ffprobe command; map OS/timeout failures to AudioProbeError."""
    try:
        return subprocess.run(
            [ffprobe, *args],
            capture_output=True,
            text=True,
            timeout=timeout_s,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        # TimeoutExpired is not an OSError, so it needs its own handler.
        raise AudioProbeError(
            f"ffprobe failed: timed out after {timeout_s}s ({FFPROBE_HELP})"
        ) from exc
    except OSError as exc:
        raise AudioProbeError(f"ffprobe failed: could not run ffprobe: {exc}") from exc


def probe_audio_ffprobe(path: Path) -> AudioProbe:
    """Probe one MP3 via ffprobe subprocesses (stream info + frame sizes)."""
    ffprobe = shutil.which("ffprobe")
    if ffprobe is None:
        raise AudioProbeError(f"ffprobe not found on PATH ({FFPROBE_HELP})")
    info_raw = _run_ffprobe(
        ffprobe,
        [
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=codec_name,channels,sample_rate,bit_rate",
            "-show_entries",
            "format=duration,bit_rate",
            "-of",
            "json",
            str(path),
        ],
        60,
    )
    if info_raw.returncode != 0:
        detail = (info_raw.stderr or "").strip().splitlines()
        raise AudioProbeError(f"ffprobe failed: {detail[0] if detail else 'unknown error'}")
    try:
        info = json.loads(info_raw.stdout)
    except json.JSONDecodeError as exc:
        raise AudioProbeError(f"ffprobe output not JSON: {exc}") from exc
    streams = info.get("streams", [])
    if not streams:
        raise AudioProbeError("ffprobe found no audio stream")
    stream = streams[0]
    fmt = info.get("format", {})
    try:
        channels = int(stream["channels"])
        sample_rate = int(stream["sample_rate"])
    except (KeyError, TypeError, ValueError) as exc:
        raise AudioProbeError(f"ffprobe stream info unreadable: {exc}") from exc
    bit_rate: int | None = None
    from_stream = False
    for source, is_stream in ((stream.get("bit_rate"), True), (fmt.get("bit_rate"), False)):
        try:
            bit_rate = int(source) if source is not None else None
        except (TypeError, ValueError):
            bit_rate = None
        if bit_rate is not None:
            from_stream = is_stream
            break
    duration_ms: int | None = None
    try:
        duration_ms = round(float(fmt["duration"]) * 1000)
    except (KeyError, TypeError, ValueError):
        duration_ms = None
    cbr_frames = _check_cbr_frames(ffprobe, path)
    return AudioProbe(
        codec=str(stream.get("codec_name", "")),
        channels=channels,
        sample_rate=sample_rate,
        bit_rate_bps=bit_rate,
        bit_rate_from_stream=from_stream,
        duration_ms=duration_ms,
        cbr_frames=cbr_frames,
    )


def _check_cbr_frames(ffprobe: str, path: Path) -> bool:
    """True when all MP3 frame sizes are (near-)constant, as CBR requires.

    CBR MP3 frames have identical size (up to 1 byte of padding); VBR
    frames vary widely. Raises :class:`AudioProbeError` when sizes cannot
    be read at all.
    """
    packets_raw = _run_ffprobe(
        ffprobe,
        [
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "packet=size",
            "-of",
            "json",
            str(path),
        ],
        120,
    )
    if packets_raw.returncode != 0:
        raise AudioProbeError("ffprobe failed: could not read audio packets")
    try:
        packets = json.loads(packets_raw.stdout).get("packets", [])
        sizes = [int(p["size"]) for p in packets]
    except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
        raise AudioProbeError(f"ffprobe packet output unreadable: {exc}") from exc
    if not sizes:
        raise AudioProbeError("ffprobe found no audio packets")
    return max(sizes) - min(sizes) <= 1


ProbeFn = Callable[[Path], AudioProbe]


def validate_bundle(
    bundle_dir: str | Path,
    *,
    probe: ProbeFn | None = None,
    known_voices: Collection[str] | None = None,
) -> ValidationResult:
    """Validate a bundle directory against the spec; collect every error.

    Every error names the file and the rule broken, e.g.
    ``text/ch002.json: sentence 14 overlaps sentence 13``.

    :param known_voices: optional allow-list for the manifest voices map
        (MV8). When given, each voices entry must name a voice in it;
        when ``None`` only the shape contract is checked (non-empty
        engine/voice, speed and pitch above 0). ``scribe validate`` runs
        shape-only (offline, no engine); ``build`` validates the cast
        against the engine's real list before writing, so written bundles
        satisfy both. Unused voices are never an error.
    """
    result = ValidationResult()
    probe_fn: ProbeFn = probe if probe is not None else probe_audio_ffprobe
    root = Path(bundle_dir)
    manifest = _load_manifest(root, result)
    if manifest is None:
        return result
    _validate_manifest_declarations(manifest, result)
    _validate_voices(manifest, result, known_voices=known_voices)
    _validate_referenced_files(root, manifest, result)
    _validate_source(root, manifest, result)
    for entry in manifest.chapters:
        _validate_chapter(root, manifest, entry, probe_fn, result)
    return result


# ---------------------------------------------------------------------------
# Manifest loading and manifest-level checks
# ---------------------------------------------------------------------------


def _read_text_file(path: Path, label: str, errors: list[str]) -> str | None:
    """Read a file as strict UTF-8 and check NFC; report problems, else text."""
    try:
        raw = path.read_bytes()
    except OSError as exc:
        errors.append(f"{label}: cannot read file: {exc.strerror or exc}")
        return None
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        errors.append(f"{label}: not valid UTF-8: {exc}")
        return None
    if unicodedata.normalize("NFC", text) != text:
        errors.append(f"{label}: text is not NFC-normalized (normalize to NFC before writing)")
        return None
    return text


def _load_manifest(root: Path, result: ValidationResult) -> Manifest | None:
    path = root / "manifest.json"
    if not path.is_file():
        result.errors.append(f"manifest.json: file missing in {root}")
        return None
    text = _read_text_file(path, "manifest.json", result.errors)
    if text is None:
        return None
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        result.errors.append(f"manifest.json: invalid JSON: {exc}")
        return None
    try:
        manifest = Manifest.from_dict(data)
    except BundleError as exc:
        result.errors.append(f"manifest.json: {exc}")
        return None
    for key in ("spec_version", "id", "title", "type"):
        if not getattr(manifest, key).strip():
            result.errors.append(f"manifest.json: missing required field '{key}' (blank)")
    if manifest.spec_version not in ALLOWED_SPEC_VERSIONS:
        result.errors.append(
            f"manifest.json: spec_version {manifest.spec_version!r} "
            f"must be one of {list(ALLOWED_SPEC_VERSIONS)}"
        )
    return manifest


def _validate_manifest_declarations(manifest: Manifest, result: ValidationResult) -> None:
    if not manifest.chapters:
        result.errors.append("manifest.json: no chapters listed")
    audio = manifest.audio
    if audio.format != "mp3":
        result.errors.append(f"manifest.json: audio.format must be 'mp3', got '{audio.format}'")
    if audio.channels != 1:
        result.errors.append(
            f"manifest.json: audio.channels must be 1 (mono), got {audio.channels}"
        )
    if audio.sample_rate not in ALLOWED_SAMPLE_RATES:
        result.errors.append(
            "manifest.json: audio.sample_rate must be one of "
            f"{list(ALLOWED_SAMPLE_RATES)}, got {audio.sample_rate}"
        )
    if audio.bitrate_kbps != 64:
        result.errors.append(
            f"manifest.json: audio.bitrate_kbps must be 64, got {audio.bitrate_kbps}"
        )
    if not audio.cbr:
        result.errors.append("manifest.json: audio.cbr must be true (constant bitrate required)")
    prev_index: int | None = None
    for entry in manifest.chapters:
        if entry.index <= 0:
            result.errors.append(
                f"manifest.json: chapter entry has non-positive index {entry.index}"
            )
        elif prev_index is not None and entry.index <= prev_index:
            result.errors.append(
                "manifest.json: chapter indices not strictly increasing: "
                f"{prev_index} then {entry.index}"
            )
        else:
            prev_index = entry.index
        if not entry.title.strip():
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing required field 'title' (blank)"
            )
        if not entry.audio.strip():
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing required field 'audio' (blank)"
            )
        if not entry.text.strip():
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing required field 'text' (blank)"
            )
        if entry.duration_ms <= 0:
            result.errors.append(
                f"manifest.json: chapter {entry.index} has "
                f"non-positive duration_ms {entry.duration_ms}"
            )


def _validate_voices(
    manifest: Manifest,
    result: ValidationResult,
    *,
    known_voices: Collection[str] | None,
) -> None:
    """Check every manifest voices entry (MV8, spec sections 3-4/7).

    Shape contract (always): non-blank speaker key, non-blank engine,
    non-blank voice, speed and pitch above 0. Membership (only when
    ``known_voices`` is given): the voice id must be in the allow-list.
    Unused voices are never an error (plan explicit): only malformed or
    unknown voice names fail. Every error names ``manifest.json``, the
    dotted key, and the rule.
    """
    for name, voice in manifest.voices.items():
        if not isinstance(name, str) or not name.strip():
            result.errors.append(
                f"manifest.json: voices: speaker key must be a non-empty string (got {name!r})"
            )
            continue
        if not isinstance(voice.engine, str) or not voice.engine.strip():
            result.errors.append(
                f"manifest.json: voices.{name}.engine: must be a non-empty string "
                f"(got {voice.engine!r})"
            )
        if not isinstance(voice.voice, str) or not voice.voice.strip():
            result.errors.append(
                f"manifest.json: voices.{name}.voice: unknown voice "
                f"{voice.voice!r} (must name a real Kokoro voice)"
            )
        elif known_voices is not None and voice.voice not in known_voices:
            result.errors.append(
                f"manifest.json: voices.{name}.voice: unknown voice "
                f"{voice.voice!r} (must be a real Kokoro voice)"
            )
        if (
            isinstance(voice.speed, bool)
            or not isinstance(voice.speed, (int, float))
            or not voice.speed > 0
        ):
            result.errors.append(
                f"manifest.json: voices.{name}.speed: must be a number above 0 "
                f"(got {voice.speed!r})"
            )
        if (
            isinstance(voice.pitch, bool)
            or not isinstance(voice.pitch, (int, float))
            or not voice.pitch > 0
        ):
            result.errors.append(
                f"manifest.json: voices.{name}.pitch: must be a number above 0 "
                f"(got {voice.pitch!r})"
            )


def _validate_referenced_files(root: Path, manifest: Manifest, result: ValidationResult) -> None:
    for entry in manifest.chapters:
        if entry.audio and not (root / entry.audio).is_file():
            result.errors.append(
                f"manifest.json: chapter {entry.index} audio file missing {entry.audio}"
            )
        if entry.text and not (root / entry.text).is_file():
            result.errors.append(
                f"manifest.json: chapter {entry.index} text file missing {entry.text}"
            )
    if manifest.cover and not (root / manifest.cover).is_file():
        result.errors.append(f"manifest.json: cover file missing {manifest.cover}")


def _validate_source(root: Path, manifest: Manifest, result: ValidationResult) -> None:
    source = manifest.source
    if source is None or source.file is None:
        return
    path = root / source.file
    if not path.is_file():
        result.errors.append(f"{source.file}: file missing (listed in manifest source)")
        return
    if source.sha256 is None:
        return
    if len(source.sha256) != 64 or any(c not in "0123456789abcdefABCDEF" for c in source.sha256):
        result.errors.append("manifest.json: source sha256 is not a 64-char hex string")
        return
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest.lower() != source.sha256.lower():
        result.errors.append(
            f"{source.file}: sha256 mismatch: expected {source.sha256}, got {digest}"
        )


# ---------------------------------------------------------------------------
# Chapter checks (spec sections 4-6)
# ---------------------------------------------------------------------------


def _validate_chapter(
    root: Path,
    manifest: Manifest,
    entry: ChapterEntry,
    probe_fn: ProbeFn,
    result: ValidationResult,
) -> None:
    label = entry.text or f"chapter {entry.index}"
    text_path = root / entry.text if entry.text else None
    chapter: ChapterFile | None = None
    if text_path is not None and text_path.is_file():
        chapter = _load_chapter(text_path, label, result)
    if chapter is not None:
        _validate_chapter_content(label, chapter, entry, manifest, result)
    audio_path = root / entry.audio if entry.audio else None
    if audio_path is not None and audio_path.is_file():
        _validate_audio(
            entry.audio, audio_path, manifest.audio, entry.duration_ms, probe_fn, result
        )


def _load_chapter(path: Path, label: str, result: ValidationResult) -> ChapterFile | None:
    text = _read_text_file(path, label, result.errors)
    if text is None:
        return None
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        result.errors.append(f"{label}: invalid JSON: {exc}")
        return None
    try:
        return ChapterFile.from_dict(data)
    except BundleError as exc:
        result.errors.append(f"{label}: {exc}")
        return None


def _is_nfc(text: str) -> bool:
    return unicodedata.normalize("NFC", text) == text


def _validate_chapter_content(
    label: str,
    chapter: ChapterFile,
    entry: ChapterEntry,
    manifest: Manifest,
    result: ValidationResult,
) -> None:
    if chapter.chapter != entry.index:
        result.errors.append(
            f"{label}: chapter is {chapter.chapter}, manifest lists index {entry.index}"
        )
    if chapter.spec_version not in ALLOWED_SPEC_VERSIONS:
        result.errors.append(
            f"{label}: spec_version {chapter.spec_version!r} "
            f"must be one of {list(ALLOWED_SPEC_VERSIONS)}"
        )
    if chapter.duration_ms <= 0:
        result.errors.append(f"{label}: non-positive duration_ms {chapter.duration_ms}")
    drift = abs(chapter.duration_ms - entry.duration_ms)
    if drift > DURATION_TOLERANCE_MS:
        result.errors.append(
            f"{label}: duration_ms {chapter.duration_ms} differs from manifest "
            f"duration_ms {entry.duration_ms} by {drift} ms "
            f"(tolerance {DURATION_TOLERANCE_MS} ms)"
        )
    if chapter.pages is not None and chapter.blocks is None:
        _validate_pages(label, chapter, result)
        return
    if not _is_nfc(chapter.title):
        result.errors.append(f"{label}: title is not NFC-normalized")
    voices = manifest.voices
    sentences: list[Sentence] = []
    for block in chapter.blocks or []:
        if block.type == "heading" and block.level is not None:
            if block.level not in (1, 2, 3):
                result.errors.append(
                    f"{label}: heading block {block.id} has level {block.level}, need 1-3"
                )
        if block.text is not None and not _is_nfc(block.text):
            result.errors.append(f"{label}: block {block.id} text is not NFC-normalized")
        sentences.extend(block.sentences)
    expected_sid = 1
    prev_end: int | None = None
    prev_sid = 0
    for sentence in sentences:
        if not _is_nfc(sentence.text):
            result.errors.append(f"{label}: sentence {sentence.sid} text is not NFC-normalized")
        if sentence.sid != expected_sid:
            result.errors.append(
                f"{label}: sentence sid out of order: expected {expected_sid}, got {sentence.sid}"
            )
        if sentence.speaker not in voices:
            result.errors.append(
                f"{label}: sentence {sentence.sid} has unknown speaker '{sentence.speaker}'"
            )
        if sentence.start_ms < 0:
            result.errors.append(
                f"{label}: sentence {sentence.sid} has negative start_ms {sentence.start_ms}"
            )
        if sentence.start_ms >= sentence.end_ms:
            result.errors.append(
                f"{label}: sentence {sentence.sid} has start_ms {sentence.start_ms} "
                f">= end_ms {sentence.end_ms}"
            )
        if sentence.end_ms > chapter.duration_ms:
            result.errors.append(
                f"{label}: sentence {sentence.sid} end_ms {sentence.end_ms} exceeds "
                f"duration_ms {chapter.duration_ms}"
            )
        if prev_end is None and sentence.start_ms != 0:
            result.errors.append(
                f"{label}: first sentence start_ms is {sentence.start_ms}, expected 0"
            )
        if prev_end is not None and sentence.start_ms < prev_end:
            result.errors.append(f"{label}: sentence {sentence.sid} overlaps sentence {prev_sid}")
        if sentence.page is not None and (
            isinstance(sentence.page, bool) or sentence.page < 1
        ):
            result.errors.append(
                f"{label}: sentence {sentence.sid} has invalid page {sentence.page!r} "
                "(need 1-based source page)"
            )
        _validate_spans(label, sentence, result)
        prev_end = sentence.end_ms if prev_end is None else max(prev_end, sentence.end_ms)
        prev_sid = sentence.sid
        expected_sid += 1
    if chapter.pages is not None:
        _validate_pages_with_blocks(label, chapter, sentences, result)


def _validate_spans(label: str, sentence: Sentence, result: ValidationResult) -> None:
    text_len = len(sentence.text)
    for pos, span in enumerate(sentence.spans):
        if span.style not in ALLOWED_SPAN_STYLES:
            result.errors.append(
                f"{label}: sentence {sentence.sid} span {pos} has unknown style '{span.style}'"
            )
        if span.start < 0 or span.end < 0:
            result.errors.append(
                f"{label}: sentence {sentence.sid} span {pos} has negative offset "
                f"[{span.start}, {span.end}]"
            )
        elif span.start > span.end:
            result.errors.append(
                f"{label}: sentence {sentence.sid} span {pos} has start {span.start} "
                f"> end {span.end}"
            )
        elif span.end > text_len:
            result.errors.append(
                f"{label}: sentence {sentence.sid} span {pos} range [{span.start}, "
                f"{span.end}] exceeds text length {text_len}"
            )


def _validate_pages(label: str, chapter: ChapterFile, result: ValidationResult) -> None:
    """Pure pages-without-blocks (legacy v1.0 option (b)): ordering only."""
    prev_start: int | None = None
    for pos, page in enumerate(chapter.pages or []):
        if page.page < 1:
            result.errors.append(f"{label}: page entry {pos} has non-positive page {page.page}")
        if page.start_ms < 0 or page.start_ms > chapter.duration_ms:
            result.errors.append(
                f"{label}: page entry {pos} start_ms {page.start_ms} outside "
                f"duration_ms {chapter.duration_ms}"
            )
        if prev_start is None and page.start_ms != 0:
            result.errors.append(f"{label}: first page start_ms is {page.start_ms}, expected 0")
        if prev_start is not None and page.start_ms < prev_start:
            result.errors.append(
                f"{label}: page entry {pos} start_ms {page.start_ms} out of order "
                f"(previous {prev_start})"
            )
        prev_start = page.start_ms


def _validate_pages_with_blocks(
    label: str,
    chapter: ChapterFile,
    sentences: list[Sentence],
    result: ValidationResult,
) -> None:
    """v1.1 blocks+pages: sorted marks matching each page's first sentence.

    Rules: non-empty; page numbers 1-based and strictly increasing;
    ``start_ms`` within duration, strictly increasing, first 0; every
    entry equals the ``start_ms`` of its page's first sentence; every
    paged sentence's page appears in ``pages`` (unpageable EPUB sentences
    with ``page=None`` are allowed and skipped).
    """
    pages = list(chapter.pages or [])
    if not pages:
        result.errors.append(f"{label}: pages present but empty (need one entry per page)")
        return
    first_start: dict[int, int] = {}
    for sentence in sentences:
        if sentence.page is None:
            continue
        if sentence.page not in first_start:
            first_start[sentence.page] = sentence.start_ms
    prev_page: int | None = None
    prev_start: int | None = None
    seen: set[int] = set()
    for pos, entry in enumerate(pages):
        if entry.page < 1:
            result.errors.append(
                f"{label}: page entry {pos} has non-positive page {entry.page}"
            )
        if entry.start_ms < 0 or entry.start_ms > chapter.duration_ms:
            result.errors.append(
                f"{label}: page entry {pos} start_ms {entry.start_ms} outside "
                f"duration_ms {chapter.duration_ms}"
            )
        if prev_page is not None and entry.page <= prev_page:
            result.errors.append(
                f"{label}: page entry {pos} page {entry.page} out of order "
                f"(previous {prev_page})"
            )
        if prev_start is None and entry.start_ms != 0:
            result.errors.append(
                f"{label}: first page start_ms is {entry.start_ms}, expected 0"
            )
        if prev_start is not None and entry.start_ms <= prev_start:
            result.errors.append(
                f"{label}: page entry {pos} start_ms {entry.start_ms} out of order "
                f"(previous {prev_start})"
            )
        if entry.page in seen:
            result.errors.append(f"{label}: page entry {pos} duplicates page {entry.page}")
        seen.add(entry.page)
        expected = first_start.get(entry.page)
        if expected is None:
            result.errors.append(
                f"{label}: page entry {pos} page {entry.page} has no sentences"
            )
        elif expected != entry.start_ms:
            result.errors.append(
                f"{label}: page entry {pos} start_ms {entry.start_ms} "
                f"does not match first sentence on page {entry.page} ({expected})"
            )
        prev_page = entry.page
        prev_start = entry.start_ms
    for sentence in sentences:
        if sentence.page is not None and sentence.page not in seen:
            result.errors.append(
                f"{label}: sentence {sentence.sid} page {sentence.page} "
                "missing from pages marks"
            )


# ---------------------------------------------------------------------------
# Audio checks (spec sections 2, 6-7)
# ---------------------------------------------------------------------------


def _validate_audio(
    label: str,
    path: Path,
    expected: AudioSpec,
    duration_ms: int,
    probe_fn: ProbeFn,
    result: ValidationResult,
) -> None:
    try:
        probe = probe_fn(path)
    except AudioProbeError as exc:
        # AudioProbeError messages already start with "ffprobe"; report bare.
        result.errors.append(f"{label}: {exc}")
        return
    except Exception as exc:  # test stubs must not crash validation
        result.errors.append(f"{label}: audio probe failed: {exc}")
        return
    if probe.codec != "mp3":
        result.errors.append(f"{label}: codec is '{probe.codec}', expected 'mp3'")
    if probe.channels != expected.channels:
        result.errors.append(
            f"{label}: channels {probe.channels} do not match manifest audio "
            f"channels {expected.channels}"
        )
    if probe.sample_rate != expected.sample_rate:
        result.errors.append(
            f"{label}: sample_rate {probe.sample_rate} does not match manifest audio "
            f"sample_rate {expected.sample_rate}"
        )
    expected_bps = expected.bitrate_kbps * 1000
    if probe.bit_rate_bps is None:
        result.errors.append(f"{label}: could not determine audio bitrate")
    else:
        tolerance = (
            STREAM_BITRATE_TOLERANCE_BPS
            if probe.bit_rate_from_stream
            else FORMAT_BITRATE_TOLERANCE_BPS
        )
        if abs(probe.bit_rate_bps - expected_bps) > tolerance:
            result.errors.append(
                f"{label}: average bitrate {probe.bit_rate_bps} bps differs from "
                f"expected {expected_bps} bps ({expected.bitrate_kbps} kbps CBR required)"
            )
    if not probe.cbr_frames:
        result.errors.append(f"{label}: MP3 frames vary in size; constant bitrate (CBR) required")
    if probe.duration_ms is None:
        result.errors.append(f"{label}: could not determine MP3 duration")
    else:
        drift = abs(probe.duration_ms - duration_ms)
        if drift > DURATION_TOLERANCE_MS:
            result.errors.append(
                f"{label}: MP3 duration {probe.duration_ms} ms differs from manifest "
                f"duration_ms {duration_ms} by {drift} ms "
                f"(tolerance {DURATION_TOLERANCE_MS} ms)"
            )
