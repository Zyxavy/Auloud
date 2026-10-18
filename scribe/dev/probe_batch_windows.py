"""Throwaway: does one long create_timed call beat per-sentence synth calls?

Run from ``scribe/``::

    uv run --no-sync python dev/probe_batch_windows.py [work_dir]

For the same 8 real sentences it compares, on the CUDA path:

- A: 8 separate ``engine.synth`` calls (today's per-sentence path)
- B: one ``create_timed`` call over the joined text (windows of <=510 phonemes)
- C: like B but ``continuous=True`` (overlapping windows, docstring says ~1.4x work)

and reports wall time, the number of ``session.run`` calls, and audio seconds.
Writes nothing; touches no cache.
"""

from __future__ import annotations

import json
import sys
import threading
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import kokoro_onnx  # noqa: E402

VOICE = "am_onyx"
CHAPTERS = (97, 101)
COUNT = 8

_runs = 0
_lock = threading.Lock()
_orig_infer = kokoro_onnx.Kokoro._infer


def _counting_infer(self, *args, **kwargs):
    global _runs
    try:
        return _orig_infer(self, *args, **kwargs)
    finally:
        with _lock:
            _runs += 1


kokoro_onnx.Kokoro._infer = _counting_infer


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
    return picked[:COUNT]


def main() -> int:
    global _runs
    if len(sys.argv) < 2:
        raise SystemExit(f"usage: {Path(__file__).name} <work-dir>")
    work_dir = Path(sys.argv[1])
    from build import create_engine

    engine = create_engine("models", device="cuda")
    backend = engine._kokoro
    texts = load_sentences(work_dir)
    joined = " ".join(texts)
    print(f"sentences: {len(texts)}  chars: {sum(map(len, texts))}  voice: {VOICE}")
    try:
        print(
            f"has_timings: {backend.has_timings}  "
            f"outputs: {[o.name for o in backend.sess.get_outputs()]}"
        )
    except Exception as exc:  # noqa: BLE001
        print(f"timings probe failed: {type(exc).__name__}: {exc}")

    cases = (
        ("A per-sentence x8", lambda: [engine.synth(t, VOICE, 1.0) for t in texts]),
        ("B one create_timed", lambda: backend.create_timed(joined, VOICE, 1.0)),
        (
            "C create_timed cont",
            lambda: backend.create_timed(joined, VOICE, 1.0, continuous=True),
        ),
    )

    print()
    header = f"{'case':>20} {'wall_s':>7} {'runs':>5} {'audio_s':>8} {'ms/sent':>8}"
    print(header)
    print("-" * len(header))
    for label, run in cases:
        _runs = 0
        start = time.perf_counter()
        try:
            result = run()
        except Exception as exc:  # noqa: BLE001
            print(f"{label:>20} FAILED: {type(exc).__name__}: {exc}")
            continue
        wall = time.perf_counter() - start
        if isinstance(result, list):
            audio_s = sum(a.size for a in result) / 24000
        else:
            audio_s = np.asarray(result[0], dtype=np.float32).size / 24000
        print(
            f"{label:>20} {wall:>7.2f} {_runs:>5} {audio_s:>8.2f} "
            f"{1000 * wall / len(texts):>8.1f}"
        )

    print()
    print("runs = session.run calls, ms/sent = wall milliseconds per sentence.")
    print("A carries no sentence pauses (assemble adds them); B and C insert them.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
