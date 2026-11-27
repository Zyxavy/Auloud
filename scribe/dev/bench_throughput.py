"""Task 6 throughput harness: concurrent sessions, IO binding, provider options.

Run from ``scribe/`` with the venv Python (needs the Kokoro models and, for
GPU variants, a working CUDA provider)::

    .venv\\Scripts\\python.exe dev/bench_throughput.py --device cuda

Measures, all on the same fixed sentence list (varying lengths so a dynamic
shape genuinely changes):

  serial K=1        one session, sentences one at a time
  shared K=2        one session, two threads calling run concurrently
  sessions K=2      two sessions, one thread each
  iobinding K=1     one session through IOBinding

Tuned ORT CUDA provider options were also measured here and dropped: they are
neutral-or-slower for Kokoro-82M (Task 6 report). Prints wall seconds and RTF
per variant; ``--json`` writes the same numbers. This is a dev tool, not a
test: the model is stochastic run-to-run, so it measures speed, never
bit-equality (see tests/test_throughput.py for that).
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

VOICE = "af_heart"
SPEED = 1.0

# Lengths from ~3 to ~40 words so input_ids shape varies between calls.
SENTENCES = [
    "Yes.",
    "The quiet alder fox crosses the mossy hill at dawn.",
    "She said nothing for a long moment, and the silence between them grew heavier than any answer she could have given him then.",
    "Run.",
    "Beyond the last fence the field opened into a wide grey pasture where the mist still hung in ribbons above the wet grass.",
    "What do you mean by that?",
    "Every town keeps at least one secret it would rather the road forget, and this one had kept three.",
    "Listen.",
    "He counted the coins twice, slid them across the worn oak, and waited for the innkeeper to look up from the ledger.",
    "No.",
    "The lantern guttered once, twice, then settled into a steady yellow glow that made the maps on the wall look older than they were.",
    "Come inside before the rain finds you.",
    "A patient man learns that most questions answer themselves if you leave them alone long enough.",
    "Here.",
    "They walked until the path gave out, then followed the stream, and followed it still when the stream became a trickle.",
    "Is that all?",
]


def _build_engine(device: str, iobinding: bool = False):
    from device import create_engine_with_device

    return create_engine_with_device("models", device=device, iobinding=iobinding)


def _synth_all(engine, sentences):
    return [engine.synth(text, VOICE, SPEED) for text in sentences]


def _time(fn) -> float:
    start = time.perf_counter()
    fn()
    return time.perf_counter() - start


def _audio_seconds(audios) -> float:
    return sum(len(a) for a in audios) / 24000.0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--json", default=None)
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args(argv)

    sentences = SENTENCES * args.repeat
    results: dict[str, dict[str, float]] = {}

    # Warm the GPU so the first run does not pay the kernel/cudnn setup cost.
    warm = _build_engine(args.device)
    warm.synth("Warm up the kernels.", VOICE, SPEED)
    del warm

    serial_engine = _build_engine(args.device)
    audios = _synth_all(serial_engine, sentences)
    results["serial_k1"] = {
        "wall_s": _time(lambda: _synth_all(serial_engine, sentences)),
        "audio_s": _audio_seconds(audios),
    }

    iob_engine = _build_engine(args.device, iobinding=True)
    iob_engine.synth(sentences[0], VOICE, SPEED)
    results["iobinding_k1"] = {
        "wall_s": _time(lambda: _synth_all(iob_engine, sentences)),
        "audio_s": _audio_seconds(audios),
    }

    def _shared():
        with ThreadPoolExecutor(max_workers=2) as pool:
            return [f.result() for f in [pool.submit(serial_engine.synth, t, VOICE, SPEED) for t in sentences]]

    results["shared_k2"] = {
        "wall_s": _time(_shared),
        "audio_s": _audio_seconds(audios),
    }

    session2 = _build_engine(args.device)
    session2.synth(sentences[0], VOICE, SPEED)

    def _two_sessions():
        engines = [serial_engine, session2]
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [
                pool.submit(engines[i % 2].synth, text, VOICE, SPEED)
                for i, text in enumerate(sentences)
            ]
            return [f.result() for f in futures]

    results["sessions_k2"] = {
        "wall_s": _time(_two_sessions),
        "audio_s": _audio_seconds(audios),
    }

    print(f"device={args.device}  sentences={len(sentences)}")
    header = f"{'variant':<16}{'wall_s':>10}{'audio_s':>10}{'RTF':>8}"
    print(header)
    print("-" * len(header))
    for name, row in results.items():
        rtf = row["audio_s"] / row["wall_s"] if row["wall_s"] else 0.0
        row["rtf"] = rtf
        print(f"{name:<16}{row['wall_s']:>10.2f}{row['audio_s']:>10.1f}{rtf:>7.2f}x")

    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"\nreport written: {args.json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
