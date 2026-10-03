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

"""MV8: bundle voices map, validation, inspect --speakers, golden fixture.

Fake engines only except the golden contract test (real ffprobe, never
skipped) and the writer integration (real ffmpeg, skipped loudly when
absent like the other build tests).
"""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import numpy as np
import pytest
from ebooklib import epub
from typer.testing import CliRunner

import cli
from audio.assemble import assemble_chapter
from audio.encode import encode_assembled_chapter
from build import build_voices_map, remap_chapter_speakers
from bundle.inspect import format_inspect, inspect_bundle
from bundle.models import Block, ChapterFile, Sentence, Voice
from bundle.validate import AudioProbe, validate_bundle
from bundle.writer import write_bundle
from text.cast import ResolvedVoice

FIXTURE = Path(__file__).resolve().parents[2] / "spec" / "fixtures" / "multivoice-golden"

GOLDEN_BOOK_ID = "9e86414e-92b7-53e5-8a1f-e4ac7dc9dde9"
GOLDEN_DURATION_MS = 1550


def _needs_ffmpeg() -> None:
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("ffmpeg/ffprobe not on PATH (MV8 writer test needs real binaries)")


def _stub_probe(durations: dict[str, int] | None = None) -> object:
    durations = durations or {}

    def _probe(path: Path) -> AudioProbe:
        return AudioProbe(
            codec="mp3",
            channels=1,
            sample_rate=24000,
            bit_rate_bps=64000,
            bit_rate_from_stream=True,
            duration_ms=durations.get(Path(path).name, 3000),
            cbr_frames=True,
        )

    return _probe


def _sentence(
    sid: int,
    speaker: str,
    start_ms: int,
    end_ms: int,
    text: str = "Hello.",
    confidence: str = "high",
) -> dict:
    out: dict = {
        "sid": sid,
        "speaker": speaker,
        "start_ms": start_ms,
        "end_ms": end_ms,
        "text": text,
    }
    if confidence != "high":
        out["confidence"] = confidence
    return out


