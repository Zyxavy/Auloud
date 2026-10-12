// SPDX-License-Identifier: AGPL-3.0-or-later
//! Native app shell (Slice 17 AP1, egui): window, theme, navigation, helper
//! lifecycle, and settings. Screens other than lifecycle states and Settings
//! show which slice builds them (AP2/AP3 own Books and the stepper).

use eframe::egui;
use scribe_ui::helper::{self, WorkerCmd, WorkerEvent};
use scribe_ui::machine::{self, Action, Event, State};
use scribe_ui::settings::{self, Settings};
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
    Settings,
}

impl Screen {
    fn title(self) -> &'static str {
        match self {
            Self::Books => "Books",
            Self::Jobs => "Jobs",
            Self::Cast => "Cast",
            Self::Voices => "Voices",
            Self::Validate => "Validate",
            Self::Settings => "Settings",
        }
    }

    /// Slice that builds the real screen (honest placeholder text).
    fn lands_in(self) -> &'static str {
        match self {
            Self::Books => "AP2: Books list, add-book, draft",
            Self::Jobs => "Slice 18: jobs tray, render step",
            Self::Cast => "Slice 19: cast table, audition, overrides",
            Self::Voices => "Slice 19: voices view, engine selector",
            Self::Validate => "Slice 20: validate list, transfer, models",
            Self::Settings => "here: workspace, device, helper path",
        }
    }

    fn all() -> [Self; 6] {
        [
            Self::Books,
            Self::Jobs,
            Self::Cast,
            Self::Voices,
            Self::Validate,
            Self::Settings,
        ]
    }
}

struct App {
    settings: Settings,
    machine: State,
    worker_tx: std::sync::mpsc::Sender<WorkerCmd>,
    worker_rx: std::sync::mpsc::Receiver<WorkerEvent>,
    screen: Screen,
    workspace_edit: String,
    device_edit: String,
    helper_edit: String,
    notice: String,
}

impl App {
    fn new() -> Self {
        let settings = Settings::load();
        let (worker_tx, worker_rx) = helper::spawn_worker(settings.clone());
        worker_tx
            .send(WorkerCmd::Spawn)
            .expect("worker takes commands");
        Self {
            workspace_edit: settings.workspace.display().to_string(),
            device_edit: settings.device.clone(),
            helper_edit: settings
                .helper_path
                .as_ref()
                .map(|p| p.display().to_string())
                .unwrap_or_default(),
            settings,
            machine: State::Starting { attempt: 0 },
            worker_tx,
            worker_rx,
            screen: Screen::Books,
            notice: String::new(),
        }
    }

    /// Drain worker events through the state machine (called every frame).
    fn pump(&mut self) {
        while let Ok(event) = self.worker_rx.try_recv() {
            let event = match event {
                WorkerEvent::Connected { base_url } => Event::Connected { base_url },
                WorkerEvent::Failed { message } => Event::Failed { message },
                WorkerEvent::VersionWrong { have, want } => Event::VersionWrong { have, want },
                WorkerEvent::ChildDied => Event::ChildDied,
            };
            let (state, action) = machine::transition(&self.machine, &event);
            self.machine = state;
            if action == Action::Spawn {
                self.worker_tx.send(WorkerCmd::Spawn).ok();
            }
        }
    }

    fn retry(&mut self) {
        let (state, action) = machine::transition(&self.machine, &Event::RetryRequested);
        self.machine = state;
        if action == Action::Spawn {
            self.worker_tx.send(WorkerCmd::Spawn).ok();
        }
    }

    fn save_settings(&mut self) {
        let workspace = self.workspace_edit.trim();
        if workspace.is_empty() {
            self.notice = "workspace must not be empty".to_owned();
            return;
        }
        self.settings.workspace = workspace.into();
        self.settings.device = self.device_edit.clone();
        let helper = self.helper_edit.trim();
        self.settings.helper_path = if helper.is_empty() {
            None
        } else {
            Some(helper.into())
        };
        match self.settings.save() {
            Ok(()) => {
                self.notice =
                    "saved; restart the helper (Retry) to pick up workspace/helper changes"
                        .to_owned();
            }
            Err(err) => self.notice = format!("cannot save settings: {err}"),
        }
    }

    /// Screens shown while the helper serves.
    fn screen_ui(&mut self, ui: &mut egui::Ui) {
        if self.screen == Screen::Settings {
            ui.heading(egui::RichText::new("Settings").color(rgb(theme::OXBLOOD)));
            ui.horizontal(|ui| {
                ui.label("Workspace:");
                ui.text_edit_singleline(&mut self.workspace_edit);
            });
            ui.horizontal(|ui| {
                ui.label("Device:");
                for device in settings::DEVICES {
                    ui.radio_value(&mut self.device_edit, device.to_owned(), device);
                }
            });
            ui.horizontal(|ui| {
                ui.label("Helper path (empty = discover):");
                ui.text_edit_singleline(&mut self.helper_edit);
            });
            if ui.button("Save").clicked() {
                self.save_settings();
            }
            if !self.notice.is_empty() {
                ui.label(&self.notice);
            }
            return;
        }
        ui.heading(egui::RichText::new(self.screen.title()).color(rgb(theme::OXBLOOD)));
        ui.label(self.screen.lands_in());
    }

    /// Halt screens (missing/crashed/version): reason + Retry.
    fn halt_ui(&mut self, ui: &mut egui::Ui, title: &str, reason: &str) {
        ui.heading(egui::RichText::new(title).color(rgb(theme::OXBLOOD)));
        ui.group(|ui| {
            ui.label(egui::RichText::new(reason).color(rgb(theme::SAGE)));
        });
        if ui.button("Retry").clicked() {
            self.retry();
        }
        ui.label("Settings holds the workspace, device, and helper path.");
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        ui.ctx()
            .request_repaint_after(std::time::Duration::from_millis(500));
        self.pump();
        ui.visuals_mut().extreme_bg_color = rgb(theme::PAPER);
        ui.horizontal(|ui| {
            ui.heading(egui::RichText::new("Auloud Scribe").color(rgb(theme::OXBLOOD)));
            for screen in Screen::all() {
                ui.selectable_value(&mut self.screen, screen, screen.title());
            }
        });
        ui.separator();
        // Clone to satisfy the borrow checker: halt screens read the state
        // while the buttons mutate it.
        match self.machine.clone() {
            State::Running { .. } => self.screen_ui(ui),
            State::Starting { .. } => {
                ui.spinner();
                ui.label("Starting helper…");
            }
            State::Missing { reason } => {
                self.halt_ui(ui, "No helper connected.", &reason);
            }
            State::Crashed { message } => {
                self.halt_ui(ui, "Helper crashed.", &message);
            }
            State::VersionMismatch { have, want } => {
                self.halt_ui(
                    ui,
                    "Helper speaks a contract this app does not know.",
                    &format!(
                        "helper API {have}, app API {want}: upgrade one side so both \
                         agree (docs/api.md), then Retry"
                    ),
                );
            }
        }
    }

    fn on_exit(&mut self) {
        self.worker_tx.send(WorkerCmd::Shutdown).ok();
        self.settings.save().ok();
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
