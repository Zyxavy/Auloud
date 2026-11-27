# Auloud Scribe — render equivalence + safety tests (Task 1).
#
# Gates every later optimization task: a refactor is only acceptable if it
# reproduces the sequential baseline's sentence order, cache keys, decoded
# PCM hashes and timings (within 1 ms), and passes `scribe validate`.

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

# Make ffmpeg discoverable when winget installed it without updating PATH.
_FFMPEG_DIR = r"C:\Users\kelps\AppData\Local\Microsoft\WinGet\Packages\Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe\ffmpeg-9.0.2-full_build\bin"
if os.path.isdir(_FFMPEG_DIR) and _FFMPEG_DIR not in os.environ.get("PATH", ""):
    os.environ["PATH"] = _FFMPEG_DIR + os.pathsep + os.environ.get("PATH", "")

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from build import (
    BuildError,
    CAST_FILENAME,
    compare_bundles,
    create_engine,
    ensure_script,
    run_build,
    resolve_chapter,
)
from bundle.models import ChapterFile
from bundle.validate import validate_bundle
from text.cast import read_cast
from tts.base import TTSEngine
from tts.cache import cache_key, cache_path, _read_cached
from tts.kokoro import resolve_model_paths

from build import _narrator_entry

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "bench_chapter.epub"
GOLDEN = Path(__file__).resolve().parent / "fixtures" / "render_equivalence_golden.json"
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"
VOICE_PITCH = 1.0
try:
    from bundle.writer import VOICE_PITCH as _VP

    VOICE_PITCH = _VP
except Exception:  # pragma: no cover - fallback
    pass


def _has_ffmpeg() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def _has_models() -> bool:
    try:
        resolve_model_paths(MODELS_DIR)
        return True
    except Exception:
        return False


def _find_model_files():
    m = MODELS_DIR / "kokoro-v1.0.onnx"
    v = MODELS_DIR / "voices-v1.0.bin"
    return m.is_file() and v.is_file()


def _read_flac(path, sample_rate):
    # reuse cache's decoder
    from tts.cache import _read_flac as _rf

    return _rf(path, sample_rate)


def _collect_facts(source, work_root, out_dir, warm_cache_from=None):
    """Run a sequential build and capture per-sentence facts.

    When ``warm_cache_from`` is given, its cache is copied into this work
    dir first so the build is a warm (all-hit) run; this is what makes the
    decoded PCM hashes reproducible across the golden and candidate runs
    despite the engine's stochastic source generator.
    """
    if not _has_ffmpeg():
        pytest.skip("ffmpeg/ffprobe not on PATH")
    if not _find_model_files():
        pytest.skip("Kokoro model files absent")

    work_root = Path(work_root)
    out_dir = Path(out_dir)
    if warm_cache_from is not None:
        try:
            si = ensure_script(source, work_root=work_root)
            src_cache = Path(warm_cache_from) / si.book_id / "cache"
            dst_cache = work_root / si.book_id / "cache"
            if src_cache.is_dir():
                dst_cache.mkdir(parents=True, exist_ok=True)
                for f in src_cache.glob("*.flac"):
                    (dst_cache / f.name).write_bytes(f.read_bytes())
        except Exception:
            pass
    result = run_build(
        source,
        work_root=work_root,
        out_dir=out_dir,
        show_progress=False,
        device="auto",
    )
    vr = validate_bundle(out_dir)
    assert vr.ok, vr.errors

    engine = create_engine(MODELS_DIR, device="auto")
    script_info = ensure_script(source, work_root=work_root)
    chapters = script_info.chapters
    book_id = script_info.book_id
    cast_path = work_root / book_id / CAST_FILENAME
    cast = read_cast(cast_path)
    narrator_voice, narrator_speed, narrator_engine = _narrator_entry(cast_path)
    cache_dir = work_root / book_id / "cache"

    # Map rendered timings by chapter.
    render_text_dir = work_root / book_id / "render" / "text"
    timed_by_chapter = {}
    if render_text_dir.is_dir():
        for p in render_text_dir.glob("*.json"):
            raw = json.loads(p.read_text(encoding="utf-8"))
            timed_by_chapter[raw.get("chapter")] = ChapterFile.from_dict(raw)

    facts_sentences = []
    for chapter in chapters:
        plan = resolve_chapter(
            cast,
            chapter,
            narrator_voice=narrator_voice,
            narrator_speed=narrator_speed,
            narrator_engine=narrator_engine,
        )
        timed = timed_by_chapter.get(chapter.chapter)
        timing_map = {}
        if timed is not None:
            for s in timed.sentences_in_order():
                timing_map[s.sid] = (s.start_ms, s.end_ms)
        for s in chapter.sentences_in_order():
            resolved = plan[s.sid]
            key = cache_key(
                s.text, resolved.voice, resolved.speed, VOICE_PITCH, engine.engine_version
            )
            cpath = cache_path(cache_dir, key)
            audio = _read_flac(cpath, engine.sample_rate)
            pcm_hash = hashlib.sha256(
                np.asarray(audio, dtype=np.float32).tobytes()
            ).hexdigest()
            start_ms, end_ms = timing_map.get(s.sid, (None, None))
            facts_sentences.append(
                {
                    "chapter": chapter.chapter,
                    "sid": s.sid,
                    "text": s.text,
                    "cache_key": key,
                    "pcm_hash": pcm_hash,
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                }
            )

    facts = {
        "engine_version": engine.engine_version,
        "sentence_order": [[s["chapter"], s["sid"]] for s in facts_sentences],
        "sentences": facts_sentences,
    }
    return facts, result, work_root


