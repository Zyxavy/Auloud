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

"""Slice 6 UI7: Voices view and audition service — versioned clips + jobs.

No model loading; the audition synth is stubbed by fake spawners writing
dummy WAV bytes into the versioned cache (never real Kokoro). Fixture state
lives in ``tmp_path`` (never the real workspace, models, bundles or logs).
"""

from __future__ import annotations

import threading
import time
from pathlib import Path

import pytest

LOCAL = {"host": "127.0.0.1:8137"}

DUMMY_WAV = b"RIFF" + bytes(100)

VERSION_A = "fake-voices-1"
VERSION_B = "fake-voices-2"


class _DeadProcess:
    """Popen-like stub that is already exited (fast voices jobs)."""

    pid = 111111

    def poll(self):  # type: ignore[no-untyped-def]
        return 0

    def wait(self, timeout: float | None = None):  # type: ignore[no-untyped-def]
        return 0

    def terminate(self) -> None:
        return None

    def kill(self) -> None:
        return None


class _VoicesSpawner:
    """Spawn stub: writes dummy versioned clips synchronously, then exits."""

    def __init__(self, workspace: Path) -> None:
        self.workspace = Path(workspace)
        self.calls: list[dict] = []

    def __call__(self, job: dict, folder: Path) -> _DeadProcess:  # type: ignore[no-untyped-def]
        self.calls.append(dict(job))
        if job.get("kind") == "voices-sample":
            from ui.cast import versioned_samples_dir

            out = versioned_samples_dir(
                self.workspace,
                str(job.get("engine_version") or ""),
                str(job.get("text_hash") or ""),
            )
            out.mkdir(parents=True, exist_ok=True)
            for pos, voice in enumerate(job.get("voices") or [], start=1):
                (out / f"{pos}-{voice}.wav").write_bytes(DUMMY_WAV)
        return _DeadProcess()


class _BlockingVoicesProcess:
    """Popen-like stub that stays running until released (coalescing tests)."""

    pid = 222222

    def __init__(self, job: dict, folder: Path, workspace: Path, release: threading.Event) -> None:
        self.job = dict(job)
        self.folder = Path(folder)
        self.workspace = Path(workspace)
        self._release = release
        self._exit: int | None = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        self._release.wait(timeout=30.0)
        try:
            if self.job.get("kind") == "voices-sample":
                from ui.cast import versioned_samples_dir

                out = versioned_samples_dir(
                    self.workspace,
                    str(self.job.get("engine_version") or ""),
                    str(self.job.get("text_hash") or ""),
                )
                out.mkdir(parents=True, exist_ok=True)
                for pos, voice in enumerate(self.job.get("voices") or [], start=1):
                    (out / f"{pos}-{voice}.wav").write_bytes(DUMMY_WAV)
        except OSError:
            self._exit = -1
            return
        self._exit = 0

    def poll(self):  # type: ignore[no-untyped-def]
        return self._exit

    def wait(self, timeout: float | None = None):  # type: ignore[no-untyped-def]
        self._thread.join(timeout=timeout)
        return self._exit

    def terminate(self) -> None:
        self._release.set()

    def kill(self) -> None:
        self._release.set()


class _BlockingSpawner:
    """One blocking voices job (stays running until ``release`` is set)."""

    def __init__(self, workspace: Path) -> None:
        self.workspace = Path(workspace)
        self.calls: list[dict] = []
        self.release = threading.Event()

    def __call__(self, job: dict, folder: Path) -> _BlockingVoicesProcess:  # type: ignore[no-untyped-def]
        self.calls.append(dict(job))
        return _BlockingVoicesProcess(job, folder, self.workspace, self.release)


def _api_client(  # type: ignore[no-untyped-def]
    workspace: Path, token: str = "ui7-token", spawner=None, voices_version: str = VERSION_A
):
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    kwargs: dict = {"voices_version": voices_version, "pid_alive_fn": lambda _pid: False}
    if spawner is not None:
        kwargs["spawn_fn"] = spawner
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
    raise AssertionError(f"job {job_id} never reached {want} in {timeout_s}s: {last}")


def _versioned_clip(workspace: Path, voice: str, version: str = VERSION_A) -> Path:
    from ui.cast import sample_text_hash, versioned_samples_dir

    folder = versioned_samples_dir(workspace, version, sample_text_hash())
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"1-{voice}.wav"
    path.write_bytes(DUMMY_WAV)
    return path


# --- palette list -------------------------------------------------------------


