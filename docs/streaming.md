# Live streaming design (Slice 13, ST1)

Press play on an **unrendered** chapter and hear the system voice within a
second or two, with the sentence highlight following, screen off, two
voices, speed and sleep timer, no rendering wait. Rendering stays the
saved-audio path; streaming is the "listen now" path. Plan:
`docs/plans/Slice13.md`. Premise (D-131 amendment): only a live `speak()`
path can reopen streaming on the Tab E; file-synthesis numbers do not
apply, so every claim below waits on the ST0 gate numbers.

## 1. Layers

```
reader live mode (ST5)          highlight <- currentSid, tap-to-restart
playback service (ST4)          session, notification, focus, sleep, saves
stream player facade (ST4)      media-session-facing state over the core
stream core (ST2, pure Kotlin)  planner, event reducer, sid position
stream TTS driver (ST3)         speak/stop/rebind over system TextToSpeech
```

The core never imports Android or Media3: it consumes a driver seam and
emits state, so ST2 is fully JVM-tested against a fake driver. The
service owns the Android half (focus, noisy-audio, wake lock, saves).

Proposed homes (ST2-ST4 create these; names may move once, then stay):

- `tts/StreamPlanner.kt`: queue plan (next N utterances with ids, voice
  per sentence, volume per role, silent pauses), refill policy.
- `tts/StreamEvents.kt`: event reducer (start/done/error/stop) plus
  sid position tracking.
- `tts/StreamTtsDriver.kt` + `AndroidStreamTtsDriver`: speak with
  utterance ids, volume param, silent utterances, stop, rebind.
- `playback/StreamPlayer.kt`: service-side facade (state machine below,
  progress saves, render pause/resume, sleep/speed wiring).

## 2. Queue: shallow, refilled on done (decision 2)

Keep about 3-5 sentences queued ahead; every `done` event plans the
next one. Shallow because seek and stop must be instant (stop = one
`stop()` call, no draining) and memory stays flat on 1.5 GB RAM.

Utterance ids are `stream-<chapter>-<sid>-<attempt>`: the attempt
counter makes post-stop events identifiable as stale (section 6).

Pause stops at a sentence boundary: pause = `stop()` plus remember the
current sid; resume re-queues from that sid. Speed or voice change =
stop plus re-queue from the current sid at the new setting.

## 3. Voice switching: decided by the gate (decision 3)

Two variants behind the driver seam, measured in ST0 (LiveSw vs Live2x):

- (a) one `TextToSpeech` instance, voice set per utterance;
- (b) two instances, one voice each, sequential hand-off on `done`.

The smaller switch gap wins. Fallback, no shame: if both gap audibly
in dialogue-heavy text, stream single-voice (narrator for everything)
and keep two voices for rendered audio only. The planner already tags
each utterance with its role, so the fallback is a voice-mapping change,
not a planner change.

## 4. Hosting in the playback service (decision 4)

The stream player lives inside `PlaybackService` next to the ExoPlayer
path, so notification, lock-screen, Bluetooth buttons, audio focus, the
wake lock and the sleep timer keep working unchanged. Rendered chapters
keep the existing player; the service routes per chapter (section 5).

Session state shape: the plan proposes a custom Media3 player (a
`SimpleBasePlayer` subclass). ST4 must first confirm that class exists
in the pinned Media3 version with `minSdk 24`; if it does not, the
fallback is a plain state broadcaster driving the existing session UI
instead of a second `Player`. Either way the service owns exactly one
audible path at a time.

## 5. Chapter routing and hand-off

`PlaybackQueue.gateFor` today refuses books that are not fully
rendered. Streaming changes the unit of routing from book to chapter
(`isRenderedChapter` already exists per chapter):

- rendered chapter: ExoPlayer path, as today;
- unrendered chapter: stream path, position by sid.

Next/previous chapter and auto-advance switch paths at the boundary;
`ChapterMediaMap` keeps describing the rendered subset only (streamed
chapters never enter the ExoPlayer playlist). A book that finishes
rendering mid-listen picks up the rendered path on the next chapter
change, never mid-sentence.

