"""Throwaway probe (plan RT1): does synth scale with threads, and where does time go?

Run from ``scribe/``::

    uv run --no-sync python dev/probe_render_scaling.py [work_dir]

Reads real sentences from a book's ``script.json``, synthesizes them with a
real engine at T in {1, 2, 4, 8, 12} threads, and reports wall time, the
inference (GPU burst) share, audio drift against T=1, and VRAM.
Writes nothing; deletes nothing; touches no cache.
"""

from __future__ import annotations

import json
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import kokoro_onnx  # noqa: E402

THREADS = (1, 2, 4, 8, 12)
SENTENCE_COUNT = 40
VOICE = "am_onyx"
CHAPTERS = (97, 101)
DEFAULT_WORK_DIR = Path(".scribe/937ed66f-4821-5c73-9636-eef370e16b9b")

_infer_times: list[float] = []
_lock = threading.Lock()
_orig_infer = kokoro_onnx.Kokoro._infer


def _timed_infer(self, *args, **kwargs):
    start = time.perf_counter()
    try:
        return _orig_infer(self, *args, **kwargs)
    finally:
        with _lock:
            _infer_times.append(time.perf_counter() - start)


kokoro_onnx.Kokoro._infer = _timed_infer


def vram_mb() -> int | None:
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            timeout=15,
            check=True,
        )
    except Exception:
        return None
    lines = [line for line in out.stdout.strip().splitlines() if line.strip()]
    return int(lines[0]) if lines else None


def load_sentences(work_dir: Path) -> list[str]:
    script = json.loads((work_dir / "script.json").read_text(encoding="utf-8"))
    chapters = script["chapters"] if isinstance(script, dict) else script
    picked: list[str] = []
    for chapter in chapters:
        index = chapter.get("chapter")
        if index is None or not (CHAPTERS[0] <= index < CHAPTERS[1]):
            continue
        for block in chapter["blocks"]:
            for sentence in block["sentences"]:
                text = (sentence.get("text") or "").strip()
                if 20 <= len(text) <= 300:
                    picked.append(text)
    if len(picked) < SENTENCE_COUNT:
        raise SystemExit(f"only {len(picked)} usable sentences in the range")
    step = len(picked) / SENTENCE_COUNT
    return [picked[int(i * step)] for i in range(SENTENCE_COUNT)]


def compare(results: list[np.ndarray], baseline: dict[int, np.ndarray]) -> tuple[int, float]:
    """Return (count of length mismatches, max absolute sample difference)."""
    lendiff = 0
    worst = 0.0
    for i, audio in enumerate(results):
        ref = baseline[i]
        if audio.shape != ref.shape:
            lendiff += 1
            continue
        if audio.size:
            worst = max(worst, float(np.max(np.abs(audio - ref))))
    return lendiff, worst


def main() -> int:
    work_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_WORK_DIR
    from build import create_engine

    engine = create_engine("models", device="cuda")
    print(f"providers: {engine._kokoro.sess.get_providers()}")
    texts = load_sentences(work_dir)
    print(f"sentences: {len(texts)}  voice: {VOICE}  chars: {sum(map(len, texts))}")

    header = (
        f"{'T':>3} {'wall_s':>8} {'s/sent':>7} {'speedup':>8} {'run_ms':>8} "
        f"{'other_ms':>9} {'len<>':>6} {'max_delta':>11} {'vramMB':>7}"
    )
    print()
    print(header)
    print("-" * len(header))

    baseline: dict[int, np.ndarray] = {}
    base_wall = 0.0
    for threads in THREADS:
        _infer_times.clear()
        start = time.perf_counter()
        if threads == 1:
            results = [engine.synth(text, VOICE, 1.0) for text in texts]
        else:
            with ThreadPoolExecutor(max_workers=threads) as pool:
                results = list(pool.map(lambda t: engine.synth(t, VOICE, 1.0), texts))
        wall = time.perf_counter() - start
        infer = list(_infer_times)

        if threads == 1 and not baseline:
            baseline = dict(enumerate(results))
            base_wall = wall
            lendiff, delta = 0, 0.0
            audio_s = sum(a.size for a in results) / 24000
            print(f"    (audio: {audio_s:.1f} s total, {audio_s / len(results):.2f} s/sent)")
        else:
            lendiff, delta = compare(results, baseline)

        count = len(texts)
        total_infer = sum(infer)
        print(
            f"{threads:>3} {wall:>8.2f} {count / wall:>7.2f} {base_wall / wall:>8.2f} "
            f"{1000 * total_infer / count:>8.1f} "
            f"{1000 * (wall - total_infer) / count:>9.1f} {lendiff:>6} {delta:>11.3e} "
            f"{str(vram_mb()):>7}"
        )

    # Drift detail: is the parallel drift the same shape as the path's own
    # run-to-run drift? Small subset so the extra passes stay cheap.
    print()
    print("drift detail (first 8 sentences, mean/p99 over all samples):")
    subset = texts[:8]
    serial_a = [engine.synth(text, VOICE, 1.0) for text in subset]
    serial_b = [engine.synth(text, VOICE, 1.0) for text in subset]
    with ThreadPoolExecutor(max_workers=8) as pool:
        parallel = list(pool.map(lambda t: engine.synth(t, VOICE, 1.0), subset))
    for label, other in (("serial vs serial", serial_b), ("T=8    vs serial", parallel)):
        drift = np.concatenate([np.abs(other[i] - serial_a[i]) for i in range(len(subset))])
        print(
            f"  {label}: mean={drift.mean():.3e} p99={np.quantile(drift, 0.99):.3e} "
            f"over1e-3={100 * (drift > 1e-3).mean():.3f}% "
            f"over1e-2={100 * (drift > 1e-2).mean():.3f}%"
        )
    print()
    print("run_ms = mean time inside session.run (queue-inclusive when T>1),")
    print("other_ms = the rest of wall time per sentence (phonemize + glue),")
    print("max_delta = largest |sample| drift against the first T=1 pass.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
