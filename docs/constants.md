# Auloud tuning constants (VC4, 2026-10-09)

Every number the on-device render path tunes by, with its value,
its source measurement, and its date. Standing provisionals keep
their current basis stated plainly. Changed constants would be
logged here with a decision entry; this revision changes none
(see "Retune verdict" below).

Conventions: RTF means audio seconds per wall second (higher is
faster). Dates are owner-report or commit dates in UTC.

## Estimate constants (Player, `render/RenderUiState.kt`)

| Constant | Value | Source | Date | Status |
| --- | --- | --- | --- | --- |
| `RENDER_RTF_LOW` | 0.86 | Slice 10 plan section 2: System TTS bench on the Tab E, slow run of a noisy pair (2.56x then 0.86x run to run) | 2026-10 (plan figure, pre-Slice 10) | Provisional, stands |
| `RENDER_RTF_HIGH` | 2.56 | Same bench, fast run of the pair | 2026-10 (plan figure) | Provisional, stands |
| `EST_MS_PER_WORD` | 400 ms/word | Assumption, not a measurement: 150 wpm adult narration; sizes unrendered chapters only, measured manifest durations always win | 2026-10 (RN9) | Provisional, stands |

`EngineBenchmark.SYSTEM_RTF_LOW/HIGH` (tts package) mirror the
same two numbers so the voice picker warns from the same band;
they are a deliberate duplicate, keep them in sync by hand until
a retune moves both.

## Guard constant (Player, `render/RenderGuards.kt`)

| Constant | Value | Source | Date | Status |
| --- | --- | --- | --- | --- |
| `DEFAULT_TEMP_LIMIT_C` | 40.0 C | Provisional default; the Slice 10 plan left temperature and storage defaults to device acceptance, and no temperature numbers have been reported | 2026-10 (RN3) | Provisional, stands |

## Storage-estimate constants (Player, `render/RenderEstimate.kt`)

| Constant | Value | Source | Date | Status |
| --- | --- | --- | --- | --- |
| `AUDIO_BYTES_PER_MS` | 8.0 (64 kbps) | Arithmetic from the spec audio format (AAC-LC mono 24 kHz, constrained targeting 64 kbps), container overhead ignored and documented as under 1% | 2026-10 (RN8) | Stands (derived, not measured) |
| `SPOOL_BYTES_PER_MS` | 50.09 (86 MiB per 30 min) | Arithmetic from the Slice 10 plan figure (86 MiB of 16-bit mono PCM per 30 min of audio) | 2026-10 (RN8) | Stands (derived, not measured) |
| `DEFAULT_CHAPTER_AUDIO_MS` | 1,800,000 (30 min) | Conservative fallback for unknown chapter lengths; over-estimating pauses early is safe | 2026-10 (RN8) | Stands (policy choice, not measured) |

Note: one test pins 50.0 as an injected rate (`RenderEstimateTest`);
that is a test input, not the production 50.09 constant.

## Engine-speed constants (Player, `tts/EngineBenchmark.kt`)

| Constant | Value | Source | Date | Status |
| --- | --- | --- | --- | --- |
| `PIPER_RTF` | 0.36 | Slice 10 plan section 2: warmed Piper on the Tab E (about 2 h 47 min rendering per hour of audio) | 2026-10 (plan figure) | Provisional, stands |

## Encoder offset and AAC tolerance (spec + Scribe + Player)

| Constant | Value | Source | Date | Status |
| --- | --- | --- | --- | --- |
| `encoder_offset_ms` fixture/default | 0 | Placeholder, not a measurement; every Tab E encode path defaults to 0 (`EncoderConfig`, `RenderFinalize`, both AAC fixtures) | 2026-10 (RN6/RN7/RN10) | Provisional, stands; gate open (below) |
| AAC duration tolerance | 50 ms, provisional | Same number as the MP3 rule, flagged provisional in spec section 7 and `scribe/bundle/validate.py` (`AAC_DURATION_TOLERANCE_MS`) | 2026-10 (RN1) | Provisional, stands |
| AAC average-bitrate band | 16 kbps around 64 kbps | Scribe `AAC_BITRATE_TOLERANCE_BPS`; catches gross misconfiguration only, since AAC has no MP3-style CBR frames | 2026-10 (RN1) | Stands (check shape, not tuned) |
| MP3 duration tolerance | 50 ms (`DURATION_TOLERANCE_MS`) | Established Scribe rule; measured ffprobe-vs-sample deltas were 0 ms against it | Pre-Slice 10 | Stands (not in retune scope) |

