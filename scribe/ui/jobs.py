# Auloud Scribe — turns ebooks into multi-voice audiobooks (PC tool).
# Copyright (C) 2026 Zyxavy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Slice 6 UI4: job store, detached launch, FIFO queue, pause/resume/cancel.

One folder per job under ``<workspace>/jobs/<job-id>/``::

    job.json      state (id, book_id, kind, state, created/updated, pid,
                  exit, summary counts, error {file, rule, message}, args)
    events.jsonl  the build's own ``--events-jsonl`` output (build jobs) or
                  manager-written ``draft_start``/``draft_done`` lines
                  (draft jobs) — same UI1 key contract, tailed for SSE
    build.log     captured stdout/stderr of the detached process
    stop          cross-process stop sentinel (present only while a graceful
                  stop is requested; the build polls it via ``build.stop_file``)

History is a directory scan; no database. States are ``queued``, ``running``,
``paused``, ``done``, ``failed``, ``cancelled`` plus ``interrupted`` (the job
was ``running`` but its process is dead and no terminal state was recorded —
detected at manager start/scan, resumable from cache).

Concurrency is a single global FIFO: at most one job runs at a time; a second
job (any book) waits queued and starts when the first ends. One render per
book is enforced on top: creating a build/draft job while the book's
``.scribe.lock`` names a live holder — or while the same book already has a
queued/running/paused job — is refused with ``work-lock-held`` (the caller
shapes the 409). The detached child takes the real per-book lock itself, so
a CLI run racing the queue still fails that job cleanly instead of
overlapping.

Pause is cooperative (D-050 as amended: sequential chapters, no workers):
the manager creates the ``stop`` file and the ``scribe build`` child stops
at the next sentence boundary (``BuildStoppedError``: partial chapter files
removed, sentence cache untouched, so resume re-renders only uncached work
via the MV7 path). Pause never truncates or edits the cache. Resume deletes
the sentinel and starts a new process with the same arguments. Cancel
terminates the process and removes the in-progress chapter's torn render
files (best effort; ``chapter_up_to_date`` would re-render them anyway).
Draft jobs run the same detached ``scribe draft`` with manager-written
``draft_start``/``draft_done``/``draft_failed`` lines (UI3 thread subsumed;
the work-dir ``events.jsonl`` seam is mirrored for UI3 readers).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from .security import safe_join

#: Job folders live directly under the workspace (skipped by the book scan).
JOBS_DIRNAME = "jobs"
#: Persisted files inside each job folder.
JOB_FILENAME = "job.json"
EVENTS_FILENAME = "events.jsonl"
LOG_FILENAME = "build.log"
STOP_FILENAME = "stop"
#: Strict job-id charset (validated before any filesystem touch).
JOB_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
#: Terminal states (no further transitions except ``resume`` from some).
TERMINAL = frozenset({"done", "failed", "cancelled"})
#: States a ``resume`` accepts (new process, cache reused).
RESUMABLE = frozenset({"paused", "interrupted", "failed", "cancelled"})
#: How long ``pause`` waits for a graceful exit before terminating (s).
PAUSE_GRACE_S = 30.0
#: SSE tail poll interval (s).
TAIL_POLL_S = 0.5

_VALID_DEVICES = frozenset({"auto", "cpu", "cuda"})


def check_job_id(job_id: str) -> str:
    """Validate a job id (strict charset; raises ``bad-job-id`` when bad)."""
    text = str(job_id or "")
    if JOB_ID_RE.fullmatch(text) is None:
        raise ValueError(f"{text}: bad-job-id: bad job id")
    return text


def jobs_root(workspace: Path | str) -> Path:
    """``<workspace>/jobs`` (created on demand by writers)."""
    return Path(workspace).resolve() / JOBS_DIRNAME


