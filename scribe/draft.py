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

"""``scribe draft`` pipeline (SW5, MV6 multi-voice): EPUB/PDF -> work folder.

Parse (SW3 :func:`extract.clean.extract_epub_chapters`, CP5
:func:`extract.pdf.extract_pdf_chapters` for ``.pdf`` sources) then run MV2-MV4
(dialogue split :func:`text.dialogue.split_chapter_dialogue`, candidates
via :mod:`text.speakers`, attribution :func:`text.attribution
.attribute_quotes`), then write the work folder ``.scribe/<book-id>/`` with:

- ``script.json`` — book metadata plus chapters as SW2
  :class:`bundle.models.ChapterFile` dicts (``include_draft=True``:
  every sentence carries RAW ``speaker`` (``narrator`` for narration,
  the tag surface or ``unknown`` for dialogue), ``confidence``
  (high/medium/low; narration always high), ``kind``
  (narration/dialogue), ``quote`` (the stable
  ``{chapter, block, quote}`` key for dialogue, else null; never sid)
  and ``split_pair`` (the MV7 same-sentence tag link, else null).
  No audio timings yet (SW7). Top-level ``attribution_rules_version``
  pins the MV2-MV4 logic version (see
  :data:`text.attribution.ATTRIBUTION_RULES_VERSION`); build re-drafts
  only when the source hash or this version changes, never for cast edits.
- ``cast.yaml`` — multi-voice cast, merged never clobbered: first draft
  writes :func:`text.cast.draft_cast` from discovery counts (starting
  from :func:`text.cast.default_multivoice_cast`); re-runs merge via
  :func:`text.cast.merge_cast` (your voices/speeds/aliases/overrides
  stay, newcomers appended with auto palette voices).
- ``draft_report.md`` — SW5 chapter list, word counts, dropped elements
  and rough estimate (unchanged).
- ``cast_report.md`` — MV6 characters with line counts, then
  low-confidence lines grouped by chapter with context and the quote key
  to paste into ``overrides``.

Book id construction (deterministic, so rebuilds keep the id and the Player
keeps saved progress)::

    book_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "auloud:book:" + sha256_hex))

where ``sha256_hex`` is the lowercase hex sha256 of the source file bytes.
Same bytes always give the same id; different bytes give a different id.

No timestamps are written anywhere, so running draft twice on the same file
produces byte-identical output. No network access; everything runs locally.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass, field
from pathlib import Path

from bundle.models import Block, ChapterFile, Sentence
from extract.clean import count_words, extract_epub_chapters
from extract.pdf import (
    SHORT_LINE_CHARS,
    PdfQuality,
    ScannedPdfError,
    extract_pdf_with_quality,
)
from text.attribution import ATTRIBUTION_RULES_VERSION, attribute_quotes
from text.cast import (
    default_multivoice_cast,
    draft_cast,
    merge_cast,
    read_cast,
    write_cast,
)
from text.dialogue import DIALOGUE, NARRATION, split_chapter_dialogue
from text.speakers import build_quote_contexts, normalize_name

BOOK_ID_NAMESPACE = uuid.NAMESPACE_URL
BOOK_ID_PREFIX = "auloud:book:"

SCRIPT_FILENAME = "script.json"
CAST_FILENAME = "cast.yaml"
REPORT_FILENAME = "draft_report.md"
CAST_REPORT_FILENAME = "cast_report.md"

# Rough speaking-rate heuristic for the duration estimate: ~150 words/min at
# ~6 characters per word (5 letters + space) is ~900 chars/min, i.e. ~15
# characters per second. This is a guess at how fast the narrator voice
# *speaks*, deliberately separate from the SW0 real-time factor (RTF 1.38),
# which measures how fast the engine *synthesizes* — synthesis speed must
# never be conflated with speaking rate. The estimate is marked rough in the
# report and exists only to sanity-size a build.
EST_CHARS_PER_SEC = 15.0


class DraftError(ValueError):
    """``scribe draft`` cannot proceed (missing file, empty book, ...)."""


def _reason(exc: OSError) -> str:
    """Human-readable cause for an :class:`OSError` (prefers strerror)."""
    return str(exc.strerror or exc)


def sha256_of_file(path: Path | str) -> str:
    """Lowercase hex sha256 of a file's bytes (streamed, EPUBs can be big)."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def book_id_for_sha256(sha256_hex: str) -> str:
    """Deterministic book id: ``uuid5(NAMESPACE_URL, "auloud:book:<hex>")``."""
    return str(uuid.uuid5(BOOK_ID_NAMESPACE, f"{BOOK_ID_PREFIX}{sha256_hex.lower()}"))


