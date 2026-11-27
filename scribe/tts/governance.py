"""Resource governance for Scribe's TTS pipeline.

Memory guard, worker recommendation, and doctor helpers. Extracted from
``tts.engine`` so engine construction and resource policy stay separate.
"""

from __future__ import annotations

import os

try:
    import psutil as _psutil_mod
except Exception:
    _psutil_mod = None

psutil = _psutil_mod


def _available_memory_bytes():
    ps = globals().get("psutil")
    if ps is not None:
        try:
            return int(ps.virtual_memory().available)
        except Exception:
            pass
    try:
        if os.name == "nt":
            import ctypes as _ctypes

            class _MEMORYSTATUSEX(_ctypes.Structure):
                _fields_ = [
                    ("dwLength", _ctypes.c_ulong),
                    ("dwMemoryLoad", _ctypes.c_ulong),
                    ("ullTotalPhys", _ctypes.c_ulonglong),
                    ("ullAvailPhys", _ctypes.c_ulonglong),
                    ("ullTotalPageFile", _ctypes.c_ulonglong),
                    ("ullAvailPageFile", _ctypes.c_ulonglong),
                    ("ullTotalVirtual", _ctypes.c_ulonglong),
                    ("ullAvailVirtual", _ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", _ctypes.c_ulonglong),
                ]

            stat = _MEMORYSTATUSEX()
            stat.dwLength = _ctypes.sizeof(_MEMORYSTATUSEX)
            if _ctypes.windll.kernel32.GlobalMemoryStatusEx(_ctypes.byref(stat)):
                return int(stat.ullAvailPhys)
    except Exception:
        pass
    env = os.environ.get("AULOUD_AVAILABLE_RAM_BYTES")
    if env:
        try:
            return int(env)
        except ValueError:
            pass
    return 8 * 1024 * 1024 * 1024


PREP_RSS_MB = 128.0
PREP_WORKER_RSS_MB = PREP_RSS_MB
PREP_RSS_PER_WORKER_MB = PREP_RSS_MB
PREP_RSS = PREP_RSS_MB

MODEL_RSS_MB = 1190.0
MODEL_WORKER_RSS_MB = MODEL_RSS_MB
MODEL_RSS_PER_WORKER_MB = MODEL_RSS_MB
MODEL_RSS = MODEL_RSS_MB

MEMORY_LIMIT_FRACTION = 0.7


def _resolve_rss(name_candidates, explicit):
    if explicit is not None:
        return float(explicit)
    for name in name_candidates:
        if name in globals():
            try:
                return float(globals()[name])
            except (TypeError, ValueError):
                continue
    return 0.0


def physical_cores():
    ps = globals().get("psutil")
    if ps is not None:
        try:
            c = ps.cpu_count(logical=False)
            if c:
                return int(c)
        except Exception:
            pass
    try:
        return int(os.cpu_count() or 1)
    except Exception:
        return 1


def memory_guard(
    prep_workers,
    inference_workers,
    *,
    prep_rss_mb=None,
    model_rss_mb=None,
    prep_rss=None,
    model_rss=None,
    available_bytes=None,
    available_mb=None,
    available_ram_bytes=None,
    limit_fraction=MEMORY_LIMIT_FRACTION,
    verbose=True,
    print_note=True,
    **_ignored,
):
    pw = int(prep_workers or 0)
    iw = int(inference_workers or 0)
    if pw < 0:
        pw = 0
    if iw < 0:
        iw = 0

    prep_val = _resolve_rss(
        ("prep_rss_mb", "PREP_RSS_MB", "PREP_WORKER_RSS_MB",
         "PREP_RSS_PER_WORKER_MB", "PREP_RSS", "prep_rss"),
        prep_rss_mb if prep_rss_mb is not None else prep_rss,
    )
    model_val = _resolve_rss(
        ("model_rss_mb", "MODEL_RSS_MB", "MODEL_WORKER_RSS_MB",
         "MODEL_RSS_PER_WORKER_MB", "MODEL_RSS", "model_rss"),
        model_rss_mb if model_rss_mb is not None else model_rss,
    )

    if available_bytes is None and available_mb is not None:
        available_bytes = int(available_mb) * 1024 * 1024
    if available_bytes is None and available_ram_bytes is not None:
        available_bytes = int(available_ram_bytes)
    if available_bytes is None:
        available_bytes = _available_memory_bytes()

    try:
        frac = float(limit_fraction)
    except (TypeError, ValueError):
        frac = MEMORY_LIMIT_FRACTION
    if frac <= 0 or frac > 1:
        frac = MEMORY_LIMIT_FRACTION

    limit = int(available_bytes * frac)

    def _estimate(p, k):
        return int(p * prep_val * 1024 * 1024 + k * model_val * 1024 * 1024)

    original = (pw, iw)
    est = _estimate(pw, iw)
    reduced = False
    while est > limit and (iw > 1 or pw > 0):
        if iw > 1:
            iw -= 1
        elif pw > 0:
            pw -= 1
        else:
            break
        est = _estimate(pw, iw)
        reduced = True
        if iw <= 1 and pw <= 0:
            break

    if reduced and (verbose or print_note):
        print(
            "memory guard: estimated " + str(est // (1024*1024)) + " MB exceeds " +
            str(int(frac*100)) + "% of available RAM " +
            "(" + str(available_bytes // (1024*1024)) + " MB); " +
            "reduced workers prep " + str(original[0]) + "->" + str(pw) + ", " +
            "inference " + str(original[1]) + "->" + str(iw),
            flush=True,
        )
    return pw, iw


def guard_memory(*a, **k):
    return memory_guard(*a, **k)


def auto_reduce_workers(*a, **k):
    return memory_guard(*a, **k)


def recommended_workers(prep_workers=None, inference_workers=None, **kw):
    if prep_workers is None:
        prep_workers = min(4, max(1, physical_cores() // 2))
    if inference_workers is None:
        inference_workers = 2
    return memory_guard(prep_workers, inference_workers, **kw)


def recommended_prep_workers(**kw):
    return recommended_workers(**kw)[0]


def recommended_inference_workers(**kw):
    return recommended_workers(**kw)[1]


memory_guard_workers = memory_guard
estimate_memory = memory_guard
check_memory = memory_guard