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

"""Bundle writer: manifest, MP3s, text JSON, source, cover (SW8, CP5 PDF).

Given split chapters with SW7 timings, their encoded MP3s, and the source
EPUB or PDF, writes the bundle layout from ``docs/03-BundleSpec.md`` section 1::

    <bundle>/
      manifest.json
      cover.jpg            # only when the EPUB carries a JPEG cover
      source/book.epub     # byte-identical copy of the original (EPUB books)
      source/book.pdf      # byte-identical copy of the original (PDF books)
      audio/chNNN.mp3      # zero-padded (3 digits, 4 once past 999 chapters)
      text/chNNN.json

Manifest contents: ``spec_version`` ``"1.0"``, deterministic ``id`` (reuses
SW5 :func:`draft.book_id_for_file`, so the same source bytes always yield
the same bundle id and the Player keeps its saved progress), title/author
from the EPUB Dublin Core or the PDF metadata (reuses
:func:`draft.read_book_metadata`), ``type`` ``"epub"`` or ``"pdf"``,
``source`` with relative path + sha256, ``audio``
matching the real SW7 encoding (mono / 24 kHz / 64 kbps / CBR — the rate
and bitrate constants are reused from :mod:`tts.base` / :mod:`audio.encode`,
not restated), ``voices`` with the narrator only (reuses :mod:`text.cast`
constants, plus pitch 1.0), one entry per chapter
(index/title/audio/text/duration_ms), ``created_at`` (UTC ISO-8601,
seconds precision, ``Z`` suffix), ``generator`` (``scribe <version>``).
MV8 multi-voice: :func:`write_bundle` accepts an explicit ``voices`` map
(built by ``build`` from its per-sentence resolution, so the writer never
re-resolves); when omitted it keeps the legacy narrator-only map so older
callers and narration-only books stay valid. Bundle sentence ``speaker``
values are the RESOLVED character keys (``narrator``, a character, or a
``default_*`` generic) — the caller remaps them before calling; the writer
only ships what it is given and lets the validator gate check it.

``created_at`` differs on every run BY DESIGN: SW5's twice-run
byte-identity rule covers ``script.json``/``cast.yaml``/``draft_report.md``
only. Two bundles built from the same source share everything except
``created_at``.

Cover-absent choice: when the EPUB has no JPEG cover, the writer omits the
``cover.jpg`` file AND omits the ``cover`` key from ``manifest.json``
(never ``null``). :meth:`bundle.models.Manifest.to_dict` already drops a
``None`` cover, and :mod:`bundle.validate` treats a missing key and
``None`` identically, so writer and validator agree; the shared
``spec/fixtures/valid-bundle`` fixture (no ``cover`` key) pins the
convention. Cover search order: ebooklib cover items first, then the first
JPEG-sniffed manifest item (magic bytes ``FF D8``); only JPEG bytes are
ever written as ``cover.jpg``.

The writer finishes by running SW2
:func:`bundle.validate.validate_bundle` (reused by import, real ffprobe)
over the written bundle. A failing bundle raises
:class:`BundleWriteError` listing every error — a partial bundle is left on
disk for inspection but must never be treated as valid.
"""

from __future__ import annotations

import json
import shutil
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from audio.encode import BITRATE_KBPS
from bundle.models import AudioSpec, ChapterEntry, ChapterFile, Manifest, SourceInfo, Voice
from bundle.validate import validate_bundle
from draft import book_id_for_file, read_book_metadata
from text.cast import NARRATOR, NARRATOR_ENGINE, NARRATOR_SPEED, NARRATOR_VOICE
from tts.base import SAMPLE_RATE

#: Bundle contract version (spec section 3).
SPEC_VERSION = "1.0"
#: Book types by source suffix (CP5: PDF sources ride the same blocks
#: chapters; the full 1.1 ``pages`` amendment lands in CP6).
BOOK_TYPE_EPUB = "epub"
BOOK_TYPE_PDF = "pdf"
#: Fixed relative paths inside the bundle (forward slashes, spec section 1).
SOURCE_EPUB_REL = "source/book.epub"
SOURCE_PDF_REL = "source/book.pdf"
#: Back-compat alias (EPUB-era callers import this name).
SOURCE_REL = SOURCE_EPUB_REL
COVER_REL = "cover.jpg"
#: Narrator pitch: cast.yaml (SW5) pins speed only, so 1.0 is the default.
VOICE_PITCH = 1.0
#: JPEG start-of-image marker (cover sniffing).
JPEG_MAGIC = b"\xff\xd8"


class BundleWriteError(ValueError):
    """The bundle cannot be written, or the written bundle failed validation."""


