# Auloud Scribe — turns ebooks into multi-voice audiobooks (PC tool).
# Copyright (C) 2026 Zyxavy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as
# published by the Free Software Foundation, either version 3 of the
# License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""SPIKE (throwaway, not production): chunk consecutive same-speaker sentences.

Idea (from Alexandria): synthesize several consecutive same-speaker sentences
as one TTS call (up to ``MAX_CHARS`` chars) so the voice does not switch and
prosody runs across the joins, then recover per-sentence timings by splitting
the chunk audio at sentence boundaries using Kokoro's phoneme timings.

Why the split is needed: the bundle contract wants one ``start_ms``/``end_ms``
per sentence for the Player's read-along highlight. Chunking removes the
per-sentence audio that used to define those marks, so the marks must be
recovered from the model's phoneme-level ``duration`` output.

What this script proves (or disproves), per the Task 7 success bar:

1. sentence boundary error < 50 ms   -> measured here (see the report)
2. QC WER not worse                  -> NOT measured: faster-whisper is not
                                        installed; the script records that
3. listening preference (majority)   -> NOT measured here: it writes blind
                                        A/B WAVs for the owner to listen to

Boundary-recovery method (verified against the real Kokoro export, which
does report ``duration``, so ``create_timed`` returns phoneme timings):

- phonemize the chunk (this is exactly what ``create_timed`` does internally),
- phonemize each sentence on its own,
- align each sentence's phoneme string inside the chunk's phoneme stream
  (sequential, exact; spaces between sentences are skipped),
- the sentence's speech span is the first/last phoneme timing of its span.

The alignment was exact on every group tried in the real chapter (see the
results file). If it ever fails the group is counted, not silently mis-timed.

Usage::

    python experiments/chunking_spike.py --chapter <text/chNNN.json> \\
        [--voice af_heart] [--out experiments/out] [--max-chars 500] \\
        [--samples 5] [--device auto]

Only the *text* JSON of a bundle is read; the audio files it writes live under
``--out`` (gitignored). No copyrighted text or audio is committed.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

# Let `python experiments/chunking_spike.py` import the Scribe packages.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

MAX_CHARS = 500
DEFAULT_VOICE = "af_heart"
DEFAULT_OUT = Path(__file__).resolve().parent / "out"
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"


# --------------------------------------------------------------------------
# Pure logic (no model, unit-tested in tests/test_chunking_spike.py)
# --------------------------------------------------------------------------


def group_sentences(
    sentences: list[tuple[str, str]], max_chars: int = MAX_CHARS
) -> list[list[int]]:
    """Group sentence indices into runs of consecutive same-speaker sentences.

    A new group starts when the speaker changes or when adding the next
    sentence would push the group past ``max_chars``. A single sentence longer
    than ``max_chars`` becomes its own (over-budget) group. Sentence text is
    measured stripped, matching the whitespace the chunk joins with.
    """
    groups: list[list[int]] = []
    current: list[int] = []
    size = 0
    speaker: str | None = None
    for index, (name, text) in enumerate(sentences):
        length = len(text.strip())
        if current and (name != speaker or size + length > max_chars):
            groups.append(current)
            current, size = [], 0
        current.append(index)
        size += length
        speaker = name
    if current:
        groups.append(current)
    return groups


def align_phoneme_spans(
    chunk_phonemes: str, sentence_phonemes: list[str]
) -> list[tuple[int, int]] | None:
    """Locate each sentence's phonemes inside the chunk's phoneme stream.

    Returns ``(start, end)`` character offsets into ``chunk_phonemes`` (end
    exclusive, indices line up 1:1 with the ``create_timed`` timing list).
    Spaces in ``chunk_phonemes`` are skipped between sentences. Returns
    ``None`` on the first sentence that does not match exactly, so a caller
    can fall back instead of mis-timing.
    """
    spans: list[tuple[int, int]] = []
    cursor = 0
    for phonemes in sentence_phonemes:
        needle = phonemes.strip()
        if not needle:
            spans.append((cursor, cursor))
            continue
        while cursor < len(chunk_phonemes) and chunk_phonemes[cursor] == " ":
            cursor += 1
        if not chunk_phonemes.startswith(needle, cursor):
            return None
        spans.append((cursor, cursor + len(needle)))
        cursor += len(needle)
    return spans


