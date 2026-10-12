# Acceptance: Slice 17 (owner run)

Build: `cargo run -p scribe-ui` in `scribe-rs/`. A real helper is required
(dev tree works: the app discovers `scribe/.venv` on its own). Models in
`scribe/models/` for any render step. Check each box with what you saw, not
what you expect.

## A. Fresh start

- [ ] Delete `%APPDATA%\Auloud\scribe-ui.settings` (fresh settings) and any
  old test workspace. Launch: the app opens on Books, empty state names
  Add book as the next action.
- [ ] Settings screen: set a workspace path, device `cpu`, empty helper
  path. Save: notice confirms. Restart the app: values persist.

## B. Add and draft (EPUB + PDF)

- [ ] Add book: pick a real EPUB (native dialog). The row appears with
  `draft: drafting`, then flips to done; the stepper walks extract to
  draft Done with chapter/sentence counts.
- [ ] Same for a real PDF.
- [ ] Add a non-ebook (e.g. `.txt`): shaped refusal naming `.epub or .pdf`.
- [ ] Add the same EPUB twice: second copy lands as `name-2.epub`, both
  rows render, long titles truncate with full title on the detail panel.

## C. Interruptions

- [ ] Restart the app mid-draft: the list reattaches by id, the draft
  continues or resumes, no duplicate rows.
- [ ] Kill the helper process mid-run: crash screen within seconds,
  auto-retry spins, Retry works after relaunching manually if needed.
- [ ] Set a bad helper path in Settings: Missing screen with the reason;
  Settings stays openable from it; fixing the path + Retry connects.

## D. Layout

- [ ] Resize 800x600 to 1920x1080: nav usable, rows readable, no clipped
  buttons; chips stay on their rows.

## E. Verdict

- Result (pass / fail with notes):
- Failures pasted below (screen + action + what showed):