def job_dir(workspace: Path | str, job_id: str) -> Path:
    """Confined job folder (validates the id, then ``safe_join``)."""
    check_job_id(job_id)
    return safe_join(Path(workspace).resolve(), JOBS_DIRNAME, job_id)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _atomic_write_json(path: Path, data: dict[str, Any]) -> None:
    """Atomic JSON write (retries transient Windows file locks).

    Monitor threads poll ``job.json`` while API threads save it; on Win32
    an open-for-read handle denies replace/share-delete, so ``os.replace``
    can raise ``PermissionError``. Retrying a few times rides out the
    millisecond race without ever leaving a torn file (the temp + replace
    shape is unchanged).
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(data, sort_keys=True, indent=2) + "\n"
    tmp = path.with_suffix(".tmp")
    last: OSError | None = None
    for _ in range(20):
        try:
            tmp.write_text(payload, encoding="utf-8")
            os.replace(tmp, path)
            return
        except OSError as exc:
            last = exc
            time.sleep(0.05)
    raise last if last is not None else OSError(f"cannot write {path}")


def _read_job_file(path: Path) -> dict[str, Any] | None:
    for _ in range(5):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            break
        except OSError:
            time.sleep(0.02)
            continue
        except ValueError:
            return None
    else:
        return None
    return data if isinstance(data, dict) else None


def _tail_event(job_events: Path) -> dict[str, Any] | None:
    """Last parseable JSON object of ``events.jsonl``, or None."""
    try:
        lines = job_events.read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    for line in reversed(lines):
        if not line.strip():
            continue
        try:
            data = json.loads(line)
        except ValueError:
            continue
        if isinstance(data, dict):
            return data
    return None


def _summary_from_tail(tail: dict[str, Any] | None) -> dict[str, Any]:
    """Summary counts for ``job.json`` from an events tail (zeros when none)."""
    summary: dict[str, Any] = {"cached": 0, "rendered": 0, "audio_ms": 0, "wall_s": 0.0}
    if not isinstance(tail, dict):
        return summary
    for key in ("cached", "rendered", "audio_ms"):
        try:
            summary[key] = int(tail.get(key) or 0)
        except (TypeError, ValueError):
            summary[key] = 0
    try:
        summary["wall_s"] = float(tail.get("wall_s") or 0.0)
    except (TypeError, ValueError):
        summary["wall_s"] = 0.0
    return summary


def _split_shaped(text: str, fallback_file: str, fallback_rule: str) -> dict[str, str]:
    parts = str(text).split(": ", 2)
    if len(parts) == 3:
        file, rule, message = parts
        return {"file": file or fallback_file, "rule": rule or fallback_rule, "message": message}
    if len(parts) == 2:
        return {"file": fallback_file, "rule": fallback_rule, "message": parts[1]}
    return {"file": fallback_file, "rule": fallback_rule, "message": str(text)}


def _error_from_log(log_path: Path, fallback_file: str) -> dict[str, str] | None:
    """Shape the last ``failed:`` line of ``build.log`` (None when absent)."""
    try:
        lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return None
    for line in reversed(lines):
        stripped = line.strip()
        if not stripped:
            continue
        if "failed:" in stripped:
            # CLI prints "build failed: <file: rule: message>" or
            # "draft failed: <message>"; strip the prefix, shape the rest.
            payload = stripped.split("failed:", 1)[1].strip()
            if not payload:
                continue
            return _split_shaped(payload, fallback_file, "build-failed")
    return None


def _cli_path() -> Path:
    """Absolute ``cli.py`` for detached children (dev tree or wheel)."""
    try:
        import cli as _cli  # type: ignore[import-not-found]

        return Path(str(_cli.__file__)).resolve()
    except ImportError:
        pass
    try:
        import scribe.cli as _cli2  # type: ignore[import-not-found]

        return Path(str(_cli2.__file__)).resolve()
    except ImportError:
        pass
    # Last resort: sibling of this file's package parent (flat tree).
    here = Path(__file__).resolve()
    candidate = here.parent.parent / "cli.py"
    return candidate


def _detached_popen(cmd: list[str], log_path: Path) -> "subprocess.Popen[str]":
    """Spawn ``cmd`` detached (Windows flags; ``start_new_session`` on POSIX).

    Stdout/stderr append to ``log_path``; stdin is DEVNULL. Documented
    Windows flags: ``DETACHED_PROCESS`` (no console inheritance) plus
    ``CREATE_NEW_PROCESS_GROUP`` (Ctrl-C goes to the child group only) plus
    ``CREATE_NO_WINDOW`` (no console window flashes on Win11). POSIX uses
    ``start_new_session=True`` (new process group, immune to Ctrl-C).
    """
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handle = open(log_path, "ab")
    try:
        kwargs: dict[str, Any] = {
            "stdin": subprocess.DEVNULL,
            "stdout": handle,
            "stderr": subprocess.STDOUT,
            "close_fds": False if os.name == "nt" else True,
        }
        if os.name == "nt":
            flags = 0
            for attr, fallback in (
                ("DETACHED_PROCESS", 0x00000008),
                ("CREATE_NEW_PROCESS_GROUP", 0x00000200),
                ("CREATE_NO_WINDOW", 0x08000000),
            ):
                flags |= int(getattr(subprocess, attr, fallback))
            kwargs["creationflags"] = flags
        else:
            kwargs["start_new_session"] = True
        proc = subprocess.Popen(cmd, **kwargs)  # noqa: S603 -- argv list, no shell
    finally:
        try:
            handle.close()
        except OSError:
            pass
    return proc  # type: ignore[return-value]


def _default_pid_alive(pid: Any) -> bool:
    try:
        from lock import _pid_alive
    except ImportError:
        return False
    try:
        return bool(_pid_alive(int(pid)))
    except (TypeError, ValueError):
        return False


def _book_work_dir(workspace: Path, book_id: str) -> Path:
    from .books import WORK_DIRNAME

    return workspace / WORK_DIRNAME / book_id


def _book_locked(workspace: Path, book_id: str) -> bool:
    """True when the book's ``.scribe.lock`` names a live holder."""
    try:
        from .books import is_rendering
    except ImportError:
        return False
    try:
        return bool(is_rendering(_book_work_dir(workspace, book_id)))
    except Exception:
        return False