def book_id_for_file(path: Path | str) -> tuple[str, str]:
    """``(book_id, sha256_hex)`` for a source file's bytes."""
    sha = sha256_of_file(path)
    return book_id_for_sha256(sha), sha


def read_book_metadata(epub_path: Path | str) -> tuple[str, str | None]:
    """``(title, author)`` from EPUB Dublin Core or PDF metadata.

    Title falls back to the file stem, author to ``None``. Never raises
    for missing metadata — a draft must work on messy real-world files.
    """
    from ebooklib import epub as ebooklib_epub

    path = Path(epub_path)
    fallback = path.stem

    if path.suffix.lower() == ".pdf":
        try:
            import pymupdf

            with pymupdf.open(str(path)) as doc:
                meta = doc.metadata or {}
            title = str(meta.get("title") or "").strip() or fallback
            author = str(meta.get("author") or "").strip() or None
        except Exception:
            title, author = fallback, None
        return title, author

    def _first(values: object) -> str | None:
        if not isinstance(values, (list, tuple)) or not values:
            return None
        first = values[0]
        if isinstance(first, (list, tuple)):
            first = first[0] if first else None
        if not isinstance(first, str):
            return None
        return first.strip() or None

    try:
        book = ebooklib_epub.read_epub(str(path))
        title = _first(book.get_metadata("DC", "title")) or fallback
        author = _first(book.get_metadata("DC", "creator"))
    except Exception:
        title, author = fallback, None
    return title, author


def _sentence_stats(chapter: ChapterFile) -> tuple[int, int, int]:
    """``(sentence_count, word_count, char_count)`` for one split chapter.

    Chars are stripped sentence lengths (spacing is not spoken, so it does
    not count toward the duration estimate).
    """
    sentences = chapter.sentences_in_order()
    words = sum(count_words(s.text) for s in sentences)
    chars = sum(len(s.text.strip()) for s in sentences)
    return len(sentences), words, chars


def estimate_seconds(total_chars: int) -> float:
    """Rough audio seconds for ``total_chars`` at EST_CHARS_PER_SEC."""
    return total_chars / EST_CHARS_PER_SEC


