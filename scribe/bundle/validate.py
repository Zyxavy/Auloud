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

import json
import math
import shutil
import subprocess
import unicodedata
from collections.abc import Callable, Collection
from dataclasses import dataclass, field
from pathlib import Path

from bundle.models import (
    RESERVED_SPEAKERS,
    RENDER_STATES,
    AudioSpec,
    BundleError,
    ChapterEntry,
    ChapterFile,
    Manifest,
    RenderFingerprint,
    Sentence,
)
from draft import sha256_of_file

DURATION_TOLERANCE_MS = 50
#: Spec 2.0 part 2 (RN1, D-102): M4A duration agreement. PROVISIONAL until
#: the RN10 beep measurement confirms or loosens it; the measured encoder
#: constant is recorded as manifest ``encoder_offset_ms`` and applied to
#: timings when they are written.
AAC_DURATION_TOLERANCE_MS = 50
STREAM_BITRATE_TOLERANCE_BPS = 2000
FORMAT_BITRATE_TOLERANCE_BPS = 8000
#: Spec 2.0 part 2 (RN1, D-102): AAC average-bitrate band. AAC has no
#: frame-level CBR, so the average wanders with content (a pure tone at
#: the 64k setting probes near 61k); the check catches gross
#: misconfiguration (32k or 128k by mistake), not exact rates.
AAC_BITRATE_TOLERANCE_BPS = 16000
ALLOWED_SAMPLE_RATES = (24000, 22050)
ALLOWED_SPAN_STYLES = ("italic", "bold")
#: Manifest ``audio.format`` values (spec 2.0 part 2; 1.x books always mp3).
AUDIO_FORMATS = ("mp3", "m4a")
#: Roles carrying per-role gain (spec 2.0 part 2, same keys as D-095).
GAIN_ROLES = ("narrator", "dialogue")
#: Bundle contract versions accepted (spec v1.2 additive: range builds write "1.2";
#: spec v2.0 major: unrendered books write "2.0", IN1 D-082).
ALLOWED_SPEC_VERSIONS = ("1.0", "1.1", "1.2", "2.0")
#: 1.x versions (rendered books with required audio and timings).
V1_SPEC_VERSIONS = ("1.0", "1.1", "1.2")

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
    """Measured properties of one chapter audio file (from ffprobe or a stub).

    ``container`` is the ffprobe format name (for example ``mp3`` or
    ``mov,mp4,m4a,3gp,3g2,mj2``); MP3-era stubs leave it blank, which
    the M4A container check treats as "not mp4/m4a".
    """

    codec: str
    channels: int
    sample_rate: int
    bit_rate_bps: int | None
    bit_rate_from_stream: bool
    duration_ms: int | None
    cbr_frames: bool
    container: str = ""


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
    """Probe one chapter audio file via ffprobe (stream info + frame sizes)."""
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
            "format=duration,format_name,bit_rate",
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
    container = str(fmt.get("format_name", "") or "")
    return AudioProbe(
        codec=str(stream.get("codec_name", "")),
        channels=channels,
        sample_rate=sample_rate,
        bit_rate_bps=bit_rate,
        bit_rate_from_stream=from_stream,
        duration_ms=duration_ms,
        cbr_frames=cbr_frames,
        container=container,
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
    _validate_render_state_field(manifest, result)
    return manifest


def _validate_render_state_field(manifest: Manifest, result: ValidationResult) -> None:
    """Spec v2.0 (IN1, D-082): ``render_state`` presence and value.

    1.x bundles must not carry it (absent, never null); 2.0 bundles must
    carry exactly one of ``none``/``partial``/``complete``. Every error
    names ``manifest.json`` and the rule.
    """
    if manifest.spec_version in V1_SPEC_VERSIONS:
        if manifest.render_state is not None:
            result.errors.append(
                f"manifest.json: render_state {manifest.render_state!r} "
                "must be absent in 1.x bundles (2.0 only)"
            )
    elif manifest.spec_version == "2.0":
        if manifest.render_state not in RENDER_STATES:
            result.errors.append(
                f"manifest.json: render_state {manifest.render_state!r} "
                f"must be one of {list(RENDER_STATES)}"
            )


def _audio_format_for_extension(audio_rel: str | None) -> str | None:
    """``mp3``/``m4a`` from a chapter ``audio`` path extension, else ``None``.

    Spec 2.0 part 2: the extension decides the chapter codec (lowercase
    ``.mp3``/``.m4a`` only, matching the lowercase generated-file rule).
    """

    if not audio_rel:
        return None
    suffix = Path(audio_rel).suffix
    if suffix == ".mp3":
        return "mp3"
    if suffix == ".m4a":
        return "m4a"
    return None


def _validate_manifest_declarations(manifest: Manifest, result: ValidationResult) -> None:
    if not manifest.chapters:
        result.errors.append("manifest.json: no chapters listed")
    _validate_manifest_audio(manifest, result)
    prev_index: int | None = None
    for pos, entry in enumerate(manifest.chapters, start=1):
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
        if entry.index != pos:
            result.errors.append(
                f"manifest.json: chapter entry {pos} has index {entry.index}, "
                f"expected {pos} (indices must be consecutive 1..N in bundle order)"
            )
        if entry.source_index is not None and (
            isinstance(entry.source_index, bool) or entry.source_index < 1
        ):
            result.errors.append(
                f"manifest.json: chapter {entry.index} has invalid source_index "
                f"{entry.source_index!r} (need 1-based source chapter)"
            )
        if not entry.title.strip():
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing required field 'title' (blank)"
            )
        if not entry.text.strip():
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing required field 'text' (blank)"
            )
        _validate_chapter_entry_audio(manifest, entry, result)
    _validate_source_indices(manifest, result)
    _validate_audio_format_agreement(manifest, result)
    _validate_device_fields(manifest, result)
    _validate_render_state_consistency(manifest, result)


