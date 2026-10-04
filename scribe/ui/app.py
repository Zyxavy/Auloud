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

"""UI3 app factory: skeleton protections (UI2) plus Books routes (plan UI3).

Kept from UI2: ``GET /api/health``, ``GET /`` (now the real Books UI; the
``{{AULOUD_TOKEN}}`` slot is still filled per run) and ``POST /api/echo``
(the tested token example). No CORS middleware anywhere; the Host/token
middleware answers 421/403 with generic bodies that never echo the
offending Host or the expected token.

Added in UI3 (thin operators over the library; no library behavior change):

- ``GET /api/books`` — workspace scan (sources, work folders, bundles;
  chips derived from files only, never triggering work).
- ``POST /api/books/upload`` — multipart upload (token required via the
  middleware), sanitized filename confined to the workspace, then a draft
  on a daemon thread. The thread writes the work-dir ``events.jsonl`` seam
  with the UI1 key contract so UI4 reuses the tailing; in-memory status
  plus that file are the persisted minimal result (UI4 subsumes this with
  real job folders).
- ``GET /api/books/{book_id}`` — stepper detail assembled from work-dir
  files only (never triggers work).
"""

from __future__ import annotations

import html
import json
import threading
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse

from .books import (
    WORK_DIRNAME,
    get_book_detail,
    scan_books,
)
from .security import (
    TOKEN_HEADER,
    generate_token,
    is_allowed_host,
    is_valid_token,
    safe_join,
    sanitize_filename,
)

#: Methods that change state and therefore require the per-run token.
STATE_CHANGING = frozenset({"POST", "PUT", "PATCH", "DELETE"})


def _placeholder_html(token: str) -> str:
    """Render the skeleton page with the per-run token embedded.

    The static file ships in the wheel (``ui/static/index.html``); the
    ``{{AULOUD_TOKEN}}`` slot is filled per run so later pages (UI3+) can
    read one ``api()`` helper. The token is HTML-escaped (the ``secrets``
    URL-safe alphabet needs no escaping in practice; the escape covers a
    custom ``token=`` passed in tests).
    """
    template = (Path(__file__).resolve().parent / "static" / "index.html").read_text(
        encoding="utf-8"
    )
    return template.replace("{{AULOUD_TOKEN}}", html.escape(token, quote=True))


def _error_shape(file: str, rule: str, message: str) -> dict[str, str]:
    """API error body shaped ``{file, rule, message}`` (UI1 unified shape)."""
    return {"file": str(file), "rule": str(rule), "message": str(message)}


def _split_shaped(text: str, fallback_file: str, fallback_rule: str) -> dict[str, str]:
    """Split a ``file: rule: message`` string into the error shape (best effort)."""
    parts = str(text).split(": ", 2)
    if len(parts) == 3:
        return _error_shape(parts[0] or fallback_file, parts[1] or fallback_rule, parts[2])
    if len(parts) == 2:
        return _error_shape(parts[0] or fallback_file, fallback_rule, parts[1])
    return _error_shape(fallback_file, fallback_rule, str(text))


def _run_draft_job(
    app: FastAPI, workspace: Path, source_path: Path, book_id: str, filename: str
) -> None:
    """Background draft for one upload (UI3 simplicity; UI4 subsumes it).

    Writes the work-dir ``events.jsonl`` seam with the UI1 key contract
    (``draft_start`` then ``draft_done``/``draft_failed``) so UI4's tailing
    reuses the same parser; the ``draft_failed`` line also carries an
    ``error`` object so the message survives a server restart. In-memory
    status mirrors the file. Never raises out of the thread.
    """
    from .books import EVENTS_FILENAME

    work_dir = workspace / WORK_DIRNAME / book_id
    events_path = work_dir / EVENTS_FILENAME
    wall_start = time.monotonic()

    def _write(event: str, extra: dict[str, Any] | None = None) -> None:
        line: dict[str, Any] = {
            "event": event,
            "chapter": 0,
            "sid": None,
            "cached": 0,
            "rendered": 0,
            "audio_ms": 0,
            "wall_s": round(time.monotonic() - wall_start, 3),
        }
        if extra:
            line.update(extra)
        try:
            work_dir.mkdir(parents=True, exist_ok=True)
            with open(events_path, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, sort_keys=True) + "\n")
        except OSError:
            pass

    _write("draft_start")
    try:
        from draft import run_draft

        run_draft(source_path, work_root=workspace / WORK_DIRNAME)
    except Exception as exc:  # DraftError and friends surface cleanly, never traceback
        err = _error_shape(filename, "draft-failed", str(exc) or type(exc).__name__)
        try:
            app.state.draft_jobs[book_id] = {"status": "failed", "filename": filename, "error": err}
        except Exception:
            pass
        _write("draft_failed", {"error": err})
        return
    try:
        app.state.draft_jobs[book_id] = {"status": "done", "filename": filename, "error": None}
    except Exception:
        pass
    _write("draft_done")