## 6. Position is a sentence id (decision 5)

Streamed chapters reuse the Slice 9 mechanism: saves go through the
existing `ProgressRepository.save(bookId, chapterIndex, 0L,
sentenceSid = sid)` path (the same call `UnrenderedReaderViewModel`
uses), every few seconds plus on pause, chapter change and teardown
(reuse `ProgressSavePolicy`). If the chapter is later rendered, the
existing sid-to-ms conversion applies on first rendered open.

Stale-event rule (core reducer): an event is applied only when its
utterance id belongs to the current attempt; post-stop, post-seek and
duplicate `done` events are ignored. A missing `done` (engine skipped
the callback) advances on the next `start`; a present-but-late `done`
for the current sid is harmless.

## 7. Leveling by volume param (decision 6)

`KEY_PARAM_VOLUME` per utterance, calibrated once per role: synthesize
a standard sentence to a file, measure loudness, store the relative
volume (ST6). The param attenuates only, so the louder role is turned
down to the quieter one. Calibration values live with the voice
settings; uncalibrated roles play at full volume (no invented
correction).

Pauses are silent utterances matching the rendered rules (250/500/800/
1000 ms plus the 100 ms quote-tag pause); LiveSil measures their
accuracy on-device.

## 8. Streaming pauses rendering (decision 7, D-134)

Start of a stream sends `RenderService.ACTION_PAUSE` for the same book;
the stream records that it paused the job. When the stream stops, it
sends `ACTION_RESUME` only for jobs it paused itself (D-134: an
explicit user pause stays paused; a guard pause stays under guard
rules). The optional "render while listening" setting stays off by
default. Renders run unplugged (D-130), so render-plus-stream
contention is real on this chip; the ST0 long run should note heat.

## 9. Offline voices and recovery (decision 8)

Only voices with no network requirement are listed or used (same filter
as the render path). Engine death or disconnect: rebind, then resume at
the current sid (position was saved within seconds, worst case the
sentence restarts). Error mid-queue: mark the sentence failed, speak
the next one, keep listening; three consecutive failures stop with the
shaped message from section 10. Process death: the sid save resumes at
the sentence on next open.

## 10. Reader live mode (ST5 shape)

`UnrenderedReaderViewModel` already positions by sid with follow/
detach/back-to-now and tap-to-move. Live mode adds one audio source to
it: highlight follows the stream core's current sid; tap-to-jump
restarts the stream from the tapped sid (unlike read-only tap, audio
is lost, so the stream restarts audibly from there). Chapter progress
shows sentence fraction, not a time bar; the screen labels the mode
"Live voice, not saved". Hub and library show "Listen now" on
unrendered books only when streaming is available (gate passed plus a
usable offline system voice present), else "Render first" as today.

## 11. Refusals (shaped messages)

- No offline system voice: refuse like the render path refuses, naming
  the missing voice, not a crash.
- Single-voice fallback in effect: the book still streams; the voice
  screen says so (two voices stay available in rendered audio).
- No-go gate outcome: no stream code ships; the slice ends after ST0
  plus this note (plan section 6).

## 12. API 24 notes

`speak`, `playSilentUtterance`, `KEY_PARAM_VOLUME` and the
start/done/error utterance callbacks all exist since API 21; nothing
here needs more. Per-word range callbacks need API 26, so highlight
stays sentence-level (word-level is a v4 item, D-133). No new
permission: streaming speaks to audio out and needs none; a wake lock
for screen-off (if ST0 shows the activity path dies) rides the
existing foreground-service notification, decided in ST4 on evidence.

## 13. Build order after the gate

1. ST2 core (`StreamPlanner`, `StreamEvents`) against a fake driver:
   ordering, refill, stale events, duplicate/missing done, error
   mid-queue, seek, speed change, pause/resume.
2. ST3 driver seam plus Android impl; behaves as the gate measured.
3. ST4 facade plus service wiring (routing, saves, render pause/resume
   per D-134, sleep/speed); device: screen-off, swipe-away, calls,
   headset unplug.
4. ST5 reader live mode; ST6 calibration; ST7 acceptance.
