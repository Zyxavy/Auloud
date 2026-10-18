// SPDX-License-Identifier: AGPL-3.0-or-later
//! PF1: the 500-speaker cast-table probe (Slice 21).
//!
//! Not part of the green suite (it measures a machine, not a behaviour):
//! run it by hand with
//! `cargo test -p scribe-ui --test cast_table_probe -- --ignored --nocapture`.
//!
//! It drives a headless `egui::Context` (no window, no GPU) with the cast
//! table's row shape repeated `ROWS` times inside the same `ScrollArea`
//! the screen uses (`max_height(240.0)`), scrolling from top to bottom,
//! and times egui layout plus tessellation per frame. The GPU submit and
//! the window are not measured, so this is the CPU side of the budget.
//!
//! Numbers (dev machine, Windows 11, 2026-10-18), 500 rows, content
//! 10997 px, 240 px window:
//!
//! | Profile | min | median | p95 | max |
//! | --- | --- | --- | --- | --- |
//! | release | 2.44 ms | 2.50 ms | 5.39 ms | 7.67 ms |
//! | debug | 25.70 ms | 28.60 ms | 57.42 ms | 70.94 ms |
//!
//! The app ships release, so the release row is the verdict: 2.5 ms
//! median is far inside a 60 Hz frame (16.7 ms) even at p95, so the
//! table stays as it is. No virtualization, no `egui_extras`, no crate
//! approval needed. See D-181.
//!
//! What limits the number: it is the CPU side only, on this desktop, at
//! a 1200x800 window. A slower laptop or a GPU-bound frame would add to
//! it, and the debug row shows what an unoptimized build costs.

const ROWS: usize = 500;
const WARMUP: usize = 3;
const FRAMES: usize = 60;
const VIEWPORT_H: f32 = 240.0;
const VOICES: [&str; 5] = ["af_sarah", "am_adam", "am_michael", "bf_emma", "af_heart"];

#[test]
#[ignore = "PF1 perf probe: run with --ignored --nocapture"]
fn five_hundred_speaker_rows_stay_interactive() {
    let ctx = egui::Context::default();
    let mut input = egui::RawInput {
        screen_rect: Some(egui::Rect::from_min_size(
            egui::Pos2::ZERO,
            egui::vec2(1200.0, 800.0),
        )),
        ..Default::default()
    };
    let names: Vec<String> = (0..ROWS).map(|n| format!("speaker-{n:03}")).collect();
    let mut speeds: Vec<String> = vec!["1.00".to_owned(); ROWS];

    // Warm up, and learn the content height the scroll offsets need.
    let mut height = 0.0_f32;
    for _ in 0..WARMUP {
        height = frame(&ctx, &mut input, 0.0, &names, &mut speeds);
    }
    println!("PF1: {ROWS} rows, content {height:.0} px, window 240 px");

    let mut ms: Vec<f64> = (0..FRAMES)
        .map(|i| {
            let step = i as f32 / (FRAMES - 1) as f32;
            let offset = (height - VIEWPORT_H).max(0.0) * step;
            let started = std::time::Instant::now();
            frame(&ctx, &mut input, offset, &names, &mut speeds);
            started.elapsed().as_secs_f64() * 1000.0
        })
        .collect();
    ms.sort_by(f64::total_cmp);
    let pct = |p: f64| ms[((ms.len() as f64 - 1.0) * p).round() as usize];
    println!(
        "PF1 CPU frame ms: min {:.2}, median {:.2}, p95 {:.2}, max {:.2}",
        ms[0],
        pct(0.5),
        pct(0.95),
        ms[ms.len() - 1]
    );

    // Loose ceiling on purpose: a machine measurement should only fail
    // when the table turns pathological (for example, work per row that
    // grows with the row count), never because the machine was busy.
    assert!(pct(0.95) < 100.0, "p95 frame was {:.1} ms", pct(0.95));
}

/// One headless frame of the cast table at `offset`: the row shape of
/// `App::cast_ui` (character and kind, voice combo, speed field, counts)
/// inside the table's `ScrollArea`. Returns the content height.
fn frame(
    ctx: &egui::Context,
    input: &mut egui::RawInput,
    offset: f32,
    names: &[String],
    speeds: &mut [String],
) -> f32 {
    let mut height = 0.0_f32;
    let output = ctx.run_ui(input.clone(), |ui| {
        height = egui::ScrollArea::vertical()
            .max_height(VIEWPORT_H)
            .vertical_scroll_offset(offset)
            .show(ui, |ui| {
                for (row, name) in names.iter().enumerate() {
                    ui.horizontal(|ui| {
                        ui.label(format!("{name} [character]"));
                        let mut voice = VOICES[row % VOICES.len()].to_owned();
                        egui::ComboBox::from_id_salt(format!("voice-{name}"))
                            .selected_text(&voice)
                            .show_ui(ui, |ui| {
                                for option in VOICES {
                                    ui.selectable_value(&mut voice, option.to_owned(), option);
                                }
                            });
                        ui.text_edit_singleline(&mut speeds[row]);
                        ui.label("12 lines, 3 min");
                        ui.label("1 low");
                    });
                }
            })
            .content_size
            .y;
    });
    // Tessellation is where shapes become triangles; the backend owns it
    // in the real app, so measure it here too. There is no backend here
    // to upload textures to, so acknowledge the deltas (`TexturesDelta`
    // asserts on drop if unhandled).
    let mut textures = output.textures_delta;
    textures.clear();
    let _ = ctx.tessellate(output.shapes, output.pixels_per_point);
    height
}
