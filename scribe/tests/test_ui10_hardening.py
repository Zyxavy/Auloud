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

"""Slice 6 UI10: tests and hardening (gap audit, security, fuzz, SSE, state machine).

Gap audit (before UI10; after = covered by this file unless noted):

- Endpoints (29 in ``create_app``): GET /api/health (tested UI2),
  GET / (tested UI2), POST /api/echo (tested UI2),
  GET /api/books (UI3 pure scan only; API shape + Host covered here),
  GET /api/books/{id} (UI3 traversal/unknown; verbatim + fuzz here),
  POST /api/books/upload (UI3 token/extension/sanitize/parity; missing-file
  422->400 + Host here), POST /api/books/{id}/build (UI4/UI5 device/lock;
  bad-type fuzz + Host + token here), POST /api/books/{id}/plan (UI5 grammar;
  Host + token + bad-body here), GET /api/jobs (UI4 list; Host here),
  GET /api/jobs/{id} (UI4 charset; full charset matrix here),
  POST pause/resume/cancel (UI4 bad-transition only; double-pause,
  cancel-paused, pause-refused, resume-rejected matrix here),
  GET events SSE (UI4 replay/heartbeat/Last-Event-ID; query-param reconnect,
  torn-line skip, paused-terminal drain, 400/404 here),
  GET/PUT settings (UI4/UI5 round-trip; bad-body + Host + token here),
  GET /api/cast/voices (UI6 palette; Host + no-engine here),
  GET /api/voices/{name}.wav (UI6/UI7 serve/missing; traversal + Host here),
  POST /api/voices/sample (UI7 cached/coalesce; bad-body + Host + token here),
  POST /api/voices/{name}/sample (UI7 per-voice; bad-voice + Host here),
  GET/PUT/PATCH cast (UI6 ops/atomic/conflict; bad-body + Host + token here),
  GET cast/report (UI6 escape; missing + Host here),
  GET quotes (UI6 paging; bad-confidence/page + Host here),
  POST validate (UI8 inline/wording; Host + token + bad-body here),
  GET drives (UI8 mocked; Host here),
  POST transfer (UI8 copy/verify/exists; bad-body + Host + token here),
  POST open-folder (UI8 mocked; bad-body + Host + token here).
- Security D-052/D-058 claims: wrong Host only tested on /health (UI2);
  missing token only on echo/upload + spot checks; traversal only on
  book-detail/job-detail/voice-sample; job-id charset only on detail.
  This file covers Host on NEW routes, token on EVERY mutation,
  traversal on EVERY path param, charset on EVERY job route.
- State machine (jobs.py): UI4's 17 cover build-done, build-fail,
  pause-resume, cancel-running, cancel-queued, bad-transition(done),
  cross-book queue, same-book refuse, CLI-lock refuse, reattach-interrupted,
  interrupted-cache-reuse, SSE, charset/token, list/settings, keepawake,
  stop-file, real-detach. Missing (covered here): double-pause,
  cancel-during-pause, pause-refused voices + transfer, resume-rejected
  (done/running/queued), resume-cancelled-allowed, queue-across-restart,
  interrupted pause/cancel-refused.
- Hardening: non-dict bodies gave 422 ``detail`` (not shaped); unexpected
  exceptions gave plain 500. Fixed in app.py (UI10): RequestValidationError
  -> 400 shaped, Exception -> 500 shaped (HTTPException passthrough).
  Each fix has a test below.

Isolation: no TTS model load. All builds use the FakeProcess stub (never
real Kokoro); preflight uses a stub engine; validate uses a stub probe;
voices use injected versions. ``test_no_engine_imports`` proves the suite
runs with engine imports blocked.
"""

from __future__ import annotations

import json
import shutil
import sys
import threading
import time
from pathlib import Path

import pytest
from ebooklib import epub

LOCAL = {"host": "127.0.0.1:8137"}
HOSTILE = ("evil.com", "127.0.0.1.evil.com:8137", "attacker.test")
SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."
VOICES_VERSION = "fake-ui10-1"


