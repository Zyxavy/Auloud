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

"""SW7: assembly/timings, loudness, and streaming CBR encode.

Unit tests use a fake synth of KNOWN sample lengths (multiples of 24
samples, so ms mapping is exact at 24 kHz). The integration test uses real
ffmpeg/ffprobe with NO mocks: two chapters encode to mono 24 kHz 64 kbps
CBR, pass the duration-agreement check, and pass the SW2 validator.
"""

from __future__ import annotations

import json
import shutil
from collections.abc import Callable
from pathlib import Path

import numpy as np
import pytest

from audio.assemble import (
    PAUSE_BREAK_MS,
    PAUSE_HEADING_MS,
    PAUSE_PARA_MS,
    PAUSE_SENTENCE_MS,
    PEAK_TARGET,
    AssembledChapter,
    apply_loudness_gain,
    assemble_chapter,
    pause_after_sentence,
    samples_for_ms,
)
from audio.encode import encode_assembled_chapter
from bundle.models import Block, ChapterFile, Sentence
from bundle.validate import AudioProbe, probe_audio_ffprobe, validate_bundle
from tts.base import SAMPLE_RATE

SR = SAMPLE_RATE


def _sentence(sid: int, text: str) -> Sentence:
    return Sentence(sid=sid, speaker="narrator", start_ms=0, end_ms=0, text=text)


def _chapter(blocks: list[Block], index: int = 1) -> ChapterFile:
    return ChapterFile(
        spec_version="1.0",
        chapter=index,
        title=f"Chapter {index}",
        duration_ms=0,
        blocks=blocks,
    )


def make_synth(lengths: dict[int, int]) -> tuple[Callable[[Sentence], np.ndarray], list[int]]:
    """Fake synth: ``sid -> sample count`` of constant 0.5 audio; records call order."""
    calls: list[int] = []

    def _synth(sentence: Sentence) -> np.ndarray:
        calls.append(sentence.sid)
        return np.full((lengths[sentence.sid],), 0.5, dtype=np.float32)

    return _synth, calls


def _sine(samples: int, freq: float = 440.0) -> np.ndarray:
    t = np.arange(samples, dtype=np.float64) / SR
    return (0.4 * np.sin(2 * np.pi * freq * t)).astype(np.float32)


# ---------------------------------------------------------------------------
# (a) timing arithmetic
# ---------------------------------------------------------------------------


def test_timing_arithmetic_exact_offsets() -> None:
    """Known lengths -> exact offsets; ordered, non-overlapping, end excludes pause."""
    chapter = _chapter(
        [
            Block(id=1, type="heading", level=1, text="Chapter One",
                  sentences=[_sentence(1, "Chapter One")]),
            Block(id=2, type="para",
                  sentences=[_sentence(2, "First."), _sentence(3, "Second.")]),
        ]
    )
    synth, calls = make_synth({1: 24_000, 2: 48_000, 3: 24_000})
    out = assemble_chapter(chapter, synth)

    assert calls == [1, 2, 3]  # one call per sentence, in order
    assert [(t.sid, t.start_ms, t.end_ms) for t in out.timings] == [
        (1, 0, 1000),      # 24 000 samples; 800 ms heading pause follows
        (2, 1800, 3800),   # 48 000 samples; 250 ms sentence pause follows
        (3, 4050, 5050),   # 24 000 samples; 500 ms para-final pause follows
    ]
    assert out.duration_ms == 5550  # includes the final 500 ms pause
    assert out.sample_count == 5550 * 24
    assert len(out.pcm) == out.sample_count

    # Ordered, non-overlapping, end-excludes-pause.
    prev_end = 0
    for timing in out.timings:
        assert timing.start_ms >= prev_end
        assert timing.start_ms < timing.end_ms
        prev_end = timing.end_ms
    assert out.timings[0].start_ms == 0
    assert out.timings[1].start_ms - out.timings[0].end_ms == PAUSE_HEADING_MS
    assert out.timings[2].start_ms - out.timings[1].end_ms == PAUSE_SENTENCE_MS
    assert out.duration_ms - out.timings[2].end_ms == PAUSE_PARA_MS
    # Sentence audio itself holds no pause: end-start == synth length in ms.
    assert out.timings[0].end_ms - out.timings[0].start_ms == 1000
    assert out.timings[1].end_ms - out.timings[1].start_ms == 2000


