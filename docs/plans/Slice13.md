# Slice 13 Implementation Plan: Live Streaming (press play, hear it now)

Project: Auloud Player (main). Follows `09-V2Roadmap.md` (Slice 13, v2.1) with the premise **changed by your ReadEra evidence**: the earlier file-synthesis numbers (System TTS 0.86x-2.56x, D-131) measure `synthesizeToFile`, which has per-utterance overhead and is not how readers stream. A live `speak()` pipeline may keep up in real time on the Tab E. This slice therefore starts with a measurement gate, then builds a live System TTS streaming path if the gate passes.

## 0. Before Slice 13 (loose ends from Slice 12 - status 2026-10-10)

- [x] Tags `slice-12` and `v2.0.0` exist (tagged at VC8 commit `31119b1`); signing key at `D:\keys\auloud-release.keystore`, backup still owner duty
- [x] Over-install procedure run on the tablet (owner verdict 2026-10-10, boxes ticked in `docs/ReleaseSigning.md`)
- [ ] Follow the README v2 walkthrough once from a fresh install (it is marked untested)
- [ ] If you can, add rough numbers to the soak entry (hours rendered, kills, temperature); it was accepted on a one-line verdict, so the retuned constants have no data behind them

## 1. Goal

Press play on an **unrendered** chapter and hear audio within a second or two, with the sentence highlight following the voice, screen off, narrator and dialogue voices, speed and sleep timer, and no rendering wait. Rendering stays available for saved audio; streaming is the "listen now" path.

**Done when:**

- On the Tab E, a 30-minute listen of an unrendered chapter has no audible gaps at 1.0x and 1.5x, with the screen off for at least 30 minutes
- The highlight follows the spoken sentence; pause, resume, seek and tap-to-jump work
- If the measurement gate fails, the slice ends with a recorded no-go and a reduced scope (below), not a half-built feature

## 2. How live streaming differs from rendering