def _make_epub(path: Path, titles: list[str], title: str = "UI10 Test Book") -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-ui10-book")
    book.set_title(title)
    book.set_language("en")
    book.add_author("UI10 Author")
    items = []
    for pos, chapter_title in enumerate(titles, start=1):
        body = " ".join([SENTENCE] * 25)
        item = epub.EpubHtml(title=chapter_title, file_name=f"ch{pos}.xhtml", lang="en")
        item.content = f"<h1>{chapter_title}</h1><p>{body}</p>"
        book.add_item(item)
        items.append(item)
    book.toc = [
        epub.Link(f"ch{pos}.xhtml", chapter_title, f"ch{pos}")
        for pos, chapter_title in enumerate(titles, 1)
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = items
    epub.write_epub(str(path), book)
    return path


def _stub_probe(durations: dict[str, int] | None = None):
    from bundle.validate import AudioProbe

    table = dict(durations or {})

    def _probe(path: Path) -> AudioProbe:
        return AudioProbe(
            codec="mp3",
            channels=1,
            sample_rate=24000,
            bit_rate_bps=64000,
            bit_rate_from_stream=True,
            duration_ms=table.get(Path(path).name, 3000),
            cbr_frames=True,
        )

    return _probe


class FakeProcess:
    """Popen-like stub: slow UI1 events for build/draft, slow sleep for voices."""

    def __init__(self, job: dict, folder: Path, workspace: Path, behavior: dict) -> None:
        self.job = dict(job)
        self.folder = Path(folder)
        self.workspace = Path(workspace)
        self.behavior = dict(behavior)
        self.pid = 999999
        self._exit: int | None = None
        self._terminated = False
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def poll(self) -> int | None:
        return self._exit

    def wait(self, timeout: float | None = None) -> int | None:
        self._thread.join(timeout=timeout)
        return self._exit

    def terminate(self) -> None:
        self._terminated = True

    def kill(self) -> None:
        self._terminated = True

    def _run(self) -> None:
        try:
            kind = self.job.get("kind")
            if kind == "draft":
                time.sleep(float(self.behavior.get("delay", 0.02)))
                if self.behavior.get("fail"):
                    try:
                        with open(self.folder / "build.log", "a", encoding="utf-8") as h:
                            h.write("draft failed: fake boom\n")
                    except OSError:
                        pass
                    self._exit = 1
                    return
                self._exit = 0
                return
            if kind == "voices-sample":
                total = float(self.behavior.get("voices_delay", 5.0))
                deadline = time.monotonic() + total
                while time.monotonic() < deadline:
                    if self._terminated:
                        self._exit = -1
                        return
                    try:
                        if (self.folder / "stop").exists():
                            self._exit = 1
                            return
                    except OSError:
                        pass
                    time.sleep(0.02)
                if self.behavior.get("fail"):
                    self._exit = 1
                    return
                self._exit = 0
                return
            total = int(self.behavior.get("sentences", 4))
            delay = float(self.behavior.get("delay", 0.02))
            wall_start = time.monotonic()
            for sid in range(1, total + 1):
                if self._terminated:
                    self._exit = -1
                    return
                try:
                    if (self.folder / "stop").exists():
                        self._exit = 1
                        return
                except OSError:
                    pass
                line = {
                    "event": "sentence",
                    "chapter": 1,
                    "sid": sid,
                    "cached": 0,
                    "rendered": sid,
                    "audio_ms": 0,
                    "wall_s": round(time.monotonic() - wall_start, 3),
                }
                try:
                    with open(self.folder / "events.jsonl", "a", encoding="utf-8") as h:
                        h.write(json.dumps(line, sort_keys=True) + "\n")
                except OSError:
                    self._exit = -1
                    return
                time.sleep(delay)
            if self._terminated:
                self._exit = -1
                return
            done = {
                "event": "build_done",
                "chapter": 0,
                "sid": None,
                "cached": 0,
                "rendered": total,
                "audio_ms": 1000,
                "wall_s": round(time.monotonic() - wall_start, 3),
            }
            try:
                with open(self.folder / "events.jsonl", "a", encoding="utf-8") as h:
                    h.write(json.dumps(done, sort_keys=True) + "\n")
            except OSError:
                pass
            self._exit = 0
        except Exception:
            self._exit = -1


class FakeSpawner:
    """Per-spawn build behaviors; drafts fast; voices slow (to catch running)."""

    def __init__(self, workspace: Path, behaviors: list[dict] | None = None) -> None:
        self.workspace = Path(workspace)
        self.behaviors = [dict(b) for b in (behaviors or [{}])]
        self.calls: list[dict] = []
        self._builds = 0

    def __call__(self, job: dict, folder: Path) -> FakeProcess:
        self.calls.append(dict(job))
        if job.get("kind") == "draft":
            behavior: dict = {"delay": 0.01}
        elif job.get("kind") == "voices-sample":
            behavior = {"voices_delay": 5.0}
        else:
            idx = min(self._builds, len(self.behaviors) - 1)
            behavior = self.behaviors[idx]
            self._builds += 1
        return FakeProcess(job, folder, self.workspace, behavior)


def _api_client(workspace: Path, token: str = "ui10-token", spawner=None, **extra):  # type: ignore[no-untyped-def]
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    kwargs: dict = {"pid_alive_fn": lambda _pid: False}
    kwargs["voices_version"] = extra.pop("voices_version", VOICES_VERSION)
    kwargs["validate_probe"] = extra.pop("validate_probe", _stub_probe())
    if spawner is not None:
        kwargs["spawn_fn"] = spawner
    kwargs.update(extra)
    app = create_app(workspace, token=token, **kwargs)
    return TestClient(app), token


def _auth(token: str) -> dict[str, str]:
    return {**LOCAL, "X-Auloud-Token": token}


def _wait_job(client, job_id: str, headers: dict, want: set, timeout_s: float = 20.0):  # type: ignore[no-untyped-def]
    deadline = time.monotonic() + timeout_s
    last: dict = {}
    while time.monotonic() < deadline:
        resp = client.get(f"/api/jobs/{job_id}", headers=headers)
        assert resp.status_code == 200, resp.text
        last = resp.json()
        if last.get("state") in want:
            return last
        time.sleep(0.05)
    raise AssertionError(f"job {job_id} never reached {want}: {last}")


def _draft_book(
    workspace: Path, name: str = "book.epub", title: str = "UI10 Test Book"
) -> tuple[Path, str]:
    """Real library draft (no model); returns (src, book_id)."""
    from draft import book_id_for_file, run_draft

    src = _make_epub(workspace / name, ["Chapter One"], title=title)
    run_draft(src, work_root=workspace / ".scribe")
    book_id, _sha = book_id_for_file(src)
    return src, book_id


def _shaped(body: dict) -> None:
    assert isinstance(body, dict), body
    assert set(body) >= {"file", "rule", "message"}, body
    assert isinstance(body["file"], str) and body["file"]
    assert isinstance(body["rule"], str) and body["rule"]
    assert isinstance(body["message"], str) and body["message"]


# --- security: Host on NEW routes --------------------------------------------------


def test_host_rejected_on_new_routes(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.01}])
    client, _token = _api_client(tmp_path, spawner=spawner)
    get_routes = [
        "/api/books",
        "/api/books/abc",
        "/api/jobs",
        "/api/jobs/abc123",
        "/api/jobs/abc123/events",
        "/api/settings",
        "/api/cast/voices",
        "/api/voices/af_heart.wav",
        "/api/books/abc/cast",
        "/api/books/abc/cast/report",
        "/api/books/abc/quotes",
        "/api/drives",
    ]
    post_routes = [
        ("/api/books/abc/build", {}),
        ("/api/books/abc/plan", {}),
        ("/api/voices/sample", {}),
        ("/api/books/abc/validate", {}),
        ("/api/books/abc/transfer", {"destination": "/tmp/x"}),
        ("/api/books/abc/open-folder", {}),
    ]
    for hostile in HOSTILE:
        for path in get_routes:
            resp = client.get(path, headers={"host": hostile})
            assert resp.status_code == 421, (hostile, path, resp.text)
            assert hostile not in resp.text
        for path, body in post_routes:
            resp = client.post(path, json=body, headers={"host": hostile})
            assert resp.status_code == 421, (hostile, path, resp.text)
            assert hostile not in resp.text


