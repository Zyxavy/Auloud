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

"""UI2 app factory: placeholder routes plus the D8 protections (plan D2/D8).

Skeleton only (real views land in UI3+): ``GET /api/health``,
``GET /`` (placeholder page with the per-run token embedded) and
``POST /api/echo`` (a tested example proving token enforcement on
state-changing requests). Deliberately no CORS middleware anywhere, and the
Host/token middleware answers 421/403 with generic bodies that never echo
the offending Host or the expected token.
"""

from __future__ import annotations

import html
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse

from .security import TOKEN_HEADER, generate_token, is_allowed_host, is_valid_token

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


def create_app(workspace: Path | str, token: str | None = None) -> FastAPI:
    """Build the UI2 skeleton app bound to ``workspace`` (confined root).

    :param workspace: file access below this root only (see ``safe_join``).
    :param token: per-run token; a fresh ``secrets`` token when omitted.
    """
    root = Path(workspace).resolve()
    run_token = token or generate_token()

    app = FastAPI(
        title="Auloud Scribe UI (skeleton)",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.workspace = root
    app.state.token = run_token

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

    return app
