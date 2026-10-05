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

"""Slice 6 UI8: Validate (inline) and Transfer (copy+verify+record).

No model loading; no slow mark. FastAPI tests skip cleanly without the
``ui`` extra. Fixture bundles come from ``spec/fixtures/`` (never the real
workspace, models, bundles or logs).
"""

from __future__ import annotations

import json
import shutil
import time
from pathlib import Path

import pytest

LOCAL = {"host": "127.0.0.1:8137"}
FIXTURES = Path(__file__).resolve().parent.parent.parent / "spec" / "fixtures"


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


def _api_client(
    workspace: Path,
    token: str = "ui8-token",
    probe=None,
    drives_fn=None,
    open_fn=None,
):
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    kwargs: dict = {
        "pid_alive_fn": lambda _pid: False,
        "validate_probe": probe or _stub_probe({"ch001.mp3": 1832400, "ch002.mp3": 1640100}),
    }
    if drives_fn is not None:
        kwargs["drives_fn"] = drives_fn
    if open_fn is not None:
        kwargs["open_fn"] = open_fn
    app = create_app(workspace, token=token, **kwargs)
    return TestClient(app), token


def _auth(token: str) -> dict[str, str]:
    return {**LOCAL, "X-Auloud-Token": token}


def _make_book_with_bundle(tmp_path: Path, name: str = "empty.epub") -> tuple[Path, str]:
    """Workspace with one source + one mapped bundle; returns (ws, book_id)."""
    from draft import book_id_for_file, sha256_of_file

    ws = tmp_path / "ws"
    ws.mkdir(parents=True, exist_ok=True)
    (ws / name).write_bytes(b"")
    target = ws / "bundles" / "b1"
    shutil.copytree(FIXTURES / "valid-bundle", target)
    book_id, _sha = book_id_for_file(ws / name)
    manifest_path = target / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["id"] = book_id
    manifest["source"] = {"file": name, "sha256": sha256_of_file(ws / name)}
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return ws, book_id


def _wait_job(client, job_id: str, headers: dict, want: set, timeout_s: float = 30.0):
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


# --- validate (inline) -------------------------------------------------------


def test_validate_inline_shaped_and_fast(tmp_path: Path) -> None:
    ws, book_id = _make_book_with_bundle(tmp_path)
    client, token = _api_client(ws)
    headers = _auth(token)
    started = time.monotonic()
    resp = client.post(f"/api/books/{book_id}/validate", json={}, headers=headers)
    elapsed = time.monotonic() - started
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["book_id"] == book_id
    # Stub durations match the fixture manifest, but the fixture has no
    # source/book.epub file; validation reports that one missing file only.
    assert doc["valid"] is False
    assert doc["validation"], "expected the missing-source error"
    for error in doc["validation"]:
        assert set(error) == {"file", "rule", "message"}
    # Inline verdict: seconds, not a job (well under the 30 s budget).
    assert elapsed < 30.0, f"validate took {elapsed:.1f}s"
    # The detail carries the same shaped results (no separate store).
    detail = client.get(f"/api/books/{book_id}", headers=headers).json()
    assert detail["validation"] == doc["validation"]
    assert detail["bundles"] == doc["bundles"]


def test_validate_wording_matches_cli_strings(tmp_path: Path) -> None:
    """API file/message rejoin to the ``validate_bundle`` strings exactly."""
    from bundle.validate import validate_bundle

    ws, book_id = _make_book_with_bundle(tmp_path)
    probe = _stub_probe({"ch001.mp3": 1832400, "ch002.mp3": 1640100})
    expected = validate_bundle(ws / "bundles" / "b1", probe=probe).errors
    client, token = _api_client(ws, probe=probe)
    resp = client.post(f"/api/books/{book_id}/validate", json={}, headers=_auth(token))
    assert resp.status_code == 200, resp.text
    rejoined = [f"{e['file']}: {e['message']}" for e in resp.json()["validation"]]
    assert rejoined == expected


def test_validate_unknown_and_bad_id(tmp_path: Path) -> None:
    ws, _book_id = _make_book_with_bundle(tmp_path)
    client, token = _api_client(ws)
    missing = client.post(
        "/api/books/00000000-0000-0000-0000-000000000000/validate",
        json={},
        headers=_auth(token),
    )
    assert missing.status_code == 404
    assert missing.json()["rule"] == "book-not-found"
    bad = client.post("/api/books/bad:id/validate", json={}, headers=_auth(token))
    assert bad.status_code == 400
    assert bad.json()["rule"] == "path-traversal"
    # Token guard (POST needs it; GET detail does not).
    denied = client.post(
        f"/api/books/{_book_id}/validate", json={}, headers=LOCAL
    )
    assert denied.status_code == 403


# --- transfer: copy, verify, record ------------------------------------------


