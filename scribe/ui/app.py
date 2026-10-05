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

"""UI8 app factory: UI7 plus Validate and Transfer (plan UI8).

Kept from UI2/UI3: ``GET /api/health``, ``GET /`` (Books UI with the
``{{AULOUD_TOKEN}}`` slot filled per run) and ``POST /api/echo`` (the tested
token example). No CORS middleware anywhere; the Host/token middleware
answers 421/403 with generic bodies that never echo the offending Host or
the expected token.

UI7 adds (thin operators over :mod:`ui.cast` versioned cache plus the UI4
job store; the server never loads the TTS model):

- ``GET /api/cast/voices`` — now with ``engine_version`` (package metadata,
  never a model load), ``sample_text``/``text_hash`` and per-voice
  ``details`` (``{name, locale, gender}`` from the Kokoro id prefix).
- ``GET /api/voices/{name}.wav`` — versioned clip first
  (``voice-samples/<version>/<text-hash>/`` with immutable caching), then
  the legacy flat UI6 cache; absent clips are 409 ``sample-missing`` with
  the seeding command plus, when a generation job already covers the clip,
  its ``job_id`` (coalesced: the UI polls the job, then retries; ``GET``
  never starts a job itself, so no token bypass).
- ``POST /api/voices/sample {voices?, regenerate?}`` — queue a detached
  ``voices-sample`` job (202 + job id; 200 ``cached`` when every requested
  clip is already versioned-cached). Concurrent posts for the same missing
  clips coalesce to the running job (202 with the same id plus
  ``coalesced: true`` — queued-on-same, see the UI7 D-entry).
- ``POST /api/voices/{name}/sample {regenerate?}`` — per-voice convenience
  for the same job (same 200/202 contract).

UI6 adds (thin operators over :mod:`ui.cast` and :mod:`text.cast`; the
``cast.yaml`` model, merge and validation stay in the library):

- ``GET /api/books/{id}/cast`` — parsed cast, file mtime (the conflict
  guard), ``has_comments`` save warning, per-character stats (lines from
  draft resolution, minutes from bundle timings when a valid bundle exists),
  the minor-voices collapsible source, and the current validation errors.
- ``PUT /api/books/{id}/cast {cast, expected_mtime}`` — full replace with
  the mtime guard (409 + current state on conflict).
- ``PATCH /api/books/{id}/cast {ops, expected_mtime}`` — preferred; typed
  ops (``set_voice``, ``set_speed``, ``set_first_person``, ``add_override``,
  ``remove_override``), atomic: every op validates before any write, and the
  response names how many quote lines re-voice (the Render-step delta).
- ``GET /api/books/{id}/cast/report`` — ``cast_report.md`` as escaped text
  (``markdown`` raw plus ``html`` fully escaped; the view renders
  ``textContent``, so no raw HTML ever executes) with low-confidence keys.
- ``GET /api/books/{id}/quotes?confidence=low&page=&per_page=`` — the
  overrides picker (chapter/block/quote/text/raw speaker/confidence plus
  the resolved character); paged with a total (novels have thousands).
- ``GET /api/cast/voices`` — the D-036 palette dropdown (no model loaded;
  UI7 adds the engine version plus locale/gender details; full engine
  enumeration beyond the palette stays deferred).
- ``GET /api/voices/{name}.wav`` — cached audition clip (exact
  ``<voice>.wav`` or ``*-<voice>.wav`` from ``scribe voices --sample``);
  absent clips are 409 ``sample-missing`` with the seeding command — the
  server never synths inline (one Kokoro clip costs seconds plus model
  load), and UI7 owns the generation job. The ``GET`` path is the contract
  UI7 extends (versioned first, flat fallback, coalesced job reference).


Kept from UI2/UI3: ``GET /api/health``, ``GET /`` (Books UI with the
``{{AULOUD_TOKEN}}`` slot filled per run) and ``POST /api/echo`` (the tested
token example). No CORS middleware anywhere; the Host/token middleware
answers 421/403 with generic bodies that never echo the offending Host or
the expected token.

UI5 adds (thin operators over :mod:`build`; no render-semantics change):

- ``POST /api/books/{id}/plan {chapters?, pages?}`` — preflight from
  :func:`build.plan_build` (cached vs to-render sentences/chapters, time
  estimate plus its RTF basis, resolved chapter list, page resolution).
  Errors reuse the CLI's ``{file, rule, message}`` shapes (``bad-range``,
  ``chapters-pages-exclusive``, ``pages-need-pdf``).
- ``GET/PUT /api/settings`` — now ``{keep_awake, device}`` (device
  ``auto``/``cpu``/``cuda``, default ``auto``); ``PUT`` validates with the
  CLI-identical ``bad-device``/``cuda-unavailable`` wording. The
  build-create route below uses the stored device when a request omits it.
  ``PUT`` needs the token (middleware covers PUT).

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
- ``GET/PUT /api/settings`` — the keep-awake toggle plus the compute
  device (persisted workspace JSON, defaults on / ``auto``).
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
from fastapi.responses import (
    FileResponse,
    HTMLResponse,
    JSONResponse,
    StreamingResponse,
)

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
    if rule in ("bad-state", "work-lock-held", "cannot-pause", "name-taken",
                "exists", "no-valid-bundle"):
        return JSONResponse(shaped, status_code=409)
    if rule in ("path-traversal", "bad-job-id", "bad-device", "bad-request",
                "bad-extension", "chapters-pages-exclusive", "bad-range",
                "pages-need-pdf", "pages-no-cover", "cuda-unavailable",
                "plan-failed", "draft-failed", "bad-destination",
                "open-unsupported", "unwritable", "verify-failed"):
        return JSONResponse(shaped, status_code=400)
    # Unknown ValueErrors from the manager are conflicts when they name a
    # busy book, else bad requests.
    text = str(exc)
    if "work-lock-held" in text or "bad-state" in text or ": exists:" in text:
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
    plan_engine: Any | None = None,
    voices_version: str | None = None,
    validate_probe: Any | None = None,
    drives_fn: Any | None = None,
    open_fn: Any | None = None,
) -> FastAPI:
    """Build the UI8 app bound to ``workspace`` (confined root).

    :param workspace: file access below this root only (see ``safe_join``).
    :param token: per-run token; a fresh ``secrets`` token when omitted.
    :param job_manager: injected manager (tests); otherwise one is created.
    :param spawn_fn: fake detached spawner for ``JobManager`` (tests only).
    :param pid_alive_fn: fake PID liveness for ``JobManager`` (tests only).
    :param plan_engine: TTS engine for the preflight route (tests inject a
        fake so fingerprints resolve without models; production passes None
        and :func:`build.plan_build` tries the real engine, falling back to
        an all-to-render preflight when models are missing).
    :param voices_version: engine version for the audition cache (tests
        inject ``fake-1`` then ``fake-2`` to prove version-bump regen;
        production passes None and the package metadata is read live,
        never loading the model).
    :param validate_probe: ffprobe seam for the validate route and the
        book readers (tests inject a stub so no real ffprobe runs;
        production passes None and the real ffprobe path is used).
    :param drives_fn: removable-drive lister for ``GET /api/drives``
        (tests inject a fake; production passes None and
        :func:`transfer.list_drives` runs the ctypes probe).
    :param open_fn: folder opener for the open-folder route (tests inject
        a recorder; production passes None and ``os.startfile`` runs on
        Windows only).
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
    app.state.plan_engine = plan_engine
    app.state.voices_version = voices_version
    app.state.validate_probe = validate_probe
    app.state.drives_fn = drives_fn
    app.state.open_fn = open_fn

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
        try:
            probe = app.state.validate_probe
        except AttributeError:
            probe = None
        books = scan_books(root, draft_jobs=_effective_draft_jobs(app), probe=probe)
        return {"books": books}

    @app.get("/api/books/{book_id}")
    async def _book_detail(book_id: str) -> JSONResponse:
        """Stepper state for one book, from work-dir files only."""
        try:
            probe = app.state.validate_probe
        except AttributeError:
            probe = None
        try:
            detail = get_book_detail(
                root, book_id, draft_jobs=_effective_draft_jobs(app), probe=probe
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
        """Queue a detached build job (202 + job id; 409 when the book is busy).

        ``device`` defaults to the persisted compute setting (``auto`` when
        never stored); an explicit request value wins. Validation
        (``bad-device`` and friends) lives in the manager/child with the
        CLI-identical ``{file, rule, message}`` shapes.
        """
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
        device = body.get("device")
        if device is None:
            try:
                from . import keepawake as _keepawake

                device = _keepawake.get_device(root)
            except Exception:
                device = "auto"
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

    @app.post("/api/books/{book_id}/plan")
    async def _plan(book_id: str, payload: dict[str, Any] | None = None) -> JSONResponse:
        """Preflight for the Render step: cached vs to-render plus estimate.

        Pure (renders no audio, writes no render files, takes no lock).
        Returns the :func:`build.plan_build` numbers with ``message`` set to
        :func:`build.format_plan` output, so the panel wording matches
        ``scribe build --plan``. Selection errors reuse the CLI's
        ``{file, rule, message}`` shapes verbatim (``bad-range``,
        ``chapters-pages-exclusive``, ``pages-need-pdf``, ``pages-no-cover``).
        """
        from .books import WORK_DIRNAME, check_book_id

        try:
            check_book_id(book_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )
        body = payload or {}
        chapters = body.get("chapters")
        pages = body.get("pages")
        for key in ("chapters", "pages"):
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
            from build import format_plan, plan_build
            from draft import DraftError

            try:
                source_abs = safe_join(root, source_rel)
            except ValueError as exc:
                return JSONResponse(
                    _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
                )
            models_dir = root / "models"
            if not models_dir.is_dir():
                models_dir = Path("models")
            plan = plan_build(
                source_abs,
                work_root=root / WORK_DIRNAME,
                chapters=chapters,
                pages=pages,
                engine=app.state.plan_engine,
                models_dir=models_dir,
            )
        except DraftError as exc:
            # DraftError subclasses ValueError: catch first so stale-source
            # failures keep the draft-failed rule, not bad-request.
            return JSONResponse(
                _split_shaped(str(exc), book_id, "draft-failed"), status_code=400
            )
        except ValueError as exc:
            # BuildError + selection errors are already shaped
            # ``file: rule: message`` by the library (bad-range,
            # chapters-pages-exclusive, pages-need-pdf, pages-no-cover).
            return JSONResponse(
                _split_shaped(str(exc), book_id, "bad-request"), status_code=400
            )
        except Exception as exc:  # plan never leaks a traceback
            return JSONResponse(
                _error_shape(book_id, "plan-failed", f"{book_id}: plan-failed: {exc}"),
                status_code=500,
            )
        return JSONResponse(
            {
                "book_id": plan.book_id,
                "title": plan.title,
                "source_chapters_total": plan.source_chapters_total,
                "selected_chapters": plan.selected_chapters,
                "cached_chapters": plan.cached_chapters,
                "to_render_chapters": plan.to_render_chapters,
                "cached_sentences": plan.cached_sentences,
                "to_render_sentences": plan.to_render_sentences,
                "estimated_seconds": plan.estimated_seconds,
                "rtf_used": plan.rtf_used,
                "page_resolution": plan.page_resolution,
                "is_range": plan.is_range,
                "message": format_plan(plan),
            }
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
        """Compute settings (keep-awake default on, device default auto)."""
        from . import keepawake as _keepawake

        return _keepawake.get_settings(root)

    @app.put("/api/settings")
    async def _settings_put(payload: dict[str, Any]) -> JSONResponse:
        """Persist compute settings (token-guarded via the middleware).

        Accepts ``{keep_awake?: bool, device?: "auto"|"cpu"|"cuda"}`` (at
        least one required); unmentioned fields keep their stored values.
        ``device`` is validated live with the CLI-identical wording
        (``bad-device`` for unknown values, ``cuda-unavailable`` when this
        machine's onnxruntime has no CUDA provider).
        """
        from . import keepawake as _keepawake

        if not isinstance(payload, dict) or (
            "keep_awake" not in payload and "device" not in payload
        ):
            return JSONResponse(
                _error_shape(
                    "settings",
                    "bad-request",
                    'settings: bad-request: need {keep_awake?: bool, device?: "auto"|"cpu"|"cuda"}',
                ),
                status_code=400,
            )
        keep_awake: bool | None = None
        if "keep_awake" in payload:
            if not isinstance(payload["keep_awake"], bool):
                return JSONResponse(
                    _error_shape(
                        "settings",
                        "bad-request",
                        "settings: bad-request: keep_awake must be bool",
                    ),
                    status_code=400,
                )
            keep_awake = payload["keep_awake"]
        device: str | None = None
        if "device" in payload:
            raw_device = payload["device"]
            if not isinstance(raw_device, str):
                return JSONResponse(
                    _error_shape(
                        "device",
                        "bad-device",
                        f"device: bad-device: {raw_device!r} must be one of "
                        "['auto', 'cpu', 'cuda']",
                    ),
                    status_code=400,
                )
            try:
                from device import resolve_device_providers
            except ImportError as exc:
                return JSONResponse(
                    _error_shape(
                        "settings", "plan-failed", f"settings: plan-failed: {exc}"
                    ),
                    status_code=500,
                )
            try:
                resolve_device_providers(raw_device)
            except ValueError as exc:
                # Already shaped ``device: bad-device|cuda-unavailable: ...``
                # by the library (wording identical to the CLI).
                return JSONResponse(
                    _split_shaped(str(exc), "device", "bad-device"), status_code=400
                )
            device = raw_device.strip().lower()
        # Confine explicitly (defense in depth; the manager never leaves root).
        try:
            safe_join(root, _keepawake.SETTINGS_FILENAME)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), "settings", "path-traversal"), status_code=400
            )
        return JSONResponse(
            _keepawake.set_settings(root, keep_awake=keep_awake, device=device)
        )

    # --- UI6: Cast view ----------------------------------------------------
    # Thin operators over ui.cast (pure ops) and text.cast (model/merge/
    # validation). cast.yaml stays the source of truth; every write is
    # validated verbatim and mtime-guarded (409 + current state on conflict).

    def _cast_work_dir(book_id: str) -> tuple[Path | None, JSONResponse | None]:
        """``.scribe/<book_id>`` confined, or the 400 for a bad id."""
        from .books import WORK_DIRNAME, check_book_id

        try:
            check_book_id(book_id)
        except ValueError as exc:
            return None, JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )
        try:
            return safe_join(root, WORK_DIRNAME, book_id), None
        except ValueError as exc:
            return None, JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )

    def _read_script(work_dir: Path) -> dict[str, Any] | None:
        path = work_dir / "script.json"
        if not path.is_file():
            return None
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _narration_lines(script: dict[str, Any] | None) -> int:
        if not script or not isinstance(script.get("chapters"), list):
            return 0
        total = 0
        for chapter in script["chapters"]:
            if not isinstance(chapter, dict):
                continue
            for block in chapter.get("blocks") or []:
                if not isinstance(block, dict):
                    continue
                for sentence in block.get("sentences") or []:
                    if isinstance(sentence, dict) and sentence.get("kind") != "dialogue":
                        total += 1
        return total

    def _bundle_speaker_ms(book_id: str) -> tuple[dict[str, int], bool]:
        """``(speaker_ms, has_audio)`` from the first valid mapped bundle.

        Only the first valid bundle counts (a book can hold a full plus
        range bundles; summing all of them would double-count shared
        chapters). Read-only; any inspection problem means no minutes.
        """
        from .books import get_book_detail

        try:
            detail = get_book_detail(root, book_id)
        except (ValueError, KeyError):
            return {}, False
        for bundle in detail.get("bundles") or []:
            if isinstance(bundle, dict) and bundle.get("valid") and bundle.get("path"):
                break
        else:
            return {}, False
        try:
            from bundle.inspect import inspect_bundle

            result = inspect_bundle(root / str(bundle["path"]))
        except Exception:
            return {}, False
        return dict(result.speaker_ms), True

    def _cast_payload(
        book_id: str, work_dir: Path, cast: dict[str, Any], mtime: float, has_comments: bool
    ) -> dict[str, Any]:
        from . import cast as _castmod

        script = _read_script(work_dir)
        quotes = _castmod.read_script_quotes(script) if script is not None else []
        speaker_ms, has_audio = _bundle_speaker_ms(book_id)
        rows, minor = _castmod.build_cast_stats(
            cast, quotes, _narration_lines(script), speaker_ms
        )
        low_total = sum(1 for q in quotes if q.get("confidence") == "low")
        validation = _castmod.shaped_validation_errors(cast)
        payload: dict[str, Any] = {
            "book_id": book_id,
            "mtime": mtime,
            "has_comments": has_comments,
            "cast": cast,
            "first_person": cast.get("first_person", "narrator"),
            "stats": rows,
            "minor": minor,
            "quotes_total": len(quotes),
            "low_confidence_total": low_total,
            "has_audio": has_audio,
            "validation": validation,
        }
        if has_comments:
            payload["comment_warning"] = _castmod.COMMENT_WARNING
        return payload

    def _cast_patch_error(exc: Exception, fallback_file: str) -> JSONResponse:
        from .cast import CastPatchError

        if isinstance(exc, CastPatchError):
            body = exc.shaped()
            status = 404 if exc.rule == "cast-not-found" else 400
            return JSONResponse(body, status_code=status)
        return JSONResponse(
            _split_shaped(str(exc), fallback_file, "bad-request"), status_code=400
        )

    def _voices_version() -> str:
        """Audition engine version (injected fake in tests; else live metadata)."""
        from . import cast as _castmod

        try:
            injected = app.state.voices_version
        except AttributeError:
            injected = None
        if isinstance(injected, str) and injected.strip():
            return injected
        return _castmod.get_engine_version()

    def _voices_hash() -> str:
        from . import cast as _castmod

        return _castmod.sample_text_hash()

    def _wanted_or_palette(raw: Any) -> tuple[list[str] | None, JSONResponse | None]:
        """Validate an optional ``voices`` list (None means the palette)."""
        from .cast import PALETTE_VOICES, check_voice_name

        if raw is None:
            return None, None
        if not isinstance(raw, list) or not raw:
            return None, JSONResponse(
                _error_shape(
                    "voices",
                    "bad-request",
                    "voices: bad-request: voices must be a non-empty list (or omit for all)",
                ),
                status_code=400,
            )
        wanted: list[str] = []
        for entry in raw:
            if not isinstance(entry, str):
                return None, JSONResponse(
                    _error_shape(
                        "voices",
                        "bad-request",
                        f"voices: bad-request: voice must be a string (got {entry!r})",
                    ),
                    status_code=400,
                )
            try:
                name = check_voice_name(entry)
            except ValueError as exc:
                return None, JSONResponse(
                    _split_shaped(str(exc), entry, "path-traversal"), status_code=400
                )
            if name not in PALETTE_VOICES:
                return None, JSONResponse(
                    _error_shape(
                        f"{name}.wav",
                        "unknown-voice",
                        f"{name}.wav: unknown-voice: {name!r} is not in the UI7 palette "
                        "(full engine enumeration stays deferred)",
                    ),
                    status_code=400,
                )
            wanted.append(name)
        return sorted(set(wanted)), None

    def _sample_job_response(
        wanted: list[str], *, regenerate: bool
    ) -> JSONResponse:
        """200-cached or 202-job for ``wanted`` (coalesced when covered)."""
        from . import cast as _castmod

        version = _voices_version()
        thash = _voices_hash()
        if not regenerate and all(
            _castmod.find_versioned_sample(root, voice, version, thash) is not None
            for voice in wanted
        ):
            return JSONResponse(
                {
                    "cached": True,
                    "voices": wanted,
                    "engine_version": version,
                    "text_hash": thash,
                }
            )
        active = manager.find_active_voices_job(wanted, version, thash)
        if active is not None:
            return JSONResponse(
                {
                    "job_id": active["id"],
                    "state": active.get("state"),
                    "voices": wanted,
                    "engine_version": version,
                    "text_hash": thash,
                    "coalesced": True,
                },
                status_code=202,
            )
        try:
            created = manager.create_voices_sample(wanted, version, thash)
        except (ValueError, KeyError) as exc:
            return _job_error_response(exc, "voices")
        return JSONResponse(
            {
                "job_id": created["id"],
                "state": created.get("state"),
                "voices": wanted,
                "engine_version": version,
                "text_hash": thash,
                "coalesced": False,
            },
            status_code=202,
        )

    @app.get("/api/cast/voices")
    async def _cast_voices() -> dict[str, Any]:
        """Voice dropdown + grid source (D-036 palette; no model loaded)."""
        from .cast import PALETTE_VOICES, palette_details

        try:
            from tts.voices import SAMPLE_TEXT as _SAMPLE_TEXT
        except ImportError:
            from .cast import sample_text as _sample_text_fn

            _SAMPLE_TEXT = _sample_text_fn()
        version = _voices_version()
        thash = _voices_hash()
        return {
            "voices": list(PALETTE_VOICES),
            "source": "palette",
            "engine_version": version,
            "sample_text": _SAMPLE_TEXT,
            "text_hash": thash,
            "details": palette_details(),
            "note": (
                "UI7 palette grid source (D-036 plus legacy af_heart) with "
                "the engine version and locale/gender hints. Clips are "
                "versioned by (voice, engine version, sample text); full "
                "engine enumeration beyond the palette stays deferred."
            ),
        }

    @app.get("/api/voices/{name}.wav")
    async def _voice_sample(name: str) -> Any:
        """Versioned audition clip, legacy flat fallback, or 409 + job ref.

        Cached clips stream immediately (the server never synths inline).
        Missing clips are 409 ``sample-missing``: with ``job_id`` when a
        generation job already covers the clip (coalesced — poll the job,
        then retry), else without one (POST ``/api/voices/sample`` first).
        ``GET`` never starts a job itself, so the token exemption for GET
        cannot trigger state changes. Range requests ride the framework's
        ``FileResponse`` (not hand-rolled).
        """
        from . import cast as _castmod

        try:
            voice = _castmod.check_voice_name(name)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), name, "path-traversal"), status_code=400
            )
        version = _voices_version()
        thash = _voices_hash()
        found, is_versioned = _castmod.find_cached_clip(root, voice, version, thash)
        if found is not None:
            cache = (
                "public, max-age=31536000, immutable"
                if is_versioned
                else "no-cache"
            )
            return FileResponse(
                str(found),
                media_type="audio/wav",
                filename=f"{voice}.wav",
                headers={"Cache-Control": cache},
            )
        active = manager.find_active_voices_job([voice], version, thash)
        body: dict[str, Any] = _error_shape(
            f"{voice}.wav",
            "sample-missing",
            f"{voice}.wav: sample-missing: no cached clip; run "
            "`scribe voices --sample --out-dir <workspace>/.scribe/voice-samples` "
            "or POST /api/voices/sample to generate it (UI polls the job, then retries)",
        )
        body["engine_version"] = version
        body["text_hash"] = thash
        if active is not None:
            body["job_id"] = active["id"]
            body["job_state"] = active.get("state")
        else:
            body["job_id"] = None
        return JSONResponse(body, status_code=409)

    @app.post("/api/voices/sample")
    async def _voices_sample(payload: dict[str, Any] | None = None) -> JSONResponse:
        """Queue a detached audition render (202 + job; 200 when cached).

        Body ``{voices?: [...], regenerate?: bool}`` (token-guarded via the
        middleware). Omitted ``voices`` means the whole palette. Concurrent
        posts for the same missing clips coalesce to the running job (202
        with the same id plus ``coalesced: true`` — queued-on-same).
        """
        from .cast import PALETTE_VOICES

        body = payload or {}
        wanted, bad = _wanted_or_palette(body.get("voices"))
        if bad is not None:
            return bad
        if wanted is None:
            wanted = list(PALETTE_VOICES)
        regenerate = body.get("regenerate", False)
        if not isinstance(regenerate, bool):
            return JSONResponse(
                _error_shape(
                    "voices",
                    "bad-request",
                    "voices: bad-request: regenerate must be bool",
                ),
                status_code=400,
            )
        return _sample_job_response(wanted, regenerate=regenerate)

    @app.post("/api/voices/{name}/sample")
    async def _voice_sample_one(
        name: str, payload: dict[str, Any] | None = None
    ) -> JSONResponse:
        """Per-voice convenience for the same generation job (same contract)."""
        from .cast import PALETTE_VOICES, check_voice_name

        try:
            voice = check_voice_name(name)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), name, "path-traversal"), status_code=400
            )
        if voice not in PALETTE_VOICES:
            return JSONResponse(
                _error_shape(
                    f"{voice}.wav",
                    "unknown-voice",
                    f"{voice}.wav: unknown-voice: {voice!r} is not in the UI7 palette "
                    "(full engine enumeration stays deferred)",
                ),
                status_code=400,
            )
        body = payload or {}
        regenerate = body.get("regenerate", False)
        if not isinstance(regenerate, bool):
            return JSONResponse(
                _error_shape(
                    "voices",
                    "bad-request",
                    "voices: bad-request: regenerate must be bool",
                ),
                status_code=400,
            )
        return _sample_job_response([voice], regenerate=regenerate)

    @app.get("/api/books/{book_id}/cast")
    async def _cast_get(book_id: str) -> JSONResponse:
        """Parsed cast plus stats, mtime guard token and save warnings."""
        from . import cast as _castmod

        work_dir, bad = _cast_work_dir(book_id)
        if bad is not None or work_dir is None:
            return bad  # type: ignore[return-value]
        try:
            cast, mtime, has_comments = _castmod.read_cast_state(work_dir)
        except FileNotFoundError as exc:
            return JSONResponse(
                _split_shaped(str(exc), "cast.yaml", "cast-not-found"), status_code=404
            )
        except Exception as exc:
            return _cast_patch_error(exc, "cast.yaml")
        return JSONResponse(_cast_payload(book_id, work_dir, cast, mtime, has_comments))

    @app.put("/api/books/{book_id}/cast")
    async def _cast_put(book_id: str, payload: dict[str, Any] | None = None) -> JSONResponse:
        """Full replace with the mtime guard (token-guarded via middleware)."""
        from . import cast as _castmod

        work_dir, bad = _cast_work_dir(book_id)
        if bad is not None or work_dir is None:
            return bad  # type: ignore[return-value]
        body = payload or {}
        new_cast = body.get("cast")
        if not isinstance(new_cast, dict):
            return JSONResponse(
                _error_shape(
                    "cast.yaml",
                    "bad-op",
                    "cast.yaml: bad-op: PUT needs {cast: {...}, expected_mtime: <mtime from GET>}",
                ),
                status_code=400,
            )
        try:
            old_cast, _, _ = _castmod.read_cast_state(work_dir)
        except FileNotFoundError as exc:
            return JSONResponse(
                _split_shaped(str(exc), "cast.yaml", "cast-not-found"), status_code=404
            )
        except Exception as exc:
            return _cast_patch_error(exc, "cast.yaml")
        try:
            mtime, had_comments = _castmod.write_cast_guarded(
                work_dir, new_cast, expected_mtime=body.get("expected_mtime")
            )
        except _castmod.CastConflictError as exc:
            return JSONResponse(
                {
                    "file": "cast.yaml",
                    "rule": "conflict",
                    "message": str(exc),
                    "current_mtime": exc.current_mtime,
                    "cast": exc.current_cast,
                },
                status_code=409,
            )
        except Exception as exc:
            return _cast_patch_error(exc, "cast.yaml")
        script = _read_script(work_dir)
        quotes = _castmod.read_script_quotes(script) if script is not None else []
        affected = _castmod.count_changed_quotes(old_cast, new_cast, quotes)
        response = _cast_payload(book_id, work_dir, new_cast, mtime, had_comments)
        response["affected"] = {"quotes": affected}
        response["saved_comments"] = had_comments
        return JSONResponse(response)

    @app.patch("/api/books/{book_id}/cast")
    async def _cast_patch(book_id: str, payload: dict[str, Any] | None = None) -> JSONResponse:
        """Atomic op list (all ops validate before any apply; 409 on conflict)."""
        from . import cast as _castmod

        work_dir, bad = _cast_work_dir(book_id)
        if bad is not None or work_dir is None:
            return bad  # type: ignore[return-value]
        body = payload or {}
        ops = body.get("ops")
        try:
            current, _, _ = _castmod.read_cast_state(work_dir)
        except FileNotFoundError as exc:
            return JSONResponse(
                _split_shaped(str(exc), "cast.yaml", "cast-not-found"), status_code=404
            )
        except Exception as exc:
            return _cast_patch_error(exc, "cast.yaml")
        staged, errors = _castmod.validate_ops_atomic(current, ops or [])
        if errors:
            first = errors[0]
            response = dict(first)
            if len(errors) > 1:
                response["errors"] = errors
            status = 404 if first.get("rule") == "cast-not-found" else 400
            return JSONResponse(response, status_code=status)
        try:
            mtime, had_comments = _castmod.write_cast_guarded(
                work_dir, staged, expected_mtime=body.get("expected_mtime")
            )
        except _castmod.CastConflictError as exc:
            return JSONResponse(
                {
                    "file": "cast.yaml",
                    "rule": "conflict",
                    "message": str(exc),
                    "current_mtime": exc.current_mtime,
                    "cast": exc.current_cast,
                },
                status_code=409,
            )
        except Exception as exc:
            return _cast_patch_error(exc, "cast.yaml")
        script = _read_script(work_dir)
        quotes = _castmod.read_script_quotes(script) if script is not None else []
        affected = _castmod.count_changed_quotes(current, staged, quotes)
        response = _cast_payload(book_id, work_dir, staged, mtime, had_comments)
        response["affected"] = {"quotes": affected}
        response["applied"] = len(ops or [])
        response["saved_comments"] = had_comments
        return JSONResponse(response)

    @app.get("/api/books/{book_id}/cast/report")
    async def _cast_report(book_id: str) -> JSONResponse:
        """``cast_report.md`` read-only: raw markdown plus fully-escaped HTML."""
        from . import cast as _castmod

        work_dir, bad = _cast_work_dir(book_id)
        if bad is not None or work_dir is None:
            return bad  # type: ignore[return-value]
        path = work_dir / _castmod.CAST_REPORT_FILENAME
        if not path.is_file():
            return JSONResponse(
                _error_shape(
                    _castmod.CAST_REPORT_FILENAME,
                    "report-not-found",
                    f"{_castmod.CAST_REPORT_FILENAME}: report-not-found: "
                    "no cast report yet (draft first)",
                ),
                status_code=404,
            )
        try:
            markdown = path.read_text(encoding="utf-8")
            mtime = path.stat().st_mtime
        except OSError as exc:
            return JSONResponse(
                _error_shape(
                    _castmod.CAST_REPORT_FILENAME,
                    "unreadable",
                    f"{_castmod.CAST_REPORT_FILENAME}: unreadable: {exc}",
                ),
                status_code=400,
            )
        script = _read_script(work_dir)
        quotes = _castmod.read_script_quotes(script) if script is not None else []
        low = [
            {
                "chapter": q["chapter"],
                "block": q["block"],
                "quote": q["quote"],
                "text": q["text"],
                "speaker": q["speaker"],
                "confidence": q["confidence"],
            }
            for q in quotes
            if q.get("confidence") == "low"
        ]
        return JSONResponse(
            {
                "book_id": book_id,
                "mtime": mtime,
                "markdown": markdown,
                # Escaped, no raw HTML: the view renders textContent anyway;
                # this field is the belt to that suspenders (tests pin it).
                "html": html.escape(markdown, quote=True),
                "low_confidence": low,
            }
        )

    @app.get("/api/books/{book_id}/quotes")
    async def _quotes(request: Request, book_id: str) -> JSONResponse:
        """Quote picker data: paged dialogue quotes with a low filter."""
        from . import cast as _castmod

        work_dir, bad = _cast_work_dir(book_id)
        if bad is not None or work_dir is None:
            return bad  # type: ignore[return-value]
        params = request.query_params
        confidence = str(params.get("confidence", "all")).strip().lower()
        if confidence not in ("all", "low"):
            return JSONResponse(
                _error_shape(
                    book_id,
                    "bad-request",
                    f"{book_id}: bad-request: confidence must be 'all' or 'low'",
                ),
                status_code=400,
            )
        try:
            page = int(str(params.get("page", "1")).strip())
            default_pp = str(_castmod.QUOTES_DEFAULT_PER_PAGE)
            per_page_raw = str(params.get("per_page", default_pp)).strip()
            per_page = int(per_page_raw)
        except (TypeError, ValueError):
            return JSONResponse(
                _error_shape(
                    book_id,
                    "bad-request",
                    f"{book_id}: bad-request: page and per_page must be ints",
                ),
                status_code=400,
            )
        if page < 1 or per_page < 1 or per_page > _castmod.QUOTES_MAX_PER_PAGE:
            return JSONResponse(
                _error_shape(
                    book_id,
                    "bad-request",
                    f"{book_id}: bad-request: need page >= 1 and 1 <= per_page <= "
                    f"{_castmod.QUOTES_MAX_PER_PAGE}",
                ),
                status_code=400,
            )
        script = _read_script(work_dir)
        if script is None:
            return JSONResponse(
                _error_shape(
                    book_id,
                    "draft-missing",
                    f"{book_id}: draft-missing: no script.json yet (draft first)",
                ),
                status_code=404,
            )
        quotes = _castmod.read_script_quotes(script)
        if confidence == "low":
            quotes = [q for q in quotes if q.get("confidence") == "low"]
        try:
            cast, _, _ = _castmod.read_cast_state(work_dir)
        except Exception:
            cast = None
        resolved = _castmod.resolve_quote_speakers(cast, quotes) if cast is not None else {}
        total = len(quotes)
        start = (page - 1) * per_page
        items: list[dict[str, Any]] = []
        for entry in quotes[start : start + per_page]:
            key = (entry["chapter"], entry["block"], entry["quote"])
            items.append({**entry, "resolved": resolved.get(key)})
        return JSONResponse(
            {
                "book_id": book_id,
                "total": total,
                "page": page,
                "per_page": per_page,
                "confidence": confidence,
                "quotes": items,
            }
        )

    # --- UI8: Validate and Transfer --------------------------------------
    # Validate runs INLINE (read-only, seconds even on large books; see
    # ui/transfer.py for the measured verdict). Transfer copies one bundle
    # to an explicit outside-workspace destination with size+sha256
    # verification and a persisted record that owns the On-tablet chip.

    def _validate_probe() -> Any | None:
        try:
            return app.state.validate_probe
        except AttributeError:
            return None

    @app.post("/api/books/{book_id}/validate")
    async def _validate(book_id: str) -> JSONResponse:
        """Re-run the bundle validator for one book (inline, read-only).

        Returns ``{book_id, valid, validation, bundles}`` with
        validation errors shaped ``{file, rule, message}`` (wording
        identical to ``scribe validate``; rule is ``validation``). Never
        writes; a missing book is 404, a bad id is 400.
        """
        from .books import check_book_id

        try:
            check_book_id(book_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )
        try:
            detail = get_book_detail(
                root,
                book_id,
                draft_jobs=_effective_draft_jobs(app),
                probe=_validate_probe(),
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
        return JSONResponse(
            {
                "book_id": book_id,
                "valid": detail.get("valid", False),
                "validation": detail.get("validation", []),
                "bundles": detail.get("bundles", []),
            }
        )

    @app.get("/api/drives")
    async def _drives() -> dict[str, Any]:
        """Detected removable drives (Windows ctypes only; ``[]`` elsewhere).

        Shape ``{drives: [{path, removable}]}``. Read-only; the typed path
        stays available when no drives are present.
        """
        try:
            fn = app.state.drives_fn
        except AttributeError:
            fn = None
        if callable(fn):
            try:
                drives = fn()
            except Exception:
                drives = []
        else:
            try:
                from .transfer import list_drives as _list_drives

                drives = _list_drives()
            except Exception:
                drives = []
        if not isinstance(drives, list):
            drives = []
        return {"drives": drives}

    @app.post("/api/books/{book_id}/transfer")
    async def _transfer(
        book_id: str, payload: dict[str, Any] | None = None
    ) -> JSONResponse:
        """Copy the book's valid bundle to an outside destination (202+job).

        Body ``{destination: <absolute path>, force?: bool, bundle?: <rel>}``.
        ``destination`` is validated as an absolute path on a real drive
        (relative/``..``/NUL/shell metachars are 400 ``bad-destination``);
        only the SOURCE side is workspace-confined. The bundle copies to
        ``<destination>/<bundle-dirname>``; an existing dir is 409
        ``exists`` unless ``force`` is true (explicit overwrite of that one
        dir only). Progress polls via ``GET /api/jobs/{job_id}``
        (``bytes_copied``/``bytes_total``); verification is size + sha256
        per file and the record owns the On-tablet chip.
        """
        from .books import check_book_id

        try:
            check_book_id(book_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )
        body = payload or {}
        destination = body.get("destination")
        force = body.get("force", False)
        wanted_bundle = body.get("bundle")
        if not isinstance(destination, str) or not destination.strip():
            return JSONResponse(
                _error_shape(
                    book_id,
                    "bad-destination",
                    f"{book_id}: bad-destination: need {destination!r} as an absolute path",
                ),
                status_code=400,
            )
        if not isinstance(force, bool):
            return JSONResponse(
                _error_shape(
                    book_id,
                    "bad-request",
                    f"{book_id}: bad-request: force must be bool",
                ),
                status_code=400,
            )
        if wanted_bundle is not None and not isinstance(wanted_bundle, str):
            return JSONResponse(
                _error_shape(
                    book_id,
                    "bad-request",
                    f"{book_id}: bad-request: bundle must be a string",
                ),
                status_code=400,
            )
        try:
            from .transfer import validate_destination as _validate_dest
        except ImportError as exc:
            return JSONResponse(
                _error_shape(
                    book_id, "plan-failed", f"{book_id}: plan-failed: {exc}"
                ),
                status_code=500,
            )
        try:
            _validate_dest(destination)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), "dest", "bad-destination"), status_code=400
            )
        try:
            detail = get_book_detail(
                root,
                book_id,
                draft_jobs=_effective_draft_jobs(app),
                probe=_validate_probe(),
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
        bundles = detail.get("bundles") or []
        if wanted_bundle:
            picked = next(
                (b for b in bundles if b.get("path") == wanted_bundle), None
            )
            if picked is None:
                return JSONResponse(
                    _error_shape(
                        book_id,
                        "book-not-found",
                        f"{book_id}: book-not-found: no bundle at {wanted_bundle}",
                    ),
                    status_code=404,
                )
            if not picked.get("valid"):
                return JSONResponse(
                    _error_shape(
                        wanted_bundle,
                        "no-valid-bundle",
                        f"{wanted_bundle}: no-valid-bundle: bundle is not valid (validate first)",
                    ),
                    status_code=409,
                )
        else:
            picked = next(
                (b for b in bundles if b.get("valid") and b.get("path")), None
            )
            if picked is None:
                return JSONResponse(
                    _error_shape(
                        book_id,
                        "no-valid-bundle",
                        f"{book_id}: no-valid-bundle: "
                        "no valid bundle yet (render and validate first)",
                    ),
                    status_code=409,
                )
        bundle_rel = str(picked.get("path") or "")
        bundle_id = str(picked.get("id") or "")
        # Source confinement (defense in depth; the manager rechecks).
        try:
            safe_join(root, bundle_rel)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), bundle_rel, "path-traversal"), status_code=400
            )
        try:
            job = manager.create_transfer(
                book_id,
                bundle_rel,
                destination,
                force=force,
                bundle_id=bundle_id,
            )
        except (ValueError, KeyError) as exc:
            return _job_error_response(exc, book_id)
        return JSONResponse(
            {
                "job_id": job["id"],
                "book_id": book_id,
                "state": job.get("state"),
                "bundle": bundle_rel,
                "destination": job.get("destination"),
            },
            status_code=202,
        )

    @app.post("/api/books/{book_id}/open-folder")
    async def _open_folder(
        book_id: str, payload: dict[str, Any] | None = None
    ) -> JSONResponse:
        """Open the transfer destination in the OS file manager (Windows).

        Body ``{destination?: <absolute path>}`` (defaults to the book's
        last transfer record). Token-guarded POST because server-side open
        is a state change. Windows calls ``os.startfile``; elsewhere this
        is 400 ``open-unsupported`` with the printed path still returned.
        """
        from .books import check_book_id

        try:
            check_book_id(book_id)
        except ValueError as exc:
            return JSONResponse(
                _split_shaped(str(exc), book_id, "path-traversal"), status_code=400
            )
        body = payload or {}
        destination = body.get("destination")
        if destination is None:
            try:
                from .transfer import get_transfer_record as _get_record

                record = _get_record(root, book_id)
            except Exception:
                record = None
            destination = (
                record.get("destination") if isinstance(record, dict) else None
            )
            if not destination:
                return JSONResponse(
                    _error_shape(
                        book_id,
                        "bad-destination",
                        f"{book_id}: bad-destination: no transfer yet (transfer first)",
                    ),
                    status_code=400,
                )
        if not isinstance(destination, str):
            return JSONResponse(
                _error_shape(
                    book_id,
                    "bad-destination",
                    f"{book_id}: bad-destination: destination must be a string",
                ),
                status_code=400,
            )
        try:
            fn = app.state.open_fn
        except AttributeError:
            fn = None
        try:
            from .transfer import open_folder as _open_folder_fn

            opened = _open_folder_fn(
                destination, opener=fn if callable(fn) else None
            )
        except ValueError as exc:
            text = str(exc)
            if "open-unsupported" in text:
                return JSONResponse(
                    _split_shaped(text, str(destination), "open-unsupported"),
                    status_code=400,
                )
            return JSONResponse(
                _split_shaped(text, str(destination), "bad-destination"),
                status_code=400,
            )
        return JSONResponse({"opened": opened, "book_id": book_id})

    return app
