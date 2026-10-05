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

"""Slice 6 UI8: Validate and Transfer helpers (pure, stdlib only).

Validate runs INLINE in the request (no job): ``validate_bundle`` ffprobes
every chapter, which is seconds on real books (stub 2-chapter measures
~3 ms; a real 1-chapter build validates in ~4 s per D-048, so even a
20-chapter novel stays well under 30 s). A job would add FIFO, SSE and
pause machinery for no benefit; the read-only probe seam
(:func:`bundle.validate.validate_bundle` ``probe`` param) is injected via
``app.state.validate_probe`` so tests never need ffprobe.

Transfer copies one bundle dir to a user-chosen destination OUTSIDE the
workspace by design (a USB stick or folder the tablet can read). Only the
SOURCE side is confined via ``safe_join``; the destination is validated as
an absolute path on a real drive (relative, ``..``, NUL and shell
metachars rejected). Copies run in a background thread as a ``transfer``
job with the same ``job.json`` shape as builds (polled via
``GET /api/jobs/{id}`` for bytes progress), then verified size + sha256
per file. A transfer record (destination, time, manifest id, verification
outcome) lives in ``<workspace>/.scribe-transfers.json``; the On-tablet
chip reads it and requires the recorded bundle id to still match a mapped
bundle (a rebuild with a new id clears the chip).

Drives use stdlib ``ctypes`` only (``GetLogicalDrives`` +
``GetDriveTypeW`` on Windows; no ``wmic``, no subprocess, no new
dependency). ``open_folder`` calls ``os.startfile`` on Windows only and
fails cleanly elsewhere; it is always behind a token-guarded POST.
"""

from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

#: Workspace transfer-record filename (next to ``jobs/``, never committed).
TRANSFERS_FILENAME = ".scribe-transfers.json"

#: Copy chunk size (1 MiB; progress updates per chunk).
_COPY_CHUNK = 1024 * 1024

#: Shell metacharacters rejected in typed destinations (we never shell out,
#: so this is defense in depth; ``:`` is allowed for the ``C:\\`` drive
#: prefix and spaces/dashes/dots in names are allowed).
_SHELL_CHARS = frozenset({";", "&", "|", "$", "`", "<", ">", '"'})


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def transfers_path(workspace: Path | str) -> Path:
    """``<workspace>/.scribe-transfers.json`` (may be absent)."""
    return Path(workspace).resolve() / TRANSFERS_FILENAME


def list_drives() -> list[dict[str, Any]]:
    """Detected removable drives (Windows only, stdlib ``ctypes`` only).

    Uses ``GetLogicalDrives`` (bitmask) plus ``GetDriveTypeW`` per letter
    and returns only ``DRIVE_REMOVABLE`` (2) entries as
    ``{path, removable}`` with a trailing backslash (``E:\\``). No
    ``wmic``, no subprocess, no new dependency. Non-Windows or any
    ``ctypes`` failure returns ``[]`` (the typed path stays available).
    """
    if os.name != "nt":
        return []
    try:
        import ctypes  # noqa: PLC0415 -- local import keeps import time light

        kernel32 = ctypes.windll.kernel32  # type: ignore[attr-defined]
        mask = int(kernel32.GetLogicalDrives())
    except Exception:
        return []
    # DRIVE_REMOVABLE = 2 (floppy/USB); fixed = 3, remote = 4, cdrom = 5.
    drives: list[dict[str, Any]] = []
    try:
        for letter in range(26):
            if not (mask & (1 << letter)):
                continue
            root = f"{chr(65 + letter)}:\\"
            try:
                dtype = int(kernel32.GetDriveTypeW(root))
            except Exception:
                continue
            if dtype == 2:
                drives.append({"path": root, "removable": True})
    except Exception:
        return drives
    return sorted(drives, key=lambda d: str(d.get("path", "")))


