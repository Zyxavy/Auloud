# Slice 7 Implementation Plan: Benchmark Spike and Gate

Project: throwaway Android benchmark app (deleted after the gate) + device runs on the Galaxy Tab E. Source of truth for requirements: `docs/09-V2Roadmap.md` "Slice 7" plus D-066 (espeak-ng license path, OPEN). This slice decides D-066 and gates everything downstream (PW7 engines, PW8 recommendation table, Slice 10 rendering targets). It changes no shipped code: not the Player app, not Scribe.

## 1. Goal

Replace opinions with numbers: which TTS tiers run on the Tab E at which real-time factor, at what RAM/thermal cost, and what exactly the espeak linkage is — then pick the license path and confirm or rewrite the v2 targets.

**Done when** (09-V2Roadmap Slice 7 exit criteria, as amended by D-077):

- An engine tier table with measured numbers (warmed RTF, load time, RAM, 10-min battery/thermal) for **Piper and System TTS `synthesizeToFile`** on the Tab E. **Kokoro is gated to faster devices (D-077): no on-tablet run** — the 330 MB model plus runtime on 1.5 GB RAM settles it without a measurement session.
- Two-voice measurements: two Piper models at once (RAM), System TTS per-utterance voice switching (latency)
- The espeak-ng/GPL situation established as fact (what each engine path links)
- D-066 decided (option A/B/C) with the license review; targets confirmed (roughly RTF 1.0 background, 1.3 streaming) or the fallback declared (System TTS + background Piper-low on the tablet, Kokoro for faster devices)

## 2. Research findings (verified 2026-10-05, no guessing)

- **Runtime:** sherpa-onnx via JitPack (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.8`, POM declares Apache-2.0). Demo app `android/SherpaOnnxTts` sets `minSdk 21` — API 24 is covered.
- **ABIs in the 1.13.8 AAR (downloaded, unzipped, inspected):** `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — each with `libonnxruntime.so` + `libsherpa-onnx-{c-api,cxx-api,jni}.so`. The 32-bit path exists; the spike confirms on-device which ABI the Tab E actually loads (`Build.SUPPORTED_ABIS`, 32/64-bit).
- **espeak linkage (D-066 input, technical fact):** `cmake/espeak-ng-for-piper.cmake` builds espeak-ng from source (csukuangfj fork, pinned hash) and forces `BUILD_SHARED_LIBS OFF` around it; the AAR ships no separate `libespeak` (only onnxruntime + 3 sherpa libs); the armv7 `libsherpa-onnx-jni.so` contains `espeak`/`mbrola` strings. Reads as statically linked GPL-3.0 code inside the sherpa library — the license review decides what that means (option A vs B/C), this plan only records the fact. Not legal advice.
- **Kotlin TTS API:** `OfflineTts(OfflineTtsConfig(model = OfflineTtsModelConfig(vits|kokoro, numThreads, debug)))`, `generateWithConfigAndCallback(text, GenerationConfig(sid, speed, ...))`, `audio.save()`, `tts.release()`. Piper needs `model.onnx + tokens.txt + espeak-ng-data`; Kokoro needs `model.onnx + voices.bin + tokens.txt + espeak-ng-data`.
- **Model packs:** Piper lessac-low is already local (`scribe/models/piper/`); tokens.txt is NOT in rhasspy piper-voices — generate it from the `.onnx.json` `phoneme_id_map` (ids in order, verify count against `num_symbols`), else lift the pack from a sherpa prebuilt APK. Kokoro pack `kokoro-en-v0_19.tar.bz2` (model 330 MB + voices 5.5 MB + espeak data) downloads in background; if the link will not deliver it, the Kokoro row is measured on the faster device or deferred with the fallback declared.
- **System TTS tier needs no spike code:** PW6's adapter path is the harness (audition on device). The spike measures `synthesizeToFile` latency + voice-switch cost directly.

## 3. Scope

| In | Out |
| --- | --- |
| Throwaway `:spike` module (device-info screen, per-engine bench, results export to `/Auloud/spike-results.txt`) | Any change to `:app` or Scribe |
| JitPack sherpa-onnx 1.13.8 (pinned; license recorded like any dep) | Committing models, AARs, APKs, or results (all gitignored/sideloaded) |
| Sideloaded model packs via microSD (`/Auloud/spike-models/`, same sneakernet as v1) | `INTERNET` permission (the spike is offline like the app) |
| 10-minute thermal/battery observation per engine | Overnight soak (that is Slice 12) |
| Gate table + D-066 decision in `DECISIONS.md` | Engine implementation (PW7) |

## 4. Work packages

### S7A: Research (DONE, this plan)

JitPack/AAR/ABI/espeak findings above (all verified against artifacts, not docs claims). No code, no suites. The AAR file stays in Temp (re-downloadable, never committed).

### S7B: Throwaway spike app (M)

New `player/spike/` module (applicationId `app.auloud.spike`, minSdk 24, debug only, deleted after the gate):

- Device screen: `SUPPORTED_ABIS`, 64-bit?, RAM class, Android version — ground truth first, numbers after
- Bench screen per engine: load wall time, warmed RTF over 20 fixed sentences (audio-sec / wall-sec), peak PSS (Debug.MemoryInfo), model bytes on disk
- Two-voice screen: two Piper instances at once (PSS delta), Kokoro sid-A/sid-B alternation (switch overhead vs same-sid), System TTS alternating voices per utterance (mean switch gap)
- Battery screen: 10-minute continuous synth per engine (start/end %, °C via BatteryManager, heat note) — charging and unplugged are separate runs
- Export: append-only `/Auloud/spike-results.txt` (timestamped table the user pastes back)
- No release build, no signing, no Play rules: `assembleDebug` + microSD sideload like v1
- **Verify:** builds clean; text-only smoke on any available device/emulator that it lists engines without crashing (numbers only count from the Tab E)

### S7C: Device runs (user, with a checklist)

1. Copy the Piper pack to `/Auloud/spike-models/` (lessac `.onnx` + `tokens.txt` + `espeak-ng-data`; no Kokoro folder needed — D-077)
2. Sideload spike APK, run Device screen first (paste ABIs)
3. Piper, 2x Piper, System, Battery 10-min (charging, then unplugged if time allows)
4. Paste `spike-results.txt` back; agent tabulates the tier table

### S7D: Gate (agent + user)

- Tier table in `docs/09-V2Roadmap.md` (or the plan appendix): engine, warmed RTF, load s, peak MB, 10-min %/°C, two-voice notes, verdict per tier
- D-066 decided (A/B/C) with the license review; `08-Licenses.md` section 5 updated to the chosen path
- Targets confirmed or fallback declared; PW7 unblocked with the engine list; PW8 recommendation table filled
- Delete `player/spike/` (throwaway means throwaway; the plan + results stay)

## 5. Risks (from 09-V2Roadmap §7, spike-specific)

- Tab E is 32-bit userspace: the armeabi-v7a .so must load (first thing the Device screen proves; if it does not, the gate answers itself)
- 330 MB Kokoro model on 1.5 GB RAM: may OOM on load — that IS a result (record it, declare the fallback)
- tokens.txt generated from `.onnx.json`: if synth output is garbage, the map is wrong, not the engine (validate counts first, ears second)
- Heat throttling skews later runs: cool-down between engines, note order in results
- No adb: everything typed on-screen and exported to the file (same sneakernet discipline as v1)
