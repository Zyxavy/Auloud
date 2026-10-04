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

"""Slice 6 UI3: Books scan, detail, upload+draft (fixture workspaces only).

No model loading; no slow mark. FastAPI tests skip cleanly without the
``ui`` extra; pure scan/detail tests always run. Fixtures live in
``tmp_path`` (never the real workspace or logs).
"""

from __future__ import annotations

import json
import os
import shutil
import time
from datetime import datetime, timezone
from pathlib import Path

import pytest
from ebooklib import epub

LOCAL = {"host": "127.0.0.1:8137"}
FIXTURES = Path(__file__).resolve().parent.parent.parent / "spec" / "fixtures"

SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."


def _make_epub(path: Path, titles: list[str], title: str = "UI3 Test Book") -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-ui3-book")
    book.set_title(title)
    book.set_language("en")
    book.add_author("UI3 Author")
    items = []
    for pos, chapter_title in enumerate(titles, start=1):
        # Long body (like test_ui1's 25 repeats) so chapters survive the
        # tiny-merge as separate chapters.
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


def _api_client(workspace: Path, token: str = "ui3-token"):
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    app = create_app(workspace, token=token)
    return TestClient(app), token


def _auth(token: str) -> dict[str, str]:
    return {**LOCAL, "X-Auloud-Token": token}


def _wait_detail(client, book_id: str, headers: dict[str, str], timeout_s: float = 60.0) -> dict:
    deadline = time.monotonic() + timeout_s
    last: dict = {}
    while time.monotonic() < deadline:
        resp = client.get(f"/api/books/{book_id}", headers=headers)
        assert resp.status_code == 200, resp.text
        last = resp.json()
        if last.get("draft_status") in ("done", "failed"):
            return last
        time.sleep(0.2)
    raise AssertionError(f"draft did not finish in {timeout_s}s: {last}")


# --- pure scan ---------------------------------------------------------------


def test_scan_empty_workspace(tmp_path: Path) -> None:
    from ui.books import scan_books

    assert scan_books(tmp_path) == []


def test_scan_source_only_is_not_drafted(tmp_path: Path) -> None:
    from draft import book_id_for_file
    from ui.books import scan_books

    _make_epub(tmp_path / "novel.epub", ["Chapter One"])
    books = scan_books(tmp_path)
    assert len(books) == 1
    book = books[0]
    expected_id, _sha = book_id_for_file(tmp_path / "novel.epub")
    assert book["id"] == expected_id
    assert book["source_file"] == "novel.epub"
    assert book["drafted"] is False
    assert book["chapters"] == 0
    assert book["cast_edited"] is False
    assert book["rendering"] is False
    assert book["rendering_percent"] is None
    assert book["valid"] is False
    assert book["on_tablet"] is False


def test_scan_skips_bundle_source_copies(tmp_path: Path) -> None:
    from ui.books import find_source_files, scan_books

    _make_epub(tmp_path / "novel.epub", ["Chapter One"])
    bundle_src = tmp_path / "bundles" / "b1" / "source"
    bundle_src.mkdir(parents=True)
    shutil.copy(tmp_path / "novel.epub", bundle_src / "book.epub")
    names = [p.name for p in find_source_files(tmp_path)]
    assert names == ["novel.epub"]
    assert len(scan_books(tmp_path)) == 1


def test_scan_drafted_and_cast_edited_heuristic(tmp_path: Path) -> None:
    from draft import book_id_for_file, run_draft
    from ui.books import scan_books

    src = _make_epub(tmp_path / "novel.epub", ["Chapter One", "Chapter Two"])
    run_draft(src, work_root=tmp_path / ".scribe")
    book_id, _sha = book_id_for_file(src)
    books = scan_books(tmp_path)
    assert len(books) == 1
    assert books[0]["drafted"] is True
    assert books[0]["chapters"] == 2
    assert books[0]["cast_edited"] is False
    # Touch only the cast: the heuristic flips.
    work_dir = tmp_path / ".scribe" / book_id
    script_mtime = (work_dir / "script.json").stat().st_mtime
    os.utime(work_dir / "cast.yaml", (script_mtime + 5, script_mtime + 5))
    assert scan_books(tmp_path)[0]["cast_edited"] is True


