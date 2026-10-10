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

"""FP5: upload streaming/placement safety plus transfer-resume delete guard.

Uploads stream to a ``.part`` temp in chunks (never whole in RAM), are
capped, and land via exclusive link with -N retry; transfer resume and the
transfer runner re-validate the stored destination plus a dirname match
before any delete. Fixture workspaces only; no model loading.
"""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path

import pytest

LOCAL = {"host": "127.0.0.1:8137"}


def _api_client(workspace: Path, token: str = "fp5-token"):
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    app = create_app(workspace, token=token)
    return TestClient(app), token


def _auth(token: str) -> dict[str, str]:
    return {**LOCAL, "X-Auloud-Token": token}


def test_upload_oversize_refused(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    import ui.app as appmod

    monkeypatch.setattr(appmod, "MAX_UPLOAD_BYTES", 64)
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    payload = b"x" * 65
    resp = client.post(
        "/api/books/upload",
        files={"file": ("big.epub", payload, "application/epub+zip")},
        headers=headers,
    )
    assert resp.status_code == 413, resp.text
    body = resp.json()
    assert set(body) == {"file", "rule", "message"}
    assert body["rule"] == "too-large"
    assert not (tmp_path / "big.epub").exists()
    assert list(tmp_path.glob(".upload-*.part")) == []
    assert client.get("/api/jobs", headers=headers).json()["jobs"] == []


def test_upload_same_name_concurrency(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import ui.app as appmod

    # Tiny chunks force the multi-chunk streaming loop; full-range bytes
    # (including b"\n") pin binary-safe writing (Windows O_BINARY).
    monkeypatch.setattr(appmod, "_UPLOAD_CHUNK_BYTES", 16)
    client, token = _api_client(tmp_path)
    app = client.app
    headers = _auth(token)
    from fastapi.testclient import TestClient

    payloads = {n: f"fp5-{n}:".encode() + bytes(range(256)) * 4 for n in range(4)}
    results: dict[int, object] = {}
    barrier = threading.Barrier(len(payloads))

    def _one(n: int) -> None:
        barrier.wait(timeout=10.0)
        per_thread = TestClient(app)
        results[n] = per_thread.post(
            "/api/books/upload",
            files={"file": ("book.epub", payloads[n], "application/epub+zip")},
            headers=headers,
        )

    threads = [threading.Thread(target=_one, args=(n,)) for n in payloads]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=60.0)
    assert all(not thread.is_alive() for thread in threads)
    names: dict[int, str] = {}
    for n, resp in results.items():
        assert resp.status_code == 202, (n, resp.text)
        names[n] = resp.json()["filename"]
    assert len(set(names.values())) == len(payloads), names
    for n, name in names.items():
        assert (tmp_path / name).read_bytes() == payloads[n]
    assert list(tmp_path.glob(".upload-*.part")) == []


def test_upload_partial_never_drafted(tmp_path: Path) -> None:
    from ui.books import find_source_files, scan_books

    (tmp_path / ".upload-deadbeef.part").write_bytes(b"truncated garbage")
    assert ".upload-deadbeef.part" not in [p.name for p in find_source_files(tmp_path)]
    assert all(
        ".part" not in str(book.get("source_file") or "") for book in scan_books(tmp_path)
    )
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    # A ".part" name can never be uploaded as a book (suffix gate first).
    refused = client.post(
        "/api/books/upload",
        files={"file": ("evil.epub.part", b"data", "application/epub+zip")},
        headers=headers,
    )
    assert refused.status_code == 400
    assert refused.json()["rule"] == "bad-extension"
    # An empty upload leaves no file and queues no draft.
    empty = client.post(
        "/api/books/upload",
        files={"file": ("empty.epub", b"", "application/epub+zip")},
        headers=headers,
    )
    assert empty.status_code == 400
    assert empty.json()["rule"] == "empty-file"
    assert not (tmp_path / "empty.epub").exists()
    assert client.get("/api/jobs", headers=headers).json()["jobs"] == []


def test_safe_join_tolerates_extended_prefix_race(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Windows resolve() transiently returns ``\\\\?\\`` forms under
    concurrent first-time dir creation; that must never read as an escape."""
    from ui.security import safe_join

    real_resolve = Path.resolve
    calls = {"n": 0}

    def _flaky(self: Path, strict: bool = False) -> Path:
        out = real_resolve(self, strict=strict)
        calls["n"] += 1
        if calls["n"] == 2 and not str(out).startswith("\\\\?\\"):
            return Path("\\\\?\\" + str(out))
        return out

    monkeypatch.setattr(Path, "resolve", _flaky)
    assert safe_join(tmp_path, "jobs", "job-abc123").name == "job-abc123"
    # Genuine escapes still refuse under the same patched resolve.
    with pytest.raises(ValueError, match="path-traversal"):
        safe_join(tmp_path, "..", "evil.txt")


def _wait_state(manager, job_id: str, want: set[str], timeout_s: float = 30.0) -> dict:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        job = manager.get_job(job_id)
        if job.get("state") in want:
            return job
        time.sleep(0.05)
    raise AssertionError(f"job {job_id} never reached {want}")


def test_resume_moved_drive_refuses_not_deletes(tmp_path: Path) -> None:
    from ui.jobs import JobManager, job_dir

    ws = tmp_path / "ws"
    ws.mkdir()
    bundle = ws / "bundles" / "b1"
    bundle.mkdir(parents=True)
    (bundle / "manifest.json").write_text(json.dumps({"id": "x"}), encoding="utf-8")
    (bundle / "ch001.txt").write_bytes(b"audio-bytes")
    dest = tmp_path / "usb"
    dest.mkdir()
    manager = JobManager(ws)
    job = manager.create_transfer("fp5book1", "bundles/b1", str(dest))
    job_id = job["id"]
    _wait_state(manager, job_id, {"done"})
    # Park the job as failed, then point its dest_bundle at a victim dir.
    folder = job_dir(ws, job_id)
    job_file = folder / "job.json"
    data = json.loads(job_file.read_text(encoding="utf-8"))
    victim = tmp_path / "victim"
    victim.mkdir()
    (victim / "keep.txt").write_bytes(b"keep")
    data["state"] = "failed"
    data["dest_bundle"] = str(victim)
    data["force"] = True
    job_file.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="bad-destination"):
        manager.resume(job_id)
    assert (victim / "keep.txt").read_bytes() == b"keep"
    assert manager.get_job(job_id)["state"] == "failed"
    # An invalid stored destination refuses the same way.
    data = json.loads(job_file.read_text(encoding="utf-8"))
    data["destination"] = "relative/nowhere"
    data["dest_bundle"] = str(dest / "b1")
    job_file.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="bad-destination"):
        manager.resume(job_id)
    assert manager.get_job(job_id)["state"] == "failed"
    # The runner itself re-checks before any delete (defense in depth).
    data = json.loads(job_file.read_text(encoding="utf-8"))
    data["state"] = "running"
    data["destination"] = str(dest)
    data["dest_bundle"] = str(victim)
    data["force"] = True
    job_file.write_text(json.dumps(data, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    manager._run_transfer_job(job_id)
    final = manager.get_job(job_id)
    assert final["state"] == "failed"
    assert (final["error"] or {}).get("rule") == "bad-destination"
    assert (victim / "keep.txt").read_bytes() == b"keep"