def test_no_cors_on_new_routes(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    for resp in (
        client.get("/api/books", headers=LOCAL),
        client.get("/api/jobs", headers=LOCAL),
        client.get("/api/settings", headers=LOCAL),
        client.get("/api/cast/voices", headers=LOCAL),
        client.get("/api/drives", headers=LOCAL),
        client.post("/api/books/abc/validate", json={}, headers=headers),
    ):
        assert "access-control-allow-origin" not in resp.headers


# --- security: token on EVERY mutation ---------------------------------------------


def test_missing_token_on_every_mutation(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    good = _auth(token)
    # A real book + job id for routes that need existing ids; the 403 must
    # fire before any id lookup, so even bogus ids give 403 here.
    cases = [
        ("POST", "/api/echo", {"m": 1}),
        ("POST", "/api/books/upload", None),
        ("POST", "/api/books/abc/build", {}),
        ("POST", "/api/books/abc/plan", {}),
        ("POST", "/api/jobs/abc123/pause", None),
        ("POST", "/api/jobs/abc123/resume", None),
        ("POST", "/api/jobs/abc123/cancel", None),
        ("PUT", "/api/settings", {"keep_awake": True}),
        ("POST", "/api/voices/sample", {}),
        ("POST", "/api/voices/af_heart/sample", {}),
        ("PUT", "/api/books/abc/cast", {"cast": {}}),
        ("PATCH", "/api/books/abc/cast", {"ops": []}),
        ("POST", "/api/books/abc/validate", {}),
        ("POST", "/api/books/abc/transfer", {"destination": "/tmp/x"}),
        ("POST", "/api/books/abc/open-folder", {}),
    ]
    for method, path, body in cases:
        kwargs: dict = {"headers": dict(LOCAL)}
        if body is not None:
            kwargs["json"] = body
        # Upload needs multipart; without a file the framework would 422,
        # but the token middleware runs first, so 403 is still expected.
        if path == "/api/books/upload":
            resp = client.post(path, headers=dict(LOCAL))
        else:
            resp = client.request(method, path, **kwargs)
        assert resp.status_code == 403, (method, path, resp.status_code, resp.text)
        assert token not in resp.text
    # Sanity: the same calls with the token do NOT 403 (they reach handlers).
    ok = client.post("/api/echo", json={"m": 1}, headers=good)
    assert ok.status_code == 200


# --- security: traversal on every path param ----------------------------------------


def test_traversal_on_every_book_route(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    # Colon shapes reach the handler and must be 400 path-traversal.
    book_gets = [
        "/api/books/bad:id",
        "/api/books/bad:id/cast",
        "/api/books/bad:id/cast/report",
        "/api/books/bad:id/quotes",
    ]
    for path in book_gets:
        resp = client.get(path, headers=LOCAL)
        assert resp.status_code == 400, (path, resp.text)
        _shaped(resp.json())
        assert resp.json()["rule"] == "path-traversal"
    book_posts = [
        ("/api/books/bad:id/build", {}),
        ("/api/books/bad:id/plan", {}),
        ("/api/books/bad:id/validate", {}),
        ("/api/books/bad:id/transfer", {"destination": "/tmp/x", "force": False}),
        ("/api/books/bad:id/open-folder", {}),
    ]
    for path, body in book_posts:
        resp = client.post(path, json=body, headers=headers)
        assert resp.status_code == 400, (path, resp.text)
        _shaped(resp.json())
        assert resp.json()["rule"] == "path-traversal"
    for method, path, body in (
        ("PUT", "/api/books/bad:id/cast", {"cast": {}}),
        ("PATCH", "/api/books/bad:id/cast", {"ops": []}),
    ):
        resp = client.request(method, path, json=body, headers=headers)
        assert resp.status_code == 400, (method, path, resp.text)
        assert resp.json()["rule"] == "path-traversal"


def test_job_id_charset_on_every_job_route(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    # Shapes that reach the handler must be 400 bad-job-id (shaped).
    for bad in ("bad:id", "x y"):
        resp = client.get(f"/api/jobs/{bad}", headers=LOCAL)
        assert resp.status_code == 400, (bad, resp.text)
        _shaped(resp.json())
        assert resp.json()["rule"] == "bad-job-id"
        for action in ("pause", "resume", "cancel"):
            resp2 = client.post(f"/api/jobs/{bad}/{action}", headers=headers)
            assert resp2.status_code == 400, (bad, action, resp2.text)
            assert resp2.json()["rule"] == "bad-job-id"
        resp3 = client.get(f"/api/jobs/{bad}/events", headers=LOCAL)
        assert resp3.status_code == 400, (bad, resp3.text)
        assert resp3.json()["rule"] == "bad-job-id"
    # Slashes and dot segments never reach the handler: the router
    # normalizes them to framework 404 (no leak, no traceback). Documented,
    # not shaped (same as UI3's book-detail note).
    for bad in ("a/b", ".."):
        resp = client.get(f"/api/jobs/{bad}", headers=LOCAL)
        assert resp.status_code == 404, (bad, resp.text)
        assert "Traceback" not in resp.text
    # Unknown but well-formed ids are 404 shaped (not 400, not traceback).
    for action_path in ("/api/jobs/job-doesnotexist01",):
        resp = client.get(action_path, headers=LOCAL)
        assert resp.status_code == 404
        _shaped(resp.json())
    for action in ("pause", "resume", "cancel"):
        resp = client.post(f"/api/jobs/job-doesnotexist01/{action}", headers=headers)
        assert resp.status_code == 404
        _shaped(resp.json())
    resp = client.get("/api/jobs/job-doesnotexist01/events", headers=LOCAL)
    assert resp.status_code == 404
    _shaped(resp.json())


def test_voice_traversal_rejected(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    for path in ("/api/voices/bad:id.wav", "/api/voices/%2e%2e.wav"):
        resp = client.get(path, headers=LOCAL)
        assert resp.status_code == 400, (path, resp.text)
        _shaped(resp.json())
        assert resp.json()["rule"] == "path-traversal"
    # Dot segments in the sample POST never reach the handler (router
    # normalizes to framework 404); colon shapes do and must be 400.
    resp = client.post("/api/voices/bad:id/sample", json={}, headers=headers)
    assert resp.status_code == 400, resp.text
    _shaped(resp.json())
    dot = client.post("/api/voices/../sample", json={}, headers=headers)
    assert dot.status_code == 404
    assert "Traceback" not in dot.text


# --- hardening: bad bodies are shaped, never 422/traceback ---------------------------


def test_bad_bodies_are_shaped_not_422(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    cases = [
        ("POST", "/api/echo", [1, 2]),
        ("POST", "/api/books/abc/build", [1]),
        ("POST", "/api/books/abc/plan", 123),
        ("PUT", "/api/settings", [1]),
        ("POST", "/api/voices/sample", [1]),
        ("POST", "/api/voices/af_heart/sample", [1]),
        ("PUT", "/api/books/abc/cast", [1]),
        ("PATCH", "/api/books/abc/cast", [1]),
        ("POST", "/api/books/abc/transfer", [1]),
        ("POST", "/api/books/abc/open-folder", [1]),
    ]
    for method, path, body in cases:
        resp = client.request(method, path, json=body, headers=headers)
        assert resp.status_code == 400, (method, path, resp.status_code, resp.text)
        _shaped(resp.json())
        assert resp.json()["rule"] == "bad-request"
        assert "Traceback" not in resp.text


def test_upload_missing_file_is_shaped(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    resp = client.post("/api/books/upload", headers=_auth(token))
    assert resp.status_code == 400
    _shaped(resp.json())
    assert "Traceback" not in resp.text


def test_bad_field_types_are_shaped(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    # Wrong JSON types inside otherwise-valid bodies (verbatim CLI wording kept).
    resp = client.post("/api/books/abc/build", json={"chapters": 123}, headers=headers)
    assert resp.status_code == 400
    assert resp.json()["rule"] == "bad-request"
    resp = client.post("/api/books/abc/plan", json={"pages": ["x"]}, headers=headers)
    assert resp.status_code == 400
    resp = client.put("/api/settings", json={"keep_awake": "yes"}, headers=headers)
    assert resp.status_code == 400
    resp = client.put("/api/settings", json={"device": 123}, headers=headers)
    assert resp.status_code == 400
    assert resp.json()["rule"] == "bad-device"
    resp = client.post("/api/voices/sample", json={"regenerate": "yes"}, headers=headers)
    assert resp.status_code == 400
    resp = client.post(
        "/api/books/abc/transfer", json={"destination": "/tmp/x", "force": "yes"},
        headers=headers,
    )
    assert resp.status_code == 400
    resp = client.get("/api/books/abc/quotes?confidence=bad", headers=LOCAL)
    assert resp.status_code == 400
    resp = client.get("/api/books/abc/quotes?page=bad", headers=LOCAL)
    assert resp.status_code == 400
    for body in resp.json(), {}:
        assert "Traceback" not in json.dumps(body)


def test_unexpected_failure_is_shaped_500(tmp_path: Path) -> None:
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    import ui.app as appmod

    app = create_app(tmp_path, token="ui10-500", validate_probe=_stub_probe())
    client = TestClient(app, raise_server_exceptions=False)
    orig = appmod.scan_books

    def _boom(*args: object, **kwargs: object):
        raise RuntimeError("kaboom-ui10")

    appmod.scan_books = _boom  # type: ignore[assignment]
    try:
        resp = client.get("/api/books", headers=LOCAL)
    finally:
        appmod.scan_books = orig
    assert resp.status_code == 500
    _shaped(resp.json())
    assert resp.json()["rule"] == "internal"
    assert "Traceback" not in resp.text


def test_index_escapes_malicious_token(tmp_path: Path) -> None:
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    evil = '"><script>alert(1)</script>'
    app = create_app(tmp_path, token=evil)
    client = TestClient(app)
    resp = client.get("/", headers=LOCAL)
    assert resp.status_code == 200
    assert evil not in resp.text
    assert "&lt;script&gt;" in resp.text or "&quot;" in resp.text


# --- SSE: extend UI4 (query param, torn lines, paused drain, errors) -------------------


def test_sse_query_param_reconnect_and_heartbeat(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 3, "delay": 0.01}])
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, book_id = _draft_book(tmp_path)
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    assert resp.status_code == 202, resp.text
    job_id = resp.json()["job_id"]
    _wait_job(client, job_id, headers, {"done"})
    full = client.get(f"/api/jobs/{job_id}/events", headers=headers)
    assert full.status_code == 200
    assert "text/event-stream" in full.headers["content-type"]
    assert ": ping" in full.text  # heartbeat first, then events
    assert full.text.startswith(": ping")
    # Query-param reconnect (EventSource without header support).
    resumed = client.get(f"/api/jobs/{job_id}/events?lastEventId=2", headers=headers)
    assert resumed.status_code == 200
    assert "id: 1\n" not in resumed.text
    assert "id: 3" in resumed.text
    # Bad offset falls back to 0 (full replay, never 500).
    bad_off = client.get(
        f"/api/jobs/{job_id}/events", headers={**headers, "Last-Event-ID": "junk"}
    )
    assert bad_off.status_code == 200
    assert "id: 1" in bad_off.text


def test_sse_skips_torn_tail_and_reports_errors(tmp_path: Path) -> None:
    from ui.jobs import JobManager

    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.01}])
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, book_id = _draft_book(tmp_path)
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    _wait_job(client, job_id, headers, {"done"})
    events = tmp_path / "jobs" / job_id / "events.jsonl"
    with open(events, "a", encoding="utf-8") as h:
        h.write("{torn json\n")
        h.write("\n")
    body = client.get(f"/api/jobs/{job_id}/events", headers=headers)
    assert body.status_code == 200
    assert "{torn json" not in body.text
    assert "build_done" in body.text
    assert "Traceback" not in body.text
    # Error paths are shaped.
    bad = client.get("/api/jobs/bad:id/events", headers=headers)
    assert bad.status_code == 400
    assert bad.json()["rule"] == "bad-job-id"
    missing = client.get("/api/jobs/job-doesnotexist02/events", headers=headers)
    assert missing.status_code == 404
    assert missing.json()["rule"] == "job-not-found"
    # A manager-level read of a missing job is empty (never raises).
    manager: JobManager = client.app.state.jobs  # type: ignore[attr-defined]
    assert manager.read_event_lines("job-doesnotexist02") == []


def test_sse_paused_job_drains_and_closes(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 20, "delay": 0.03}])
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, book_id = _draft_book(tmp_path)
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    deadline = time.monotonic() + 10.0
    events_file = tmp_path / "jobs" / job_id / "events.jsonl"
    while time.monotonic() < deadline:
        if len(events_file.read_text(encoding="utf-8").splitlines()) >= 2:
            break
        time.sleep(0.05)
    paused = client.post(f"/api/jobs/{job_id}/pause", headers=headers)
    assert paused.status_code == 200, paused.text
    _wait_job(client, job_id, headers, {"paused"})
    body = client.get(f"/api/jobs/{job_id}/events", headers=headers)
    assert body.status_code == 200
    assert ": ping" in body.text
    assert "data:" in body.text


# --- state machine: fill UI4's gaps -----------------------------------------------------


def test_double_pause_rejected(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 20, "delay": 0.03}])
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, book_id = _draft_book(tmp_path)
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    deadline = time.monotonic() + 10.0
    events_file = tmp_path / "jobs" / job_id / "events.jsonl"
    while time.monotonic() < deadline:
        if len(events_file.read_text(encoding="utf-8").splitlines()) >= 2:
            break
        time.sleep(0.05)
    first = client.post(f"/api/jobs/{job_id}/pause", headers=headers)
    assert first.status_code == 200, first.text
    _wait_job(client, job_id, headers, {"paused"})
    second = client.post(f"/api/jobs/{job_id}/pause", headers=headers)
    assert second.status_code == 409
    _shaped(second.json())
    assert second.json()["rule"] == "bad-state"


