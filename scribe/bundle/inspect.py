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

"""``scribe inspect`` reader (SW9): chapters, speakers, sample sentences.

Reads a bundle with the SW2 models (:class:`bundle.models.Manifest`,
:class:`bundle.models.ChapterFile`) and renders a short human summary:
one line per chapter (index/title/duration), declared speakers with
sentence counts, and the first 1-2 sentences of each chapter as samples.

This is a READER, not a validator: it deliberately does not run
:func:`bundle.validate.validate_bundle`, whose ffprobe audio probe would
reject bundles with placeholder or missing MP3s (like the shared
``spec/fixtures``). Structural problems (missing/invalid manifest or
chapter JSON, index mismatches) raise :class:`InspectError` with every
problem named, so the thin CLI prints them cleanly instead of a
traceback. No network access; no audio decoding.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from bundle.models import BundleError, ChapterFile, Manifest
from draft import format_duration

#: How many opening sentences per chapter are shown as samples.
SAMPLE_SENTENCES = 2


class InspectError(ValueError):
    """A bundle cannot be inspected (missing/invalid manifest or chapters)."""


@dataclass
class ChapterSummary:
    """One chapter's inspect line: duration plus up to two sample texts."""

    index: int
    title: str
    duration_ms: int
    sentence_count: int
    samples: list[str] = field(default_factory=list)


@dataclass
class InspectResult:
    """Outcome of :func:`inspect_bundle` (everything :func:`format_inspect`
    prints). ``speakers`` maps each declared voice to its sentence count,
    in manifest order, with any undeclared speakers appended."""

    bundle_dir: Path
    book_id: str
    title: str
    author: str | None
    total_duration_ms: int
    chapters: list[ChapterSummary] = field(default_factory=list)
    speakers: dict[str, int] = field(default_factory=dict)


def _read_json(path: Path, label: str, errors: list[str]) -> object | None:
    """Parse ``path`` as UTF-8 JSON; on any problem record ``label`` + reason."""
    try:
        raw = path.read_bytes()
    except OSError as exc:
        errors.append(f"{label}: cannot read file: {exc.strerror or exc}")
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        errors.append(f"{label}: invalid JSON: {exc}")
        return None


def inspect_bundle(bundle_dir: Path | str) -> InspectResult:
    """Read ``bundle_dir`` and summarize it; raise :class:`InspectError`.

    Every problem names the file and the rule broken (mirroring the SW2
    validator's style), collected across the manifest and all chapters so
    one broken chapter file does not hide the next.
    """
    root = Path(bundle_dir)
    errors: list[str] = []
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        raise InspectError(f"manifest.json: file missing in {root}")
    data = _read_json(manifest_path, "manifest.json", errors)
    manifest: Manifest | None = None
    if data is not None:
        try:
            manifest = Manifest.from_dict(data)
        except BundleError as exc:
            errors.append(f"manifest.json: {exc}")
    if manifest is None:
        raise InspectError("; ".join(errors) if errors else "manifest.json: unreadable")
    if not manifest.chapters:
        errors.append("manifest.json: no chapters listed")
        raise InspectError("; ".join(errors))

    speakers: dict[str, int] = {name: 0 for name in manifest.voices}
    summaries: list[ChapterSummary] = []
    for entry in manifest.chapters:
        label = entry.text or f"chapter {entry.index}"
        chapter: ChapterFile | None = None
        text_path = root / entry.text if entry.text else None
        if text_path is None or not text_path.is_file():
            errors.append(f"manifest.json: chapter {entry.index} text file missing {label}")
        else:
            raw = _read_json(text_path, label, errors)
            if raw is not None:
                try:
                    chapter = ChapterFile.from_dict(raw)
                except BundleError as exc:
                    errors.append(f"{label}: {exc}")
        if chapter is not None and chapter.chapter != entry.index:
            errors.append(
                f"{label}: chapter is {chapter.chapter}, "
                f"manifest lists index {entry.index}"
            )
            chapter = None
        sentences = chapter.sentences_in_order() if chapter is not None else []
        for sentence in sentences:
            speakers[sentence.speaker] = speakers.get(sentence.speaker, 0) + 1
        title = chapter.title if chapter is not None else entry.title
        duration = entry.duration_ms
        summaries.append(
            ChapterSummary(
                index=entry.index,
                title=title,
                duration_ms=duration,
                sentence_count=len(sentences),
                samples=[s.text for s in sentences[:SAMPLE_SENTENCES]],
            )
        )

    if errors:
        raise InspectError("; ".join(errors))
    return InspectResult(
        bundle_dir=root,
        book_id=manifest.id,
        title=manifest.title,
        author=manifest.author,
        total_duration_ms=sum(s.duration_ms for s in summaries),
        chapters=summaries,
        speakers=speakers,
    )


def format_inspect(result: InspectResult) -> str:
    """Render :class:`InspectResult` as human-readable lines."""
    heading = result.title
    if result.author:
        heading += f" — {result.author}"
    lines = [
        heading,
        f"id: {result.book_id}",
        f"chapters: {len(result.chapters)}, "
        f"total audio: {format_duration(result.total_duration_ms / 1000.0)} "
        f"({result.total_duration_ms} ms)",
    ]
    for summary in result.chapters:
        noun = "sentence" if summary.sentence_count == 1 else "sentences"
        lines.append(
            f"[{summary.index}] {summary.title} — "
            f"{format_duration(summary.duration_ms / 1000.0)} "
            f"({summary.duration_ms} ms), {summary.sentence_count} {noun}"
        )
        if summary.samples:
            for sample in summary.samples:
                lines.append(f'    "{sample}"')
        else:
            lines.append("    (no sentences)")
    lines.append("speakers:")
    for name, count in result.speakers.items():
        noun = "sentence" if count == 1 else "sentences"
        lines.append(f"  {name}: {count} {noun}")
    return "\n".join(lines)
