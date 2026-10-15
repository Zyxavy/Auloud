# Slice 21 Implementation Plan: Design, Access, Polish

Follows `docs/10-V3Roadmap.md` Slice 21 and the `rust-ui` skill. No
helper contract change, no Player work, no new slice-20 behavior:
this slice makes the existing app readable, navigable, and documented.
Owner-heavy by nature (contrast numbers, screen reader, DPI scaling
need human eyes); code work stays small and measured.

## 1. Goal

Make the app hold up on a real desktop: **a checked light theme plus
a dark inversion, readable titles, every control reachable by
keyboard, a cast table that stays fast on big novels, and a runbook
that gets a fresh Windows or Linux install from zero to first render**
— with the owner judging looks, not the agent.

**Done when:**

- Paper/ink/oxblood/sage pairs all pass WCAG AA (4.5:1 text) by
  computed check; dark theme inverts them and passes the same check
- Titles render in a serif display face (or the plan records why not)
- Tab walks every screen in a sane order with visible focus; nothing
  reachable only by mouse
- A 500-speaker synthetic cast stays interactive (or virtualizes with
  the owner's approval)
- Owner acceptance logged (looks, screen reader, high contrast, DPI
  100-200%); UI runbook written; suite + clippy + deny green; tagged
  `slice-21`

## 2. Inputs and what is known

- Tokens exist (`scribe-ui/src/lib.rs` `theme`: paper `FAF7F0`, ink
  `1A1714`, oxblood, sage); headings already use oxblood, halt
  reasons sage. No dark theme, no custom font, no contrast record.
- Screen reader rides on eframe's accesskit stack (already in the
  tree via eframe 0.36; no new crate for the pass itself).
- Cast table renders every row in a `ScrollArea` (`main.rs` cast
  section): the 60-row spike ran fine; novels can pass 500 speakers.
- egui focus follows widgets by default; nobody has walked the order
  or checked focus visibility on this app's theme.
- OFL-1.1 is allow-listed (deny.toml), but any font file or font
  crate is still a new dependency: owner approval first (standing
  rule). Same for `egui_extras` if virtualization needs it.
- DPI scaling is eframe-native (pixels-per-point); the check is an
  owner run at 100/125/150/200%, not code.

**Unknowns this slice resolves:** whether the token pairs pass AA as
they stand, whether a serif face is worth its weight, and whether the
full-row cast table is actually slow at novel scale.

## 3. Scope

| In | Out (later) |
| --- | --- |
| Contrast math + dark inversion, recorded | Full rebrand, new palette, icon set |
| Serif display face for titles (or recorded no) | Body font change |
| Keyboard walk + visible focus, screen-reader/high-contrast/DPI owner pass | Touch targets, tablet UI (Player owns that) |
| Cast-table perf probe; virtualize only on evidence | Quotes-picker virtualization (same technique if needed) |
| UI runbook Windows + Linux | Installer work (Slice 22), videos |

## 4. Key decisions (proposed; log on acceptance)

1. **Math first, eyes second.** Contrast ratios compute in a unit
   test over the token table (WCAG formula); the owner then judges
   whether passing pairs actually look right. Numbers never overrule
   taste, taste never ships failing numbers.
2. **Dark by inversion, not a second design.** Same pairs with
   paper/ink swapped (plus checked sage/oxblood on dark paper); one
   theme switch, no parallel styling.
3. **Virtualize only on evidence.** The probe renders a 500-speaker
   synthetic cast and measures; `egui_extras` lands only if frames
   drop and the owner approves the crate.
4. **No logic crosses the boundary (standing rule).** This slice adds
   no helper calls at all; the runbook documents existing behavior.

## 5. Work packages

### DS1: Contrast + dark theme (S)

- Pure `theme::contrast(a, b)` (WCAG relative-luminance ratio) plus a
  `DARK` set (paper/ink swapped, sage/oxblood re-checked); unit
  tests assert every text pair ≥ 4.5:1 in both themes.
- Theme switch in Settings (light/dark); headings/notes read the
  active set (one lookup site, not per-screen branches).
- Tests: ratio math against known vectors (black/white = 21:1) plus
  the AA assertions over both tables.
- **Verify:** switch themes live, both readable; suite green.

### DS2: Serif display face (S, needs a font decision)

- Candidate: one OFL serif file or font crate for titles only
  (headings stay short, so a small cut works); license check plus
  `cargo deny` before code, owner picks the face from two renders.
- Fallback (no approval): record the no and keep the default face;
  the slice still closes.
- Tests: none visual; a load-or-fallback unit test (missing file
  falls back, never crashes).
- **Verify:** owner reads both faces at 100% and 150%, picks or declines.

### DS3: Component pass (S)

- Unify the five named components across screens (table, stepper,
  tray, audition player row, chips): one spacing, one type scale,
  one chip shape, both themes. No new widgets, no layout redesign.
