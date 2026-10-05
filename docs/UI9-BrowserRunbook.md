# UI9 Browser Runbook (manual, not yet run)

Three-browser visual pass for the Scribe Web UI design system. The agent
cannot open browsers; work through this checklist yourself in Edge, Chrome
and Firefox and record pass/fail per browser.

## Setup

1. `uv run scribe ui --workspace <test-workspace>` in `scribe/`.
2. Use a workspace with one drafted book (a few chapters) and one valid
   bundle, so every stepper state shows.
3. Keep this file open; tick each row per browser.

## Checklist (repeat in Edge, Chrome, Firefox)

| # | Check | Edge | Chrome | Firefox |
|---|-------|------|--------|---------|
| 1 | Layout: single 960 px column, sections divided by hairlines (no boxes-in-boxes), tables with hairline row dividers, chips rectangular 4 px | | | |
| 2 | Stepper: book detail shows Draft, Cast, Render, Validate, On tablet with done (sage edge) vs active (oxblood edge) states | | | |
| 3 | Tray: start a render, watch percent + `x.x RTF` + ETA + `% cached`; Pause, Resume, Cancel all work; completion toast appears | | | |
| 4 | Conflict banner: hand-edit `cast.yaml` mid-session, then Save cast in the UI; expect the conflict banner (the only shadowed element) with "Load the server version" | | | |
| 5 | Validate: press Validate now; errors read `file: rule: message`; valid bundles list with chapter counts | | | |
| 6 | Dark mode: Windows Settings, Personalization, Colors, Choose your mode, Dark; reload the page; paper/ink flip, buttons stay readable, chips and focus rings visible; switch back to Light | | | |
| 7 | Keyboard only: Tab from the skip link ("Skip to main content") through upload, book links, chapter checkboxes, device select, cast voice dropdowns, Play buttons, transfer path; every control reachable and operable with Enter/Space; focus ring always visible | | | |
| 8 | Zoom 200%: browser zoom to 200%; no horizontal scroll in tables/stepper; tray and player row still usable | | | |
| 9 | Copy: durations read `58:32` shape, speeds read `24.8x RTF`, buttons verb-first (Start render, Regenerate all, Save cast, Copy to tablet folder); empty states name the next action | | | |

## Notes

- Player-row: press Play on a voice; one audio element appears and plays.
- Report failures as browser + step + screenshot text; file UI bugs, do not fix mid-pass.
