# Slice 22 acceptance: installers, licenses, soak, v3.0.0

From `docs/plans/Slice22.md` and the v3.0 release criteria
(`docs/10-V3Roadmap.md` section 7). The agent fills what a file, a command
or an artifact can prove; the owner fills what needs a machine, a render or
a listen. The tag `v3.0.0` lands only after the owner's rows pass.

## Agent gates (dev machine, Windows 11, 2026-10-19)

| Gate | Command | Result |
| --- | --- | --- |
| Rust suite | `cargo test --workspace` (in `scribe-rs/`) | green, no failures in any target |
| Clippy | `cargo clippy --workspace --all-targets -- -D warnings` | clean |
| Deny | `cargo deny check` | advisories, bans, licenses, sources all ok |
| Python suite | `uv run pytest` (in `scribe/`) | 893 passed |
| Ruff | `uv run ruff check .` (in `scribe/`) | clean |
| Packer, `cpu` | `scribe/dev/pack_portable.ps1 -Variant cpu` | exit 0; 286 MB, 11,780 entries |
| Packer, `gpu` | `scribe/dev/pack_portable.ps1 -Variant gpu` | exit 0; 1,918 MB, 11,951 entries |

## Artifact checks (agent, 2026-10-19, by listing the archives)

| Check | `-cpu` | `-gpu` |
| --- | --- | --- |
| Entry separators | no entry name contains a backslash | same |
| App is the current build | `scribe-ui.exe` 17,179,136 B, 2026-10-19 11:54 | same |
| License files | NOTICE, THIRD_PARTY_LICENSES, SOURCE_OFFER, sbom.cdx.json, MANIFEST.sha256, texts/ all present | same, plus `texts/LicenseRef-NVIDIA-Proprietary.txt` |
| SBOM components | 307 (306 SPDX ids, 1 name) | 311 (306 ids, 5 names: the four NVIDIA wheels and the MSVC runtime) |
| Helper | 2026-10-18 stage smoke: ready line, `/api/health` ok, app spawned the bundled helper | not re-smoked; the helper build itself succeeded |

Not claimed anywhere: a clean machine, a device, or a build other than this
one. The owner chose the documented clean-machine path (D-157).

## Release criteria (`docs/10-V3Roadmap.md` section 7)

| # | Criterion | Evidence | State |
| --- | --- | --- | --- |
| 1 | Kitten engine result recorded (Slice 14), licenses verified | `docs/archive/v2/kitten.md` (desk research, model licenses Apache-2.0 and checked), `docs/plans/Slice14.md`, and the v3 roadmap's Slice 14 line ("remaining items are device-only"). `docs/archive/v2/kitten-ab.md` is still an empty listening protocol | partial: the desk side is recorded; the device listening result is open, owner |
| 2 | A clean Windows 11 machine with no Python runs the app and completes a build; Linux build documented and tested | D-157 (documented setup only, no clean-machine run), D-190 (no Linux build; the runbook's Linux lines are unrun) | not met as written; the owner's call, recorded |
| 3 | Add book, draft, cast edit with audition, render with ranges, validate and transfer all work in the native UI | Slice 17 to 20 owner acceptances (commits `c5d10e8`, `3e17bf2`, `0f11ce2`, `485edc2`), plus the 2026-10-18 dev-machine smoke | owner walk on the packaged zip |
| 4 | Jobs survive closing the window; relaunch reattaches | Slice 18 acceptance (`73871fc`, `3e17bf2`); job-system unit tests | owner check |
| 5 | Models install through the manager (download or sideload) | Slice 20 model manager; `scribe-ui/src/models.rs` and its tests | owner check, needs network |
| 6 | Keyboard and screen-reader pass done; scaling checked | the owner rows in `docs/acceptance-slice21.md` (accepted 2026-10-19; no per-item result written down) | owner |
| 7 | Licenses, NOTICE, SBOM and source offer complete; decisions logged; tagged `v3.0.0` | LG1 (D-186, D-188), `docs/08-Licenses.md` section 8, D-183 to D-190 | agent side done and checked above; the tag waits on the rows in this table |

## Slice 22 work packages

| WP | State |
| --- | --- |
| RP1 | committed (`be6dd28`); the tier check on a live render is unrun, owner |
| IN1 | both zips rebuilt 2026-10-19 and checked; the machine-without-Python run is unrun (D-157) |
| IN2 | documented only, no artifact (D-190) |
| LG1 | generated and checked for both variants; `Settings` > `Licenses` has not been seen on a display |
| SO1 | owner, pending |
| DC1 | committed (`8ab24af`); the five missing slice tags are cut (`slice-14`, `slice-15`, `slice-17`, `slice-20`, `slice-21`) |
| AC | this sheet |

## Owner acceptance (pending)

| Item | How to judge | State | Notes |
| --- | --- | --- | --- |
| Soak (SO1) | a full multi-voice novel from add-book to a transferred bundle: a mid-render app kill and resume, pause and resume, and a range re-render, then compare against the Python bundle with `scribe validate` (durations within the RT4 jitter floor, not byte equality) | owner, 2026-10-26 | soak run, verdict good; no per-box numbers recorded |
| Render speed tiers | a render started at `fast` runs 8 workers on this 20-CPU machine, `slow` runs 1 | owner, 2026-10-26 | render good; tier-vs-worker count not separately recorded |
| Licenses screen | open `Settings` > `Licenses` and read the three files | owner, 2026-10-26 | fair |
| Clean machine | D-157: documented setup only | n/a | no verified clean-machine claim is made |
| Tag | `git tag v3.0.0` | pending | after the rows above |