def test_palette_list_includes_engine_version_and_details(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from ui.cast import PALETTE_VOICES

    # Isolate CWD so the CWD-models fallback cannot promote this to engine.
    monkeypatch.chdir(tmp_path)
    client, _token = _api_client(tmp_path, voices_version=VERSION_A)
    resp = client.get("/api/cast/voices", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["voices"] == list(PALETTE_VOICES)
    assert doc["source"] == "palette"
    assert doc["engine_version"] == VERSION_A
    assert isinstance(doc["text_hash"], str) and len(doc["text_hash"]) == 12
    assert isinstance(doc["sample_text"], str) and len(doc["sample_text"]) > 20
    by_name = {d["name"]: d for d in doc["details"]}
    assert set(by_name) == set(PALETTE_VOICES)
    for entry in doc["details"]:
        assert set(entry) == {"name", "locale", "gender"}
    assert by_name["af_bella"] == {"name": "af_bella", "locale": "American", "gender": "female"}
    assert by_name["bm_lewis"] == {"name": "bm_lewis", "locale": "British", "gender": "male"}
    assert by_name["am_onyx"]["gender"] == "male"
    assert by_name["jf_alpha"] == {"name": "jf_alpha", "locale": "Japanese", "gender": "female"}
    assert by_name["zf_xiaoxiao"]["locale"] == "Chinese"
    assert by_name["im_nicola"]["locale"] == "Italian"


def test_describe_voice_prefix_hints_unknown_safe() -> None:
    from ui.cast import describe_voice

    assert describe_voice("af_heart") == {
        "name": "af_heart",
        "locale": "American",
        "gender": "female",
    }
    assert describe_voice("xx_qqq")["locale"] == "unknown"
    assert describe_voice("xx_qqq")["gender"] == "unknown"
    assert describe_voice("")["locale"] == "unknown"


# --- serving ------------------------------------------------------------------


def test_cached_clip_serves_with_immutable_headers(tmp_path: Path) -> None:
    _versioned_clip(tmp_path, "af_bella")
    client, _token = _api_client(tmp_path, voices_version=VERSION_A)
    resp = client.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"] == "audio/wav"
    assert resp.content == DUMMY_WAV
    assert "immutable" in resp.headers["cache-control"]
    assert "31536000" in resp.headers["cache-control"]


def test_legacy_flat_serves_with_no_cache(tmp_path: Path) -> None:
    from ui.cast import samples_dir

    flat = samples_dir(tmp_path)
    flat.mkdir(parents=True)
    (flat / "01-af_heart.wav").write_bytes(DUMMY_WAV)
    client, _token = _api_client(tmp_path, voices_version=VERSION_A)
    resp = client.get("/api/voices/af_heart.wav", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    assert resp.content == DUMMY_WAV
    assert resp.headers["cache-control"] == "no-cache"


def test_versioned_wins_over_legacy_flat(tmp_path: Path) -> None:
    from ui.cast import samples_dir

    flat = samples_dir(tmp_path)
    flat.mkdir(parents=True)
    (flat / "01-af_bella.wav").write_bytes(b"RIFF-legacy")
    _versioned_clip(tmp_path, "af_bella")
    client, _token = _api_client(tmp_path, voices_version=VERSION_A)
    resp = client.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    assert resp.content == DUMMY_WAV
    assert "immutable" in resp.headers["cache-control"]


def test_missing_clip_409_shape_without_job(tmp_path: Path) -> None:
    client, _token = _api_client(tmp_path, voices_version=VERSION_A)
    resp = client.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert resp.status_code == 409, resp.text
    body = resp.json()
    assert body["rule"] == "sample-missing"
    assert "voices --sample" in body["message"]
    assert body["job_id"] is None
    assert body["engine_version"] == VERSION_A
    assert isinstance(body["text_hash"], str)
    assert client.get("/api/voices/..%2F..%2Fx.wav", headers={**LOCAL}).status_code in (400, 404)


def test_serving_cached_performs_no_engine_import(  # type: ignore[no-untyped-def]
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import sys

    import build

    _versioned_clip(tmp_path, "af_bella")

    def _boom(*args: object, **kwargs: object) -> object:
        raise AssertionError("server must not load the TTS model to serve a clip")

    monkeypatch.setattr(build, "create_engine", _boom)
    for module in ("tts.kokoro", "kokoro_onnx"):
        sys.modules.pop(module, None)
    client, _token = _api_client(tmp_path, voices_version=VERSION_A)
    resp = client.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    assert "tts.kokoro" not in sys.modules
    assert "kokoro_onnx" not in sys.modules


# --- generation jobs ----------------------------------------------------------


def test_same_version_post_is_cached_no_job(tmp_path: Path) -> None:
    from ui.cast import PALETTE_VOICES

    _versioned_clip(tmp_path, "af_bella")
    spawner = _VoicesSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    resp = client.post("/api/voices/sample", json={"voices": ["af_bella"]}, headers=headers)
    assert resp.status_code == 200, resp.text
    assert resp.json()["cached"] is True
    assert spawner.calls == []
    assert client.get("/api/jobs", headers=headers).json() == {"jobs": []}
    assert PALETTE_VOICES  # palette import guard (list asserted in the palette test)


def test_version_bump_regenerates(tmp_path: Path) -> None:
    _versioned_clip(tmp_path, "af_bella", version=VERSION_A)
    spawner = _VoicesSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    hit = client.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert hit.status_code == 200  # same version: cache hit, no job
    assert client.get("/api/jobs", headers=headers).json() == {"jobs": []}
    # Bumped engine version on the same workspace: the old dir no longer hits.
    client2, token2 = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_B)
    headers2 = _auth(token2)
    miss = client2.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert miss.status_code == 409
    assert miss.json()["job_id"] is None
    made = client2.post("/api/voices/sample", json={"voices": ["af_bella"]}, headers=headers2)
    assert made.status_code == 202, made.text
    final = _wait_job(client2, made.json()["job_id"], headers2, {"done"})
    assert final["kind"] == "voices-sample"
    assert final["voices"] == ["af_bella"]
    assert final["engine_version"] == VERSION_B
    assert client2.get("/api/voices/af_bella.wav", headers={**LOCAL}).status_code == 200


def test_concurrent_posts_coalesce_to_one_job(tmp_path: Path) -> None:
    spawner = _BlockingSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    first = client.post("/api/voices/sample", json={"voices": ["af_bella"]}, headers=headers)
    assert first.status_code == 202, first.text
    assert first.json()["coalesced"] is False
    job_id = first.json()["job_id"]
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        state = client.get(f"/api/jobs/{job_id}", headers=headers).json()["state"]
        if state == "running":
            break
        time.sleep(0.05)
    second = client.post("/api/voices/sample", json={"voices": ["af_bella"]}, headers=headers)
    assert second.status_code == 202, second.text
    assert second.json()["job_id"] == job_id  # coalesced: one job, not two
    assert second.json()["coalesced"] is True
    assert len(spawner.calls) == 1
    # A running regenerate-all also covers a single-voice retry.
    single = client.post("/api/voices/af_bella/sample", json={}, headers=headers)
    assert single.status_code == 202, single.text
    assert single.json()["job_id"] == job_id
    assert len(spawner.calls) == 1
    spawner.release.set()
    _wait_job(client, job_id, headers, {"done"})
    assert client.get("/api/voices/af_bella.wav", headers={**LOCAL}).status_code == 200


def test_get_missing_with_active_job_returns_job_id(tmp_path: Path) -> None:
    spawner = _BlockingSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    made = client.post("/api/voices/sample", json={"voices": ["af_bella"]}, headers=headers)
    job_id = made.json()["job_id"]
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        if client.get(f"/api/jobs/{job_id}", headers=headers).json()["state"] == "running":
            break
        time.sleep(0.05)
    missing = client.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert missing.status_code == 409
    assert missing.json()["job_id"] == job_id  # coalesced 409 + job reference
    spawner.release.set()
    _wait_job(client, job_id, headers, {"done"})


def test_409_then_ready_flow(tmp_path: Path) -> None:
    spawner = _VoicesSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    assert client.get("/api/voices/af_bella.wav", headers={**LOCAL}).status_code == 409
    made = client.post("/api/voices/sample", json={"voices": ["af_bella"]}, headers=headers)
    assert made.status_code == 202, made.text
    _wait_job(client, made.json()["job_id"], headers, {"done"})
    ready = client.get("/api/voices/af_bella.wav", headers={**LOCAL})
    assert ready.status_code == 200
    assert ready.content == DUMMY_WAV


def test_per_voice_post_cached_and_regenerate(tmp_path: Path) -> None:
    spawner = _VoicesSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    first = client.post("/api/voices/af_bella/sample", json={}, headers=headers)
    assert first.status_code == 202, first.text
    _wait_job(client, first.json()["job_id"], headers, {"done"})
    assert len(spawner.calls) == 1
    cached = client.post("/api/voices/af_bella/sample", json={}, headers=headers)
    assert cached.status_code == 200
    assert cached.json()["cached"] is True
    assert len(spawner.calls) == 1  # no new job
    forced = client.post("/api/voices/af_bella/sample", json={"regenerate": True}, headers=headers)
    assert forced.status_code == 202, forced.text
    assert forced.json()["coalesced"] is False
    _wait_job(client, forced.json()["job_id"], headers, {"done"})
    assert len(spawner.calls) == 2


def test_regenerate_all_writes_every_palette_voice(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from ui.cast import PALETTE_VOICES, find_versioned_sample

    # Isolate CWD: omitted voices means all available, which is the palette
    # only without a models dir.
    monkeypatch.chdir(tmp_path)
    spawner = _VoicesSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    made = client.post("/api/voices/sample", json={"regenerate": True}, headers=headers)
    assert made.status_code == 202, made.text
    assert sorted(made.json()["voices"]) == sorted(PALETTE_VOICES)
    _wait_job(client, made.json()["job_id"], headers, {"done"})
    for voice in PALETTE_VOICES:
        assert find_versioned_sample(tmp_path, voice, VERSION_A) is not None, voice


def test_unknown_and_bad_voices_rejected(tmp_path: Path) -> None:
    spawner = _VoicesSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    unknown = client.post(
        "/api/voices/sample", json={"voices": ["no_such_voice_xyz"]}, headers=headers
    )
    assert unknown.status_code == 400
    assert unknown.json()["rule"] == "unknown-voice"
    bad_shape = client.post("/api/voices/sample", json={"voices": ["bad:voice"]}, headers=headers)
    assert bad_shape.status_code == 400
    empty = client.post("/api/voices/sample", json={"voices": []}, headers=headers)
    assert empty.status_code == 400
    typed = client.post("/api/voices/sample", json={"voices": "af_bella"}, headers=headers)
    assert typed.status_code == 400
    bad_regen = client.post("/api/voices/sample", json={"regenerate": "yes"}, headers=headers)
    assert bad_regen.status_code == 400
    per_unknown = client.post("/api/voices/no_such_voice_xyz/sample", json={}, headers=headers)
    assert per_unknown.status_code == 400
    assert per_unknown.json()["rule"] == "unknown-voice"
    denied = client.post("/api/voices/sample", json={}, headers=LOCAL)
    assert denied.status_code == 403
    assert spawner.calls == []


def test_pause_voices_rejected_cancel_works(tmp_path: Path) -> None:
    spawner = _BlockingSpawner(tmp_path)
    client, token = _api_client(tmp_path, spawner=spawner, voices_version=VERSION_A)
    headers = _auth(token)
    made = client.post("/api/voices/sample", json={"voices": ["af_bella"]}, headers=headers)
    job_id = made.json()["job_id"]
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        if client.get(f"/api/jobs/{job_id}", headers=headers).json()["state"] == "running":
            break
        time.sleep(0.05)
    paused = client.post(f"/api/jobs/{job_id}/pause", headers=headers)
    assert paused.status_code == 409
    assert paused.json()["rule"] == "bad-state"
    cancelled = client.post(f"/api/jobs/{job_id}/cancel", headers=headers)
    assert cancelled.status_code == 200, cancelled.text
    assert cancelled.json()["state"] == "cancelled"


# --- frontend -------------------------------------------------------------------


def test_frontend_voices_view_offline_single_player() -> None:
    from ui import security

    index = Path(security.__file__).resolve().parent / "static" / "index.html"
    text = index.read_text(encoding="utf-8")
    assert "{{AULOUD_TOKEN}}" in text
    assert "http" not in text, "no CDN or external requests (offline)"
    assert ":root" in text
    for needle in (
        'aria-label="Voices"',
        'id="voices-table"',
        'id="voices-body"',
        'id="voices-meta"',
        'id="voices-status"',
        'id="voices-refresh"',
        'id="voices-regen-all"',
        "loadVoices",
        "regenVoice",
        "regenAllVoices",
        "pollJobDone",
        "Regenerate all",
        "engine",
    ):
        assert needle in text, needle
    # One player implementation: a single mount, one audio factory, one
    # audition entry point shared by the Cast table and the Voices grid.
    assert text.count('id="player-row"') == 1
    assert text.count('document.createElement("audio")') == 1
    assert text.count("async function auditionVoice") == 1