def _write_bundle(
    root: Path,
    sentences: list[dict],
    voices: dict,
    duration_ms: int = 3000,
    source_bytes: bytes = b"fake-epub",
) -> Path:
    (root / "audio").mkdir(parents=True, exist_ok=True)
    (root / "text").mkdir(parents=True, exist_ok=True)
    (root / "source").mkdir(parents=True, exist_ok=True)
    (root / "source" / "book.epub").write_bytes(source_bytes)
    manifest = {
        "spec_version": "1.0",
        "id": "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
        "title": "MV8 Test",
        "type": "epub",
        "source": {"file": "source/book.epub", "sha256": hashlib.sha256(source_bytes).hexdigest()},
        "audio": {
            "format": "mp3",
            "channels": 1,
            "sample_rate": 24000,
            "bitrate_kbps": 64,
            "cbr": True,
        },
        "voices": voices,
        "chapters": [
            {
                "index": 1,
                "title": "Chapter One",
                "audio": "audio/ch001.mp3",
                "text": "text/ch001.json",
                "duration_ms": duration_ms,
            }
        ],
    }
    (root / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    chapter = {
        "spec_version": "1.0",
        "chapter": 1,
        "title": "Chapter One",
        "duration_ms": duration_ms,
        "blocks": [{"id": 1, "type": "para", "sentences": sentences}],
    }
    (root / "text" / "ch001.json").write_text(json.dumps(chapter), encoding="utf-8")
    (root / "audio" / "ch001.mp3").write_bytes(b"")
    return root


def _two_voice_map() -> dict:
    return {
        "narrator": {"engine": "kokoro", "voice": "am_onyx", "speed": 1.0, "pitch": 1.0},
        "Alice": {"engine": "kokoro", "voice": "bf_isabella", "speed": 1.0, "pitch": 1.0},
    }


# ---------------------------------------------------------------------------
# Validator: valid multi-voice passes; unknown speaker/voice rejected
# ---------------------------------------------------------------------------


def test_validator_valid_multivoice_passes(tmp_path: Path) -> None:
    root = _write_bundle(
        tmp_path,
        [_sentence(1, "narrator", 0, 1500), _sentence(2, "Alice", 1500, 2800)],
        _two_voice_map(),
    )
    result = validate_bundle(root, probe=_stub_probe())
    assert result.ok, result.errors


def test_validator_unknown_speaker_rejected(tmp_path: Path) -> None:
    root = _write_bundle(tmp_path, [_sentence(1, "Ghost", 0, 2800)], _two_voice_map())
    result = validate_bundle(root, probe=_stub_probe())
    assert any("ch001.json" in e and "unknown speaker" in e and "Ghost" in e for e in result.errors)


def test_validator_unknown_voice_shape_rejected(tmp_path: Path) -> None:
    voices = _two_voice_map()
    voices["Alice"] = {"engine": "kokoro", "voice": "  ", "speed": 1.0, "pitch": 1.0}
    root = _write_bundle(tmp_path, [_sentence(1, "Alice", 0, 2800)], voices)
    result = validate_bundle(root, probe=_stub_probe())
    assert any(
        "manifest.json" in e and "voices.Alice.voice" in e and "unknown voice" in e
        for e in result.errors
    )


def test_validator_unknown_voice_against_list_rejected(tmp_path: Path) -> None:
    voices = _two_voice_map()
    voices["Alice"] = {"engine": "kokoro", "voice": "xx_nope", "speed": 1.0, "pitch": 1.0}
    root = _write_bundle(tmp_path, [_sentence(1, "Alice", 0, 2800)], voices)
    # Shape-only (scribe validate offline): non-empty voice passes shape.
    assert validate_bundle(root, probe=_stub_probe()).ok
    # Against the engine list: xx_nope is rejected, naming file+key+rule.
    result = validate_bundle(root, probe=_stub_probe(), known_voices=("am_onyx", "bf_isabella"))
    assert any(
        "manifest.json" in e and "voices.Alice.voice" in e and "xx_nope" in e for e in result.errors
    )


def test_validator_bad_speed_and_pitch_rejected(tmp_path: Path) -> None:
    voices = _two_voice_map()
    voices["Alice"] = {"engine": "kokoro", "voice": "bf_isabella", "speed": 0, "pitch": -1.0}
    root = _write_bundle(tmp_path, [_sentence(1, "Alice", 0, 2800)], voices)
    result = validate_bundle(root, probe=_stub_probe())
    joined = "\n".join(result.errors)
    assert "voices.Alice.speed" in joined
    assert "voices.Alice.pitch" in joined


def test_validator_unused_voices_are_not_an_error(tmp_path: Path) -> None:
    voices = _two_voice_map()
    voices["Robert"] = {"engine": "kokoro", "voice": "bm_lewis", "speed": 1.0, "pitch": 1.0}
    root = _write_bundle(tmp_path, [_sentence(1, "narrator", 0, 2800)], voices)
    result = validate_bundle(root, probe=_stub_probe())
    assert result.ok, result.errors


# ---------------------------------------------------------------------------
# Build helpers: voices map + speaker remap
# ---------------------------------------------------------------------------


def _cast() -> dict:
    return {
        "narrator": {"engine": "kokoro", "voice": "am_onyx", "speed": 1.0},
        "characters": {"Alice": {"voice": "bf_isabella", "speed": 1.0}},
        "aliases": {},
        "default_female": {"voice": "af_bella", "speed": 1.0},
        "default_male": {"voice": "am_adam", "speed": 1.0},
        "first_person": "narrator",
        "overrides": [],
    }


def test_build_voices_map_used_only_narrator_first() -> None:
    cast = _cast()
    plans = [
        {
            1: ResolvedVoice("narrator", "am_onyx", 1.0),
            2: ResolvedVoice("Alice", "bf_isabella", 1.0),
        },
        {1: ResolvedVoice("narrator", "am_onyx", 1.0)},
    ]
    voices = build_voices_map(cast, plans)
    assert list(voices) == ["narrator", "Alice"]
    assert voices["narrator"].voice == "am_onyx"
    assert voices["Alice"].voice == "bf_isabella"
    assert all(v.engine == "kokoro" and v.pitch == 1.0 for v in voices.values())


def test_build_voices_map_generics_collapsed_and_narrator_always() -> None:
    cast = _cast()
    plans = [{1: ResolvedVoice("default_female", "af_bella", 1.0)}]
    voices = build_voices_map(cast, plans)
    assert set(voices) == {"narrator", "default_female"}
    assert voices["default_female"].voice == "af_bella"


def test_remap_chapter_speakers_keeps_timings() -> None:
    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="One",
        duration_ms=1000,
        blocks=[
            Block(
                id=1,
                type="para",
                sentences=[
                    Sentence(
                        sid=1,
                        speaker="the Alice",
                        start_ms=0,
                        end_ms=500,
                        text="Hi.",
                        kind="dialogue",
                        confidence="low",
                    ),
                    Sentence(sid=2, speaker="unknown", start_ms=500, end_ms=900, text="Yo."),
                ],
            )
        ],
    )
    plan = {
        1: ResolvedVoice("Alice", "bf_isabella", 1.0),
        2: ResolvedVoice("default_female", "af_bella", 1.0),
    }
    remapped = remap_chapter_speakers(chapter, plan)
    got = remapped.sentences_in_order()
    assert [s.speaker for s in got] == ["Alice", "default_female"]
    assert [(s.start_ms, s.end_ms) for s in got] == [(0, 500), (500, 900)]
    assert [s.text for s in got] == ["Hi.", "Yo."]
    assert got[0].kind == "dialogue" and got[0].confidence == "low"


