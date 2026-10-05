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

"""Slice 6 UI2: server skeleton + packaging + D8 protections.

No model loading; everything here is fast (no slow mark). FastAPI tests
skip cleanly when the optional ``ui`` extra is not installed, so the base
install suite stays green; the pure-helper tests (sanitizer, safe_join,
host/token, ports, lock) always run.
"""

from __future__ import annotations

import socket
from pathlib import Path

import pytest
from typer.testing import CliRunner

import cli
from ui import security
from ui.security import (
    is_allowed_host,
    is_valid_token,
    safe_join,
    sanitize_filename,
)
from ui.server import (
    BIND_HOST,
    UiLockError,
    acquire_ui_lock,
    assert_bind_host,
    build_url,
    find_free_port,
)

LOCAL = {"host": "127.0.0.1:8137"}


def _api_client(tmp_path: Path):
    """TestClient for a fresh skeleton app (skips without the ui extra)."""
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    app = create_app(tmp_path)
    return TestClient(app), app.state.token


# --- sanitize_filename -----------------------------------------------------


def test_sanitize_filename_keeps_plain_basename() -> None:
    assert sanitize_filename("my book.epub") == "my book.epub"
    assert sanitize_filename("cast.yaml") == "cast.yaml"


def test_sanitize_filename_strips_directories() -> None:
    assert sanitize_filename("../../../etc/passwd") == "passwd"
    assert sanitize_filename("/abs/path/book.epub") == "book.epub"
    assert sanitize_filename("a/b/c.pdf") == "c.pdf"


def test_sanitize_filename_strips_windows_specs() -> None:
    assert sanitize_filename("C:\\Windows\\evil.epub") == "evil.epub"
    assert sanitize_filename("C:evil.epub") == "evil.epub"
    assert sanitize_filename("\\\\server\\share\\file.pdf") == "file.pdf"


def test_sanitize_filename_replaces_forbidden_chars() -> None:
    assert sanitize_filename('a<b>c"d.ePub') == "a_b_c_d.ePub"


def test_sanitize_filename_fallback() -> None:
    assert sanitize_filename("") == "upload.bin"
    assert sanitize_filename("..") == "upload.bin"
    assert sanitize_filename(".") == "upload.bin"
    assert sanitize_filename(None) == "upload.bin"


# --- safe_join --------------------------------------------------------------


def test_safe_join_valid_stays_inside(tmp_path: Path) -> None:
    out = safe_join(tmp_path, "books", "novel.epub")
    assert out == (tmp_path.resolve() / "books" / "novel.epub")
    assert safe_join(tmp_path) == tmp_path.resolve()


def test_safe_join_dotdot_escape_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="path-traversal"):
        safe_join(tmp_path, "..", "evil.txt")
    with pytest.raises(ValueError, match="path-traversal"):
        safe_join(tmp_path, "a", "..", "..", "evil.txt")


def test_safe_join_absolute_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="path-traversal"):
        safe_join(tmp_path, "/etc/passwd")


def test_safe_join_windows_drive_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="path-traversal"):
        safe_join(tmp_path, "C:\\evil.txt")
    with pytest.raises(ValueError, match="path-traversal"):
        safe_join(tmp_path, "C:evil.txt")
    with pytest.raises(ValueError, match="path-traversal"):
        safe_join(tmp_path, "\\\\server\\share\\x.txt")


# --- host / token ------------------------------------------------------------


def test_is_allowed_host_local_variants() -> None:
    assert is_allowed_host("127.0.0.1")
    assert is_allowed_host("127.0.0.1:8137")
    assert is_allowed_host("localhost")
    assert is_allowed_host("localhost:9999")
    assert is_allowed_host("LOCALHOST:8137")


def test_is_allowed_host_rejects_rebinding_shapes() -> None:
    assert not is_allowed_host(None)
    assert not is_allowed_host("")
    assert not is_allowed_host("evil.com")
    assert not is_allowed_host("evil.com:8137")
    assert not is_allowed_host("127.0.0.1.evil.com")
    assert not is_allowed_host("localhost.evil.com")
    assert not is_allowed_host("[::1]")


def test_token_generate_unique_and_check() -> None:
    from ui.security import generate_token

    first, second = generate_token(), generate_token()
    assert first and second and first != second
    assert is_valid_token(first, first)
    assert not is_valid_token("wrong", first)
    assert not is_valid_token(None, first)
    assert not is_valid_token("", first)


# --- bind guard / ports / lock ------------------------------------------------


def test_assert_bind_host_local_ok() -> None:
    assert_bind_host("127.0.0.1")


def test_assert_bind_host_non_local_rejected() -> None:
    for host in ("0.0.0.0", "localhost", "192.168.1.2", ""):
        with pytest.raises(ValueError, match="non-local-bind"):
            assert_bind_host(host)


def test_find_free_port_returns_preferred_when_free() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind((BIND_HOST, 0))
        free = probe.getsockname()[1]
    assert find_free_port(free) == free


def test_find_free_port_picks_next_when_busy() -> None:
    holder = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    holder.bind((BIND_HOST, 0))
    busy = holder.getsockname()[1]
    try:
        picked = find_free_port(busy)
    finally:
        holder.close()
    assert picked != busy
    assert picked > busy


def test_find_free_port_bad_range_rejected() -> None:
    with pytest.raises(ValueError, match="bad-port"):
        find_free_port(0)
    with pytest.raises(ValueError, match="bad-port"):
        find_free_port(99999)


def test_single_instance_second_holder_rejected(tmp_path: Path) -> None:
    first = acquire_ui_lock(tmp_path)
    try:
        with pytest.raises(UiLockError, match="ui-lock-held"):
            acquire_ui_lock(tmp_path)
    finally:
        first.release()


