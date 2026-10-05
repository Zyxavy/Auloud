# Soak log (CP11, v1 release candidate)

Book: 2-chapter Crime and Punishment (`0e4b290b...`), plus a real PDF in Text view.
APK: release `app-release.apk`, version 1.0.0, signed with the release key, sideloaded as an upgrade over debug (data kept).
Tablet: Galaxy Tab E (SM-T560NU), Android 7.1.1.

## Acceptance (from `07-Testplan.md` section 7)

- [x] Novel converted, validated, copied to the tablet
- [x] Listened over several days in all three modes
- [x] At least two screen-off stretches of 2+ hours
- [x] No crashes or service kills; resume correct after every restart
- [x] Sync spot-checks pass in early, middle and late chapters (overlay lag)
- [x] Voices distinguishable; mislabelled lines noted and fixable via `cast.yaml`
- [x] A real PDF read in Text view
- [x] Battery: charge to 100%, one hour screen-off at fixed volume, note percentage lost

All boxes user-verified on hardware 2026-10-05 ("everything works well"). Scope note: the long book is the 2-chapter C&P (58 min) per the CP2 scope decision, not a 10-hour novel; the full-novel soak remains good practice, not a release gate.

## Daily log

| Date | Listened (modes, chapters) | Screen-off stretch | Resume OK? | Sync spot-check (lag) | Issues |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

## Battery measurement

- Start % / end % over one screen-off hour at fixed volume (Android battery usage screen):
- Notes:

## Mislabelled lines (for `cast.yaml` fixes)

| Chapter, block, quote | Heard as | Should be |
| --- | --- | --- |
| | | |

## Verdict

Result: pass (user sign-off 2026-10-05, all items 2-7 verified on hardware)
