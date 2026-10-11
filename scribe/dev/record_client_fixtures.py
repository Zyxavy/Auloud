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

"""Record real helper responses for the Rust client tests (Slice 16 API2).

Boots a dev helper (never the frozen bundle: fixtures pin the contract, not
a build), drives one short book end to end, and saves every response body
under ``scribe-rs/scribe-client/tests/data/``. Bodies carry no token and no
port; absolute work paths inside are machine-specific and parsed, never
asserted. Re-record after any contract change::

    uv run python dev/record_client_fixtures.py
"""

from __future__ import annotations

import http.client
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIBE = HERE.parent
REPO = SCRIBE.parent
DATA = REPO / "scribe-rs" / "scribe-client" / "tests" / "data"
BOOK = REPO / "spec" / "fixtures" / "epub2-minimal" / "source" / "book.epub"
MODELS = SCRIBE / "models"
TOKEN = "fixture-recorder"
TERMINAL = {"done", "failed", "cancelled"}


def call(method: str, base: str, path: str, body=None, headers=None, token=True):
    data = None
    heads = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode()
        heads["Content-Type"] = "application/json"
    if token:
        heads["X-Auloud-Token"] = TOKEN
    request = urllib.request.Request(
        base + path, data=data, headers=heads, method=method
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def upload(base: str, book: Path):
    boundary = "FIXTUREBOUNDARY"
    with open(book, "rb") as handle:
        blob = handle.read()
    payload = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{book.name}"\r\n'
        "Content-Type: application/epub+zip\r\n\r\n"
    ).encode() + blob + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        base + "/api/books/upload",
        data=payload,
        headers={
            "X-Auloud-Token": TOKEN,
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.status, response.read()


def poll_job(base: str, job_id: str, timeout_s: float = 600.0):
    start = time.monotonic()
    while time.monotonic() - start < timeout_s:
        status, raw = call("GET", base, f"/api/jobs/{job_id}")
        assert status == 200, raw[:200]
        job = json.loads(raw)
        if job["state"] in TERMINAL:
            return job
        time.sleep(5)
    raise TimeoutError(f"job {job_id} not terminal in {timeout_s}s")


def sse_sample(base: str, job_id: str, max_bytes: int = 4096) -> str:
    parts = urllib.parse.urlsplit(base)
    connection = http.client.HTTPConnection(parts.hostname, parts.port, timeout=30)
    connection.request(
        "GET",
        f"/api/jobs/{job_id}/events",
        headers={"X-Auloud-Token": TOKEN},
    )
    response = connection.getresponse()
    assert response.status == 200, response.status
    chunk = response.read(max_bytes)
    connection.close()
    return chunk.decode("utf-8", "replace")


def save(name: str, payload) -> None:
    path = DATA / name
    if isinstance(payload, (dict, list)):
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    else:
        path.write_text(str(payload), encoding="utf-8")
    print("wrote", path.name)


def main() -> None:
    assert BOOK.is_file(), f"fixture book missing: {BOOK}"
    DATA.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="client-fixtures-"))
    env = dict(os.environ, AULOUD_HELPER_TOKEN=TOKEN, PYTHONPATH=str(REPO))
    proc = subprocess.Popen(
        # `python -m scribe` must resolve the working tree (HP1 __main__
        # shim), never the stale installed copy: PYTHONPATH points at the
        # repo root while cwd stays in scribe/ so relative `models`
        # resolves to the dev models. Plain sys.executable: no uv
        # reinstall, safe beside running renders.
        [sys.executable, "-m", "scribe", "helper",
         "--workspace", str(work), "--port", "0"],
        cwd=str(SCRIBE),
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    assert proc.stdout is not None
    port = 0
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        line = proc.stdout.readline()
        if not line:
            break
        try:
            payload = json.loads(line)
        except ValueError:
            continue
        if payload.get("ready"):
            port = int(payload["port"])
            versions = payload["versions"]
            break
    assert port, "no ready line from helper"
    base = f"http://127.0.0.1:{port}"
    try:
        save("_meta.json", {"contract": "docs/api.md", "versions": versions})
        status, raw = call("GET", base, "/api/health")
        assert status == 200
        save("health.json", json.loads(raw))
        status, raw = call("GET", base, "/api/version")
        assert status == 200
        save("version.json", json.loads(raw))
        status, raw = call("GET", base, "/api/books")
        save("books-empty.json", json.loads(raw))
        status, raw = call("GET", base, "/api/jobs")
        save("jobs-empty.json", json.loads(raw))
        status, raw = call("GET", base, "/api/settings")
        save("settings.json", json.loads(raw))
        status, raw = call("GET", base, "/api/cast/voices?engine=kokoro")
        assert status == 200
        save("voices-kokoro.json", json.loads(raw))
        status, raw = call("GET", base, "/api/health", token=False)
        save("error-403.json", {"status": status, "body": json.loads(raw)})

        status, raw = upload(base, BOOK)
        assert status == 202, raw[:300]
        save("upload.json", json.loads(raw))
        book_id = json.loads(raw)["book_id"]
        job_id = json.loads(raw)["job_id"]
        save("draft-job.json", poll_job(base, job_id))

        status, raw = call("GET", base, f"/api/books/{book_id}")
        assert status == 200
        save("book-detail.json", json.loads(raw))
        status, raw = call("GET", base, f"/api/books/{book_id}/cast")
        assert status == 200
        save("cast.json", json.loads(raw))
        status, raw = call(
            "POST", base, f"/api/books/{book_id}/plan", {"chapters": "1"}
        )
        assert status == 200
        save("plan.json", json.loads(raw))
        status, raw = call(
            "POST",
            base,
            f"/api/books/{book_id}/build",
            {"chapters": "1", "device": "cpu"},
        )
        assert status == 202, raw[:300]
        save("build-queued.json", json.loads(raw))
        build_job = poll_job(base, json.loads(raw)["job_id"])
        assert build_job["state"] == "done", build_job.get("error")
        save("build-job.json", build_job)
        save("events-sample.txt", sse_sample(base, build_job["id"]))
        status, raw = call("POST", base, f"/api/books/{book_id}/validate")
        assert status == 200
        save("validate.json", json.loads(raw))
        assert json.loads(raw)["valid"]

        status, raw = call(
            "POST",
            base,
            "/api/voices/sample",
            {"voices": ["am_onyx"], "engine": "kokoro"},
        )
        assert status in (200, 202), raw[:300]
        save("sample-initial.json", json.loads(raw))
        if status == 202:
            sample_job = poll_job(base, json.loads(raw)["job_id"], timeout_s=300.0)
            assert sample_job["state"] == "done", sample_job.get("error")
            save("sample-job.json", sample_job)
            status, raw = call(
                "POST",
                base,
                "/api/voices/sample",
                {"voices": ["am_onyx"], "engine": "kokoro"},
            )
            assert status == 200, raw[:300]
            save("sample-cached.json", json.loads(raw))

        status, raw = call("GET", base, "/api/books/no-such-book")
        save("error-404-book.json", {"status": status, "body": json.loads(raw)})
        status, raw = call("GET", base, "/api/jobs/!!!")
        save("error-400-job.json", {"status": status, "body": json.loads(raw)})
        print("ALL FIXTURES RECORDED")
    finally:
        proc.terminate()
        proc.wait(timeout=60)


if __name__ == "__main__":
    main()