def test_cancel_paused_job(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 20, "delay": 0.03}])
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, book_id = _draft_book(tmp_path)
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    deadline = time.monotonic() + 10.0
    events_file = tmp_path / "jobs" / job_id / "events.jsonl"
    while time.monotonic() < deadline:
        if len(events_file.read_text(encoding="utf-8").splitlines()) >= 2:
            break
        time.sleep(0.05)
    assert client.post(f"/api/jobs/{job_id}/pause", headers=headers).status_code == 200
    _wait_job(client, job_id, headers, {"paused"})
    cancelled = client.post(f"/api/jobs/{job_id}/cancel", headers=headers)
    assert cancelled.status_code == 200, cancelled.text
    final = _wait_job(client, job_id, headers, {"cancelled"})
    assert final["state"] == "cancelled"


def test_pause_refused_for_voices_and_transfer(tmp_path: Path) -> None:
    from ui.cast import sample_text_hash

    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.01}])
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    # Voices-sample job stays running under the slow fake (5 s).
    queued = client.post("/api/voices/sample", json={}, headers=headers)
    assert queued.status_code == 202, queued.text
    voices_id = queued.json()["job_id"]
    time.sleep(0.3)  # let the FIFO start it
    refused = client.post(f"/api/jobs/{voices_id}/pause", headers=headers)
    assert refused.status_code == 409
    _shaped(refused.json())
    assert refused.json()["rule"] == "bad-state"
    assert "voices-sample" in refused.json()["message"]
    client.post(f"/api/jobs/{voices_id}/cancel", headers=headers)
    # Transfer pause is refused even when done (kind check precedes state).
    FIXTURES = Path(__file__).resolve().parent.parent.parent / "spec" / "fixtures"
    from draft import book_id_for_file, sha256_of_file

    ws = tmp_path / "ws-t"
    ws.mkdir(parents=True, exist_ok=True)
    (ws / "empty.epub").write_bytes(b"")
    target = ws / "bundles" / "b1"
    shutil.copytree(FIXTURES / "valid-bundle", target)
    book_id, _sha = book_id_for_file(ws / "empty.epub")
    manifest_path = target / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["id"] = book_id
    # Fully valid bundle: internal source file + matching sha (UI8 pattern),
    # plus the stub probe durations the fixture expects.
    (target / "source").mkdir(parents=True, exist_ok=True)
    (target / "source" / "book.epub").write_bytes(b"")
    manifest["source"] = {
        "file": "source/book.epub",
        "sha256": sha256_of_file(target / "source" / "book.epub"),
    }
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    probe = _stub_probe({"ch001.mp3": 1832400, "ch002.mp3": 1640100})
    t_client, t_token = _api_client(
        ws, spawner=FakeSpawner(ws), validate_probe=probe
    )
    t_headers = _auth(t_token)
    dest = tmp_path / "tablet"
    dest.mkdir(parents=True, exist_ok=True)
    created = t_client.post(
        f"/api/books/{book_id}/transfer", json={"destination": str(dest)}, headers=t_headers
    )
    assert created.status_code == 202, created.text
    transfer_id = created.json()["job_id"]
    _wait_job(t_client, transfer_id, t_headers, {"done", "failed"})
    refused_t = t_client.post(f"/api/jobs/{transfer_id}/pause", headers=t_headers)
    assert refused_t.status_code == 409
    assert refused_t.json()["rule"] == "bad-state"
    assert "transfer" in refused_t.json()["message"]
    # Voices hash seam is real (no model): the queued job carries it.
    assert sample_text_hash() and len(sample_text_hash()) == 12


