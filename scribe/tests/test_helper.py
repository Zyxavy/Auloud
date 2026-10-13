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

"""HP1: headless helper entry point (ready line, token everywhere, watchdog).

``scribe ui`` behavior is pinned unchanged (default token scope) alongside.
"""

from __future__ import annotations

import json
import socket
from pathlib import Path
from types import SimpleNamespace

import pytest

from ui.helper import (
    HELPER_API_VERSION,
    HelperError,
    build_helper_app,
    format_ready,
    helper_versions,
    parent_gone,
    pick_port,
    run_helper,
    watchdog_once,
)


def _api_client(tmp_path: Path, **kwargs):
    """TestClient for a helper-mode app (skips without the ui extra)."""
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    app = build_helper_app(tmp_path, "tok", **kwargs)
    return TestClient(app, base_url="http://127.0.0.1:8137")


def test_ready_line_format() -> None:
    line = format_ready(8137, {"scribe": "0.1.0", "spec": ["2.0"], "api": 1})
    payload = json.loads(line)
    assert payload == {
        "ready": True,
        "port": 8137,
        "versions": {"scribe": "0.1.0", "spec": ["2.0"], "api": 1},
    }
    assert "\n" not in line


def test_helper_versions_keys() -> None:
    versions = helper_versions()
    assert versions["api"] == HELPER_API_VERSION
    assert isinstance(versions["scribe"], str) and versions["scribe"]
    assert "2.0" in versions["spec"]


def test_version_endpoint_matches_ready_versions(tmp_path: Path) -> None:
    """API1: GET /api/version serves the ready-line versions object."""
    client = _api_client(tmp_path)
    assert client.get("/api/version").status_code == 403
    response = client.get("/api/version", headers={"X-Auloud-Token": "tok"})
    assert response.status_code == 200
    assert response.json() == helper_versions()


def test_token_required_on_get(tmp_path: Path) -> None:
    client = _api_client(tmp_path)
    assert client.get("/api/health").status_code == 403
    assert client.get("/api/health", headers={"X-Auloud-Token": "tok"}).status_code == 200


def test_token_header_name(tmp_path: Path) -> None:
    from ui.security import TOKEN_HEADER

    client = _api_client(tmp_path)
    assert client.get("/api/health", headers={TOKEN_HEADER: "tok"}).status_code == 200
    assert client.get("/api/health", headers={TOKEN_HEADER: "wrong"}).status_code == 403


def test_host_with_port_passes(tmp_path: Path) -> None:
    client = _api_client(tmp_path)
    response = client.get(
        "/api/health",
        headers={"host": "127.0.0.1:9999", "X-Auloud-Token": "tok"},
    )
    assert response.status_code == 200
    evil = client.get(
        "/api/health",
        headers={"host": "127.0.0.1.evil.com", "X-Auloud-Token": "tok"},
    )
    assert evil.status_code == 421


def test_blank_token_refused(tmp_path: Path) -> None:
    with pytest.raises(HelperError):
        build_helper_app(tmp_path, "")
    with pytest.raises(HelperError):
        build_helper_app(tmp_path, "  ")


def test_pick_port_zero_is_ephemeral() -> None:
    port = pick_port(0)
    assert 1 <= port <= 65535


def test_pick_port_skips_busy() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        busy = int(sock.getsockname()[1])
        assert pick_port(busy) != busy


def test_parent_gone_predicate() -> None:
    assert parent_gone(1234, pid_alive_fn=lambda pid: False) is True
    assert parent_gone(1234, pid_alive_fn=lambda pid: True) is False
    with pytest.raises(HelperError):
        parent_gone(0)
    with pytest.raises(HelperError):
        parent_gone(-4)


def test_watchdog_once_stops_server() -> None:
    server = SimpleNamespace(should_exit=False)
    assert watchdog_once(server, 999, pid_alive_fn=lambda pid: False) is True
    assert server.should_exit is True
    server = SimpleNamespace(should_exit=False)
    assert watchdog_once(server, 999, pid_alive_fn=lambda pid: True) is False
    assert server.should_exit is False


def test_second_helper_refused_by_lock(tmp_path: Path) -> None:
    from ui.server import UiLockError, acquire_ui_lock

    with acquire_ui_lock(tmp_path, cmd="helper"):
        with pytest.raises(UiLockError):
            run_helper(
                workspace=tmp_path,
                log_dir=None,
                port=0,
                parent_pid=None,
                token="tok",
                serve_fn=lambda app, host, port: None,
            )


def test_lock_refusal_names_the_holder(tmp_path: Path) -> None:
    from ui.server import UiLockError, acquire_ui_lock

    with acquire_ui_lock(tmp_path, cmd="ui"):
        with pytest.raises(UiLockError) as exc_info:
            run_helper(
                workspace=tmp_path,
                log_dir=None,
                port=0,
                parent_pid=None,
                token="tok",
                serve_fn=lambda app, host, port: None,
            )
    assert "stop that `scribe ui` first" in str(exc_info.value)


def test_run_helper_leaks_no_log_handlers(tmp_path: Path) -> None:
    import logging

    before = len(logging.getLogger().handlers)
    run_helper(
        workspace=tmp_path,
        log_dir=None,
        port=0,
        parent_pid=None,
        token="tok",
        serve_fn=lambda app, host, port: None,
    )
    assert len(logging.getLogger().handlers) == before


def test_run_helper_uses_serve_fn_and_prints_ready(tmp_path: Path, capsys) -> None:
    seen = {}

    def fake_serve(app, host, port):
        seen["host"] = host
        seen["port"] = port

    run_helper(
        workspace=tmp_path,
        log_dir=None,
        port=0,
        parent_pid=None,
        token="tok",
        serve_fn=fake_serve,
    )
    assert seen["host"] == "127.0.0.1"
    payload = json.loads(capsys.readouterr().out.strip())
    assert payload["ready"] is True
    assert payload["port"] == seen["port"]
    assert (tmp_path / "logs" / "helper.log").is_file()


def test_missing_token_fails_loud(tmp_path: Path) -> None:
    with pytest.raises(HelperError):
        run_helper(
            workspace=tmp_path,
            log_dir=None,
            port=0,
            parent_pid=None,
            token=None,
            serve_fn=lambda app, host, port: None,
        )


def test_scribe_ui_default_scope_unchanged(tmp_path: Path) -> None:
    """`scribe ui` keeps token-on-state-changing-only (HP1 changes nothing)."""
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    client = TestClient(create_app(tmp_path), base_url="http://127.0.0.1:8137")
    assert client.get("/api/health").status_code == 200
    from ui.app import STATE_CHANGING

    assert "GET" not in STATE_CHANGING
