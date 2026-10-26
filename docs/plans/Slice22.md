# Slice 22 Implementation Plan: Installers and v3.0 release

Follows `docs/10-V3Roadmap.md` Slice 22 (section 4) and the release
criteria (section 7). This is the last v3 slice: it makes the app
installable, carries the licenses, proves the whole path once, and tags
`v3.0.0`. Owner-heavy: the packaging choice, every clean-machine run and
the release verdict are yours. Code work stays small, and the one new
feature here is the render-speed picker.

## 1. Goal

**Done when:**

- A Windows machine with no Python runs the installed or unzipped app
  and completes a build; the Linux artifact is documented and, if a Linux
  machine or VM is available, tested
- `--render-speed slow|balanced|fast` is reachable from the app, not only
  the CLI
- NOTICE, SBOM, in-app licenses and the source offer are complete for
  both licenses in play (AGPL-3.0-or-later Scribe, GPL-3.0 and
  Apache-2.0 Player notices where they ship)
- One soak on a clean machine covers add-book to transferred bundle, a
  mid-render kill and resume, pause and resume, and a range re-render,
  and the resulting bundle validates and matches the Python workflow's
  (text identical, durations within tolerance)
- README and the first-run guide match what ships; the missing slice tags
  are cut; the release criteria list is verified item by item; tagged
  `v3.0.0`

## 2. Inputs and what is known

- The helper ships in one of two layouts from the Slice 15 gate: embedded
  (`helper\embedded\python\python.exe`) or frozen
  (`helper\frozen\scribe-helper\scribe-helper.exe`). Discovery already
  looks for both next to the exe (`scribe-ui/src/helper.rs`,
  `resolve_command`), so an installer only has to place files.
- D-157 took the packaging stop-loss: v3 ships with a documented one-time
  setup rather than a proven nothing-to-install bundle, and nothing
  clean-machine is marked verified without a run. Slice 22 owns that
  one-time-setup UX.
- D-156 named the clean-machine route if you want one run: a VirtualBox
  Windows 11 VM on this PC, no network, 4-6 GB RAM, three shared folders.
- The render-speed tiers exist and are measured (D-178): `slow` = 1
  worker, `balanced` = 4, `fast` = 8, ceiling 12. Only the CLI has the
  flag today; D-178 deliberately made no API or UI change, so surfacing
  it needs a decision plus one contract line.
- `cargo deny check` is green on the trimmed `zip` 9 tree (D-182). Its
  consequence 3 asks Slice 22 to re-run deny rather than assume the
  transitive tree is covered.
- The Rust UI has no licenses view; the roadmap asks for in-app licenses.
- Slice 14's Kitten result is a Player-side item and its plan boxes were
  never ticked; the v3 release criterion that names it has to be
  established from `docs/test-log.md` and the Player repo, not from
  plan checkboxes.

**Unknowns this slice resolves:** which packaging artifact actually ships
(portable zip or installer), whether a clean machine is available for the
soak, and whether the release criteria list has any item that is still
open.

## 3. Scope

| In | Out (later) |
| --- | --- |
| Portable zip and/or Windows installer with the helper bundled | Code signing, store listing, auto-update |
| Linux tarball, AppImage only if a Linux build machine appears | Guaranteed Linux support beyond one mainstream distro |
| NOTICE, SBOM, in-app licenses view, source offer text | Legal review, per-file copyright headers |
| Render-speed picker: helper API field, `jobs.py` flag, UI radios | A second speed knob per role, per-chapter speed, auto-tuning |
| Soak checklist and its recorded results | New render features found during the soak |
| README, first-run guide, stale v3 wording, missing tags | v4 Android work, the v1.1 backlog |

## 4. Key decisions (proposed; log on acceptance)

1. **Portable zip first, installer second.** The zip proves the file
   layout and the one-time setup with no new tool and no SmartScreen
   story; an installer (Inno Setup or similar) is a second artifact with
   an uninstaller, and it is only worth it if you want the Start menu
   entry. The owner picks; both need the same bundled layout.
2. **One contract line for the render speed.** `selection.render_speed`
   (optional, `slow|balanced|fast`, missing means `balanced`) so old
   clients keep working and the default matches the CLI. This amends
   D-178's "no API change" and gets its own DECISIONS entry.
3. **The tier is a request field, not a job field.** The UI shows the
   tier it is about to use; the job echo stays as it is, because the
   chapter fingerprint already excludes the tier (D-178) and jobs are
   read by chapter, not by configuration.
