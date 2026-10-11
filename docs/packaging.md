# Helper Packaging (Slice 15)

How the Python Scribe ships inside the v3 app, and the measured numbers behind the choice.

## Runtime inventory (HP0, dev machine 2026-10-11)

- Python 3.14.5 (venv); **pinned for the helper: CPython 3.14.8** via python-build-standalone release 20261009 (`install_only`, x86_64 Windows; Linux x86_64 at HP7). Stays on 3.14 because every pinned wheel already ships cp314 builds; 3.15 exists upstream but buys nothing here.
- `site-packages`: 170 top-level dirs, about 396 MB. Native highlights: onnxruntime 1.30.0 (pybind `.pyd` plus `onnxruntime.dll`), PyMuPDF 1.28.2 (`mupdfcpp64.dll` 25 MB plus `_mupdf.pyd`), numpy 2.5.3 (OpenBLAS), soundfile (libsndfile), blis, lxml, pydantic-core.
- Phonemizer is self-contained: `espeakng_loader` 0.2.4 ships `espeak-ng.dll` (409 KB) plus `espeak-ng-data` (lang and voices, about 17.5 MB); `phonemizer` 3.4.0 (required by `kokoro-onnx` 0.6.1) rides on it. **No system espeak-ng is needed at runtime**; only the `doctor` row checks for `espeak-ng.exe`, so that row becomes advisory for the helper (HP1).
- `piper-tts` 1.8.0 carries its own espeak data (see `tts/piper.py`); same conclusion, no system dependency.
- `en_core_web_sm` 3.8.0 URL-pinned in `pyproject.toml` plus `uv.lock`.
- Web stack (`ui` extra): FastAPI 0.142.2, uvicorn 0.54, starlette 1.7.
- External today, bundled at HP2: ffmpeg/ffprobe (pinned build TBD), MSVC runtime DLLs if the sandbox proves them missing.
- Models stay outside the bundle (sideloaded): `kokoro-v1.0.onnx` 310 MB, `voices-v1.0.bin` 27 MB, `piper/` about 99 MB (several voices).

## Helper entry reuse notes (HP1 reads these)

- `ui/server.py`: 127.0.0.1-only bind guard (no `--host` flag on purpose), free-port pick from 8137 upward, per-workspace `.scribe-ui.lock` (O_EXCL plus stale rules, no same-process re-entry).
- `ui/security.py`: per-run token (`secrets`), Host allow-list (`is_allowed_host`), constant-time token compare, `safe_join` against traversal.
- `ui/jobs.py`: detached child processes with per-book lock, FIFO queue, stop sentinel, pause/resume/cancel, reattach after restart.
- `lock.py`: PID-liveness plus 24 h stale pattern shared by both locks.
- `cli.py` `doctor`: 11 rows; the espeak-ng row goes advisory for the helper (see above).

## Metrics sheet (HP8 fills this; thresholds proposed in decision 9 of the slice plan)

| Metric | Embedded | Frozen | Dev machine |
| --- | --- | --- | --- |
| Size unzipped, zipped, file count |  |  |  |
| Cold start and warm start to ready |  |  |  |
| Idle memory |  |  |  |
| Build speed (RTF) on the C&P fragment |  |  | about 4.38x CPU (test-log reference) |
| Defender result and first-run scan time |  |  |  |
| Long path, spaces, non-ASCII |  |  |  |
| Offline behavior |  |  |  |
| Detached job survives helper kill |  |  |  |
| Build reliability and reproducibility |  |  | |

## Decision log (slice decisions land in `docs/DECISIONS.md`; summary here at HP9)

- Sandbox is unavailable on this machine (owner-confirm 2026-10-11); the HP4 clean-machine route falls back to VirtualBox, Hyper-V, or a spare machine (route chosen at HP4 at the latest).
- Gate thresholds approved as proposed (cold ready 10 s, warm 5 s, idle 300 MB, speed within 10%, zero Defender hits, offline OK).
