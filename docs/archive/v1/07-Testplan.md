# Test Plan

Goal: prove the v1 done criteria from `01-PRD.md` and `04-Roadmap.md`, on the actual Samsung Galaxy Tab E (Android 7.1.1).

## 1. Test levels

| Level | Scope | Tools |
| --- | --- | --- |
| Unit | Scribe text logic, bundle parsing, sentence index | pytest, JUnit |
| Integration | Scribe end to end (EPUB to valid bundle); Player import and playback of a bundle | pytest, instrumented tests |
| Device | Real Tab E behavior: screen-off, battery, storage, interruptions | manual checklist |
| Soak | Whole novel over several days | manual log |

## 2. Test assets

- **Tiny bundle:** 2 chapters, about 5 minutes, hand-made (Slice 1).
- **Beep bundle:** a chapter with a short beep at known times (for example every 30 seconds) and a sentence timed to each beep, to measure sync.
- **Short EPUB:** a public-domain book with lots of dialogue (for Scribe golden tests).
- **Long EPUB:** a public-domain novel of 10+ hours (for the soak test).
- **Bad bundles:** missing MP3, invalid JSON, wrong duration, wrong speaker key, non-UTF-8 text.
- **Test PDFs:** one clean, one with headers and page numbers, one scanned (expected to fail gracefully).

## 3. Scribe tests

| Area | Cases |
| --- | --- |
| Extraction | Chapter order, headings, italics, dropped boilerplate, image-only pages |
| Sentence split | Abbreviations, ellipses, dialogue with commas |
| Dialogue | Straight and curly quotes, multi-paragraph quote, unbalanced quote, nested single quotes |
| Speakers | Explicit tag, pronoun tag, alternation, fallback, per-line override |
| Cast | Aliases resolve to one character; top N limit; unknown speaker defaults |
| Audio | Timing rules from the spec (ordered, non-overlapping, sids consecutive); MP3 is mono CBR 64 kbps; duration within 50 ms of the buffer |
| Cache/resume | Editing `cast.yaml` re-renders only changed lines; crash and rerun continues |
| Validate | Every bad bundle above is rejected with a clear message |

## 4. Player tests

| Area | Cases |
| --- | --- |
| Import | Valid bundle imports; bad chapter flagged; duplicate import updates, not duplicates |
| Playback | Play, pause, seek, next/previous chapter, auto-advance, end of book |
| Position | Resume after app restart, after reboot, after chapter change |
| Sentence index | Binary search returns the right `sid` at boundaries and in gaps |
| Highlight | Follows audio; tap-to-seek; manual scroll detaches; "back to now" re-attaches |
| Modes | Read only, listen only, read + listen switch without losing position |
| Speed/timer | 0.75x to 2.0x; sleep timer pauses on time and at end of chapter |
| PDF | Page changes at the right `start_ms`; page turn seeks audio |

## 5. Sync accuracy test (beep bundle)

1. Play the beep bundle in read + listen mode with the debug overlay on.
2. At each beep, note the highlighted `sid` and the overlay's position.
3. Repeat at the start, middle and end of a long chapter, at 1.0x and 1.5x speed.

**Pass:** the highlight changes within 300 ms of the beep, and does not drift over the chapter (no growing offset).

## 6. Tab E device checklist

**Screen-off and background**

- [ ] Playback continues 30 minutes with the screen off
- [ ] Playback continues 2+ hours with the screen off
- [ ] Continues after swiping the app from recents (record the result; if it stops, note the Samsung setting needed)
- [ ] Notification and lock screen controls work
- [ ] Bluetooth headset play/pause and next/previous work
- [ ] Battery optimization prompt appears once and leads to the right settings screen

**Interruptions**

- [ ] Incoming call pauses, playback resumes after
- [ ] Other app's audio pauses ours and we resume
- [ ] Headphone unplug pauses
- [ ] Bluetooth disconnect pauses
- [ ] Low battery warning does not stop playback
- [ ] microSD ejected shows a message; reinserted resumes

**Resources**

- [ ] Memory stable during 1 hour of read + listen (no growth trend; no out-of-memory crash)
- [ ] UI stays smooth while scrolling a 5,000-sentence chapter
- [ ] Storage: a 15-hour bundle fits on the microSD card with room to spare

**Battery test:** charge to 100%, screen off, fixed volume, play 1 hour, record battery loss. Repeat with the screen on in read + listen mode. Record both in the log; the target is a screen-off drain comparable to a music player.

## 7. Soak test (v1 acceptance)

Log daily in `docs/soak-log.md`: date, mode, hours listened, issues. Run it on the signed release APK (not a debug build).

- [ ] 10+ hour multi-voice novel converted, validated, copied to the tablet
- [ ] Listened over several days in all three modes
- [ ] At least two screen-off stretches of 2+ hours
- [ ] No crashes or service kills; resume correct after every restart
- [ ] Sync spot-checks pass in early, middle and late chapters (overlay lag)
- [ ] Voices distinguishable; mislabelled lines noted and fixable via `cast.yaml`
- [ ] A real PDF read in Text view (and Page view if ever built; Page view is v1.1 per D-046)
- [ ] Battery: charge to 100%, one hour screen-off at fixed volume, note the percentage lost (use Android's battery usage screen since adb isn't available)

## 8. Performance thresholds

| Metric | Target |
| --- | --- |
| Chapter open to first highlight | Under 1 second |
| Highlight lag | Within 300 ms |
| Seek to sentence | Under 500 ms to audible audio |
| App cold start to library | Under 3 seconds |
| Scribe real-time factor | Record it; no hard target, but know how long a novel takes |

## 9. Bug log template

```
ID / Date / Build
Where: Scribe | Player, slice
Steps to reproduce:
Expected:
Actual:
Device and Android version:
Severity: blocker | major | minor
Status:
```

## 10. Release checklist (v1)

- [ ] All slice "done when" lists checked
- [ ] Soak test (section 7) passed and logged
- [ ] `scribe validate` passes on every test bundle (incl. pdf-golden v1.1)
- [ ] No known blocker or major bugs
- [ ] README with setup steps for Scribe and Player (CP10; fresh-follow verified by the user)
- [ ] Licenses listed (app screen + `player/NOTICE` + `player/THIRD_PARTY_LICENSES.md` + per-voice table in `08-Liscenses.md` section 4)
- [ ] Release APK `1.0.0` built minified, signed with the kept-outside-repo key, no `INTERNET` in the merged manifest, installed on the Tab E with upgrade-install working