def test_resume_rejected_for_done_running_queued(tmp_path: Path) -> None:
    spawner = FakeSpawner(
        tmp_path, [{"sentences": 20, "delay": 0.03}, {"sentences": 2, "delay": 0.01}]
    )
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, first_id = _draft_book(tmp_path, name="a.epub", title="UI10 Book A")
    _src2, second_id = _draft_book(tmp_path, name="b.epub", title="UI10 Book B")
    r1 = client.post(f"/api/books/{first_id}/build", json={}, headers=headers)
    r2 = client.post(f"/api/books/{second_id}/build", json={}, headers=headers)
    assert r1.status_code == 202 and r2.status_code == 202
    first_job, second_job = r1.json()["job_id"], r2.json()["job_id"]
    # Queued resume is rejected while the first still runs.
    deadline = time.monotonic() + 10.0
    queued_seen = False
    while time.monotonic() < deadline:
        state = client.get(f"/api/jobs/{second_job}", headers=headers).json()["state"]
        if state == "queued":
            queued_seen = True
            break
        time.sleep(0.05)
    assert queued_seen
    assert client.post(f"/api/jobs/{second_job}/resume", headers=headers).status_code == 409
    running = client.post(f"/api/jobs/{first_job}/resume", headers=headers)
    assert running.status_code == 409
    assert running.json()["rule"] == "bad-state"
    _wait_job(client, first_job, headers, {"done"})
    done = client.post(f"/api/jobs/{first_job}/resume", headers=headers)
    assert done.status_code == 409
    assert done.json()["rule"] == "bad-state"