def validate_destination(raw: Any) -> Path:
    """Validate a typed transfer destination (outside the workspace).

    Returns the absolute ``Path`` (not resolved against the workspace).
    Raises ``ValueError`` shaped ``dest: bad-destination: ...`` when the
    destination is relative, contains ``..``, NUL/newline, shell
    metachars, or sits on no real drive. The source side stays confined
    via ``safe_join`` by callers; this function never touches the
    workspace.
    """
    text = raw if isinstance(raw, str) else str(raw) if raw is not None else ""
    if not isinstance(raw, str) or not text.strip():
        raise ValueError("dest: bad-destination: destination must be a non-empty path")
    if "\x00" in text:
        raise ValueError("dest: bad-destination: destination contains NUL")
    if "\n" in text or "\r" in text:
        raise ValueError("dest: bad-destination: destination contains a newline")
    stripped = text.strip()
    # Shell metachars (defense in depth; we never shell out).
    for char in _SHELL_CHARS:
        if char in stripped:
            raise ValueError(
                f"dest: bad-destination: destination contains {char!r} (shell metachar)"
            )
    if "$(" in stripped:
        raise ValueError("dest: bad-destination: destination contains '$(' (shell metachar)")
    candidate = Path(stripped)
    if not candidate.is_absolute():
        raise ValueError(
            f"{stripped}: bad-destination: destination must be absolute (got relative)"
        )
    if ".." in candidate.parts:
        raise ValueError(
            f"{stripped}: bad-destination: destination must not contain '..'"
        )
    # Real drive: the anchor (``C:\\`` or ``/``) must exist. The full path
    # itself is created on demand, so only the anchor is checked.
    try:
        anchor = Path(candidate.anchor) if candidate.anchor else None
    except Exception:
        anchor = None
    if anchor is not None and str(anchor):
        try:
            if not Path(str(anchor)).exists():
                raise ValueError(
                    f"{stripped}: bad-destination: drive does not exist ({anchor})"
                )
        except ValueError:
            raise
        except Exception as exc:
            raise ValueError(
                f"{stripped}: bad-destination: cannot check drive: {exc}"
            ) from exc
    return candidate


def _iter_source_files(src: Path) -> list[Path]:
    """All files under ``src`` (sorted, relative-walk, no dirs)."""
    out: list[Path] = []
    for path in sorted(src.rglob("*")):
        if path.is_file():
            out.append(path)
    return out


def _sha256_of(path: Path) -> tuple[int, str]:
    """``(size, hex-sha256)`` of one file (streamed)."""
    digest = hashlib.sha256()
    size = 0
    with open(path, "rb") as handle:
        while True:
            chunk = handle.read(_COPY_CHUNK)
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


def copy_bundle_with_hashes(
    src: Path,
    dst: Path,
    progress_cb: Callable[[int, int], None] | None = None,
    *,
    cancel_cb: Callable[[], bool] | None = None,
) -> dict[str, dict[str, Any]]:
    """Copy ``src`` dir to ``dst`` (fresh dir), hashing during copy.

    Returns ``{rel-posix: {size, sha256}}`` for the SOURCE files. Calls
    ``progress_cb(copied, total)`` after each chunk; aborts with
    ``InterruptedError`` when ``cancel_cb`` returns True. Raises
    ``FileExistsError`` when ``dst`` exists (callers check ``force`` first)
    and ``OSError`` on IO failure. Never deletes anything.
    """
    src = Path(src)
    dst = Path(dst)
    if dst.exists():
        raise FileExistsError(f"{dst}: exists: destination bundle dir already exists")
    files = _iter_source_files(src)
    total = 0
    for path in files:
        try:
            total += path.stat().st_size
        except OSError:
            pass
    copied = 0
    if progress_cb is not None:
        progress_cb(0, total)
    hashes: dict[str, dict[str, Any]] = {}
    for path in files:
        rel = path.relative_to(src).as_posix()
        target = dst / path.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        size = 0
        with open(path, "rb") as handle_in, open(target, "wb") as handle_out:
            while True:
                if cancel_cb is not None and cancel_cb():
                    raise InterruptedError(f"{dst}: cancelled: transfer cancelled")
                chunk = handle_in.read(_COPY_CHUNK)
                if not chunk:
                    break
                handle_out.write(chunk)
                digest.update(chunk)
                size += len(chunk)
                copied += len(chunk)
                if progress_cb is not None:
                    progress_cb(copied, total)
        hashes[rel] = {"size": size, "sha256": digest.hexdigest()}
    return hashes


