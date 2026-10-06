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

"""Bundle dataclasses (SW2): manifest, chapter file, sentence, block, span.

Shapes follow ``docs/03-BundleSpec.md`` sections 3-5. JSON keys are
snake_case exactly as in the spec. Unknown JSON keys are ignored on parse
(Player parity: ``ignoreUnknownKeys``), while missing required fields and
wrong JSON types raise :class:`BundleError`.

Spec v2.0 (IN1, D-082): ``audio``, per-chapter ``audio``/``duration_ms``
and per-sentence ``start_ms``/``end_ms`` are conditional on
``render_state`` (``none``/``partial``/``complete``), so the models below
parse them as optional (absent or null reads as ``None``; the writer omits
them, never null). Whether they must be present is a validator rule
(``bundle/validate.py``), not a shape rule: 1.x bundles still require them
and 2.0 bundles require them exactly for rendered chapters.

Semantic rules (speakers, sids, timings, audio, hashes) live in
``bundle/validate.py``; this module only checks shape.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

#: Spec 2.0 book render states (manifest ``render_state``; IN1, D-082).
RENDER_STATES = ("none", "partial", "complete")
#: Spec 2.0 reserved on-device speakers (every 2.0 sentence uses one).
RESERVED_SPEAKERS = ("narrator", "dialogue")


class BundleError(ValueError):
    """A bundle JSON file has invalid shape (missing field, wrong type)."""


# ---------------------------------------------------------------------------
# Small parsing helpers (shape only, no semantic rules)
# ---------------------------------------------------------------------------


def _require_str(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if not isinstance(value, str):
        raise BundleError(f"missing or invalid required field '{key}' (need string)")
    return value


def _require_int(data: dict[str, Any], key: str) -> int:
    value = data.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise BundleError(f"missing or invalid required field '{key}' (need integer)")
    return value


def _optional_str(data: dict[str, Any], key: str) -> str | None:
    value = data.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise BundleError(f"invalid field '{key}' (need string)")
    return value


def _optional_int(data: dict[str, Any], key: str) -> int | None:
    value = data.get(key)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int):
        raise BundleError(f"invalid field '{key}' (need integer)")
    return value


def _optional_float(data: dict[str, Any], key: str, default: float) -> float:
    value = data.get(key, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BundleError(f"invalid field '{key}' (need number)")
    return float(value)


def _optional_bool(data: dict[str, Any], key: str, default: bool) -> bool:
    value = data.get(key, default)
    if not isinstance(value, bool):
        raise BundleError(f"invalid field '{key}' (need boolean)")
    return value


def _require_list(data: dict[str, Any], key: str) -> list[Any]:
    value = data.get(key)
    if not isinstance(value, list):
        raise BundleError(f"missing or invalid required field '{key}' (need list)")
    return value


# ---------------------------------------------------------------------------
# Manifest (spec section 3)
# ---------------------------------------------------------------------------


@dataclass
class AudioSpec:
    """``manifest.audio``: fixed MP3 description (spec section 2)."""

    format: str = "mp3"
    channels: int = 1
    sample_rate: int = 24000
    bitrate_kbps: int = 64
    cbr: bool = True

    @classmethod
    def from_dict(cls, data: Any) -> AudioSpec:
        if not isinstance(data, dict):
            raise BundleError("invalid field 'audio' (need object)")
        audio = cls()
        if "format" in data:
            audio.format = _require_str(data, "format")
        if "channels" in data:
            audio.channels = _require_int(data, "channels")
        if "sample_rate" in data:
            audio.sample_rate = _require_int(data, "sample_rate")
        if "bitrate_kbps" in data:
            audio.bitrate_kbps = _require_int(data, "bitrate_kbps")
        if "cbr" in data:
            audio.cbr = _optional_bool(data, "cbr", True)
        return audio

    def to_dict(self) -> dict[str, Any]:
        return {
            "format": self.format,
            "channels": self.channels,
            "sample_rate": self.sample_rate,
            "bitrate_kbps": self.bitrate_kbps,
            "cbr": self.cbr,
        }


@dataclass
class Voice:
    """One entry of ``manifest.voices`` (speaker name -> engine voice)."""

    engine: str = ""
    voice: str = ""
    speed: float = 1.0
    pitch: float = 1.0

    @classmethod
    def from_dict(cls, data: Any) -> Voice:
        if not isinstance(data, dict):
            raise BundleError("invalid voice entry (need object)")
        return cls(
            engine=_optional_str(data, "engine") or "",
            voice=_optional_str(data, "voice") or "",
            speed=_optional_float(data, "speed", 1.0),
            pitch=_optional_float(data, "pitch", 1.0),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "engine": self.engine,
            "voice": self.voice,
            "speed": self.speed,
            "pitch": self.pitch,
        }


@dataclass
class SourceInfo:
    """``manifest.source``: original ebook file and its sha256."""

    file: str | None = None
    sha256: str | None = None

    @classmethod
    def from_dict(cls, data: Any) -> SourceInfo:
        if not isinstance(data, dict):
            raise BundleError("invalid field 'source' (need object)")
        return cls(file=_optional_str(data, "file"), sha256=_optional_str(data, "sha256"))

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {}
        if self.file is not None:
            out["file"] = self.file
        if self.sha256 is not None:
            out["sha256"] = self.sha256
        return out


@dataclass
class ChapterEntry:
    """One entry of ``manifest.chapters``.

    Spec v1.2 (D7, range bundles): ``source_index`` is the 1-based chapter
    number in the source book. Absent (``None``) for full builds; present
    for range builds where the bundle ``index`` is renumbered consecutively
    1..K. Never null on the wire (omitted when absent); parsing accepts
    missing (or null) as ``None``.

    Spec v2.0 (IN1, D-082): ``audio`` and ``duration_ms`` are conditional.
    Rendered chapters carry both (as in 1.x); unrendered chapters omit
    both (``None``). ``text`` is always required. The validator (not this
    shape layer) enforces which combination each bundle needs.
    """

    index: int
    title: str
    text: str
    audio: str | None = None
    duration_ms: int | None = None
    source_index: int | None = None

    @classmethod
    def from_dict(cls, data: Any) -> ChapterEntry:
        if not isinstance(data, dict):
            raise BundleError("invalid chapter entry (need object)")
        return cls(
            index=_require_int(data, "index"),
            title=_require_str(data, "title"),
            text=_require_str(data, "text"),
            audio=_optional_str(data, "audio"),
            duration_ms=_optional_int(data, "duration_ms"),
            source_index=_optional_int(data, "source_index"),
        )

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "index": self.index,
            "title": self.title,
            "text": self.text,
        }
        if self.audio is not None:
            out["audio"] = self.audio
        if self.duration_ms is not None:
            out["duration_ms"] = self.duration_ms
        if self.source_index is not None:
            out["source_index"] = self.source_index
        return out


@dataclass
class Manifest:
    """``manifest.json``. Required: spec_version, id, title, type,
    chapters. Everything else is optional (spec section 3).

    Spec v2.0 (IN1, D-082): ``audio`` is conditional (absent for
    ``render_state`` ``none``, present otherwise) and ``render_state``
    itself is required in 2.0 manifests, absent in 1.x. Both parse as
    ``None`` when missing; the validator enforces the combinations.
    """

    spec_version: str
    id: str
    title: str
    type: str
    chapters: list[ChapterEntry] = field(default_factory=list)
    audio: AudioSpec | None = None
    render_state: str | None = None
    author: str | None = None
    language: str | None = None
    source: SourceInfo | None = None
    cover: str | None = None
    voices: dict[str, Voice] = field(default_factory=dict)
    created_at: str | None = None
    generator: str | None = None

    @classmethod
    def from_dict(cls, data: Any) -> Manifest:
        if not isinstance(data, dict):
            raise BundleError("manifest must be a JSON object")
        audio_raw = data.get("audio")
        if audio_raw is None:
            audio: AudioSpec | None = None
        elif not isinstance(audio_raw, dict):
            raise BundleError("missing or invalid required field 'audio' (need object)")
        else:
            audio = AudioSpec.from_dict(audio_raw)
        chapters_raw = _require_list(data, "chapters")
        voices_raw = data.get("voices", {})
        if not isinstance(voices_raw, dict):
            raise BundleError("invalid field 'voices' (need object)")
        voices: dict[str, Voice] = {}
        for name, voice_raw in voices_raw.items():
            voices[name] = Voice.from_dict(voice_raw)
        source_raw = data.get("source")
        return cls(
            spec_version=_require_str(data, "spec_version"),
            id=_require_str(data, "id"),
            title=_require_str(data, "title"),
            type=_require_str(data, "type"),
            audio=audio,
            render_state=_optional_str(data, "render_state"),
            chapters=[ChapterEntry.from_dict(c) for c in chapters_raw],
            author=_optional_str(data, "author"),
            language=_optional_str(data, "language"),
            source=SourceInfo.from_dict(source_raw) if source_raw is not None else None,
            cover=_optional_str(data, "cover"),
            voices=voices,
            created_at=_optional_str(data, "created_at"),
            generator=_optional_str(data, "generator"),
        )

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "spec_version": self.spec_version,
            "id": self.id,
            "title": self.title,
            "type": self.type,
            "chapters": [c.to_dict() for c in self.chapters],
        }
        if self.audio is not None:
            out["audio"] = self.audio.to_dict()
        if self.render_state is not None:
            out["render_state"] = self.render_state
        if self.author is not None:
            out["author"] = self.author
        if self.language is not None:
            out["language"] = self.language
        if self.source is not None:
            out["source"] = self.source.to_dict()
        if self.cover is not None:
            out["cover"] = self.cover
        if self.voices:
            out["voices"] = {name: voice.to_dict() for name, voice in self.voices.items()}
        if self.created_at is not None:
            out["created_at"] = self.created_at
        if self.generator is not None:
            out["generator"] = self.generator
        return out


# ---------------------------------------------------------------------------
# Chapter file (spec sections 4-5)
# ---------------------------------------------------------------------------


@dataclass
class Span:
    """Inline formatting range: character offsets into the sentence text."""

    start: int
    end: int
    style: str

    @classmethod
    def from_dict(cls, data: Any) -> Span:
        if not isinstance(data, dict):
            raise BundleError("invalid span entry (need object)")
        return cls(
            start=_require_int(data, "start"),
            end=_require_int(data, "end"),
            style=_require_str(data, "style"),
        )

    def to_dict(self) -> dict[str, Any]:
        return {"start": self.start, "end": self.end, "style": self.style}


@dataclass
class Sentence:
    """One read-aloud sentence with its MP3 offsets (integer milliseconds).

    MV6 draft fields (script.json only; the bundle ignores them): ``kind``
    (``narration``|``dialogue``), ``confidence`` (``high``|``medium``|``low``;
    narration is always ``high``), and ``quote`` (the stable
    ``(chapter, block, quote)`` key for dialogue, else ``None``). ``speaker``
    holds the RAW surface (``narrator`` for narration, the tag text or
    ``unknown`` for dialogue); resolution to voices happens in MV7 build.
    MV7 adds ``split_pair`` (script.json only): the paragraph-local id the
    two halves of one quote-split sentence share (else ``None``); assembly
    reads it for the short tag pause. Unknown keys are ignored on parse
    (Player parity); missing draft fields default to narration/high/None so
    legacy script and bundle JSON read.

    CP6 (spec v1.1): ``page`` is the 1-based PDF source page (PDF text path
    only). EPUB sentences carry no ``page``: the bundle omits the key
    entirely (absent, never null); parsing accepts missing (or null) as
    ``None`` and validation enforces ``>= 1`` when present.

    Spec v2.0 (IN1, D-082): ``start_ms``/``end_ms`` are conditional.
    Rendered sentences carry both integers (as in 1.x); unrendered
    sentences omit both (``None``). The validator enforces all-or-none
    per chapter file.
    """

    sid: int
    speaker: str
    text: str
    start_ms: int | None = None
    end_ms: int | None = None
    spans: list[Span] = field(default_factory=list)
    kind: str = "narration"
    confidence: str = "high"
    quote: dict[str, int] | None = None
    split_pair: int | None = None
    page: int | None = None

    @classmethod
    def from_dict(cls, data: Any) -> Sentence:
        if not isinstance(data, dict):
            raise BundleError("invalid sentence entry (need object)")
        spans_raw = data.get("spans", [])
        if not isinstance(spans_raw, list):
            raise BundleError("invalid field 'spans' (need list)")
        kind_raw = data.get("kind", "narration")
        kind = kind_raw if kind_raw in ("narration", "dialogue") else "narration"
        conf_raw = data.get("confidence", "high")
        confidence = conf_raw if conf_raw in ("high", "medium", "low") else "high"
        quote: dict[str, int] | None = None
        quote_raw = data.get("quote")
        if isinstance(quote_raw, dict):
            try:
                chapter = quote_raw.get("chapter")
                block = quote_raw.get("block")
                number = quote_raw.get("quote")
                if (
                    isinstance(chapter, int)
                    and not isinstance(chapter, bool)
                    and isinstance(block, int)
                    and not isinstance(block, bool)
                    and isinstance(number, int)
                    and not isinstance(number, bool)
                ):
                    quote = {"chapter": chapter, "block": block, "quote": number}
            except (AttributeError, TypeError):
                quote = None
        pair_raw = data.get("split_pair")
        split_pair = (
            pair_raw
            if isinstance(pair_raw, int) and not isinstance(pair_raw, bool)
            else None
        )
        return cls(
            sid=_require_int(data, "sid"),
            speaker=_require_str(data, "speaker"),
            text=_require_str(data, "text"),
            start_ms=_optional_int(data, "start_ms"),
            end_ms=_optional_int(data, "end_ms"),
            spans=[Span.from_dict(s) for s in spans_raw],
            kind=kind,
            confidence=confidence,
            quote=quote,
            split_pair=split_pair,
            page=_optional_int(data, "page"),
        )

    def to_dict(self, *, include_draft: bool = False) -> dict[str, Any]:
        """Bundle dict; with ``include_draft`` also write MV6 draft fields.

        The bundle format is unchanged (spec law): bundle text JSON carries
        only sid/speaker/start_ms/end_ms/text/spans (plus CP6 ``page`` when
        present: PDF sentences include it, EPUB sentences omit the key
        entirely, never null). ``script.json`` (the draft work file, not
        the bundle) uses ``include_draft=True`` to persist
        ``kind``/``confidence``/``quote``/``split_pair`` per sentence
        (``page`` rides in both: provenance must survive draft and build).
        """
        out: dict[str, Any] = {
            "sid": self.sid,
            "speaker": self.speaker,
            "text": self.text,
        }
        if self.start_ms is not None:
            out["start_ms"] = self.start_ms
        if self.end_ms is not None:
            out["end_ms"] = self.end_ms
        if self.spans:
            out["spans"] = [s.to_dict() for s in self.spans]
        if self.page is not None:
            out["page"] = self.page
        if include_draft:
            out["kind"] = self.kind
            out["confidence"] = self.confidence
            out["quote"] = dict(self.quote) if self.quote is not None else None
            out["split_pair"] = self.split_pair
        return out


BLOCK_TYPES = ("heading", "para", "quote", "break")


@dataclass
class Block:
    """One chapter block (spec section 4 table)."""

    id: int
    type: str
    level: int | None = None
    text: str | None = None
    sentences: list[Sentence] = field(default_factory=list)

    @classmethod
    def from_dict(cls, data: Any) -> Block:
        if not isinstance(data, dict):
            raise BundleError("invalid block entry (need object)")
        block_type = _require_str(data, "type")
        if block_type not in BLOCK_TYPES:
            raise BundleError(f"invalid block type '{block_type}' (need one of {BLOCK_TYPES})")
        sentences_raw = data.get("sentences", [])
        if not isinstance(sentences_raw, list):
            raise BundleError("invalid field 'sentences' (need list)")
        return cls(
            id=_require_int(data, "id"),
            type=block_type,
            level=_optional_int(data, "level"),
            text=_optional_str(data, "text"),
            sentences=[Sentence.from_dict(s) for s in sentences_raw],
        )

    def to_dict(self, *, include_draft: bool = False) -> dict[str, Any]:
        out: dict[str, Any] = {"id": self.id, "type": self.type}
        if self.level is not None:
            out["level"] = self.level
        if self.text is not None:
            out["text"] = self.text
        if self.sentences:
            out["sentences"] = [s.to_dict(include_draft=include_draft) for s in self.sentences]
        return out


@dataclass
class PageEntry:
    """One entry of a PDF page-sync chapter file (spec section 5)."""

    page: int
    start_ms: int

    @classmethod
    def from_dict(cls, data: Any) -> PageEntry:
        if not isinstance(data, dict):
            raise BundleError("invalid page entry (need object)")
        return cls(page=_require_int(data, "page"), start_ms=_require_int(data, "start_ms"))

    def to_dict(self) -> dict[str, Any]:
        return {"page": self.page, "start_ms": self.start_ms}


@dataclass
class ChapterFile:
    """``text/chNNN.json``: EPUB form (``blocks``), PDF page-sync (``pages``),
    or v1.1 PDF text path (both ``blocks`` and ``pages``).

    v1.0 allowed exactly one of ``blocks``/``pages``; v1.1 allows both
    (blocks carry sentences with optional ``page`` provenance, ``pages``
    carries the sync marks). At least one must be present.

    v1.2 (D7, range bundles): ``source_index`` is the 1-based source-book
    chapter this bundle chapter was rendered from. ``chapter`` is always
    the consecutive bundle index 1..K; ``source_index`` is absent (``None``)
    for full builds and present for range builds. Omitted on the wire when
    absent, never null.

    v2.0 (IN1, D-082): ``duration_ms`` is conditional. Rendered chapters
    carry a positive integer (as in 1.x); unrendered chapters omit it
    (``None``). The validator matches its presence against the manifest
    entry and requires sentence timings exactly when it is present.
    """

    spec_version: str
    chapter: int
    title: str
    duration_ms: int | None = None
    blocks: list[Block] | None = None
    pages: list[PageEntry] | None = None
    source_index: int | None = None

    @classmethod
    def from_dict(cls, data: Any) -> ChapterFile:
        if not isinstance(data, dict):
            raise BundleError("chapter file must be a JSON object")
        blocks_raw = data.get("blocks")
        pages_raw = data.get("pages")
        if blocks_raw is None and pages_raw is None:
            raise BundleError("chapter file needs 'blocks' (EPUB) or 'pages' (PDF sync)")
        # v1.1: blocks+pages together is the PDF text path (both allowed).
        blocks = None
        if blocks_raw is not None:
            if not isinstance(blocks_raw, list):
                raise BundleError("invalid field 'blocks' (need list)")
            blocks = [Block.from_dict(b) for b in blocks_raw]
        pages = None
        if pages_raw is not None:
            if not isinstance(pages_raw, list):
                raise BundleError("invalid field 'pages' (need list)")
            pages = [PageEntry.from_dict(p) for p in pages_raw]
        return cls(
            spec_version=_require_str(data, "spec_version"),
            chapter=_require_int(data, "chapter"),
            title=_require_str(data, "title"),
            duration_ms=_optional_int(data, "duration_ms"),
            blocks=blocks,
            pages=pages,
            source_index=_optional_int(data, "source_index"),
        )

    def to_dict(self, *, include_draft: bool = False) -> dict[str, Any]:
        out: dict[str, Any] = {
            "spec_version": self.spec_version,
            "chapter": self.chapter,
            "title": self.title,
        }
        if self.duration_ms is not None:
            out["duration_ms"] = self.duration_ms
        if self.source_index is not None:
            out["source_index"] = self.source_index
        if self.blocks is not None:
            out["blocks"] = [b.to_dict(include_draft=include_draft) for b in self.blocks]
        if self.pages is not None:
            out["pages"] = [p.to_dict() for p in self.pages]
        return out

    def sentences_in_order(self) -> list[Sentence]:
        """All sentences in document order (across blocks)."""
        ordered: list[Sentence] = []
        for block in self.blocks or []:
            ordered.extend(block.sentences)
        return ordered
