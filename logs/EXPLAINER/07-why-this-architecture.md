# Why this architecture

The reasoning behind the decisions a new contributor is most likely to question. Each entry ends with the decision id so you can read the full record.

## Why two programs instead of one app

The tablet cannot do the heavy work and the PC cannot go on the couch. Scribe owns quality (multi-voice neural TTS, caches, retries, human-fixed casts); the Player owns listening (playback, position, read-along). The bundle between them lets each release on its own cadence. Merging them would put a Python ML pipeline on Android 7.1.1, which is not a plan. (D-001 and the v1 architecture.)

## Why the tablet renders at all, if the PC does it better

Because requiring a PC for every book cuts off the actual user: someone with an old tablet and an EPUB. On-device rendering trades quality for independence (two voices, slower than PC, overnight renders). Streaming covers the gap between import and finished render. The PC path stays untouched as the quality option. (v2 roadmap, D-130 through D-138.)

## Why two voices on-device instead of per-character

Memory and engine latency. Each extra voice is another loaded model and another voice-switch gap in a live stream. Two roles (narrator, dialogue) carry most of the listening benefit for a fraction of the cost, and the attribution machinery the device would need is the weakest part of Scribe anyway. Per-character stays a PC feature. (v2 scope, decisions 7 and 8.)

## Why the GPL flavor split

Piper speech needs espeak-ng, which is GPL, and espeak-ng is statically linked inside the sherpa-onnx binary. There is no clean-room way to ship those bytes under Apache-2.0, so the app ships twice: `core` without them, `full` with them plus the license text and a written source offer. Same app id, so either upgrades the other in place. (D-126 option A.)

## Why hypotheses plus a bench instead of just benchmarking

Early engine numbers came from the wrong method (file synthesis) for the question asked (live speech). The project records the hypothesis, names the measurement that would settle it, and builds the spike that takes the measurement. Twice this saved a wrong conclusion: the streaming gate was first closed on file numbers, re-opened on live-architecture evidence, measured properly, and only then accepted. (D-131, D-137, D-138.)

## Why the bundle spec is law

Three implementations write or read it (Python Scribe, Kotlin ingest, Kotlin reader) across two release trains. Every silent divergence would surface as a corrupt book on a device with no debugger attached. The spec-first rule with shared fixtures makes divergence loud and early. (Bundle spec, `spec/fixtures/`.)

## What comes next

v3 ports Scribe to Rust with a Tauri desktop app (Windows plus Linux); the Python Scribe stays canonical until parity. All Android platform work (newer APIs, word-level highlight) waits for v4. (D-133.)
