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

"""MV7: multi-voice rendering and leveling.

Fake engine only (fast, no models): dialogue split-pair links, exact
assembly offsets with mixed voices and the ~100 ms tag pause,
per-voice peak leveling with specific numbers, determinism, the
sentence-audio cache key (voice+speed) with hit/miss stats, build-time
resolution (gender hint re-derived, ``unknown`` never crashes), the
per-sentence render fingerprint (a cast edit re-renders exactly the
chapters holding affected sentences), and rejection of unknown voices
against the engine's real voice list. Encode-dependent tests skip loudly
when ffmpeg/ffprobe are missing.
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

import numpy as np
import pytest
from ebooklib import epub

from audio.assemble import (
    PAUSE_PARA_MS,
    PAUSE_SENTENCE_MS,
    PAUSE_TAG_MS,
    PEAK_TARGET,
    assemble_chapter,
    gains_for_peaks,
    voice_peak_levels,
)
from build import (
    BuildError,
    BuildResult,
    _fingerprint,
    _with_timings,
    ensure_script,
    format_summary,
    resolve_chapter,
    resolve_sentence_voice,
    run_build,
)
from bundle.models import Block, ChapterFile, Sentence
from bundle.validate import validate_bundle
from bundle.writer import VOICE_PITCH
from draft import book_id_for_file, run_draft
from text.cast import read_cast, write_cast
from text.dialogue import split_paragraph_dialogue
from tts.base import TTSEngine
from tts.cache import CacheStats, cache_key, cache_path, get_or_synth

SR = 24_000


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (build tests need real binaries)")


class VoicesEngine(TTSEngine):
    """Fake engine with a REAL voice list (MV7 validates against it)."""

    def __init__(
        self,
        voices: tuple[str, ...] = (
            "af_bella",
            "af_heart",
            "am_adam",
            "am_eric",
            "am_onyx",
            "bf_isabella",
            "bm_lewis",
            "im_nicola",
            "jf_alpha",
            "zf_xiaoxiao",
        ),
        version: str = "fake-mv7",
    ) -> None:
        self.calls: list[tuple[str, str, float]] = []
        self._voices = tuple(voices)
        self._version = version

    @property
    def sample_rate(self) -> int:
        return SR

    @property
    def engine_version(self) -> str:
        return self._version

    @property
    def voices(self) -> tuple[str, ...]:
        return self._voices

    def synth(self, text: str, voice: str, speed: float) -> np.ndarray:
        self.calls.append((text, voice, float(speed)))
        # Deterministic across processes AND voice-sensitive (like a real
        # engine): the same text in another voice is different audio.
        freq = 440.0 + (len(text) % 5) * 110.0 + (sum(ord(char) for char in voice) % 7) * 13.0
        t = np.arange(2400, dtype=np.float64) / SR
        return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


def _cast() -> dict:
    return {
        "narrator": {"engine": "kokoro", "voice": "v_narr", "speed": 1.0},
        "characters": {"Alice": {"voice": "v_alice", "speed": 1.1}},
        "aliases": {},
        "default_female": {"voice": "v_fem", "speed": 1.0},
        "default_male": {"voice": "v_male", "speed": 1.0},
        "first_person": "narrator",
        "overrides": [],
    }


def _sentence(
    sid: int,
    text: str,
    *,
    kind: str = "narration",
    speaker: str = "narrator",
    pair: int | None = None,
    quote: dict | None = None,
) -> Sentence:
    return Sentence(
        sid=sid,
        speaker=speaker,
        start_ms=0,
        end_ms=0,
        text=text,
        kind=kind,
        confidence="high",
        quote=quote,
        split_pair=pair,
    )


def _quote_key(chapter: int = 1, block: int = 2, quote: int = 1) -> dict:
    return {"chapter": chapter, "block": block, "quote": quote}


# ---------------------------------------------------------------------------
# (a) dialogue split pairs: same-sentence tags link, new sentences do not
# ---------------------------------------------------------------------------


def test_split_pair_tag_after_quote() -> None:
    result = split_paragraph_dialogue('"We should leave," she said.', chapter=1, block=2)
    assert len(result.sentences) == 2
    dialogue, tag = result.sentences
    assert dialogue.kind == "dialogue" and tag.kind == "narration"
    assert dialogue.split_pair is not None
    assert tag.split_pair == dialogue.split_pair


def test_split_pair_new_sentence_has_no_pair() -> None:
    result = split_paragraph_dialogue('"Hello." She smiled.', chapter=1, block=2)
    assert len(result.sentences) == 2
    assert all(s.split_pair is None for s in result.sentences)


def test_split_pair_tag_before_quote() -> None:
    result = split_paragraph_dialogue('She said, "Hi."', chapter=1, block=2)
    assert len(result.sentences) == 2
    tag, dialogue = result.sentences
    assert tag.kind == "narration" and dialogue.kind == "dialogue"
    assert tag.split_pair is not None
    assert dialogue.split_pair == tag.split_pair


def test_split_pair_two_quotes_one_sentence_share_one_id() -> None:
    result = split_paragraph_dialogue('"Hi," she said, "bye."', chapter=1, block=2)
    assert len(result.sentences) == 3
    pairs = {s.split_pair for s in result.sentences}
    assert len(pairs) == 1 and None not in pairs


def test_split_pair_tag_after_exclamation() -> None:
    result = split_paragraph_dialogue('"Run!" he shouted.', chapter=1, block=2)
    assert len(result.sentences) == 2
    first, second = result.sentences
    assert first.split_pair is not None
    assert second.split_pair == first.split_pair


# ---------------------------------------------------------------------------
# (b) assembly: exact tag-pause offsets, mixed voices, leveling, determinism
# ---------------------------------------------------------------------------


def _const_synth(levels: dict[int, tuple[int, float]]):
    """Fake synth: ``sid -> (sample count, constant amplitude)``."""

    def _synth(sentence: Sentence) -> np.ndarray:
        count, level = levels[sentence.sid]
        return np.full((count,), level, dtype=np.float32)

    return _synth


def test_tag_pause_exact_offsets_mixed_voices() -> None:
    """D(tag-pair) + N(tag-pair) take 100 ms; everything else is normal."""
    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter 1",
        duration_ms=0,
        blocks=[
            Block(
                id=2,
                type="para",
                sentences=[
                    _sentence(
                        1, '"Leave,"', kind="dialogue", speaker="Alice", pair=1, quote=_quote_key()
                    ),
                    _sentence(2, " she said.", pair=1),
                    _sentence(
                        3, '"Later."', kind="dialogue", speaker="Bob", quote=_quote_key(quote=2)
                    ),
                ],
            )
        ],
    )
    synth = _const_synth({1: (SR, 0.2), 2: (SR // 2, 0.8), 3: (SR, 0.2)})
    voices = {1: "v_alice", 2: "v_narr", 3: "v_bob"}
    out = assemble_chapter(chapter, synth, voice_of=lambda s: voices[s.sid])

    assert [(t.sid, t.start_ms, t.end_ms) for t in out.timings] == [
        (1, 0, 1000),
        (2, 1100, 1600),  # 100 ms tag pause after sentence 1
        (3, 1850, 2850),  # 250 ms sentence pause after sentence 2
    ]
    assert out.duration_ms == 3350  # trailing 500 ms paragraph pause
    assert out.timings[1].start_ms - out.timings[0].end_ms == PAUSE_TAG_MS == 100
    assert out.timings[2].start_ms - out.timings[1].end_ms == PAUSE_SENTENCE_MS
    assert out.duration_ms - out.timings[2].end_ms == PAUSE_PARA_MS

    # Ordered, non-overlapping, end-excludes-pause.
    prev_end = 0
    for timing in out.timings:
        assert timing.start_ms >= prev_end
        assert timing.start_ms < timing.end_ms <= out.duration_ms
        prev_end = timing.end_ms

    # Leveling rode along (different amplitudes per voice) without moving
    # any boundary: gains are exact peak ratios.
    assert out.voice_gains["v_alice"] == pytest.approx(PEAK_TARGET / 0.2)
    assert out.voice_gains["v_narr"] == pytest.approx(PEAK_TARGET / 0.8)
    assert out.voice_gains["v_bob"] == pytest.approx(PEAK_TARGET / 0.2)


def test_tag_pause_before_quote_exact_offsets() -> None:
    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter 1",
        duration_ms=0,
        blocks=[
            Block(
                id=2,
                type="para",
                sentences=[
                    _sentence(1, "She said, ", pair=1),
                    _sentence(
                        2, '"Hi."', kind="dialogue", speaker="Alice", pair=1, quote=_quote_key()
                    ),
                ],
            )
        ],
    )
    out = assemble_chapter(chapter, _const_synth({1: (SR // 2, 0.5), 2: (SR, 0.5)}))
    assert [(t.sid, t.start_ms, t.end_ms) for t in out.timings] == [
        (1, 0, 500),
        (2, 600, 1600),
    ]
    assert out.duration_ms == 2100
    assert out.voice_gains == {}  # no voice_of: Slice 2 path, gains untouched


def test_leveling_specific_numbers_and_peak_not_silence() -> None:
    """Peak gains are exact ratios; a silence-only voice keeps gain 1.0."""
    gains = gains_for_peaks({"v_loud": 0.8, "v_quiet": 0.2, "v_silent": 0.0})
    assert gains["v_loud"] == pytest.approx(PEAK_TARGET / 0.8)
    assert gains["v_quiet"] == pytest.approx(PEAK_TARGET / 0.2)
    assert gains["v_silent"] == 1.0
    assert voice_peak_levels({"v": [np.zeros(10, dtype=np.float32)], "w": []}) == {
        "v": 0.0,
        "w": 0.0,
    }


def test_assemble_deterministic_same_inputs_identical() -> None:
    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter 1",
        duration_ms=0,
        blocks=[
            Block(
                id=2,
                type="para",
                sentences=[
                    _sentence(
                        1, '"Hi,"', kind="dialogue", speaker="Alice", pair=1, quote=_quote_key()
                    ),
                    _sentence(2, " she said.", pair=1),
                ],
            )
        ],
    )
    voices = {1: "v_alice", 2: "v_narr"}
    first = assemble_chapter(
        chapter,
        _const_synth({1: (SR, 0.3), 2: (SR // 2, 0.6)}),
        voice_of=lambda s: voices[s.sid],
    )
    second = assemble_chapter(
        chapter,
        _const_synth({1: (SR, 0.3), 2: (SR // 2, 0.6)}),
        voice_of=lambda s: voices[s.sid],
    )
    assert np.array_equal(first.pcm, second.pcm)  # bit-identical audio
    assert [(t.sid, t.start_ms, t.end_ms) for t in first.timings] == [
        (t.sid, t.start_ms, t.end_ms) for t in second.timings
    ]
    assert first.voice_gains == second.voice_gains
    # Both voices now peak at the shared target: comparable loudness.
    for timing, sid in zip(first.timings, (1, 2)):
        segment = first.pcm[timing.start_ms * 24 : timing.end_ms * 24]
        assert float(np.max(np.abs(segment))) == pytest.approx(PEAK_TARGET, abs=1e-6)


# ---------------------------------------------------------------------------
# (c) cache: key holds voice+speed; stats count hits and misses
# ---------------------------------------------------------------------------


def test_cache_key_same_text_different_voice_two_entries(tmp_path: Path) -> None:
    engine = VoicesEngine()
    cache_dir = tmp_path / "cache"
    get_or_synth(engine, "Hello world.", "v_a", 1.0, 1.0, cache_dir)
    get_or_synth(engine, "Hello world.", "v_b", 1.0, 1.0, cache_dir)
    assert len(engine.calls) == 2  # two misses: voice is part of the key
    assert len(list(cache_dir.glob("*.flac"))) == 2
    get_or_synth(engine, "Hello world.", "v_a", 1.0, 1.0, cache_dir)
    assert len(engine.calls) == 2  # hit: no new synth
    get_or_synth(engine, "Hello world.", "v_a", 1.2, 1.0, cache_dir)
    assert len(engine.calls) == 3  # speed is part of the key too
    assert cache_key("Hello world.", "v_a", 1.0, 1.0, engine.engine_version) != cache_key(
        "Hello world.", "v_b", 1.0, 1.0, engine.engine_version
    )


def test_cache_stats_counts_hits_and_misses(tmp_path: Path) -> None:
    engine = VoicesEngine()
    cache_dir = tmp_path / "cache"
    stats = CacheStats()
    get_or_synth(engine, "Hi.", "v", 1.0, 1.0, cache_dir, stats=stats)
    get_or_synth(engine, "Hi.", "v", 1.0, 1.0, cache_dir, stats=stats)
    get_or_synth(engine, "Yo.", "v", 1.0, 1.0, cache_dir, stats=stats)
    assert (stats.hits, stats.misses) == (1, 2)


# ---------------------------------------------------------------------------
# (d) resolution: narration, characters, generics, overrides, unknowns
# ---------------------------------------------------------------------------


def _resolve(cast: dict, sentence: Sentence):
    return resolve_sentence_voice(
        cast, sentence, chapter_index=1, narrator_voice="v_narr", narrator_speed=1.0
    )


def test_resolve_narration_takes_narrator() -> None:
    resolved = _resolve(_cast(), _sentence(1, "The night was dark."))
    assert (resolved.character, resolved.voice, resolved.speed) == ("narrator", "v_narr", 1.0)


def test_resolve_character_voice_and_speed() -> None:
    sentence = _sentence(2, '"Hello,"', kind="dialogue", speaker="Alice", quote=_quote_key())
    resolved = _resolve(_cast(), sentence)
    assert (resolved.character, resolved.voice, resolved.speed) == ("Alice", "v_alice", 1.1)


def test_resolve_unknown_speaker_falls_to_generic_never_crashes() -> None:
    for raw in ("unknown", "", "  ", "Some Stranger"):
        sentence = _sentence(2, '"Hm."', kind="dialogue", speaker=raw, quote=_quote_key())
        resolved = _resolve(_cast(), sentence)
        assert resolved.character in ("default_female", "default_male")
        assert resolved.voice in ("v_fem", "v_male")


def test_gender_hint_rederived_at_build_no_stored_hint() -> None:
    """Script sentences carry no gender hint: build re-derives it.

    Decision pin: ``Robert`` (male name list) resolves to the male
    generic and ``unknown`` to the female generic from speaker + text
    alone, with no spaCy and no ``script.json`` shape change.
    """
    cast = _cast()
    male = _sentence(2, '"I agree."', kind="dialogue", speaker="Robert", quote=_quote_key())
    assert _resolve(cast, male).character == "default_male"
    unknown = _sentence(
        3, '"Bare line."', kind="dialogue", speaker="unknown", quote=_quote_key(quote=2)
    )
    assert _resolve(cast, unknown).character == "default_female"


def test_resolve_quote_override_beats_raw_speaker() -> None:
    cast = _cast()
    cast["overrides"] = [{"chapter": 1, "block": 2, "quote": 1, "speaker": "Alice"}]
    sentence = _sentence(2, '"Hm."', kind="dialogue", speaker="Bob", quote=_quote_key())
    assert _resolve(cast, sentence).character == "Alice"


def test_resolve_first_person_goes_to_narrator() -> None:
    sentence = _sentence(2, '"I said."', kind="dialogue", speaker="I", quote=_quote_key())
    assert _resolve(_cast(), sentence).character == "narrator"


def test_resolve_chapter_covers_every_sentence_in_order() -> None:
    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter 1",
        duration_ms=0,
        blocks=[
            Block(
                id=1,
                type="para",
                sentences=[
                    _sentence(1, "Narration."),
                    _sentence(2, '"Hi,"', kind="dialogue", speaker="Alice", quote=_quote_key()),
                ],
            )
        ],
    )
    plan = resolve_chapter(_cast(), chapter, narrator_voice="v_narr", narrator_speed=1.0)
    assert sorted(plan) == [1, 2]
    assert plan[1].character == "narrator"
    assert plan[2].character == "Alice"


def test_with_timings_preserves_draft_fields() -> None:
    from audio.assemble import SentenceTiming

    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter 1",
        duration_ms=0,
        blocks=[
            Block(
                id=2,
                type="para",
                sentences=[
                    _sentence(
                        1, '"Hi,"', kind="dialogue", speaker="Alice", pair=7, quote=_quote_key()
                    ),
                    _sentence(2, " she said.", pair=7),
                ],
            )
        ],
    )
    timed = _with_timings(
        chapter,
        [
            SentenceTiming(sid=1, start_ms=0, end_ms=1000),
            SentenceTiming(sid=2, start_ms=1100, end_ms=1600),
        ],
        2100,
    )
    first, second = timed.sentences_in_order()
    assert (first.start_ms, first.end_ms) == (0, 1000)
    assert (second.start_ms, second.end_ms) == (1100, 1600)
    assert timed.duration_ms == 2100
    assert first.kind == "dialogue" and first.quote == _quote_key()
    assert (first.split_pair, second.split_pair) == (7, 7)


# ---------------------------------------------------------------------------
# (e) fingerprint: a cast edit moves exactly the affected sentences
# ---------------------------------------------------------------------------


def _dialogue_epub(path: Path) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-mv7-book")
    book.set_title("MV7 Dialogue Book")
    book.set_language("en")
    book.add_author("MV7 Author")

    long_para = " ".join(["The quiet alder fox crosses the mossy hill at dawn."] * 25)
    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = (
        "<h1>Chapter One</h1>"
        f"<p>{long_para}</p>"
        '<p>"Hello," Alice said.</p>'
        '<p>"Hi," Robert said.</p>'
        '<p>"How are you?"</p>'
        '<p>"Fine, thanks."</p>'
    )
    ch2 = epub.EpubHtml(title="Chapter Two", file_name="ch2.xhtml", lang="en")
    ch2.content = f'<h1>Chapter Two</h1><p>{long_para}</p><p>"Bare first."</p><p>"Second bare."</p>'
    for item in (ch1, ch2):
        book.add_item(item)
    book.toc = [
        epub.Link("ch1.xhtml", "Chapter One", "ch1"),
        epub.Link("ch2.xhtml", "Chapter Two", "ch2"),
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [ch1, ch2]
    epub.write_epub(str(path), book)
    return path


def _script_chapters(work_dir: Path, book_id: str) -> list[ChapterFile]:
    from draft import SCRIPT_FILENAME

    data = json.loads((work_dir / book_id / SCRIPT_FILENAME).read_text(encoding="utf-8"))
    return [ChapterFile.from_dict(c) for c in data["chapters"]]


def test_fingerprint_cast_edit_moves_only_affected_sentences(tmp_path: Path) -> None:
    epub_path = _dialogue_epub(tmp_path / "dialogue.epub")
    result = run_draft(epub_path, work_root=tmp_path / "work")
    cast = read_cast(result.cast_path)
    assert "Alice" in cast["characters"]
    chapters = _script_chapters(tmp_path / "work", result.book_id)
    engine = VoicesEngine()

    before = [_fingerprint(cast, engine, chapter) for chapter in chapters]
    cast["characters"]["Alice"]["voice"] = "zf_xiaoxiao"
    after = [_fingerprint(cast, engine, chapter) for chapter in chapters]

    assert before[1] == after[1]  # chapter 2 holds no Alice lines: untouched
    assert before[0] != after[0]  # chapter 1 holds Alice lines: re-renders

    rows_before = {tuple(row) for row in before[0]["voices"]}
    rows_after = {tuple(row) for row in after[0]["voices"]}
    changed = rows_before ^ rows_after
    assert changed  # exactly Alice's sentences changed voice
    for row in changed:
        assert row[1] == "Alice" and row[2] in ("bf_isabella", "zf_xiaoxiao")


# ---------------------------------------------------------------------------
# (f) build: cast-edit round trip, unknown-voice rejection
# ---------------------------------------------------------------------------


def _chapter_texts(work_dir: Path, book_id: str, index: int) -> set[str]:
    chapters = _script_chapters(work_dir, book_id)
    return {s.text for s in chapters[index - 1].sentences_in_order()}


def _assert_multivoice_bundle(out: Path) -> None:
    """MV8 bundle-success asserts: manifest + text JSON carry resolved keys."""
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    voices = manifest["voices"]
    assert "narrator" in voices
    assert "Alice" in voices
    for name, entry in voices.items():
        assert entry["engine"] == "kokoro"
        assert isinstance(entry["voice"], str) and entry["voice"].strip()
        assert float(entry["speed"]) > 0
    # Every sentence speaker exists in voices; no raw fallback ships.
    for chapter_file in sorted((out / "text").glob("ch*.json")):
        chapter = json.loads(chapter_file.read_text(encoding="utf-8"))
        for block in chapter.get("blocks", []):
            for sentence in block.get("sentences", []):
                assert sentence["speaker"] in voices, (
                    f"{chapter_file.name}: speaker {sentence['speaker']!r} not in manifest voices"
                )
                assert sentence["speaker"] != "unknown"
                for draft_key in ("kind", "confidence", "quote", "split_pair"):
                    assert draft_key not in sentence
    check = validate_bundle(out)
    assert check.ok, check.errors


def test_build_cast_edit_rerenders_only_affected_render(tmp_path: Path) -> None:
    """Render X, edit Alice's voice, rebuild: only chapter 1 re-renders.

    MV8: the bundle writer now ships the resolved voices map (narrator +
    characters + used generics) with resolved character keys per sentence,
    so both builds succeed end to end and validate. Render assertions are
    MV7's (per-chapter skip/re-render, cache hits/misses, new entries).
    """
    _needs_ffmpeg()
    epub_path = _dialogue_epub(tmp_path / "dialogue.epub")
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    first = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=VoicesEngine(),
        show_progress=False,
    )
    assert isinstance(first, BuildResult)
    assert (first.rendered, first.skipped) == (2, 0)
    _assert_multivoice_bundle(out)
    book_id, _ = book_id_for_file(epub_path)
    render = work_root / book_id / "render"
    assert (render / "audio" / "ch001.mp3").is_file()
    assert (render / "audio" / "ch002.mp3").is_file()
    ch2_mp3_before = (render / "audio" / "ch002.mp3").read_bytes()
    ch2_json_before = (render / "text" / "ch002.json").read_bytes()
    ch1_mp3_before = (render / "audio" / "ch001.mp3").read_bytes()

    cast = read_cast(work_root / book_id / "cast.yaml")
    cast["characters"]["Alice"]["voice"] = "zf_xiaoxiao"
    write_cast(work_root / book_id / "cast.yaml", cast)

    resumer = VoicesEngine()
    second = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        engine=resumer,
        show_progress=False,
    )
    assert isinstance(second, BuildResult)
    assert (second.rendered, second.skipped) == (1, 1)
    _assert_multivoice_bundle(out)
    # Chapter 2 holds no Alice lines: skipped, artifacts bit-identical,
    # and no synth call for any of its texts.
    assert (render / "audio" / "ch002.mp3").read_bytes() == ch2_mp3_before
    assert (render / "text" / "ch002.json").read_bytes() == ch2_json_before
    ch2_texts = _chapter_texts(work_root, book_id, 2)
    assert all(call[0] not in ch2_texts for call in resumer.calls)
    # Chapter 1 holds Alice lines: re-rendered with a new voice.
    assert (render / "audio" / "ch001.mp3").read_bytes() != ch1_mp3_before
    chapters = _script_chapters(work_root, book_id)
    narrator = cast["narrator"]
    plan = resolve_chapter(
        cast,
        chapters[0],
        narrator_voice=narrator["voice"],
        narrator_speed=float(narrator["speed"]),
    )
    alice = [
        sentence
        for sentence in chapters[0].sentences_in_order()
        if plan[sentence.sid].character == "Alice"
    ]
    assert alice
    assert {call[0] for call in resumer.calls} == {s.text for s in alice}
    assert {call[1] for call in resumer.calls} == {"zf_xiaoxiao"}
    # The new voice left new cache entries alongside the old ones.
    cache_dir = work_root / book_id / "cache"
    for sentence in alice:
        resolved = plan[sentence.sid]
        key = cache_key(
            sentence.text,
            resolved.voice,
            resolved.speed,
            VOICE_PITCH,
            resumer.engine_version,
        )
        assert cache_path(cache_dir, key).is_file()

    # A cast edit is a re-render, never a re-draft.
    assert ensure_script(epub_path, work_root=work_root).redrafted is False


def test_summary_prints_cache_hits_and_misses(tmp_path: Path) -> None:
    """``format_summary`` prints the MV7 ``X cached, Y rendered`` counts."""
    result = BuildResult(
        book_id="book",
        title="Title",
        work_dir=tmp_path,
        bundle_dir=tmp_path / "out",
        log_path=tmp_path / "scribe.log",
        chapters_total=2,
        rendered=1,
        skipped=1,
        total_sentences=10,
        total_audio_ms=5000,
        wall_seconds=2.0,
        rtf=2.5,
        cache_hits=7,
        cache_misses=2,
    )
    text = format_summary(result)
    assert "7 cached" in text
    assert "2 rendered" in text


def test_build_rejects_unknown_voice_against_real_list(tmp_path: Path) -> None:
    epub_path = _dialogue_epub(tmp_path / "dialogue.epub")
    work_root = tmp_path / "work"
    result = run_draft(epub_path, work_root=work_root)
    cast = read_cast(result.cast_path)
    cast["characters"]["Alice"]["voice"] = "xx_nope"
    write_cast(result.cast_path, cast)

    with pytest.raises(BuildError) as excinfo:
        run_build(
            epub_path,
            work_root=work_root,
            out_dir=tmp_path / "out",
            engine=VoicesEngine(),
            show_progress=False,
        )
    message = str(excinfo.value)
    assert "cast.yaml" in message  # file
    assert "characters.Alice.voice" in message  # key
    assert "unknown voice" in message and "xx_nope" in message  # rule


def test_run_draft_narration_tag_keeps_split_pair(tmp_path: Path) -> None:
    """Narration tags from ``run_draft`` keep the MV7 same-sentence link.

    Regression for the MV7 review H1: ``_apply_attribution`` gave the
    dialogue half its ``split_pair`` but built narration sentences without
    it, so narration tags always lost the link and assembly never took
    the tag pause on real books. Lowercase pronoun tag (``she said``)
    is used because MV2 only links continuation tags, not new sentences.
    """
    book = epub.EpubBook()
    book.set_identifier("test-mv7-tag-book")
    book.set_title("MV7 Tag Book")
    book.set_language("en")
    book.add_author("MV7 Author")
    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = '<h1>Chapter One</h1><p>"Hello," she said.</p>'
    book.add_item(ch1)
    book.toc = [epub.Link("ch1.xhtml", "Chapter One", "ch1")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [ch1]
    epub_path = tmp_path / "tag.epub"
    epub.write_epub(str(epub_path), book)

    result = run_draft(epub_path, work_root=tmp_path / "work")
    chapters = _script_chapters(tmp_path / "work", result.book_id)
    assert len(chapters) == 1
    sentences = chapters[0].sentences_in_order()
    dialogue = next(s for s in sentences if "Hello" in s.text)
    assert dialogue.kind == "dialogue"
    assert dialogue.split_pair is not None
    tag = next(
        s for s in sentences if s.sid != dialogue.sid and s.split_pair == dialogue.split_pair
    )
    assert tag.kind == "narration"
    assert "she said" in tag.text
