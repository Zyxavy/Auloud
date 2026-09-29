# WP10 Runbook — Slice 1 device testing on the Tab E

Target: Samsung Galaxy Tab E (SM-T560NU), Android 7.1.1. No USB/adb in this setup, so everything goes via microSD sneakernet and the debug overlay is the instrument (no logcat).

## 0. What you need

- microSD card + laptop card reader
- The debug APK: `player/app/build/outputs/apk/debug/app-debug.apk` (rebuild below)
- A test bundle (Part B)
- Time blocks: 30 min + 2 h screen-off runs

## A. APK to tablet

1. Build (from `player/`):
   ```
   .\gradlew.bat --no-daemon :app:assembleDebug --console=plain
   ```
2. Copy `app/build/outputs/apk/debug/app-debug.apk` to the microSD card (any folder). Compare file sizes after copy.
3. Insert the card in the Tab E. Open My Files → tap the APK → allow **Unknown sources** (Settings → Security) when asked → Install.
4. Launch **Auloud**. Expected: library screen, "No books yet" empty state, Settings action in the header. This is a **debug** build (debug overlay active, no minification).

To update later: rebuild, copy over, tap to install (data/progress is kept).

## B. Test bundle (hand-made, per spec section 9)

1. Record two short chapters (phone voice recorder is fine, ~2–5 min each). Transfer the recordings to the laptop.
2. Transcode each to spec audio (mono, 24 kHz, 64 kbps **CBR**):
   ```
   ffmpeg -i ch1.wav -ac 1 -ar 24000 -b:a 64k -codec:a libmp3lame ch001.mp3
   ```
   Verify CBR/mono: `ffprobe -v error -show_entries stream=codec_name,channels,sample_rate,bit_rate -of default=noprint_wrappers=1 ch001.mp3`
3. Read exact durations (milliseconds):
   ```
   ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 ch001.mp3
   ```
4. Build this folder (use the durations from step 3 — manifest, chapter JSON, and MP3 must agree within 50 ms):
   ```
   TestBook/
     manifest.json
     audio/ch001.mp3
     audio/ch002.mp3
   ```
   `text/*.json` and `cover.jpg`/`source/` are **optional for Slice 1** (the Player doesn't read text yet) — add rough ones later for Slice 3. Minimal `manifest.json`:
   ```json
   {
     "spec_version": "1.0",
     "id": "<new-uuid>",
     "title": "Test Book",
     "author": "Test Author",
     "language": "en",
     "type": "epub",
     "audio": { "format": "mp3", "channels": 1, "sample_rate": 24000, "bitrate_kbps": 64, "cbr": true },
     "voices": { "narrator": { "engine": "kokoro", "voice": "af_sarah", "speed": 1.0, "pitch": 1.0 } },
     "chapters": [
       { "index": 1, "title": "Chapter One", "audio": "audio/ch001.mp3", "text": "text/ch001.json", "duration_ms": <from step 3> },
       { "index": 2, "title": "Chapter Two", "audio": "audio/ch002.mp3", "text": "text/ch002.json", "duration_ms": <from step 3> }
     ],
     "created_at": "<today>T00:00:00Z",
     "generator": "hand-made"
   }
   ```
5. Also make a **corrupt** copy (for check C6): duplicate the folder, delete `audio/ch002.mp3` in the copy (or truncate its `manifest.json`).
6. Copy `TestBook/` (and the corrupt copy) to the `Auloud/` folder on the microSD card. Insert card → open Auloud → **Rescan**.

## C. Checks

Read the debug overlay (player screen, debug builds only): chapter, positionMs, state, last save time. Compare against what you hear.

| # | Test | Steps | Expected |
|---|------|-------|----------|
| C1 | Import | Rescan with card inserted | Test Book appears with correct title/author; corrupt copy appears as skipped **with reason**, no crash |
| C2 | Basics | Play, pause, seek bar, next/prev chapter | All respond; chapter titles update; position advances |
| C3 | Battery prompt | First Play tap | Dialog appears once explaining why; button opens a real Samsung settings screen — **write down the exact menu path**; never appears again (reopenable via Settings entry) |
| C4 | Screen-off 30 min | Note chapter+position from overlay → power button off → wait 30 min → wake | Audio continued; position ≈ start + 30 min; overlay matches audio; no restart signs |
| C5 | Screen-off 2 h | Same as C4, 2 hours | Same as C4 |
| C6 | Bad chapter | Play the corrupt bundle | Skips the bad chapter with a message, no crash |
| C7 | Recents swipe | Playing, screen on, swipe app away in recents | Record either way: continues or stops |
| C8 | Lock-screen controls | Lock while playing | Lock-screen play/pause works; notification controls work |
| C9 | Interruptions | Call / other app's audio / headphone unplug / Bluetooth disconnect (whatever applies) | Ours pauses (headphone/BT/call); note resume behavior for each |
| C10 | Resume | Note position → close app → reopen; then reboot tablet → reopen | Same spot both times |
| C11 | Overlay truth | During playback compare overlay chapter/position/state to what you hear | Matches; save time updates about every 5 s while playing |
| C12 | Battery drain | 100% charge, screen off, fixed volume, play 1 h | Record % lost (target: comparable to a music player) |

Slice 1 "done when": C4 + C10 + C8 pass (30+ min screen-off end to end, resume correct, lock-screen controls work).

## D. Recording results

Append one block per check to `docs/test-log.md`:

```
Date / build (commit) / device state:
Test: C#
Steps:
Expected:
Actual (include overlay readings):
Result: pass | fail | needs retest
```

Only your confirmed runs count as device-verified. If the USB cable ever works again, `adb logcat -s AuloudPlayback:V AuloudProgress:V` replaces the overlay as the instrument.