def test_transfer_copy_verifies_and_records(tmp_path: Path) -> None:
    ws, book_id = _make_book_with_bundle(tmp_path)
    # Add the listed source file so the stub-valid bundle is fully valid.
    (ws / "bundles" / "b1" / "source").mkdir(parents=True, exist_ok=True)
    (ws / "bundles" / "b1" / "source" / "book.epub").write_bytes(b"")
    # Rewrite the manifest source sha to the empty file so verify passes.
    from draft import sha256_of_file

    manifest_path = ws / "bundles" / "b1" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["source"] = {
        "file": "source/book.epub",
        "sha256": sha256_of_file(ws / "bundles" / "b1" / "source" / "book.epub"),
    }
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    client, token = _api_client(ws)
    headers = _auth(token)
    got = client.post(f"/api/books/{book_id}/validate", json={}, headers=headers).json()
    assert got["valid"] is True
    dest = tmp_path / "usb"
    dest.mkdir()
    resp = client.post(
        f"/api/books/{book_id}/transfer",
        json={"destination": str(dest)},
        headers=headers,
    )
    assert resp.status_code == 202, resp.text
    job_id = resp.json()["job_id"]
    final = _wait_job(client, job_id, headers, {"done"})
    assert final["kind"] == "transfer"
    assert final["verification"]["ok"] is True
    assert (final["bytes_total"] or 0) > 0
    assert final["bytes_copied"] == final["bytes_total"]
    copied = dest / "b1"
    assert (copied / "manifest.json").is_file()
    # Record owns the chip: destination + time + manifest id + outcome.
    record = json.loads((ws / ".scribe-transfers.json").read_text(encoding="utf-8"))[book_id]
    assert record["destination"] == str(dest)
    assert record["bundle_id"] == manifest["id"]
    assert record["verified"] is True
    assert record["time"]
    detail = client.get(f"/api/books/{book_id}", headers=headers).json()
    assert detail["on_tablet"] is True
    assert detail["transfer"]["destination"] == str(dest)
    listed = client.get("/api/books", headers=headers).json()["books"]
    assert next(b for b in listed if b["id"] == book_id)["on_tablet"] is True


def test_transfer_verification_catches_truncation(tmp_path: Path) -> None:
    from ui.transfer import copy_bundle_with_hashes, verify_transfer

    src = tmp_path / "src"
    (src / "audio").mkdir(parents=True)
    (src / "manifest.json").write_text('{"id": "x"}', encoding="utf-8")
    (src / "audio" / "ch001.mp3").write_bytes(bytes(range(256)) * 64)
    dst = tmp_path / "dst" / "src"
    copy_bundle_with_hashes(src, dst)
    ok, errors = verify_transfer(src, dst)
    assert ok is True and errors == []
    # Flip a byte post-copy (truncate): verification must fail.
    data = (dst / "audio" / "ch001.mp3").read_bytes()
    (dst / "audio" / "ch001.mp3").write_bytes(data[:-10])
    ok, errors = verify_transfer(src, dst)
    assert ok is False
    assert errors and all(set(e) == {"file", "rule", "message"} for e in errors)
    assert errors[0]["rule"] == "verify-failed"


def test_transfer_record_id_mismatch_clears_chip(tmp_path: Path) -> None:
    ws, book_id = _make_book_with_bundle(tmp_path)
    (ws / "bundles" / "b1" / "source").mkdir(parents=True, exist_ok=True)
    (ws / "bundles" / "b1" / "source" / "book.epub").write_bytes(b"")
    from draft import sha256_of_file

    manifest_path = ws / "bundles" / "b1" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["source"] = {
        "file": "source/book.epub",
        "sha256": sha256_of_file(ws / "bundles" / "b1" / "source" / "book.epub"),
    }
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    client, token = _api_client(ws)
    headers = _auth(token)
    dest = tmp_path / "usb2"
    dest.mkdir()
    resp = client.post(
        f"/api/books/{book_id}/transfer", json={"destination": str(dest)}, headers=headers
    )
    assert resp.status_code == 202, resp.text
    _wait_job(client, resp.json()["job_id"], headers, {"done"})
    assert client.get(f"/api/books/{book_id}", headers=headers).json()["on_tablet"] is True
    # Rebuild with a new manifest id: the stale record must not match.
    manifest["id"] = "00000000-0000-0000-0000-000000000099"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    detail = client.get(f"/api/books/{book_id}", headers=headers).json()
    assert detail["on_tablet"] is False


