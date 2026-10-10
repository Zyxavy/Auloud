# Slice 14 Implementation Plan: KittenTTS Engine (Player `full` flavor)

Project: Auloud Player, `full` flavor only. Follows `docs/10-V3Roadmap.md` (Slice 14) and builds on Slice 8 (`TtsEngine`, registry, sherpa engines, model-pack discovery, Voice lab, benchmark), Slice 10 and 11 (rendering, fingerprints, stale detection, recommendation). Decision numbers continue from D-141. The audit fix plan (FP1-FP7) is done; the 2.1.0 smoke test, the 2.0 to 2.1 over-install proof and the release page are still open and are not blockers for this slice.

## 1. Goal

Add KittenTTS as an optional neural voice tier, measure it on the Tab E (nano int8 first, then micro), listen-test it against System TTS and Piper, and let the per-device recommendation use the result. Narrator and dialogue can be two speakers of **one** small model, so there is no second model to load and no voice-switch gap.

**Done when** (from the roadmap):

- The Voice lab lists Kitten voices from a sideloaded pack
- Benchmark numbers for nano int8 and micro on the Tab E are recorded
- The A/B result and the recommendation are logged
- A chapter renders on the device with Kitten narrator and dialogue voices
- The `core` flavor is unaffected (license scan green)

## 2. What is known and what is not

**Known (from sherpa-onnx documentation and the verified roadmap notes)**

- sherpa-onnx lists Kitten models: nano, micro and mini variants, including v0.8 nano fp32 and **nano int8 (about 25 MB)**; the pinned version is 1.13.8
- The Kitten config takes four paths and one scale: model, voices, tokens, and the espeak-ng data directory, plus a length scale. A speaker is chosen by id (`sid`), and no reference audio is needed
- Model packs from the sherpa-onnx release page contain the model file, `voices.bin`, `tokens.txt` and an `espeak-ng-data` folder
- Legacy KittenTTS voices: Bella, Jasper, Luna, Bruno, Rosie, Hugo, Kiki, Leo; English only; 24 kHz
- The AAR ships `armeabi-v7a`; the espeak-ng data is GPL-3.0, which keeps this engine in `full`

**Not known (this slice must find out)**

- Whether the Kitten config actually loads on **32-bit ARM** in the pinned build
- Speed and memory of nano int8 and micro on the Tab E
- The mapping between sherpa's speaker ids and the voice names, and each voice's gender
- The exact model filename in each variant's pack (v0.1 packs use a name like `model.fp16.onnx`; v0.8 may differ)
- The license terms of each variant's weights (legacy weights are marked Apache-2.0, but the KittenML repo says models are licensed separately and KittenTTS-2 uses its own license)

## 3. Scope

| In | Out |
| --- | --- |
| `SherpaKittenEngine`, pack discovery, registry entry in `full` | Bundling any model in the repo or the APK |
| Voice lab audition and benchmark, thread sweep | Kitten in `core`; Kitten streaming (backlog only) |
| A/B listening protocol and recommendation update | New UI beyond the existing engine and voice screens |
| One-chapter on-device render check | Kitten in Scribe (comes in Slice 21) |
| Licenses, README and docs | Upgrading sherpa-onnx (stay on the pinned version) |

## 4. Key decisions (log from D-141)

1. **`full`-only, namespaced `kitten:<id>`.** Registered by the flavor-specific provider; `core` contains no sherpa or espeak files.
2. **Packs are sideloaded and detected by file scan,** like Piper: a folder with a model file (name pattern, not a fixed name), `voices.bin`, `tokens.txt` and `espeak-ng-data/`. The variant is read from the folder name (for example `kitten-nano-en-v0_8-int8`) for display, size and version string. An optional small `pack.json` can carry verified voice names, genders and sids.
3. **Voices are listed by speaker id.** The engine asks the loaded model for its speaker count; names and genders come from a table keyed by variant family **only once KT0 has verified the mapping**. Until then the labels are "Kitten voice 0..N" with no gender claim.
4. **Narrator and dialogue share one loaded model** (two sids). This is a design advantage over Piper's two models (274 MB, about 0.30x file-synthesis on the Tab E per the ST0 numbers, 0.36x warmed RTF in the Slice 10 bench).
5. **Speed per request, not per load:** pass the speed with each generation call rather than the config's length scale, so speed changes need no reload.
6. **Sample rate from the engine** (expected 24 kHz) and the existing resampling utilities; no assumptions in the adapter.
7. **Licensing gate:** no model is bundled or committed; each variant's license, source URL and verified date go in `08-Licenses.md`; a variant with unclear terms is marked "personal use only" in the docs and in the pack list.
8. **Recommendation follows data:** keep System TTS as the tablet default (D-078) unless benchmark and A/B results justify Kitten as the recommended neural tier. Categories are the existing `EngineSpeedCategory` set (`STANDARD`, `TOO_SLOW`, `BACKGROUND` in `tts/EngineBenchmark.kt`); unknown engines default to `BACKGROUND` until measured.
9. **No streaming change.** If a variant sustains well above 1.3x, log a backlog item for neural streaming; do not start it here.
10. **Version bump decision:** a new engine is a minor feature, so propose Player `2.2.0`; confirm when closing the slice.