def verify_transfer(src: Path, dst: Path) -> tuple[bool, list[dict[str, str]]]:
    """Verify ``dst`` matches ``src`` (size + sha256 per file).

    Returns ``(ok, errors)`` with errors shaped ``{file, rule, message}``
    (rule ``verify-failed``). A truncated file changes both size and hash,
    so it always fails. Missing files, extra read errors and hash
    mismatches all fail. Never raises on IO problems (they become errors).
    """
    src = Path(src)
    dst = Path(dst)
    errors: list[dict[str, str]] = []
    try:
        sources = _iter_source_files(src)
    except OSError as exc:
        return False, [
            {"file": "bundle", "rule": "verify-failed", "message": f"cannot list source: {exc}"}
        ]
    for path in sources:
        rel = path.relative_to(src).as_posix()
        counterpart = dst / path.relative_to(src)
        if not counterpart.is_file():
            errors.append(
                {
                    "file": rel,
                    "rule": "verify-failed",
                    "message": f"{rel}: verify-failed: file missing in destination",
                }
            )
            continue
        try:
            src_size, src_hash = _sha256_of(path)
        except OSError as exc:
            errors.append(
                {
                    "file": rel,
                    "rule": "verify-failed",
                    "message": f"{rel}: verify-failed: cannot read source: {exc}",
                }
            )
            continue
        try:
            dst_size, dst_hash = _sha256_of(counterpart)
        except OSError as exc:
            errors.append(
                {
                    "file": rel,
                    "rule": "verify-failed",
                    "message": f"{rel}: verify-failed: cannot read destination: {exc}",
                }
            )
            continue
        if dst_size != src_size:
            errors.append(
                {
                    "file": rel,
                    "rule": "verify-failed",
                    "message": (
                        f"{rel}: verify-failed: size mismatch: "
                        f"expected {src_size}, got {dst_size}"
                    ),
                }
            )
            continue
        if dst_hash.lower() != src_hash.lower():
            errors.append(
                {
                    "file": rel,
                    "rule": "verify-failed",
                    "message": f"{rel}: verify-failed: sha256 mismatch (truncated or corrupt?)",
                }
            )
    if errors:
        return False, errors
    if not sources:
        return False, [
            {
                "file": "bundle",
                "rule": "verify-failed",
                "message": "bundle: verify-failed: no files to verify",
            }
        ]
    return True, []


def read_transfers(workspace: Path | str) -> dict[str, Any]:
    """All transfer records (never raises; corrupt means ``{}``)."""
    path = transfers_path(workspace)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def get_transfer_record(workspace: Path | str, book_id: str) -> dict[str, Any] | None:
    """Record for ``book_id``, or None (never raises)."""
    records = read_transfers(workspace)
    entry = records.get(str(book_id))
    return dict(entry) if isinstance(entry, dict) else None


def write_transfer_record(
    workspace: Path | str,
    book_id: str,
    record: dict[str, Any],
) -> dict[str, Any]:
    """Persist one book's transfer record (atomic tmp+replace; never raises).

    Record shape: ``{destination, time, bundle_id, bundle_path, verified,
    bytes_total}``. Returns the stored record.
    """
    root = Path(workspace).resolve()
    path = root / TRANSFERS_FILENAME
    try:
        current = read_transfers(root)
    except Exception:
        current = {}
    if not isinstance(current, dict):
        current = {}
    stored = dict(record)
    stored["time"] = stored.get("time") or _now_iso()
    current[str(book_id)] = stored
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(current, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        tmp.replace(path)
    except OSError:
        pass
    return stored


def is_on_tablet(
    record: dict[str, Any] | None, mapped_bundle_ids: list[str]
) -> bool:
    """True when ``record`` marks this book On tablet (id-match required).

    The recorded ``bundle_id`` must equal one of the book's current mapped
    bundle manifest ids and ``verified`` must be true. A rebuild writes a
    new manifest id, so the stale record stops matching and the chip
    clears (no silent carry-over).
    """
    if not isinstance(record, dict):
        return False
    if not record.get("verified"):
        return False
    bundle_id = record.get("bundle_id")
    if not isinstance(bundle_id, str) or not bundle_id:
        return False
    return bundle_id in mapped_bundle_ids


def open_folder(path_str: str, *, opener: Callable[[str], None] | None = None) -> str:
    """Open ``path_str`` in the OS file manager (Windows only).

    Calls ``os.startfile`` (or the injected ``opener`` in tests) and
    returns the path. Raises ``ValueError`` shaped
    ``path: open-unsupported: ...`` on non-Windows and
    ``path: bad-destination: ...`` when missing. Callers put this behind a
    token-guarded POST (server-side open is a state change).
    """
    text = str(path_str or "").strip()
    if not text:
        raise ValueError("folder: bad-destination: nothing to open")
    target = Path(text)
    if not target.exists():
        raise ValueError(f"{text}: bad-destination: folder does not exist")
    if opener is not None:
        opener(str(target))
        return str(target)
    if os.name != "nt":
        raise ValueError(
            f"{text}: open-unsupported: folder opening needs Windows (path: {target})"
        )
    try:
        os.startfile(str(target))  # type: ignore[attr-defined]
    except Exception as exc:
        raise ValueError(f"{text}: open-unsupported: cannot open folder: {exc}") from exc
    return str(target)


__all__ = [
    "TRANSFERS_FILENAME",
    "copy_bundle_with_hashes",
    "get_transfer_record",
    "is_on_tablet",
    "list_drives",
    "open_folder",
    "read_transfers",
    "transfers_path",
    "validate_destination",
    "verify_transfer",
    "write_transfer_record",
]