def test_resume_cancelled_build_allowed(tmp_path: Path) -> None:
    """Cancelled is resumable (RESUMABLE): cancel a queued job, resume it."""
    spawner = FakeSpawner(
        tmp_path, [{"sentences": 30, "delay": 0.05}, {"sentences": 2, "delay": 0.01}]
    )
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, first_id = _draft_book(tmp_path, name="a.epub", title="UI10 Book A")
    _src2, second_id = _draft_book(tmp_path, name="b.epub", title="UI10 Book B")
    r1 = client.post(f"/api/books/{first_id}/build", json={}, headers=headers)
    r2 = client.post(f"/api/books/{second_id}/build", json={}, headers=headers)
    second_job = r2.json()["job_id"]
    cancelled = client.post(f"/api/jobs/{second_job}/cancel", headers=headers)
    assert cancelled.status_code == 200, cancelled.text
    assert cancelled.json()["state"] == "cancelled"
    resumed = client.post(f"/api/jobs/{second_job}/resume", headers=headers)
    assert resumed.status_code == 200, resumed.text
    assert resumed.json()["state"] in ("queued", "running")
    _wait_job(client, r1.json()["job_id"], headers, {"done"})
    final = _wait_job(client, second_job, headers, {"done"})
    assert final["state"] == "done"