def test_scan_rendering_lock_and_percent(tmp_path: Path) -> None:
    import json as _json

    from draft import book_id_for_file, run_draft
    from ui.books import scan_books

    src = _make_epub(tmp_path / "novel.epub", ["Chapter One"])
    result = run_draft(src, work_root=tmp_path / ".scribe")
    book_id, _sha = book_id_for_file(src)
    work_dir = tmp_path / ".scribe" / book_id
    total = result.total_sentences
    assert total > 0
    # No lock: not rendering, even with an events tail present.
    (work_dir / "events.jsonl").write_text(
        _json.dumps(
            {
                "event": "sentence",
                "chapter": 1,
                "sid": 1,
                "cached": 1,
                "rendered": 2,
                "audio_ms": 0,
                "wall_s": 1.0,
            },
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    assert scan_books(tmp_path)[0]["rendering"] is False
    # Live lock (same process): rendering with a percent from the tail.
    (work_dir / ".scribe.lock").write_text(
        _json.dumps(
            {
                "pid": os.getpid(),
                "started_at": datetime.now(timezone.utc).isoformat().replace(
                    "+00:00", "Z"
                ),
                "cmd": "build",
            }
        )
        + "\n",
        encoding="utf-8",
    )
    try:
        book = scan_books(tmp_path)[0]
        assert book["rendering"] is True
        assert book["rendering_percent"] == round(100.0 * 3 / total)
    finally:
        (work_dir / ".scribe.lock").unlink(missing_ok=True)
    # Stale lock (dead pid): ignored.
    (work_dir / ".scribe.lock").write_text(
        _json.dumps({"pid": 999999, "started_at": "2000-01-01T00:00:00Z", "cmd": "dead"}) + "\n",
        encoding="utf-8",
    )
    try:
        assert scan_books(tmp_path)[0]["rendering"] is False
    finally:
        (work_dir / ".scribe.lock").unlink(missing_ok=True)


def test_scan_valid_bundle_maps_by_source_sha(tmp_path: Path) -> None:
    from ui.books import scan_books

    (tmp_path / "empty.epub").write_bytes(b"")
    target = tmp_path / "bundles" / "b1"
    shutil.copytree(FIXTURES / "valid-bundle", target)
    (target / "source").mkdir()
    (target / "source" / "book.epub").write_bytes(b"")
    probe = _stub_probe({"ch001.mp3": 1832400, "ch002.mp3": 1640100})
    books = scan_books(tmp_path, probe=probe)
    assert len(books) == 1
    assert books[0]["bundles"] == 1
    assert books[0]["valid"] is True


def test_scan_invalid_bundle_is_not_valid(tmp_path: Path) -> None:
    from ui.books import scan_books

    (tmp_path / "empty.epub").write_bytes(b"")
    target = tmp_path / "bundles" / "bad"
    shutil.copytree(FIXTURES / "missing-mp3", target)
    books = scan_books(tmp_path, probe=_stub_probe())
    assert len(books) >= 1
    assert all(b["valid"] is False for b in books)


def test_on_tablet_stub_always_false(tmp_path: Path) -> None:
    from draft import run_draft
    from ui.books import scan_books

    src = _make_epub(tmp_path / "novel.epub", ["Chapter One"])
    run_draft(src, work_root=tmp_path / ".scribe")
    assert [b["on_tablet"] for b in scan_books(tmp_path)] == [False]


# --- detail ------------------------------------------------------------------


def test_detail_assembly_from_files_only(tmp_path: Path) -> None:
    from draft import book_id_for_file, run_draft
    from ui.books import get_book_detail

    src = _make_epub(tmp_path / "novel.epub", ["Chapter One", "Chapter Two"])
    run_draft(src, work_root=tmp_path / ".scribe")
    book_id, _sha = book_id_for_file(src)
    detail = get_book_detail(tmp_path, book_id)
    assert detail["id"] == book_id
    assert detail["drafted"] is True
    assert [c["index"] for c in detail["chapters"]] == [1, 2]
    assert all(c["sentences"] > 0 for c in detail["chapters"])
    assert detail["cast"]["has_narrator"] is True
    assert detail["valid"] is False
    assert detail["validation"] == []
    assert detail["bundles"] == []
    assert detail["on_tablet"] is False


def test_detail_unknown_id_is_key_error(tmp_path: Path) -> None:
    from ui.books import get_book_detail

    with pytest.raises(KeyError, match="book-not-found"):
        get_book_detail(tmp_path, "00000000-0000-0000-0000-000000000000")


def test_detail_bad_ids_rejected(tmp_path: Path) -> None:
    from ui.books import get_book_detail

    for bad in ("..", ".", "", "a/b", "C:evil", "a:b", "../x"):
        with pytest.raises(ValueError, match="path-traversal"):
            get_book_detail(tmp_path, bad)


def test_detail_validation_errors_shaped(tmp_path: Path) -> None:
    from draft import book_id_for_file, sha256_of_file
    from ui.books import get_book_detail

    (tmp_path / "empty.epub").write_bytes(b"")
    target = tmp_path / "bundles" / "bad"
    shutil.copytree(FIXTURES / "missing-mp3", target)
    # The fixture carries no source sha, so point its manifest at the book
    # (id match) to exercise the mapping plus the shaped errors.
    book_id, _sha = book_id_for_file(tmp_path / "empty.epub")
    manifest_path = target / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["id"] = book_id
    manifest["source"] = {"file": "empty.epub", "sha256": sha256_of_file(tmp_path / "empty.epub")}
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    detail = get_book_detail(tmp_path, book_id, probe=_stub_probe())
    assert detail["valid"] is False
    assert detail["validation"], "invalid bundle must surface errors"
    for error in detail["validation"]:
        assert set(error) == {"file", "rule", "message"}
        assert error["file"] and error["message"]


# --- upload + draft -----------------------------------------------------------


def test_upload_requires_token(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    with open(src, "rb") as handle:
        missing = client.post(
            "/api/books/upload",
            files={"file": ("novel.epub", handle, "application/epub+zip")},
            headers=LOCAL,
        )
    assert missing.status_code == 403
    with open(src, "rb") as handle:
        wrong = client.post(
            "/api/books/upload",
            files={"file": ("novel.epub", handle, "application/epub+zip")},
            headers={**LOCAL, "X-Auloud-Token": "nope"},
        )
    assert wrong.status_code == 403
    assert "nope" not in wrong.text
    listed = client.get("/api/books", headers=LOCAL)
    assert listed.status_code == 200


def test_upload_bad_extension_rejected(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    resp = client.post(
        "/api/books/upload",
        files={"file": ("notes.txt", b"hello", "text/plain")},
        headers=_auth(token),
    )
    assert resp.status_code == 400
    body = resp.json()
    assert body["rule"] == "bad-extension"
    assert set(body) == {"file", "rule", "message"}


def test_upload_sanitizes_and_confines(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    with open(src, "rb") as handle:
        resp = client.post(
            "/api/books/upload",
            files={"file": ("..\\..\\evil.epub", handle, "application/epub+zip")},
            headers=_auth(token),
        )
    assert resp.status_code == 202, resp.text
    assert (tmp_path / "evil.epub").is_file()
    assert not (tmp_path.parent / "evil.epub").exists()


def test_upload_and_draft_parity_with_cli(tmp_path: Path) -> None:
    from draft import book_id_for_file, run_draft

    client, token = _api_client(tmp_path)
    headers = _auth(token)
    src = _make_epub(tmp_path / "parcel.epub", ["Chapter One", "Chapter Two"])
    with open(src, "rb") as handle:
        resp = client.post(
            "/api/books/upload",
            files={"file": ("book.epub", handle, "application/epub+zip")},
            headers=headers,
        )
    assert resp.status_code == 202, resp.text
    book_id = resp.json()["book_id"]
    detail = _wait_detail(client, book_id, headers)
    assert detail["draft_status"] == "done", detail.get("draft_error")
    assert detail["drafted"] is True
    # CLI parity: same source bytes drafted elsewhere are byte-identical
    # (draft writes no timestamps; reports are deterministic).
    other = tmp_path / "direct"
    other.mkdir()
    shutil.copy(tmp_path / "book.epub", other / "book.epub")
    run_draft(other / "book.epub", work_root=other / ".scribe")
    ui_work = tmp_path / ".scribe" / book_id
    cli_work = other / ".scribe" / book_id
    assert book_id == book_id_for_file(other / "book.epub")[0]
    for name in ("script.json", "cast.yaml", "draft_report.md", "cast_report.md"):
        assert (ui_work / name).read_bytes() == (cli_work / name).read_bytes(), name
    # The UI1 seam exists for UI4 tailing (same key contract).
    tail_lines = (ui_work / "events.jsonl").read_text(encoding="utf-8").strip().splitlines()
    assert tail_lines
    for line in tail_lines:
        assert set(json.loads(line)) >= {
            "event",
            "chapter",
            "sid",
            "cached",
            "rendered",
            "audio_ms",
            "wall_s",
        }
    assert json.loads(tail_lines[-1])["event"] == "draft_done"
    # The list shows the same book as drafted.
    listed = client.get("/api/books", headers=headers).json()["books"]
    assert any(b["id"] == book_id and b["drafted"] for b in listed)


def test_upload_draft_failure_surfaces_shaped_error(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    resp = client.post(
        "/api/books/upload",
        files={"file": ("empty.epub", b"not an epub at all", "application/epub+zip")},
        headers=headers,
    )
    assert resp.status_code == 202, resp.text
    detail = _wait_detail(client, resp.json()["book_id"], headers)
    assert detail["draft_status"] == "failed"
    assert set(detail["draft_error"]) == {"file", "rule", "message"}


def test_detail_api_traversal_and_unknown(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    # NOTE: dot segments ("..") never reach the handler (the router
    # normalizes them to 404); colon shapes do and must be 400.
    bad = client.get("/api/books/bad:id", headers=LOCAL)
    assert bad.status_code == 400
    assert bad.json()["rule"] == "path-traversal"
    missing = client.get("/api/books/00000000-0000-0000-0000-000000000000", headers=LOCAL)
    assert missing.status_code == 404
    assert missing.json()["rule"] == "book-not-found"


def test_no_cors_headers_on_books_routes(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    listed = client.get("/api/books", headers=LOCAL)
    detailed = client.get("/api/books/does-not-exist-1", headers=LOCAL)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    with open(src, "rb") as handle:
        uploaded = client.post(
            "/api/books/upload",
            files={"file": ("a.epub", handle, "application/epub+zip")},
            headers=headers,
        )
    assert uploaded.status_code == 202
    for resp in (listed, detailed, uploaded):
        assert "access-control-allow-origin" not in resp.headers


# --- frontend -----------------------------------------------------------------


def test_frontend_static_offline_and_components() -> None:
    from ui import security

    index = Path(security.__file__).resolve().parent / "static" / "index.html"
    text = index.read_text(encoding="utf-8")
    assert "{{AULOUD_TOKEN}}" in text
    assert "http" not in text, "no CDN or external requests (offline)"
    assert ":root" in text
    for needle in (
        "auloud-table",
        "stepper",
        "chip",
        'id="tray"',
        'id="player-row"',
        'id="books-body"',
        'id="d-steps"',
    ):
        assert needle in text, needle
    assert "No books yet" in text
    assert "Pick a book" in text