## 5. Work packages

### KT0: Feasibility, mapping and license check (S-M), do first

- Stage packs on the tablet's `Auloud/models` (microSD or internal): **nano int8**, **micro**, and nano fp32 for comparison, from the sherpa-onnx release page; record archive and extracted sizes
- **32-bit gate:** using the retained spike app, add a minimal Kitten bench (load, synthesize one sentence, report load time and RTF). If the load fails on `armeabi-v7a`, record the exact error and decide: build the engine anyway for 64-bit devices (and mark the tablet "not supported"), or stop. Use any 64-bit phone you have as a second device
- **Voice mapping:** read the sherpa-onnx conversion script for how `voices.bin` orders the voices, and confirm by generating the same sentence on the PC for each sid and for each named voice in the Python KittenTTS package, comparing the results; record the evidence and the table (and genders after your listening confirmation)
- **License check:** read the license text and model card for each variant you will test; record terms, whether redistribution is allowed, and the espeak-ng data note; log decision 7 with the findings
- Define the pack detection rules (decision 2), including how the model filename varies across variants
- **Verify:** findings written in `docs/kitten.md` with sources and dates; spike result in `docs/test-log.md`; decisions logged

### KT1: Engine adapter (M)

- `SherpaKittenEngine` behind `TtsEngine`, structured like the Piper adapter: complete-pack detection, lazy native load behind the existing handle seam, one loaded model, release when idle
- Generation by `sid` and `speed`; PCM returned at the engine's sample rate; thread count from settings; synthesis off the main thread; error mapping (pack incomplete, load failure, synthesis failure, timeout)
- Capabilities: multi-speaker, small load cost, sample rate from the model
- **Verify (JVM tests with a fake native layer):** pack detection variants, voice listing, speed and sid passing, load and release policy, error mapping, namespaced id parsing

### KT2: Packs and registry (S-M)

- Discovery in the conventional folders (internal `Auloud/models` and the SD card `Auloud/models`); incomplete packs listed with the reason (for example "missing voices.bin")
- Engine registered only in `full`; settings and Voice lab show Kitten packs with variant, size and license note
- `core` license scan, manifest check (no `INTERNET`) and both flavors' builds stay green
- **Verify (JVM tests):** discovery fixtures (complete, incomplete, mixed variants, nested folder); flavor registration test; scans pass for both flavors

### KT3: Voice lab audition and benchmark (M)

- Audition each voice with a narration line and a dialogue line; assign roles from the same pack
- Benchmark through the existing Voice lab: warmed RTF, load time, memory change and category, with the variant name and thread count in the report
- **Thread sweep:** run 1, 2, 3 and 4 threads and show RTF per setting; pick the default thread count from the result
- Results appear on screen and are recorded in `docs/test-log.md`
- **Verify (JVM tests):** report formatting, category calculation, thread-sweep selection; **device (you):** run nano int8 then micro and record in `docs/test-log.md`: variant, threads, warmed RTF, load time, memory, category, tablet temperature notes; mini only on a faster device

### KT4: A/B listening protocol (S, yours)

The agent prepares the materials; you listen.

- A 600-word passage with narration and dialogue from the C&P gold chapters (no Alice fixture exists yet; Slice 17 promotes one) or a public-domain passage you supply, plus a short list of tricky items (numbers, years, Mr./Dr., names)
- Render the same passage with System TTS, Piper (whatever packs you have), Kitten nano int8 and Kitten micro; 10-minute listens in random order, on headphones and on the tablet speaker
- A score sheet (1 to 5): clarity, naturalness, fatigue, narrator versus dialogue distinction, plus a count of mispronunciations; record in `docs/test-log.md`
- **Verify:** completed score sheet and a one-paragraph conclusion

### KT5: Recommendation and voice mapping (S-M)