def test_single_instance_release_allows_reacquire(tmp_path: Path) -> None:
    acquire_ui_lock(tmp_path).release()
    second = acquire_ui_lock(tmp_path)
    second.release()


def test_build_url() -> None:
    assert build_url(8137) == "http://127.0.0.1:8137/"


# --- API: host / token / CORS -------------------------------------------------


def test_health_ok_without_token(tmp_path: Path) -> None:
    client, _token = _api_client(tmp_path)
    response = client.get("/api/health", headers=LOCAL)
    assert response.status_code == 200
    assert response.json() == {"ok": True}


def test_index_embeds_token(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    response = client.get("/", headers=LOCAL)
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    assert token in response.text


def test_wrong_host_rejected_without_leak(tmp_path: Path) -> None:
    client, _token = _api_client(tmp_path)
    for hostile in ("evil.com", "127.0.0.1.evil.com:8137", "attacker.test"):
        response = client.get("/api/health", headers={"host": hostile})
        assert response.status_code == 421
        assert hostile not in response.text


def test_post_echo_requires_token(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    missing = client.post("/api/echo", json={"m": 1}, headers=LOCAL)
    assert missing.status_code == 403
    wrong = client.post(
        "/api/echo", json={"m": 1}, headers={**LOCAL, "X-Auloud-Token": "nope"}
    )
    assert wrong.status_code == 403
    assert "nope" not in wrong.text
    ok = client.post(
        "/api/echo", json={"m": 1}, headers={**LOCAL, "X-Auloud-Token": token}
    )
    assert ok.status_code == 200
    assert ok.json() == {"echo": {"m": 1}}


def test_no_cors_headers_anywhere(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    get_resp = client.get("/api/health", headers=LOCAL)
    post_resp = client.post(
        "/api/echo", json={}, headers={**LOCAL, "X-Auloud-Token": token}
    )
    options_resp = client.options("/api/echo", headers=LOCAL)
    for resp in (get_resp, post_resp, options_resp):
        assert "access-control-allow-origin" not in resp.headers


def test_skeleton_routes_are_minimal(tmp_path: Path) -> None:
    pytest.importorskip("fastapi")
    from ui.app import create_app

    paths = sorted({route.path for route in create_app(tmp_path).routes})
    # UI4 extends the UI3 skeleton with the job runner + settings routes
    # (no auto docs/openapi/redoc anywhere; still no CORS middleware).
    assert paths == [
        "/",
        "/api/books",
        "/api/books/upload",
        "/api/books/{book_id}",
        "/api/books/{book_id}/build",
        "/api/echo",
        "/api/health",
        "/api/jobs",
        "/api/jobs/{job_id}",
        "/api/jobs/{job_id}/cancel",
        "/api/jobs/{job_id}/events",
        "/api/jobs/{job_id}/pause",
        "/api/jobs/{job_id}/resume",
        "/api/settings",
    ]


# --- doctor + CLI wiring -------------------------------------------------------


def test_doctor_ui_row_passes_with_extra() -> None:
    pytest.importorskip("fastapi")
    result = cli.check_ui()
    assert result.status == cli.PASS
    assert "fastapi" in result.detail and "uvicorn" in result.detail


def test_doctor_ui_row_missing_extra_is_hint(monkeypatch: pytest.MonkeyPatch) -> None:
    real = cli.importlib.util.find_spec

    def _missing(name: str, *args: object, **kwargs: object):
        if name in ("fastapi", "uvicorn"):
            return None
        return real(name, *args, **kwargs)

    monkeypatch.setattr(cli.importlib.util, "find_spec", _missing)
    result = cli.check_ui()
    assert result.status == cli.FAIL
    assert "uv sync --extra ui" in result.hint


def test_doctor_table_includes_ui(tmp_path: Path) -> None:
    result = CliRunner().invoke(cli.app, ["doctor", "--models-dir", str(tmp_path)])
    assert result.exit_code in (0, 1)
    assert "ui" in result.output


def test_ui_command_missing_extra_prints_hint(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    real = cli.importlib.util.find_spec

    def _missing(name: str, *args: object, **kwargs: object):
        if name in ("fastapi", "uvicorn"):
            return None
        return real(name, *args, **kwargs)

    monkeypatch.setattr(cli.importlib.util, "find_spec", _missing)
    result = CliRunner().invoke(
        cli.app, ["ui", "--workspace", str(tmp_path), "--no-browser"]
    )
    assert result.exit_code == 1
    assert "uv sync --extra ui" in result.output


def test_base_help_works_without_extra(monkeypatch: pytest.MonkeyPatch) -> None:
    real = cli.importlib.util.find_spec

    def _missing(name: str, *args: object, **kwargs: object):
        if name in ("fastapi", "uvicorn"):
            return None
        return real(name, *args, **kwargs)

    monkeypatch.setattr(cli.importlib.util, "find_spec", _missing)
    assert CliRunner().invoke(cli.app, ["--help"]).exit_code == 0
    assert CliRunner().invoke(cli.app, ["ui", "--help"]).exit_code == 0


# --- packaging ------------------------------------------------------------------


def test_ui_package_and_static_ship() -> None:
    pure = Path(security.__file__).resolve().parent
    for rel in ("__init__.py", "app.py", "server.py", "security.py"):
        assert (pure / rel).is_file(), rel
    index = pure / "static" / "index.html"
    assert index.is_file()
    assert "{{AULOUD_TOKEN}}" in index.read_text(encoding="utf-8")


def test_pyproject_ui_extra_and_force_include() -> None:
    text = (Path(cli.__file__).resolve().parent / "pyproject.toml").read_text(
        encoding="utf-8"
    )
    assert '"ui" = "scribe/ui"' in text
    for dep in ("fastapi>=", "uvicorn>=", "python-multipart>="):
        assert dep in text