## Fixture-only placeholders (never shipped behavior)

| Value | Source | Status |
| --- | --- | --- |
| `gain_db` fixture values (-1.5/0.5 rendered, -1.0/0.0 partial) | Illustrative numbers for the golden fixtures | Placeholders; RN10/RN11 replace with derived magnitudes, not yet measured |
| Fingerprint placeholder engine version strings | Fixture text | Placeholders; real renders record real versions |

## Analysis parameters (not device tuning, recorded so nobody retunes them by mistake)

| Constant | Value | Basis |
| --- | --- | --- |
| `BeepOffset.detectOnsets` threshold | 0.08 | Sits far from beep tone peaks (0.5) and digital silence; encoder noise stays far below |
| `BeepOffset.detectOnsets` minGapMs | 200 ms | Between one 440 Hz sine period (about 2 ms) and the shortest beep-chapter pause (250 ms) |
| `RenderPlanner.DEFAULT_NEXT_N` | 5 | Placeholder scope ("a few chapters"); RN9 replaces with duration-mapped hours only with measured data |
| Beep chapter known starts | 0/1000/2000/3000 ms, 500 ms tones | Fixed by the debug engine design, verified on JVM (RN10) |
| Highlight check window | about 300 ms | Acceptance criterion (RN11/VS7 pass by ear and eye), not a code constant; `LagTracker` only measures, nothing thresholds on it |
| Pause durations 250/500/800/1000/100 ms | Scribe design, pinned by shared RN2 timing vectors (10/10 pass on Kotlin) | Not tablet-tuned; VC5 re-confirmed the sentence/tag behavior is baseline, not a ported rule |

## Evidence reviewed for this retune

- Slice 10 RN11 (2026-10-08, Tab E, owner-run): all boxes pass, highlight flips within about 300 ms by ear and eye. No beep onsets, no RTF numbers, no estimate-vs-actual figures recorded. Bounds the real offset inside the 300 ms window; justifies no change.
- Slice 11 VS7 (2026-10-09, Tab E, owner-run): "everything works great"; estimates "pretty close to the real time". No precise predicted-vs-actual numbers, so per the VS7 entry itself the Slice 10 provisionals stand untuned.
- VC5 suite (2026-10-09, JVM): sentence agreement 41/44 sentences and 34/34 runs on quote-heavy prose, rules left alone; flake fix and lint are not tuning evidence.
- NOT run (owner-deferred, recorded as unmeasured): SysLong 5-minute bench (VC0 gate, decides streaming and the render-ahead window); beep onset measurement (RN11 procedure steps 4-6); VC7 soak estimate-vs-actual and storage-per-hour numbers. No numbers are invented for any of these.

## Retune verdict: no changes

The retune rule is that a constant moves only with a real
measurement behind it. The only post-provisional evidence
(RN11 functional pass, VS7 "pretty close") is qualitative and,
by its own log wording, leaves the provisionals standing. So
every constant above keeps its value and its provisional flag,
and there are no decision entries or test updates in this
commit. A constants doc that changes nothing is the honest VC4
outcome; the numbers that can move these constants are named in
the next section.

## Encoder offset gate: CLOSED by default-acceptance (D-132, 2026-10-10)

Open since Slice 10 (D-109): `RenderFinalize` refuses a nonzero
`encoder_offset_ms` because shifting all timings would move the
first start and break the spec section 6 rule (first start 0,
carried exactly into 2.0). The provisional 0 never hits that
refusal, which is pinned by test.

Disposition: closed 2026-10-10 without the onset numbers (owner
waived the beep measurement as unnecessary). Offset 0 stands, the
50 ms AAC tolerance stands, and the finalize refusal of nonzero
stays as the safe default. Nothing observed (RN11/VS7 functional
passes) contradicts first-start-0, so no spec amendment was needed.
A future measured stable nonzero reopens this via the spec-first
amendment path described below (kept for that case): device
chapters starting at the offset would need the spec amended first,
then the finalize check follows. Original open-gate reasoning
retained for the record: had the beep run measured zero, the
question would have closed with the tolerance kept; had it
measured a stable nonzero, the amendment-vs-refuse choice would
have been forced then.
