# Chunking spike results (Task 7)

## Setup
- Chapter: `scribe/.scribe/17917f2a-5ac1-53a2-ba39-f9d677b0f1d0/render/text/ch001.json` (26 sentences, 130s audio)
- Device: CUDA (RTX 4050)
- Grouping: consecutive same-speaker sentences, up to 500 chars
- Result: 26 sentences → 4 groups (synth calls 26 → 4)

## Success bar
1. **Listening preference (majority prefer chunked):** NOT MEASURED — owner must listen to A/B files (none written with `--no-audio`; re-run without `--no-audio` to produce WAVs in `experiments/out/`).
2. **Sentence boundary error < 50ms:** PASS — mean 25.6ms, median 26.1ms, max 42ms, all 14 measured boundaries within 50ms.
3. **QC WER not worse than sentence-by-sentence:** NOT MEASURED — faster-whisper is not installed.

## Additional observations
- **Speech duration delta:** chunked audio is ~395ms longer on average (max 788ms). The split boundaries are within tolerance, but the chunked synthesis produces measurably different (longer) prosody. This is a real quality concern, not just a timing one.
- **Align failures:** 1 (one sentence boundary could not be aligned).
- **Synth calls:** 26 → 4 (6.5x reduction in TTS calls). If chunking were adopted, this would reduce per-sentence overhead, but the duration drift suggests the model generates different prosody for longer inputs.

## Verdict: INCONCLUSIVE — needs owner listening + QC WER

- Boundary error passes the < 50ms bar.
- Duration drift (~400ms/chapter) is a quality concern that needs a listening A/B to assess perceptually.
- QC WER (the most objective quality metric) could not be measured without faster-whisper.

Recommendation: Install faster-whisper and re-run the spike with `--json` output to get QC WER. If WER is comparable and the owner prefers the chunked audio, implement. Otherwise drop — the duration drift and 1 align failure suggest the boundary splitting is not clean enough for production.