def _validate_manifest_audio(manifest: Manifest, result: ValidationResult) -> None:
    """Manifest ``audio`` object: required in 1.x, conditional in 2.0.

    Spec v2.0 (IN1, D-082): absent for ``render_state`` ``none``, present
    for ``partial``/``complete``. Declaration values are checked whenever
    the object is present.
    """
    audio = manifest.audio
    if manifest.spec_version in V1_SPEC_VERSIONS:
        if audio is None:
            result.errors.append("manifest.json: missing required field 'audio' (absent)")
            return
    elif manifest.spec_version == "2.0":
        if manifest.render_state == "none":
            if audio is not None:
                result.errors.append(
                    "manifest.json: audio must be absent when render_state is 'none' "
                    "(unrendered books carry no audio)"
                )
            return
        if audio is None:
            result.errors.append(
                f"manifest.json: missing required field 'audio' "
                f"(render_state {manifest.render_state!r} needs it for rendered chapters)"
            )
            return
    elif audio is None:
        return
    if audio is None:
        return
    if audio.format not in AUDIO_FORMATS:
        result.errors.append(
            f"manifest.json: audio.format must be one of {list(AUDIO_FORMATS)}, "
            f"got '{audio.format}'"
        )
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
        result.errors.append(
            "manifest.json: audio.cbr must be true "
            "(constant bitrate for MP3, constrained setting for M4A)"
        )