def sentence_speech_spans(timings, spans: list[tuple[int, int]]):
    """Spoken ``(start_s, end_s)`` of each sentence, from phoneme timings.

    A span is the first phoneme's start to the last phoneme's end (seconds).
    An empty span yields ``None``.
    """
    out: list[tuple[float, float] | None] = []
    for start_i, end_i in spans:
        if end_i <= start_i or end_i > len(timings):
            out.append(None)
            continue
        out.append((float(timings[start_i].start), float(timings[end_i - 1].end)))
    return out


def acoustic_onset(
    audio: np.ndarray,
    sample: int,
    sample_rate: int,
    *,
    thresh_frac: float = 0.01,
    quiet_ms: float = 20.0,
    search_ms: float = 60.0,
) -> int | None:
    """Nearest speech onset to ``sample``: sample index where audio rises
    above 1 % of peak, with the preceding ``quiet_ms`` below that threshold.

    Independent of the model's timing output, so comparing it with a
    timing-derived boundary measures the timing error. ``None`` when no onset
    is found in the search window (all-silent or all-loud).
    """
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    if peak <= 0.0:
        return None
    thresh = peak * thresh_frac
    quiet = max(1, int(quiet_ms / 1000.0 * sample_rate))
    reach = max(1, int(search_ms / 1000.0 * sample_rate))
    lo, hi = max(0, sample - reach), min(audio.size, sample + reach)
    best: int | None = None
    for i in range(lo, hi):
        if abs(float(audio[i])) < thresh:
            continue
        before = audio[max(0, i - quiet) : i]
        if before.size and float(np.max(np.abs(before))) >= thresh:
            continue
        if best is None or abs(i - sample) < abs(best - sample):
            best = i
    return best


def boundary_error_ms(audio: np.ndarray, sample: int, sample_rate: int, **kwargs) -> float | None:
    """Timing-derived split point minus the acoustic onset, in milliseconds.

    Positive means the mark sits after the first syllable actually starts
    (the Player would highlight late and clip the onset).
    """
    onset = acoustic_onset(audio, sample, sample_rate, **kwargs)
    if onset is None:
        return None
    return (sample - onset) / sample_rate * 1000.0


# --------------------------------------------------------------------------
# Model harness (needs a Kokoro engine)
# --------------------------------------------------------------------------


def create_timed(engine, text: str, voice: str, speed: float):
    """``create_timed`` on the wrapped Kokoro backend (audio, rate, timings)."""
    from tts.kokoro import LANG

    return engine._kokoro.create_timed(text, voice, float(speed), LANG)


def load_sentences(chapter_path: Path) -> list[tuple[str, str]]:
    """``(speaker, text)`` for every non-empty sentence, in document order."""
    data = json.loads(chapter_path.read_text(encoding="utf-8"))
    sentences: list[tuple[str, str]] = []
    for block in data.get("blocks") or []:
        for sentence in block.get("sentences") or []:
            text = (sentence.get("text") or "").strip()
            if text:
                sentences.append((sentence.get("speaker") or "unknown", text))
    return sentences