4. **Licenses are generated, not typed.** The NOTICE and SBOM come from
   the locked dependency sets (`Cargo.lock`, the helper's pinned wheels),
   with a script that fails when a dependency is missing from the list,
   so a later dependency cannot silently ship unnoticed.
5. **Nothing is marked verified without a run.** Where no clean machine
   exists, the gap is named in the acceptance sheet exactly as D-157
   names it, and the release still ships with the documented setup.

## 5. Work packages

### RP1: Render-speed picker (S, needs the contract decision)

- Helper: `docs/api.md` selection gains `render_speed`; `scribe/ui/jobs.py`
  passes `--render-speed <tier>` to the render child beside `--device`
  (jobs.py:1000), and a test asserts the argv for each tier plus the
  missing-field default.
- Rust: `scribe_client::Selection` gains the optional field
  (`scribe-client/src/client.rs:30`); `render::Form` gains it with the
  same "unset means app default" shape as the device override; a radio
  row (`App default (balanced)`, `slow`, `balanced`, `fast`) sits next to
  the Device row (`scribe-ui/src/main.rs:1297-1311`).
- Resume must keep the tier a job started with: pause and resume rebuild
  the child argv, so the stored selection has to carry it (test).
- Tests: `render.rs` unit tests for the radio state and the serialized
  selection; a helper test for the argv; the live client test only if the
  helper is up.
- **Verify:** a render started at `fast` runs 8 workers on a 20-CPU
  machine, `slow` runs 1; suite green; clippy and deny clean.

### IN1: Windows artifact and one-time setup (M)

- Bundled layout: `scribe-ui.exe` plus `helper\embedded\...` (the default
  candidate) into one folder, then a zip; an installer only if you choose
  one (start menu entry plus uninstall that keeps the workspace).
- First-run gate: the app's discovery order already finds the bundled
  helper; the runbook's step 3 path is the thing to make true, not to
  rewrite.
- Uninstall or delete must keep the workspace, and say so.
- **Verify:** on a Windows machine with no Python and no repo, unzip,
  start, add a book, draft it, render one chapter, and transfer. Record
  what was installed or already present, and every gap.
- **State (2026-10-18):** `scribe/dev/pack_portable.ps1` plus the staged
  zip exist (`logs\portable\Auloud-Scribe-3.0.0.zip`, 284 MB, 11,250
  entries). Smoked on the dev machine only: helper ready in 0.6 s from the
  staged folder, `/api/health` ok, the app spawned the bundled helper.
  The Verify line above is unrun, because this machine has Python and the
  repo, and the owner chose documented setup only (D-157). Layout and
  numbers: D-184.
- **State (2026-10-19):** both variant zips exist and carry this slice's
  UI and licenses: `-cpu` 286 MB, 11,780 entries; `-gpu` 1,918 MB, 11,951
  entries. Their entries are named with `/` (verified: no entry contains a
  backslash), the `gpu` one carries the four NVIDIA rows, and both carry
  `licenses/` with the six headline files plus `texts/` and `third-party/`.
  The 2026-10-18 pair was packaged with `-SkipUi` and carried the previous
  day's `scribe-ui.exe`, so it held neither the Licenses screen nor the
  SBOM fix; step 5 also used `CreateFromDirectory`, whose Windows
  separators a Unix `unzip` keeps in the file name. Both are fixed, and a
  full run of each variant verifies them.

### IN2: Linux artifact (M, best effort)

- Same layout as a tarball, with the Unix names discovery already looks
  for (`python3`, `bin`, `scribe-helper`); document the prerequisites
  from `docs/ui-runbook.md` section 3 (ffmpeg, ALSA, X11 or Wayland, a GL
  or Vulkan driver, OpenSSL headers only for building).
- AppImage only if a Linux build machine or WSL2 appears; otherwise the
  tarball is the artifact and the gap is stated.
- **Verify:** the runbook's Linux lines are run and marked by whoever ran
  them, or the lines stay unverified and say so.
- **State (2026-10-19):** not built, on the owner's call that the Windows
  build is enough for `v3.0.0` (D-190). The lines in
  `docs/ui-runbook.md` section 3 stay as Slice 21 read them from the
  sources, unrun by anyone, and AC records them that way. A tarball is not
  a rerun on this machine: `scribe/dev/package_helper.py` is Windows-only
  (no platform branch; the CPython standalone asset, the ffmpeg archive
  and the exported requirement set are all the Windows ones), so the
  helper builder would gain a platform path first. WSL2 with Debian exists
  here, so the stop is scope, not hardware.

### LG1: NOTICE, SBOM, licenses, source offer (S)

- Generate from the locked sets: Rust crates (`Cargo.lock`, driven by the
  `deny.toml` allow-list), Python wheels for the helper, and ffmpeg and
  espeak-ng as separate programs; record the exact versions. This extends
  `docs/08-Licenses.md` (which already carries the Player inventory and
  the own-code table) rather than starting a second list.
- SBOM in a standard shape (SPDX or CycloneDX JSON) for both sets.
- In-app licenses: one read-only view or screen showing the NOTICE and
  the third-party list, reachable without a browser.
- Source offer: AGPL-3.0-or-later and GPL-3.0 both need the written
  offer and the license texts beside the binary.
- **Verify:** the generator fails on a dependency missing from the list;
  the NOTICE matches the lock files; every license file is present in the
  artifact.
- **State (2026-10-19):** `scribe/dev/gen_licenses.py` (stdlib only) plus
  19 canonical texts in `scribe/dev/license-texts/`; `pack_portable.ps1`
  runs it as step 4 and asserts the headline files in step 6. Ran against
  both IN1 stages: 311 components for `gpu` (226 crates, 80 distributions,
  3 bundled native libraries, 2 programs) and 307 for `cpu`, 19 and 18
  texts, 511 and 507 per-component license files, CycloneDX 1.5 SBOM,
  `MANIFEST.sha256`; 13 unit tests. Both zips carry `licenses/` (the five
  headline files, `texts/` and `third-party/` listed inside). The in-app
  view is Settings > Licenses in `scribe-ui` (reads the folder beside the
  exe, names the generator in a source tree); its module has tests, but no
  one has looked at the screen on a display. Nothing about the set is a
  clean-machine claim. D-186's open NVIDIA question is answered by D-188
  (the four wheels join the allow-list and carry their own `License.txt`),
  so a `gpu` build no longer stops at step 4, and the SBOM writes a
  non-SPDX atom such as `LicenseRef-NVIDIA-Proprietary` as a `name`
  because `license.id` must be an SPDX id. Both rebuilt zips carry the
  folder (`-cpu` 307 components, `-gpu` 311, the four NVIDIA rows pointing
  at their own `License.txt` under `third-party/`). Nobody has looked at
  the screen on a display. Files and rules: `docs/08-Licenses.md`
  section 8, D-186, D-188.

### SO1: Clean-machine soak (M, owner-run)

- The roadmap's path: a full multi-voice novel from add-book to
  transferred bundle, a mid-render app kill and resume by relaunch, pause
  and resume, and a range re-render.
- Compare the bundle with the Python workflow's: `scribe validate` clean,
  text identical, durations within tolerance (the RT4 jitter floor, not
  byte equality).
- If no clean machine exists, the fallback is the documented approach: a
  dev-machine soak plus a named list of what a clean machine would have
  proven, and no verified claim.
- **Verify:** results in `docs/test-log.md` with the machine named, and
  the acceptance sheet says who ran what.

### DC1: Docs, wording and tags (S)

- README: install and first-run instructions for the shipped artifact(s),
  and what to do without a GPU. `docs/ui-runbook.md` is the first-run
  guide; it gets the artifact paths and a line saying who ran what, and
  the README links to it instead of repeating it.
- `docs/04-Roadmap.md`: remove the stale Tauri-port wording that D-147
  cancelled, and point the v3 section at the shipped design.
- Cut the missing tags (`slice-14`, `slice-15`, `slice-17`, `slice-20`,
  `slice-21`) on the commits that closed them, and tick roadmap boxes only
  for verified items.
- **Verify:** every link and path in the README resolves in the artifact;
  `git tag` list matches the tagged slices.
- **State (2026-10-19):** the README's "Get the app" now covers both
  Windows zips (unzip, run `scribe-ui.exe`, no installer, the `cpu` pick
  without an NVIDIA driver), the `licenses\` note and a link to the
  first-run guide; Path A points at the runbook and keeps the source-tree
  commands; the repo layout lists `scribe-rs/` and `parity/`; the v3
  status line drops "installers". `docs/04-Roadmap.md` describes the
  native Rust UI over the packaged Python helper (D-147, egui per D-159)
  instead of the cancelled Tauri port, and the v4 "Tauri mobile targets"
  parenthetical is gone. The five missing tags are cut on the commits that
  closed their slices: `slice-14` `84d4aef`, `slice-15` `6d8f4c8`,
  `slice-17` `c5d10e8`, `slice-20` `485edc2`, `slice-21` `2d2be06` (the
  last slice-21 commit, after the owner's acceptance). The Slice 21 sheet
  records that acceptance.

### AC: Acceptance and `v3.0.0` (S)

- Acceptance sheet (`docs/acceptance-slice22.md`): the artifact checks,
  the soak result, the licenses checklist, and one row per release
  criterion from `docs/10-V3Roadmap.md` section 7 with its evidence.
- **Verify:** owner acceptance logged; suite, clippy and deny green on
  the tagged commit; tag `v3.0.0`.
- **State (2026-10-26):** `docs/acceptance-slice22.md` holds the agent
  gates (Rust and Python suites, clippy, deny, ruff, both packer runs),
  the artifact checks for both zips, and one row per release criterion
  with its evidence. The owner rows (soak, render tiers, the Licenses
  screen) are filled in: soak good, render good, licenses fair.
  `v3.0.0` is cut at `52e4458`. Criterion 2 still holds only as far as
  D-157 and D-190 allow (no clean-machine run, no Linux artifact), and
  criterion 1's device listening result is still an empty protocol.

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | RP1 | choose a render speed in the app |
| 2 | IN1 | install or unzip the app on Windows |
| 3 | LG1 | read every license and the source offer |
| 4 | SO1 | trust one end-to-end run on a clean machine |
| 5 | IN2 | know the Linux story and its gaps |
| 6 | DC1 | hand the docs to someone else |
| 7 | AC | tag v3.0.0 |

Both decisions are in: the contract line (D-183) and the portable zip
(D-184). Everything else is approval-free.

## 7. Fallback paths (decided at AC)

| Result | Decision |
| --- | --- |
| No clean machine available | Dev-machine soak plus a named set of unproven items; no verified claim (D-157) |
| No Linux artifact in v3.0.0 (D-190) | Owner's call: the Windows build is the release; the Linux lines stay documented and unverified |
| Installer tool judged not worth it | Portable zip is the artifact; the repo says so |
| The render-speed picker needs more than one contract line | Keep the request field, drop the job echo (decision 3) |
| A dependency has no license allow-list entry | Owner decision before it ships, never a silent addition |
| Soak finds a defect | Fix it in this slice if it is small, otherwise a named blocker on the tag |

## 8. Definition of done

> **Closed 2026-10-26 (tag `v3.0.0` at `52e4458`).** The last v3 slice.
> - Render speed selectable in the app: render good. The tier-vs-worker
>   count (8 on `fast`, 1 on `slow`) was not separately recorded.
> - Windows artifact: built, placed, generated and checked on the dev
>   machine. Never started on a machine without Python - D-157, documented
>   setup only, no clean-machine claim.
> - Linux: documented with its unverified lines named; no artifact (D-190).
> - NOTICE, SBOM, in-app licenses and source offer: generated from the
>   lock files, checked for both variants; the Licenses screen was read
>   as "fair".
> - Soak: run, good; recorded as a one-line verdict in
>   `docs/test-log.md`, no per-box numbers.
> - README, first-run guide, stale v3 wording and the missing slice
>   tags: fixed.
> - Release criteria: verified item by item in
>   `docs/acceptance-slice22.md`; two of the seven criteria (clean
>   machine, Linux) are recorded as not met by owner decision, not
>   forgotten. Tagged `v3.0.0`.

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Helper packaging (native wheels, size, antivirus flags) fails on the target | Slice 15 gate evidence plus the embedded layout default; name the gap, do not fake it |
| Unsigned binary triggers SmartScreen or antivirus | Document for personal use; signing stays a later decision |
| The license list rots after a dependency bump | Generated from the lock files, with a failing check |
| Soak expands into new features | Findings are fixed only when small; otherwise named as blockers |
| The picker's default surprises an existing workflow | Missing field means `balanced`, exactly today's behavior |
| Tagging v3.0.0 on an unverified criterion | One acceptance row per criterion; any gap delays the tag |

## 10. Prerequisites for you

- [x] Packaging choice: portable zip (D-184)
- [x] Clean machine for the soak: documented setup only, so no verified clean-machine claim (D-157)
- [x] Linux: no artifact in `v3.0.0` (D-190); WSL2 exists on this machine, but `package_helper.py` is Windows-only
- [x] Approval for the one contract line (`selection.render_speed`), which amends D-178 (D-183)
- [x] Per-render choice only, kept out of Settings (D-183)
- [x] The Slice 21 acceptance rows, if they are not done yet: the `slice-21` tag is a prerequisite for this slice's `v3.0.0`

## 11. Working with the agent

- One work package per session; the agent writes the code, the generator,
  the plan and the tests, and **you** pick packaging, run the clean-machine
  soak, listen, and judge the release
- It must not claim a machine, an OS or an artifact it did not run
- No new dependency without a license check; a new packaging tool is a
  dependency question too
- Commit per work package (`RP1:`/`IN1:`/`IN2:`/`LG1:`/`SO1:`/`DC1:`);
  tag `v3.0.0` after AC
- Bundle format is untouched here; the helper contract changes once, for
  the render speed, with the spec line and the decision entry together

## 12. Next

After `v3.0.0`: the v1.1 backlog and v4 (Android platform work), per
`docs/10-V3Roadmap.md` section 10. Any backend port to Rust stays
piece-by-piece and only on profiling evidence (D-147).
