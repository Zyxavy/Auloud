# Slice 21 acceptance: design, access, polish

From `docs/plans/Slice21.md` section 5, AC. The owner fills the second
table at acceptance; the tag `slice-21` lands only after those rows pass.

## Agent gates (dev machine, Windows 11, 2026-10-18)

| Gate | Command | Result |
| --- | --- | --- |
| Suite | `cargo test --workspace` | green (no failures in any target) |
| Clippy | `cargo clippy --workspace --all-targets -- -D warnings` | clean |
| Deny | `cargo deny check` | green (advisories, bans, licenses, sources) after the owner-approved `zip` bump to 9.0.5 with its codec features off. The first run failed advisories on `zip v2.4.2` (GHSA-4684-h2q9-2vcp), which is what triggered the bump. See D-182. |
| PF1 probe | `cargo test -p scribe-ui --test cast_table_probe -- --ignored --nocapture` | numbers and verdict in D-181 |

## Owner acceptance (pending)

| Item | How to judge | State | Notes |
| --- | --- | --- | --- |
| Looks, light theme | open every screen | pending | |
| Looks, dark theme | out of this slice: dark deferred by owner decision, 2026-10-15 | n/a | |
| DS2 title face | headings at 100% and 150% | pending | Source Serif 4 shipped (9e736e0); confirm the verdict |
| DS3 components | compare table, stepper, tray, audition row and chips screen to screen | pending | one spacing, one type scale, one chip shape (16c74ad). Review fix: a failed or cancelled draft now reads a warn chip in the books list too, matching the stepper's `FAILED` (review fix) |
| Keyboard-only walk | Tab and Shift-Tab through every screen, no mouse, dialogs included | pending | agent part: visible focus ring and Esc on the quit dialog (1985316). Order fixes need evidence from this walk |
| Screen reader | NVDA or Narrator through Add book, Cast and Render | pending | rides eframe's accesskit stack, no new crate |
| High contrast | Windows high contrast mode on, every screen | pending | |
| DPI | 100, 125, 150 and 200% | pending | |
| 500-speaker table | a big cast open, scroll top to bottom | pending | probe numbers in D-181 |
| Runbook cold run | follow `docs/ui-runbook.md` on Windows | pending | Linux lines are attributed in that file |
| Tag | `git tag slice-21` | pending | after the rows above |

## Walk findings (owner writes here)

Free-form notes from the keyboard walk, the screen reader pass and the
looks comparison. Any dead end gets fixed in this slice, not a new one.
