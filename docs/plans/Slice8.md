# Slice 8 Implementation Plan: Engine Interface and Engines

Projects: Auloud Player (Kotlin, Apache-2.0, minSdk 24) and Scribe (Python, AGPL-3.0, Windows 11). Source of truth for requirements: `docs/09-V2Roadmap.md` section "Slice 8" plus decisions D-065 (engine interface), D-066 (espeak-ng license path, OPEN), D-071 (two-voice mode), D-072 (PC stays multi-voice). This slice implements the interface and the engines; it does **not** render books on the device (Slice 10) or ingest EPUBs on the device (Slice 9).

## 1. Goal

The Player can speak through a pluggable engine interface with at least two selectable tiers, and Scribe can render with Piper as a second PC engine. Two-voice roles (narrator + dialogue) are the unit of voice settings everywhere.

**Done when** (09-V2Roadmap Slice 8):

- The Tab E can audition voices from each included engine (user device check)
- Scribe renders a short multi-voice chapter with Piper into a valid bundle

## 2. Gate dependency on Slice 7

Slice 7 has not run. These work packages split into **gate-free** (start anytime) and **gated** (do not start until the Slice 7 gate table + D-066 license choice exist):

| Gate-free | Gated on Slice 7 |
| --- | --- |
| SW1 Piper PC engine (runtime shortlist below; license check is part of the WP) | PW7 model-pack import + bundled engines (which engines, sherpa-onnx build viability incl. 32-bit ARM) |
| SW2 engine-namespaced cast.yaml | PW7 license implementation (D-066 option A/B/C) |
| SW3 voices CLI + web UI engine listing | PW8 engine switcher recommendation source (built-in benchmark table comes from Slice 7 numbers) |
| PW5 TtsEngine interface + registry + fake | |
| PW6 System TTS adapter (no new dependency, no license question) | |

If Slice 7 finds neural TTS infeasible on the Tab E, PW7 ships Piper-low + System TTS only and Kokoro-on-device is reserved for faster devices; nothing else in this plan changes.

## 3. Scope

| In | Out (later slices) |
| --- | --- |
| Scribe `PiperEngine` behind the existing `TTSEngine` ABC; `piper.py` stub replaced | On-device EPUB ingest (Slice 9) |
| Engine-namespaced voices in `cast.yaml`; per-engine voice validation | On-device rendering jobs (Slice 10) |
| `scribe voices --engine`, web UI voices view lists engines | Streaming synthesis (Slice 13) |
| Player `tts/` package: `TtsEngine` interface, registry, `PrefsTtsStore`, fake | Engine download feature (deferred; sideload only per D-067) |
| System TTS adapter + audition screen with narrator/dialogue roles | Unrendered-book spec amendment (Slice 9, D-070) |
| Model-pack folder import (sideload, D-067) + bundled engine(s) per gate | Audio-format spec amendment AAC (Slice 10, D-068) |
| 08-Licenses updates for every new dependency/model | PC "collapse to two voices" toggle (not in v2, D-072) |

No bundle-format change in this slice: audition audio goes to temp files, never into chapters. One confirm-only spec item (SW2): the roadmap asserts a manifest `engine` field already exists; if it does not, that is an additive amendment with validator + fixtures, not a silent assumption.

## 4. Decisions and open points (log resolutions in `DECISIONS.md`)

**P1: Piper runtime on the PC.** Candidates: `piper-tts` pip package, sherpa-onnx CPU, direct onnxruntime. Verify in SW1: espeak-ng dependency shape (external exe like today vs bundled), voice-model format, license of the runtime + a sample model, and 24 kHz resample behavior through the existing `resample_mono` path. Recommendation after the check, not before.

**P2: cast.yaml engine schema.** Scribe today requires `narrator.engine == "kokoro"` and carries no per-entry engine on characters/generics. Proposal: every voice-carrying entry (`narrator`, each character, the two generics) gains `engine`, defaulting to `kokoro` on read so existing files validate unchanged; `validate_cast` checks each voice against its own engine's list; `resolve_speaker` fallback stays engine-agnostic (gender generics). Overrides keep working (voice string + engine travel together).

**P3: Player engine wiring without DI.** The app has no DI framework (manual construction at call sites: `MainActivity` factories, `PlaybackService.onCreate`, `remember { PrefsXStore.fromContext() }`). The `TtsEngine` interface gets a hand-built registry (`Map<String, TtsEngine>` keyed by namespace) constructed where needed; JVM tests use the fake. Do not introduce Hilt/Koin for this slice.

**P4: System TTS adapter seams.** `android.speech.tts.TextToSpeech` is framework (untestable on JVM). Keep all logic (voice-id mapping `system:<voice>`, role selection, speed scaling, file-output naming) in a pure delegate; the adapter holds only the framework calls plus `shutdown()`. API 24 only: utterance IDs via the Bundle/param form available since API 21, no API > 24 calls.

**P5: sherpa-onnx viability is Slice 7's call, not this slice's.** This plan assumes the gate confirms a build with 32-bit ARM, minSdk 24 support, and a concrete espeak-ng linkage answer for D-066. If the gate rejects it, PW7 falls back to System-adapter-only plus documented plugin engines (D-066 option B) and the slice still delivers its Done-when via PW6/PW8 + SW4.

## 5. Work packages

### SW1: Scribe Piper engine (S, gate-free)

In `scribe/tts/`, replacing the stub:

