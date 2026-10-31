"""Baseline profiling harness for the scribe render pipeline (Task 0).

Run from ``scribe/``::

    uv run --no-sync python dev/bench_render.py tests/fixtures/bench_chapter.epub \\
        --device auto --out-dir ../scratch/bench-out

Instruments per-stage wall time with ``time.perf_counter()``, checks which
ONNX execution provider actually runs, samples CPU/GPU utilisation and peak
RSS, and prints a table of seconds and % of total. Writes a JSON summary
to ``--report`` (default ``bench_report.json`` next to the output dir).

Stages timed:
  draft       ensure_script (sentence split + extraction)
  phonemize   engine.synth minus session.run (kokoro-onnx glue)
  inference   session.run (monkeypatched Kokoro._infer)
  assemble    audio.assemble.assemble_chapter (concat + timings + leveling)
  encode      audio.encode.encode_assembled_chapter (PCM -> MP3 via ffmpeg)
  cache_read  tts.cache._read_cached
  cache_write tts.cache._store_cached
"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# ---------------------------------------------------------------------------
# Monkeypatches (must happen before `build` is imported, so its module-level
# `from ... import` names are rebound afterwards).
# ---------------------------------------------------------------------------

STAGES = ("draft", "phonemize", "inference", "assemble", "encode",
          "cache_read", "cache_write", "synth_total")
times = {s: 0.0 for s in STAGES}
counts = {s: 0 for s in STAGES}

BENCH_SESSION_INPUTS = None
ORT_PROFILE_PREFIX = "bench_ort_profile"


def _patch():
    import onnxruntime as _rt
    _OrigSession = _rt.InferenceSession
    _orig_run = _OrigSession.run

    def _timed_run(self, *a, **k):
        t = time.perf_counter()
        try:
            return _orig_run(self, *a, **k)
        finally:
            times["inference"] += time.perf_counter() - t
            counts["inference"] += 1

    _OrigSession.run = _timed_run

    def _profiled_inference_session(*a, **k):
        if not k.get("sess_options"):
            so = _rt.SessionOptions()
            so.enable_profiling = True
            so.profile_file_prefix = ORT_PROFILE_PREFIX
            k["sess_options"] = so
        sess = _OrigSession(*a, **k)
        global BENCH_SESSION_INPUTS
        try:
            BENCH_SESSION_INPUTS = [(i.name, list(i.shape), str(i.type)) for i in sess.get_inputs()]
        except Exception as exc:
            BENCH_SESSION_INPUTS = "unavailable: %s" % exc
        return sess

    _rt.InferenceSession = _profiled_inference_session

    import kokoro_onnx
    import audio.assemble
    import audio.encode
    import tts.cache
    import build as build_mod

    # inference: session.run
    _orig_infer = kokoro_onnx.Kokoro._infer

    def _timed_infer(self, *a, **k):
        t = time.perf_counter()
        try:
            return _orig_infer(self, *a, **k)
        finally:
            times["inference"] += time.perf_counter() - t
            counts["inference"] += 1

    kokoro_onnx.Kokoro._infer = _timed_infer

    # assembly
    _orig_assemble = audio.assemble.assemble_chapter

    def _timed_assemble(*a, **k):
        t = time.perf_counter()
        r = _orig_assemble(*a, **k)
        times["assemble"] += time.perf_counter() - t
        counts["assemble"] += 1
        return r

    audio.assemble.assemble_chapter = _timed_assemble
    build_mod.assemble_chapter = _timed_assemble

    # encode
    _orig_encode = audio.encode.encode_assembled_chapter

    def _timed_encode(*a, **k):
        t = time.perf_counter()
        r = _orig_encode(*a, **k)
        times["encode"] += time.perf_counter() - t
        counts["encode"] += 1
        return r

    audio.encode.encode_assembled_chapter = _timed_encode
    build_mod.encode_assembled_chapter = _timed_encode

    # cache read/write
    _orig_read = tts.cache._read_cached
    _orig_store = tts.cache._store_cached

    def _timed_read(*a, **k):
        t = time.perf_counter()
        r = _orig_read(*a, **k)
        times["cache_read"] += time.perf_counter() - t
        counts["cache_read"] += 1
        return r

    def _timed_store(*a, **k):
        t = time.perf_counter()
        r = _orig_store(*a, **k)
        times["cache_write"] += time.perf_counter() - t
        counts["cache_write"] += 1
        return r

    tts.cache._read_cached = _timed_read
    tts.cache._store_cached = _timed_store

    # engine synth total (phonemize + inference glue)
    from tts.kokoro import KokoroEngine

    _orig_synth = KokoroEngine.synth

    def _timed_synth(self, *a, **k):
        t = time.perf_counter()
        r = _orig_synth(self, *a, **k)
        times["synth_total"] += time.perf_counter() - t
        counts["synth_total"] += 1
        return r

    KokoroEngine.synth = _timed_synth

    # draft timing handled in main via direct call? run_build calls ensure_script
    # internally; patch build.ensure_script to time it.
    _orig_ensure = build_mod.ensure_script

    def _timed_ensure(*a, **k):
        t = time.perf_counter()
        r = _orig_ensure(*a, **k)
        times["draft"] += time.perf_counter() - t
        counts["draft"] += 1
        return r

    build_mod.ensure_script = _timed_ensure

    return build_mod


build_mod = _patch()
from build import run_build, create_engine  # noqa: E402


# ---------------------------------------------------------------------------
# RSS helper (ctypes, no psutil dependency)
# ---------------------------------------------------------------------------

class _PMC(ctypes.Structure):
    _fields_ = [
        ("cb", ctypes.c_uint32),
        ("PageFaultCount", ctypes.c_uint32),
        ("PeakWorkingSetSize", ctypes.c_size_t),
        ("WorkingSetSize", ctypes.c_size_t),
        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
        ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
        ("PagefileUsage", ctypes.c_size_t),
        ("PeakPagefileUsage", ctypes.c_size_t),
    ]


def _rss_mb():
    pmc = _PMC()
    pmc.cb = ctypes.sizeof(pmc)
    try:
        pid = ctypes.windll.kernel32.GetCurrentProcessId()
        h = ctypes.windll.kernel32.OpenProcess(0x0410, False, pid)
        ctypes.windll.kernel32.K32GetProcessMemoryInfo(h, ctypes.byref(pmc), pmc.cb)
        ctypes.windll.kernel32.CloseHandle(h)
        return pmc.WorkingSetSize / (1024 * 1024), pmc.PeakWorkingSetSize / (1024 * 1024)
    except Exception:
        return 0.0, 0.0


# ---------------------------------------------------------------------------
# Sampler thread
# ---------------------------------------------------------------------------

class Sampler(threading.Thread):
    def __init__(self, interval: float = 1.0):
        super().__init__(daemon=True)
        self.interval = interval
        self.stop_event = threading.Event()
        self.cpu_samples = []
        self.gpu_samples = []
        self.rss_samples = []
        self.peak_rss = 0.0
        self._nvidia_proc = None

    def run(self):
        # start nvidia-smi dmon to a temp file
        tmp = Path(os.environ.get("TEMP", ".")) / "bench_dmon.txt"
        try:
            self._nvidia_proc = subprocess.Popen(
                ["nvidia-smi", "dmon", "-s", "u", "-d", "1"],
                stdout=open(tmp, "w"),
                stderr=subprocess.DEVNULL,
            )
        except Exception:
            self._nvidia_proc = None
        while not self.stop_event.wait(self.interval):
            rss, peak = _rss_mb()
            self.rss_samples.append(rss)
            if peak > self.peak_rss:
                self.peak_rss = peak
            # cpu percent via powershell (best effort, cheap enough at 1s)
            try:
                out = subprocess.run(
                    ["powershell", "-NoProfile", "-Command",
                     "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average"],
                    capture_output=True, text=True, timeout=5,
                )
                val = float(out.stdout.strip())
                self.cpu_samples.append(val)
            except Exception:
                pass
            # gpu util
            try:
                out = subprocess.run(
                    ["nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"],
                    capture_output=True, text=True, timeout=5,
                )
                val = float(out.stdout.strip().splitlines()[0])
                self.gpu_samples.append(val)
            except Exception:
                pass
        if self._nvidia_proc is not None:
            try:
                self._nvidia_proc.terminate()
            except Exception:
                pass

    def stop(self):
        self.stop_event.set()
        self.join(timeout=5)


def _avg(xs):
    return (sum(xs) / len(xs)) if xs else 0.0


def _max(xs):
    return max(xs) if xs else 0.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", help="EPUB/PDF source to build")
    parser.add_argument("--work-root", default=".scribe")
    parser.add_argument("--out-dir", default=None)
    parser.add_argument("--chapters", default=None)
    parser.add_argument("--device", default="auto")
    parser.add_argument("--report", default="bench_report.json")
    args = parser.parse_args()

    source = Path(args.source)
    out_dir = Path(args.out_dir) if args.out_dir else None

    sampler = Sampler(interval=1.0)
    sampler.start()
    wall_start = time.perf_counter()
    try:
        result = run_build(
            source,
            work_root=args.work_root,
            out_dir=out_dir,
            device=args.device,
            chapters=args.chapters,
            show_progress=False,
        )
    finally:
        sampler.stop()
    wall = time.perf_counter() - wall_start

    # phonemize = synth_total - inference
    times["phonemize"] = max(0.0, times["synth_total"] - times["inference"])
    # concat/timing assembly = assemble_chapter wall minus the synthesis and
    # cache work that happens inside its synth_fn pass (non-overlapping).
    times["concat"] = max(
        0.0,
        times["assemble"]
        - (times["phonemize"] + times["inference"])
        - times["cache_read"]
        - times["cache_write"],
    )
    counts["concat"] = counts["assemble"]

    # provider check
    try:
        eng = create_engine("models", device=args.device)
        providers = list(eng._kokoro.sess.get_providers()) if hasattr(eng._kokoro, "sess") else "n/a"
    except Exception as exc:
        providers = f"error: {exc}"
    session_inputs = BENCH_SESSION_INPUTS

    stage_list = ("draft", "phonemize", "inference", "cache_read", "cache_write", "concat", "encode")
    total_stage = sum(times[s] for s in stage_list)
    if total_stage <= 0:
        total_stage = wall

    print()
    print(f"wall time: {wall:.2f}s   audio: {result.total_audio_ms/1000:.1f}s   RTF: {result.rtf:.2f}x")
    print(f"providers: {providers}")
    print(f"session inputs: {session_inputs}")
    print()
    header = f"{'stage':<14}{'seconds':>10}{'%total':>9}{'calls':>8}"
    print(header)
    print("-" * len(header))
    for s in stage_list:
        pct = 100.0 * times[s] / total_stage if total_stage else 0.0
        print(f"{s:<14}{times[s]:>10.3f}{pct:>8.1f}%{counts.get(s,0):>8}")
    print(f"{'TOTAL(stages)':<14}{total_stage:>10.3f}{'100.0':>8}%")
    print()
    print(f"CPU util avg: {_avg(sampler.cpu_samples):.1f}%  max: {_max(sampler.cpu_samples):.1f}%")
    print(f"GPU util avg: {_avg(sampler.gpu_samples):.1f}%  max: {_max(sampler.gpu_samples):.1f}%")
    print(f"peak RSS (bench proc): {sampler.peak_rss:.1f} MB  last: {sampler.rss_samples[-1] if sampler.rss_samples else 0:.1f} MB")
    print(f"cache hits: {result.cache_hits}  misses: {result.cache_misses}")

    import glob, json as _json
    profile_files = sorted(glob.glob(ORT_PROFILE_PREFIX + "*"))
    op_totals = {}
    for pf in profile_files:
        try:
            data = _json.loads(Path(pf).read_text(encoding="utf-8"))
            if isinstance(data, list):
                for ev in data:
                    if isinstance(ev, dict) and "dur" in ev:
                        op_totals[ev.get("name", "?")] = op_totals.get(ev.get("name", "?"), 0) + ev["dur"]
        except Exception:
            pass
    top_ops = sorted(op_totals.items(), key=lambda x: -x[1])[:15]
    print()
    print("ORT profile files:", profile_files)
    print("Top ops (microseconds):")
    for name, dur in top_ops:
        print(f"  {name:<40} {dur/1e6:>8.3f}s")

    report = {
        "wall_s": wall,
        "audio_s": result.total_audio_ms / 1000.0,
        "rtf": result.rtf,
        "providers": providers,
        "session_inputs": session_inputs,
        "ort_profile_files": profile_files,
        "ort_top_ops_us": {n: d for n, d in top_ops},
        "stages_s": {s: times[s] for s in stage_list},
        "stage_calls": {s: counts.get(s, 0) for s in stage_list},
        "stage_pct": {s: (100.0 * times[s] / total_stage if total_stage else 0.0) for s in stage_list},
        "cpu_util_avg": _avg(sampler.cpu_samples),
        "cpu_util_max": _max(sampler.cpu_samples),
        "gpu_util_avg": _avg(sampler.gpu_samples),
        "gpu_util_max": _max(sampler.gpu_samples),
        "peak_rss_mb": sampler.peak_rss,
        "cache_hits": result.cache_hits,
        "cache_misses": result.cache_misses,
    }
    out_path = Path(args.report)
    out_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"\nreport written: {out_path}")


if __name__ == "__main__":
    main()