def create_app(workspace: Path | str, token: str | None = None) -> FastAPI:
    """Build the UI3 app bound to ``workspace`` (confined root).

    :param workspace: file access below this root only (see ``safe_join``).
    :param token: per-run token; a fresh ``secrets`` token when omitted.
    """
    root = Path(workspace).resolve()
    run_token = token or generate_token()

    app = FastAPI(
        title="Auloud Scribe UI",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.workspace = root
    app.state.token = run_token
    # UI3 in-memory draft states: {book_id: {status, filename, error}}.
    # The work-dir files plus events.jsonl are the persisted result; UI4
    # replaces this with real job folders.
    app.state.draft_jobs = {}

    @app.middleware("http")
    async def _local_only(request: Request, call_next: Any) -> Any:
        host = request.headers.get("host")
        if not is_allowed_host(host):
            return JSONResponse({"detail": "misdirected host"}, status_code=421)
        if request.method in STATE_CHANGING and not is_valid_token(
            request.headers.get(TOKEN_HEADER),
            run_token,
        ):
            return JSONResponse({"detail": "forbidden"}, status_code=403)
        response = await call_next(request)
        return response

    @app.get("/api/health")
    async def _health() -> dict[str, bool]:
        return {"ok": True}

    @app.get("/", response_class=HTMLResponse)
    async def _index() -> HTMLResponse:
        return HTMLResponse(_placeholder_html(run_token))

    @app.post("/api/echo")
    async def _echo(payload: dict[str, Any]) -> dict[str, Any]:
        """Skeleton-only tested example: echoes the JSON body (token-guarded)."""
        return {"echo": payload}

    @app.get("/api/books")
    async def _books() -> dict[str, Any]:
        """Workspace scan: sources, work folders, bundles (read-only)."""
        books = scan_books(root, draft_jobs=app.state.draft_jobs)
        return {"books": books}

    @app.get("/api/books/{book_id}")
    async def _book_detail(book_id: str) -> JSONResponse:
        """Stepper state for one book, from work-dir files only."""
        try:
            detail = get_book_detail(
                root, book_id, draft_jobs=app.state.draft_jobs
            )
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )
        except KeyError:
            return JSONResponse(
                _error_shape(
                    book_id,
                    "book-not-found",
                    f"{book_id}: book-not-found: no source, work folder or bundle matches",
                ),
                status_code=404,
            )
        return JSONResponse(detail)

    @app.post("/api/books/upload")
    async def _upload(file: UploadFile = File(...)) -> JSONResponse:
        """Save an upload (confined, sanitized) and draft it on a thread.

        Token is enforced by the middleware (POST always needs it).
        Returns 202 ``{book_id, filename, status}``; draft errors surface
        later in the book detail as ``{file, rule, message}``.
        """
        raw_name = file.filename or ""
        safe = sanitize_filename(raw_name)
        suffix = Path(safe).suffix.lower()
        if suffix not in (".epub", ".pdf"):
            return JSONResponse(
                _error_shape(safe, "bad-extension", f"{safe}: bad-extension: need .epub or .pdf"),
                status_code=400,
            )
        try:
            dest = safe_join(root, safe)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), safe, "path-traversal"), status_code=400
            )
        final = dest
        final_name = safe
        if final.exists():
            stem = Path(safe).stem
            ext = Path(safe).suffix
            placed = False
            for num in range(1, 1000):
                candidate_name = f"{stem}-{num}{ext}"
                try:
                    candidate = safe_join(root, candidate_name)
                except ValueError as exc:
                    return JSONResponse(
                        _split_shaped(str(exc), candidate_name, "path-traversal"),
                        status_code=400,
                    )
                if not candidate.exists():
                    final = candidate
                    final_name = candidate_name
                    placed = True
                    break
            if not placed:
                return JSONResponse(
                    _error_shape(safe, "name-taken", f"{safe}: name-taken: too many copies"),
                    status_code=409,
                )
        try:
            data = await file.read()
        except Exception as exc:
            return JSONResponse(
                _error_shape(final_name, "unreadable", f"{final_name}: unreadable: {exc}"),
                status_code=400,
            )
        if not data:
            return JSONResponse(
                _error_shape(
                    final_name, "empty-file", f"{final_name}: empty-file: upload is empty"
                ),
                status_code=400,
            )
        try:
            final.parent.mkdir(parents=True, exist_ok=True)
            final.write_bytes(data)
        except OSError as exc:
            return JSONResponse(
                _error_shape(final_name, "unwritable", f"{final_name}: unwritable: {exc}"),
                status_code=400,
            )
        try:
            from draft import book_id_for_file

            book_id, _sha = book_id_for_file(final)
        except OSError as exc:
            return JSONResponse(
                _error_shape(final_name, "unreadable", f"{final_name}: unreadable: {exc}"),
                status_code=400,
            )
        except Exception as exc:
            return JSONResponse(
                _error_shape(final_name, "draft-failed", f"{final_name}: draft-failed: {exc}"),
                status_code=400,
            )
        app.state.draft_jobs[book_id] = {
            "status": "drafting",
            "filename": final.name,
            "error": None,
        }
        thread = threading.Thread(
            target=_run_draft_job,
            args=(app, root, final, book_id, final.name),
            daemon=True,
        )
        thread.start()
        return JSONResponse(
            {"book_id": book_id, "filename": final.name, "status": "drafting"},
            status_code=202,
        )

    return app
