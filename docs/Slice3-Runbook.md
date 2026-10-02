# Slice 3 device runbook (RA11)

Checks C13 onward for the Tab E (SM-T560NU, Android 7.1.1). No adb: install
the debug APK from microSD, copy bundles to `/Auloud/` (or a watch folder),
Rescan in the library. Results go in `docs/test-log.md` (one entry per
check with overlay readings).

Setup (once):

- Build: latest debug APK from `dev/slice3` (`.\gradlew.bat :app:assembleDebug`
  in `player/`, APK at `app/build/outputs/apk/debug/app-debug.apk`).
- Bundles on the tablet: `logs/ra-beep` (40 beeps, 17.8 s), `logs/ra-long`
  (5,000 tones, 56:47), the C&P sample, Yellow Wallpaper.
- The reader sync overlay (`RDG sid … lag avg … max … PSS …MB`) sits below
  the text in Read / Read + listen (debug builds only). `lag avg/max` is the
  highlight lag over the last 20 sentence changes in ms.

## C13 sync on the beep bundle

1. Open `ra-beep`, mode Read + listen, play from the start.
2. Watch the highlight and listen: it must flip at each beep.
3. Seek to the middle and the end, repeat. Then set speed 1.5x, repeat all three spots.
4. Pass: flip lands on the beep at start, middle and end at 1.0x and 1.5x;
   overlay `lag avg` and `lag max` stay under about 300 ms everywhere.

## C14 long chapter scroll and highlight

1. Open `ra-long`, mode Read + listen, let it run.
2. Watch several minutes: scrolling glides, highlight advances steadily.
3. Toggle Following off (scroll away), fling hard up and down.
4. Pass: no stutter, no stuck highlight, no crash; flings feel smooth.

## C15 tap to jump

1. In `ra-beep` (Read + listen, playing), tap a mid-paragraph sentence,
   including one italic sentence.
2. Pass: highlight jumps there and audio is audible within about 500 ms;
   taps land on the tapped sentence, not a neighbor.

## C16 detach and back to now

1. In `ra-long` while playing, drag to scroll far away from the highlight.
2. Confirm auto-follow stops and the "Back to now" button appears.
3. Tap the button.
4. Pass: button appears only when detached and the current sentence is off
   screen; tapping glides back and re-follows with no fighting or jitter.

## C17 mode switching keeps your place

1. In the C&P sample, switch Read / Listen / Read + listen at the start,
   middle and end of a chapter and across a chapter change.
2. Pass: the position is never lost; leaving Read resumes audio at the same
   sentence; entering Read pauses audio with the text in place.

## C18 Read-mode position

1. In Read mode (audio paused), scroll to a clearly identifiable spot and
   wait about a second.
2. Switch to Listen.
3. Pass: audio starts at (or within one sentence of) the spot scrolled to.

## C19 speed and sleep timer

1. In Read + listen on a real book, try 0.75x, 1.5x and 2.0x: highlight stays
   with the audio at each speed.
2. Set each timer option in turn (15, 30, 45, 60 min, end of chapter):
   playback pauses at the end, position saved, full volume restored after.
   Include one screen-off run (timer set, screen off, playback pauses alone).
3. Pass: sync holds at all speeds; every option pauses, including
   end-of-chapter (use `ra-beep` for a fast end-of-chapter check) and
   screen-off.

## C20 memory over an hour

1. In Read + listen on Yellow Wallpaper, note overlay PSS at 0, 30 and
   60 minutes.
2. Pass: no growth trend, no slowdown, no crash.

## C21 real-book hour

1. Read + listen the C&P sample and Yellow Wallpaper for about an hour
   combined, with taps, seeks, chapter changes and mode switches mixed in.
2. Pass: no crashes, highlight sane throughout, headings/quotes/breaks and
   italics look right.

## Slice 3 done when (from the roadmap)

- [ ] C13: highlight within about 300 ms of the audio at start, middle and
  end of a long chapter
- [ ] C17 (+C18): switching modes never loses your place
- [ ] C20: memory stable during a 1-hour session