def test_sids_must_be_consecutive_from_1() -> None:
    chapter = _chapter([Block(id=1, type="para", sentences=[_sentence(5, "Hi.")])])
    synth, _ = make_synth({5: 2400})
    with pytest.raises(ValueError, match="sids out of order"):
        assemble_chapter(chapter, synth)


def test_empty_sentence_audio_rejected() -> None:
    chapter = _chapter([Block(id=1, type="para", sentences=[_sentence(1, "Hi.")])])
    synth, _ = make_synth({1: 0})
    with pytest.raises(ValueError, match="empty audio"):
        assemble_chapter(chapter, synth)


def test_leading_break_silence_dropped_first_starts_at_0() -> None:
    """Spec rule 1: nothing precedes the first sentence, so a leading break's
    silence is dropped from the audio (first start stays 0)."""
    chapter = _chapter(
        [
            Block(id=1, type="break"),
            Block(id=2, type="para", sentences=[_sentence(1, "One.")]),
        ]
    )
    synth, _ = make_synth({1: 2400})  # 100 ms of audio
    out = assemble_chapter(chapter, synth)
    assert out.timings[0].start_ms == 0
    assert out.timings[0].end_ms == 100
    # The dropped 1000 ms break pause is excluded; only 100 ms + para-final.
    assert out.duration_ms == 100 + PAUSE_PARA_MS