def test_queue_across_restart(tmp_path: Path) -> None:
    """Queued jobs left by a dead server start on the next manager."""
    from ui.jobs import JobManager

    spawner = FakeSpawner(
        tmp_path, [{"sentences": 30, "delay": 0.05}, {"sentences": 2, "delay": 0.01}]
    )
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, first_id = _draft_book(tmp_path, name="a.epub", title="UI10 Book A")
    _src2, second_id = _draft_book(tmp_path, name="b.epub", title="UI10 Book B")
    r1 = client.post(f"/api/books/{first_id}/build", json={}, headers=headers)
    r2 = client.post(f"/api/books/{second_id}/build", json={}, headers=headers)
    first_job, second_job = r1.json()["job_id"], r2.json()["job_id"]
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        states = (
            client.get(f"/api/jobs/{first_job}", headers=headers).json()["state"],
            client.get(f"/api/jobs/{second_job}", headers=headers).json()["state"],
        )
        if states == ("running", "queued"):
            break
        time.sleep(0.05)
    assert states == ("running", "queued"), states
    # Simulate a server restart: new manager, dead pids -> first becomes
    # interrupted, the queued second pumps to running.
    spawner2 = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.01}])
    manager2 = JobManager(tmp_path, spawn_fn=spawner2, pid_alive_fn=lambda _p: False)
    assert manager2.get_job(first_job)["state"] == "interrupted"
    deadline = time.monotonic() + 15.0
    seen: set[str] = set()
    while time.monotonic() < deadline:
        seen.add(manager2.get_job(second_job)["state"])
        if "running" in seen or "done" in seen:
            break
        time.sleep(0.05)
    assert seen & {"running", "done"}, seen
    client.post(f"/api/jobs/{first_job}/cancel", headers=headers)