- `PiperEngine` implementing the `TTSEngine` ABC (`synth`, `sample_rate`, `engine_version`, `voices`); 24 kHz native or via `resample_mono`; `engine_version` distinct from Kokoro's so the SW cache (`cache.py` key includes `engine_version`) never collides across engines
- Runtime per P1 with license recorded; `scribe doctor` gains an engine check mirroring `check_engine`; `pyproject.toml` optional or base dependency per license/weight outcome (ask if it touches packaging)
- **Verify:** ABC-conformance tests with a fake backend (no model download in unit tests); `scribe voices --engine piper --sample` against a real model as a manual proof recorded in the WP report; existing Kokoro suites green

### SW2: Engine-namespaced cast.yaml (M, gate-free)

- Schema per P2: `engine` on narrator/characters/generics, default `kokoro` on read; `validate_cast(cast, known_voices)` becomes per-engine (unknown engine is a validation error like today's non-kokoro narrator error); `resolve_speaker` total/lenient behavior unchanged; `ui/cast.py` patch ops carry `engine`; existing `engine="piper"` rejection test updated to the new acceptance
- Manifest `engine` field: confirm presence; if absent, additive amendment + validator + fixture (flag it, do not smuggle it in)
- **Verify:** cast/validation/ui-cast suites green; a piper-engine cast fixture validates; all Kokoro goldens byte-identical

### SW3: Voices CLI + web UI engine listing (S, gate-free)

- `scribe voices --engine <name>` (default kokoro; unknown engine is a clean CLI error); `tts/voices.py` per-engine `list_voices`/`engine_voice_names` (Piper lists installed model cards, format TBD in SW1)
- Web UI: `available_voices(engine)`, `/api/cast/voices?engine=`; audition pipeline (`voices-sample` jobs) per engine; palette fallback behavior unchanged when no models dir
- **Verify:** new CLI + API tests; full scribe suite green

### SW4: Piper multi-voice chapter render proof (S, gate-free)

- Render one short multi-voice chapter (narrator + at least two Piper voices) through the unchanged `build`/`assemble` path (leveling, 100 ms tag pause, 24 kHz contract all engine-agnostic by construction); `validate` the bundle; record RTF
- **Verify:** bundle validates; voice-gain/loudness assertions hold; RTF recorded in the WP report (second half of the slice Done-when)

### PW5: Player TtsEngine interface + registry (M, gate-free)

New `app/auloud/player/tts/` package, manual wiring per P3:

- `TtsEngine` interface (D-065: voice list, capabilities incl. multi-speaker/load-cost/sample-rate, `synthesize`); `EngineRegistry` (namespace keying); `TtsCapabilities` data; `PrefsTtsStore` + interface following the `ReaderModeStore`/`PrefsReaderModeStore` template; `FakeTtsEngine` for tests
- No new dependency, no permission, no manifest change
- **Verify:** JVM unit tests (registry routing, namespace parsing, store round-trip, fake synthesis contract); `:app:testDebugUnitTest` green; `06-PlayerDesign.md` amended with the tts package

### PW6: System TTS adapter (M, gate-free)

- `SystemTtsAdapter(context)` behind `TtsEngine`: voice enumeration as `system:<voice>`, per-utterance voice selection, `synthesizeToFile` to app-cache temp for audition (exact audio, future timings), `shutdown()` lifecycle; pure delegate per P4; API-24-safe calls only
- **Verify:** JVM tests over the delegate with a fake framework seam; `:app:testDebugUnitTest` green; on-device audition deferred to PW8 (needs device test)

### PW7: Model-pack import + bundled engine(s) (L, GATED)

Do not start before the Slice 7 gate + D-066 choice.

- Model-pack import from a folder (microSD, same UX shape as watch folders: picker, persistable permission, sizes shown, packs deletable); engine implementation(s) per gate (sherpa-onnx AAR with 32-bit ARM + minSdk 24 confirmed, or fallback per P5)
- License: implements D-066 (A: GPL-3.0 Player relicense with NOTICE/in-app/08 updates; B: adapter-only + plugin docs; C: no-GPL path as spiked); `08-Licenses.md` gains sherpa-onnx/onnxruntime/model/espeak rows with versions
- New native dependency and any permission-adjacent storage behavior: ask before adding, per slice rules
- **Verify:** unit tests for import scanning/validation; engine smoke on JVM where possible; device proof is PW8; no `INTERNET` permission added (CI guard stays green)

### PW8: Audition screen + voice settings (M; screen gate-free, recommendation table gated)

- `TtsVoiceSection` in settings + `VoiceAuditionScreen` (same `showX` pattern as licenses): narrator voice, dialogue voice, per-role speed, audition playback; automatic voice mapping on engine switch (gender + palette, D-036); per-device recommendation row fed by the Slice 7 table (placeholder copy until the gate lands)
- **Verify:** JVM tests for mapping + store; `:app:testDebugUnitTest` green; device check by the user (audition each engine on the Tab E: first half of the slice Done-when)

## 6. Test and doc touch list

- Scribe: `tests/test_tts_piper.py`, cast/voices/ui-cast additions, `tests/test_voices.py` engine param; `pyproject.toml` only if SW1 forces it (ask first)
- Player: `tts/` JVM tests only (no new test framework); `06-PlayerDesign.md` tts section; `08-Licenses.md` new rows (SW1, PW7); `DECISIONS.md` entries for P1/P2 resolutions and any gate fallout
- Roadmap: tick nothing in `04-Roadmap.md` (v1, frozen); `09-V2Roadmap.md` Slice 8 boxes tick only on their stated device/manual proofs
- Reports per slice-workflow format; device items stay with the user; no new work package starts without a go-ahead