def _validate_chapter_entry_audio(
    manifest: Manifest, entry: ChapterEntry, result: ValidationResult
) -> None:
    """Chapter entry ``audio``/``duration_ms``: required in 1.x, paired in 2.0.

    A rendered chapter carries both; an unrendered 2.0 chapter omits both
    (never one without the other). 1.x chapters must always be rendered.
    Spec 2.0 part 2: a rendered chapter's ``audio`` path ends in ``.mp3``
    or ``.m4a`` (1.x: always ``.mp3``); the extension decides the codec.
    """
    has_audio = entry.audio is not None and entry.audio.strip() != ""
    has_duration = entry.duration_ms is not None
    if manifest.spec_version in V1_SPEC_VERSIONS:
        if not has_audio:
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing required field 'audio' (blank)"
            )
        elif _audio_format_for_extension(entry.audio) != "mp3":
            result.errors.append(
                f"manifest.json: chapter {entry.index} audio file {entry.audio!r} "
                "must end in .mp3 (m4a audio needs spec 2.0)"
            )
        if not has_duration:
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing required field "
                "'duration_ms' (absent)"
            )
        elif isinstance(entry.duration_ms, bool) or entry.duration_ms <= 0:
            result.errors.append(
                f"manifest.json: chapter {entry.index} has "
                f"non-positive duration_ms {entry.duration_ms}"
            )
        return
    if has_audio and _audio_format_for_extension(entry.audio) is None:
        result.errors.append(
            f"manifest.json: chapter {entry.index} audio file {entry.audio!r} "
            "must end in .mp3 or .m4a (per-chapter format comes from the extension)"
        )
    if has_audio != has_duration:
        if has_audio:
            result.errors.append(
                f"manifest.json: chapter {entry.index} has audio without duration_ms "
                "(rendered chapters need both; unrendered chapters omit both)"
            )
        else:
            result.errors.append(
                f"manifest.json: chapter {entry.index} has duration_ms without audio "
                "(rendered chapters need both; unrendered chapters omit both)"
            )
        return
    if has_duration and (isinstance(entry.duration_ms, bool) or entry.duration_ms <= 0):
        result.errors.append(
            f"manifest.json: chapter {entry.index} has "
            f"non-positive duration_ms {entry.duration_ms}"
        )


def _validate_audio_format_agreement(manifest: Manifest, result: ValidationResult) -> None:
    """Manifest ``audio.format`` must match at least one rendered chapter.

    Spec 2.0 part 2 (RN1, D-102): mixed MP3/M4A books are legal, so the
    manifest value cannot be normative per chapter; per-chapter extension
    plus probe are authoritative. ``none`` books carry no ``audio``
    object, so the rule is vacuous for them.
    """

    audio = manifest.audio
    if audio is None or audio.format not in AUDIO_FORMATS:
        return
    rendered = [e for e in manifest.chapters if e.duration_ms is not None]
    if not rendered:
        return
    chapter_formats = {_audio_format_for_extension(e.audio) for e in rendered}
    chapter_formats.discard(None)
    if chapter_formats and audio.format not in chapter_formats:
        have = ", ".join(sorted(chapter_formats))
        result.errors.append(
            f"manifest.json: audio.format '{audio.format}' matches no rendered chapter "
            f"(chapters are {have}; per-chapter format comes from the audio file extension)"
        )


def _validate_device_fields(manifest: Manifest, result: ValidationResult) -> None:
    """Spec 2.0 part 2 (RN1, D-102): ``gain_db``, ``encoder_offset_ms``,
    ``render_fingerprint`` presence and shape.

    All three are 2.0-only (absent in 1.x, never null). Gain is one
    finite number per role (``gain_db`` name and decibel unit per D-102);
    the offset is an integer (shape-checked at parse); fingerprints live
    on rendered chapters only and pin both roles. Every error names
    ``manifest.json`` and the rule.
    """

    is_20 = manifest.spec_version == "2.0"
    if manifest.gain_db is not None:
        if not is_20:
            result.errors.append(
                "manifest.json: gain_db is 2.0-only (absent in 1.x bundles)"
            )
        else:
            if not manifest.gain_db:
                result.errors.append(
                    "manifest.json: gain_db present but empty (need one number per role)"
                )
            for role, value in manifest.gain_db.items():
                if role not in GAIN_ROLES:
                    result.errors.append(
                        f'manifest.json: gain_db has unknown role "{role}" '
                        "(need narrator and/or dialogue)"
                    )
                elif not math.isfinite(value):
                    result.errors.append(
                        f"manifest.json: gain_db.{role} must be a finite number "
                        f"(got {value!r})"
                    )
    if manifest.encoder_offset_ms is not None and not is_20:
        result.errors.append(
            "manifest.json: encoder_offset_ms is 2.0-only (absent in 1.x bundles)"
        )
    for entry in manifest.chapters:
        fingerprint = entry.render_fingerprint
        if fingerprint is None:
            continue
        if not is_20:
            result.errors.append(
                f"manifest.json: chapter {entry.index} render_fingerprint "
                "is 2.0-only (absent in 1.x bundles)"
            )
            continue
        if entry.duration_ms is None:
            result.errors.append(
                f"manifest.json: chapter {entry.index} carries render_fingerprint "
                "without duration_ms (fingerprints describe rendered chapters only)"
            )
            continue
        _validate_fingerprint(entry, fingerprint, result)


