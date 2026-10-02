# Device test log

Confirmed runs on the Tab E only. Template per check (Slice 3 checks: see `docs/Slice3-Runbook.md`):
```
Date / build (commit) / device state:
Test: C#
Steps:
Expected:
Actual (include overlay readings):
Result: pass | fail | needs retest
```

---
## MV0 voice palette (2026-10-02, user listening test over 54 samples)

- Narrator: `am_onyx` (18)
- Characters: `bf_isabella` (23), `bm_lewis` (28), `im_nicola` (38), `jf_alpha` (39), `zf_xiaoxiao` (49), `am_eric` (14)
- Generic female: `af_bella` (03); generic male: `am_adam` (12)
- User note: current voices sound good and are enough for v1; v2/v3 should add more diverse voices (logged in D-036).

---
## RA11 acceptance (2026-10-01, Tab E, user run)

Build: debug APK from `dev/slice3` (through `13a1366`, incl. the Listen dead-end fix). Bundles: `ra-beep`, `ra-long`, C&P sample, Yellow Wallpaper.

- C13 beep sync (start/middle/end, 1.0x + 1.5x, lag avg/max under ~300 ms): pass
- C14 long chapter scroll/highlight (5,000 sentences, flings): pass
- C15 tap to jump (incl. italic sentences, audible within ~500 ms): pass
- C16 detach + back to now (no fighting/jitter): pass
- C17 mode switches at start/middle/end + across chapter change, position kept: pass
- C18 Read scroll-settle, then Listen starts at that sentence: pass
- C19 speeds (0.75x/1.5x/2.0x in sync) + every timer option incl. end-of-chapter and screen-off pause: pass
- C20 PSS at 0/30/60 min, no growth trend: pass
- C21 real-book hour (C&P + Yellow Wallpaper, mixed interaction): pass
- Listen dead end found in testing (no way back to Read modes): fixed (`13a1366`, mode switcher slot on the Listen screen) and retested: pass

Note: qualitative passes; exact overlay lag/PSS numbers were not recorded. Slice 3 "done when" (300 ms sync, lossless switches, stable hour) signed off by the user.
## SW11 acceptance (2026-10-01)

- C&P sample (67 sentences, fragment): `scribe build` → 1 chapter, 7:20 audio in 105.9 s wall (**RTF 4.16x**); `scribe validate` valid; `scribe inspect` correct; imports into Slice 1 Player and **plays on the Tab E, user-confirmed** (listen-only; read-along is Slice 3).
- Yellow Wallpaper stress (local-only, 6621 words): 2 chapters, 36:00 audio in 700.6 s wall (**RTF 3.08x**); 0 skipped, 0 failed; cover extracted; bundle validates.
- Real-time factors recorded for slice close-out.
## SW0 engine spike (2026-10-01, laptop CPU, RTX 4050 present but unused)

- Kokoro-82M via kokoro-onnx 0.6.1 + onnxruntime 1.23.2, voice af_heart: 23.3 s audio in 16.8 s wall over 5 varied sentences (narration, dialogue, abbreviations, ellipses, long) -> **RTF 1.38** (model load excluded).
- Install pain: official `kokoro==0.9.4` uninstallable (tokenizers 0.10.3 sdist, no cp310/cp314 Windows wheels, needs Rust); kokoro-onnx installed cleanly (21 wheels). espeak-ng 1.52.0 via .msi, no issues. Model kokoro-v1.0.onnx + voices-v1.0.bin from thewh1teagle releases.
- Subjective: voice good enough; Piper comparison skipped. Choice: Kokoro/af_heart (see D-023).
## Slice 1 device verification (user run, 2026-09-29, Tab E Android 7.1.1)

Build: debug APK from `slice-1` (HEAD f77bbc6 + watch-folders). Bundle: hand-made TestBook (2 tone chapters) + TestBook-Bad (missing ch002.mp3).

- C1 import (valid + corrupt with reason, no crash): pass
- C2 playback basics: pass
- C3 battery prompt once + settings entry: pass; prompt leads to app > optimize battery usage
- C4 30-min screen-off: pass, position correct, no restarts
- C5 2-h screen-off: pass
- C6 bad chapter skipped with message: pass
- C7 recents swipe: recorded, no issue
- C8 lock-screen + notification controls: pass
- C9 interruptions: pass
- C10 resume after restart + reboot: pass
- C11 overlay matches audio: pass
- C12 battery drain: no issue
- Overall: all pass, nothing odd
## MV4 attribution eval (2026-10-02, laptop, `dev/slice4` commit MV4)

- Harness: `uv run python dev/eval_speakers.py --predictor mv4` (strict) and
  `--predictor mv4 --alias-aware`, over the n=5 seed
  (`spec/fixtures/speakers-gold/crime-and-punishment-ch01.yaml`).
- The seed carries anchors and excerpts only, no paragraph texts, so every
  gold context arrives with empty `block_text`: no tag is visible and rules
  1-4 cannot fire. First line falls to `unknown`, the rest repeat it via
  `fallback`. This run validates the wiring format, not accuracy.

| predictor | overall | high | medium | low |
| --- | --- | --- | --- | --- |
| baseline | 0/5 = 0.0% | n/a | n/a | 0/5 = 0.0% |
| mv4 strict | 0/5 = 0.0% | n/a | n/a | 0/5 = 0.0% |
| mv4 alias-aware | 0/5 = 0.0% | n/a | n/a | 0/5 = 0.0% |

Accuracy by rule (mv4 strict; alias-aware identical, the seed has no
`surface` fields yet):

| rule | accuracy |
| --- | --- |
| explicit | n/a (0 lines) |
| pronoun | n/a (0 lines) |
| alternation | n/a (0 lines) |
| continuation | n/a (0 lines) |
| fallback | 0/4 = 0.0% |
| unknown | 0/1 = 0.0% |

- The 80%/90% targets from the Slice 4 plan are explicitly NOT promised on
  n=5: the set is a format seed, and 0/5 here reflects missing paragraph
  texts, not rule quality. Rule quality is pinned by 21 unit tests in
  `scribe/tests/test_attribution.py` (each rule in isolation, both tag
  sides, gender-mismatch rejection, alternation sustain/break, continuation
  chains, fallback/unknown, confidence levels). Retune and re-record when
  the 100-150 line full-novel gold set lands (still owed).