def _write_wav(path: Path, audio: np.ndarray, sample_rate: int) -> None:
    clipped = np.clip(audio, -1.0, 1.0)
    pcm = (clipped * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(pcm.tobytes())


@dataclass
class GroupResult:
    indices: list[int]
    chars: int
    align_ok: bool
    speech_delta_ms: list[float] = field(default_factory=list)  # chunk minus standalone
    boundary_error_ms: list[float] = field(default_factory=list)
    chunk_ms: float = 0.0
    standalone_ms: float = 0.0
    phonemes: int = 0


@dataclass
class SpikeReport:
    chapter: str
    voice: str
    sentences: int
    groups: list[GroupResult]
    whisper: str
    out_dir: str

    @property
    def group_count(self) -> int:
        return len(self.groups)

    def summary(self) -> dict:
        deltas = [abs(d) for group in self.groups for d in group.speech_delta_ms]
        signed = [d for group in self.groups for d in group.speech_delta_ms]
        errors_signed = [e for group in self.groups for e in group.boundary_error_ms]
        errors = [abs(e) for e in errors_signed]
        chunk_s = sum(group.chunk_ms for group in self.groups) / 1000.0
        sent_s = sum(group.standalone_ms for group in self.groups) / 1000.0

        def _stat(values: list[float], fn) -> float:
            return round(fn(values), 2) if values else 0.0

        return {
            "sentences": self.sentences,
            "groups": self.group_count,
            "synth_calls_sentence": self.sentences,
            "synth_calls_chunked": self.group_count,
            "align_failures": sum(1 for g in self.groups if not g.align_ok),
            "boundary_error_ms": {
                "count": len(errors),
                "mean_abs": _stat(errors, lambda v: sum(v) / len(v)),
                "median_abs": _stat(errors, lambda v: float(np.median(v))),
                "max_abs": _stat(errors, max),
                "within_50ms": sum(1 for e in errors if e <= 50.0),
                "mean_signed": _stat(errors_signed, lambda v: sum(v) / len(v)),
            },
            "speech_duration_delta_ms": {
                "mean_abs": _stat(deltas, lambda v: sum(v) / len(v)),
                "median_abs": _stat(deltas, lambda v: float(np.median(v))),
                "max_abs": _stat(deltas, max),
                "over_50ms": sum(1 for d in deltas if d > 50.0),
                "mean_signed": _stat(signed, lambda v: sum(v) / len(v)),
            },
            "audio_seconds": {
                "chunked": round(chunk_s, 3),
                "sentence_by_sentence": round(sent_s, 3),
            },
            "whisper_wer": self.whisper,
        }


def measure(
    sentences: list[tuple[str, str]],
    engine,
    *,
    voice: str = DEFAULT_VOICE,
    speed: float = 1.0,
    max_chars: int = MAX_CHARS,
    samples: int = 5,
    out_dir: Path | None = None,
    chapter_label: str = "chapter",
) -> SpikeReport:
    """Run the chunked vs sentence-by-sentence comparison on one chapter."""
    groups = group_sentences(sentences, max_chars)
    results: list[GroupResult] = []
    ab_groups: list[tuple[np.ndarray, int, np.ndarray, int]] = []

    for indices in groups:
        texts = [sentences[i][1] for i in indices]
        chunk = " ".join(texts)
        audio, rate, timings = create_timed(engine, chunk, voice, speed)
        result = GroupResult(indices=indices, chars=len(chunk), align_ok=False)
        result.chunk_ms = len(audio) / rate * 1000.0
        result.phonemes = len(timings)

        chunk_phonemes = "".join(timing.phoneme for timing in timings)
        sentence_phonemes = [
            engine._kokoro.tokenizer.known(engine._kokoro.tokenizer.phonemize(t, "en-us"))
            for t in texts
        ]
        spans = align_phoneme_spans(chunk_phonemes, sentence_phonemes)

        standalone_total = 0
        standalone_spans: list[tuple[float, float] | None] = []
        standalone_audio: list[np.ndarray] = []
        for text in texts:
            saudio, srate, stimings = create_timed(engine, text, voice, speed)
            standalone_total += len(saudio)
            standalone_spans.append(
                (float(stimings[0].start), float(stimings[-1].end)) if stimings else None
            )
            standalone_audio.append(saudio)
        result.standalone_ms = standalone_total / rate * 1000.0

        if spans is not None:
            result.align_ok = True
            derived = sentence_speech_spans(timings, spans)
            for chunk_span, solo_span in zip(derived, standalone_spans):
                if chunk_span is None or solo_span is None:
                    continue
                chunk_dur = (chunk_span[1] - chunk_span[0]) * 1000.0
                solo_dur = (solo_span[1] - solo_span[0]) * 1000.0
                result.speech_delta_ms.append(chunk_dur - solo_dur)
            # How far is each internal split point from the real speech onset?
            for start_i, _end_i in spans[1:]:
                sample = int(timings[start_i].start * rate)
                error = boundary_error_ms(audio, sample, rate)
                if error is not None:
                    result.boundary_error_ms.append(error)
            if len(texts) >= 2:
                ab_groups.append(
                    (audio, rate, np.concatenate(standalone_audio), rate)
                )

        results.append(result)

    if out_dir is not None:
        out_dir.mkdir(parents=True, exist_ok=True)
        _write_ab_samples(ab_groups, out_dir, samples)

    whisper = _whisper_status()
    return SpikeReport(
        chapter=chapter_label,
        voice=voice,
        sentences=len(sentences),
        groups=results,
        whisper=whisper,
        out_dir=str(out_dir) if out_dir else "",
    )


def _whisper_status() -> str:
    try:
        from qc import QCError, load_model

        try:
            load_model("tiny")
        except QCError as exc:
            return f"not measured ({exc})"
        return "measured"
    except Exception as exc:  # noqa: BLE001 - report, never fail the spike
        return f"not measured ({exc})"


def _write_ab_samples(
    ab_groups: list[tuple[np.ndarray, int, np.ndarray, int]],
    out_dir: Path,
    samples: int,
) -> None:
    """Blind A/B: one WAV per rendition, letter assignment written to key.txt."""
    picks = ab_groups[:samples]
    rng = random.Random(1729)
    key_lines = ["One voice (af_heart), chunked vs sentence-by-sentence."]
    for n, (chunk_audio, rate, solo_audio, _srate) in enumerate(picks, 1):
        chunked, sentence = (chunk_audio, "chunked"), (solo_audio, "sentence")
        first, second = (chunked, sentence) if rng.random() < 0.5 else (sentence, chunked)
        _write_wav(out_dir / f"sample{n:02d}_A.wav", first[0], rate)
        _write_wav(out_dir / f"sample{n:02d}_B.wav", second[0], rate)
        key_lines.append(f"sample{n:02d}: A={first[1]}  B={second[1]}")
    (out_dir / "key.txt").write_text("\n".join(key_lines) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--chapter", required=True, type=Path, help="bundle text/chNNN.json")
    parser.add_argument("--voice", default=DEFAULT_VOICE)
    parser.add_argument("--speed", type=float, default=1.0)
    parser.add_argument("--max-chars", type=int, default=MAX_CHARS)
    parser.add_argument("--samples", type=int, default=5, help="A/B groups to write")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--device", default="auto", choices=["auto", "cpu", "cuda"])
    parser.add_argument("--no-audio", action="store_true", help="skip WAV writing")
    parser.add_argument("--json", type=Path, help="also write the summary here")
    args = parser.parse_args(argv)

    from device import create_engine_with_device

    sentences = load_sentences(args.chapter)
    if not sentences:
        print(f"no sentences in {args.chapter}", file=sys.stderr)
        return 2
    engine = create_engine_with_device(MODELS_DIR, device=args.device)
    print(f"device providers: {engine._kokoro.sess.get_providers()}", file=sys.stderr)

    report = measure(
        sentences,
        engine,
        voice=args.voice,
        speed=args.speed,
        max_chars=args.max_chars,
        samples=args.samples,
        out_dir=None if args.no_audio else args.out,
        chapter_label=str(args.chapter),
    )
    summary = report.summary()
    print(json.dumps(summary, indent=2))
    if args.json:
        args.json.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
