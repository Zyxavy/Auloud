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

"""Slice 6 UI3: Books scan and Book detail assembly (status derivation only).

Workspace layout (UI3 decision, documented for UI4+):

- ``<workspace>/*.epub|*.pdf`` — source files (recursive scan, skipping
  dot-dirs plus ``.scribe/``, ``bundles/``, ``jobs/``, ``models/``,
  ``logs/`` and the bundle-internal ``source/`` copies, so a bundle's
  ``source/book.epub`` never becomes a second book).
- ``<workspace>/.scribe/<book-id>/`` — draft work folder (``script.json``,
  ``cast.yaml``, ``draft_report.md``, ``cast_report.md``, plus the UI1
  ``.scribe.lock`` and the UI3 ``events.jsonl`` seam described below).
- ``<workspace>/bundles/<bundle-id>/`` — rendered bundles (``manifest.json``).

Book id is the existing draft scheme (``draft.book_id_for_file``: UUIDv5
over the source sha256), so the UI, the CLI and the Player agree. Range
bundles carry a range-aware id (D7); they map back to the book via
``manifest.source.sha256`` when present, else by exact id match.

Status chips (all derived from files; this module never triggers work):

- Drafted: ``script.json`` parses with at least one chapter.
- Cast edited: ``cast.yaml`` mtime newer than ``script.json`` mtime (1 s
  tolerance; a fresh draft writes both together, a hand/UI edit touches
  only the cast — heuristic, documented).
- Rendering: the per-book ``.scribe.lock`` names a live holder (see
  :mod:`lock`; stale locks are ignored). Percent comes from the
  ``events.jsonl`` tail when present (cached+rendered over the script
  sentence total, clamped 0-100); otherwise the chip is bare.
- Valid: any mapped bundle passes :func:`bundle.validate.validate_bundle`.
- On tablet (UI8): the ``.scribe-transfers.json`` record with a matching
  bundle manifest id and ``verified`` true (a rebuild with a new id clears
  the chip; no match means False).

Draft-as-job seam (UI4 owns the machinery): the upload route saves the file
and creates a draft job; progress persists as ``events.jsonl`` in the work
dir using the UI1 key contract
``{event, chapter, sid, cached, rendered, audio_ms, wall_s}`` with
draft-specific event names (``draft_start``/``draft_done``/``draft_failed``).
No cast editing, no transfer record here.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

#: Work-folder root name inside the workspace (mirrors the CLI default).
WORK_DIRNAME = ".scribe"
#: Bundle root name inside the workspace (mirrors the build default).
BUNDLES_DIRNAME = "bundles"
#: Source suffixes listed as books (lowercase compare).
SOURCE_SUFFIXES = frozenset({".epub", ".pdf"})
#: Directory parts never descended into for source scan.
SKIP_DIR_PARTS = frozenset(
    {
        ".scribe",
        "bundles",
        "jobs",
        "models",
        "logs",
        ".git",
        ".venv",
        "__pycache__",
        "node_modules",
        "source",
    }
)
#: Draft seam file inside each work dir (UI1 key contract, draft event names).
EVENTS_FILENAME = "events.jsonl"
#: Filenames inside each work dir (mirror draft.py constants by name).
SCRIPT_FILENAME = "script.json"
CAST_FILENAME = "cast.yaml"
CAST_REPORT_FILENAME = "cast_report.md"
#: mtime slack so a fresh draft (two writes in one second) is not "edited".
CAST_EDIT_TOLERANCE_S = 1.0
#: Book-id path guard (UUIDs pass; traversal/absolute/drive shapes never do).
_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


def work_root(workspace: Path | str) -> Path:
    """``<workspace>/.scribe`` (created on demand by callers that write)."""
    return Path(workspace) / WORK_DIRNAME


def bundles_root(workspace: Path | str) -> Path:
    """``<workspace>/bundles`` (may be absent; scan treats it as empty)."""
    return Path(workspace) / BUNDLES_DIRNAME


def check_book_id(book_id: str) -> str:
    """Validate a book id path segment (raises ``path-traversal`` when bad).

    Shape is ``{file, rule, message}`` with rule ``path-traversal`` (never
    a raw traceback at the API layer). Dots alone, slashes, backslashes,
    colons and drive specs are all rejected; UUIDs and similar tokens pass.
    """
    text = str(book_id or "")
    if not text or text in (".", ".."):
        raise ValueError(f"{text}: path-traversal: bad book id")
    if "/" in text or "\\" in text or ":" in text:
        raise ValueError(f"{text}: path-traversal: bad book id")
    if _ID_RE.fullmatch(text) is None:
        raise ValueError(f"{text}: path-traversal: bad book id")
    return text


def find_source_files(workspace: Path | str) -> list[Path]:
    """Source EPUB/PDF files under ``workspace`` (sorted, confined skips).

    Recursive, but never descends into :data:`SKIP_DIR_PARTS` or hidden
    dirs, so bundle-internal ``source/book.epub`` copies and the work
    folders themselves never appear as books.
    """
    root = Path(workspace)
    found: list[Path] = []
    for suffix in ("*.epub", "*.pdf", "*.EPUB", "*.PDF", "*.Epub", "*.Pdf"):
        for path in root.rglob(suffix):
            try:
                rel = path.relative_to(root)
            except ValueError:
                continue
            if any(part in SKIP_DIR_PARTS or part.startswith(".") for part in rel.parts[:-1]):
                continue
            if path.suffix.lower() not in SOURCE_SUFFIXES:
                continue
            if path.is_file():
                found.append(path)
    # Deduplicate case-variant glob overlap (Windows is case-insensitive).
    uniq = sorted({p.resolve() for p in found})
    return uniq


def _book_id_for_source(path: Path) -> tuple[str, str] | None:
    """``(book_id, sha256)`` for a source file, or None when unreadable."""
    try:
        from draft import book_id_for_file
    except ImportError:
        return None
    try:
        return book_id_for_file(path)
    except OSError:
        return None


def _read_json_file(path: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def _script_chapters(script: dict[str, Any]) -> list[dict[str, Any]]:
    chapters = script.get("chapters")
    return chapters if isinstance(chapters, list) else []


def _script_sentence_total(script: dict[str, Any]) -> int:
    total = 0
    for chapter in _script_chapters(script):
        if not isinstance(chapter, dict):
            continue
        for block in chapter.get("blocks") or []:
            if not isinstance(block, dict):
                continue
            sentences = block.get("sentences") or []
            if isinstance(sentences, list):
                total += len(sentences)
    return total


def has_cast_edits(work_dir: Path) -> bool:
    """True when ``cast.yaml`` is newer than ``script.json`` (edit heuristic).

    A fresh draft writes both within the same second; only a later touch
    (hand edit or a future UI6 patch) flips the chip. Missing files mean
    False (nothing to compare).
    """
    script = work_dir / SCRIPT_FILENAME
    cast = work_dir / CAST_FILENAME
    if not (script.is_file() and cast.is_file()):
        return False
    try:
        return (
            cast.stat().st_mtime > script.stat().st_mtime + CAST_EDIT_TOLERANCE_S
        )
    except OSError:
        return False


def is_rendering(work_dir: Path) -> bool:
    """True when the per-book lock names a live holder (stale locks ignored).

    Uses :mod:`lock` liveness (Windows OpenProcess / POSIX kill) plus the
    24 h age cap, mirroring :func:`lock.acquire_work_lock` without taking it.
    """
    try:
        from lock import LOCK_FILENAME, STALE_AFTER_S, _pid_alive, _read_lock
    except ImportError:
        return False
    path = work_dir / LOCK_FILENAME
    if not path.is_file():
        return False
    data = _read_lock(path)
    if data is None:
        return False
    holder = data.get("pid")
    if isinstance(holder, int):
        try:
            import os

            if holder == os.getpid():
                return True
        except Exception:
            pass
    from datetime import datetime, timezone

    age_s: float | None = None
    try:
        stamp = datetime.fromisoformat(str(data.get("started_at") or "").replace("Z", "+00:00"))
        age_s = (datetime.now(timezone.utc) - stamp).total_seconds()
    except (ValueError, TypeError):
        age_s = None
    alive = _pid_alive(holder) if isinstance(holder, int) else False
    if not alive:
        return False
    if age_s is not None and age_s > STALE_AFTER_S:
        return False
    return True


def read_events_tail(work_dir: Path) -> dict[str, Any] | None:
    """Last JSON object of the work-dir ``events.jsonl`` seam, or None."""
    path = work_dir / EVENTS_FILENAME
    if not path.is_file():
        # UI4 job folders (when they exist) keep their own events.jsonl;
        # UI3 has no job store, so there is nothing else to tail.
        return None
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    for line in reversed(lines):
        if not line.strip():
            continue
        try:
            data = json.loads(line)
        except ValueError:
            continue
        if isinstance(data, dict):
            return data
    return None


def rendering_percent(script_total: int, tail: dict[str, Any] | None) -> int | None:
    """0-100 progress from an events tail, or None when unknowable.

    ``cached + rendered`` over the script sentence total (build events are
    cumulative per the UI1 contract). A ``draft_done``/``build_done`` tail
    means the run finished, so no percent is reported (not rendering).
    """
    if not tail or not script_total or script_total <= 0:
        return None
    if tail.get("event") in ("draft_done", "build_done"):
        return None
    try:
        done = int(tail.get("cached") or 0) + int(tail.get("rendered") or 0)
    except (TypeError, ValueError):
        return None
    pct = round(100.0 * done / script_total)
    return max(0, min(100, pct))


def parse_validation_errors(errors: list[str]) -> list[dict[str, str]]:
    """Split validator strings into ``{file, rule, message}`` dicts.

    Validator errors already name the file (``text/ch002.json: ...``); the
    rule is the generic ``validation`` (the validator reports messages, not
    rule ids — the file plus message is the actionable part).
    """
    parsed: list[dict[str, str]] = []
    for error in errors:
        text = str(error)
        if ": " in text:
            file_part, _, message = text.partition(": ")
            parsed.append(
                {
                    "file": file_part.strip() or "bundle",
                    "rule": "validation",
                    "message": message.strip(),
                }
            )
        else:
            parsed.append({"file": "bundle", "rule": "validation", "message": text})
    return parsed


def validate_bundle_safe(
    bundle_dir: Path, *, probe: Any | None = None
) -> tuple[bool, list[dict[str, str]]]:
    """Run the bundle validator without ever raising (read-only for the UI).

    Returns ``(ok, errors)`` with errors shaped ``{file, rule, message}``.
    ``probe`` is the :func:`bundle.validate.validate_bundle` seam (tests
    inject a stub; production uses real ffprobe).
    """
    try:
        from bundle.validate import validate_bundle
    except ImportError as exc:
        return False, [
            {
                "file": "bundle",
                "rule": "validation",
                "message": f"validator unavailable: {exc}",
            }
        ]
    try:
        if probe is not None:
            result = validate_bundle(bundle_dir, probe=probe)
        else:
            result = validate_bundle(bundle_dir)
    except Exception as exc:  # validate must never break a status scan
        return False, [
            {"file": "bundle", "rule": "validation", "message": f"validation crashed: {exc}"}
        ]
    if result.ok:
        return True, []
    return False, parse_validation_errors(list(result.errors))


def _read_cast_summary(work_dir: Path) -> dict[str, Any]:
    """Counts for the detail stepper from ``cast.yaml`` (never raises)."""
    path = work_dir / CAST_FILENAME
    empty: dict[str, Any] = {
        "characters": 0,
        "aliases": 0,
        "overrides": 0,
        "character_names": [],
        "has_narrator": False,
    }
    if not path.is_file():
        return empty
    try:
        from text.cast import read_cast
    except ImportError:
        return empty
    try:
        cast = read_cast(path)
    except (OSError, ValueError):
        return empty
    if not isinstance(cast, dict):
        return empty
    characters = cast.get("characters")
    aliases = cast.get("aliases")
    overrides = cast.get("overrides")
    names: list[str] = []
    if isinstance(characters, dict):
        names = sorted(str(k) for k in characters if isinstance(k, str))
    alias_count = 0
    if isinstance(aliases, dict):
        for surfaces in aliases.values():
            if isinstance(surfaces, list):
                alias_count += sum(1 for s in surfaces if isinstance(s, str))
    override_count = len(overrides) if isinstance(overrides, list) else 0
    return {
        "characters": len(names),
        "aliases": alias_count,
        "overrides": override_count,
        "character_names": names,
        "has_narrator": isinstance(cast.get("narrator"), dict),
    }


def _chapter_summaries(script: dict[str, Any]) -> tuple[list[dict[str, Any]], int, float]:
    """``(chapters, total_sentences, total_chars)`` for the detail stepper.

    Per-chapter estimate reuses the draft heuristic (chars/15); durations
    are estimates until a bundle exists (bundle chapters carry real timings
    in the ``bundles`` section below).
    """
    try:
        from draft import estimate_seconds
    except ImportError:

        def estimate_seconds(chars: int) -> float:  # type: ignore[no-redef]
            return chars / 15.0

    chapters: list[dict[str, Any]] = []
    total_sentences = 0
    total_chars = 0
    for raw in _script_chapters(script):
        if not isinstance(raw, dict):
            continue
        sentences = 0
        chars = 0
        for block in raw.get("blocks") or []:
            if not isinstance(block, dict):
                continue
            for sentence in block.get("sentences") or []:
                if not isinstance(sentence, dict):
                    continue
                sentences += 1
                text = sentence.get("text")
                chars += len(str(text).strip()) if isinstance(text, str) else 0
        total_sentences += sentences
        total_chars += chars
        index = raw.get("chapter")
        chapters.append(
            {
                "index": index if isinstance(index, int) else 0,
                "title": str(raw.get("title") or ""),
                "sentences": sentences,
                "est_seconds": round(estimate_seconds(chars), 1),
            }
        )
    chapters.sort(key=lambda c: c["index"])
    return chapters, total_sentences, total_chars


def scan_books(
    workspace: Path | str,
    *,
    draft_jobs: dict[str, dict[str, Any]] | None = None,
    probe: Any | None = None,
) -> list[dict[str, Any]]:
    """List every book visible from ``workspace`` files (read-only, sorted).

    Merges source files, work folders and bundles by book id (see the
    module docstring for the range-bundle mapping). ``draft_jobs`` is the
    app's in-memory draft states (``{book_id: {status, error}}``); ``probe``
    is the validator seam. Never triggers work (no draft/build calls).
    """
    root = Path(workspace).resolve()
    work = root / WORK_DIRNAME
    bundles = root / BUNDLES_DIRNAME
    jobs = draft_jobs or {}

    try:
        from draft import book_id_for_sha256
    except ImportError:
        book_id_for_sha256 = None  # type: ignore[assignment]

    books: dict[str, dict[str, Any]] = {}
    bundle_ids: dict[str, list[str]] = {}
    try:
        from .transfer import is_on_tablet as _is_on_tablet
        from .transfer import read_transfers as _read_transfers

        transfer_records = _read_transfers(root)
        if not isinstance(transfer_records, dict):
            transfer_records = {}
    except Exception:
        transfer_records = {}
        _is_on_tablet = None  # type: ignore[assignment]

    def _ensure(book_id: str) -> dict[str, Any]:
        entry = books.get(book_id)
        if entry is None:
            entry = {
                "id": book_id,
                "title": book_id,
                "author": None,
                "source_file": None,
                "source_type": None,
                "chapters": 0,
                "sentences": 0,
                "drafted": False,
                "cast_edited": False,
                "rendering": False,
                "rendering_percent": None,
                "draft_status": "none",
                "valid": False,
                "bundles": 0,
                # UI8: transfer record owns this (None until a verified
                # transfer with a matching bundle id).
                "on_tablet": False,
                "transfer": None,
            }
            books[book_id] = entry
        return entry

    # Sources first (titles from metadata when readable, else the stem).
    for source in find_source_files(root):
        pair = _book_id_for_source(source)
        if pair is None:
            continue
        book_id, _sha = pair
        entry = _ensure(book_id)
        try:
            rel = source.relative_to(root).as_posix()
        except ValueError:
            continue
        entry["source_file"] = rel
        entry["source_type"] = source.suffix.lower().lstrip(".")
        try:
            from draft import read_book_metadata

            title, author = read_book_metadata(source)
        except Exception:
            title, author = source.stem, None
        entry["title"] = title or source.stem
        entry["author"] = author

    # Work folders (draft state).
    if work.is_dir():
        for child in sorted(work.iterdir()):
            if not child.is_dir() or child.name.startswith("."):
                continue
            if child.name == "jobs":
                continue  # UI4 job store (absent in UI3); never a book.
            book_id = child.name
            if _ID_RE.fullmatch(book_id) is None:
                continue
            script = _read_json_file(child / SCRIPT_FILENAME)
            if script is None:
                # A work dir without a readable script still marks the book
                # (draft failed or is mid-run); title stays source-derived.
                if book_id in books:
                    pass
                else:
                    _ensure(book_id)
                entry = books[book_id]
            else:
                entry = _ensure(book_id)
                chapters = _script_chapters(script)
                entry["drafted"] = len(chapters) > 0
                entry["chapters"] = len(chapters)
                entry["sentences"] = _script_sentence_total(script)
                title = script.get("title")
                if isinstance(title, str) and title.strip():
                    entry["title"] = title
                author = script.get("author")
                entry["author"] = author if isinstance(author, str) else None
                if entry["source_file"] is None:
                    source_name = script.get("source_file")
                    if isinstance(source_name, str) and source_name:
                        entry["source_file"] = source_name
            entry["cast_edited"] = has_cast_edits(child)
            rendering = is_rendering(child)
            entry["rendering"] = rendering
            tail = read_events_tail(child)
            if rendering:
                script_total = entry["sentences"] or 0
                if not script_total and script is not None:
                    script_total = _script_sentence_total(script)
                entry["rendering_percent"] = rendering_percent(script_total, tail)
            else:
                entry["rendering_percent"] = None
            job = jobs.get(book_id)
            if isinstance(job, dict) and isinstance(job.get("status"), str):
                entry["draft_status"] = job["status"]
            elif tail is not None and tail.get("event") == "draft_failed":
                entry["draft_status"] = "failed"
            elif entry["drafted"]:
                entry["draft_status"] = "done"
            elif tail is not None and tail.get("event") == "draft_start":
                entry["draft_status"] = "drafting"

    # Bundles (validity + mapping back to books).
    if bundles.is_dir():
        for child in sorted(bundles.iterdir()):
            if not child.is_dir():
                continue
            manifest = _read_json_file(child / "manifest.json")
            if manifest is None:
                continue
            manifest_id = manifest.get("id")
            if not isinstance(manifest_id, str) or not manifest_id:
                continue
            target: str | None = None
            if manifest_id in books:
                target = manifest_id
            else:
                source = manifest.get("source")
                sha = source.get("sha256") if isinstance(source, dict) else None
                if (
                    isinstance(sha, str)
                    and len(sha) == 64
                    and book_id_for_sha256 is not None
                ):
                    try:
                        candidate = book_id_for_sha256(sha)
                    except Exception:
                        candidate = None
                    if candidate is not None and candidate in books:
                        target = candidate
            if target is None:
                # Orphan bundle (source removed): still listed so its Valid
                # chip is visible; detail shows bundle info without draft.
                target = manifest_id
                entry = _ensure(target)
                title = manifest.get("title")
                entry["title"] = str(title) if isinstance(title, str) else target
                author = manifest.get("author")
                entry["author"] = author if isinstance(author, str) else None
            entry = books[target]
            ok, _errors = validate_bundle_safe(child, probe=probe)
            entry["bundles"] = int(entry["bundles"]) + 1
            if ok:
                entry["valid"] = True
            bundle_ids.setdefault(target, [])
            if manifest_id not in bundle_ids[target]:
                bundle_ids[target].append(manifest_id)

    # In-memory draft states for books with no work dir yet (just uploaded).
    for book_id, job in jobs.items():
        if book_id in books or not isinstance(job, dict):
            continue
        if _ID_RE.fullmatch(str(book_id)) is None:
            continue
        entry = _ensure(str(book_id))
        status = job.get("status")
        entry["draft_status"] = status if isinstance(status, str) else "drafting"
        filename = job.get("filename")
        if isinstance(filename, str) and filename:
            entry["source_file"] = filename
            entry["title"] = Path(filename).stem

    # UI8 transfer records: On tablet only with a matching bundle id.
    for book_id, entry in books.items():
        record = transfer_records.get(book_id)
        if not isinstance(record, dict):
            entry["on_tablet"] = False
            entry["transfer"] = None
            continue
        entry["transfer"] = dict(record)
        try:
            entry["on_tablet"] = bool(
                _is_on_tablet(record, bundle_ids.get(book_id, []))
                if _is_on_tablet is not None
                else False
            )
        except Exception:
            entry["on_tablet"] = False

    ordered = sorted(books.values(), key=lambda b: str(b["title"]).lower())
    # Drafting books without files sort with the rest; chips stay False.
    return ordered


def get_book_detail(
    workspace: Path | str,
    book_id: str,
    *,
    draft_jobs: dict[str, dict[str, Any]] | None = None,
    probe: Any | None = None,
) -> dict[str, Any]:
    """Stepper state for one book, from work-dir files only (never works).

    Raises ``ValueError`` (``path-traversal``) for bad ids and ``KeyError``
    (``book-not-found``) when no source, work folder or bundle matches.
    """
    check_book_id(book_id)
    root = Path(workspace).resolve()
    work = root / WORK_DIRNAME
    bundles = root / BUNDLES_DIRNAME
    jobs = draft_jobs or {}

    try:
        from draft import book_id_for_sha256
    except ImportError:
        book_id_for_sha256 = None  # type: ignore[assignment]

    work_dir = work / book_id
    work_exists = work_dir.is_dir()

    # Known book? work folder, memory job, mapped bundle, or source match.
    # (No file contents read here: the script is read after the slow scan
    # below, so one request never mixes a pre-draft script read with a
    # post-draft job status. The draft thread sets in-memory done only
    # after run_draft returns, so done always implies the files exist.)
    known = (
        work_exists
        or book_id in jobs
        or any(
            (lambda p: p is not None)(_book_id_for_source(p))
            for p in find_source_files(root)
            if (_book_id_for_source(p) or (None,))[0] == book_id
        )
    )
    mapped_bundles: list[dict[str, Any]] = []
    if bundles.is_dir():
        for child in sorted(bundles.iterdir()):
            if not child.is_dir():
                continue
            manifest = _read_json_file(child / "manifest.json")
            if manifest is None:
                continue
            manifest_id = manifest.get("id")
            match = manifest_id == book_id
            if not match and book_id_for_sha256 is not None:
                source = manifest.get("source")
                sha = source.get("sha256") if isinstance(source, dict) else None
                if isinstance(sha, str) and len(sha) == 64:
                    try:
                        match = book_id_for_sha256(sha) == book_id
                    except Exception:
                        match = False
            if not match:
                continue
            known = True
            ok, errors = validate_bundle_safe(child, probe=probe)
            chapters_raw = manifest.get("chapters")
            chapter_count = len(chapters_raw) if isinstance(chapters_raw, list) else 0
            total_ms = 0
            if isinstance(chapters_raw, list):
                for item in chapters_raw:
                    if isinstance(item, dict) and isinstance(item.get("duration_ms"), int):
                        total_ms += int(item["duration_ms"])
            try:
                rel = child.relative_to(root).as_posix()
            except ValueError:
                rel = child.name
            mapped_bundles.append(
                {
                    "id": manifest_id,
                    "path": rel,
                    "chapters": chapter_count,
                    "duration_ms": total_ms,
                    "valid": ok,
                    "errors": errors,
                }
            )
    if not known:
        raise KeyError(f"{book_id}: book-not-found: no source, work folder or bundle matches")

    # Fresh script read (after the slow scan above) plus a fresh job lookup
    # right after it, so status and files agree. If the job says finished
    # but the first read missed the file (draft landed between the two),
    # one re-read closes it: done implies the files are on disk.
    script = _read_json_file(work_dir / SCRIPT_FILENAME) if work_exists else None
    job = jobs.get(book_id)
    job_status = job.get("status") if isinstance(job, dict) else None
    if job_status in ("done", "failed") and script is None and work_exists:
        script = _read_json_file(work_dir / SCRIPT_FILENAME)

    chapters: list[dict[str, Any]] = []
    total_sentences = 0
    total_chars = 0
    title = book_id
    author: str | None = None
    source_file: str | None = None
    source_sha: str | None = None
    if script is not None:
        chapters, total_sentences, total_chars = _chapter_summaries(script)
        raw_title = script.get("title")
        if isinstance(raw_title, str) and raw_title.strip():
            title = raw_title
        raw_author = script.get("author")
        author = raw_author if isinstance(raw_author, str) else None
        raw_source = script.get("source_file")
        source_file = raw_source if isinstance(raw_source, str) else None
        raw_sha = script.get("source_sha256")
        source_sha = raw_sha if isinstance(raw_sha, str) else None
    else:
        for source in find_source_files(root):
            pair = _book_id_for_source(source)
            if pair is not None and pair[0] == book_id:
                try:
                    source_file = source.relative_to(root).as_posix()
                except ValueError:
                    source_file = source.name
                title = source.stem
                try:
                    from draft import read_book_metadata

                    meta_title, meta_author = read_book_metadata(source)
                    title = meta_title or title
                    author = meta_author
                except Exception:
                    author = None
                source_sha = pair[1]
                break

    cast = (
        _read_cast_summary(work_dir)
        if work_dir.is_dir()
        else _read_cast_summary(root / "__none__")
    )
    rendering = is_rendering(work_dir) if work_dir.is_dir() else False
    tail = read_events_tail(work_dir) if work_dir.is_dir() else None
    percent = rendering_percent(total_sentences, tail) if rendering else None
    # Fresh job lookup beside the tail read (both post-date the slow scan,
    # so a draft that lands mid-request shows consistently).
    job = jobs.get(book_id)
    job_status = job.get("status") if isinstance(job, dict) else None
    if isinstance(job_status, str):
        draft_status = job_status
        draft_error = job.get("error") if isinstance(job, dict) else None
    elif tail is not None and tail.get("event") == "draft_failed":
        draft_status = "failed"
        draft_error = tail.get("error")
    elif script is not None and _script_chapters(script):
        draft_status = "done"
        draft_error = None
    elif tail is not None and tail.get("event") == "draft_start":
        draft_status = "drafting"
        draft_error = None
    else:
        draft_status = "none"
        draft_error = None

    # Final consistency: a finished job implies the files are on disk (the
    # draft thread updates memory only after run_draft returns), but the
    # script above may pre-date a draft that landed during this request's
    # slow scans. One re-read closes the tear for good.
    if draft_status in ("done", "failed") and not chapters and work_dir.is_dir():
        fresh = _read_json_file(work_dir / SCRIPT_FILENAME)
        if fresh is not None and _script_chapters(fresh):
            script = fresh
            chapters, total_sentences, total_chars = _chapter_summaries(script)
            raw_title = script.get("title")
            if isinstance(raw_title, str) and raw_title.strip():
                title = raw_title
            raw_author = script.get("author")
            author = raw_author if isinstance(raw_author, str) else None
            raw_source = script.get("source_file")
            source_file = raw_source if isinstance(raw_source, str) else None
            raw_sha = script.get("source_sha256")
            source_sha = raw_sha if isinstance(raw_sha, str) else None

    validation_errors: list[dict[str, str]] = []
    for bundle in mapped_bundles:
        validation_errors.extend(bundle["errors"])
    valid = any(b["valid"] for b in mapped_bundles)

    # UI8 transfer record: On tablet only when the recorded bundle id still
    # matches one of the mapped bundles (a rebuild clears the chip).
    try:
        from .transfer import get_transfer_record as _get_record
        from .transfer import is_on_tablet as _is_on_tablet_detail

        record = _get_record(root, book_id)
    except Exception:
        record = None
        _is_on_tablet_detail = None  # type: ignore[assignment]
    mapped_ids = [
        str(b.get("id"))
        for b in mapped_bundles
        if isinstance(b.get("id"), str) and b.get("id")
    ]
    try:
        on_tablet = bool(
            _is_on_tablet_detail(record, mapped_ids)
            if _is_on_tablet_detail is not None
            else False
        )
    except Exception:
        on_tablet = False

    return {
        "id": book_id,
        "title": title,
        "author": author,
        "source_file": source_file,
        "source_sha256": source_sha,
        "drafted": script is not None and len(_script_chapters(script)) > 0,
        "draft_status": draft_status,
        "draft_error": draft_error,
        "cast_edited": has_cast_edits(work_dir) if work_dir.is_dir() else False,
        "rendering": rendering,
        "rendering_percent": percent,
        "chapters": chapters,
        "total_sentences": total_sentences,
        "cast": cast,
        "bundles": mapped_bundles,
        "valid": valid,
        "validation": validation_errors,
        "on_tablet": on_tablet,
        "transfer": dict(record) if isinstance(record, dict) else None,
    }
