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

"""UI2 local-server security helpers (stdlib only, no framework import).

Slice 6 plan D8: bind to 127.0.0.1 is not enough (other pages in the browser
can reach localhost), so the server also checks the Host header, sends no
CORS headers, and requires a per-run token on state-changing requests. This
module holds the pure helpers so they are unit-testable without the ``ui``
extra installed:

- :func:`generate_token` (``secrets`` module, per-run random)
- :func:`is_allowed_host` (blocks DNS-rebinding style hosts)
- :func:`is_valid_token` (constant-time comparison, no leak)
- :func:`sanitize_filename` (uploads land in UI3; the sanitizer lands now)
- :func:`safe_join` (all file access stays inside the workspace root)
"""

from __future__ import annotations

import hmac
import re
import secrets
import urllib.parse
from pathlib import Path, PurePath, PureWindowsPath

#: Fallback when a client-supplied name carries no usable basename.
FALLBACK_FILENAME = "upload.bin"

#: Hostnames the local server answers to (bind is 127.0.0.1-only; ``localhost``
#: resolves there, so browsers may send either, with any port).
ALLOWED_HOSTS = frozenset({"127.0.0.1", "localhost"})

#: Header carrying the per-run token on state-changing requests.
TOKEN_HEADER = "X-Auloud-Token"

_DRIVE_RE = re.compile(r"^[A-Za-z]:")

#: Windows device names that cannot be created as files (with any extension).
_RESERVED_NAMES = frozenset(
    {"CON", "PRN", "AUX", "NUL"}
    | {f"COM{i}" for i in range(1, 10)}
    | {f"LPT{i}" for i in range(1, 10)}
)


def strip_extended_prefix(text: str | Path) -> str:
    """Drop a Win32 extended-length prefix (comparison helper, no FS touch).

    ``Path.resolve()`` may return the ``\\\\?\\`` form from one call and
    the plain form from the next depending on what exists at call time
    (concurrent first-time directory creation), so prefix-sensitive
    comparisons must strip it first. ``\\\\?\\UNC\\`` maps back to ``\\\\``.
    """
    raw = str(text)
    if raw.startswith("\\\\?\\UNC\\"):
        return "\\\\" + raw[len("\\\\?\\UNC\\"):]
    if raw.startswith("\\\\?\\"):
        return raw[len("\\\\?\\"):]
    return raw


def generate_token(nbytes: int = 32) -> str:
    """Return a fresh per-run URL-safe token (``secrets`` module)."""
    return secrets.token_urlsafe(nbytes)


def is_valid_token(provided: str | None, expected: str) -> bool:
    """Constant-time token check; ``None``/empty never validates."""
    if not provided or not expected:
        return False
    return hmac.compare_digest(provided, expected)


def is_allowed_host(host_header: str | None) -> bool:
    """True only for ``127.0.0.1`` / ``localhost`` (optional ``:port``).

    Everything else, including DNS-rebinding shapes such as
    ``127.0.0.1.evil.com`` or an attacker domain resolving to localhost, is
    rejected. The check is hostname-only (any port passes) so a picked
    next-free-port never breaks legitimate browsers.
    """
    if not host_header:
        return False
    host = host_header.split(",")[0].strip().lower().rstrip(".")
    if not host:
        return False
    if host.startswith("["):  # no IPv6 listener in UI2; refuse, never branch
        return False
    if ":" in host:
        name, _, port = host.rpartition(":")
        if port.isdigit() and name:
            host = name
    return host in ALLOWED_HOSTS


def sanitize_filename(name: str | None) -> str:
    """Return a safe basename for a client-supplied upload name.

    Takes the final path component across POSIX and Windows separators,
    strips a Windows drive prefix (``C:``), replaces Windows-forbidden and
    control characters with ``_``, prefixes Windows device names
    (``CON``/``PRN``/``AUX``/``NUL``/``COM1``-``COM9``/``LPT1``-``LPT9``,
    any extension, case-insensitive) with ``_``, and falls back to
    ``upload.bin`` when nothing usable remains (empty, ``.`` or ``..``).
    Unicode letters and spaces survive; directory parts never do.
    """
    if not name:
        return FALLBACK_FILENAME
    cleaned = str(name).replace("\x00", "").strip()
    if not cleaned:
        return FALLBACK_FILENAME
    parts = re.split(r"[\\/]+", cleaned)
    base = ""
    for part in reversed(parts):
        if part:
            base = part
            break
    if not base:
        return FALLBACK_FILENAME
    base = _DRIVE_RE.sub("", base).strip()
    if not base or base in (".", ".."):
        return FALLBACK_FILENAME
    safe = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", base)
    safe = safe.strip().strip(".")
    if not safe or safe in (".", ".."):
        return FALLBACK_FILENAME
    stem = safe.split(".", 1)[0].upper()
    if stem in _RESERVED_NAMES:
        safe = "_" + safe
    return safe[:255]


def safe_join(root: Path | str, *parts: str | Path) -> Path:
    """Join ``parts`` onto ``root`` and refuse escapes (``path-traversal``).

    Decodes one layer of URL-encoding first (so ``%2e%2e`` cannot smuggle
    ``..`` past the checks when a caller passes a raw URL segment; the
    framework already decodes once for route params), then rejects absolute
    paths, Windows drive specs (``C:...``), UNC prefixes
    (``\\\\\\\\server``) and ADS colons (``file:stream``) up front, then
    resolves and requires the result to stay at or under the resolved root.
    ``..`` segments that resolve back inside are allowed; anything escaping
    raises ``ValueError`` shaped ``{file, rule, message}`` with rule
    ``path-traversal`` (never a raw traceback at the API layer).
    """
    root_path = Path(root)
    original = "/".join(str(p) for p in parts) if parts else "."
    decoded: list[str] = []
    for part in parts:
        text = str(part).replace("\x00", "")
        if not text:
            continue
        text = urllib.parse.unquote(text)
        if not text:
            continue
        decoded.append(text)
    for text in decoded:
        if PurePath(text).is_absolute() or PureWindowsPath(text).is_absolute():
            raise ValueError(f"{original}: path-traversal: absolute path rejected")
        if PureWindowsPath(text).drive or text.startswith("\\\\"):
            raise ValueError(f"{original}: path-traversal: drive/UNC path rejected")
        if ":" in text:
            raise ValueError(f"{original}: path-traversal: ADS/colon path rejected")
    candidate = root_path
    for text in decoded:
        candidate = candidate / text
    base = root_path.resolve()
    resolved = candidate.resolve()
    # Compare prefix-stripped forms: Windows resolve() can return the
    # extended-length form for one call and the plain form for the next
    # (concurrent first-time directory creation), which must never read
    # as an escape.
    try:
        base_cmp = Path(strip_extended_prefix(base))
        res_cmp = Path(strip_extended_prefix(resolved))
        inside = res_cmp == base_cmp or res_cmp.is_relative_to(base_cmp)
    except (OSError, ValueError):
        inside = False
    if not inside:
        raise ValueError(f"{original}: path-traversal: escapes workspace root")
    return resolved
