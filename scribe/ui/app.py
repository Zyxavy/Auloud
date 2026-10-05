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

"""UI4 app factory: UI3 books routes plus the job runner (plan UI4).

Kept from UI2/UI3: ``GET /api/health``, ``GET /`` (Books UI with the
``{{AULOUD_TOKEN}}`` slot filled per run) and ``POST /api/echo`` (the tested
token example). No CORS middleware anywhere; the Host/token middleware
answers 421/403 with generic bodies that never echo the offending Host or
the expected token.

UI4 adds (thin operators over :mod:`ui.jobs`; no library behavior change):

- ``POST /api/books/{id}/build {chapters?, pages?, device?}`` — queue a
  detached ``scribe build`` job (202 + job id). One render per book (busy
  books refuse with ``work-lock-held``); across books a single FIFO queues.
- ``GET /api/jobs`` — history (directory scan, newest first).
- ``GET /api/jobs/{id}`` — one job (job ids are strict ``[A-Za-z0-9_-]``).
- ``POST /api/jobs/{id}/pause|resume|cancel`` — pause is a graceful stop at
  a sentence boundary (stop-sentinel file the build polls; cache untouched),
  resume starts a new process that skips cached work, cancel terminates and
  cleans the in-progress chapter's torn files.
- ``GET /api/jobs/{id}/events`` — SSE over the job's ``events.jsonl``
  (``Last-Event-ID`` reconnect, heartbeat comments, file replay).
- ``GET/PUT /api/settings`` — the keep-awake toggle (persisted workspace
  JSON, default on). ``PUT`` needs the token (middleware covers PUT).
- Upload ``POST /api/books/upload`` now creates a draft *job* (same store,
  same FIFO, ``draft_start``/``draft_done``/``draft_failed`` lines in the
  UI1 key contract, mirrored to the work-dir seam for UI3 readers) instead
  of a bare daemon thread.
"""

from __future__ import annotations

import asyncio
import html
import json
import logging
from pathlib import Path
from typing import Any

from fastapi import FastAPI, File, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse

from .books import (
    find_source_files,
    get_book_detail,
    scan_books,
)
from .jobs import JobManager, check_job_id
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

_HEARTBEAT_S = 15.0
_TAIL_POLL_S = 0.5

log = logging.getLogger("auloud.ui")


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


def _job_error_response(exc: Exception, fallback_file: str) -> JSONResponse:
    """Map job ``ValueError``/``KeyError`` to a shaped 400/404/409 response."""
    if isinstance(exc, KeyError):
        shaped = _split_shaped(str(exc), fallback_file, "job-not-found")
        return JSONResponse(shaped, status_code=404)
    shaped = _split_shaped(str(exc), fallback_file, "bad-request")
    rule = shaped.get("rule", "")
    if rule in ("bad-state", "work-lock-held", "cannot-pause", "name-taken"):
        return JSONResponse(shaped, status_code=409)
    if rule in ("path-traversal", "bad-job-id", "bad-device", "bad-request",
                "bad-extension", "chapters-pages-exclusive"):
        return JSONResponse(shaped, status_code=400)
    # Unknown ValueErrors from the manager are conflicts when they name a
    # busy book, else bad requests.
    text = str(exc)
    if "work-lock-held" in text or "bad-state" in text:
        return JSONResponse(shaped, status_code=409)
    return JSONResponse(shaped, status_code=400)


def _effective_draft_jobs(app: FastAPI) -> dict[str, dict[str, Any]]:
    """In-memory draft states plus live draft-job states (read-only merge).

    UI3 wrote ``app.state.draft_jobs`` from its thread; UI4 draft jobs live
    in the job store. Both feed ``scan_books``/``get_book_detail`` so old
    and new drafts read identically. Manager states map to the UI3
    vocabulary (queued/running/paused/interrupted all read as ``drafting``;
    terminal states keep their names so the tray can tell them apart).
    """
    merged: dict[str, dict[str, Any]] = {}
    try:
        mem = app.state.draft_jobs
        if isinstance(mem, dict):
            for key, value in mem.items():
                if isinstance(value, dict):
                    merged[str(key)] = value
    except AttributeError:
        pass
    try:
        manager: JobManager | None = app.state.jobs
    except AttributeError:
        return merged
    if manager is None:
        return merged
    try:
        jobs = manager.list_jobs()
    except Exception:
        return merged
    for job in jobs:
        if job.get("kind") != "draft":
            continue
        book_id = job.get("book_id")
        if not isinstance(book_id, str) or not book_id:
            continue
        state = str(job.get("state") or "")
        if state in ("queued", "running", "paused", "interrupted"):
            status = "drafting"
        elif state in ("done", "failed", "cancelled"):
            status = state
        else:
            status = "drafting"
        merged[book_id] = {
            "status": status,
            "filename": job.get("source_file") or "",
            "error": job.get("error"),
        }
    return merged