def _validate_fingerprint(
    entry: ChapterEntry, fingerprint: RenderFingerprint, result: ValidationResult
) -> None:
    """Shape of one rendered chapter's ``render_fingerprint`` (spec 2.0.2)."""

    label = f"manifest.json: chapter {entry.index} render_fingerprint"
    if not fingerprint.engine.strip():
        result.errors.append(
            f"{label}.engine must be a non-empty string (namespaced engine id)"
        )
    for role in RESERVED_SPEAKERS:
        voice = fingerprint.voices.get(role)
        if not isinstance(voice, str) or not voice.strip():
            result.errors.append(
                f"{label}.voices missing or blank voice for role '{role}' "
                "(need narrator and dialogue)"
            )
    for role in fingerprint.voices:
        if role not in RESERVED_SPEAKERS:
            result.errors.append(
                f'{label}.voices has unknown role "{role}" (need narrator and dialogue)'
            )
    for role in RESERVED_SPEAKERS:
        speed = fingerprint.speeds.get(role)
        if isinstance(speed, bool) or not isinstance(speed, (int, float)) or not speed > 0:
            result.errors.append(
                f"{label}.speeds missing or invalid speed for role '{role}' "
                "(need a number above 0)"
            )
    for role in fingerprint.speeds:
        if role not in RESERVED_SPEAKERS:
            result.errors.append(
                f'{label}.speeds has unknown role "{role}" (need narrator and dialogue)'
            )
    if not fingerprint.engine_versions:
        result.errors.append(
            f"{label}.engine_versions present but empty "
            "(need one version string per engine used)"
        )
    for key, value in fingerprint.engine_versions.items():
        if not key.strip() or not value.strip():
            result.errors.append(
                f"{label}.engine_versions has a blank engine or version "
                "(need non-empty strings)"
            )


def _validate_render_state_consistency(manifest: Manifest, result: ValidationResult) -> None:
    """Manifest ``render_state`` must agree with the chapters (spec v2.0).

    Per-chapter state is inferred from ``duration_ms`` presence. ``none``
    needs every chapter unrendered, ``complete`` every chapter rendered,
    ``partial`` at least one of each.
    """
    if manifest.spec_version != "2.0" or manifest.render_state not in RENDER_STATES:
        return
    rendered = sum(1 for e in manifest.chapters if e.duration_ms is not None)
    unrendered = len(manifest.chapters) - rendered
    state = manifest.render_state
    if state == "none" and rendered:
        result.errors.append(
            f"manifest.json: render_state 'none' but {rendered} chapter(s) carry "
            "duration_ms (unrendered books omit audio and duration_ms everywhere)"
        )
    elif state == "complete" and unrendered:
        result.errors.append(
            f"manifest.json: render_state 'complete' but {unrendered} chapter(s) omit "
            "duration_ms (rendered books carry audio and duration_ms everywhere)"
        )
    elif state == "partial" and not (rendered and unrendered):
        result.errors.append(
            "manifest.json: render_state 'partial' needs at least one rendered and "
            "one unrendered chapter "
            f"(got {rendered} rendered, {unrendered} unrendered)"
        )