@dataclass
class WriteResult:
    """Outcome of :func:`write_bundle` (the bundle passed validation)."""

    bundle_dir: Path
    manifest_path: Path
    book_id: str
    sha256: str
    chapters: int
    cover: bool


def _scribe_version() -> str:
    """Scribe version for ``manifest.generator`` (single source: ``cli``)."""
    try:
        from cli import __version__

        return str(__version__)
    except ImportError:
        return "0.1.0"


def _utc_now_iso() -> str:
    """UTC ISO-8601 with ``Z`` suffix, seconds precision (spec example form)."""
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _write_json(path: Path, data: dict[str, Any]) -> None:
    """Write UTF-8 JSON (deterministic key order, trailing newline)."""
    try:
        text = json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    except (TypeError, ValueError) as exc:
        raise BundleWriteError(f"{path.name}: cannot serialize JSON: {exc}") from exc
    try:
        path.write_text(text, encoding="utf-8")
    except OSError as exc:
        raise BundleWriteError(
            f"{path.name}: cannot write bundle file: {exc.strerror or exc}"
        ) from exc


def _item_bytes(item: Any) -> bytes | None:
    """Raw bytes of one ebooklib item, or ``None`` (text items are str)."""
    try:
        content = item.get_content()
    except Exception:
        return None
    return content if isinstance(content, bytes) else None


def extract_cover(epub_path: Path | str) -> bytes | None:
    """Cover JPEG bytes from an EPUB, or ``None`` when absent/unreadable.

    Search order: ebooklib cover items first, then the first JPEG-sniffed
    manifest item (magic bytes ``FF D8``). Only JPEG bytes are ever
    returned (the bundle file is ``cover.jpg``); a non-JPEG cover item
    falls through to the sniff pass, and anything unreadable yields
    ``None`` instead of failing the build.
    """
    try:
        import ebooklib
        from ebooklib import epub as ebooklib_epub

        book = ebooklib_epub.read_epub(str(epub_path))
        items = list(book.get_items())
        cover_items = list(book.get_items_of_type(ebooklib.ITEM_COVER))
    except Exception:
        return None
    seen: set[int] = set()
    for item in [*cover_items, *items]:
        if id(item) in seen:
            continue
        seen.add(id(item))
        data = _item_bytes(item)
        if data is not None and data[:2] == JPEG_MAGIC:
            return data
    return None