def test_leading_break_chapter_passes_sw2_validator(tmp_path: Path) -> None:
    """A leading-break chapter (silence dropped) validates clean under SW2."""
    chapter = _chapter(
        [
            Block(id=1, type="break"),
            Block(
                id=2,
                type="para",
                sentences=[_sentence(1, "One."), _sentence(2, "Two.")],
            ),
        ],
        index=1,
    )
    synth, _ = make_synth({1: 2400, 2: 2400})
    assembled = assemble_chapter(chapter, synth)

    bundle = tmp_path / "bundle"
    (bundle / "audio").mkdir(parents=True)
    (bundle / "text").mkdir(parents=True)
    (bundle / "audio" / "ch001.mp3").write_bytes(b"")  # content stubbed below
    (bundle / "text" / "ch001.json").write_text(
        json.dumps(_with_timings(chapter, assembled).to_dict(), ensure_ascii=False)
        + "\n",
        encoding="utf-8",
    )
    (bundle / "manifest.json").write_text(
        json.dumps({
            "spec_version": "1.0",
            "id": "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
            "title": "Leading Break",
            "type": "epub",
            "audio": {"format": "mp3", "channels": 1, "sample_rate": 24000,
                      "bitrate_kbps": 64, "cbr": True},
            "voices": {"narrator": {"engine": "kokoro", "voice": "af_heart",
                                    "speed": 1.0, "pitch": 1.0}},
            "chapters": [{
                "index": 1,
                "title": "Chapter 1",
                "audio": "audio/ch001.mp3",
                "text": "text/ch001.json",
                "duration_ms": assembled.duration_ms,
            }],
        }, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    def _stub_probe(path: Path) -> AudioProbe:
        return AudioProbe(
            codec="mp3",
            channels=1,
            sample_rate=24000,
            bit_rate_bps=64000,
            bit_rate_from_stream=True,
            duration_ms=assembled.duration_ms,
            cbr_frames=True,
        )

    result = validate_bundle(bundle, probe=_stub_probe)
    assert result.ok, result.errors


def test_degenerate_timing_raises_value_error_not_assertion() -> None:
    """1-sample audio rounds to start_ms == end_ms: must be ValueError."""
    chapter = _chapter([Block(id=1, type="para", sentences=[_sentence(1, "Hi.")])])
    synth, _ = make_synth({1: 1})
    with pytest.raises(ValueError, match="outside duration"):
        assemble_chapter(chapter, synth)


# ---------------------------------------------------------------------------
# (b) pause-length matrix
# ---------------------------------------------------------------------------


def test_pause_after_sentence_matrix() -> None:
    assert pause_after_sentence("heading", False) == 800
    assert pause_after_sentence("heading", True) == 800
    assert pause_after_sentence("para", False) == 250
    assert pause_after_sentence("para", True) == 500
    assert pause_after_sentence("quote", False) == 250
    assert pause_after_sentence("quote", True) == 500
    with pytest.raises(ValueError, match="break"):
        pause_after_sentence("break", True)


def _single_sentence_chapter(block_type: str, audio_samples: int = 2400) -> ChapterFile:
    if block_type == "heading":
        block = Block(id=1, type="heading", level=1, text="Title",
                      sentences=[_sentence(1, "Title")])
    else:
        block = Block(id=1, type=block_type, sentences=[_sentence(1, "Hello world.")])
    return _chapter([block])


@pytest.mark.parametrize(
    ("block_type", "expected_pause"),
    [("heading", PAUSE_HEADING_MS), ("para", PAUSE_PARA_MS), ("quote", PAUSE_PARA_MS)],
)
def test_block_final_pause(block_type: str, expected_pause: int) -> None:
    """Trailing pause = duration - end depends only on the block kind."""
    chapter = _single_sentence_chapter(block_type)
    synth, _ = make_synth({1: 2400})  # 100 ms of audio
    out = assemble_chapter(chapter, synth)
    assert out.timings[0].end_ms - out.timings[0].start_ms == 100
    assert out.duration_ms - out.timings[0].end_ms == expected_pause


def test_para_internal_pause_is_sentence_length() -> None:
    chapter = _chapter(
        [Block(id=1, type="para",
               sentences=[_sentence(1, "One."), _sentence(2, "Two.")])]
    )
    synth, _ = make_synth({1: 2400, 2: 2400})
    out = assemble_chapter(chapter, synth)
    assert out.timings[1].start_ms - out.timings[0].end_ms == PAUSE_SENTENCE_MS


def test_break_block_contributes_silence_without_timings() -> None:
    chapter = _chapter(
        [
            Block(id=1, type="para", sentences=[_sentence(1, "One.")]),
            Block(id=2, type="break"),
        ]
    )
    synth, calls = make_synth({1: 2400})  # 100 ms
    out = assemble_chapter(chapter, synth)
    assert calls == [1]  # break synthesizes nothing
    assert len(out.timings) == 1
    # 100 ms audio + 500 ms para-final + 1000 ms break silence.
    assert out.duration_ms == 100 + PAUSE_PARA_MS + PAUSE_BREAK_MS
    tail = out.pcm[600 * 24:]  # last 1000 ms
    assert tail.size == samples_for_ms(PAUSE_BREAK_MS)
    assert np.all(tail == 0)


# ---------------------------------------------------------------------------
# (c) loudness determinism
# ---------------------------------------------------------------------------


def test_loudness_deterministic_peak_met_duration_unchanged() -> None:
    pcm = _sine(SR)  # 1 s, peak 0.4
    first = apply_loudness_gain(pcm)
    second = apply_loudness_gain(pcm)
    assert np.array_equal(first, second)  # same in -> bit-identical out
    assert len(first) == len(pcm)  # duration unchanged
    assert float(np.max(np.abs(first))) == pytest.approx(PEAK_TARGET, abs=1e-6)
    # Input untouched (no in-place scaling).
    assert float(np.max(np.abs(pcm))) == pytest.approx(0.4, abs=1e-6)


def test_loudness_silence_and_empty_pass_through() -> None:
    silence = np.zeros(2400, dtype=np.float32)
    out = apply_loudness_gain(silence)
    assert np.array_equal(out, silence)
    assert len(apply_loudness_gain(np.zeros(0, dtype=np.float32))) == 0


def test_loudness_rejects_bad_target() -> None:
    with pytest.raises(ValueError, match="target_peak"):
        apply_loudness_gain(np.ones(8, dtype=np.float32), target_peak=0.0)


# ---------------------------------------------------------------------------
# (d) integration: real ffmpeg, no mocks + SW2 validator
# ---------------------------------------------------------------------------


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (integration needs real binaries)")


def _synth_sine(text_lengths: dict[str, int]) -> Callable[[Sentence], np.ndarray]:
    def _synth(sentence: Sentence) -> np.ndarray:
        return _sine(text_lengths[sentence.text], freq=440.0 + 110.0 * sentence.sid)

    return _synth


def _with_timings(chapter: ChapterFile, assembled: AssembledChapter) -> ChapterFile:
    """Copy ``chapter`` blocks, filling sentence timings from ``assembled`` in order."""
    timing_iter = iter(assembled.timings)
    blocks: list[Block] = []
    for block in chapter.blocks or []:
        sentences = [
            Sentence(
                sid=s.sid,
                speaker=s.speaker,
                start_ms=t.start_ms,
                end_ms=t.end_ms,
                text=s.text,
                spans=list(s.spans),
            )
            for s, t in zip(block.sentences, [next(timing_iter) for _ in block.sentences])
        ] if block.sentences else []
        blocks.append(
            Block(id=block.id, type=block.type, level=block.level,
                  text=block.text, sentences=sentences)
        )
    return ChapterFile(
        spec_version="1.0",
        chapter=chapter.chapter,
        title=chapter.title,
        duration_ms=assembled.duration_ms,
        blocks=blocks,
    )


def test_integration_two_chapters_encode_and_validate(tmp_path: Path) -> None:
    _needs_ffmpeg()
    ch1 = _chapter(
        [
            Block(id=1, type="heading", level=1, text="Chapter One",
                  sentences=[_sentence(1, "Chapter One")]),
            Block(id=2, type="para",
                  sentences=[_sentence(2, "The rain had not stopped."),
                             _sentence(3, "We should leave.")]),
        ],
        index=1,
    )
    ch2 = _chapter(
        [
            Block(id=1, type="para", sentences=[_sentence(1, "Dawn broke.")]),
            Block(id=2, type="break"),
            Block(id=3, type="para", sentences=[_sentence(2, "Birds sang.")]),
        ],
        index=2,
    )
    synth = _synth_sine({
        "Chapter One": int(1.0 * SR),
        "The rain had not stopped.": int(1.2 * SR),
        "We should leave.": int(0.9 * SR),
        "Dawn broke.": int(0.8 * SR),
        "Birds sang.": int(1.1 * SR),
    })
    chapters = [ch1, ch2]
    assembled_list = [assemble_chapter(c, synth) for c in chapters]

    bundle = tmp_path / "bundle"
    audio_dir = bundle / "audio"
    text_dir = bundle / "text"
    audio_dir.mkdir(parents=True)
    text_dir.mkdir(parents=True)

    manifest_chapters = []
    for i, (chapter, assembled) in enumerate(zip(chapters, assembled_list), start=1):
        label = f"ch{i:03d}"
        loud = apply_loudness_gain(assembled.pcm)
        assert len(loud) == len(assembled.pcm)
        mp3 = audio_dir / f"{label}.mp3"
        probe_ms = encode_assembled_chapter(
            AssembledChapter(pcm=loud, timings=assembled.timings,
                             duration_ms=assembled.duration_ms,
                             sample_count=assembled.sample_count,
                             sample_rate=assembled.sample_rate),
            mp3,
            chapter_label=label,
        )
        delta = abs(probe_ms - assembled.duration_ms)
        print(f"INTEGRATION {label}: expected={assembled.duration_ms} "
              f"probe={probe_ms} delta={delta}")
        assert delta <= 50

        probe = probe_audio_ffprobe(mp3)
        assert probe.codec == "mp3"
        assert probe.channels == 1
        assert probe.sample_rate == 24000
        assert probe.bit_rate_bps == 64000
        assert probe.cbr_frames

        timed = _with_timings(chapter, assembled)
        (text_dir / f"{label}.json").write_text(
            json.dumps(timed.to_dict(), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        manifest_chapters.append({
            "index": i,
            "title": chapter.title,
            "audio": f"audio/{label}.mp3",
            "text": f"text/{label}.json",
            "duration_ms": assembled.duration_ms,
        })

    (bundle / "manifest.json").write_text(
        json.dumps({
            "spec_version": "1.0",
            "id": "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
            "title": "SW7 Mini Book",
            "type": "epub",
            "audio": {"format": "mp3", "channels": 1, "sample_rate": 24000,
                      "bitrate_kbps": 64, "cbr": True},
            "voices": {"narrator": {"engine": "kokoro", "voice": "af_heart",
                                    "speed": 1.0, "pitch": 1.0}},
            "chapters": manifest_chapters,
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    result = validate_bundle(bundle)  # real ffprobe, no stub
    assert result.ok, result.errors
