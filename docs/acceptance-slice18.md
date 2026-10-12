# Acceptance: Slice 18 (owner run)

Build: `cargo build -p scribe-ui` in `scribe-rs/`, then run
`target/debug/scribe-ui.exe`. A real helper is required (dev tree works:
the app discovers `scribe/.venv` on its own). Models in `scribe/models/`
for every render step. Check each box with what you saw, not what you
expect. Slice 17 acceptance stays valid; this covers the tray, the render
panel, and quitting.

## A. Tray + job controls (JP1)

- [ ] Start any render (CLI works: `uv run scribe build <book> --work-dir
  <ws>/.scribe --out-dir <ws>/bundles --models-dir models`). The bottom
  tray appears on every screen with the live row: state, sentence counts
  with cache hits, RTF, wall time, and a scan-percent bar labeled `scan`
  (spinner while the scan has no percent).
- [ ] Pause from the tray: the row flips to paused; Resume restarts it
  (counts keep climbing, cache hits grow). Cancel stops it with
  `job cancelled`.
- [ ] History toggle: settled jobs hide behind `History (n)`; the Jobs
  screen shows live rows plus full history with the same controls.
- [ ] No percent or ETA on tray rows yet (by decision D-161): counts + RTF
  only. Note any row where the `scan` bar disagrees with the counts.

## B. Render panel (JP2)

- [ ] Open a drafted book: the Render section lists chapter checkboxes,
  a range field, device radios defaulting to the app setting, and the
  keep-awake checkbox (not `loading…`).
- [ ] Tick chapters 1-2, Check plan: the preflight block shows the
  helper's counts + estimate + range notice, and Start render is gated
  on it (untouched Start says `check the plan first`).
- [ ] Start the partial render: the tray picks it up; it runs to done;
  the book row gains bundles. Editing the picker clears the preflight.
- [ ] PDF book: the Pages field shows; a page-range plan previews its
  `pages:` resolution line; the render completes.
- [ ] Estimate honesty: note estimate vs wall time for one render.
- [ ] Errors: bad range text and Start-while-busy each land in the render
  note as `file: rule: detail` (no traceback, no empty note).

## C. Quit warning (JP3)

- [ ] Close the window mid-render: a `Quit with running jobs?` panel
  lists what runs. Stay cancels the close (app usable); Quit quits.
- [ ] After Quit mid-render: relaunch, the tray reattaches to the
  running job (or its settled history) with no duplicate rows.
- [ ] Close with no running jobs: no panel, quits at once.

## D. Verdict

- Result (pass / fail with notes):
- Failures pasted below (screen + action + what showed):