def write_bundle(
    chapters: Sequence[ChapterFile],
    audio_paths: Sequence[Path | str],
    source_file: Path | str,
    out_dir: Path | str,
    *,
    voices: dict[str, Voice] | dict[str, dict[str, Any]] | None = None,
) -> WriteResult:
    """Write a complete bundle and validate it; fail loudly on any problem.

    :param chapters: timed chapters (SW7 output: sentence timings filled,
        ``duration_ms`` set), in order, numbered 1..N. Sentence ``speaker``
        values are shipped as given: MV8 callers pass the RESOLVED
        character keys (``narrator``, a character, or a ``default_*``
        generic); legacy callers pass narrator-only chapters.
    :param audio_paths: one already-encoded CBR MP3 per chapter, same order.
    :param source_file: original EPUB or PDF (copied byte-identical, read
        for id/title/author/cover). PDFs are stored as ``source/book.pdf``
        with manifest ``type`` ``"pdf"`` (CP5; ``pages`` sync marks arrive
        in CP6).
    :param out_dir: bundle root (created; existing files are overwritten).
    :param voices: explicit manifest voices map (MV8 multi-voice path:
        ``name -> Voice`` or plain ``name -> {engine, voice, speed,
        pitch}`` dicts, as built by ``build`` from its resolution). When
        ``None`` (default) the legacy narrator-only map is written.
    :returns: :class:`WriteResult` (the bundle passed ``validate_bundle``).
    :raises BundleWriteError: bad inputs, unwritable output, or the
        written bundle failed validation (every validator error is listed;
        the partial bundle is left on disk for inspection but is NOT valid).
    """
    source = Path(source_file)
    is_pdf = source.suffix.lower() == ".pdf"
    book_type = BOOK_TYPE_PDF if is_pdf else BOOK_TYPE_EPUB
    source_rel = SOURCE_PDF_REL if is_pdf else SOURCE_EPUB_REL
    if not source.is_file():
        kind = "PDF" if is_pdf else "EPUB"
        raise BundleWriteError(f"{kind} not found: {source}")
    if not chapters:
        raise BundleWriteError("no chapters given: nothing to write")
    if len(chapters) != len(audio_paths):
        raise BundleWriteError(
            f"{len(chapters)} chapters but {len(audio_paths)} audio files: "
            "pass one MP3 per chapter, in chapter order"
        )
    mp3s = [Path(p) for p in audio_paths]
    for pos, (chapter, mp3) in enumerate(zip(chapters, mp3s), start=1):
        if chapter.chapter != pos:
            raise BundleWriteError(
                f"chapter at position {pos} is numbered {chapter.chapter}: "
                "pass chapters in order, numbered 1..N"
            )
        if chapter.blocks is None:
            raise BundleWriteError(
                f"chapter {pos}: PDF page-sync chapters are not supported by this bundle writer"
            )
        if chapter.duration_ms <= 0:
            raise BundleWriteError(f"chapter {pos}: non-positive duration_ms {chapter.duration_ms}")
        if not chapter.title.strip():
            raise BundleWriteError(f"chapter {pos}: blank title")
        if not mp3.is_file():
            raise BundleWriteError(f"chapter {pos}: audio file not found: {mp3}")

    try:
        book_id, sha = book_id_for_file(source)
    except OSError as exc:
        raise BundleWriteError(
            f"{source.name}: cannot read source file: {exc.strerror or exc}"
        ) from exc
    title, author = read_book_metadata(source)

    bundle = Path(out_dir)
    audio_dir = bundle / "audio"
    text_dir = bundle / "text"
    source_dir = bundle / "source"
    try:
        audio_dir.mkdir(parents=True, exist_ok=True)
        text_dir.mkdir(parents=True, exist_ok=True)
        source_dir.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise BundleWriteError(
            f"{bundle}: cannot create bundle folders: {exc.strerror or exc}"
        ) from exc

    try:
        shutil.copyfile(source, source_dir / source_rel.split("/")[-1])
    except OSError as exc:
        raise BundleWriteError(
            f"{source.name}: cannot copy source to bundle: {exc.strerror or exc}"
        ) from exc

    cover_rel: str | None = None
    cover_bytes = extract_cover(source) if not is_pdf else None
    if cover_bytes is not None:
        try:
            (bundle / COVER_REL).write_bytes(cover_bytes)
        except OSError as exc:
            raise BundleWriteError(
                f"{COVER_REL}: cannot write cover: {exc.strerror or exc}"
            ) from exc
        cover_rel = COVER_REL

    width = 4 if len(chapters) > 999 else 3
    entries: list[ChapterEntry] = []
    for chapter in chapters:
        stem = f"ch{chapter.chapter:0{width}d}"
        audio_rel = f"audio/{stem}.mp3"
        text_rel = f"text/{stem}.json"
        try:
            shutil.copyfile(mp3s[chapter.chapter - 1], bundle / audio_rel)
        except OSError as exc:
            raise BundleWriteError(
                f"{audio_rel}: cannot copy chapter audio: {exc.strerror or exc}"
            ) from exc
        _write_json(bundle / text_rel, chapter.to_dict())
        entries.append(
            ChapterEntry(
                index=chapter.chapter,
                title=chapter.title,
                audio=audio_rel,
                text=text_rel,
                duration_ms=chapter.duration_ms,
            )
        )

    voices_map: dict[str, Voice]
    if voices is None:
        voices_map = {
            NARRATOR: Voice(
                engine=NARRATOR_ENGINE,
                voice=NARRATOR_VOICE,
                speed=NARRATOR_SPEED,
                pitch=VOICE_PITCH,
            )
        }
    else:
        voices_map = {}
        for name, entry in voices.items():
            if isinstance(entry, Voice):
                voices_map[name] = entry
            elif isinstance(entry, dict):
                voices_map[name] = Voice.from_dict(entry)
            else:
                raise BundleWriteError(
                    f"manifest.json: voices.{name}: invalid voice entry "
                    f"(need object, got {type(entry).__name__})"
                )

    manifest = Manifest(
        spec_version=SPEC_VERSION,
        id=book_id,
        title=title,
        type=book_type,
        audio=AudioSpec(
            format="mp3",
            channels=1,
            sample_rate=SAMPLE_RATE,
            bitrate_kbps=BITRATE_KBPS,
            cbr=True,
        ),
        chapters=entries,
        author=author,
        source=SourceInfo(file=source_rel, sha256=sha),
        cover=cover_rel,
        voices=voices_map,
        created_at=_utc_now_iso(),
        generator=f"scribe {_scribe_version()}",
    )
    manifest_path = bundle / "manifest.json"
    _write_json(manifest_path, manifest.to_dict())

    result = validate_bundle(bundle)
    if not result.ok:
        details = "\n".join(f"  - {error}" for error in result.errors)
        raise BundleWriteError(
            f"written bundle failed validation ({len(result.errors)} error(s)) "
            f"-- do not use {bundle}:\n{details}"
        )
    return WriteResult(
        bundle_dir=bundle,
        manifest_path=manifest_path,
        book_id=book_id,
        sha256=sha,
        chapters=len(chapters),
        cover=cover_rel is not None,
    )
