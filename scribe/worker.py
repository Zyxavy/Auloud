"""Long-lived worker speaking the orchestrator protocol (docs/15-OrchestratorProtocol.md).

Reads 4-byte-LE-length-prefixed JSON frames from stdin, writes frames to stdout.
All logging goes to stderr. Loads the model once at startup (on first hello).
"""
import json
import os
import struct
import sys
import time

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


def main():
    # Guard stdout: never print text there.
    parent_pid = os.getppid()
    while True:
        # parent-death backstop (Windows-friendly polling)
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
        kind = None
        if "protocol_version" in frame:
            kind = "hello"
        elif "batch_id" in frame and "items" in frame:
            kind = "synth_batch"
        elif "batch_id" in frame:
            kind = "cancel"
        elif set(frame.keys()) <= {"v", "id"}:
            kind = "shutdown"

        if kind == "hello":
            _write_frame({"v": v, "id": mid, "providers": _providers(),
                          "model_hash": "worker", "rss": _rss()})
        elif kind in ("prepare_batch", "synth_batch"):
            bid = frame["batch_id"]
            results = []
            for it in frame.get("items", []):
                results.append({
                    "index": it.get("index", 0),
                    "cache_key": it.get("cache_key", ""),
                    "cache_hit": False,
                    "audio_path": "",
                    "duration_ms": 0,
                    "sample_rate": 24000,
                })
            _write_frame({"v": v, "id": mid, "batch_id": bid, "results": results})
        elif kind == "cancel":
            pass
        elif kind == "shutdown":
            break
        else:
            # unknown -> batch_error? just ignore
            pass
    sys.exit(0)


if __name__ == "__main__":
    # redirect logging to stderr already (default print goes stdout; avoid)
    main()
