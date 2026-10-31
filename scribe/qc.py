"""Quality-control report pass (Whisper-based WER)."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

__all__ = [
    "QCError",
    "normalize_text",
    "compute_wer",
    "load_model",
    "transcribe",
    "transcribe_audio",
    "run_qc",
]

SUPPORTED_MODELS = ("tiny", "base")


class QCError(Exception):
    """Raised when QC cannot run."""


def normalize_text(text: str) -> str:
    text = (text or "").lower()
    text = re.sub(r"[^\w\s]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text


def _words(text: str) -> list[str]:
    return normalize_text(text).split()


def compute_wer(reference: str, hypothesis: str) -> float:
    ref = _words(reference)
    hyp = _words(hypothesis)
    if not ref:
        return 0.0 if not hyp else 1.0
    prev = list(range(len(hyp) + 1))
    for i, rw in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, hw in enumerate(hyp, 1):
            cost = 0 if rw == hw else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[-1] / len(ref)


def _split_sentences(text: str) -> list[str]:
    parts = re.split(r"(?<=[.!?])\s+", (text or "").strip())
    return [p.strip() for p in parts if p.strip()]


def load_model(model_name: str = "tiny"):
    try:
        from faster_whisper import WhisperModel  # type: ignore
    except ImportError as exc:
        raise QCError(
            "faster-whisper is not installed; run `uv pip install faster-whisper`."
        ) from exc
    if model_name not in SUPPORTED_MODELS:
        raise QCError(f"unknown model {model_name!r} (expected tiny or base)")
    try:
        return WhisperModel(model_name, device="cpu", compute_type="int8")
    except Exception as exc:  # noqa: BLE001
        raise QCError(f"failed to load whisper model {model_name!r}: {exc}") from exc


def transcribe(audio_path, model=None, model_name: str = "tiny") -> str:
    if model is None:
        model = load_model(model_name)
    try:
        segments, _info = model.transcribe(str(audio_path))
    except Exception as exc:  # noqa: BLE001
        raise QCError(f"transcription failed for {audio_path}: {exc}") from exc
    return " ".join((seg.text or "").strip() for seg in segments).strip()


def transcribe_audio(audio_path, model_name: str = "tiny") -> str:
    return transcribe(audio_path, model=None, model_name=model_name)


@dataclass
class QCResult:
    report: dict[str, Any]
    output_path: Path


def _read_bundle(bundle: Path) -> tuple[dict[str, Any], list[Any]]:
    manifest_path = bundle / "manifest.json"
    if not manifest_path.is_file():
        raise QCError(f"manifest.json missing in {bundle}")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise QCError(f"cannot read manifest: {exc}") from exc
    return manifest, manifest.get("chapters", [])


def run_qc(bundle, model: str = "tiny", threshold: float = 0.15, output_path=None) -> QCResult:
    bundle = Path(bundle)
    _manifest, chapters = _read_bundle(bundle)
    whisper_model = load_model(model)

    chapter_results: list[dict[str, Any]] = []
    all_wers: list[float] = []
    flagged_chapters = 0
    total_sentences = 0
    flagged_sentences = 0

    for entry in chapters:
        idx = entry.get("index")
        title = entry.get("title", "")
        text = entry.get("text", "") or ""
        audio_rel = entry.get("audio")
        audio_path = (bundle / audio_rel) if audio_rel else None

        transcript = ""
        wer = 0.0
        if audio_path is not None and audio_path.is_file():
            transcript = transcribe(audio_path, model=whisper_model)
            wer = compute_wer(text, transcript)

        text_path = bundle / "text" / f"ch{int(idx):03d}.json" if idx is not None else None
        sentences_list: list[dict[str, Any]] = []
        if text_path is not None and text_path.is_file():
            try:
                cf = json.loads(text_path.read_text(encoding="utf-8"))
                ordered: list[dict[str, Any]] = []
                for block in (cf.get("blocks") or []):
                    for s in (block.get("sentences") or []):
                        ordered.append(s)
                hyp_sentences = _split_sentences(transcript)
                for i, s in enumerate(ordered):
                    s_text = s.get("text", "") or ""
                    hyp = hyp_sentences[i] if i < len(hyp_sentences) else ""
                    s_wer = compute_wer(s_text, hyp)
                    flagged = s_wer > threshold
                    if flagged:
                        flagged_sentences += 1
                    total_sentences += 1
                    sentences_list.append(
                        {
                            "sid": s.get("sid"),
                            "text": s_text,
                            "transcript": hyp,
                            "wer": s_wer,
                            "flagged": flagged,
                        }
                    )
            except (OSError, ValueError, KeyError):
                pass

        flagged = wer > threshold
        if flagged:
            flagged_chapters += 1
        all_wers.append(wer)
        chapter_results.append(
            {
                "index": idx,
                "title": title,
                "wer": wer,
                "flagged": flagged,
                "reference": text,
                "transcript": transcript,
                "audio": audio_rel,
                "sentences": sentences_list,
            }
        )

    mean_wer = (sum(all_wers) / len(all_wers)) if all_wers else 0.0
    report = {
        "bundle": str(bundle),
        "model": model,
        "threshold": threshold,
        "chapters": chapter_results,
        "summary": {
            "chapters": len(chapter_results),
            "flagged_chapters": flagged_chapters,
            "mean_wer": mean_wer,
            "sentences": total_sentences,
            "flagged_sentences": flagged_sentences,
        },
    }

    if output_path is None:
        output_path = bundle / "qc_report.json"
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    return QCResult(report=report, output_path=output_path)