def _cleanup_partial_chapter(workspace: Path, book_id: str, chapter: int) -> list[str]:
    """Delete torn render files for ``chapter`` (best effort; returns removed).

    Cooperative stops already clean up; terminate (cancel) can leave a torn
    MP3/JSON behind. ``chapter_up_to_date`` would re-render them anyway, but
    removing the in-progress pair keeps ``cancel cleans partial output``
    literal. Both width-3 and width-4 stems are tried (chapter counts above
    999 use four digits). Never touches other chapters or the cache.
    """
    removed: list[str] = []
    try:
        index = int(chapter)
    except (TypeError, ValueError):
        return removed
    if index < 1:
        return removed
    work = _book_work_dir(workspace, book_id)
    for width in (3, 4):
        stem = f"ch{index:0{width}d}"
        for sub, suffix in (("audio", ".mp3"), ("text", ".json")):
            path = work / "render" / sub / f"{stem}{suffix}"
            try:
                if path.is_file():
                    path.unlink()
                    removed.append(f"render/{sub}/{stem}{suffix}")
            except OSError:
                pass
    return sorted(removed)


# Injectable process type: real Popen or the tests' FakeProcess.
SpawnFn = Callable[[dict[str, Any], Path], Any]


class JobManager:
    """Job store + single-FIFO runner (thread-safe; scan is the history)."""

    def __init__(
        self,
        workspace: Path | str,
        *,
        spawn_fn: SpawnFn | None = None,
        pid_alive_fn: Callable[[Any], bool] | None = None,
    ) -> None:
        self.workspace = Path(workspace).resolve()
        self._spawn = spawn_fn or self._spawn_detached
        self._pid_alive = pid_alive_fn or _default_pid_alive
        self._mu = threading.Lock()
        self._live: dict[str, Any] = {}
        self._pausing: set[str] = set()
        self._cancelling: set[str] = set()
        self._scan_on_start()

    # -- persistence ------------------------------------------------------

    def _all_job_dirs(self) -> list[Path]:
        root = jobs_root(self.workspace)
        if not root.is_dir():
            return []
        out: list[Path] = []
        for child in sorted(root.iterdir()):
            if not child.is_dir():
                continue
            if JOB_ID_RE.fullmatch(child.name) is None:
                continue
            if (child / JOB_FILENAME).is_file():
                out.append(child)
        return out

    def _load(self, job_id: str) -> dict[str, Any] | None:
        check_job_id(job_id)
        return _read_job_file(job_dir(self.workspace, job_id) / JOB_FILENAME)

    def _save(self, job: dict[str, Any]) -> None:
        job["updated"] = _now_iso()
        _atomic_write_json(job_dir(self.workspace, str(job["id"])) / JOB_FILENAME, job)

    def list_jobs(self) -> list[dict[str, Any]]:
        """All jobs, newest first (directory scan; never raises on torn files)."""
        jobs: list[dict[str, Any]] = []
        for folder in self._all_job_dirs():
            data = _read_job_file(folder / JOB_FILENAME)
            if isinstance(data, dict) and isinstance(data.get("id"), str):
                jobs.append(self._public(data))
        jobs.sort(key=lambda j: str(j.get("created", "")), reverse=True)
        return jobs

    def get_job(self, job_id: str) -> dict[str, Any]:
        """Public job record (raises ``KeyError``/``ValueError``)."""
        check_job_id(job_id)
        data = self._load(job_id)
        if data is None:
            raise KeyError(f"{job_id}: job-not-found: no such job")
        return self._public(data)

    @staticmethod
    def _public(job: dict[str, Any]) -> dict[str, Any]:
        """API view of a stored record (stable keys, no internals)."""
        return {
            "id": job.get("id"),
            "book_id": job.get("book_id"),
            "kind": job.get("kind"),
            "state": job.get("state"),
            "created": job.get("created"),
            "updated": job.get("updated"),
            "pid": job.get("pid"),
            "exit": job.get("exit"),
            "summary": job.get("summary")
            or {"cached": 0, "rendered": 0, "audio_ms": 0, "wall_s": 0.0},
            "error": job.get("error"),
            "chapters": job.get("chapters"),
            "pages": job.get("pages"),
            "device": job.get("device"),
            "source_file": job.get("source_file"),
        }

    def _job_ids_for_book(self, book_id: str, states: set[str] | None = None) -> list[str]:
        out: list[str] = []
        for folder in self._all_job_dirs():
            data = _read_job_file(folder / JOB_FILENAME)
            if not isinstance(data, dict):
                continue
            if data.get("book_id") != book_id:
                continue
            if states is not None and data.get("state") not in states:
                continue
            job_id = data.get("id")
            if isinstance(job_id, str):
                out.append(job_id)
        return out

    # -- creation ----------------------------------------------------------

    def _new_id(self) -> str:
        root = jobs_root(self.workspace)
        for _ in range(10):
            candidate = f"job-{uuid.uuid4().hex[:12]}"
            if not (root / candidate).exists():
                return candidate
        return f"job-{uuid.uuid4().hex}"

    def _check_book_free(self, book_id: str) -> None:
        """Refuse when the book is busy (lock held or job active).

        Raises ``ValueError`` shaped ``work-lock-held`` (the API maps it to
        409). Active means a queued/running/paused job for the same book —
        one render per book; across books the FIFO queues instead.
        """
        from .books import check_book_id

        check_book_id(book_id)
        busy = self._job_ids_for_book(book_id, {"queued", "running", "paused"})
        if busy:
            raise ValueError(
                f"{book_id}: work-lock-held: book already has job {busy[0]} "
                "(one render per book; wait or cancel it first)"
            )
        if _book_locked(self.workspace, book_id):
            raise ValueError(
                f"{book_id}: work-lock-held: book is locked by another draft/build "
                "(another run is holding the work lock)"
            )

    def create_build(
        self,
        book_id: str,
        source_file: str,
        *,
        chapters: str | None = None,
        pages: str | None = None,
        device: str = "cpu",
    ) -> dict[str, Any]:
        """Queue a build job (202 shape via the API; raises ``ValueError``)."""
        normalized_device = str(device or "cpu").strip().lower()
        if normalized_device not in _VALID_DEVICES:
            raise ValueError(
                f"{source_file}: bad-device: {device!r} must be one of ['auto', 'cpu', 'cuda']"
            )
        if chapters and pages:
            raise ValueError(
                "build: chapters-pages-exclusive: --chapters and --pages "
                "cannot be used together (pick one range form)"
            )
        self._check_book_free(book_id)
        job_id = self._new_id()
        now = _now_iso()
        job: dict[str, Any] = {
            "id": job_id,
            "book_id": book_id,
            "kind": "build",
            "state": "queued",
            "created": now,
            "updated": now,
            "pid": None,
            "exit": None,
            "summary": {"cached": 0, "rendered": 0, "audio_ms": 0, "wall_s": 0.0},
            "error": None,
            "chapters": chapters,
            "pages": pages,
            "device": normalized_device,
            "source_file": source_file,
        }
        folder = job_dir(self.workspace, job_id)
        folder.mkdir(parents=True, exist_ok=True)
        _atomic_write_json(folder / JOB_FILENAME, job)
        try:
            (folder / EVENTS_FILENAME).write_text("", encoding="utf-8")
        except OSError:
            pass
        self._pump_queue()
        loaded = self._load(job_id)
        return self._public(loaded or job)

    def create_draft(self, book_id: str, source_file: str, filename: str) -> dict[str, Any]:
        """Queue a draft job (upload path; same FIFO, same store)."""
        self._check_book_free(book_id)
        job_id = self._new_id()
        now = _now_iso()
        job: dict[str, Any] = {
            "id": job_id,
            "book_id": book_id,
            "kind": "draft",
            "state": "queued",
            "created": now,
            "updated": now,
            "pid": None,
            "exit": None,
            "summary": {"cached": 0, "rendered": 0, "audio_ms": 0, "wall_s": 0.0},
            "error": None,
            "chapters": None,
            "pages": None,
            "device": None,
            "source_file": source_file,
            "filename": filename,
        }
        folder = job_dir(self.workspace, job_id)
        folder.mkdir(parents=True, exist_ok=True)
        _atomic_write_json(folder / JOB_FILENAME, job)
        try:
            (folder / EVENTS_FILENAME).write_text("", encoding="utf-8")
        except OSError:
            pass
        self._pump_queue()
        loaded = self._load(job_id)
        return self._public(loaded or job)

    # -- queue pump ----------------------------------------------------------

    def _running_id(self) -> str | None:
        for folder in self._all_job_dirs():
            data = _read_job_file(folder / JOB_FILENAME)
            if isinstance(data, dict) and data.get("state") == "running":
                job_id = data.get("id")
                if isinstance(job_id, str):
                    return job_id
        return None

    def _oldest_queued(self) -> dict[str, Any] | None:
        queued: list[dict[str, Any]] = []
        for folder in self._all_job_dirs():
            data = _read_job_file(folder / JOB_FILENAME)
            if isinstance(data, dict) and data.get("state") == "queued":
                queued.append(data)
        if not queued:
            return None
        queued.sort(key=lambda j: str(j.get("created", "")))
        return queued[0]

    def _pump_queue(self) -> None:
        """Start the oldest queued job when nothing runs (idempotent)."""
        with self._mu:
            if self._running_id() is not None:
                return
            nxt = self._oldest_queued()
            if nxt is None:
                return
            job_id = str(nxt["id"])
            if job_id in self._live:
                return
        self._start_job(job_id)

    def _start_job(self, job_id: str) -> None:
        job = self._load(job_id)
        if job is None or job.get("state") not in ("queued",):
            return
        folder = job_dir(self.workspace, job_id)
        # A fresh run never inherits a stale stop request.
        try:
            (folder / STOP_FILENAME).unlink(missing_ok=True)
        except OSError:
            pass
        if job.get("kind") == "draft":
            self._write_draft_event(job, folder, "draft_start")
        try:
            proc = self._spawn(dict(job), folder)
        except Exception as exc:  # spawn failure fails the job, never crashes pump
            job["state"] = "failed"
            job["pid"] = None
            job["exit"] = -1
            job["error"] = {
                "file": str(job.get("source_file") or job.get("book_id") or job_id),
                "rule": "spawn-failed",
                "message": str(exc) or type(exc).__name__,
            }
            self._save(job)
            return
        pid = getattr(proc, "pid", None)
        try:
            pid_int = int(pid) if pid is not None else None
        except (TypeError, ValueError):
            pid_int = None
        job["state"] = "running"
        job["pid"] = pid_int
        job["exit"] = None
        job["error"] = None
        self._save(job)
        with self._mu:
            self._live[job_id] = proc
        try:
            from . import keepawake as _keepawake

            _keepawake.acquire(self.workspace)
        except Exception:
            pass
        thread = threading.Thread(target=self._monitor, args=(job_id,), daemon=True)
        thread.start()

    # -- spawn ---------------------------------------------------------------

    def _spawn_detached(self, job: dict[str, Any], folder: Path) -> Any:
        """Production spawner: detached ``scribe build``/``draft`` (stdio to log)."""
        from .books import WORK_DIRNAME

        cli = _cli_path()
        work_root = self.workspace / WORK_DIRNAME
        source_rel = str(job.get("source_file") or "")
        source_abs = safe_join(self.workspace, source_rel) if source_rel else Path(source_rel)
        events_path = folder / EVENTS_FILENAME
        log_path = folder / LOG_FILENAME
        if job.get("kind") == "draft":
            cmd = [sys.executable, str(cli), "draft", str(source_abs), "--work-dir", str(work_root)]
        else:
            cmd = [
                sys.executable,
                str(cli),
                "build",
                str(source_abs),
                "--work-dir",
                str(work_root),
                "--events-jsonl",
                str(events_path),
                "--stop-file",
                str(folder / STOP_FILENAME),
                "--no-progress",
            ]
            if job.get("chapters"):
                cmd += ["--chapters", str(job["chapters"])]
            if job.get("pages"):
                cmd += ["--pages", str(job["pages"])]
            cmd += ["--device", str(job.get("device") or "cpu")]
        return _detached_popen(cmd, log_path)

    # -- draft event mirror (UI3 seam) -------------------------------------------

    def _write_draft_event(
        self, job: dict[str, Any], folder: Path, event: str, extra: dict[str, Any] | None = None
    ) -> None:
        line: dict[str, Any] = {
            "event": event,
            "chapter": 0,
            "sid": None,
            "cached": 0,
            "rendered": 0,
            "audio_ms": 0,
            "wall_s": 0.0,
        }
        if extra:
            line.update(extra)
        try:
            with open(folder / EVENTS_FILENAME, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, sort_keys=True) + "\n")
        except OSError:
            pass
        # Mirror to the work-dir seam so UI3 readers (scan/detail) keep
        # working while UI4 owns the job folders.
        try:
            from .books import EVENTS_FILENAME as _WORK_EVENTS, WORK_DIRNAME as _WORK

            work_dir = self.workspace / _WORK / str(job.get("book_id") or "")
            work_dir.mkdir(parents=True, exist_ok=True)
            with open(work_dir / _WORK_EVENTS, "a", encoding="utf-8") as handle:
                handle.write(json.dumps(line, sort_keys=True) + "\n")
        except (OSError, ValueError):
            pass

    # -- monitor -----------------------------------------------------------------

    def _monitor(self, job_id: str) -> None:
        """Wait for the live process, then finalize (done/failed/paused/cancelled)."""
        with self._mu:
            proc = self._live.get(job_id)
        exit_code: Any = None
        if proc is not None:
            try:
                wait = getattr(proc, "wait", None)
                if callable(wait):
                    exit_code = wait()
                else:
                    poll = getattr(proc, "poll", None)
                    if callable(poll):
                        deadline = time.monotonic() + 24 * 3600
                        while time.monotonic() < deadline:
                            exit_code = poll()
                            if exit_code is not None:
                                break
                            time.sleep(0.5)
            except Exception:
                exit_code = -1
            try:
                poll = getattr(proc, "poll", None)
                if callable(poll) and exit_code is None:
                    exit_code = poll()
            except Exception:
                pass
        self._finalize(job_id, exit_code)

    def _monitor_reattached(self, job_id: str, pid: int) -> None:
        """Poll an inherited pid (server restart) until it dies, then finalize."""
        deadline = time.monotonic() + 24 * 3600
        while time.monotonic() < deadline:
            try:
                if not self._pid_alive(pid):
                    break
            except Exception:
                break
            time.sleep(1.0)
        job = self._load(job_id)
        if job is None or job.get("state") != "running":
            return
        folder = job_dir(self.workspace, job_id)
        tail = _tail_event(folder / EVENTS_FILENAME)
        done_events = {"build_done", "draft_done"}
        if isinstance(tail, dict) and tail.get("event") in done_events:
            self._finish_done(job, folder, tail)
        else:
            err = _error_from_log(folder / LOG_FILENAME, str(job.get("source_file") or job_id))
            if err is None:
                err = {
                    "file": str(job.get("source_file") or job_id),
                    "rule": "interrupted",
                    "message": f"{job_id}: interrupted: process died without a terminal state",
                }
            self._finish_failed(job, folder, exit_code=-1, error=err)
        self._pump_queue()

    def _finalize(self, job_id: str, exit_code: Any) -> None:
        with self._mu:
            self._live.pop(job_id, None)
            cancelling = job_id in self._cancelling
            pausing = job_id in self._pausing
            self._cancelling.discard(job_id)
            self._pausing.discard(job_id)
        job = self._load(job_id)
        if job is None:
            self._release_awake()
            self._pump_queue()
            return
        if job.get("state") != "running":
            self._release_awake()
            self._pump_queue()
            return
        folder = job_dir(self.workspace, job_id)
        try:
            code = int(exit_code) if exit_code is not None else -1
        except (TypeError, ValueError):
            code = -1
        if cancelling:
            self._finish_cancelled(job, folder, code)
            self._pump_queue()
            return
        if pausing:
            # Graceful stop requested: the child saw the sentinel and left
            # through BuildStoppedError (non-zero exit). Treat any exit here
            # as paused — the cache is intact by construction — and drop the
            # fulfilled request file.
            try:
                (folder / STOP_FILENAME).unlink(missing_ok=True)
            except OSError:
                pass
            job["state"] = "paused"
            job["pid"] = None
            job["exit"] = code
            job["summary"] = _summary_from_tail(_tail_event(folder / EVENTS_FILENAME))
            self._save(job)
            self._release_awake()
            self._pump_queue()
            return
        if job.get("kind") == "draft":
            if code == 0:
                self._write_draft_event(job, folder, "draft_done")
                self._finish_done(job, folder, _tail_event(folder / EVENTS_FILENAME))
            else:
                err = _error_from_log(folder / LOG_FILENAME, str(job.get("source_file") or job_id))
                if err is None:
                    err = {
                        "file": str(job.get("source_file") or job_id),
                        "rule": "draft-failed",
                        "message": f"{job_id}: draft-failed: process exited with code {code}",
                    }
                self._write_draft_event(job, folder, "draft_failed", {"error": err})
                self._finish_failed(job, folder, exit_code=code, error=err)
            self._pump_queue()
            return
        tail = _tail_event(folder / EVENTS_FILENAME)
        if code == 0:
            self._finish_done(job, folder, tail)
        else:
            # A stop file left behind by a crashed pauser still means the
            # exit was stop-induced, not a real failure.
            if (folder / STOP_FILENAME).is_file():
                try:
                    (folder / STOP_FILENAME).unlink(missing_ok=True)
                except OSError:
                    pass
                job["state"] = "paused"
                job["pid"] = None
                job["exit"] = code
                job["summary"] = _summary_from_tail(tail)
                self._save(job)
                self._release_awake()
                self._pump_queue()
                return
            err = _error_from_log(folder / LOG_FILENAME, str(job.get("source_file") or job_id))
            if err is None:
                err = {
                    "file": str(job.get("source_file") or job_id),
                    "rule": "build-failed",
                    "message": f"{job_id}: build-failed: process exited with code {code}",
                }
            self._finish_failed(job, folder, exit_code=code, error=err)
        self._pump_queue()

    def _finish_done(
        self, job: dict[str, Any], folder: Path, tail: dict[str, Any] | None
    ) -> None:
        job["state"] = "done"
        job["pid"] = None
        job["exit"] = 0
        job["error"] = None
        job["summary"] = _summary_from_tail(tail)
        try:
            (folder / STOP_FILENAME).unlink(missing_ok=True)
        except OSError:
            pass
        self._save(job)
        self._release_awake()

    def _finish_failed(
        self, job: dict[str, Any], folder: Path, *, exit_code: int, error: dict[str, str]
    ) -> None:
        job["state"] = "failed"
        job["pid"] = None
        job["exit"] = exit_code
        job["error"] = error
        job["summary"] = _summary_from_tail(_tail_event(folder / EVENTS_FILENAME))
        try:
            (folder / STOP_FILENAME).unlink(missing_ok=True)
        except OSError:
            pass
        self._save(job)
        self._release_awake()

    def _finish_cancelled(self, job: dict[str, Any], folder: Path, code: int) -> None:
        tail = _tail_event(folder / EVENTS_FILENAME)
        chapter = 0
        if isinstance(tail, dict):
            try:
                chapter = int(tail.get("chapter") or 0)
            except (TypeError, ValueError):
                chapter = 0
        if job.get("kind") == "build" and chapter > 0:
            _cleanup_partial_chapter(self.workspace, str(job.get("book_id") or ""), chapter)
        if job.get("kind") == "draft":
            err = {
                "file": str(job.get("source_file") or job["id"]),
                "rule": "cancelled",
                "message": f"{job['id']}: cancelled: draft cancelled",
            }
            self._write_draft_event(job, folder, "draft_failed", {"error": err})
        job["state"] = "cancelled"
        job["pid"] = None
        job["exit"] = code
        job["summary"] = _summary_from_tail(tail)
        try:
            (folder / STOP_FILENAME).unlink(missing_ok=True)
        except OSError:
            pass
        self._save(job)
        self._release_awake()

    def _release_awake(self) -> None:
        try:
            from . import keepawake as _keepawake

            _keepawake.release()
        except Exception:
            pass

    # -- control -------------------------------------------------------------------

    def pause(self, job_id: str) -> dict[str, Any]:
        """Request a graceful stop (sentinel; the child exits at a boundary)."""
        check_job_id(job_id)
        job = self._load(job_id)
        if job is None:
            raise KeyError(f"{job_id}: job-not-found: no such job")
        if job.get("state") != "running":
            raise ValueError(f"{job_id}: bad-state: only running jobs can pause")
        folder = job_dir(self.workspace, job_id)
        try:
            folder.mkdir(parents=True, exist_ok=True)
            (folder / STOP_FILENAME).write_text("stop\n", encoding="utf-8")
        except OSError as exc:
            raise ValueError(f"{job_id}: cannot-pause: cannot write stop file: {exc}")
        with self._mu:
            self._pausing.add(job_id)
        # Wait briefly so a fast fake settles to paused before we return;
        # slow real builds stay running and flip when the child exits.
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            current = self._load(job_id)
            if current is not None and current.get("state") != "running":
                return self._public(current)
            time.sleep(0.05)
        current = self._load(job_id)
        return self._public(current or job)

    def resume(self, job_id: str) -> dict[str, Any]:
        """Queue a new process for a paused/interrupted/failed/cancelled job."""
        check_job_id(job_id)
        job = self._load(job_id)
        if job is None:
            raise KeyError(f"{job_id}: job-not-found: no such job")
        if job.get("state") not in RESUMABLE:
            raise ValueError(
                f"{job_id}: bad-state: only paused, interrupted, failed or cancelled resume"
            )
        # Resuming a book another run locked meanwhile must fail fast.
        book_id = str(job.get("book_id") or "")
        others = [
            j
            for j in self._job_ids_for_book(book_id, {"queued", "running", "paused"})
            if j != job_id
        ]
        if others:
            raise ValueError(
                f"{book_id}: work-lock-held: book already has job {others[0]} "
                "(one render per book; wait or cancel it first)"
            )
        if book_id and _book_locked(self.workspace, book_id):
            raise ValueError(
                f"{book_id}: work-lock-held: book is locked by another draft/build "
                "(another run is holding the work lock)"
            )
        folder = job_dir(self.workspace, job_id)
        try:
            (folder / STOP_FILENAME).unlink(missing_ok=True)
        except OSError:
            pass
        job["state"] = "queued"
        job["pid"] = None
        job["exit"] = None
        job["error"] = None
        self._save(job)
        self._pump_queue()
        loaded = self._load(job_id)
        return self._public(loaded or job)

    def cancel(self, job_id: str) -> dict[str, Any]:
        """Cancel a queued/paused/running job (terminate + partial cleanup)."""
        check_job_id(job_id)
        job = self._load(job_id)
        if job is None:
            raise KeyError(f"{job_id}: job-not-found: no such job")
        state = job.get("state")
        folder = job_dir(self.workspace, job_id)
        if state == "queued":
            self._finish_cancelled(job, folder, -1)
            return self._public(job)
        if state == "paused":
            prev = job.get("exit") if isinstance(job.get("exit"), int) else -1
            self._finish_cancelled(job, folder, prev)
            return self._public(job)
        if state != "running":
            raise ValueError(f"{job_id}: bad-state: only queued, running or paused jobs cancel")
        with self._mu:
            self._cancelling.add(job_id)
            self._pausing.discard(job_id)
            proc = self._live.get(job_id)
        try:
            (folder / STOP_FILENAME).unlink(missing_ok=True)
        except OSError:
            pass
        if proc is not None:
            try:
                term = getattr(proc, "terminate", None)
                if callable(term):
                    term()
            except Exception:
                pass
            try:
                wait = getattr(proc, "wait", None)
                if callable(wait):
                    wait(timeout=10)
            except Exception:
                try:
                    kill = getattr(proc, "kill", None)
                    if callable(kill):
                        kill()
                except Exception:
                    pass
        deadline = time.monotonic() + 15.0
        while time.monotonic() < deadline:
            current = self._load(job_id)
            if current is not None and current.get("state") != "running":
                return self._public(current)
            time.sleep(0.05)
        current = self._load(job_id)
        return self._public(current or job)

    # -- startup scan ---------------------------------------------------------------

    def _scan_on_start(self) -> None:
        """Mark orphaned ``running`` jobs ``interrupted``; reattach live ones."""
        reattach: list[tuple[str, int]] = []
        for folder in self._all_job_dirs():
            data = _read_job_file(folder / JOB_FILENAME)
            if not isinstance(data, dict):
                continue
            if data.get("state") != "running":
                continue
            job_id = data.get("id")
            if not isinstance(job_id, str):
                continue
            pid = data.get("pid")
            alive = False
            if isinstance(pid, int):
                try:
                    alive = bool(self._pid_alive(pid))
                except Exception:
                    alive = False
            if alive and isinstance(pid, int):
                reattach.append((job_id, pid))
            else:
                data["state"] = "interrupted"
                data["pid"] = None
                data["summary"] = _summary_from_tail(_tail_event(folder / EVENTS_FILENAME))
                try:
                    (folder / STOP_FILENAME).unlink(missing_ok=True)
                except OSError:
                    pass
                try:
                    _atomic_write_json(folder / JOB_FILENAME, data)
                except OSError:
                    pass
        for job_id, pid in reattach:
            try:
                from . import keepawake as _keepawake

                _keepawake.acquire(self.workspace)
            except Exception:
                pass
            thread = threading.Thread(
                target=self._monitor_reattached, args=(job_id, pid), daemon=True
            )
            thread.start()
        # Queued jobs left by a dead server start now (when nothing runs).
        try:
            self._pump_queue()
        except Exception:
            pass

    def running_ids(self) -> list[str]:
        """Ids with state ``running`` (server-shutdown warning source)."""
        out: list[str] = []
        for folder in self._all_job_dirs():
            data = _read_job_file(folder / JOB_FILENAME)
            if isinstance(data, dict) and data.get("state") == "running":
                job_id = data.get("id")
                if isinstance(job_id, str):
                    out.append(job_id)
        return sorted(out)

    # -- SSE helpers ------------------------------------------------------------------

    def events_path(self, job_id: str) -> Path:
        """Confined ``events.jsonl`` path (validates the id first)."""
        check_job_id(job_id)
        data = self._load(job_id)
        if data is None:
            raise KeyError(f"{job_id}: job-not-found: no such job")
        return job_dir(self.workspace, job_id) / EVENTS_FILENAME

    def read_event_lines(self, job_id: str) -> list[str]:
        """Raw ``events.jsonl`` lines (torn last line included; never raises)."""
        try:
            path = self.events_path(job_id)
        except (ValueError, KeyError):
            return []
        try:
            return path.read_text(encoding="utf-8").splitlines()
        except OSError:
            return []


__all__ = [
    "EVENTS_FILENAME",
    "JOB_FILENAME",
    "JOBS_DIRNAME",
    "LOG_FILENAME",
    "STOP_FILENAME",
    "JobManager",
    "check_job_id",
    "job_dir",
    "jobs_root",
]
