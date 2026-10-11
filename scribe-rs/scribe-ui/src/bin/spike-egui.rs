// SPDX-License-Identifier: AGPL-3.0-or-later
//! egui spike (Slice 16 UI1): cast table with in-cell voice dropdowns plus
//! an audition button per row, and a jobs tray with live progress replayed
//! from the recorded SSE sample. Same canned data as the iced/Slint spikes.

use std::time::Duration;

use eframe::egui;
use scribe_ui::{cast_rows, replay_steps, theme, ReplayStep, TrayJob, PALETTE};

/// Theme token to an egui color (tokens stay plain RGB triples in one place).
fn rgb(color: (u8, u8, u8)) -> egui::Color32 {
    egui::Color32::from_rgb(color.0, color.1, color.2)
}

struct Spike {
    rows: Vec<scribe_ui::CastRow>,
    jobs: Vec<TrayJob>,
    steps: Vec<ReplayStep>,
    tick: usize,
    auditioned: String,
    job_note: String,
}

impl Spike {
    fn new() -> Self {
        Self {
            rows: cast_rows(),
            jobs: TrayJob::tray(),
            steps: replay_steps(),
            tick: 0,
            auditioned: "none yet".to_owned(),
            job_note: "running".to_owned(),
        }
    }

    fn fraction(&self) -> f32 {
        self.steps
            .get(self.tick % self.steps.len().max(1))
            .map(|step| step.fraction)
            .unwrap_or(0.0)
    }
}

impl eframe::App for Spike {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        ui.ctx().request_repaint_after(Duration::from_millis(500));
        self.tick += 1;

        ui.visuals_mut().extreme_bg_color = rgb(theme::PAPER);
        ui.heading(egui::RichText::new("Cast").color(rgb(theme::OXBLOOD)));
        ui.label("cast.yaml changed on disk: review before render (conflict line)");
        egui::ScrollArea::vertical()
            .max_height(320.0)
            .show(ui, |ui| {
                egui::Grid::new("cast")
                    .num_columns(5)
                    .striped(true)
                    .show(ui, |ui| {
                        ui.label("character");
                        ui.label("voice");
                        ui.label("speed");
                        ui.label("lines");
                        ui.label("");
                        ui.end_row();
                        for row in &mut self.rows {
                            ui.label(&row.character);
                            let current = row.voice.clone();
                            egui::ComboBox::from_id_salt(&row.character)
                                .selected_text(current)
                                .show_ui(ui, |ui| {
                                    for voice in PALETTE {
                                        ui.selectable_value(
                                            &mut row.voice,
                                            voice.to_owned(),
                                            voice,
                                        );
                                    }
                                });
                            ui.label(format!("{:.2}", row.speed));
                            ui.label(row.lines.to_string());
                            if ui.button("Audition").clicked() {
                                self.auditioned = format!("{} as {}", row.character, row.voice);
                            }
                            ui.end_row();
                        }
                    });
            });
        ui.label(format!("auditioned: {}", self.auditioned));

        ui.separator();
        ui.heading(egui::RichText::new("Jobs").color(rgb(theme::OXBLOOD)));
        for job in &self.jobs {
            ui.horizontal(|ui| {
                ui.label(&job.label);
                ui.label(&job.state);
            });
        }
        ui.add(
            egui::ProgressBar::new(self.fraction())
                .text(format!("{:.0}%", self.fraction() * 100.0))
                .fill(rgb(theme::SAGE)),
        );
        ui.horizontal(|ui| {
            if ui.button("Pause").clicked() {
                self.job_note = "paused".to_owned();
            }
            if ui.button("Resume").clicked() {
                self.job_note = "running".to_owned();
            }
            if ui.button("Cancel").clicked() {
                self.job_note = "cancelled".to_owned();
            }
            ui.label(&self.job_note);
        });
    }
}

fn main() {
    let options = eframe::NativeOptions::default();
    eframe::run_native(
        "Auloud spike (egui)",
        options,
        Box::new(|_cc| Ok(Box::new(Spike::new()))),
    )
    .expect("egui spike runs");
}
