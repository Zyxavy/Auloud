"""Evaluation gate for detect_emotion (Task 2)."""

from __future__ import annotations

import json
from pathlib import Path

from text.attribution import AttributionCue
from text.emotion import detect_emotion

FIXTURE = Path(__file__).parent / "fixtures" / "emotion_labels.jsonl"


def _load():
    rows = []
    with FIXTURE.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def test_precision_gate():
    rows = _load()
    assert len(rows) >= 30
    tp = 0
    pred_nonneutral = 0
    total_expected_nonneutral = 0
    for r in rows:
        cue = AttributionCue(
            verb_lemma=r.get("verb_lemma"),
            adverbs=list(r.get("adverbs") or []),
            clause=r.get("clause", ""),
        )
        tag, _ = detect_emotion(cue)
        expected = r["expected"]
        if expected != "NEUTRAL":
            total_expected_nonneutral += 1
        if tag != "NEUTRAL":
            pred_nonneutral += 1
            if tag == expected:
                tp += 1
    precision = tp / pred_nonneutral if pred_nonneutral else 1.0
    recall = tp / total_expected_nonneutral if total_expected_nonneutral else 0.0
    coverage = pred_nonneutral / len(rows)
    print(f"precision={precision:.3f} recall={recall:.3f} coverage={coverage:.3f} n={len(rows)}")
    assert precision >= 0.90, f"precision {precision:.3f} < 0.90"
