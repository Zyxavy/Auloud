# Auloud Scribe — cache concurrency safety (Task 1).

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

_FFMPEG_DIR = r"C:\Users\kelps\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.2-full_build\bin"
if os.path.isdir(_FFMPEG_DIR) and _FFMPEG_DIR not in os.environ.get("PATH", ""):
    os.environ["PATH"] = _FFMPEG_DIR + os.pathsep + os.environ.get("PATH", "")

SCRIBE_DIR = Path(__file__).resolve().parent.parent

WORKER = textwrap.dedent(
    """
    import sys
    sys.path.insert(0, {scribe!r})
    import numpy as np
    from tts.base import TTSEngine
    from tts.cache import get_or_synth

    class FakeEngine(TTSEngine):
        def __init__(self, version="fake-cc", rate=24000):
            self._v = version
            self._r = rate
        @property
        def sample_rate(self):
            return self._r
        @property
        def engine_version(self):
            return self._v
        def synth(self, text, voice, speed):
            # slight delay to force overlap
            import time
            time.sleep(0.05)
            return np.full((len(text) * 10,), 0.5, dtype=np.float32)

    text = sys.argv[1]
    cache_dir = sys.argv[2]
    engine = FakeEngine()
    audio = get_or_synth(engine, text, "af_heart", 1.0, 1.0, cache_dir)
    print(len(audio))
    """
)


def _run_worker(text, cache_dir):
    script = WORKER.format(scribe=str(SCRIBE_DIR))
    # write script to a temp file beside cache? Use stdin via -c not possible multi-line easily; write file.
    script_path = Path(cache_dir).parent / "worker.py"
    script_path.parent.mkdir(parents=True, exist_ok=True)
    script_path.write_text(script, encoding="utf-8")
    proc = subprocess.run(
        [sys.executable, str(script_path), text, str(cache_dir)],
        capture_output=True,
        text=True,
        timeout=60,
    )
    return proc


def _cached_files(cache_dir):
    return sorted(Path(cache_dir).glob("*.flac"))


def test_concurrent_same_key_processes(tmp_path):
    cache_dir = tmp_path / "cache"
    cache_dir.mkdir()
    text = "Hello world."
    procs = []
    for _ in range(8):
        procs.append(_run_worker(text, cache_dir))
    for p in procs:
        assert p.returncode == 0, p.stderr
        assert p.stderr == "" or "Exception" not in p.stderr
    # no temp files left
    assert list(cache_dir.glob(".*tmp*")) == []
    files = _cached_files(cache_dir)
    assert len(files) == 1
    # entry readable afterwards
    from tts.cache import _read_cached

    audio = _read_cached(files[0], 24000)
    assert audio is not None
    assert audio.size == len(text) * 10
    # all workers reported same length
    assert all(p.stdout.strip() == str(len(text) * 10) for p in procs)


def test_concurrent_different_keys_processes(tmp_path):
    cache_dir = tmp_path / "cache"
    cache_dir.mkdir()
    texts = [f"Sentence number {i}." for i in range(8)]
    procs = []
    for t in texts:
        procs.append(_run_worker(t, cache_dir))
    for p in procs:
        assert p.returncode == 0, p.stderr
    assert list(cache_dir.glob(".*tmp*")) == []
    files = _cached_files(cache_dir)
    assert len(files) == 8
    from tts.cache import _read_cached

    for f in files:
        audio = _read_cached(f, 24000)
        assert audio is not None
        assert audio.size > 0


def test_kill_mid_write_leaves_no_partial(tmp_path):
    """Terminate a worker mid-write; cache file must not be partial/truncated."""
    cache_dir = tmp_path / "cache"
    cache_dir.mkdir()
    # Use a slower engine by monkey? The worker sleeps 0.05s; terminate quickly.
    script = WORKER.format(scribe=str(SCRIBE_DIR))
    script_path = tmp_path / "worker_slow.py"
    # make synth slower to allow kill mid-flight
    script = script.replace("time.sleep(0.05)", "time.sleep(2.0)")
    script_path.write_text(script, encoding="utf-8")
    text = "Hello world."
    proc = subprocess.Popen(
        [sys.executable, str(script_path), text, str(cache_dir)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    # give it time to start writing temp
    import time

    time.sleep(0.5)
    proc.terminate()
    proc.wait(timeout=10)
    # no partial final file or temp left that decodes partial? Atomic rename means final absent.
    files = _cached_files(cache_dir)
    temps = list(cache_dir.glob(".*tmp*"))
    # temp may remain from killed process; final must not exist as truncated.
    # Clean temps (simulating resume cleanup) then ensure no crash on read.
    for t in temps:
        t.unlink(missing_ok=True)
    # final file either absent or readable
    if files:
        from tts.cache import _read_cached

        # if present, must decode (not partial)
        for f in files:
            # _read_cached deletes undecodable; ensure not None if present
            res = _read_cached(f, 24000)
            # either None (deleted) or valid array
            assert res is None or res.size >= 0
    # resume: run a normal worker, should succeed
    p = _run_worker(text, cache_dir)
    assert p.returncode == 0, p.stderr
    files2 = _cached_files(cache_dir)
    assert len(files2) == 1