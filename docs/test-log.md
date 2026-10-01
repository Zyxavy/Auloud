# Device test log

Confirmed runs on the Tab E only. Template per check (see `docs/WP10-Runbook.md`):

```
Date / build (commit) / device state:
Test: C#
Steps:
Expected:
Actual (include overlay readings):
Result: pass | fail | needs retest
```

---
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
