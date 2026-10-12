// SPDX-License-Identifier: AGPL-3.0-or-later
//! Native app shell (Slice 16 UI2, egui): window, theme, navigation, and the
//! helper-missing screen. No helper lifecycle yet (Slice 17 wires spawn,
//! health, restart, shutdown); every screen says which slice builds it.

use eframe::egui;
use scribe_ui::theme;

/// Theme token to an egui color (tokens stay plain RGB triples in one place).
fn rgb(color: (u8, u8, u8)) -> egui::Color32 {
    egui::Color32::from_rgb(color.0, color.1, color.2)
}

/// Top-level screens; order matches the Book detail stepper.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Screen {
    Books,
    Jobs,
    Cast,
    Voices,
    Validate,
}

impl Screen {
    fn title(self) -> &'static str {
        match self {
            Self::Books => "Books",
            Self::Jobs => "Jobs",
            Self::Cast => "Cast",
            Self::Voices => "Voices",
            Self::Validate => "Validate",
        }
    }

    /// Slice that builds the real screen (honest placeholder text).
    fn lands_in(self) -> &'static str {
        match self {
            Self::Books => "Slice 17: Books list, add-book, draft",
            Self::Jobs => "Slice 18: jobs tray, render step",
            Self::Cast => "Slice 19: cast table, audition, overrides",
            Self::Voices => "Slice 19: voices view, engine selector",
            Self::Validate => "Slice 20: validate list, transfer, models",
        }
    }

    fn all() -> [Self; 5] {
        [
            Self::Books,
            Self::Jobs,
            Self::Cast,
            Self::Voices,
            Self::Validate,
        ]
    }
}

struct App {
    screen: Screen,
}

impl App {
    fn new() -> Self {
        Self {
            screen: Screen::Books,
        }
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        ui.visuals_mut().extreme_bg_color = rgb(theme::PAPER);
        ui.horizontal(|ui| {
            ui.heading(egui::RichText::new("Auloud Scribe").color(rgb(theme::OXBLOOD)));
            for screen in Screen::all() {
                ui.selectable_value(&mut self.screen, screen, screen.title());
            }
        });
        ui.separator();
        ui.heading(egui::RichText::new(self.screen.title()).color(rgb(theme::OXBLOOD)));
        // Stop-loss consequence (D-157): a missing helper is a normal
        // state with a message, never a crash or a hang.
        ui.group(|ui| {
            ui.label(
                egui::RichText::new(
                    "No helper connected. Slice 17 wires spawn, health \
                     checks, restart, and clean shutdown.",
                )
                .color(rgb(theme::SAGE)),
            );
        });
        ui.label(self.screen.lands_in());
    }
}

fn main() {
    eframe::run_native(
        "Auloud Scribe",
        eframe::NativeOptions::default(),
        Box::new(|_cc| Ok(Box::new(App::new()))),
    )
    .expect("auloud scribe runs");
}
