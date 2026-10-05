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

"""Slice 6 UI5: Render step — preflight API, compute settings, delta re-render.

No model loading; the TTS seam is the same FakeEngine + real ffmpeg pattern
as test_ui1/test_build (stubbed synth, real fingerprint logic). Fixture books
live in ``tmp_path`` (never the real workspace, models, bundles or logs).
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import numpy as np
import pytest
from ebooklib import epub

from tts.base import TTSEngine

LOCAL = {"host": "127.0.0.1:8137"}

SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."
ENGINE_VERSION = "fake-ui5"


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (render tests need real binaries)")


class FakeEngine(TTSEngine):
    """Deterministic stand-in: exact 100 ms sine per sentence (as test_ui1)."""

    def __init__(self, version: str = ENGINE_VERSION) -> None:
        self.calls: list[tuple[str, str, float]] = []
        self._version = version

    @property
    def sample_rate(self) -> int:
        return 24_000

    @property
    def engine_version(self) -> str:
        return self._version

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:  # type: ignore[override]
        self.calls.append((text, voice, float(speed)))
        freq = 440.0 + (len(text) % 5) * 110.0
        t = np.arange(2400, dtype=np.float64) / 24_000
        return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _make_epub(path: Path, titles: list[str], title: str = "UI5 Test Book") -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-ui5-book")
    book.set_title(title)
    book.set_language("en")
    book.add_author("UI5 Author")
    items = []
    for pos, chapter_title in enumerate(titles, start=1):
        # Long body (like the UI1/UI3 helpers) so chapters survive tiny-merge.
        # Each chapter gets its own sentence text so the sentence-audio cache
        # (keyed by text) cannot leak hits across chapters in delta tests.
        line = f"The quiet alder fox crosses the mossy hill at dawn in chapter {pos}."
        body = " ".join([line] * 25)
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


def _pdf_sentence(word: str) -> str:
    return f"The quiet {word} fox crosses the mossy hill at dawn."


def _make_pdf(path: Path) -> Path:
    """4-page PDF: CHAPTER ONE on page 1, CHAPTER TWO on page 3 (no outline)."""
    import pymupdf

    doc = pymupdf.open()
    headings = {1: "CHAPTER ONE", 3: "CHAPTER TWO"}
    for page_no in range(1, 5):
        page = doc.new_page(width=595.0, height=842.0)
        top = 120.0
        if page_no in headings:
            page.insert_text((72, top), headings[page_no], fontsize=14)
            top += 40.0
        y = top
        for part in (1, 2):
            text = f"Story page {page_no} part {part} " + " ".join(
                [_pdf_sentence("elm")] * 8
            )
            rect = pymupdf.Rect(72, y, 523, 782)
            unused = page.insert_textbox(rect, text, fontsize=11)
            unused_h = unused.height if hasattr(unused, "height") else float(unused)
            y += (rect.height - unused_h) + 18.0
    doc.save(str(path))
    doc.close()
    return path


def _script_sentences(work_root: Path, book_id: str) -> dict[int, list[str]]:
    """``{source_chapter: [sentence texts]}`` from the drafted script."""
    script = json.loads((work_root / book_id / "script.json").read_text(encoding="utf-8"))
    out: dict[int, list[str]] = {}
    for chapter in script["chapters"]:
        texts = [
            s["text"]
            for block in chapter["blocks"]
            for s in block.get("sentences", [])
        ]
        out[int(chapter["chapter"])] = texts
    return out


class _DeadProcess:
    """Popen-like stub that is already exited (device-default tests only)."""

    pid = 111111

    def poll(self):  # type: ignore[no-untyped-def]
        return 0

    def wait(self, timeout: float | None = None):  # type: ignore[no-untyped-def]
        return 0

    def terminate(self) -> None:
        return None

    def kill(self) -> None:
        return None


class _RecordingSpawner:
    """Spawn stub: records build jobs, finishes them at once (no audio)."""

    def __init__(self) -> None:
        self.calls: list[dict] = []

    def __call__(self, job: dict, folder: Path) -> _DeadProcess:  # type: ignore[no-untyped-def]
        self.calls.append(dict(job))
        return _DeadProcess()


def _api_client(workspace: Path, token: str = "ui5-token", spawner=None, engine=None):  # type: ignore[no-untyped-def]
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    kwargs: dict = {"plan_engine": engine or FakeEngine()}
    if spawner is not None:
        kwargs["spawn_fn"] = spawner
        kwargs["pid_alive_fn"] = lambda _pid: False
    app = create_app(workspace, token=token, **kwargs)
    return TestClient(app), token


def _auth(token: str) -> dict[str, str]:
    return {**LOCAL, "X-Auloud-Token": token}


def _draft_epub(workspace: Path, name: str = "book.epub") -> tuple[Path, str]:
    """Draft a 2-chapter EPUB into ``workspace/.scribe``; return (src, book_id)."""
    from draft import book_id_for_file, run_draft

    src = _make_epub(workspace / name, ["Chapter One", "Chapter Two"])
    run_draft(src, work_root=workspace / ".scribe")
    book_id, _sha = book_id_for_file(src)
    return src, book_id


# --- preflight numbers vs known caches ----------------------------------------


def test_plan_fresh_book_all_to_render_no_estimate(tmp_path: Path) -> None:
    _needs_ffmpeg()
    _src, book_id = _draft_epub(tmp_path)
    client, token = _api_client(tmp_path)
    resp = client.post(f"/api/books/{book_id}/plan", json={}, headers=_auth(token))
    assert resp.status_code == 200, resp.text
    plan = resp.json()
    assert plan["book_id"] == book_id
    assert plan["source_chapters_total"] == 2
    assert plan["selected_chapters"] == [1, 2]
    assert plan["is_range"] is False
    assert plan["cached_chapters"] == 0
    assert plan["to_render_chapters"] == 2
    assert plan["cached_sentences"] == 0
    assert plan["to_render_sentences"] > 0
    # No build stats yet: no estimate, no RTF basis.
    assert plan["estimated_seconds"] is None
    assert plan["rtf_used"] is None
    assert plan["page_resolution"] == ""
    assert "no recent RTF" in plan["message"]


def test_plan_after_range_build_covers_only_the_delta(tmp_path: Path) -> None:
    """You-check (API level): re-ranging renders only new sentences (via cache)."""
    _needs_ffmpeg()
    from build import run_build
    from draft import book_id_for_range, sha256_of_file

    src, book_id = _draft_epub(tmp_path)
    by_chapter = _script_sentences(tmp_path / ".scribe", book_id)
    assert set(by_chapter) == {1, 2}
    assert all(len(texts) > 0 for texts in by_chapter.values())
    # First build renders chapter 1 only (real fingerprint logic, stub synth).
    first = run_build(
        src,
        work_root=tmp_path / ".scribe",
        out_dir=tmp_path / "out-1",
        engine=FakeEngine(),
        show_progress=False,
        chapters="1",
    )
    assert first.selected_chapters == [1]
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    # Narrow plan is fully cached now.
    narrow = client.post(f"/api/books/{book_id}/plan", json={"chapters": "1"}, headers=headers)
    assert narrow.status_code == 200, narrow.text
    assert narrow.json()["cached_sentences"] == len(by_chapter[1])
    assert narrow.json()["to_render_sentences"] == 0
    # Wider plan covers exactly the delta: ch1 cached, ch2 to render.
    wide = client.post(
        f"/api/books/{book_id}/plan", json={"chapters": "1-2"}, headers=headers
    )
    assert wide.status_code == 200, wide.text
    plan = wide.json()
    assert plan["selected_chapters"] == [1, 2]
    assert plan["cached_sentences"] == len(by_chapter[1])
    assert plan["to_render_sentences"] == len(by_chapter[2])
    assert plan["estimated_seconds"] is not None  # build stats now calibrate it
    assert plan["rtf_used"] is not None
    assert plan["is_range"] is False  # 1-2 of 2 chapters is the full book
    # Range id matches the library's range-aware id for a true sub-range.
    sub = client.post(f"/api/books/{book_id}/plan", json={"chapters": "2"}, headers=headers)
    assert sub.status_code == 200, sub.text
    sha = sha256_of_file(src)
    assert sub.json()["book_id"] == book_id_for_range(sha, [2])
    assert sub.json()["is_range"] is True
    # Second (wider) build reuses the cache: only chapter-2 lines re-synthesize.
    second_engine = FakeEngine()
    second = run_build(
        src,
        work_root=tmp_path / ".scribe",
        out_dir=tmp_path / "out-12",
        engine=second_engine,
        show_progress=False,
        chapters="1-2",
    )
    assert second.rendered == 1 and second.skipped == 1
    got = {text for text, _voice, _speed in second_engine.calls}
    assert got == set(by_chapter[2]), "wider build must synth only chapter-2 lines"


# --- range grammar + page resolution ------------------------------------------


def test_plan_range_grammar_errors_verbatim(tmp_path: Path) -> None:
    _src, book_id = _draft_epub(tmp_path)
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    backwards = client.post(
        f"/api/books/{book_id}/plan", json={"chapters": "5-3"}, headers=headers
    )
    assert backwards.status_code == 400, backwards.text
    body = backwards.json()
    assert body["rule"] == "bad-range"
    assert set(body) == {"file", "rule", "message"}
    # CLI-verbatim: rejoining file/rule/message gives the library string.
    from selection import parse_range_spec

    try:
        parse_range_spec("5-3", total=10, kind="chapters")
    except ValueError as exc:
        assert f"{body['file']}: {body['rule']}: {body['message']}" == str(exc)
    else:  # pragma: no cover - the grammar always rejects backwards ranges
        raise AssertionError("parse_range_spec accepted a backwards range")
    assert "runs backwards" in body["message"]
    oor = client.post(
        f"/api/books/{book_id}/plan", json={"chapters": "99"}, headers=headers
    )
    assert oor.status_code == 400
    assert oor.json()["rule"] == "bad-range"
    assert "out of range" in oor.json()["message"]
    both = client.post(
        f"/api/books/{book_id}/plan",
        json={"chapters": "1", "pages": "1-2"},
        headers=headers,
    )
    assert both.status_code == 400
    assert both.json()["rule"] == "chapters-pages-exclusive"
    assert "cannot be used together" in both.json()["message"]
    typed = client.post(
        f"/api/books/{book_id}/plan", json={"chapters": 3}, headers=headers
    )
    assert typed.status_code == 400
    assert typed.json()["rule"] == "bad-request"


def test_plan_pages_rejected_for_epub(tmp_path: Path) -> None:
    _src, book_id = _draft_epub(tmp_path)
    client, token = _api_client(tmp_path)
    resp = client.post(
        f"/api/books/{book_id}/plan", json={"pages": "1-2"}, headers=_auth(token)
    )
    assert resp.status_code == 400, resp.text
    assert resp.json()["rule"] == "pages-need-pdf"
    assert "EPUB" in resp.json()["message"]


def test_plan_pages_resolve_to_covering_chapters(tmp_path: Path) -> None:
    from draft import book_id_for_file, run_draft

    src = _make_pdf(tmp_path / "novel.pdf")
    run_draft(src, work_root=tmp_path / ".scribe")
    book_id, _sha = book_id_for_file(src)
    script = _script_sentences(tmp_path / ".scribe", book_id)
    assert sorted(script) == [1, 2], f"heading split must give 2 chapters: {sorted(script)}"
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    first = client.post(f"/api/books/{book_id}/plan", json={"pages": "1"}, headers=headers)
    assert first.status_code == 200, first.text
    plan = first.json()
    assert plan["selected_chapters"] == [1]
    assert "pages 1 map to chapter(s) 1" in plan["page_resolution"]
    assert "whole covering chapters render" in plan["page_resolution"]
    assert plan["page_resolution"] in plan["message"]
    across = client.post(
        f"/api/books/{book_id}/plan", json={"pages": "2-3"}, headers=headers
    )
    assert across.status_code == 200, across.text
    assert across.json()["selected_chapters"] == [1, 2]


def test_plan_unknown_book_and_bad_id_and_token(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    missing = client.post(
        "/api/books/00000000-0000-0000-0000-000000000000/plan",
        json={},
        headers=_auth(token),
    )
    assert missing.status_code == 404
    assert missing.json()["rule"] == "book-not-found"
    bad = client.post("/api/books/bad:id/plan", json={}, headers=_auth(token))
    assert bad.status_code == 400
    assert bad.json()["rule"] == "path-traversal"
    denied = client.post(
        "/api/books/00000000-0000-0000-0000-000000000000/plan", json={}, headers=LOCAL
    )
    assert denied.status_code == 403


# --- compute settings ----------------------------------------------------------


def test_settings_device_round_trip_and_validation(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    headers = _auth(token)
    assert client.get("/api/settings", headers=headers).json() == {
        "keep_awake": True,
        "device": "auto",
    }
    cpu = client.put("/api/settings", json={"device": "cpu"}, headers=headers)
    assert cpu.status_code == 200, cpu.text
    assert cpu.json() == {"keep_awake": True, "device": "cpu"}
    stored = json.loads((tmp_path / ".scribe-ui-settings.json").read_text(encoding="utf-8"))
    assert stored["device"] == "cpu"
    # Legacy keep-awake-only PUT still works and keeps the stored device.
    legacy = client.put("/api/settings", json={"keep_awake": False}, headers=headers)
    assert legacy.status_code == 200
    assert legacy.json() == {"keep_awake": False, "device": "cpu"}
    assert client.get("/api/settings", headers=headers).json() == {
        "keep_awake": False,
        "device": "cpu",
    }
    # Unknown device: CLI-identical bad-device wording (rejoined triple).
    bad = client.put("/api/settings", json={"device": "tpu"}, headers=headers)
    assert bad.status_code == 400
    assert bad.json()["rule"] == "bad-device"
    assert (
        f"{bad.json()['file']}: {bad.json()['rule']}: {bad.json()['message']}"
        == "device: bad-device: 'tpu' must be one of ['auto', 'cpu', 'cuda']"
    )
    assert set(bad.json()) == {"file", "rule", "message"}
    # CUDA: accepted only when this machine's onnxruntime reports it.
    from device import resolve_device_providers

    try:
        resolve_device_providers("cuda")
    except ValueError as exc:
        cuda = client.put("/api/settings", json={"device": "cuda"}, headers=headers)
        assert cuda.status_code == 400
        assert cuda.json()["rule"] == "cuda-unavailable"
        assert (
            f"{cuda.json()['file']}: {cuda.json()['rule']}: {cuda.json()['message']}"
            == str(exc)
        )
    else:
        cuda = client.put("/api/settings", json={"device": "cuda"}, headers=headers)
        assert cuda.status_code == 200, cuda.text
        assert cuda.json()["device"] == "cuda"
    # Empty and non-bool payloads stay 400; missing token stays 403.
    assert client.put("/api/settings", json={}, headers=headers).status_code == 400
    assert (
        client.put("/api/settings", json={"keep_awake": "yes"}, headers=headers).status_code
        == 400
    )
    assert (
        client.put("/api/settings", json={"device": "cpu"}, headers=LOCAL).status_code == 403
    )


def test_build_uses_settings_device_default(tmp_path: Path) -> None:
    from draft import book_id_for_file

    spawner = _RecordingSpawner()
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    first_src = _make_epub(tmp_path / "a.epub", ["Chapter One"], title="Book A")
    second_src = _make_epub(tmp_path / "b.epub", ["Chapter One"], title="Book B")
    first_id, _ = book_id_for_file(first_src)
    second_id, _ = book_id_for_file(second_src)
    stored = client.put("/api/settings", json={"device": "cpu"}, headers=headers)
    assert stored.status_code == 200
    defaulted = client.post(f"/api/books/{first_id}/build", json={}, headers=headers)
    assert defaulted.status_code == 202, defaulted.text
    assert client.get(f"/api/jobs/{defaulted.json()['job_id']}", headers=headers).json()[
        "device"
    ] == "cpu"
    explicit = client.post(
        f"/api/books/{second_id}/build", json={"device": "auto"}, headers=headers
    )
    assert explicit.status_code == 202, explicit.text
    assert client.get(f"/api/jobs/{explicit.json()['job_id']}", headers=headers).json()[
        "device"
    ] == "auto"
    assert [c["device"] for c in spawner.calls if c.get("kind") == "build"] == [
        "cpu",
        "auto",
    ]


def test_build_bad_device_wording_matches_cli(tmp_path: Path) -> None:
    spawner = _RecordingSpawner()
    client, token = _api_client(tmp_path, spawner=spawner)
    headers = _auth(token)
    src = _make_epub(tmp_path / "src.epub", ["Chapter One"])
    from draft import book_id_for_file

    book_id, _ = book_id_for_file(src)
    resp = client.post(f"/api/books/{book_id}/build", json={"device": "tpu"}, headers=headers)
    assert resp.status_code == 400, resp.text
    # File names the book source (UI4 convention, like work-lock-held); the
    # rule plus the message tail are the CLI's bad-device wording verbatim.
    assert resp.json()["rule"] == "bad-device"
    assert resp.json()["message"] == "'tpu' must be one of ['auto', 'cpu', 'cuda']"


# --- frontend -------------------------------------------------------------------


def test_frontend_render_step_offline_tokens_and_empty_states() -> None:
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
        'id="render-step"',
        'id="render-chapters"',
        'id="render-chapters-drop"',
        'id="render-chapters-sum"',
        'Chapters — all ',
        'id="render-pages"',
        'id="render-preflight"',
        'id="render-start"',
        'id="render-live"',
        'id="render-device"',
        'renderLiveStep',
        'latestBuildJob',
        "whole covering chapters",
        "No timing history yet",
        "Draft has not finished yet",
        "No chapters found",
    ):
        assert needle in text, needle
