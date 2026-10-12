// SPDX-License-Identifier: AGPL-3.0-or-later
//! Native app shell (Slice 17, egui): window, theme, navigation, helper
//! lifecycle, settings, and Books. Book detail lands in AP3; other screens
//! show which slice builds them.

use eframe::egui;
use scribe_ui::books::{book_status, stage_book};
use scribe_ui::helper::{self, WorkerCmd, WorkerEvent};
use scribe_ui::machine::{self, Action, Event, State};
use scribe_ui::settings::{self, Settings};
use scribe_ui::theme;

/// Book list refresh cadence while running.
const BOOKS_POLL: std::time::Duration = std::time::Duration::from_secs(2);

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
            Self::Books => "Books (list below; detail stepper in AP3)",
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
    books: Vec<scribe_client::BookSummary>,
    books_note: String,
    last_books_poll: std::time::Instant,
    selected: Option<String>,
    detail: Option<scribe_client::BookDetail>,
    detail_note: String,
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
            books: Vec::new(),
            books_note: String::new(),
            last_books_poll: std::time::Instant::now(),
            selected: None,
            detail: None,
            detail_note: String::new(),
        }
    }

    /// Drain worker events: lifecycle ones through the state machine, book
    /// ones straight into view state (called every frame).
    fn pump(&mut self) {
        while let Ok(event) = self.worker_rx.try_recv() {
            match event {
                WorkerEvent::Books { books } => {
                    self.books = books;
                    // Recovery clears the list error; user-facing notices
                    // (upload confirmations, add failures) survive until the
                    // next one (the post-upload refresh would else wipe them
                    // within milliseconds).
                    if self.books_note.starts_with("book list failed") {
                        self.books_note.clear();
                    }
                }
                WorkerEvent::BooksFailed { message } => {
                    self.books_note = format!("book list failed: {message}");
                }
                WorkerEvent::Uploaded { filename, .. } => {
                    self.books_note = format!("draft queued for {filename}");
                    self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
                }
                WorkerEvent::UploadFailed { message } => {
                    self.books_note = format!("add failed: {message}");
                }
                WorkerEvent::Detail { detail } => {
                    self.detail = Some(*detail);
                    self.detail_note.clear();
                }
                WorkerEvent::DetailFailed { message } => {
                    self.detail_note = format!("detail failed: {message}");
                }
                WorkerEvent::Connected { base_url } => {
                    self.lifecycle(Event::Connected { base_url });
                }
                WorkerEvent::Failed { message } => {
                    self.lifecycle(Event::Failed { message });
                }
                WorkerEvent::VersionWrong { have, want } => {
                    self.lifecycle(Event::VersionWrong { have, want });
                }
                WorkerEvent::ChildDied => self.lifecycle(Event::ChildDied),
            }
        }
        if matches!(self.machine, State::Running { .. })
            && self.last_books_poll.elapsed() >= BOOKS_POLL
        {
            self.last_books_poll = std::time::Instant::now();
            self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
        }
    }

    /// One lifecycle event through the machine (spawning on demand).
    fn lifecycle(&mut self, event: Event) {
        let (state, action) = machine::transition(&self.machine, &event);
        self.machine = state;
        if action == Action::Spawn {
            self.worker_tx.send(WorkerCmd::Spawn).ok();
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
            self.settings_ui(ui);
            return;
        }
        if self.screen == Screen::Books {
            self.books_ui(ui);
            return;
        }
        ui.heading(egui::RichText::new(self.screen.title()).color(rgb(theme::OXBLOOD)));
        ui.label(self.screen.lands_in());
    }

    /// Books list with status chips + add-book (AP2).
    fn books_ui(&mut self, ui: &mut egui::Ui) {
        ui.heading(egui::RichText::new("Books").color(rgb(theme::OXBLOOD)));
        if ui.button("Add book").clicked() {
            self.add_book();
        }
        if !self.books_note.is_empty() {
            ui.label(&self.books_note);
        }
        if self.books.is_empty() {
            ui.label("No books yet. Add book to draft your first.");
            return;
        }
        // Clicked id outlives the row borrow: collect first, act after.
        let mut picked: Option<String> = None;
        egui::ScrollArea::vertical().show(ui, |ui| {
            for book in &self.books {
                ui.group(|ui| {
                    ui.horizontal(|ui| {
                        let title = if book.title.is_empty() {
                            book.id.clone()
                        } else {
                            book.title.clone()
                        };
                        if ui
                            .selectable_label(self.selected.as_deref() == Some(&book.id), title)
                            .clicked()
                        {
                            picked = Some(book.id.clone());
                        }
                        ui.label(book_status(book));
                    });
                    ui.horizontal(|ui| {
                        ui.label(format!(
                            "{} sentences, {} chapters",
                            book.sentences, book.chapters
                        ));
                        if book.bundles > 0 {
                            ui.label(if book.valid {
                                format!("{} bundles, valid", book.bundles)
                            } else {
                                format!("{} bundles", book.bundles)
                            });
                        }
                        if book.cast_edited {
                            ui.label("cast edited");
                        }
                    });
                });
            }
        });
        if let Some(id) = picked {
            self.selected = Some(id.clone());
            self.detail = None;
            self.detail_note.clear();
            self.worker_tx
                .send(WorkerCmd::SelectBook { book_id: id })
                .ok();
        }
        self.stepper_ui(ui);
    }

    /// Read-only detail stepper for the selected book (AP3).
    fn stepper_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        ui.separator();
        ui.heading(egui::RichText::new("Detail").color(rgb(theme::OXBLOOD)));
        if !self.detail_note.is_empty() {
            ui.label(&self.detail_note);
        }
        let Some(detail) = self.detail.as_ref().filter(|detail| detail.id == id) else {
            ui.spinner();
            ui.label("Loading detail…");
            return;
        };
        let cast_edited = self
            .books
            .iter()
            .find(|book| book.id == id)
            .map(|book| book.cast_edited)
            .unwrap_or(false);
        for step in scribe_ui::books::stepper(detail, cast_edited) {
            let mark = match step.status {
                scribe_ui::books::StepStatus::Done => "done",
                scribe_ui::books::StepStatus::Pending => "pending",
                scribe_ui::books::StepStatus::Failed => "FAILED",
            };
            ui.horizontal(|ui| {
                ui.label(format!("[{}]", mark));
                ui.label(step.label);
                ui.label(step.note);
            });
        }
    }

    /// Native pick + stage + upload (the dialog is owner-tested; the
    /// staging below it is unit-tested in `books.rs`).
    fn add_book(&mut self) {
        let Some(path) = rfd::FileDialog::new()
            .add_filter("Ebooks", &["epub", "pdf"])
            .pick_file()
        else {
            return;
        };
        match stage_book(&self.settings.workspace, &path) {
            Ok(staged) => {
                self.books_note = format!(
                    "uploading {}…",
                    staged
                        .file_name()
                        .map(|n| n.to_string_lossy())
                        .unwrap_or_default()
                );
                self.worker_tx.send(WorkerCmd::Upload { path: staged }).ok();
            }
            Err(message) => self.books_note = message,
        }
    }

    /// Settings editor, reachable from any machine state (a bad helper path
    /// must stay fixable: the halt screens route here, never past it).
    fn settings_ui(&mut self, ui: &mut egui::Ui) {
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
        // while the buttons mutate it. Settings stays reachable while
        // halted: a bad helper path must stay fixable (Retry alone loops
        // the same refusal).
        match self.machine.clone() {
            State::Running { .. } => self.screen_ui(ui),
            _ if self.screen == Screen::Settings => self.settings_ui(ui),
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