|  | Rendered (Slices 10-11) | Live (this slice) |
| --- | --- | --- |
| Audio | Saved AAC files, exact timings | Spoken by the system TTS engine; nothing saved |
| Position | Milliseconds (and sentence ids) | **Sentence ids only**, driven by utterance start callbacks |
| Highlight | From timing JSON | From "utterance started" events (sentence-level, which is the granularity the reader already uses) |
| Voices | Batched per chapter, no switching cost | Voice chosen per utterance at queue time; switching may add a gap |
| Leveling | Baked into audio | Per-role volume parameter, calibrated |
| Pauses | Silence in the audio | Silent utterances (250, 500, 800, 1000 ms; 100 ms between a quote and its tag) |
| Engines | System and neural | **System TTS only** (neural Piper at 0.36x can't stream) |

Android 7.1.1 has start, done and error callbacks per utterance but not per-word range callbacks (those arrive in API 26), so sentence-level highlighting is the right design.

## 3. Scope

| In | Out |
| --- | --- |
| Measurement gate on the tablet | Neural-engine streaming |
| Live System TTS stream for unrendered chapters | Saving the streamed audio (use rendering for that) |
| Media session, notification, Bluetooth, screen-off | Word-level highlight (needs API 26+, v4) |
| Sentence-id progress, reader live mode, tap-to-jump | Per-character voices |
| Role volume calibration, pauses, speed, sleep timer |  |
| Hand-off to rendered chapters |  |

## 4. Key design decisions (log from D-133 onward)

1. **Measurement gate decides everything.** The spike app is kept as the measurement tool. Metrics, not RTF: the **gap** between one sentence finishing and the next starting, with several sentences queued ahead. Go thresholds (proposals): median gap 150 ms or less, 95th percentile 400 ms or less, no gap over 1 s across 30 minutes, at 1.0x and 1.5x.
2. **Queue shallow, not deep:** keep about 3-5 sentences queued ahead and refill on each "done" event, so seek and stop are instant and memory stays flat.
3. **Voice switching strategy depends on the gate.** Options to measure: (a) one `TextToSpeech` instance switching voices per utterance; (b) two instances, one per role, with sequential hand-off on "done" events. If both leave audible gaps in dialogue-heavy text, the fallback is **single-voice streaming** (narrator voice for everything), with two voices only in rendered audio.
4. **Hosting:** a custom Media3 player (a `SimpleBasePlayer` subclass) inside the existing playback service, so notification, lock screen, Bluetooth buttons, audio focus and the sleep timer keep working. Rendered chapters keep using the existing player; at chapter boundaries the service hands off between the two.
5. **Position is a sentence id** in streamed chapters (reusing the Slice 9 mechanism); converted to milliseconds only if the chapter is later rendered.
6. **Leveling by volume parameter**, calibrated once per role by synthesizing a standard sentence to a file, measuring loudness, and storing a relative volume. It can attenuate but not boost, so the louder voice is turned down.
7. **Streaming and rendering don't compete:** starting a live stream pauses the render job (the engine and CPU are shared on a weak tablet), and rendering resumes when the stream stops; an optional "render while listening" setting stays off by default.
8. **Only offline voices** are used; engine death or disconnect is recovered by rebinding and resuming at the current sentence.

## 5. Work packages

### ST0: Measurement gate (S-M), do first

Extend the spike app (or a debug screen) to run these on the Tab E with the system engine ReadEra uses (and the other installed engine, if any):

- Queue 20 sentences of a real book passage; log start and done times per utterance; compute the gap distribution at 1.0x and 1.5x
- Start latency: tap to first audio
- Voice alternation: narrator/dialogue pattern with (a) one instance and (b) two instances; gap per switch
- Silent-utterance pause accuracy for 100, 250 and 1000 ms
- 30-minute run with the screen off from a foreground service: underruns, kills, temperature
- Stop, restart-from-sentence latency (for seek)
- **Exit criteria:** numbers in `docs/test-log.md`, thresholds from decision 1 applied, decision 3 settled, and a go, reduced-go (single voice) or no-go logged

### ST1: Design note (S)

- Architecture of decisions 2-8, state machine for the stream, the hand-off with rendered chapters, reader live mode, error handling; `docs/streaming.md`
- **Verify:** reviewed; decisions logged

### ST2: Stream core logic (M)

- Pure Kotlin: queue planner (next N sentences with utterance ids, voice and volume per sentence, silent pauses), event reducer for start/done/error/stop, position tracking by sentence id, refill policy, pause (stop at a sentence boundary), resume, seek, speed change (re-queue from the current sentence), voice or role change
- Tested against a fake TTS driver
- **Verify (JVM tests):** ordering, refill, stale events after stop are ignored, duplicate or missing "done", error mid-queue, seek during queue, speed change mid-sentence, pause then resume at the right sentence

### ST3: TTS streaming driver (M)

- Production driver over the system `TextToSpeech`: utterance ids, parameters (volume), listener, silent utterances, stop, voice set at queue time, engine rebinding; two-instance variant if decision 3 chooses it; error mapping to the core's events
- **Verify (JVM tests):** driver seam with fakes; **device:** behaves as the gate measured

### ST4: Player facade and service integration (L)

- Custom player in the playback service: media session state, notification controls, Bluetooth buttons, audio focus, noisy-audio handling, wake lock, foreground, sleep timer, saved speed
- Hand-off with the existing player at chapter boundaries; progress saved every few seconds as a sentence id; resume after process death
- Streaming pauses the render job and resumes it afterwards (decision 7)
- **Verify (JVM tests):** state machine, hand-off, progress saving; **device:** screen-off, swipe-away, call interruption, headset unplug

### ST5: Reader live mode (M)

- Read + listen on unrendered chapters: highlight driven by the current sentence id, auto-scroll, detach and "back to now", tap a sentence to restart the stream from it
- Chapter progress by sentence fraction instead of a time seek bar; label "Live voice, not saved"
- Hub and library: **Listen now** on unrendered books and chapters when streaming is available (otherwise "Render first"); existing rendered behavior untouched
- **Verify (JVM tests):** view-model states, tap-to-restart, gating; **device:** visual check

### ST6: Leveling and pacing (S-M)

- Calibration action per role (synthesize a standard sentence to a file, measure, store the relative volume); apply on each utterance
- Pause lengths as silent utterances matching the rendered rules; 100 ms tag pause
- **Verify (JVM tests):** volume ratio math, pause scheduling; **device:** voices sound level-matched and paced like rendered books

### ST7: Acceptance (you)

- [ ] Start latency under about 2 seconds from tap to audio
- [ ] 30-minute listen at 1.0x and 1.5x: no audible gaps
- [ ] Screen off for 30 minutes and for 2 hours: audio continues, nothing killed
- [ ] A dialogue-heavy chapter: voice switching acceptable (or single-voice fallback in effect)
- [ ] Highlight follows the spoken sentence; pause, resume, tap-to-jump, seek work
- [ ] Speed change mid-chapter; sleep timer; Bluetooth buttons; incoming call; swipe away
- [ ] Streaming a chapter, then continuing into a rendered chapter: hand-off works
- [ ] Rendering and streaming don't fight; battery temperature and drain acceptable
- [ ] Side-by-side with ReadEra on the same book and engine: speed and gaps comparable, and Auloud keeps playing with the screen off
- [ ] Existing rendered playback, read-along, and rendering unaffected

## 6. If the gate fails

- **Reduced go:** stream with a single voice (narrator), keep two voices for rendered audio only
- **No-go:** record the numbers, mark streaming not feasible on the Tab E, and keep background rendering as the listening path; the slice then ends after ST0 and ST1

## 7. Order

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | ST0 | know whether live streaming works on your tablet |
| 2 | ST1, ST2 | have the core logic tested on the JVM |
| 3 | ST3, ST4 | hear a live stream with the screen off |
| 4 | ST5, ST6 | read along and get matched levels |
| 5 | ST7 | accept |

## 8. Definition of done

- [ ] Gate recorded with numbers and a go, reduced-go or no-go decision
- [ ] If go: all verifications passed, suites green in both flavors, device acceptance passed on the Tab E
- [ ] Decisions logged; `docs/streaming.md` written; README and roadmap updated; tagged `slice-13` (v2.1)

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Voice switching causes gaps | Gate measures one and two instances; single-voice fallback |
| Callbacks arrive late or out of order | Reducer ignores stale events; sentence-level highlight tolerates tens of milliseconds |
| Engine service killed or disconnected | Rebind and resume at the current sentence; logged |
| Screen-off live speech stops on Samsung | Foreground service and wake lock; measured in ST0 and ST7 |
| Loudness mismatch between voices | Calibrated volume per role; attenuate only |
| Rendering and streaming compete for CPU | Streaming pauses rendering; optional toggle off by default |
| Seek and speed changes feel sluggish | Shallow queue; restart from sentence; measured latency |
| Different engines behave differently (Google vs Samsung) | Test both if installed; document the recommended engine |
| Reader assumes milliseconds everywhere | Live mode uses sentence ids; tests for both modes |
| Android 7.1.1 limits (no word callbacks) | Sentence-level design; word-level is a v4 item |

## 10. Working with the agent

- One work package per session; it implements and tests with fakes; **you** run ST0 and all device checks and report the numbers
- It must not claim device results or invent gate numbers
- No new dependency without a license and `minSdk 24` check
- Commit per work package (`STn: summary`); tag `slice-13`

## 11. After this

v1.1 backlog (LLM attribution, real EPUB rendering, Page view, Wi-Fi transfer, bookmarks, themes), per-role audio caching, per-character device voices, a device copy of Scribe books, and v3 (Scribe Rust/Tauri v2 desktop port for Windows 11 and Linux, D-133) then v4 (newer Android, which also brings word-level range callbacks, plus Scribe on Android).