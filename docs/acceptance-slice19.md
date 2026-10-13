# Acceptance: Slice 19 (owner run)

Build: `cargo build -p scribe-ui` in `scribe-rs/`, then run
`target/debug/scribe-ui.exe`. A real helper is required (dev tree works:
the app discovers `scribe/.venv` on its own). You need a drafted
multi-voice book (for the cast table and an override) plus models in
`scribe/models/` for every render step. Check each box with what you saw,
not what you expect. Slice 18 acceptance stays valid; this covers the
cast table, overrides, the report, and voices.

## A. Cast table (CS1)

- [ ] Open the Cast screen with a drafted book selected: every speaker
  shows with line counts and minutes, a voice dropdown, a speed field,
  and a low-confidence flag where the helper reports one. Narrator and
  generic rows stay pinned at the top; minor characters hide behind a
  toggle.
- [ ] First-person selector: change it and the table updates. If your
  `cast.yaml` has `#` comments, a comment warning shows.
- [ ] Change one voice and one speed from the dropdowns: the change
  sticks after a screen switch. Rebuild the book: you hear the new
  voice on its lines.

## B. Conflict (CS1)

- [ ] With the app open on the Cast screen, edit `cast.yaml` by hand
  (change any voice). Back in the app, change the same row: a conflict
  message names the current file state and offers Reload. Your typed
  edits stay visible (nothing silently lost).
- [ ] Press Reload: the table shows the file contents.

## C. Overrides + report (CS2)

- [ ] Overrides section: the quote picker lists real quotes in reading
  order with low-confidence first, paged. Pick one quote, set its
  speaker, add the override: the row shows an `affected` count (lines
  the next build rebuilds).
- [ ] Start the render from the render panel: only the affected lines
  change (spot-check by ear against the pre-override audio).
- [ ] Remove the override: the row goes away, no error.
- [ ] Report section: the `cast_report` shows read-only with its
  low-confidence list.

## D. Voices + audition (CS3)

- [ ] Open the Voices screen: the engine selector lists Kokoro and
  Piper (Kitten only if installed). Each voice shows name, locale,
  gender, and the sample text.
- [ ] Press Play on a voice: you hear the sample in the app.
- [ ] Pick a voice with no cached sample: a sample job queues (the tray
  shows it) and the row offers Play when the clip lands, with no
  restart. Switch engines and audition one voice on each.

## E. Verdict

- Result (pass / fail with notes):
- Failures pasted below (screen + action + what showed):