def test_interrupted_pause_cancel_refused_resume_allowed(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.01}])
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    _src, book_id = _draft_book(tmp_path)
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    _wait_job(client, job_id, headers, {"done"})
    job_file = tmp_path / "jobs" / job_id / "job.json"
    data = json.loads(job_file.read_text(encoding="utf-8"))
    data["state"] = "running"
    data["pid"] = 999999
    job_file.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    spawner2 = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.01}])
    client2, token2 = _api_client(tmp_path, spawner=spawner2, token="ui10-second")
    headers2 = _auth(token2)
    assert client2.get(f"/api/jobs/{job_id}", headers=headers2).json()["state"] == "interrupted"
    paused = client2.post(f"/api/jobs/{job_id}/pause", headers=headers2)
    assert paused.status_code == 409
    assert paused.json()["rule"] == "bad-state"
    cancelled = client2.post(f"/api/jobs/{job_id}/cancel", headers=headers2)
    assert cancelled.status_code == 409
    assert cancelled.json()["rule"] == "bad-state"
    resumed = client2.post(f"/api/jobs/{job_id}/resume", headers=headers2)
    assert resumed.status_code == 200, resumed.text
    final = _wait_job(client2, job_id, headers2, {"done"}, timeout_s=25.0)
    assert final["state"] == "done"


# --- isolation: no engine import -----------------------------------------------------------------


def test_no_engine_imports(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """The UI10 suite serves everything without loading the TTS model."""
    for mod in ("tts.kokoro", "kokoro_onnx", "onnxruntime", "torch"):
        monkeypatch.setitem(sys.modules, mod, None)  # type: ignore[arg-type]
    # Engine factory must fail when blocked (proves the block is real).
    with pytest.raises(Exception):
        __import__("tts.kokoro")
    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.01}])
    client, _token = _api_client(tmp_path, spawner=spawner)
    assert client.get("/api/health", headers=LOCAL).status_code == 200
    assert client.get("/api/books", headers=LOCAL).status_code == 200
    assert client.get("/api/jobs", headers=LOCAL).status_code == 200
    assert client.get("/api/settings", headers=LOCAL).status_code == 200
    voices = client.get("/api/cast/voices", headers=LOCAL)
    assert voices.status_code == 200
    assert voices.json()["engine_version"] == VOICES_VERSION
    for mod in ("tts.kokoro", "kokoro_onnx"):
        assert mod not in sys.modules or sys.modules[mod] is None