- Update the per-device recommendation: among installed engines, recommend the best-quality tier whose category is `BACKGROUND` or better; the quality order is a constant table set from the KT4 result (logged as a decision)
- Cross-engine voice mapping includes Kitten voices (by gender when verified, otherwise same-index fallback); the slow-engine warning and render estimates use the measured Kitten numbers
- **Verify (JVM tests):** recommendation matrix across engine and category combinations; mapping to and from Kitten; the System TTS default remains unless the table says otherwise

### KT6: On-device render check (S-M)

- Render one chapter of an imported book with Kitten narrator and dialogue (two sids of one pack)
- Check: read-along timings correct (timings come from sample counts at the engine's rate); loudness of the two sids matched by the existing leveling; changing the dialogue sid marks the right chapters stale and re-render works; the fingerprint carries the engine id and a version string made from the variant and the sherpa-onnx version; actual render speed compared with the estimate; memory during render
- **Verify (JVM tests):** fingerprint and version-string tests; **device (you):** the checks above, recorded in the test log

### KT7: Docs, licenses and close-out (S)

- `08-Licenses.md` (variants, weights license findings, espeak-ng data, verified dates); `NOTICE` and the in-app licenses note that packs are user-supplied and carry their own licenses; README steps for downloading and placing a Kitten pack; `docs/constants.md` with the new measurements; test-log, DECISIONS, roadmap
- Both flavors build; lint clean; license scans green for both flavors
- Tag `slice-14`; version bump per decision 10
- **Verify:** every box in section 7 ticked

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | KT0 | know whether Kitten loads on the Tab E, which voice is which, and what the licenses say |
| 2 | KT1, KT2 | have the engine and packs tested on the JVM |
| 3 | KT3 | see real benchmark numbers on the tablet |
| 4 | KT4 | decide by ear whether it beats Piper and System TTS |
| 5 | KT5, KT6 | get a data-driven recommendation and a rendered chapter |
| 6 | KT7 | close the slice |

**Stop points:** if KT0 shows Kitten cannot load on any device you have, stop after recording it. If it loads but KT3 and KT4 show it is both slow and worse than Piper, finish KT1 to KT3 so the engine exists for faster devices, record the result, and skip the recommendation change. Pack switcher backlog: the engine serves one pack at a time (first complete pack wins); comparing nano against micro means swapping folders between runs.

## 7. Definition of done

- [ ] KT0 findings documented: 32-bit result, voice mapping evidence, license terms per tested variant
- [ ] Engine, pack discovery and registry built; all JVM tests green in both flavors; lint clean
- [ ] `core` flavor scans green; no `INTERNET` permission in any release manifest
- [ ] Benchmarks for nano int8 and micro on the Tab E recorded (or an explicit, documented "does not load")
- [ ] A/B scores and the recommendation decision logged
- [ ] One chapter rendered on-device with Kitten and checked for sync, level and stale behavior
- [ ] Docs, licenses and decisions updated; roadmap ticked only for verified items; tagged `slice-14`

## 8. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Kitten config does not load on 32-bit ARM | KT0 gate first; use a 64-bit phone as a second device; engine still useful for faster devices |
| Too slow or too heavy on the tablet | Benchmark nano int8 first; thread sweep; keep Piper and System TTS recommendations |
| Voice names or genders mapped wrong | Evidence-based mapping in KT0, labels stay numeric until verified |
| Model license is not Apache-2.0 for a variant | Check each variant's terms; do not bundle; mark personal-use packs; skip a variant if unclear |
| Pronunciation problems (numbers, abbreviations, names) | A/B list of tricky items; note findings; Kitten stays optional |
| Model filename and layout differ per variant | Pattern-based detection and tests with fixtures per variant |
| espeak-ng data path trouble on the SD card | Test internal and SD placements in KT2 and KT3 |
| API drift if sherpa-onnx is upgraded | Stay on the pinned 1.13.8; no upgrade in this slice |
| Scope creep into streaming | Logged as a backlog item only |

## 9. Working with the agent

- One work package per session; it implements and runs JVM tests with fakes, and it must not claim device or listening results; **you** stage packs, run the benchmark and A/B listening, and approve the license findings
- No new dependency is expected; stay on sherpa-onnx 1.13.8; no model files in the repo or the APK
- Commit per work package (`KTn: summary`); tag `slice-14`

## 10. Next

Slice 15 (Rust workspace, spec core and parity harness) is independent of this slice and could start in parallel, but finish KT0 and KT3 first so the Kitten answer arrives early. Kitten in the Rust Scribe comes in Slice 21, with Piper and the model manager.
