# Toolkit Score Sheet (Slice 16 UI1 spikes, UI2 judges)

Same two screens in each toolkit (60-row cast table with in-cell voice
dropdowns + audition buttons; jobs tray with SSE-replayed progress), same
canned data (`scribe-ui` harness: deterministic rows + the real recorded
`events-sample.txt` via `scribe-client` types). Bins:
`spike-egui` (117 lines), `spike-iced` (130), `spike-slint` (145),
harness `lib.rs` (149). Resolved versions: egui/eframe 0.36.2, iced 0.14.0
(+`smol` executor), slint 1.18.1 (inline macro, no build script).

Run with `cargo run -p scribe-ui --bin spike-<egui|iced|slint>` (no helper,
no network; all three stay alive and tick).

## Measured rows (agent, dev machine 2026-10-11)

| Criterion | egui | iced | Slint |
| --- | --- | --- | --- |
| Cast table (60 rows, scrolled) | Grid + ScrollArea, runs | scrollable column, runs | for-loop + ScrollView, runs |
| In-cell voice dropdown | ComboBox per row, runs | pick_list per row, runs | ComboBox per row, runs |
| Live progress replay | repaint timer 500 ms, runs | time subscription 500 ms, runs | Timer 500 ms, runs |
| Debug binary | 46.3 MB | 26.5 MB | 28.4 MB |
| Release binary | 13.9 MB | 10.6 MB | 13.9 MB |
| Idle memory, debug | 260 MB | 275 MB | 166 MB |
| Idle memory, release | 282 MB | 266 MB | 152 MB |
| Incremental rebuild (bin only) | 3.1 s | 3.5 s | 3.4 s |
| Full release build (all three) | 4m04s shared (not split per toolkit) | see left | see left |
| License (deny) | MIT OR Apache-2.0, green | MIT, green | Grant UNCONFIRMED (deny holds `LicenseRef-Slint-Royalty-free-2.0` spike-scoped; GPL-3.0-only stays rejected; UI2 confirms or drops Slint) |
| Build findings | none beyond a syntax slip (fixed) | needs an executor feature (`smol`) for any timer; `view`/`theme` must be free fns (higher-ranked `Fn`, closures fail) | layouts + `Text` are builtins (not `std-widgets`); default backend compiled with the existing MSVC setup, no extra toolchain |

Notes: debug memory favors no one (debug runtimes); release idle memory is the
comparable row (Slint lowest). No spike virtualizes the 60 rows (all render);
large-cast performance belongs to Slice 21. Fonts ship inside egui defaults
(`epaint_default_fonts`: OFL-1.1 + Ubuntu-font-1.0, allowed in deny, NOTICE at
Slice 22).

## Judged rows (owner, UI2 session)

| Criterion | egui | iced | Slint |
| --- | --- | --- | --- |
| Looks (would you edit cast daily?) | | | |
| Narrator pass | | | |
| 125% / 150% scaling | | | |
| Text input feel | | | |
| File dialogs | | | |
| Audition playback story | | | |
| Theming depth to the four tokens | | | |
| Slint license grant confirmed | n/a | n/a | REQUIRED before choosing |

Text input, file dialogs, and audition playback are stubbed or absent in all
three spikes (buttons record labels; no audio); judge from docs + feel, or
ask for a second spike round.
