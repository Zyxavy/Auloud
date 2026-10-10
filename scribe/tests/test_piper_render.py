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

"""Slice 8 SW4: mixed-engine chapter render proof (slow, needs real models).

Kokoro narrator + Piper character through the unchanged build/assemble
path: the bundle validates, per-sentence audio windows are non-silent in
the shipped MP3 (every voice actually rendered, none muted by leveling),
the peak stays capped (loudness), and both engine versions appear in the
build log (cache separation by engine version).

Skips cleanly when any piece is absent: Kokoro pair, the Piper pair, or
ffmpeg/ffprobe. The Piper pair lives in ``models/piper/`` (gitignored,
see `scribe doctor --models-dir`). Voice license: ``en_US-lessac-low``
carries the Blizzard 2013 research/non-commercial dataset license — used
here for local proof audio only (never committed, never distributed;
see 08-Licenses.md). A second permissive Piper voice adds model-loading
breadth, not new code paths (lazy per-voice loading is unit-tested with
fakes), so one Piper voice plus Kokoro proves the routing.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest
from ebooklib import epub

PIPER_VOICE = "en_US-lessac-low"

DIALOGUE = (
    '"We should leave at dawn," Alice said. '
    '"The road is long," Bob replied. '
    '"Pack only what you can carry," Alice said. '
    '"I will bring the lantern," Bob said. '
)


def _find_models() -> tuple[Path | None, bool]:
    """Repo models dir holding the Kokoro pair; plus the Piper pair present."""
    candidates: list[Path] = []
    override = os.environ.get("AULOUD_KOKORO_MODELS", "").strip()
    if override:
        candidates.append(Path(override))
    candidates.append(Path("models"))
    candidates.append(Path(__file__).resolve().parent.parent / "models")
    for directory in candidates:
        if (directory / "kokoro-v1.0.onnx").is_file() and (
            directory / "voices-v1.0.bin"
        ).is_file():
            piper_ok = (directory / "piper" / f"{PIPER_VOICE}.onnx").is_file() and (
                directory / "piper" / f"{PIPER_VOICE}.onnx.json"
            ).is_file()
            return directory, piper_ok
    return None, False


def _make_dialogue_epub(path: Path) -> Path:
    book = epub.EpubBook()
    book.set_identifier("test-sw4-book")
    book.set_title("SW4 Proof Book")
    book.set_language("en")
    book.add_author("SW4 Author")
    body = (
        "<p>The night was cold and the fire burned low in the small stone hearth.</p>"
        f"<p>{DIALOGUE}</p>"
        f"<p>{DIALOGUE}</p>"
    )
    item = epub.EpubHtml(title="Chapter One", file_name="ch1.xhtml", lang="en")
    item.content = f"<h1>Chapter One</h1>{body}"
    book.add_item(item)
    book.toc = [epub.Link("ch1.xhtml", "Chapter One", "ch1")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [item]
    epub.write_epub(str(path), book)
    return path


def _decode_mp3(mp3: Path, out_wav: Path) -> None:
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-v",
            "error",
            "-i",
            str(mp3),
            "-ac",
            "1",
            "-ar",
            "24000",
            str(out_wav),
        ],
        check=True,
    )


@pytest.mark.slow
def test_mixed_engine_chapter_renders_and_validates(tmp_path: Path) -> None:
    models_dir, piper_ok = _find_models()
    if models_dir is None:
        pytest.skip("SKIP: no Kokoro pair in models/ (see `scribe doctor --models-dir`)")
    if not piper_ok:
        pytest.skip(f"SKIP: no {PIPER_VOICE} pair in {models_dir}/piper")
    if shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None:
        pytest.skip("SKIP: ffmpeg/ffprobe not on PATH")

    import numpy as np
    import soundfile as sf

    from build import CAST_FILENAME, run_build
    from bundle.validate import validate_bundle
    from draft import book_id_for_file
    from text.cast import read_cast, write_cast

    epub_path = _make_dialogue_epub(tmp_path / "book.epub")
    work_root = tmp_path / "work"
    out = tmp_path / "out"
    result = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        models_dir=models_dir,
        show_progress=False,
    )
    assert result.rendered >= 1

    # Switch Alice to Piper (narrator and Bob stay Kokoro: both engines
    # render in one chapter).
    book_id, _ = book_id_for_file(epub_path)
    cast_path = work_root / book_id / CAST_FILENAME
    cast = read_cast(cast_path)
    assert "Alice" in cast.get("characters", {}), f"no Alice discovered: {cast}"
    assert "Bob" in cast.get("characters", {}), f"no Bob discovered: {cast}"
    cast["characters"]["Alice"]["engine"] = "piper"
    cast["characters"]["Alice"]["voice"] = PIPER_VOICE
    write_cast(cast_path, cast)

    mixed = run_build(
        epub_path,
        work_root=work_root,
        out_dir=out,
        models_dir=models_dir,
        show_progress=False,
    )
    assert mixed.rendered >= 1  # engine switch re-renders
    assert mixed.rtf > 0
    print(
        f"\nSW4 numbers: audio={mixed.total_audio_ms}ms "
        f"wall={mixed.wall_seconds:.1f}s rtf={mixed.rtf:.2f}"
    )

    check = validate_bundle(out)
    assert check.ok, check.errors
    manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["voices"]["narrator"]["engine"] == "kokoro"
    assert manifest["voices"]["Alice"]["engine"] == "piper"
    assert manifest["voices"]["Alice"]["voice"] == PIPER_VOICE
    assert manifest["voices"]["Bob"]["engine"] == "kokoro"

    # Both engines heard: versions in the build log (cache keys on version).
    log_text = mixed.log_path.read_text(encoding="utf-8")
    assert "kokoro-onnx" in log_text
    assert "piper-tts" in log_text

    # Every shipped sentence window is non-silent in the real MP3, and the
    # chapter peak stays capped (per-voice leveling + loudness, not muting).
    chapter = json.loads((out / "text" / "ch001.json").read_text(encoding="utf-8"))
    sentences = [s for b in chapter["blocks"] for s in b.get("sentences", [])]
    assert len(sentences) >= 6
    speakers = {s["speaker"] for s in sentences}
    assert {"narrator", "Alice", "Bob"} <= speakers
    wav = tmp_path / "ch001.wav"
    _decode_mp3(out / "audio" / "ch001.mp3", wav)
    audio, rate = sf.read(str(wav), dtype="float32", always_2d=False)
    assert rate == 24000
    assert float(np.max(np.abs(audio))) <= 1.0
    for sent in sentences:
        start = int(sent["start_ms"] * rate // 1000)
        end = int(sent["end_ms"] * rate // 1000)
        window = audio[start:end]
        assert len(window) > 0, f"empty window for sid {sent['sid']}"
        peak = float(np.max(np.abs(window)))
        assert peak > 0.005, (
            f"silent window for sid {sent['sid']} ({sent['speaker']}): peak {peak}"
        )
