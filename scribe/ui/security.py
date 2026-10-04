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
from pathlib import Path, PurePath, PureWindowsPath

#: Fallback when a client-supplied name carries no usable basename.
FALLBACK_FILENAME = "upload.bin"

#: Hostnames the local server answers to (bind is 127.0.0.1-only; ``localhost``
#: resolves there, so browsers may send either, with any port).
ALLOWED_HOSTS = frozenset({"127.0.0.1", "localhost"})

#: Header carrying the per-run token on state-changing requests.
TOKEN_HEADER = "X-Auloud-Token"

_DRIVE_RE = re.compile(r"^[A-Za-z]:")


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
    control characters with ``_``, and falls back to ``upload.bin`` when
    nothing usable remains (empty, ``.`` or ``..``). Unicode letters and
    spaces survive; directory parts never do.
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
    return safe[:255]


def safe_join(root: Path | str, *parts: str | Path) -> Path:
    """Join ``parts`` onto ``root`` and refuse escapes (``path-traversal``).

    Rejects absolute paths, Windows drive specs (``C:...``) and UNC prefixes
    (``\\\\\\\\server``) up front, then resolves and requires the result to
    stay at or under the resolved root. ``..`` segments that resolve back
    inside are allowed; anything escaping raises ``ValueError`` shaped
    ``{file, rule, message}`` with rule ``path-traversal`` (never a raw
    traceback at the API layer).
    """
    root_path = Path(root)
    original = "/".join(str(p) for p in parts) if parts else "."
    for part in parts:
        text = str(part).replace("\x00", "")
        if not text:
            continue
        if PurePath(text).is_absolute() or PureWindowsPath(text).is_absolute():
            raise ValueError(f"{original}: path-traversal: absolute path rejected")
        if PureWindowsPath(text).drive or text.startswith("\\\\"):
            raise ValueError(f"{original}: path-traversal: drive/UNC path rejected")
    candidate = root_path
    for part in parts:
        text = str(part).replace("\x00", "")
        if text:
            candidate = candidate / text
    base = root_path.resolve()
    resolved = candidate.resolve()
    try:
        inside = resolved == base or resolved.is_relative_to(base)
    except (OSError, ValueError):
        inside = False
    if not inside:
        raise ValueError(f"{original}: path-traversal: escapes workspace root")
    return resolved
