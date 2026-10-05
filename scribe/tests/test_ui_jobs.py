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

"""Slice 6 UI4: job runner, queue, pause/resume/cancel, SSE, keep-awake.

A FAKE worker (in-process stub writing UI1-format events slowly — never
real Kokoro) drives every transition. The one REAL detached-process test at
the bottom uses ``python -c`` + the production ``_detached_popen`` flags to
prove Windows detach works (fast, stays in the normal suite).
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import threading
import time
from pathlib import Path

import pytest
from ebooklib import epub

LOCAL = {"host": "127.0.0.1:8137"}

SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."


def _make_epub(path: Path, titles: list[str], title: str = "UI4 Test Book") -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-ui4-book")
    book.set_title(title)
    book.set_language("en")
    book.add_author("UI4 Author")
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


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (stop-file seam needs a real build)")


# --- fake detached worker -------------------------------------------------------


class FakeProcess:
    """Popen-like stub: writes UI1-format events slowly on a thread."""

    def __init__(
        self, job: dict, folder: Path, workspace: Path, behavior: dict
    ) -> None:
        self.job = dict(job)
        self.folder = Path(folder)
        self.workspace = Path(workspace)
        self.behavior = dict(behavior)
        self.pid = 999999  # dead pid: rescans mark interrupted unless mocked alive
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

    # -- run ------------------------------------------------------------------

    def _run(self) -> None:
        try:
            if self.job.get("kind") == "draft":
                self._run_draft()
            else:
                self._run_build()
        except Exception:
            self._exit = -1

    def _run_draft(self) -> None:
        delay = float(self.behavior.get("delay", 0.1))
        deadline = time.monotonic() + max(delay, 0.05) * 4
        while time.monotonic() < deadline:
            if self._terminated:
                self._exit = -1
                return
            time.sleep(0.02)
        if self.behavior.get("fail"):
            try:
                msg = self.behavior.get("fail_message", "fake draft boom")
                with open(self.folder / "build.log", "a", encoding="utf-8") as h:
                    h.write(f"draft failed: {msg}\n")
            except OSError:
                pass
            self._exit = 1
            return
        self._exit = 0

    def _run_build(self) -> None:
        total = int(self.behavior.get("sentences", 4))
        delay = float(self.behavior.get("delay", 0.03))
        use_cache = bool(self.behavior.get("cache", False))
        events_path = self.folder / "build.log"  # log mirror, not events
        log_path = self.folder / "build.log"
        work_dir = self.workspace / ".scribe" / str(self.job.get("book_id") or "")
        if self.behavior.get("partial"):
            # Torn in-progress pair a cancel must clean (manager-side).
            for sub, name in (("audio", "ch001.mp3"), ("text", "ch001.json")):
                try:
                    target = work_dir / "render" / sub / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(b"partial")
                except OSError:
                    pass
        try:
            with open(log_path, "a", encoding="utf-8") as handle:
                handle.write("fake build start\n")
        except OSError:
            pass
        cached = rendered = 0
        wall_start = time.monotonic()
        for sid in range(1, total + 1):
            if self._terminated:
                self._exit = -1
                return
            try:
                if (self.folder / "stop").exists():
                    self._exit = 1  # graceful: manager marks paused
                    return
            except OSError:
                pass
            if use_cache:
                marker = work_dir / "cache" / f"s{sid}.done"
                if marker.is_file():
                    cached += 1
                else:
                    try:
                        marker.parent.mkdir(parents=True, exist_ok=True)
                        marker.write_text("cached\n", encoding="utf-8")
                    except OSError:
                        pass
                    rendered += 1
            else:
                rendered += 1
            line = {
                "event": "sentence",
                "chapter": 1,
                "sid": sid,
                "cached": cached,
                "rendered": rendered,
                "audio_ms": 0,
                "wall_s": round(time.monotonic() - wall_start, 3),
            }
            try:
                with open(self.folder / "events.jsonl", "a", encoding="utf-8") as handle:
                    handle.write(json.dumps(line, sort_keys=True) + "\n")
            except OSError:
                self._exit = -1
                return
            time.sleep(delay)
        if self._terminated:
            self._exit = -1
            return
        try:
            if (self.folder / "stop").exists():
                self._exit = 1
                return
        except OSError:
            pass
        if self.behavior.get("fail"):
            try:
                msg = self.behavior.get("fail_message", "fake.epub: fake-rule: fake boom")
                with open(log_path, "a", encoding="utf-8") as handle:
                    handle.write(f"build failed: {msg}\n")
            except OSError:
                pass
            self._exit = 1
            return
        done = {
            "event": "build_done",
            "chapter": 0,
            "sid": None,
            "cached": cached,
            "rendered": rendered,
            "audio_ms": 1000,
            "wall_s": round(time.monotonic() - wall_start, 3),
        }
        try:
            with open(self.folder / "events.jsonl", "a", encoding="utf-8") as handle:
                handle.write(json.dumps(done, sort_keys=True) + "\n")
        except OSError:
            pass
        _ = events_path
        self._exit = 0


class FakeSpawner:
    """Per-spawn behaviors for builds (list consumed in order; last repeats).

    Draft spawns always use a fast default (uploads must not consume the
    build behaviors the test queued up).
    """

    def __init__(self, workspace: Path, behaviors: list[dict]) -> None:
        self.workspace = Path(workspace)
        self.behaviors = [dict(b) for b in behaviors] or [{}]
        self.calls: list[dict] = []
        self.procs: list[FakeProcess] = []
        self._builds = 0

    def __call__(self, job: dict, folder: Path) -> FakeProcess:
        self.calls.append(dict(job))
        if job.get("kind") == "draft":
            behavior: dict = {"delay": 0.02}
        else:
            idx = min(self._builds, len(self.behaviors) - 1)
            behavior = self.behaviors[idx]
            self._builds += 1
        proc = FakeProcess(job, folder, self.workspace, behavior)
        self.procs.append(proc)
        return proc


# --- client + wait helpers ---------------------------------------------------------


def _api_client(workspace: Path, spawner: FakeSpawner | None = None,
                token: str = "ui4-token", pid_alive_fn=None):  # type: ignore[no-untyped-def]
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    kwargs: dict = {"spawn_fn": spawner} if spawner is not None else {}
    if pid_alive_fn is not None:
        kwargs["pid_alive_fn"] = pid_alive_fn
    else:
        kwargs["pid_alive_fn"] = lambda _pid: False
    app = create_app(workspace, token=token, **kwargs)
    return TestClient(app), token, app


def _auth(token: str) -> dict[str, str]:
    return {**LOCAL, "X-Auloud-Token": token}


def _wait_job(client, job_id: str, headers: dict[str, str], want: set[str],  # type: ignore[no-untyped-def]
              timeout_s: float = 20.0) -> dict:
    deadline = time.monotonic() + timeout_s
    last: dict = {}
    while time.monotonic() < deadline:
        resp = client.get(f"/api/jobs/{job_id}", headers=headers)
        assert resp.status_code == 200, resp.text
        last = resp.json()
        if last.get("state") in want:
            return last
        time.sleep(0.05)
    raise AssertionError(f"job {job_id} never reached {want} in {timeout_s}s: {last}")


def _upload_epub(client, headers: dict[str, str], src: Path, name: str = "book.epub") -> dict:  # type: ignore[no-untyped-def]
    with open(src, "rb") as handle:
        resp = client.post(
            "/api/books/upload",
            files={"file": (name, handle, "application/epub+zip")},
            headers=headers,
        )
    assert resp.status_code == 202, resp.text
    return resp.json()


def _draft_done_client(  # type: ignore[no-untyped-def]
    client, headers: dict[str, str], params: dict, timeout_s: float = 30.0
) -> dict:
    """Wait for the draft job behind an upload to finish (UI4 job folders)."""
    job_id = params.get("job_id")
    assert job_id, f"upload did not return a job_id: {params}"
    return _wait_job(client, job_id, headers, {"done", "failed"}, timeout_s=timeout_s)


# --- transitions ----------------------------------------------------------------------


def test_build_runs_to_done_with_events_and_log(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 4, "delay": 0.02}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    drafted = _draft_done_client(client, headers, uploaded)
    assert drafted["state"] == "done", drafted
    book_id = uploaded["book_id"]
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    assert resp.status_code == 202, resp.text
    job_id = resp.json()["job_id"]
    final = _wait_job(client, job_id, headers, {"done"})
    assert final["summary"]["rendered"] == 4
    job_dir = tmp_path / "jobs" / job_id
    assert (job_dir / "job.json").is_file()
    assert (job_dir / "events.jsonl").is_file()
    assert (job_dir / "build.log").is_file()
    lines = (job_dir / "events.jsonl").read_text(encoding="utf-8").strip().splitlines()
    assert lines
    want_keys = {"event", "chapter", "sid", "cached", "rendered", "audio_ms", "wall_s"}
    for line in lines:
        assert set(json.loads(line)) == want_keys
    assert json.loads(lines[-1])["event"] == "build_done"


def test_build_failure_shapes_error(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.02, "fail": True,
                                      "fail_message": "fake.epub: fake-rule: fake boom"}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    resp = client.post(f"/api/books/{uploaded['book_id']}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    final = _wait_job(client, job_id, headers, {"failed"})
    assert set(final["error"]) == {"file", "rule", "message"}
    assert final["error"]["rule"] == "fake-rule"


def test_pause_then_resume_completes(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 20, "delay": 0.05}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    resp = client.post(f"/api/books/{uploaded['book_id']}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    # Let it start (at least one event), then pause gracefully.
    deadline = time.monotonic() + 10.0
    events_file = tmp_path / "jobs" / job_id / "events.jsonl"
    while time.monotonic() < deadline:
        lines = events_file.read_text(encoding="utf-8").splitlines()
        if len(lines) >= 2:
            break
        time.sleep(0.05)
    paused = client.post(f"/api/jobs/{job_id}/pause", headers=headers)
    assert paused.status_code == 200, paused.text
    final_pause = _wait_job(client, job_id, headers, {"paused"})
    assert final_pause["state"] == "paused"
    assert not (tmp_path / "jobs" / job_id / "stop").exists()
    resumed = client.post(f"/api/jobs/{job_id}/resume", headers=headers)
    assert resumed.status_code == 200, resumed.text
    final = _wait_job(client, job_id, headers, {"done"})
    assert final["state"] == "done"
    builds = [c for c in spawner.calls if c.get("kind") == "build"]
    assert len(builds) == 2  # resume is a new process


def test_cancel_running_cleans_partial_output(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 30, "delay": 0.05, "partial": True}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    book_id = uploaded["book_id"]
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        work = tmp_path / ".scribe" / book_id / "render" / "audio" / "ch001.mp3"
        if work.is_file():
            break
        time.sleep(0.05)
    assert (tmp_path / ".scribe" / book_id / "render" / "audio" / "ch001.mp3").is_file()
    cancelled = client.post(f"/api/jobs/{job_id}/cancel", headers=headers)
    assert cancelled.status_code == 200, cancelled.text
    final = _wait_job(client, job_id, headers, {"cancelled"})
    assert final["state"] == "cancelled"
    # Torn pair removed (best-effort literal); the cache dir is untouched.
    assert not (tmp_path / ".scribe" / book_id / "render" / "audio" / "ch001.mp3").exists()
    assert not (tmp_path / ".scribe" / book_id / "render" / "text" / "ch001.json").exists()


def test_cancel_queued_job(tmp_path: Path) -> None:
    spawner = FakeSpawner(
        tmp_path, [{"sentences": 30, "delay": 0.05}, {"sentences": 2, "delay": 0.02}]
    )
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    first_src = _make_epub(tmp_path / "a.epub", ["Chapter One"], title="Book A")
    second_src = _make_epub(tmp_path / "b.epub", ["Chapter One"], title="Book B")
    first = _upload_epub(client, headers, first_src, name="a.epub")
    second = _upload_epub(client, headers, second_src, name="b.epub")
    _draft_done_client(client, headers, first)
    _draft_done_client(client, headers, second)
    r1 = client.post(f"/api/books/{first['book_id']}/build", json={}, headers=headers)
    r2 = client.post(f"/api/books/{second['book_id']}/build", json={}, headers=headers)
    assert r1.status_code == 202 and r2.status_code == 202
    second_id = r2.json()["job_id"]
    cancelled = client.post(f"/api/jobs/{second_id}/cancel", headers=headers)
    assert cancelled.status_code == 200, cancelled.text
    assert cancelled.json()["state"] == "cancelled"


def test_bad_transitions_are_409(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.02}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    resp = client.post(f"/api/books/{uploaded['book_id']}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    _wait_job(client, job_id, headers, {"done"})
    assert client.post(f"/api/jobs/{job_id}/pause", headers=headers).status_code == 409
    assert client.post(f"/api/jobs/{job_id}/cancel", headers=headers).status_code == 409
    assert client.post(f"/api/jobs/{job_id}/resume", headers=headers).status_code == 409


# --- queueing ----------------------------------------------------------------------------


def test_cross_book_queueing_second_waits_for_first(tmp_path: Path) -> None:
    spawner = FakeSpawner(
        tmp_path, [{"sentences": 12, "delay": 0.05}, {"sentences": 2, "delay": 0.02}]
    )
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    first_src = _make_epub(tmp_path / "a.epub", ["Chapter One"], title="Book A")
    second_src = _make_epub(tmp_path / "b.epub", ["Chapter One"], title="Book B")
    first = _upload_epub(client, headers, first_src, name="a.epub")
    second = _upload_epub(client, headers, second_src, name="b.epub")
    _draft_done_client(client, headers, first)
    _draft_done_client(client, headers, second)
    r1 = client.post(f"/api/books/{first['book_id']}/build", json={}, headers=headers)
    r2 = client.post(f"/api/books/{second['book_id']}/build", json={}, headers=headers)
    assert r1.status_code == 202 and r2.status_code == 202
    first_id, second_id = r1.json()["job_id"], r2.json()["job_id"]
    # Poll until the FIFO shape shows (first still running, second queued).
    deadline = time.monotonic() + 10.0
    pair = ("", "")
    while time.monotonic() < deadline:
        first_state = client.get(f"/api/jobs/{first_id}", headers=headers).json()["state"]
        second_state = client.get(f"/api/jobs/{second_id}", headers=headers).json()["state"]
        pair = (first_state, second_state)
        if pair == ("running", "queued"):
            break
        if pair[0] in ("done", "failed") and pair[1] != "queued":
            break
        time.sleep(0.05)
    assert pair == ("running", "queued"), pair
    _wait_job(client, first_id, headers, {"done"})
    _wait_job(client, second_id, headers, {"done"}, timeout_s=25.0)


def test_second_build_for_same_book_is_refused(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 20, "delay": 0.05}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    book_id = uploaded["book_id"]
    first = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    assert first.status_code == 202
    second = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    assert second.status_code == 409
    assert second.json()["rule"] == "work-lock-held"


def test_build_refused_while_cli_holds_lock(tmp_path: Path) -> None:
    import json as _json

    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.02}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    book_id = uploaded["book_id"]
    work_dir = tmp_path / ".scribe" / book_id
    from datetime import datetime, timezone as _tz

    _now = datetime.now(_tz.utc).isoformat().replace("+00:00", "Z")
    (work_dir / ".scribe.lock").write_text(
        _json.dumps({"pid": os.getppid(), "started_at": _now, "cmd": "cli"}),
        encoding="utf-8",
    )
    try:
        resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    finally:
        (work_dir / ".scribe.lock").unlink(missing_ok=True)
    assert resp.status_code == 409
    assert resp.json()["rule"] == "work-lock-held"


# --- reattach / interrupted -----------------------------------------------------------------


def test_reattach_after_restart_marks_interrupted_then_resumes(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.02}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    resp = client.post(f"/api/books/{uploaded['book_id']}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    _wait_job(client, job_id, headers, {"done"})
    # Simulate a crash: rewrite the stored record to running with a dead pid.
    job_file = tmp_path / "jobs" / job_id / "job.json"
    data = json.loads(job_file.read_text(encoding="utf-8"))
    data["state"] = "running"
    data["pid"] = 999999
    job_file.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    # New app object, same workspace: the scan must mark it interrupted.
    spawner2 = FakeSpawner(tmp_path, [{"sentences": 3, "delay": 0.02, "cache": True}])
    client2, token2, _app2 = _api_client(tmp_path, spawner2, token="ui4-second")
    headers2 = _auth(token2)
    seen = client2.get(f"/api/jobs/{job_id}", headers=headers2)
    assert seen.status_code == 200, seen.text
    assert seen.json()["state"] == "interrupted"
    resumed = client2.post(f"/api/jobs/{job_id}/resume", headers=headers2)
    assert resumed.status_code == 200, resumed.text
    final = _wait_job(client2, job_id, headers2, {"done"}, timeout_s=25.0)
    assert final["state"] == "done"


def test_interrupted_resume_reuses_cache_markers(tmp_path: Path) -> None:
    from ui.jobs import JobManager

    spawner = FakeSpawner(tmp_path, [{"sentences": 4, "delay": 0.02, "cache": True}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    book_id = uploaded["book_id"]
    resp = client.post(f"/api/books/{book_id}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    final = _wait_job(client, job_id, headers, {"done"})
    assert final["summary"]["rendered"] == 4
    cache_dir = tmp_path / ".scribe" / book_id / "cache"
    assert len(list(cache_dir.glob("s*.done"))) == 4
    # Crash it, restart, resume: cached sentences are skipped, not re-rendered.
    job_file = tmp_path / "jobs" / job_id / "job.json"
    data = json.loads(job_file.read_text(encoding="utf-8"))
    data["state"] = "running"
    data["pid"] = 999999
    job_file.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    spawner2 = FakeSpawner(tmp_path, [{"sentences": 4, "delay": 0.02, "cache": True}])
    manager2 = JobManager(tmp_path, spawn_fn=spawner2, pid_alive_fn=lambda _p: False)
    assert manager2.get_job(job_id)["state"] == "interrupted"
    manager2.resume(job_id)
    deadline = time.monotonic() + 20.0
    last = {}
    while time.monotonic() < deadline:
        last = manager2.get_job(job_id)
        if last["state"] == "done":
            break
        time.sleep(0.05)
    assert last["state"] == "done"
    assert last["summary"]["cached"] == 4  # every sentence skipped via markers
    assert last["summary"]["rendered"] == 0
    assert len(list(cache_dir.glob("s*.done"))) == 4  # no duplicates, nothing wiped


# --- SSE ----------------------------------------------------------------------------------------


def test_sse_replay_heartbeat_and_last_event_id(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 3, "delay": 0.02}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    resp = client.post(f"/api/books/{uploaded['book_id']}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    _wait_job(client, job_id, headers, {"done"})
    full = client.get(f"/api/jobs/{job_id}/events", headers=headers)
    assert full.status_code == 200, full.text
    assert "text/event-stream" in full.headers["content-type"]
    body = full.text
    assert ": ping" in body  # heartbeat comment present
    assert "data:" in body
    assert body.count("id:") >= 4  # 3 sentences + build_done
    # Reconnect from offset replays only the tail.
    resumed = client.get(
        f"/api/jobs/{job_id}/events", headers={**headers, "Last-Event-ID": "2"}
    )
    assert resumed.status_code == 200, resumed.text
    assert "id: 1\n" not in resumed.text
    assert "id: 3" in resumed.text


def test_job_id_charset_rejected_and_token_guarded(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    for bad in ("bad:id", "../x", "a/b", "x y", ""):
        resp = client.get(f"/api/jobs/{bad or ' '}", headers=headers)
        assert resp.status_code in (400, 404, 405), (bad, resp.status_code)
    missing = client.post("/api/jobs/nope-missing/pause", headers=headers)
    assert missing.status_code == 404
    assert set(missing.json()) == {"file", "rule", "message"}
    # POST without the token is forbidden (and never leaks the token).
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    with open(src, "rb") as handle:
        denied = client.post(
            "/api/books/upload",
            files={"file": ("b.epub", handle, "application/epub+zip")},
            headers=LOCAL,
        )
    assert denied.status_code == 403


def test_jobs_list_and_settings_round_trip(tmp_path: Path) -> None:
    spawner = FakeSpawner(tmp_path, [{"sentences": 2, "delay": 0.02}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    assert client.get("/api/jobs", headers=headers).json() == {"jobs": []}
    # UI5 extends settings with the compute device (default auto).
    assert client.get("/api/settings", headers=headers).json() == {
        "keep_awake": True,
        "device": "auto",
    }
    updated = client.put("/api/settings", json={"keep_awake": False}, headers=headers)
    assert updated.status_code == 200
    assert updated.json() == {"keep_awake": False, "device": "auto"}
    assert (tmp_path / ".scribe-ui-settings.json").is_file()
    assert client.get("/api/settings", headers=headers).json() == {
        "keep_awake": False,
        "device": "auto",
    }
    bad = client.put("/api/settings", json={"keep_awake": "yes"}, headers=headers)
    assert bad.status_code == 400


def test_keepawake_held_while_running(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str] = []
    import ui.keepawake as keepawake

    monkeypatch.setattr(keepawake, "acquire", lambda _ws=None: calls.append("acquire"))
    monkeypatch.setattr(keepawake, "release", lambda: calls.append("release"))
    spawner = FakeSpawner(tmp_path, [{"sentences": 6, "delay": 0.05}])
    client, token, _app = _api_client(tmp_path, spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    uploaded = _upload_epub(client, headers, src)
    _draft_done_client(client, headers, uploaded)
    resp = client.post(f"/api/books/{uploaded['book_id']}/build", json={}, headers=headers)
    job_id = resp.json()["job_id"]
    _wait_job(client, job_id, headers, {"done"})
    assert "acquire" in calls
    assert calls.count("release") >= calls.count("acquire")


# --- additive cross-process stop seam -------------------------------------------


def test_stop_file_stops_build_at_sentence_boundary(tmp_path: Path) -> None:
    _needs_ffmpeg()
    import numpy as np

    from build import BuildStoppedError, run_build
    from tts.base import TTSEngine

    class FakeEngine(TTSEngine):
        @property
        def sample_rate(self) -> int:
            return 24_000

        @property
        def engine_version(self) -> str:
            return "fake-stop-file"

        def synth(self, text: str, voice: str, speed: float):  # type: ignore[override]
            t = np.arange(2400, dtype=np.float64) / 24_000
            return (0.4 * np.sin(2 * np.pi * 440.0 * t)).astype(np.float32)

    epub_path = _make_epub(tmp_path / "book.epub", ["Chapter One", "Chapter Two"])
    stop = tmp_path / "stop"
    stop.write_text("stop\n", encoding="utf-8")  # pre-armed: first boundary stops
    with pytest.raises(BuildStoppedError, match="stopped"):
        run_build(
            epub_path,
            work_root=tmp_path / "work",
            out_dir=tmp_path / "out",
            engine=FakeEngine(),
            show_progress=False,
            stop_file=stop,
        )
    assert not (tmp_path / "out" / "manifest.json").exists()


# --- REAL detached process (Windows flags proof; fast, normal suite) --------


def test_real_detached_process_writes_events_and_terminates(tmp_path: Path) -> None:
    from ui.jobs import _detached_popen

    events = tmp_path / "events.jsonl"
    log = tmp_path / "build.log"
    # NOTE: ``python -c`` forbids a compound ``while`` after a ``;``, so
    # the child script is newline-joined (the same tail/terminate pattern
    # the server uses for SSE still applies).
    script = (
        "import json, time\n"
        f"p = {str(events)!r}\n"
        "t0 = time.monotonic()\n"
        "n = 0\n"
        "while time.monotonic() - t0 < 30:\n"
        "    n += 1\n"
        "    line = json.dumps({'event': 'sentence', 'chapter': 1, 'sid': n, "
        "'cached': 0, 'rendered': n, 'audio_ms': 0, 'wall_s': 0.0}, "
        "sort_keys=True)\n"
        "    open(p, 'a', encoding='utf-8').write(line + chr(10))\n"
        "    time.sleep(0.2)\n"
    )
    proc = _detached_popen([sys.executable, "-c", script], log)
    try:
        assert proc.pid and proc.pid > 0
        # Tail while it runs (the UI4 server tails the same way for SSE).
        deadline = time.monotonic() + 10.0
        seen: list[str] = []
        while time.monotonic() < deadline:
            try:
                seen = events.read_text(encoding="utf-8").splitlines()
            except OSError:
                seen = []
            if len(seen) >= 3:
                break
            time.sleep(0.05)
        assert len(seen) >= 3, f"detached child wrote no events: {seen}"
        want_keys = {"event", "chapter", "sid", "cached", "rendered", "audio_ms", "wall_s"}
        for line in seen:
            assert set(json.loads(line)) == want_keys
        assert proc.poll() is None  # still running detached (not reaped by tail)
    finally:
        try:
            proc.terminate()
        except Exception:
            pass
        try:
            proc.wait(timeout=10)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
    assert proc.poll() is not None