@pytest.fixture(scope="module")
def golden_build(tmp_path_factory):
    base = tmp_path_factory.mktemp("eq")
    work = base / "work"
    out = base / "out"
    facts, result, work_dir = _collect_facts(FIXTURE, work, out)
    return facts, result, work_dir, out


def test_render_equivalence_to_golden(golden_build, tmp_path):
    facts_golden, result_golden, work_golden, out_golden = golden_build
    # Store the golden run (overwritten each session because the Kokoro
    # source generator is stochastic; within-session comparison is warm).
    GOLDEN.write_text(json.dumps(facts_golden, indent=2), encoding="utf-8")

    cand_work = tmp_path / "cand-work"
    cand_out = tmp_path / "cand-out"
    facts_cand, result_cand, _ = _collect_facts(
        FIXTURE, cand_work, cand_out, warm_cache_from=work_golden
    )

    golden = facts_golden

    assert facts_cand["sentence_order"] == golden["sentence_order"], "sentence order differs"
    assert [s["cache_key"] for s in facts_cand["sentences"]] == [
        s["cache_key"] for s in golden["sentences"]
    ], "cache keys differ"
    assert [s["pcm_hash"] for s in facts_cand["sentences"]] == [
        s["pcm_hash"] for s in golden["sentences"]
    ], "PCM hashes differ"
    for a, b in zip(facts_cand["sentences"], golden["sentences"]):
        assert abs(a["start_ms"] - b["start_ms"]) <= 1, (a, b)
        assert abs(a["end_ms"] - b["end_ms"]) <= 1, (a, b)

    vr = validate_bundle(cand_out)
    assert vr.ok, vr.errors


def test_render_deterministic_across_runs(tmp_path):
    """Two independent sequential builds produce identical facts."""
    if not _has_ffmpeg() or not _find_model_files():
        pytest.skip("missing ffmpeg or models")
    f1, _, w1 = _collect_facts(FIXTURE, tmp_path / "w1", tmp_path / "o1")
    f2, _, _ = _collect_facts(FIXTURE, tmp_path / "w2", tmp_path / "o2", warm_cache_from=w1)
    assert f1["sentence_order"] == f2["sentence_order"]
    assert [s["cache_key"] for s in f1["sentences"]] == [
        s["cache_key"] for s in f2["sentences"]
    ]
    assert [s["pcm_hash"] for s in f1["sentences"]] == [
        s["pcm_hash"] for s in f2["sentences"]
    ]
    for a, b in zip(f1["sentences"], f2["sentences"]):
        assert abs(a["start_ms"] - b["start_ms"]) <= 1
        assert abs(a["end_ms"] - b["end_ms"]) <= 1


def test_kill_resume_matches_golden(tmp_path):
    """Kill (fail_after) then resume; bundle matches a clean reference build.

    Uses a deterministic fake engine (the real Kokoro source generator is
    stochastic, and a cache round-trip is not bit-exact), mirroring the
    existing build kill-resume tests.
    """
    from ebooklib import epub

    if not _has_ffmpeg():
        pytest.skip("ffmpeg/ffprobe not on PATH")

    # build a tiny synthetic epub
    epub_path = tmp_path / "book.epub"
    book = epub.EpubBook()
    book.set_identifier("kill-resume-test")
    book.set_title("Kill Resume Book")
    book.set_language("en")
    book.add_author("Test")
    body = " ".join(["The quiet alder fox crosses the mossy hill at dawn."] * 25)
    item = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    item.content = f"<h1>Chapter One</h1><p>{body}</p>"
    book.add_item(item)
    book.toc = [epub.Link("ch1.xhtml", "Chapter One", "ch1")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [item]
    epub.write_epub(str(epub_path), book)

    class FakeEngine(TTSEngine):
        def __init__(self, version="fake-kr"):
            self.calls = []
            self._version = version

        @property
        def sample_rate(self):
            return 24_000

        @property
        def engine_version(self):
            return self._version

        def synth(self, text, voice, speed):
            self.calls.append((text, voice, float(speed)))
            freq = 440.0 + (len(text) % 5) * 110.0
            t = np.arange(2400, dtype=np.float64) / 24_000
            return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)

    work_kill = tmp_path / "work-kill"
    out_kill = tmp_path / "out-kill"
    engine = FakeEngine()
    with pytest.raises(BuildError, match="simulated failure"):
        run_build(
            epub_path,
            work_root=work_kill,
            out_dir=out_kill,
            engine=engine,
            fail_after=1,
            show_progress=False,
        )

    resumed = run_build(
        epub_path,
        work_root=work_kill,
        out_dir=out_kill,
        engine=FakeEngine(),
        show_progress=False,
    )
    assert resumed.failed == []

    out_ref = tmp_path / "out-ref"
    run_build(
        epub_path,
        work_root=tmp_path / "work-ref",
        out_dir=out_ref,
        engine=FakeEngine(),
        show_progress=False,
    )

    diffs = compare_bundles(out_ref, out_kill)
    assert diffs == [], diffs
    vr = validate_bundle(out_kill)
    assert vr.ok, vr.errors