def _source_rel_for_book(workspace: Path, book_id: str) -> str | None:
    """Workspace-relative source path for ``book_id`` (None when unknown)."""
    try:
        from draft import book_id_for_file
    except ImportError:
        return None
    for source in find_source_files(workspace):
        try:
            candidate, _sha = book_id_for_file(source)
        except OSError:
            continue
        except Exception:
            continue
        if candidate == book_id:
            try:
                return source.relative_to(workspace).as_posix()
            except ValueError:
                return source.name
    return None


def create_app(
    workspace: Path | str,
    token: str | None = None,
    *,
    job_manager: JobManager | None = None,
    spawn_fn: Any | None = None,
    pid_alive_fn: Any | None = None,
) -> FastAPI:
    """Build the UI4 app bound to ``workspace`` (confined root).

    :param workspace: file access below this root only (see ``safe_join``).
    :param token: per-run token; a fresh ``secrets`` token when omitted.
    :param job_manager: injected manager (tests); otherwise one is created.
    :param spawn_fn: fake detached spawner for ``JobManager`` (tests only).
    :param pid_alive_fn: fake PID liveness for ``JobManager`` (tests only).
    """
    root = Path(workspace).resolve()
    run_token = token or generate_token()

    from contextlib import asynccontextmanager

    @asynccontextmanager
    async def _lifespan(_: FastAPI):  # type: ignore[no-untyped-def]
        yield
        try:
            running = manager.running_ids()
        except Exception:
            running = []
        if running:
            # Refuse silent orphaning: the detached children keep running
            # (their book locks stay held), and the next start marks whatever
            # died meanwhile ``interrupted``. The log line is the warning.
            log.warning(
                "scribe ui stopping with %d running job(s): %s",
                len(running),
                ", ".join(running),
            )

    app = FastAPI(
        title="Auloud Scribe UI",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=_lifespan,
    )
    app.state.workspace = root
    app.state.token = run_token
    # UI3 in-memory draft states (kept for readers that never went through
    # the job store); UI4 merges live draft-job states over them.
    app.state.draft_jobs = {}
    manager = job_manager or JobManager(root, spawn_fn=spawn_fn, pid_alive_fn=pid_alive_fn)
    app.state.jobs = manager

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
        books = scan_books(root, draft_jobs=_effective_draft_jobs(app))
        return {"books": books}

    @app.get("/api/books/{book_id}")
    async def _book_detail(book_id: str) -> JSONResponse:
        """Stepper state for one book, from work-dir files only."""
        try:
            detail = get_book_detail(
                root, book_id, draft_jobs=_effective_draft_jobs(app)
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
        """Save an upload (confined, sanitized) and queue a draft job.

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
        try:
            rel = final.relative_to(root).as_posix()
        except ValueError:
            rel = final.name
        try:
            job = manager.create_draft(book_id, rel, final.name)
        except ValueError as exc:
            return _job_error_response(exc, final_name)
        try:
            app.state.draft_jobs[book_id] = {
                "status": "drafting",
                "filename": final.name,
                "error": None,
            }
        except Exception:
            pass
        return JSONResponse(
            {"book_id": book_id, "filename": final.name, "status": "drafting",
             "job_id": job["id"]},
            status_code=202,
        )

    @app.post("/api/books/{book_id}/build")
    async def _build(book_id: str, payload: dict[str, Any] | None = None) -> JSONResponse:
        """Queue a detached build job (202 + job id; 409 when the book is busy)."""
        from .books import check_book_id

        try:
            check_book_id(book_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )
        body = payload or {}
        chapters = body.get("chapters")
        pages = body.get("pages")
        device = body.get("device") or "cpu"
        for key in ("chapters", "pages", "device"):
            value = body.get(key)
            if value is not None and not isinstance(value, str):
                return JSONResponse(
                    _error_shape(
                        book_id, "bad-request", f"{book_id}: bad-request: {key} must be a string"
                    ),
                    status_code=400,
                )
        source_rel = _source_rel_for_book(root, book_id)
        if source_rel is None:
            return JSONResponse(
                _error_shape(
                    book_id,
                    "book-not-found",
                    f"{book_id}: book-not-found: no source file matches",
                ),
                status_code=404,
            )
        try:
            job = manager.create_build(
                book_id, source_rel, chapters=chapters, pages=pages, device=device
            )
        except ValueError as exc:
            return _job_error_response(exc, book_id)
        return JSONResponse(
            {"job_id": job["id"], "book_id": book_id, "state": job["state"]},
            status_code=202,
        )

    @app.get("/api/jobs")
    async def _jobs_list() -> dict[str, Any]:
        """Job history (directory scan, newest first)."""
        return {"jobs": manager.list_jobs()}

    @app.get("/api/jobs/{job_id}")
    async def _job_detail(job_id: str) -> JSONResponse:
        """One job (400 on bad id shape, 404 when unknown)."""
        try:
            check_job_id(job_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), job_id, "bad-job-id"), status_code=400
            )
        try:
            return JSONResponse(manager.get_job(job_id))
        except (KeyError, ValueError) as exc:
            return _job_error_response(exc, job_id)

    @app.post("/api/jobs/{job_id}/pause")
    async def _job_pause(job_id: str) -> JSONResponse:
        """Graceful stop at a sentence boundary (state ``paused`` on success)."""
        try:
            check_job_id(job_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), job_id, "bad-job-id"), status_code=400
            )
        try:
            return JSONResponse(manager.pause(job_id))
        except (KeyError, ValueError) as exc:
            return _job_error_response(exc, job_id)

    @app.post("/api/jobs/{job_id}/resume")
    async def _job_resume(job_id: str) -> JSONResponse:
        """New process for a paused/interrupted/failed/cancelled job (cache reused)."""
        try:
            check_job_id(job_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), job_id, "bad-job-id"), status_code=400
            )
        try:
            return JSONResponse(manager.resume(job_id))
        except (KeyError, ValueError) as exc:
            return _job_error_response(exc, job_id)

    @app.post("/api/jobs/{job_id}/cancel")
    async def _job_cancel(job_id: str) -> JSONResponse:
        """Terminate the process and clean the partial chapter (state ``cancelled``)."""
        try:
            check_job_id(job_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), job_id, "bad-job-id"), status_code=400
            )
        try:
            return JSONResponse(manager.cancel(job_id))
        except (KeyError, ValueError) as exc:
            return _job_error_response(exc, job_id)

    @app.get("/api/jobs/{job_id}/events")
    async def _job_events(request: Request, job_id: str) -> StreamingResponse:
        """SSE over the job's ``events.jsonl`` (replay + tail + heartbeats)."""
        try:
            check_job_id(job_id)
        except ValueError as exc:
            return JSONResponse(  # type: ignore[return-value]
                _split_shaped(str(exc), job_id, "bad-job-id"), status_code=400
            )
        try:
            events_path = manager.events_path(job_id)
        except KeyError as exc:
            return JSONResponse(  # type: ignore[return-value]
                _split_shaped(str(exc), job_id, "job-not-found"), status_code=404
            )
        raw_last = request.headers.get("last-event-id")
        if raw_last is None:
            raw_last = request.query_params.get("lastEventId")
        try:
            offset = int(str(raw_last).strip()) if raw_last not in (None, "") else 0
        except (TypeError, ValueError):
            offset = 0
        if offset < 0:
            offset = 0

        async def _stream():  # type: ignore[no-untyped-def]
            # Heartbeat first so reconnect/watchdog tests see one at once;
            # the periodic one keeps idle connections (and Edge) alive.
            yield ": ping\n\n"
            last_heartbeat = asyncio.get_event_loop().time()
            sent = offset
            while True:
                if await request.is_disconnected():
                    break
                try:
                    text = events_path.read_text(encoding="utf-8")
                except OSError:
                    text = ""
                lines = text.splitlines()
                while sent < len(lines):
                    line = lines[sent]
                    sent += 1
                    if not line.strip():
                        continue
                    try:
                        json.loads(line)
                    except ValueError:
                        continue  # skip torn tail writes; the next poll retries
                    yield f"id: {sent}\ndata: {line}\n\n"
                try:
                    job = manager.get_job(job_id)
                except (KeyError, ValueError):
                    break
                if job.get("state") in ("done", "failed", "cancelled", "paused", "interrupted"):
                    # One last drain (a done line may have landed mid-poll),
                    # then close so TestClient-style readers terminate.
                    try:
                        text = events_path.read_text(encoding="utf-8")
                    except OSError:
                        text = ""
                    lines = text.splitlines()
                    while sent < len(lines):
                        line = lines[sent]
                        sent += 1
                        if not line.strip():
                            continue
                        try:
                            json.loads(line)
                        except ValueError:
                            continue
                        yield f"id: {sent}\ndata: {line}\n\n"
                    break
                now = asyncio.get_event_loop().time()
                if now - last_heartbeat >= _HEARTBEAT_S:
                    yield ": ping\n\n"
                    last_heartbeat = now
                await asyncio.sleep(_TAIL_POLL_S)

        return StreamingResponse(
            _stream(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "X-Accel-Buffering": "no",
                "Connection": "keep-alive",
            },
        )

    @app.get("/api/settings")
    async def _settings_get() -> dict[str, Any]:
        """Keep-awake toggle (default on; persisted workspace JSON)."""
        from . import keepawake as _keepawake

        return _keepawake.get_settings(root)

    @app.put("/api/settings")
    async def _settings_put(payload: dict[str, Any]) -> JSONResponse:
        """Persist the keep-awake toggle (token-guarded via the middleware)."""
        from . import keepawake as _keepawake

        if not isinstance(payload, dict) or "keep_awake" not in payload:
            return JSONResponse(
                _error_shape(
                    "settings", "bad-request", "settings: bad-request: need {keep_awake: bool}"
                ),
                status_code=400,
            )
        value = payload["keep_awake"]
        if not isinstance(value, bool):
            return JSONResponse(
                _error_shape(
                    "settings", "bad-request", "settings: bad-request: keep_awake must be bool"
                ),
                status_code=400,
            )
        # Confine explicitly (defense in depth; the manager never leaves root).
        try:
            safe_join(root, _keepawake.SETTINGS_FILENAME)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), "settings", "path-traversal"), status_code=400
            )
        return JSONResponse(_keepawake.set_enabled(root, value))

    return app