def _validate_source_indices(manifest: Manifest, result: ValidationResult) -> None:
    """Spec v1.2 range rules: ``source_index`` uniqueness and ordering.

    When any entry carries ``source_index`` (range bundle): every entry
    must carry one, values must be unique and strictly increasing in
    bundle order (bundle ``index`` is already consecutive 1..N above).
    Full builds omit the field entirely (no error when all are absent).
    Mixed (some present, some absent) is an error naming the file and rule.
    """
    indices = [e.source_index for e in manifest.chapters]
    if all(v is None for v in indices):
        return
    for entry in manifest.chapters:
        if entry.source_index is None:
            result.errors.append(
                f"manifest.json: chapter {entry.index} missing source_index "
                "(range bundles need source_index on every chapter)"
            )
    present = [v for v in indices if v is not None]
    if len(set(present)) != len(present):
        result.errors.append(
            "manifest.json: duplicate source_index values "
            f"{sorted(present)} (need unique 1-based source chapters)"
        )
    prev: int | None = None
    for entry in manifest.chapters:
        if entry.source_index is None:
            continue
        if prev is not None and entry.source_index <= prev:
            result.errors.append(
                f"manifest.json: source_index {entry.source_index} out of order "
                f"(previous {prev}; need strictly increasing in bundle order)"
            )
        prev = entry.source_index


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
    if manifest.spec_version == "2.0":
        for reserved in RESERVED_SPEAKERS:
            if reserved not in manifest.voices:
                result.errors.append(
                    f"manifest.json: voices missing reserved speaker '{reserved}' "
                    "(2.0 books need both narrator and dialogue)"
                )


