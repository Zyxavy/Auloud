"""Long-lived worker speaking the orchestrator protocol (docs/15-OrchestratorProtocol.md).

Reads 4-byte-LE-length-prefixed JSON frames from stdin, writes frames to stdout.
All logging goes to stderr. Loads the model once at startup (on first hello).
"""
import ctypes
import json
import os
import struct
import sys
import time

PROTOCOL_VERSION = 1
MAX_FRAME = 16 * 1024 * 1024


def _read_frame():
    hdr = sys.stdin.buffer.read(4)
    if not hdr:
        return None
    if len(hdr) < 4:
        return None
    (n,) = struct.unpack("<I", hdr)
    if n == 0 or n > MAX_FRAME:
        raise ValueError(f"bad frame size {n}")
    data = sys.stdin.buffer.read(n)
    if len(data) < n:
        return None
    return json.loads(data.decode("utf-8"))


def _write_frame(obj):
    raw = json.dumps(obj).encode("utf-8")
    sys.stdout.buffer.write(struct.pack("<I", len(raw)))
    sys.stdout.buffer.write(raw)
    sys.stdout.buffer.flush()


def _rss():
    """Peak RSS of this process in KB (Windows-compatible)."""
    try:
        # Windows: use GetProcessMemoryInfo via psapi
        import ctypes
        from ctypes import wintypes

        class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
            _fields_ = [
                ("cb", wintypes.DWORD),
                ("PageFaultCount", wintypes.DWORD),
                ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t),
                ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t),
            ]

        pmc = PROCESS_MEMORY_COUNTERS()
        pmc.cb = ctypes.sizeof(PROCESS_MEMORY_COUNTERS)
        handle = ctypes.windll.kernel32.GetCurrentProcess()
        if ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(pmc), pmc.cb):
            return pmc.WorkingSetSize // 1024
    except Exception:
        pass
    # Unix fallback
    try:
        import resource
        return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    except Exception:
        return 0


def _providers():
    try:
        import onnxruntime as ort
        return list(ort.get_available_providers())
    except Exception:
        return ["CPUExecutionProvider"]


def _classify(frame):
    if "protocol_version" in frame:
        return "hello"
    if "batch_id" in frame and "items" in frame:
        return "synth_batch"
    if "batch_id" in frame:
        return "cancel"
    if set(frame.keys()) <= {"v", "id"}:
        return "shutdown"
    return "unknown"


def main(argv=None):
    import argparse

    # Guard stdout: redirect all text output to stderr so protocol stays clean.
    real_stdout = sys.stdout.buffer
    sys.stdout = sys.stderr

    parser = argparse.ArgumentParser(description="scribe worker (hidden)")
    parser.add_argument("--models-dir", default="models")
    parser.add_argument("--device", default="auto")
    args = parser.parse_args()

    # Load engine once.
    engine = None
    try:
        from tts.engine import create_engine
        engine = create_engine(models_dir=args.models_dir, device=args.device)
    except Exception as exc:
        print(f"worker: failed to load engine: {exc}", file=sys.stderr, flush=True)

    parent_pid = os.getppid()
    while True:
        # parent-death backstop
        try:
            if os.getppid() != parent_pid:
                break
        except Exception:
            pass
        frame = _read_frame()
        if frame is None:
            break  # stdin EOF
        v = frame.get("v", 1)
        mid = frame.get("id", "")
        kind = _classify(frame)

        if kind == "hello":
            pv = frame.get("protocol_version", 0)
            if pv != 0 and pv != PROTOCOL_VERSION:
                _write_frame({"v": v, "id": mid, "error": "protocol version mismatch",
                              "expected": PROTOCOL_VERSION, "got": pv})
                continue
            _write_frame({"v": v, "id": mid, "protocol_version": PROTOCOL_VERSION,
                          "providers": _providers(), "model_hash": "worker",
                          "rss": _rss()})
        elif kind in ("prepare_batch", "synth_batch"):
            bid = frame["batch_id"]
            results = []
            for it in frame.get("items", []):
                idx = it.get("index", 0)
                text = it.get("text", "")
                voice = it.get("voice", "am_adam")
                speed = float(it.get("speed", 1.0))
                cache_key = it.get("cache_key", "")
                audio_path = ""
                duration_ms = 0
                cache_hit = False
                try:
                    if engine is not None:
                        audio = engine.synth(text, voice, speed)
                        # Write to cache (placeholder path)
                        audio_path = f"cache/{cache_key}.wav"
                        duration_ms = int(len(audio) / 24000 * 1000) if hasattr(audio, "__len__") else 0
                except Exception as exc:
                    _write_frame({"v": v, "id": mid, "batch_id": bid, "error": str(exc), "retryable": True})
                    continue
                results.append({
                    "index": idx,
                    "cache_key": cache_key,
                    "cache_hit": cache_hit,
                    "audio_path": audio_path,
                    "duration_ms": duration_ms,
                    "sample_rate": 24000,
                })
            _write_frame({"v": v, "id": mid, "batch_id": bid, "results": results})
        elif kind == "cancel":
            pass
        elif kind == "shutdown":
            break
        else:
            _write_frame({"v": v, "id": mid, "error": f"unknown message kind: {kind}"})
    sys.exit(0)


if __name__ == "__main__":
    main()