# ---------------------------------------------------------------------------
# Writer: explicit multi-voice map ships (real ffmpeg)
# ---------------------------------------------------------------------------


def _mini_epub(path: Path) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-mv8-writer")
    book.set_title("MV8 Writer Book")
    book.set_language("en")
    book.add_author("MV8 Author")
    ch1 = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    ch1.content = "<h1>Chapter One</h1><p>The rain had not stopped.</p>"
    book.add_item(ch1)
    book.toc = [epub.Link("ch1.xhtml", "Chapter One", "ch1")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [ch1]
    epub.write_epub(str(path), book)
    return path


def test_writer_explicit_voices_map_validates(tmp_path: Path) -> None:
    _needs_ffmpeg()
    epub_path = _mini_epub(tmp_path / "mini.epub")

    def _sine(sentence: Sentence) -> np.ndarray:
        t = np.arange(2400, dtype=np.float64) / 24_000
        return (0.4 * np.sin(2 * np.pi * 440.0 * t)).astype(np.float32)

    chapter = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter One",
        duration_ms=0,
        blocks=[
            Block(
                id=1,
                type="para",
                sentences=[
                    Sentence(sid=1, speaker="Alice", start_ms=0, end_ms=0, text="Hi."),
                    Sentence(sid=2, speaker="narrator", start_ms=0, end_ms=0, text="She left."),
                ],
            )
        ],
    )
    assembled = assemble_chapter(chapter, _sine)
    mp3 = tmp_path / "ch001.mp3"
    encode_assembled_chapter(assembled, mp3, chapter_label="ch001")
    timed_blocks = []
    timing_iter = iter(assembled.timings)
    for block in chapter.blocks or []:
        sentences = [
            Sentence(
                sid=s.sid, speaker=s.speaker, start_ms=t.start_ms, end_ms=t.end_ms, text=s.text
            )
            for s, t in zip(block.sentences, [next(timing_iter) for _ in block.sentences])
        ]
        timed_blocks.append(Block(id=block.id, type=block.type, sentences=sentences))
    timed = ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="Chapter One",
        duration_ms=assembled.duration_ms,
        blocks=timed_blocks,
    )

    bundle = tmp_path / "bundle"
    result = write_bundle(
        [timed],
        [mp3],
        epub_path,
        bundle,
        voices={
            "narrator": Voice(engine="kokoro", voice="am_onyx", speed=1.0, pitch=1.0),
            "Alice": {"engine": "kokoro", "voice": "bf_isabella", "speed": 1.0, "pitch": 1.0},
        },
    )
    assert result.chapters == 1
    manifest = json.loads((bundle / "manifest.json").read_text(encoding="utf-8"))
    assert set(manifest["voices"]) == {"narrator", "Alice"}
    assert manifest["voices"]["Alice"]["voice"] == "bf_isabella"
    assert validate_bundle(bundle).ok


# ---------------------------------------------------------------------------
# Inspect --speakers: lines, seconds, low-confidence
# ---------------------------------------------------------------------------


