"""Long-lived worker speaking the orchestrator protocol (docs/15-OrchestratorProtocol.md).

Reads 4-byte-LE-length-prefixed JSON frames from stdin, writes frames to
stdout. All logging goes to stderr. Loads the model once at startup and
fills the same sentence cache Python reads (`tts.cache`), so the handoff
back to Python is the cache itself and no audio crosses the pipe.
"""
import argparse
import ctypes
import json
import struct
import sys

PROTOCOL_VERSION = 1
MAX_FRAME = 16 * 1024 * 1024

#: Real stdout, captured before ``main`` redirects sys.stdout to stderr.
_REAL_STDOUT = None


def _read_frame():
    """One decoded frame, or ``None`` on EOF."""
    hdr = sys.stdin.buffer.read(4)
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
    _REAL_STDOUT.write(struct.pack("<I", len(raw)))
    _REAL_STDOUT.write(raw)
    _REAL_STDOUT.flush()


def _rss_mb():
    """Working set of this process in MB (0 when unavailable)."""

    class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
        _fields_ = [
            ("cb", ctypes.c_ulong),
            ("PageFaultCount", ctypes.c_ulong),
            ("PeakWorkingSetSize", ctypes.c_size_t),
            ("WorkingSetSize", ctypes.c_size_t),
            ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPagedPoolUsage", ctypes.c_size_t),
            ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
            ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
            ("PagefileUsage", ctypes.c_size_t),
            ("PeakPagefileUsage", ctypes.c_size_t),
        ]

    try:
        kernel32 = ctypes.windll.kernel32
        # Explicit prototypes: without them GetCurrentProcess's handle is
        # truncated to 32 bits and the query silently returns 0.
        kernel32.GetCurrentProcess.restype = ctypes.c_void_p
        kernel32.K32GetProcessMemoryInfo.argtypes = [
            ctypes.c_void_p,
            ctypes.POINTER(PROCESS_MEMORY_COUNTERS),
            ctypes.c_ulong,
        ]
        counters = PROCESS_MEMORY_COUNTERS()
        counters.cb = ctypes.sizeof(PROCESS_MEMORY_COUNTERS)
        if kernel32.K32GetProcessMemoryInfo(
            kernel32.GetCurrentProcess(), ctypes.byref(counters), counters.cb
        ):
            return counters.WorkingSetSize // (1024 * 1024)
    except Exception:
        pass
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
    if "batch_id" in frame:
        return "synth_batch"
    if frame.get("type") == "shutdown":
        return "shutdown"
    return "unknown"


def _synth_batch(engine, cache_dir, frame):
    """``(results, error)`` for one synth_batch frame; error aborts the batch."""
    from bundle.writer import VOICE_PITCH
    from tts.cache import CacheStats, cache_key, cache_path, get_or_synth

    results = []
    for item in frame.get("items", []):
        text = item.get("text", "")
        voice = item.get("voice", "")
        speed = float(item.get("speed", 1.0))
        stats = CacheStats()
        try:
            audio = get_or_synth(
                engine, text, voice, speed, VOICE_PITCH, cache_dir, stats=stats
            )
        except Exception as exc:
            return [], str(exc)
        key = cache_key(text, voice, speed, VOICE_PITCH, engine.engine_version)
        path = cache_path(cache_dir, key)
        results.append(
            {
                "index": item.get("index", 0),
                "cache_key": key,
                "cache_hit": stats.hits > 0 and stats.misses == 0,
                # Split long sentences cache one entry per clause, so the
                # full-text path may not exist; the audio is still correct.
                "audio_path": str(path) if path.is_file() else "",
                "duration_ms": int(len(audio) * 1000 / engine.sample_rate),
                "sample_rate": engine.sample_rate,
            }
        )
    return results, None


def main(argv=None):
    global _REAL_STDOUT

    # Guard the protocol stream: everything else prints to stderr.
    _REAL_STDOUT = sys.stdout.buffer
    sys.stdout = sys.stderr

    parser = argparse.ArgumentParser(description="scribe orchestrator worker")
    parser.add_argument("--models-dir", default="models")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--cache-dir", default=".cache")
    args = parser.parse_args(argv)

    from tts.engine import create_engine

    try:
        engine = create_engine(models_dir=args.models_dir, device=args.device)
    except Exception as exc:
        # Never claim ready with no engine: the orchestrator would count
        # every sentence as done and the build would silently render nothing.
        print(f"worker: cannot load engine: {exc}", file=sys.stderr, flush=True)
        return 1

    while True:
        frame = _read_frame()
        if frame is None:
            break
        v = frame.get("v", 1)
        mid = frame.get("id", "")
        kind = _classify(frame)

        if kind == "hello":
            if frame.get("protocol_version") != PROTOCOL_VERSION:
                print(
                    f"worker: protocol mismatch: expected {PROTOCOL_VERSION}, "
                    f"got {frame.get('protocol_version')}",
                    file=sys.stderr,
                    flush=True,
                )
                return 1
            _write_frame(
                {
                    "v": v,
                    "id": mid,
                    "type": "ready",
                    "providers": _providers(),
                    "model_hash": engine.engine_version,
                    "rss_mb": _rss_mb(),
                }
            )
        elif kind == "synth_batch":
            batch_id = frame["batch_id"]
            results, error = _synth_batch(engine, args.cache_dir, frame)
            if error is not None:
                _write_frame(
                    {
                        "v": v,
                        "id": mid,
                        "type": "batch_error",
                        "batch_id": batch_id,
                        "kind": "synth",
                        "message": error,
                        "retryable": True,
                    }
                )
            else:
                _write_frame(
                    {
                        "v": v,
                        "id": mid,
                        "type": "batch_done",
                        "batch_id": batch_id,
                        "results": results,
                    }
                )
        elif kind == "shutdown":
            break
        else:
            _write_frame(
                {
                    "v": v,
                    "id": mid,
                    "type": "batch_error",
                    "batch_id": "",
                    "kind": "protocol",
                    "message": f"unknown message: {sorted(frame)}",
                    "retryable": False,
                }
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())
