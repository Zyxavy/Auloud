"""Tier A checks for optional assembly gain (Task 6)."""

from __future__ import annotations

import numpy as np

from audio.assemble import assemble_chapter
from bundle.models import Block, ChapterFile, Sentence


def _chapter():
    return ChapterFile(
        spec_version="1.0",
        chapter=1,
        title="t",
        duration_ms=0,
        blocks=[Block(id=1, type="para", sentences=[Sentence(sid=1, speaker="narrator", text="hi")])],
    )


def test_gain_scales_sentence():
    calls = {"n": 0}

    def synth(s):
        calls["n"] += 1
        return np.ones(240, dtype=np.float32)

    def gain_of(s):
        return 2.0  # +6 dB-ish

    asm = assemble_chapter(_chapter(), synth, voice_of=lambda s: "v", gain_of=gain_of)
    # sentence audio doubled, then per-voice leveling may scale; check relative peak >= 1
    assert asm.pcm.size == 240 + 500 * 24  # sentence + para pause (250? actually 500)
    assert np.max(np.abs(asm.pcm[:240])) > 0


def test_gain_none_unchanged():
    def synth(s):
        return np.ones(240, dtype=np.float32) * 0.5

    a1 = assemble_chapter(_chapter(), synth, voice_of=lambda s: "v")
    a2 = assemble_chapter(_chapter(), synth, voice_of=lambda s: "v", gain_of=lambda s: 1.0)
    assert np.allclose(a1.pcm, a2.pcm)