def format_duration(seconds: float) -> str:
    """``H:MM:SS`` (or ``M:SS`` under an hour) for a rough estimate."""
    total = int(round(seconds))
    hours, rest = divmod(total, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"


@dataclass
class DraftResult:
    """Outcome of :func:`run_draft` (paths plus the numbers in the report)."""

    book_id: str
    sha256: str
    title: str
    author: str | None
    work_dir: Path
    script_path: Path
    cast_path: Path
    report_path: Path
    cast_report_path: Path = Path("cast_report.md")
    chapters: list[ChapterFile] = field(default_factory=list)
    drops: list[str] = field(default_factory=list)
    pdf_quality: PdfQuality | None = None  # Set for PDF sources (CP5 report).
    total_sentences: int = 0
    total_words: int = 0
    total_chars: int = 0
    estimated_seconds: float = 0.0
    dialogue_sentences: int = 0
    low_confidence: int = 0


def _write_script(
    path: Path,
    *,
    book_id: str,
    title: str,
    author: str | None,
    source_file: str,
    source_sha256: str,
    chapters: list[ChapterFile],
) -> None:
    """Write script.json deterministically (sorted keys, no timestamps).

    Chapters use ``include_draft=True`` (per-sentence raw speaker,
    confidence, kind, quote key). Top-level ``attribution_rules_version``
    pins the MV2-MV4 logic version for build's staleness check.
    """
    data = {
        "attribution_rules_version": ATTRIBUTION_RULES_VERSION,
        "author": author,
        "book_id": book_id,
        "chapters": [c.to_dict(include_draft=True) for c in chapters],
        "source_file": source_file,
        "source_sha256": source_sha256,
        "title": title,
    }
    path.write_text(
        json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
        encoding="utf-8",
    )


def _apply_attribution(
    book_id: str, extracted: list[ChapterFile]
) -> tuple[list[ChapterFile], dict[str, int], dict[str, str], list[dict]]:
    """Run MV2-MV4 over extracted chapters; return script chapters + discovery.

    Returns ``(chapters, line_counts, gender_hints, low_confidence)`` where
    ``line_counts`` maps raw dialogue surfaces to dialogue-sentence counts
    (``unknown`` excluded: it resolves to generics, never a character),
    ``gender_hints`` maps each surface to its majority per-occurrence hint
    (ties break alphabetically, matching :func:`text.cast.draft_cast`),
    and ``low_confidence`` lists low-confidence quotes with context for the
    cast report (one entry per quote key, in reading order).
    """
    script_chapters: list[ChapterFile] = []
    line_counts: dict[str, int] = {}
    gender_votes: dict[str, dict[str, int]] = {}
    low_confidence: list[dict] = []

    for pos, raw_chapter in enumerate(extracted, start=1):
        dialogue = split_chapter_dialogue(raw_chapter, chapter_index=pos)
        if dialogue.chapter.blocks is None:
            script_chapters.append(dialogue.chapter)
            continue
        contexts = build_quote_contexts(dialogue, book=book_id)
        attributions = attribute_quotes(contexts)
        att_by_key = {(a.chapter, a.block, a.quote): a for a in attributions}

        tagged_by_block: dict[int, list] = {}
        for tagged in dialogue.tagged:
            tagged_by_block.setdefault(tagged.block, []).append(tagged)

        quote_texts: dict[tuple[int, int, int], list[str]] = {}
        quote_sids: dict[tuple[int, int, int], list[int]] = {}
        for tagged in dialogue.tagged:
            if tagged.kind == DIALOGUE and tagged.quote is not None:
                key = (tagged.chapter, tagged.block, tagged.quote)
                quote_texts.setdefault(key, []).append(tagged.text)
                quote_sids.setdefault(key, []).append(tagged.sid)

        new_blocks: list[Block] = []
        for block in dialogue.chapter.blocks or []:
            tagged_list = sorted(
                tagged_by_block.get(block.id, []), key=lambda t: t.sid
            )
            sentences: list[Sentence] = []
            for tagged in tagged_list:
                if tagged.kind == DIALOGUE and tagged.quote is not None:
                    att = att_by_key.get((tagged.chapter, tagged.block, tagged.quote))
                    if att is None:
                        speaker, confidence, gender = "unknown", "low", "unknown"
                    else:
                        speaker, confidence, gender = att.speaker, att.confidence, att.gender
                    quote_key = {
                        "block": tagged.block,
                        "chapter": tagged.chapter,
                        "quote": tagged.quote,
                    }
                    sentences.append(
                        Sentence(
                            sid=tagged.sid,
                            speaker=speaker,
                            start_ms=0,
                            end_ms=0,
                            text=tagged.text,
                            spans=list(tagged.spans),
                            kind=DIALOGUE,
                            confidence=confidence,
                            quote=quote_key,
                            split_pair=tagged.split_pair,
                            page=tagged.page,
                        )
                    )
                    if normalize_name(speaker) and normalize_name(speaker) != "unknown":
                        line_counts[speaker] = line_counts.get(speaker, 0) + 1
                        votes = gender_votes.setdefault(
                            speaker, {"female": 0, "male": 0, "unknown": 0}
                        )
                        hint = gender if gender in ("female", "male") else "unknown"
                        votes[hint] = votes.get(hint, 0) + 1
                else:
                    sentences.append(
                        Sentence(
                            sid=tagged.sid,
                            speaker="narrator",
                            start_ms=0,
                            end_ms=0,
                            text=tagged.text,
                            spans=list(tagged.spans),
                            kind=NARRATION,
                            confidence="high",
                            quote=None,
                            split_pair=tagged.split_pair,
                            page=tagged.page,
                        )
                    )
            new_blocks.append(
                Block(
                    id=block.id,
                    type=block.type,
                    level=block.level,
                    text=block.text,
                    sentences=sentences,
                )
            )
        script_chapters.append(
            ChapterFile(
                spec_version=dialogue.chapter.spec_version,
                chapter=dialogue.chapter.chapter,
                title=dialogue.chapter.title,
                duration_ms=dialogue.chapter.duration_ms,
                blocks=new_blocks,
                pages=list(dialogue.chapter.pages)
                if dialogue.chapter.pages is not None
                else None,
            )
        )

        seen_quotes: set[tuple[int, int, int]] = set()
        for att in attributions:
            if att.confidence != "low":
                continue
            key = (att.chapter, att.block, att.quote)
            if key in seen_quotes:
                continue
            seen_quotes.add(key)
            texts = quote_texts.get(key, [])
            full = "".join(texts).strip()
            sids = quote_sids.get(key, [])
            low_confidence.append(
                {
                    "chapter": att.chapter,
                    "block": att.block,
                    "quote": att.quote,
                    "speaker": att.speaker,
                    "confidence": att.confidence,
                    "rule": att.rule,
                    "text": full,
                    "sids": list(sids),
                }
            )

    gender_hints: dict[str, str] = {}
    for surface, votes in gender_votes.items():
        best = sorted(votes.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]
        gender_hints[surface] = best
    return script_chapters, line_counts, gender_hints, low_confidence


def _write_report(
    path: Path,
    *,
    result: DraftResult,
    source_name: str,
    per_chapter: list[tuple[int, str, int, int, float]],
) -> None:
    """Write draft_report.md (chapter list, counts, drops, rough estimate)."""
    lines = [
        f"# Draft report: {result.title}",
        "",
        f"- Book id: `{result.book_id}`",
        "  (UUIDv5 with namespace `NAMESPACE_URL` over the name "
        "`\"auloud:book:<sha256-hex>\"`, where `<sha256-hex>` is the "
        "source file's sha256 — same bytes always give the same id.)",
        f"- Source: `{source_name}` (sha256 `{result.sha256}`)",
        f"- Author: {result.author or '(unknown)'}",
        f"- Chapters: {len(result.chapters)}, "
        f"sentences: {result.total_sentences}, "
        f"words: {result.total_words}, "
        f"characters: {result.total_chars}",
        f"- Estimated audio: ~{format_duration(result.estimated_seconds)} "
        f"({result.estimated_seconds:.0f} s) — ROUGH, see basis below.",
        "",
        "## Chapters",
        "",
        "| # | Title | Sentences | Words | Est. audio |",
        "| --- | --- | --- | --- | --- |",
    ]
    for index, title, sentences, words, seconds in per_chapter:
        safe_title = title.replace("|", "\\|")
        lines.append(
            f"| {index} | {safe_title} | {sentences} | {words} "
            f"| ~{format_duration(seconds)} |"
        )
    if result.pdf_quality is not None:
        lines += _pdf_quality_lines(result.pdf_quality)
    lines += [
        "",
        "## Dropped elements",
        "",
    ]
    if result.drops:
        lines += [f"- {drop}" for drop in result.drops]
    else:
        lines.append("None — every spine element survived cleaning.")
    lines += [
        "",
        "## Estimation basis (rough)",
        "",
        f"- Heuristic: stripped sentence characters / {EST_CHARS_PER_SEC:.0f} "
        "chars-per-second (~150 words/min at ~6 chars/word).",
        "- This is a guess at speaking rate, NOT a measurement — actual "
        "audio length comes from synthesis (SW7 timings).",
        "- Do not confuse this with the SW0 real-time factor (RTF 1.38 on "
        "CPU): RTF measures how fast the engine *synthesizes* audio, not "
        "how fast the narrator *speaks*.",
        "",
        "Next: review `script.json`, edit `cast.yaml` if needed, then run "
        "`scribe build` (SW9) to render audio.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def _pdf_quality_lines(quality: PdfQuality) -> list[str]:
    """``draft_report.md`` PDF quality section (CP5: counts plus warning)."""
    text_pages = quality.pages - quality.scanned_pages
    lines = [
        "",
        "## PDF quality",
        "",
        f"- Pages: {quality.pages} (text: {text_pages}, "
        f"scanned/image-only: {quality.scanned_pages})",
        f"- Dropped: {quality.header_lines_dropped} header lines, "
        f"{quality.footer_lines_dropped} footer lines, "
        f"{quality.page_numbers_dropped} page numbers, "
        f"{quality.footnotes_dropped} footnotes",
        f"- Short-line ratio: {quality.short_line_ratio:.0%} "
        f"({quality.short_lines}/{quality.total_lines}; "
        f"lines under {SHORT_LINE_CHARS} chars)",
    ]
    if quality.messy:
        reasons = "; ".join(quality.messy_reasons)
        lines.append(f"- Warning: this PDF looks messy - {reasons}.")
    return lines


def _write_cast_report(
    path: Path,
    *,
    result: DraftResult,
    line_counts: dict[str, int],
    gender_hints: dict[str, str],
    merged_cast: dict,
    low_confidence: list[dict],
) -> None:
    """Write cast_report.md (characters by lines, low-confidence by chapter).

    Style follows draft_report.md (header bullets, ``##`` sections, pipe
    tables). Characters table lists raw dialogue surfaces by line count
    (``unknown`` excluded at discovery: it resolves to generics, never a
    character); voice column shows the merged cast voice (user edits kept)
    or the generic fallback for omitted surfaces. Low-confidence section
    groups by chapter (one entry per quote key, in reading order) with the
    quote text, one sentence of context on each side when present, and the
    exact ``overrides`` snippet to paste into ``cast.yaml``.
    """

    total_dialogue = sum(line_counts.values())
    lines = [
        f"# Cast report: {result.title}",
        "",
        f"- Book id: `{result.book_id}`",
        f"- Named dialogue sentences: {total_dialogue} "
        f"(`unknown` excluded: generics, never a character), "
        f"low-confidence quotes: {len(low_confidence)}",
        f"- Characters drafted: {len(merged_cast.get('characters', {}))} "
        f"(top {5} by lines get own voices; the rest resolve to generics)",
        "",
        "## Characters (by lines)",
        "",
        "| Character (raw) | Lines | Gender | Voice |",
        "| --- | --- | --- | --- |",
    ]
    ranked = sorted(line_counts.items(), key=lambda kv: (-kv[1], normalize_name(kv[0])))
    characters = merged_cast.get("characters", {})
    if not isinstance(characters, dict):
        characters = {}
    aliases = merged_cast.get("aliases", {})
    if not isinstance(aliases, dict):
        aliases = {}
    from text.cast import canonicalize_speaker

    for surface, count in ranked:
        hint = gender_hints.get(surface, "unknown")
        canonical = canonicalize_speaker(surface, characters, aliases)
        if canonical is not None and isinstance(characters.get(canonical), dict):
            voice = characters[canonical].get("voice", "?")
            voice_cell = f"`{canonical}` ({voice})"
        else:
            generic = "default_male" if hint == "male" else "default_female"
            entry = merged_cast.get(generic, {})
            voice = entry.get("voice", "?") if isinstance(entry, dict) else "?"
            voice_cell = f"generic `{generic}` ({voice})"
        safe = surface.replace("|", "\\|")
        lines.append(f"| {safe} | {count} | {hint} | {voice_cell} |")
    if not ranked:
        lines.append("| (no dialogue found) | 0 | — | narrator only |")
    known = {normalize_name(s) for s, _ in ranked}
    kept_zero = [k for k in characters if normalize_name(k) not in known]
    if kept_zero:
        kept_line = ", ".join(f"`{k}`" for k in sorted(kept_zero))
        lines += ["", "Kept, no current lines (merge keeps): " + kept_line]
    lines += ["", "## Low-confidence lines by chapter", ""]
    if not low_confidence:
        lines.append("None — every dialogue line attributed high or medium.")
    else:
        by_chapter: dict[int, list[dict]] = {}
        for entry in low_confidence:
            by_chapter.setdefault(entry["chapter"], []).append(entry)
        title_by_chapter = {c.chapter: c.title for c in result.chapters}
        for chapter_idx in sorted(by_chapter):
            title = title_by_chapter.get(chapter_idx, "")
            lines.append(f"### Chapter {chapter_idx}: {title}".rstrip())
            lines.append("")
            chapter_obj = next((c for c in result.chapters if c.chapter == chapter_idx), None)
            ordered = chapter_obj.sentences_in_order() if chapter_obj is not None else []
            sid_to_pos = {s.sid: i for i, s in enumerate(ordered)}
            for entry in sorted(by_chapter[chapter_idx], key=lambda e: (e["block"], e["quote"])):
                key = f"({entry['chapter']}, {entry['block']}, {entry['quote']})"
                lines.append(
                    f"- Quote {key} speaker `{entry['speaker']}` "
                    f"({entry['confidence']}, {entry['rule']}): "
                    f"{entry['text'][:200]}"
                )
                sids = entry.get("sids", [])
                if sids and ordered:
                    first_pos = min(sid_to_pos.get(s, 0) for s in sids)
                    last_pos = max(sid_to_pos.get(s, 0) for s in sids)
                    before = ordered[first_pos - 1].text.strip()[:160] if first_pos > 0 else ""
                    after = (
                        ordered[last_pos + 1].text.strip()[:160]
                        if last_pos + 1 < len(ordered)
                        else ""
                    )
                    if before:
                        lines.append(f"  Context before: {before}")
                    if after:
                        lines.append(f"  Context after: {after}")
                override_snippet = (
                    "- {chapter: "
                    + str(entry["chapter"])
                    + ", block: "
                    + str(entry["block"])
                    + ", quote: "
                    + str(entry["quote"])
                    + ", speaker: <Name>}"
                )
                lines.append("  Override: `" + override_snippet + "`")
            lines.append("")
    lines += [
        "Next: fix names in `cast.yaml` (`aliases` for variants, `overrides` "
        "for single lines above), then run `scribe build` — no re-draft "
        "needed (cast edits never trigger one).",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")


def run_draft(
    epub_path: Path | str, *, work_root: Path | str = Path(".scribe")
) -> DraftResult:
    """Parse, attribute, and write ``<work_root>/<book-id>/``; return the result.

    Every foreseeable failure (missing/unreadable source, unparsable EPUB/PDF,
    uncreatable/unwritable work folder, unreadable existing cast) raises
    :class:`DraftError` with a file+reason message, so the thin CLI renders
    it cleanly without a traceback. Writing is deterministic: same input
    bytes produce byte-identical ``script.json``, ``cast.yaml``,
    ``draft_report.md`` and ``cast_report.md`` (no timestamps anywhere).
    Re-runs merge ``cast.yaml`` (never clobber) via
    :func:`text.cast.merge_cast`.
    """
    source = Path(epub_path)
    kind = "PDF" if source.suffix.lower() == ".pdf" else "EPUB"
    if not source.is_file():
        raise DraftError(f"{kind} not found: {source}")

    try:
        book_id, sha = book_id_for_file(source)
    except OSError as exc:
        raise DraftError(f"{source}: cannot read source file: {_reason(exc)}") from exc
    title, author = read_book_metadata(source)

    pdf_quality: PdfQuality | None = None
    try:
        if kind == "PDF":
            try:
                extracted, pdf_quality = extract_pdf_with_quality(source)
            except ScannedPdfError as exc:
                raise DraftError(str(exc)) from exc
        else:
            extracted = extract_epub_chapters(source)
        if not extracted.chapters:
            raise DraftError(f"{source.name}: no chapters survived extraction")
        chapters, line_counts, gender_hints, low_confidence = _apply_attribution(
            book_id, extracted.chapters
        )
    except DraftError:
        raise
    except Exception as exc:
        raise DraftError(f"{source.name}: cannot parse {kind}: {exc}") from exc
    if not chapters:
        raise DraftError(f"{source.name}: no chapters survived extraction")

    work_dir = Path(work_root) / book_id
    script_path = work_dir / SCRIPT_FILENAME
    cast_path = work_dir / CAST_FILENAME
    report_path = work_dir / REPORT_FILENAME
    cast_report_path = work_dir / CAST_REPORT_FILENAME

    try:
        discovered = draft_cast(line_counts, gender_hints)
    except ValueError as exc:
        raise DraftError(f"{source.name}: cannot draft cast: {exc}") from exc

    per_chapter: list[tuple[int, str, int, int, float]] = []
    total_sentences = total_words = total_chars = 0
    for chapter in chapters:
        sentences, words, chars = _sentence_stats(chapter)
        seconds = estimate_seconds(chars)
        per_chapter.append((chapter.chapter, chapter.title, sentences, words, seconds))
        total_sentences += sentences
        total_words += words
        total_chars += chars
    estimated = estimate_seconds(total_chars)
    dialogue_sentences = sum(
        1 for c in chapters for s in c.sentences_in_order() if s.kind == DIALOGUE
    )

    result = DraftResult(
        book_id=book_id,
        sha256=sha,
        title=title,
        author=author,
        work_dir=work_dir,
        script_path=script_path,
        cast_path=cast_path,
        report_path=report_path,
        cast_report_path=cast_report_path,
        chapters=chapters,
        drops=list(extracted.drops),
        pdf_quality=pdf_quality,
        total_sentences=total_sentences,
        total_words=total_words,
        total_chars=total_chars,
        estimated_seconds=estimated,
        dialogue_sentences=dialogue_sentences,
        low_confidence=len(low_confidence),
    )
    try:
        work_dir.mkdir(parents=True, exist_ok=True)
        _write_script(
            script_path,
            book_id=book_id,
            title=title,
            author=author,
            source_file=source.name,
            source_sha256=sha,
            chapters=chapters,
        )
        if cast_path.is_file():
            try:
                existing = read_cast(cast_path)
            except (OSError, ValueError) as exc:
                raise DraftError(f"{cast_path.name}: cannot read cast: {exc}") from exc
            merged = merge_cast(existing, discovered)
        else:
            merged = merge_cast(default_multivoice_cast(), discovered)
        write_cast(cast_path, merged)
        _write_report(
            report_path, result=result, source_name=source.name, per_chapter=per_chapter
        )
        _write_cast_report(
            cast_report_path,
            result=result,
            line_counts=line_counts,
            gender_hints=gender_hints,
            merged_cast=merged,
            low_confidence=low_confidence,
        )
    except OSError as exc:
        where = str(exc.filename or work_dir)
        raise DraftError(f"{where}: cannot write work folder: {_reason(exc)}") from exc
    return result