def test_transfer_exists_needs_force(tmp_path: Path) -> None:
    ws, book_id = _make_book_with_bundle(tmp_path)
    (ws / "bundles" / "b1" / "source").mkdir(parents=True, exist_ok=True)
    (ws / "bundles" / "b1" / "source" / "book.epub").write_bytes(b"")
    from draft import sha256_of_file

    manifest_path = ws / "bundles" / "b1" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["source"] = {
        "file": "source/book.epub",
        "sha256": sha256_of_file(ws / "bundles" / "b1" / "source" / "book.epub"),
    }
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    client, token = _api_client(ws)
    headers = _auth(token)
    dest = tmp_path / "usb3"
    dest.mkdir()
    first = client.post(
        f"/api/books/{book_id}/transfer", json={"destination": str(dest)}, headers=headers
    )
    assert first.status_code == 202, first.text
    _wait_job(client, first.json()["job_id"], headers, {"done"})
    second = client.post(
        f"/api/books/{book_id}/transfer", json={"destination": str(dest)}, headers=headers
    )
    assert second.status_code == 409
    assert second.json()["rule"] == "exists"
    forced = client.post(
        f"/api/books/{book_id}/transfer",
        json={"destination": str(dest), "force": True},
        headers=headers,
    )
    assert forced.status_code == 202, forced.text
    _wait_job(client, forced.json()["job_id"], headers, {"done"})


def test_transfer_typed_path_rejected(tmp_path: Path) -> None:
    ws, book_id = _make_book_with_bundle(tmp_path)
    client, token = _api_client(ws)
    headers = _auth(token)
    for bad in (
        "relative/usb",
        "../escape",
        "E:drive-relative",
        "dest\x00nul",
        "C:\\bad;rm",
        "C:\\bad|pipe",
        "C:\\foo\\..\\bar",
    ):
        resp = client.post(
            f"/api/books/{book_id}/transfer",
            json={"destination": bad},
            headers=headers,
        )
        assert resp.status_code == 400, (bad, resp.text)
        assert resp.json()["rule"] == "bad-destination", (bad, resp.text)


def test_transfer_needs_valid_bundle(tmp_path: Path) -> None:
    ws, book_id = _make_book_with_bundle(tmp_path)
    client, token = _api_client(ws)
    headers = _auth(token)
    # The fixture without the source file is invalid (missing source).
    dest = tmp_path / "usb4"
    dest.mkdir()
    resp = client.post(
        f"/api/books/{book_id}/transfer",
        json={"destination": str(dest)},
        headers=headers,
    )
    # Invalid bundle: refused before any copy (no job, no dest dir).
    assert resp.status_code == 409
    assert resp.json()["rule"] == "no-valid-bundle"
    assert not (dest / "b1").exists()


# --- drives + open-folder -----------------------------------------------------


def test_drives_mocked_and_empty_by_default(tmp_path: Path) -> None:
    ws, _book_id = _make_book_with_bundle(tmp_path)
    fake = [{"path": "E:\\", "removable": True}]
    client, _token = _api_client(ws, drives_fn=lambda: fake)
    resp = client.get("/api/drives", headers=LOCAL)
    assert resp.status_code == 200
    assert resp.json() == {"drives": fake}
    plain, _t2 = _api_client(tmp_path / "ws2")
    (tmp_path / "ws2").mkdir(parents=True, exist_ok=True)
    resp2 = plain.get("/api/drives", headers=LOCAL)
    assert resp2.status_code == 200
    assert isinstance(resp2.json()["drives"], list)


def test_open_folder_mocked_and_missing_record(tmp_path: Path) -> None:
    ws, book_id = _make_book_with_bundle(tmp_path)
    seen: list[str] = []
    client, token = _api_client(ws, open_fn=lambda p: seen.append(p))
    headers = _auth(token)
    # No transfer yet and no explicit destination: 400.
    missing = client.post(
        f"/api/books/{book_id}/open-folder", json={}, headers=headers
    )
    assert missing.status_code == 400
    target = tmp_path / "somewhere"
    target.mkdir()
    resp = client.post(
        f"/api/books/{book_id}/open-folder",
        json={"destination": str(target)},
        headers=headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["opened"] == str(target)
    assert seen == [str(target)]
    # Token guard (POST needs it).
    denied = client.post(
        f"/api/books/{book_id}/open-folder", json={"destination": str(target)}, headers=LOCAL
    )
    assert denied.status_code == 403


# --- frontend -----------------------------------------------------------------


def test_frontend_static_offline_and_transfer_components() -> None:
    from ui import security

    index = Path(security.__file__).resolve().parent / "static" / "index.html"
    text = index.read_text(encoding="utf-8")
    assert "{{AULOUD_TOKEN}}" in text
    assert "http" not in text, "no CDN or external requests (offline)"
    for needle in (
        'id="validate-step"',
        'id="transfer-step"',
        'id="transfer-drive"',
        'id="transfer-path"',
        'id="transfer-start"',
        "Player import",
        "watch folder",
        "validate-run",
        "transfer-force",
        "/api/drives",
        "/api/books/",
        "/validate",
        "/transfer",
        "open-folder",
    ):
        assert needle in text, needle
