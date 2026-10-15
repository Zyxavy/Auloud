# Acceptance: Slice 20 (owner run)

Build: `cargo build -p scribe-ui` in `scribe-rs/`, then run
`target/debug/scribe-ui.exe`. A real helper is required (dev tree works:
the app discovers `scribe/.venv` on its own). Models in `scribe/models/`
for render steps; a drafted book with at least one bundle for validate,
send, and wizard-adjacent checks. Tablet on the same Wi-Fi for section
B. Check each box with what you saw, not what you expect. Slice 19
acceptance stays valid; this covers validate, Wi-Fi transfer, models,
and the wizard.

## A. Validate list (VT1)

- [ ] Open a book with a valid bundle: the Validate section ticks the
  bundle plus `book validates`.
- [ ] Open a book with an invalid bundle (or break one: delete an MP3
  from a bundle copy and re-add it as a separate test book): the
  section names every finding as `file: rule: detail`.
- [ ] Press Re-validate after a new render: old ticks clear first
  (no stale verdict beside the new bundle count).
- [ ] Book with no bundles: the section says to render first (not an
  empty box, not a spinner).

## B. Wi-Fi transfer (TR1, needs the tablet)

- [ ] Tablet: open Receive via Wi-Fi from the library. The screen shows
  an `IP:port` address plus a six-digit code and holds the screen on.
- [ ] PC: in the book detail Send section, type the IP and code, Send:
  the tablet shows Receiving, then Imported; the book appears in the
  tablet library.
- [ ] Wrong code: the send fails naming the code; nothing lands.
- [ ] Corrupt zip (send a renamed `.txt` as the bundle): rejected
  before unpacking, temp cleaned.
- [ ] Bad bundle (valid zip, broken manifest): skipped with a
  file-and-rule message after unpacking.
- [ ] Re-send the same bundle: reports Already on tablet, no duplicate.
- [ ] Re-send an updated bundle (re-render one chapter): replaces and
  imports (not a silent keep of the old audio).

## C. Model manager (MM1)

- [ ] Models screen: each file reads missing, ready (pinned), or
  present-not-pinned with its size.
- [ ] Delete one file, Download it: byte progress climbs, checksum
  passes, the row flips to pinned; render with it afterwards.
- [ ] Sideload a file from disk: same pinned finish.
- [ ] Bad file (any wrong bytes under a model name): checksum message,
  the bad file is gone, the old install untouched.

## D. First-run wizard (WZ1)

- [ ] Fresh workspace (point Settings at an empty folder, restart):
  the wizard opens on welcome (Books stays reachable behind Skip).
- [ ] Walk welcome, models (download or sideload one file), device:
  Finish saves and lands on Books.
- [ ] Skip on any step: straight to Books, no error.
- [ ] Settings holds a First-run wizard button that re-opens it.

## E. Verdict

- Result (pass / fail with notes):
- Failures pasted below (screen + action + what showed):