- Tests: pure spacing/type constants if any are extracted; the pass
  itself is owner-judged screenshots before/after.
- **Verify:** owner compares the five components screen to screen on
  both themes; suite green.

### AX1: Keyboard walk + focus (S)

- Walk every screen by Tab/Shift-Tab: sane order, every button and
  field reachable, no keyboard traps (dialogs: Esc/choice order).
  Fix order only by reordering UI code, never with focus overrides
  unless a trap proves unavoidable.
- Focus must stay visible on both themes (check the focus ring
  against each background; adjust theme, not widgets).
- Tests: focus order is owner-run (no harness asserts pixels);
  record the walk in the acceptance sheet.
- **Verify:** full walk on light and dark, no mouse; suite green.

### PF1: Cast-table perf probe (S)

- Synthetic 500-speaker cast harness (deterministic rows, same
  dropdowns + audition buttons as the real table); measure frame
  time scrolling top to bottom on the dev machine.
- Verdict in code comments: fast enough as-is, or virtualize with
  `egui_extras` (owner approves the crate first, deny green).
- Tests: harness is throwaway (lives only if virtualization
  lands, as its perf test).
- **Verify:** numbers in the decision log; suite green either way.

### RB1: UI runbook Windows + Linux (S)

- `docs/ui-runbook.md`: fresh install to first render on each OS
  (helper discovery, workspace, models via MM1, draft, render,
  send), plus the failure table (lock held, helper missing, models
  missing, port busy) with the exact screen text to expect.
- The Linux half is written from the owner's machine notes where
  the agent cannot run it; mark each unchecked line honestly.
- **Verify:** owner follows it cold on Windows; Linux lines marked
  by who ran them.

### AC: Acceptance + tag (S)

- Acceptance checklist (`docs/acceptance-slice21.md`): looks on both
  themes, keyboard-only walk, screen-reader pass (NVDA or Narrator),
  high-contrast mode, DPI 100/125/150/200%, 500-speaker table feel.
- **Verify:** owner acceptance logged; suite + clippy + deny green;
  tag `slice-21`.

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | DS1 | read both themes, trust the numbers |
| 2 | DS2 | judge the titles face (or decline it) |
| 3 | DS3 | see one visual language everywhere |
| 4 | AX1 | drive the app without a mouse |
| 5 | PF1 | know the big-cast verdict |
| 6 | RB1 | install cold from the doc |
| 7 | AC | tag |

DS2 needs the font approval before code; PF1 needs the crate
approval only if the probe fails. Everything else is approval-free.

## 7. Fallback paths (decided at AC)

| Result | Decision |
| --- | --- |
| Token pairs fail AA as they stand | Darken/lighten the failing side first; hue changes need owner picks |
| Serif faces all look wrong | Keep the default face; record the no |
| Table probe is slow and crate declined | Cap rendered rows with a "showing first N" note (honest, Slice 19 minor-collapse stays) |
| Screen reader finds dead ends | Fix order/labels as bugs in this slice, not a new slice |
| Linux half unrunnable here | Owner-marked lines; runbook says who ran what |

## 8. Definition of done

- [ ] Contrast unit tests green over both themes; theme switch live
- [ ] Title face decided (ship or recorded no)
- [ ] Five components unified on both themes
- [ ] Keyboard-only walk clean on both themes
- [ ] Table verdict recorded (fast as-is or virtualized)
- [ ] Runbook written, Windows pass done, Linux lines attributed
- [ ] Owner acceptance logged; suite + clippy + deny green; tagged `slice-21`

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Taste debates stall the slice | Numbers gate, owner judges; DS2 fallback is a clean no |
| Font/table crates balloon the build | Approval + deny gates before code; measure first |
| a11y fixes sprawl into redesign | Labels/order/contrast only; looks stay as they are |
| Linux claims without a Linux run | Attribution lines; never claim an unverified OS |

## 10. Prerequisites for you

- [ ] DS2 verdict: serif face approved (name it), or declined
- [ ] PF1 verdict if the probe fails: virtualization crate approved or row-cap accepted
- [ ] Screen reader installed (NVDA or Narrator) plus a high-contrast run
- [ ] DPI ladder 100/125/150/200% on your display
- [ ] Judge every theme, face, and walk for feel

## 11. Working with the agent

- One work package per session; it writes the theme math, the face
  wiring, and the tests; **you** judge looks, walk the keyboard, run
  the reader, and climb the DPI ladder
- It must not claim a look, a feel, or an OS it did not run
- No new dependency without a license check; no contract change (none
  expected in this slice at all)
- Commit per work package (`DSn:`/`AX1:`/`PF1:`/`RB1:`); tag `slice-21`

## 12. Next

Slice 22 builds on this slice: installers plus the v3.0 release
(helper bundled, NOTICE/SBOM/licenses/source offer, clean-machine
soak, tag `v3.0.0`).
