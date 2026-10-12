#![windows_subsystem = "windows"]
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
            Self::Jobs => "live jobs + history (same tray as below, expanded)",
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
    shutdown: std::sync::Arc<std::sync::atomic::AtomicBool>,
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
    jobs: Vec<scribe_client::Job>,
    jobs_note: String,
    show_history: bool,
    quit_confirm: bool,
    render_form: scribe_ui::render::Form,
    preflight: Option<scribe_client::PlanResult>,
    /// Book the preflight was requested for (the plan's own id is
    /// range-aware for partial renders, so it cannot key this).
    preflight_for: Option<String>,
    render_note: String,
    helper_keep_awake: Option<bool>,
    pending_remove: Option<String>,
}

impl App {
    fn new() -> Self {
        let settings = Settings::load();
        let (worker_tx, worker_rx, shutdown) = helper::spawn_worker(settings.clone());
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
            shutdown,
            screen: Screen::Books,
            notice: String::new(),
            books: Vec::new(),
            books_note: String::new(),
            last_books_poll: std::time::Instant::now(),
            selected: None,
            detail: None,
            detail_note: String::new(),
            jobs: Vec::new(),
            jobs_note: String::new(),
            show_history: false,
            quit_confirm: false,
            render_form: scribe_ui::render::Form::default(),
            preflight: None,
            preflight_for: None,
            render_note: String::new(),
            helper_keep_awake: None,
            pending_remove: None,
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
                WorkerEvent::Jobs { jobs } => {
                    self.jobs = jobs;
                }
                WorkerEvent::JobCancelled { job_id: _ } => {
                    self.jobs_note = "job cancelled".to_owned();
                    self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
                }
                WorkerEvent::JobCancelFailed { message } => {
                    self.jobs_note = format!("cancel failed: {message}");
                }
                WorkerEvent::JobPaused { job_id: _ } => {
                    self.jobs_note = "job paused".to_owned();
                    self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
                }
                WorkerEvent::JobResumed { job_id: _ } => {
                    self.jobs_note = "job resumed".to_owned();
                    self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
                }
                WorkerEvent::JobActionFailed { message } => {
                    self.jobs_note = format!("job control failed: {message}");
                }
                WorkerEvent::Preflight { book_id, plan } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.preflight = Some(*plan);
                        self.preflight_for = Some(book_id);
                        self.render_note.clear();
                    }
                }
                WorkerEvent::BuildQueued { job_id, book_id } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.render_note = format!("render queued ({job_id}): watch the tray");
                        self.preflight = None;
                        self.preflight_for = None;
                    }
                    self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
                }
                WorkerEvent::HelperSettings { keep_awake } => {
                    self.helper_keep_awake = Some(keep_awake);
                }
                WorkerEvent::RenderFailed { message } => {
                    self.render_note = message;
                }
                WorkerEvent::Deleted { book_id } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.selected = None;
                        self.detail = None;
                        self.detail_note.clear();
                    }
                    self.pending_remove = None;
                    self.books_note = "book removed".to_owned();
                    self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
                }
                WorkerEvent::DeleteFailed { message } => {
                    self.pending_remove = None;
                    self.books_note = format!("remove failed: {message}");
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
        if self.screen == Screen::Jobs {
            self.jobs_ui(ui);
            return;
        }
        ui.heading(egui::RichText::new(self.screen.title()).color(rgb(theme::OXBLOOD)));
        ui.label(self.screen.lands_in());
    }

    /// Jobs screen (JP1): the tray expanded (live rows + full history).
    fn jobs_ui(&mut self, ui: &mut egui::Ui) {
        ui.heading(egui::RichText::new("Jobs").color(rgb(theme::OXBLOOD)));
        if !self.jobs_note.is_empty() {
            ui.label(&self.jobs_note);
        }
        let jobs = self.jobs.clone();
        let (live, history) = scribe_ui::progress::split_jobs(&jobs);
        if live.is_empty() {
            ui.label("No running jobs.");
        }
        for job in &live {
            self.job_row(ui, job);
        }
        ui.separator();
        if history.is_empty() {
            ui.label("No settled jobs yet.");
        } else {
            ui.label(format!("History ({})", history.len()));
            egui::ScrollArea::vertical().show(ui, |ui| {
                for job in &history {
                    self.job_row(ui, job);
                }
            });
        }
    }

    /// Bottom strip on every screen (JP1): live rows plus history behind a
    /// toggle. Reads the jobs feed the 2 s poll already fetches; reattach
    /// falls out of the list-on-boot the tray reads.
    fn tray_ui(&mut self, ui: &mut egui::Ui) {
        ui.separator();
        let jobs = self.jobs.clone();
        let (live, history) = scribe_ui::progress::split_jobs(&jobs);
        ui.horizontal(|ui| {
            ui.label(format!("Jobs ({})", live.len()));
            if !self.jobs_note.is_empty() {
                ui.label(&self.jobs_note);
            }
            if !history.is_empty()
                && ui
                    .button(if self.show_history {
                        format!("Hide history ({})", history.len())
                    } else {
                        format!("History ({})", history.len())
                    })
                    .clicked()
            {
                self.show_history = !self.show_history;
            }
        });
        for job in &live {
            self.job_row(ui, job);
        }
        if self.show_history {
            egui::ScrollArea::vertical()
                .max_height(240.0)
                .show(ui, |ui| {
                    for job in &history {
                        self.job_row(ui, job);
                    }
                });
        }
    }

    /// One job row: label, state, counts, RTF, cache hits, controls. No
    /// percent or ETA: the jobs feed carries no plan totals and no
    /// estimate (JP2 preflight owns estimates); the bar below reuses the
    /// book scan's reported render percent when it exists, else a
    /// spinner marks indeterminate progress.
    fn job_row(&mut self, ui: &mut egui::Ui, job: &scribe_client::Job) {
        use scribe_ui::progress;
        let title = self
            .books
            .iter()
            .find(|book| book.id == job.book_id)
            .map(|book| {
                if book.title.is_empty() {
                    job.book_id.clone()
                } else {
                    scribe_ui::books::short_title(&book.title)
                }
            })
            .unwrap_or_else(|| {
                if job.book_id.is_empty() {
                    job.kind.clone()
                } else {
                    job.book_id.clone()
                }
            });
        ui.group(|ui| {
            ui.horizontal(|ui| {
                ui.label(format!("{}: {}", progress::job_label(job), title));
                ui.label(&job.state);
                ui.label(format!(
                    "{} sentences ({} cached)",
                    progress::done_sentences(&job.summary),
                    job.summary.cached
                ));
            });
            ui.horizontal(|ui| {
                if let Some(rtf) = progress::rtf(&job.summary) {
                    ui.label(format!("RTF {}", progress::format_rtf(rtf)));
                }
                ui.label(format!(
                    "wall {}",
                    progress::format_wall(job.summary.wall_s)
                ));
                let scan_percent = self
                    .books
                    .iter()
                    .find(|book| book.id == job.book_id)
                    .and_then(|book| book.rendering_percent);
                match scan_percent {
                    // Book scan's number, labeled as such: the jobs feed
                    // carries no totals, so this is never the job's own
                    // percent.
                    Some(percent) => {
                        let fraction = (percent as f32 / 100.0).clamp(0.0, 1.0);
                        ui.add(egui::ProgressBar::new(fraction).text(format!("scan {percent}%")));
                    }
                    None if job.state == "running" => {
                        ui.spinner();
                    }
                    None => {}
                }
            });
            if let Some(error) = &job.error {
                ui.label(format!("error {}: {}", error.rule, error.message));
            }
            ui.horizontal(|ui| {
                if progress::can_pause(&job.state) && ui.button("Pause").clicked() {
                    self.worker_tx
                        .send(WorkerCmd::PauseJob {
                            job_id: job.id.clone(),
                        })
                        .ok();
                }
                if progress::can_resume(&job.state) && ui.button("Resume").clicked() {
                    self.worker_tx
                        .send(WorkerCmd::ResumeJob {
                            job_id: job.id.clone(),
                        })
                        .ok();
                }
                if progress::can_cancel(&job.state) && ui.button("Cancel").clicked() {
                    self.worker_tx
                        .send(WorkerCmd::CancelJob {
                            job_id: job.id.clone(),
                        })
                        .ok();
                }
            });
        });
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
                            scribe_ui::books::short_title(&book.title)
                        };
                        if ui
                            .selectable_label(self.selected.as_deref() == Some(&book.id), title)
                            .clicked()
                        {
                            picked = Some(book.id.clone());
                        }
                        ui.label(book_status(book));
                        if let Some(progress) =
                            scribe_ui::books::draft_progress(&self.jobs, &book.id)
                        {
                            ui.label(progress);
                        }
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
                    ui.horizontal(|ui| {
                        if let Some(job_id) = scribe_ui::books::draft_job_id(&self.jobs, &book.id) {
                            if ui.button("Cancel draft").clicked() {
                                self.worker_tx.send(WorkerCmd::CancelJob { job_id }).ok();
                            }
                        }
                        if self.pending_remove.as_deref() == Some(&book.id) {
                            if ui.button("Confirm remove").clicked() {
                                // Hash from the detail document when it is
                                // open (bundle matching); rows carry the
                                // source filename themselves.
                                let sha = self
                                    .detail
                                    .as_ref()
                                    .filter(|detail| detail.id == book.id)
                                    .and_then(|detail| detail.source_sha256.clone());
                                let source = self
                                    .books
                                    .iter()
                                    .find(|row| row.id == book.id)
                                    .and_then(|row| row.source_file.clone());
                                self.worker_tx
                                    .send(WorkerCmd::DeleteBook {
                                        book_id: book.id.clone(),
                                        source_file: source,
                                        source_sha: sha,
                                    })
                                    .ok();
                            }
                            if ui.button("Keep").clicked() {
                                self.pending_remove = None;
                            }
                        } else if ui.button("Remove").clicked() {
                            self.pending_remove = Some(book.id.clone());
                        }
                    });
                });
            }
        });
        if let Some(id) = picked {
            self.selected = Some(id.clone());
            self.detail = None;
            self.detail_note.clear();
            self.render_form = scribe_ui::render::Form::default();
            self.preflight = None;
            self.preflight_for = None;
            self.render_note.clear();
            self.helper_keep_awake = None;
            self.worker_tx
                .send(WorkerCmd::SelectBook { book_id: id })
                .ok();
            self.worker_tx.send(WorkerCmd::RefreshSettings).ok();
        }
        self.stepper_ui(ui);
    }

    /// Read-only detail stepper for the selected book (AP3).
    fn stepper_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        ui.separator();
        let Some(detail) = self.detail.as_ref().filter(|detail| detail.id == id) else {
            ui.heading(egui::RichText::new("Detail").color(rgb(theme::OXBLOOD)));
            if !self.detail_note.is_empty() {
                ui.label(&self.detail_note);
            }
            ui.spinner();
            ui.label("Loading detail…");
            return;
        };
        let heading = if detail.title.is_empty() {
            "Detail".to_owned()
        } else {
            format!("Detail: {}", detail.title)
        };
        ui.heading(egui::RichText::new(heading).color(rgb(theme::OXBLOOD)));
        if !self.detail_note.is_empty() {
            ui.label(&self.detail_note);
        }
        let cast_edited = detail.cast_edited;
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
        // The detail borrow ends with the loop; the render section
        // mutates form state, so it re-reads what it needs itself.
        self.render_ui(ui);
    }

    /// Render section (JP2): chapter boxes or a range string, PDF pages,
    /// device override, keep-awake toggle, preflight, start. Editing any
    /// picker clears the preflight (it describes the old selection).
    fn render_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        // Chapters only (the full detail document stays put: it is big).
        let chapters = match self.detail.as_ref().filter(|detail| detail.id == id) {
            Some(detail) => detail.chapters.clone(),
            None => return,
        };
        let book_id = id;
        let is_pdf = self
            .books
            .iter()
            .find(|row| row.id == book_id)
            .and_then(|row| row.source_type.clone())
            .is_some_and(|kind| kind == "pdf");
        ui.separator();
        ui.heading(egui::RichText::new("Render").color(rgb(theme::OXBLOOD)));
        if !self.render_note.is_empty() {
            ui.label(&self.render_note);
        }
        if chapters.is_empty() {
            ui.label("nothing to render (no chapters yet)");
            return;
        }
        egui::ScrollArea::vertical()
            .max_height(180.0)
            .show(ui, |ui| {
                for chapter in &chapters {
                    let mut checked = self.render_form.picked.contains(&chapter.index);
                    if ui
                        .checkbox(
                            &mut checked,
                            format!(
                                "{}. {} ({} sentences)",
                                chapter.index, chapter.title, chapter.sentences
                            ),
                        )
                        .clicked()
                    {
                        scribe_ui::render::toggle(&mut self.render_form, chapter.index);
                        self.preflight = None;
                    }
                }
            });
        ui.horizontal(|ui| {
            ui.label("Chapters (range):");
            if ui
                .text_edit_singleline(&mut self.render_form.range_text)
                .changed()
            {
                let text = self.render_form.range_text.clone();
                scribe_ui::render::set_range(&mut self.render_form, &text);
                self.preflight = None;
            }
        });
        if is_pdf {
            ui.horizontal(|ui| {
                ui.label("Pages:");
                if ui
                    .text_edit_singleline(&mut self.render_form.pages_text)
                    .changed()
                {
                    let text = self.render_form.pages_text.clone();
                    scribe_ui::render::set_pages(&mut self.render_form, &text);
                    self.preflight = None;
                }
            });
        }
        ui.horizontal(|ui| {
            ui.label("Device:");
            ui.radio_value(
                &mut self.render_form.device_override,
                None,
                format!("App default ({})", self.settings.device),
            );
            for device in settings::DEVICES {
                ui.radio_value(
                    &mut self.render_form.device_override,
                    Some(device.to_owned()),
                    device,
                );
            }
        });
        match self.helper_keep_awake {
            Some(awake) => {
                let mut next = awake;
                if ui
                    .checkbox(&mut next, "Keep awake during renders")
                    .changed()
                {
                    self.worker_tx
                        .send(WorkerCmd::SetKeepAwake { keep_awake: next })
                        .ok();
                }
            }
            None => {
                ui.label("keep-awake: loading…");
            }
        }
        ui.horizontal(|ui| {
            if ui.button("Check plan").clicked() {
                let selection =
                    scribe_ui::render::to_selection(&self.render_form, &self.settings.device);
                self.worker_tx
                    .send(WorkerCmd::PlanRender {
                        book_id: book_id.clone(),
                        selection,
                    })
                    .ok();
            }
            if ui.button("Start render").clicked() {
                if self.preflight_for.as_deref() == Some(book_id.as_str()) {
                    let selection =
                        scribe_ui::render::to_selection(&self.render_form, &self.settings.device);
                    self.worker_tx
                        .send(WorkerCmd::QueueBuild {
                            book_id: book_id.clone(),
                            selection,
                        })
                        .ok();
                } else {
                    self.render_note = "check the plan first".to_owned();
                }
            }
        });
        if self.preflight_for.as_deref() == Some(book_id.as_str()) {
            if let Some(plan) = self.preflight.as_ref() {
                ui.group(|ui| {
                    // The helper's own words (counts, estimate basis, range
                    // notice): one source, no client-side paraphrase.
                    ui.label(&plan.message);
                    if !plan.page_resolution.is_empty() {
                        ui.label(format!("pages: {}", plan.page_resolution));
                    }
                });
            }
        }
    }

    /// Quit intercept (JP3): closing with live jobs asks first instead of
    /// vanishing. Quit never kills a render (detached jobs survive the app
    /// and reattach on relaunch); Stay cancels the close. No live jobs
    /// means no interruption, ever. Running-only: any other machine state
    /// has no fresh jobs feed, so a warning there could name stale jobs.
    fn quit_intercept(&mut self, ui: &mut egui::Ui) {
        if ui.ctx().input(|i| i.viewport().close_requested()) {
            let (live, _) = scribe_ui::progress::split_jobs(&self.jobs);
            if !live.is_empty() {
                // eframe 0.36 has no close hook; this is the documented
                // path (see `App::on_exit` docs in eframe's epi.rs).
                ui.ctx()
                    .send_viewport_cmd(egui::ViewportCommand::CancelClose);
                self.quit_confirm = true;
            }
        }
        if !self.quit_confirm {
            return;
        }
        let jobs = self.jobs.clone();
        let (live, _) = scribe_ui::progress::split_jobs(&jobs);
        ui.separator();
        ui.heading(egui::RichText::new("Quit with running jobs?").color(rgb(theme::OXBLOOD)));
        if live.is_empty() {
            ui.label("No running jobs anymore. Quitting is safe.");
        } else {
            ui.label("These renders continue detached and reattach on relaunch:");
            for job in &live {
                let title = self
                    .books
                    .iter()
                    .find(|book| book.id == job.book_id)
                    .map(|book| {
                        if book.title.is_empty() {
                            job.book_id.clone()
                        } else {
                            scribe_ui::books::short_title(&book.title)
                        }
                    })
                    .unwrap_or_else(|| job.book_id.clone());
                ui.label(format!(
                    "{}: {} ({} sentences)",
                    scribe_ui::progress::job_label(job),
                    title,
                    scribe_ui::progress::done_sentences(&job.summary)
                ));
            }
        }
        ui.horizontal(|ui| {
            if ui.button("Quit").clicked() {
                ui.ctx().send_viewport_cmd(egui::ViewportCommand::Close);
            }
            if ui.button("Stay").clicked() {
                self.quit_confirm = false;
            }
        });
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
            State::Running { .. } => {
                self.screen_ui(ui);
                self.tray_ui(ui);
                self.quit_intercept(ui);
            }
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
        // Flag first so a blocking startup wait aborts; the message then
        // tells the worker to kill the child on its way out.
        self.shutdown
            .store(true, std::sync::atomic::Ordering::SeqCst);
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