def _validate_referenced_files(root: Path, manifest: Manifest, result: ValidationResult) -> None:
    for entry in manifest.chapters:
        # Unrendered 2.0 chapters omit audio: only chapters naming one need
        # the file (1.x always names one; 2.0 rendered chapters name one).
        if entry.audio and entry.audio.strip():
            if manifest.spec_version in V1_SPEC_VERSIONS or entry.duration_ms is not None:
                if not (root / entry.audio).is_file():
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
    digest = sha256_of_file(path)
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
    if (
        audio_path is not None
        and audio_path.is_file()
        and manifest.audio is not None
        and entry.duration_ms is not None
    ):
        _validate_audio(
            entry.audio,
            audio_path,
            manifest.audio,
            entry.duration_ms,
            probe_fn,
            result,
            _audio_format_for_extension(entry.audio) or "mp3",
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
    if chapter.source_index is not None and (
        isinstance(chapter.source_index, bool) or chapter.source_index < 1
    ):
        result.errors.append(
            f"{label}: invalid source_index {chapter.source_index!r} "
            "(need 1-based source chapter)"
        )
    if (entry.source_index is None) != (chapter.source_index is None):
        result.errors.append(
            f"{label}: source_index mismatch: manifest entry has "
            f"{entry.source_index!r}, chapter file has {chapter.source_index!r} "
            "(both present or both absent)"
        )
    elif (
        entry.source_index is not None
        and chapter.source_index is not None
        and entry.source_index != chapter.source_index
    ):
        result.errors.append(
            f"{label}: source_index {chapter.source_index} does not match "
            f"manifest entry source_index {entry.source_index}"
        )
    _validate_chapter_versions(label, chapter, entry, manifest, result)
    if not _validate_chapter_duration_presence(label, chapter, entry, manifest, result):
        return
    if chapter.pages is not None and chapter.blocks is None:
        if manifest.spec_version == "2.0":
            result.errors.append(
                f"{label}: pages without blocks is a 1.x-only shape "
                "(2.0 chapters always carry blocks)"
            )
            return
        _validate_pages(label, chapter, result)
        return
    if chapter.duration_ms is None:
        if chapter.pages is not None:
            result.errors.append(
                f"{label}: pages marks need timings "
                "(unrendered chapters carry blocks without pages)"
            )
            return
        _validate_unrendered_sentences(label, chapter, manifest, result)
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
    _validate_sentence_timings(label, chapter, sentences, voices, manifest, result)
    if chapter.pages is not None:
        _validate_pages_with_blocks(label, chapter, sentences, result)


def _validate_chapter_versions(
    label: str,
    chapter: ChapterFile,
    entry: ChapterEntry,
    manifest: Manifest,
    result: ValidationResult,
) -> None:
    """Chapter ``spec_version`` must be known and match the manifest major.

    1.x chapters mix freely among 1.0/1.1/1.2 (as before); 2.0 chapters
    belong to 2.0 manifests and 1.x manifests never hold 2.0 chapters.
    """
    if manifest.spec_version == "2.0" and chapter.spec_version != "2.0":
        result.errors.append(
            f"{label}: spec_version {chapter.spec_version!r} does not match "
            "manifest spec_version '2.0' (2.0 chapters belong to 2.0 books)"
        )
    elif manifest.spec_version in V1_SPEC_VERSIONS and chapter.spec_version == "2.0":
        result.errors.append(
            f"{label}: spec_version '2.0' does not match manifest "
            f"spec_version {manifest.spec_version!r} (1.x books hold 1.x chapters)"
        )


def _validate_chapter_duration_presence(
    label: str,
    chapter: ChapterFile,
    entry: ChapterEntry,
    manifest: Manifest,
    result: ValidationResult,
) -> bool:
    """Chapter ``duration_ms`` presence must match its manifest entry.

    Returns False when the chapter needs no further checks (the mismatch
    error below already names the file and the rule).
    """
    if chapter.duration_ms is None and entry.duration_ms is None:
        if manifest.spec_version in V1_SPEC_VERSIONS:
            result.errors.append(
                f"{label}: missing required field 'duration_ms' (absent)"
            )
            return False
        return True
    if chapter.duration_ms is not None and entry.duration_ms is not None:
        if chapter.duration_ms <= 0:
            result.errors.append(
                f"{label}: non-positive duration_ms {chapter.duration_ms}"
            )
        tolerance = (
            AAC_DURATION_TOLERANCE_MS
            if _audio_format_for_extension(entry.audio) == "m4a"
            else DURATION_TOLERANCE_MS
        )
        drift = abs(chapter.duration_ms - entry.duration_ms)
        if drift > tolerance:
            result.errors.append(
                f"{label}: duration_ms {chapter.duration_ms} differs from manifest "
                f"duration_ms {entry.duration_ms} by {drift} ms "
                f"(tolerance {tolerance} ms)"
            )
        return True
    if chapter.duration_ms is None:
        result.errors.append(
            f"{label}: chapter file omits duration_ms but the manifest entry has "
            f"{entry.duration_ms} (both present or both absent)"
        )
    else:
        result.errors.append(
            f"{label}: chapter file has duration_ms {chapter.duration_ms} but the "
            "manifest entry omits it (both present or both absent)"
        )
    return False


def _check_sentence_speaker(
    label: str,
    sentence: Sentence,
    voices: dict,
    manifest: Manifest,
    result: ValidationResult,
) -> None:
    """Speaker must exist in ``voices``; 2.0 speakers are reserved."""
    if sentence.speaker not in voices:
        result.errors.append(
            f"{label}: sentence {sentence.sid} has unknown speaker '{sentence.speaker}'"
        )
    elif manifest.spec_version == "2.0" and sentence.speaker not in RESERVED_SPEAKERS:
        result.errors.append(
            f"{label}: sentence {sentence.sid} has speaker '{sentence.speaker}' "
            f"(2.0 books allow only {list(RESERVED_SPEAKERS)})"
        )


def _validate_unrendered_sentences(
    label: str,
    chapter: ChapterFile,
    manifest: Manifest,
    result: ValidationResult,
) -> None:
    """Text checks for chapters without timings (spec v2.0).

    sids run 1..N, speakers are known and reserved, no sentence carries
    timings, spans and pages stay valid. Timing rules are vacuous here.
    """
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
    for sentence in sentences:
        if not _is_nfc(sentence.text):
            result.errors.append(f"{label}: sentence {sentence.sid} text is not NFC-normalized")
        if sentence.sid != expected_sid:
            result.errors.append(
                f"{label}: sentence sid out of order: expected {expected_sid}, got {sentence.sid}"
            )
        _check_sentence_speaker(label, sentence, voices, manifest, result)
        if sentence.start_ms is not None or sentence.end_ms is not None:
            result.errors.append(
                f"{label}: sentence {sentence.sid} carries timings "
                "but the chapter has no duration_ms "
                "(unrendered chapters omit start_ms and end_ms everywhere)"
            )
        if sentence.page is not None and (
            isinstance(sentence.page, bool) or sentence.page < 1
        ):
            result.errors.append(
                f"{label}: sentence {sentence.sid} has invalid page {sentence.page!r} "
                "(need 1-based source page)"
            )
        _validate_spans(label, sentence, result)
        expected_sid += 1


def _validate_sentence_timings(
    label: str,
    chapter: ChapterFile,
    sentences: list[Sentence],
    voices: dict,
    manifest: Manifest,
    result: ValidationResult,
) -> None:
    """Timing rules for chapters carrying ``duration_ms`` (spec section 6).

    Every sentence must carry both timings here (all-or-none with the
    chapter duration); a timed sentence in an untimed chapter is caught by
    :func:`_validate_unrendered_sentences` instead.
    """
    assert chapter.duration_ms is not None
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
        _check_sentence_speaker(label, sentence, voices, manifest, result)
        if sentence.start_ms is None or sentence.end_ms is None:
            result.errors.append(
                f"{label}: sentence {sentence.sid} omits timings "
                "but the chapter has duration_ms "
                f"{chapter.duration_ms} (rendered chapters time every sentence)"
            )
            prev_sid = sentence.sid
            expected_sid += 1
            _validate_spans(label, sentence, result)
            continue
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
    audio_format: str = "mp3",
) -> None:
    """Probe checks for one rendered chapter (spec sections 2, 6-7).

    ``audio_format`` comes from the chapter ``audio`` extension (``mp3``
    or ``m4a``). MP3 chapters keep the CBR frame check; M4A chapters
    check codec ``aac`` in an mp4/m4a container plus the *average*
    bitrate (AAC has no MP3-style CBR frames, so the frame check is
    skipped) against the provisional AAC duration tolerance.
    """
    try:
        probe = probe_fn(path)
    except AudioProbeError as exc:
        # AudioProbeError messages already start with "ffprobe"; report bare.
        result.errors.append(f"{label}: {exc}")
        return
    except Exception as exc:  # test stubs must not crash validation
        result.errors.append(f"{label}: audio probe failed: {exc}")
        return
    is_aac = audio_format == "m4a"
    want_codec = "aac" if is_aac else "mp3"
    if probe.codec != want_codec:
        if is_aac:
            result.errors.append(
                f"{label}: codec is '{probe.codec}', expected 'aac' for .m4a chapters"
            )
        else:
            result.errors.append(f"{label}: codec is '{probe.codec}', expected 'mp3'")
    if is_aac:
        tokens = (probe.container or "").lower().replace(",", " ").split()
        if "m4a" not in tokens and "mp4" not in tokens:
            result.errors.append(
                f"{label}: container '{probe.container}' is not mp4/m4a "
                "(M4A chapters need AAC in an M4A container)"
            )
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
    elif is_aac:
        if abs(probe.bit_rate_bps - expected_bps) > AAC_BITRATE_TOLERANCE_BPS:
            result.errors.append(
                f"{label}: average bitrate {probe.bit_rate_bps} bps differs from "
                f"expected {expected_bps} bps by more than {AAC_BITRATE_TOLERANCE_BPS} bps "
                f"({expected.bitrate_kbps} kbps constrained average required)"
            )
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
    if not is_aac and not probe.cbr_frames:
        result.errors.append(f"{label}: MP3 frames vary in size; constant bitrate (CBR) required")
    duration_tolerance = AAC_DURATION_TOLERANCE_MS if is_aac else DURATION_TOLERANCE_MS
    if probe.duration_ms is None:
        if is_aac:
            result.errors.append(f"{label}: could not determine audio duration")
        else:
            result.errors.append(f"{label}: could not determine MP3 duration")
    else:
        drift = abs(probe.duration_ms - duration_ms)
        if drift > duration_tolerance:
            if is_aac:
                result.errors.append(
                    f"{label}: audio duration {probe.duration_ms} ms differs from manifest "
                    f"duration_ms {duration_ms} by {drift} ms "
                    f"(tolerance {duration_tolerance} ms)"
                )
            else:
                result.errors.append(
                    f"{label}: MP3 duration {probe.duration_ms} ms differs from manifest "
                    f"duration_ms {duration_ms} by {drift} ms "
                    f"(tolerance {duration_tolerance} ms)"
                )
