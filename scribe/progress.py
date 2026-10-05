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

"""Build progress events (Slice 6 UI1): one listener interface, two sinks.

``run_build`` accepts an optional ``progress_listener`` callable receiving
one ``dict`` per sentence/chapter boundary::

    {"event": "sentence"|"chapter_done"|"chapter_skipped"|"build_done",
     "chapter": int, "sid": int|None,
     "cached": int, "rendered": int,   # cumulative sentence-audio counts
     "audio_ms": int,                  # cumulative rendered audio (done ch.)
     "wall_s": float,                  # elapsed wall seconds
     "elapsed": float,                 # alias of wall_s (CLI wording)
     "estimate": float|None}           # remaining seconds (None when unknown)

Sentence events carry the sid and the updated cumulative ``cached`` /
``rendered`` (cache hit or miss for that sentence). Chapter events carry
``audio_ms`` for that chapter (0 when skipped counts as its stored
duration). ``build_done`` carries the run totals.

Two sinks share the numbers (tested):

- :class:`RichProgressListener`: the CLI progress bar (same columns and
  ``[i/N] title`` wording as the pre-UI1 inline bar, so existing CLI output
  is unchanged).
- :class:`JsonlProgressWriter`: one JSON object per line
  ``{event, chapter, sid, cached, rendered, audio_ms, wall_s}`` for the
  future detached job tailing (server tails ``events.jsonl`` and streams
  SSE). Same cumulative numbers as the Rich bar.
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any, Callable

#: Listener signature (plain callable so the UI can pass any sink).
ProgressListener = Callable[[dict[str, Any]], None]


def make_event(
    event: str,
    *,
    chapter: int = 0,
    sid: int | None = None,
    cached: int = 0,
    rendered: int = 0,
    audio_ms: int = 0,
    wall_s: float = 0.0,
    estimate: float | None = None,
) -> dict[str, Any]:
    """One progress event dict (keys stable for Rich + JSONL sinks)."""
    return {
        "event": event,
        "chapter": chapter,
        "sid": sid,
        "cached": cached,
        "rendered": rendered,
        "audio_ms": audio_ms,
        "wall_s": wall_s,
        "elapsed": wall_s,
        "estimate": estimate,
    }


class JsonlProgressWriter:
    """Append-only JSONL sink: one object per line (detached-job tailing).

    Lines carry ``{event, chapter, sid, cached, rendered, audio_ms,
    wall_s}`` (the UI1 contract). The writer flushes every event so a
    tailing server (UI4) sees progress even when the build process dies.
    """

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = open(self.path, "a", encoding="utf-8")

    def __call__(self, event: dict[str, Any]) -> None:
        line = {
            "event": event.get("event"),
            "chapter": event.get("chapter"),
            "sid": event.get("sid"),
            "cached": event.get("cached"),
            "rendered": event.get("rendered"),
            "audio_ms": event.get("audio_ms"),
            "wall_s": event.get("wall_s"),
        }
        self._handle.write(json.dumps(line, sort_keys=True) + "\n")
        self._handle.flush()

    def close(self) -> None:
        """Close the file (idempotent)."""
        try:
            self._handle.close()
        except (OSError, ValueError):
            pass

    def __enter__(self) -> JsonlProgressWriter:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()


class FanoutProgress:
    """Call every wrapped listener with each event (Rich + JSONL together)."""

    def __init__(self, *listeners: ProgressListener) -> None:
        self._listeners = [item for item in listeners if item is not None]

    def __call__(self, event: dict[str, Any]) -> None:
        for listener in self._listeners:
            listener(event)


class RichProgressListener:
    """CLI ``rich`` bar as a listener (same wording as the old inline bar).

    Usage: ``with RichProgressListener(total, enabled) as bar:`` then
    ``run_build(..., progress_listener=bar)``. Sentence events update the
    description with cache counts; chapter events advance the bar. Disabled
    (``enabled=False``, tests) records events without touching the terminal.
    """

    def __init__(self, total: int, enabled: bool = True) -> None:
        self.total = total
        self.enabled = enabled
        self.events: list[dict[str, Any]] = []
        self._progress: Any = None
        self._task: Any = None
        self._start = time.monotonic()

    def __enter__(self) -> RichProgressListener:
        if not self.enabled:
            return self
        from rich.progress import (
            BarColumn,
            Progress,
            TaskProgressColumn,
            TextColumn,
            TimeRemainingColumn,
        )

        columns = [
            TextColumn("{task.description}"),
            BarColumn(),
            TaskProgressColumn(),
            TimeRemainingColumn(),
        ]
        self._progress = Progress(*columns, disable=False)
        self._progress.__enter__()
        self._task = self._progress.add_task("Rendering chapters", total=self.total)
        return self

    def __exit__(self, *exc: Any) -> None:
        if self._progress is not None:
            self._progress.__exit__(*exc)
            self._progress = None
            self._task = None

    def __call__(self, event: dict[str, Any]) -> None:
        self.events.append(dict(event))
        if not self.enabled or self._progress is None or self._task is None:
            return
        kind = event.get("event")
        if kind == "sentence":
            chapter = event.get("chapter")
            cached = event.get("cached")
            rendered = event.get("rendered")
            self._progress.update(
                self._task,
                description=f"[ch {chapter}] cached {cached} rendered {rendered}",
            )
        elif kind in ("chapter_done", "chapter_skipped"):
            chapter = event.get("chapter")
            title = event.get("title") or ""
            total = event.get("chapters_total") or self.total
            self._progress.update(
                self._task, description=f"[{chapter}/{total}] {title}"
            )
            self._progress.advance(self._task)
        elif kind == "build_done":
            self._progress.update(self._task, description="Rendering chapters")