def test_inspect_speakers_detail_on_fixture() -> None:
    result = inspect_bundle(FIXTURE)
    assert result.speakers == {"narrator": 2, "Alice": 1}
    assert result.speaker_ms == {"narrator": 200, "Alice": 100}
    assert result.speaker_low == {"narrator": 0, "Alice": 0}

    text = format_inspect(result, speakers_detail=True)
    assert "narrator: 2 lines, 0.2s (200 ms)" in text
    assert "Alice: 1 line, 0.1s (100 ms)" in text
    assert "low-confidence" not in text

    legacy = format_inspect(result)
    assert "narrator: 2 sentences" in legacy
    assert "Alice: 1 sentence" in legacy


def test_inspect_speakers_detail_reports_low_confidence(tmp_path: Path) -> None:
    root = _write_bundle(
        tmp_path,
        [
            _sentence(1, "narrator", 0, 1000),
            _sentence(2, "Alice", 1000, 2000, text="Hi.", confidence="low"),
        ],
        _two_voice_map(),
    )
    result = inspect_bundle(root)
    assert result.speaker_low == {"narrator": 0, "Alice": 1}
    text = format_inspect(result, speakers_detail=True)
    assert "Alice: 1 line, 1.0s (1000 ms), 1 low-confidence line" in text
    assert "narrator: 1 line, 1.0s (1000 ms)" in text


def test_inspect_cli_speakers_flag() -> None:
    runner = CliRunner()
    plain = runner.invoke(cli.app, ["inspect", str(FIXTURE)])
    assert plain.exit_code == 0, plain.output
    assert "narrator: 2 sentences" in plain.output

    detailed = runner.invoke(cli.app, ["inspect", "--speakers", str(FIXTURE)])
    assert detailed.exit_code == 0, detailed.output
    assert "narrator: 2 lines, 0.2s (200 ms)" in detailed.output
    assert "Alice: 1 line, 0.1s (100 ms)" in detailed.output


# ---------------------------------------------------------------------------
# Golden fixture contract (real probe, never skipped)
# ---------------------------------------------------------------------------


def test_multivoice_golden_present() -> None:
    for rel in (
        "manifest.json",
        "README.md",
        "audio/ch001.mp3",
        "text/ch001.json",
        "source/book.epub",
    ):
        assert (FIXTURE / rel).is_file(), f"golden file missing: {rel}"


def test_multivoice_golden_pins() -> None:
    manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["id"] == GOLDEN_BOOK_ID
    assert manifest["spec_version"] == "1.0"
    assert manifest["type"] == "epub"
    assert len(manifest["chapters"]) == 1
    entry = manifest["chapters"][0]
    assert entry == {
        "index": 1,
        "title": "Chapter One",
        "audio": "audio/ch001.mp3",
        "text": "text/ch001.json",
        "duration_ms": GOLDEN_DURATION_MS,
    }
    assert set(manifest["voices"]) == {"narrator", "Alice"}
    assert manifest["voices"]["narrator"]["voice"] == "am_onyx"
    assert manifest["voices"]["Alice"]["voice"] == "bf_isabella"

    chapter = json.loads((FIXTURE / "text" / "ch001.json").read_text(encoding="utf-8"))
    sentences = [s for b in chapter["blocks"] for s in b.get("sentences", [])]
    assert [s["sid"] for s in sentences] == [1, 2, 3]
    assert [s["speaker"] for s in sentences] == ["narrator", "Alice", "narrator"]
    assert all(s["speaker"] in manifest["voices"] for s in sentences)
    for s in sentences:
        for draft_key in ("kind", "confidence", "quote", "split_pair"):
            assert draft_key not in s
    # Spacing round trip (MV9 readiness): split halves join exactly.
    assert sentences[1]["text"] + sentences[2]["text"] == '"We should leave," Alice said.'
    assert sentences[0]["spans"] == [{"start": 22, "end": 28, "style": "italic"}]


def test_multivoice_golden_valid_with_real_probe() -> None:
    if shutil.which("ffprobe") is None:
        raise AssertionError(
            "ffprobe not on PATH (winget install ffmpeg): the golden "
            "contract test must run the real probe, never skip it"
        )
    result = validate_bundle(FIXTURE)
    assert result.ok, result.errors
