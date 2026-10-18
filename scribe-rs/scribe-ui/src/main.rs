#![windows_subsystem = "windows"]
// SPDX-License-Identifier: AGPL-3.0-or-later
//! Native app shell (Slice 17, egui): window, theme, navigation, helper
//! lifecycle, settings, and Books. Book detail lands in AP3; other screens
//! show which slice builds them.

use eframe::egui;
use scribe_ui::books::{book_status, book_status_tone};
use scribe_ui::components::{self, Tone};
use scribe_ui::helper::{self, WorkerCmd, WorkerEvent};
use scribe_ui::machine::{self, Action, Event, State};
use scribe_ui::send::SendOutcome;
use scribe_ui::settings::{self, Settings};
use scribe_ui::theme;

/// Book list refresh cadence while running.
const BOOKS_POLL: std::time::Duration = std::time::Duration::from_secs(2);

/// DS3: the tone of a job state chip. Failed is the only warn, done the
/// only ok; running, paused, interrupted and cancelled read neutral.
fn job_state_tone(state: &str) -> Tone {
    match state {
        "done" => Tone::Ok,
        "failed" => Tone::Warn,
        _ => Tone::Neutral,
    }
}

/// Top-level screens; order matches the Book detail stepper.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Screen {
    Books,
    Jobs,
    Cast,
    Voices,
    Models,
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
            Self::Models => "Models",
            Self::Validate => "Validate",
            Self::Settings => "Settings",
        }
    }

    /// Slice that builds the real screen (honest placeholder text).
    fn lands_in(self) -> &'static str {
        match self {
            Self::Books => "Books (list below; detail stepper in AP3)",
            Self::Jobs => "live jobs + history (same tray as below, expanded)",
            Self::Cast => "per-book Cast section (below the detail stepper)",
            Self::Voices => "audition voices (list below)",
            Self::Models => "Kokoro model files (status, download, sideload)",
            Self::Validate => "Slice 20: validate list, transfer",
            Self::Settings => "here: workspace, device, helper path",
        }
    }

    fn all() -> [Self; 7] {
        [
            Self::Books,
            Self::Jobs,
            Self::Cast,
            Self::Voices,
            Self::Models,
            Self::Validate,
            Self::Settings,
        ]
    }
}

/// First-run wizard steps (WZ1): welcome, models, device. Skippable on
/// every step; re-runnable from Settings.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WizardStep {
    Welcome,
    Models,
    Device,
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
    send: scribe_ui::send::Sender,
    models: scribe_ui::models::Manager,
    models_dir: Option<std::path::PathBuf>,
    wizard: Option<WizardStep>,
    helper_keep_awake: Option<bool>,
    cast: Option<scribe_client::CastPayload>,
    cast_voices: Vec<String>,
    cast_note: String,
    cast_conflict: bool,
    speed_edits: std::collections::HashMap<String, String>,
    show_minor: bool,
    quotes: Option<scribe_client::QuotesPage>,
    quotes_low_only: bool,
    quotes_page: u64,
    override_speakers: std::collections::HashMap<(u64, u64, u64), String>,
    match_text: String,
    match_speaker: String,
    report_md: Option<String>,
    show_report: bool,
    validation: Option<scribe_client::ValidateResult>,
    validation_note: String,
    voices_engine: String,
    voices: Option<scribe_client::VoicesResponse>,
    voices_for: Option<String>,
    voices_note: String,
    audio: Option<AudioOut>,
    pending_remove: Option<String>,
}

/// Audition playback (CS3): the device handle must outlive the player.
struct AudioOut {
    /// OS audio sink (dropping it ends playback).
    _handle: rodio::MixerDeviceSink,
    /// Queued clips (one voice at a time; a new Play cuts the old).
    player: rodio::Player,
    /// Voice on the player (empty when idle).
    track: String,
}

impl App {
    fn new() -> Self {
        let settings = Settings::load();
        let (worker_tx, worker_rx, shutdown) = helper::spawn_worker(settings.clone());
        worker_tx
            .send(WorkerCmd::Spawn)
            .expect("worker takes commands");
        // First-run gate (cheap metadata check): models missing means a
        // fresh install that cannot render yet.
        let wizard =
            if scribe_ui::models::setup_needed(&scribe_ui::models::models_dir(&settings.workspace))
            {
                Some(WizardStep::Welcome)
            } else {
                None
            };
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
            send: scribe_ui::send::Sender::default(),
            models: scribe_ui::models::Manager::fresh(),
            models_dir: None,
            wizard,
            helper_keep_awake: None,
            cast: None,
            cast_voices: Vec::new(),
            cast_note: String::new(),
            cast_conflict: false,
            speed_edits: std::collections::HashMap::new(),
            show_minor: false,
            quotes: None,
            quotes_low_only: true,
            quotes_page: 1,
            override_speakers: std::collections::HashMap::new(),
            match_text: String::new(),
            match_speaker: String::new(),
            report_md: None,
            show_report: false,
            validation: None,
            validation_note: String::new(),
            voices_engine: "kokoro".to_owned(),
            voices: None,
            voices_for: None,
            voices_note: String::new(),
            audio: None,
            pending_remove: None,
        }
    }

    /// Drain worker events: lifecycle ones through the state machine, book
    /// ones straight into view state (called every frame).
    fn pump(&mut self) {
        // TR1: send-thread events drain here too (non-blocking); the worker
        // feed below is untouched.
        self.send.poll();
        // MM1: model-thread events drain the same way.
        self.models.poll();
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
                    // Recovery clears control errors (confirmations
                    // survive like the book notices do).
                    if self.jobs_note.starts_with("cancel failed")
                        || self.jobs_note.starts_with("job control failed")
                    {
                        self.jobs_note.clear();
                    }
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
                        self.validation = None;
                        self.validation_note.clear();
                    }
                    self.worker_tx.send(WorkerCmd::RefreshBooks).ok();
                }
                WorkerEvent::HelperSettings { keep_awake } => {
                    self.helper_keep_awake = Some(keep_awake);
                }
                WorkerEvent::RenderFailed { message } => {
                    self.render_note = message;
                }
                WorkerEvent::Cast {
                    book_id,
                    payload,
                    voices,
                } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.speed_edits = payload
                            .stats
                            .iter()
                            .map(|stat| (stat.character.clone(), format!("{}", stat.speed)))
                            .collect();
                        self.cast = Some(*payload);
                        self.cast_voices = voices;
                        self.cast_note.clear();
                        self.cast_conflict = false;
                        // Quotes follow the cast (one fetch per load, not
                        // per poll); the picker stays in step with edits.
                        self.refresh_quotes(&book_id);
                    }
                }
                WorkerEvent::CastPatched { book_id, patch } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        let affected = patch.cast.affected.as_ref().map(|found| found.quotes);
                        self.speed_edits = patch
                            .cast
                            .stats
                            .iter()
                            .map(|stat| (stat.character.clone(), format!("{}", stat.speed)))
                            .collect();
                        self.cast = Some(patch.cast);
                        self.cast_note = match affected {
                            Some(quotes) => format!(
                                "{} ops applied, {} quotes need re-render",
                                patch.applied, quotes
                            ),
                            None => format!("{} ops applied", patch.applied),
                        };
                        self.cast_conflict = false;
                        // The picker and report describe the old cast:
                        // resync both behind the same echo.
                        self.refresh_quotes(&book_id);
                        self.report_md = None;
                        self.show_report = false;
                    }
                }
                WorkerEvent::CastConflict { book_id, message } => {
                    // No auto-refresh: typed speed edits stay on screen,
                    // Reload is explicit.
                    if self.selected.as_deref() == Some(&book_id) {
                        self.cast_note = message;
                        self.cast_conflict = true;
                    }
                }
                WorkerEvent::CastFailed { book_id, message } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.cast_note = message;
                        self.cast_conflict = false;
                    }
                }
                WorkerEvent::Quotes { book_id, page } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.quotes = Some(*page);
                    }
                }
                WorkerEvent::Report { book_id, markdown } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.report_md = Some(markdown);
                        self.show_report = true;
                    }
                }
                WorkerEvent::Validated { book_id, result } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.validation = Some(*result);
                        self.validation_note.clear();
                    }
                }
                WorkerEvent::ValidateFailed { book_id, message } => {
                    if self.selected.as_deref() == Some(&book_id) {
                        self.validation_note = message;
                    }
                }
                WorkerEvent::Voices { engine, response } => {
                    if self.voices_engine == engine {
                        self.voices = Some(*response);
                        self.voices_note.clear();
                    }
                }
                WorkerEvent::Wav { name, bytes } => match self.ensure_audio() {
                    Ok(()) => match scribe_ui::cast::decode_wav(&bytes) {
                        Ok(source) => {
                            if let Some(audio) = self.audio.as_mut() {
                                audio.player.stop();
                                audio.player.append(source);
                                audio.track = name;
                            }
                            self.voices_note.clear();
                        }
                        Err(message) => {
                            self.voices_note = message;
                        }
                    },
                    Err(message) => {
                        self.voices_note = message;
                    }
                },
                WorkerEvent::SampleQueued { job_id } => {
                    self.voices_note = format!(
                        "sample queued ({job_id}): press Play again when the tray shows it done"
                    );
                }
                WorkerEvent::VoicesFailed { message } => {
                    self.voices_note = message;
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
        if self.screen == Screen::Voices {
            self.voices_ui(ui);
            return;
        }
        if self.screen == Screen::Models {
            self.models_ui(ui);
            return;
        }
        ui.heading(egui::RichText::new(self.screen.title()).color(theme::color(theme::OXBLOOD)));
        ui.label(self.screen.lands_in());
    }

    /// Lazily open the OS audio sink (first Play only; headless machines
    /// get a note instead of a crash).
    fn ensure_audio(&mut self) -> Result<(), String> {
        if self.audio.is_none() {
            let handle = rodio::DeviceSinkBuilder::open_default_sink()
                .map_err(|err| format!("no audio device: {err:?}"))?;
            let player = rodio::Player::connect_new(handle.mixer());
            self.audio = Some(AudioOut {
                _handle: handle,
                player,
                track: String::new(),
            });
        }
        Ok(())
    }

    /// Voices screen (CS3): engine selector, voice list with audition,
    /// sample sentence. Clips fetch on Play (queued jobs seed them).
    fn voices_ui(&mut self, ui: &mut egui::Ui) {
        ui.heading(egui::RichText::new("Voices").color(theme::color(theme::OXBLOOD)));
        if !self.voices_note.is_empty() {
            ui.label(&self.voices_note);
        }
        if let Some(audio) = self.audio.as_ref() {
            if !audio.player.empty() {
                ui.horizontal(|ui| {
                    ui.label(format!("playing {}", audio.track));
                    if ui.button("Stop").clicked() {
                        audio.player.stop();
                    }
                });
            }
        }
        ui.horizontal(|ui| {
            ui.label("Engine:");
            for engine in scribe_ui::cast::ENGINES {
                ui.radio_value(&mut self.voices_engine, engine.to_owned(), engine);
            }
            if ui.button("Reload").clicked() {
                self.voices_for = None;
            }
        });
        if self.voices_for.as_deref() != Some(self.voices_engine.as_str()) {
            self.voices_for = Some(self.voices_engine.clone());
            self.worker_tx
                .send(WorkerCmd::RefreshVoices {
                    engine: self.voices_engine.clone(),
                })
                .ok();
        }
        let response = match self
            .voices
            .as_ref()
            .filter(|response| response.engine == self.voices_engine)
        {
            Some(response) => response.clone(),
            None => {
                if self.voices_note.is_empty() {
                    ui.spinner();
                    ui.label("Loading voices…");
                }
                return;
            }
        };
        if !response.sample_text.is_empty() {
            ui.label(format!("Sample: {}", response.sample_text));
        }
        if response.details.is_empty() {
            ui.label("no voices listed for this engine");
            return;
        }
        let engine = response.engine.clone();
        egui::ScrollArea::vertical().show(ui, |ui| {
            for voice in &response.details {
                ui.horizontal(|ui| {
                    ui.label(format!(
                        "{} ({} {})",
                        voice.name, voice.locale, voice.gender
                    ));
                    if ui.button("Play").clicked() {
                        self.worker_tx
                            .send(WorkerCmd::PlayVoice {
                                engine: engine.clone(),
                                name: voice.name.clone(),
                            })
                            .ok();
                    }
                });
            }
        });
    }

    /// Jobs screen (JP1): the tray expanded (live rows + full history).
    fn jobs_ui(&mut self, ui: &mut egui::Ui) {
        ui.heading(egui::RichText::new("Jobs").color(theme::color(theme::OXBLOOD)));
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
        let title = scribe_ui::books::job_book_title(&self.books, job);
        ui.group(|ui| {
            ui.horizontal(|ui| {
                ui.label(format!("{}: {}", progress::job_label(job), title));
                components::chip(ui, job.state.clone(), job_state_tone(&job.state));
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
        ui.heading(egui::RichText::new("Books").color(theme::color(theme::OXBLOOD)));
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
        // One scroll holds list and detail: the detail stack grew past
        // any window (stepper, render, cast, overrides, report), and a
        // second inner scroll would strand sections off-screen.
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
                        components::chip(ui, book_status(book), book_status_tone(book));
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
                        // Cancellable only: the server rejects the rest
                        // (an interrupted draft offers Resume in the tray).
                        if let Some(job) = scribe_ui::books::draft_job(&self.jobs, &book.id) {
                            if scribe_ui::progress::can_cancel(&job.state)
                                && ui.button("Cancel draft").clicked()
                            {
                                self.worker_tx
                                    .send(WorkerCmd::CancelJob {
                                        job_id: job.id.clone(),
                                    })
                                    .ok();
                            }
                        }
                        if self.pending_remove.as_deref() == Some(&book.id) {
                            if ui.button("Confirm remove").clicked() {
                                self.worker_tx
                                    .send(WorkerCmd::DeleteBook {
                                        book_id: book.id.clone(),
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
            self.stepper_ui(ui);
        });
        if let Some(id) = picked {
            self.selected = Some(id.clone());
            self.detail = None;
            self.detail_note.clear();
            self.render_form = scribe_ui::render::Form::default();
            self.preflight = None;
            self.preflight_for = None;
            self.render_note.clear();
            // TR1: a send belongs to the old book; drop its settled state
            // (a running thread still lands, rare and harmless) but keep
            // the remembered ip/code.
            self.send.outcome = None;
            self.send.phase = None;
            self.helper_keep_awake = None;
            self.cast = None;
            self.cast_voices.clear();
            self.cast_note.clear();
            self.cast_conflict = false;
            self.speed_edits.clear();
            self.quotes = None;
            self.quotes_low_only = true;
            self.quotes_page = 1;
            self.override_speakers.clear();
            self.match_text.clear();
            self.match_speaker.clear();
            self.report_md = None;
            self.show_report = false;
            self.validation = None;
            self.validation_note.clear();
            self.worker_tx
                .send(WorkerCmd::SelectBook {
                    book_id: id.clone(),
                })
                .ok();
            self.worker_tx.send(WorkerCmd::RefreshSettings).ok();
            self.worker_tx
                .send(WorkerCmd::RefreshCast { book_id: id })
                .ok();
        }
    }

    /// Read-only detail stepper for the selected book (AP3).
    fn stepper_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        ui.separator();
        let Some(detail) = self.detail.as_ref().filter(|detail| detail.id == id) else {
            ui.heading(egui::RichText::new("Detail").color(theme::color(theme::OXBLOOD)));
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
        ui.heading(egui::RichText::new(heading).color(theme::color(theme::OXBLOOD)));
        if !self.detail_note.is_empty() {
            ui.label(&self.detail_note);
        }
        let cast_edited = detail.cast_edited;
        for step in scribe_ui::books::stepper(detail, cast_edited) {
            let (mark, tone) = match step.status {
                scribe_ui::books::StepStatus::Done => ("done", Tone::Ok),
                scribe_ui::books::StepStatus::Pending => ("pending", Tone::Neutral),
                scribe_ui::books::StepStatus::Failed => ("FAILED", Tone::Warn),
            };
            ui.horizontal(|ui| {
                components::chip(ui, mark, tone);
                ui.label(step.label);
                ui.label(step.note);
            });
        }
        // The detail borrow ends with the loop; the render section
        // mutates form state, so it re-reads what it needs itself.
        self.render_ui(ui);
        self.cast_ui(ui);
        self.send_ui(ui);
        self.validate_ui(ui);
    }

    /// First-run wizard (WZ1): welcome, models, device. Every step
    /// skips straight to Books; the models step reuses the Models
    /// screen, the device step reuses the settings editor state.
    fn wizard_ui(&mut self, ui: &mut egui::Ui) {
        let Some(step) = self.wizard else {
            return;
        };
        ui.heading(egui::RichText::new("Welcome to Auloud Scribe").color(theme::color(theme::OXBLOOD)));
        match step {
            WizardStep::Welcome => {
                ui.label("Turn ebooks into audiobooks: add a book, draft it, render it, send it to the tablet.");
                ui.horizontal(|ui| {
                    if ui.button("Continue").clicked() {
                        self.wizard = Some(WizardStep::Models);
                    }
                    if ui.button("Skip").clicked() {
                        self.wizard = None;
                    }
                });
            }
            WizardStep::Models => {
                self.models_ui(ui);
                ui.separator();
                ui.horizontal(|ui| {
                    if ui.button("Back").clicked() {
                        self.wizard = Some(WizardStep::Welcome);
                    }
                    if ui.button("Next").clicked() {
                        self.wizard = Some(WizardStep::Device);
                    }
                    if ui.button("Skip").clicked() {
                        self.wizard = None;
                    }
                });
            }
            WizardStep::Device => {
                ui.label("Which machine renders?");
                ui.horizontal(|ui| {
                    ui.label("Device:");
                    for device in settings::DEVICES {
                        ui.radio_value(&mut self.device_edit, device.to_owned(), device);
                    }
                });
                ui.horizontal(|ui| {
                    if ui.button("Back").clicked() {
                        self.wizard = Some(WizardStep::Models);
                    }
                    if ui.button("Finish").clicked() {
                        self.save_settings();
                        self.wizard = None;
                        self.screen = Screen::Books;
                    }
                    if ui.button("Skip").clicked() {
                        self.wizard = None;
                    }
                });
                if !self.notice.is_empty() {
                    ui.label(&self.notice);
                }
            }
        }
    }

    /// Models screen (MM1): Kokoro pair status, download with byte
    /// progress plus checksum, sideload, delete. Same PC as the helper,
    /// so files land directly in `<workspace>/models` (the D-163 home).
    fn models_ui(&mut self, ui: &mut egui::Ui) {
        let dir = scribe_ui::models::models_dir(&self.settings.workspace);
        // First arrival (or workspace switch) checks the pair; hashing
        // hundreds of megabytes stays on the manager thread. A switch
        // waits for idle: the running op targets the old folder, and its
        // settle relabels nothing until it lands.
        if self.models_dir.as_ref() != Some(&dir) && !self.models.busy() {
            self.models_dir = Some(dir.clone());
            self.models
                .begin(scribe_ui::models::ModelOp::Refresh, dir.clone());
        }
        ui.heading(egui::RichText::new("Models").color(theme::color(theme::OXBLOOD)));
        ui.label(format!("Folder: {}", dir.display()));
        ui.label(scribe_ui::models::LICENSE_LINE);
        ui.separator();
        let busy = self.models.busy();
        for (index, pinned) in scribe_ui::models::PAIR.iter().enumerate() {
            let state = self
                .models
                .states
                .get(index)
                .cloned()
                .unwrap_or(scribe_ui::models::FileState::Missing);
            ui.horizontal(|ui| {
                ui.label(pinned.name);
                match state {
                    scribe_ui::models::FileState::Missing => {
                        ui.label("missing");
                    }
                    scribe_ui::models::FileState::Pinned => {
                        ui.label(format!(
                            "ready (pinned, {})",
                            scribe_ui::send::human_bytes(pinned.size)
                        ));
                    }
                    scribe_ui::models::FileState::Unpinned { size } => {
                        ui.label(format!(
                            "present, not pinned ({})",
                            scribe_ui::send::human_bytes(size)
                        ));
                    }
                }
            });
            ui.horizontal(|ui| {
                let present = !matches!(state, scribe_ui::models::FileState::Missing);
                if ui.button("Download").clicked() && !busy {
                    self.models
                        .begin(scribe_ui::models::ModelOp::Download(index), dir.clone());
                }
                if !present {
                    if ui.button("Sideload…").clicked() && !busy {
                        if let Some(picked) = rfd::FileDialog::new()
                            .add_filter("Models", &["onnx", "bin"])
                            .pick_file()
                        {
                            self.models.begin(
                                scribe_ui::models::ModelOp::Sideload(index, picked),
                                dir.clone(),
                            );
                        }
                    }
                } else if ui.button("Delete").clicked() && !busy {
                    self.models
                        .begin(scribe_ui::models::ModelOp::Delete(index), dir.clone());
                }
            });
        }
        if busy {
            ui.spinner();
        }
        if let Some(phase) = self.models.phase.clone() {
            ui.label(phase);
        }
        if let Some((downloaded, total)) = self.models.progress {
            let line = match total {
                Some(total) => format!(
                    "{} / {}",
                    scribe_ui::send::human_bytes(downloaded),
                    scribe_ui::send::human_bytes(total)
                ),
                None => format!("{}…", scribe_ui::send::human_bytes(downloaded)),
            };
            ui.label(line);
        }
        if let Some(outcome) = self.models.outcome.clone() {
            if let Some(error) = outcome.error {
                ui.label(error);
            } else if !outcome.note.is_empty() {
                ui.label(outcome.note);
            }
        }
        if ui.button("Check again").clicked() && !busy {
            self.models
                .begin(scribe_ui::models::ModelOp::Refresh, dir.clone());
        }
    }

    /// Validate section (VT1): per-bundle ticks plus file/rule/message
    /// lines from the last run. One button re-runs it inline (read-only,
    /// no job); no bundles yet names the next action instead.
    fn request_validation(&self, book_id: &str) {
        self.worker_tx
            .send(WorkerCmd::Validate {
                book_id: book_id.to_owned(),
            })
            .ok();
    }

    fn validate_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        ui.separator();
        ui.heading(egui::RichText::new("Validate").color(theme::color(theme::OXBLOOD)));
        if !self.validation_note.is_empty() {
            ui.label(&self.validation_note);
        }
        match self
            .validation
            .as_ref()
            .filter(|result| result.book_id == id)
        {
            Some(result) => {
                for line in scribe_ui::validate::lines(result) {
                    ui.label(line);
                }
                if ui.button("Re-validate").clicked() {
                    self.validation = None;
                    self.validation_note = "Validating…".to_owned();
                    self.request_validation(&id);
                }
            }
            None => {
                let detail = self.detail.as_ref().filter(|detail| detail.id == id);
                let Some(detail) = detail else {
                    return;
                };
                if detail.bundles.is_empty() {
                    ui.label("Render a bundle first, then validate it.");
                    return;
                }
                if ui.button("Validate").clicked() {
                    self.validation = None;
                    self.validation_note = "Validating…".to_owned();
                    self.request_validation(&id);
                }
            }
        }
    }

    /// Send section (TR1): tablet IP plus pairing code, push the newest
    /// valid bundle over Wi-Fi. The stepper transfer row stays the
    /// folder-copy record (the helper never learns about Wi-Fi sends);
    /// this section reports its own outcome.
    fn send_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        let (bundles, workspace) = match self.detail.as_ref().filter(|detail| detail.id == id) {
            Some(detail) => (detail.bundles.clone(), self.settings.workspace.clone()),
            None => return,
        };
        ui.separator();
        ui.heading(egui::RichText::new("Send to tablet").color(theme::color(theme::OXBLOOD)));
        let bundle = match bundles.iter().find(|entry| entry.valid).or(bundles.first()) {
            Some(entry) => entry.clone(),
            None => {
                ui.label("Render a bundle first, then send it.");
                return;
            }
        };
        if !bundle.valid {
            ui.label("No valid bundle (see Validate); the tablet will refuse it.");
        }
        ui.horizontal(|ui| {
            ui.label("Tablet IP:");
            ui.text_edit_singleline(&mut self.send.ip);
            ui.label("Code:");
            ui.text_edit_singleline(&mut self.send.code);
            let busy = self.send.busy();
            if ui.button("Send").clicked() && !busy {
                let dir = workspace.join(&bundle.path);
                let ip = self.send.ip.clone();
                let code = self.send.code.clone();
                self.send
                    .begin(dir, ip, scribe_ui::send::DEFAULT_PORT, code);
            }
        });
        if self.send.busy() {
            ui.spinner();
        }
        if let Some(phase) = self.send.phase.clone() {
            ui.label(phase);
        }
        match self.send.outcome.clone() {
            Some(SendOutcome::Sent {
                title, duplicate, ..
            }) => {
                ui.label(if duplicate {
                    format!("Already on tablet: {title}")
                } else {
                    format!("Sent {title} (open it in the tablet library)")
                });
            }
            Some(SendOutcome::Failed(message)) => {
                ui.label(message);
            }
            None => {}
        }
    }

    /// Render section (JP2): chapter boxes or a range string, PDF pages,
    /// device override, keep-awake toggle, preflight, start. Editing any
    /// picker clears the preflight (it describes the old selection).
    fn render_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        // Chapters only (the full detail document stays put: it is big).
        let (chapters, bundles) = match self.detail.as_ref().filter(|detail| detail.id == id) {
            Some(detail) => (detail.chapters.clone(), detail.bundles.clone()),
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
        ui.heading(egui::RichText::new("Render").color(theme::color(theme::OXBLOOD)));
        if !self.render_note.is_empty() {
            ui.label(&self.render_note);
        }
        // Done state first: did the render succeed, where is the bundle,
        // and what comes next. The picker below starts a new one.
        let finished = scribe_ui::render::finished_lines(&bundles);
        if !finished.is_empty() {
            ui.group(|ui| {
                for line in &finished {
                    ui.label(line);
                }
            });
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
        let jobs = self.jobs.clone();
        let (live, _) = scribe_ui::progress::split_jobs(&jobs);
        // Gate on the panel flag too: the panel's own Quit arrives as
        // another close request, and cancelling it again would trap the
        // user (Quit could never quit while a job runs).
        let close_requested = ui.ctx().input(|i| i.viewport().close_requested());
        if scribe_ui::progress::should_cancel_close(
            close_requested,
            !live.is_empty(),
            self.quit_confirm,
        ) {
            // eframe 0.36 has no close hook; this is the documented
            // path (see `App::on_exit` docs in eframe's epi.rs).
            ui.ctx()
                .send_viewport_cmd(egui::ViewportCommand::CancelClose);
            self.quit_confirm = true;
        }
        if !self.quit_confirm {
            return;
        }
        // AX1: Esc takes the safe choice (Stay), so the dialog cannot
        // become a keyboard trap for a keyboard-only user.
        if ui.input(|i| i.key_pressed(egui::Key::Escape)) {
            self.quit_confirm = false;
            return;
        }
        ui.separator();
        ui.heading(egui::RichText::new("Quit with running jobs?").color(theme::color(theme::OXBLOOD)));
        if live.is_empty() {
            ui.label("No running jobs anymore. Quitting is safe.");
        } else {
            ui.label("These renders continue detached and reattach on relaunch:");
            for job in &live {
                ui.label(format!(
                    "{}: {} ({} sentences)",
                    scribe_ui::progress::job_label(job),
                    scribe_ui::books::job_book_title(&self.books, job),
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

    /// Cast table (CS1): voice dropdowns, speed fields, first-person
    /// selector, minor collapsible, conflict reload. Voice changes send
    /// immediately; speeds apply on leave/Enter; every write is one atomic
    /// PATCH, so a conflict names the file instead of tearing it.
    fn cast_ui(&mut self, ui: &mut egui::Ui) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        let drafted = self
            .detail
            .as_ref()
            .filter(|detail| detail.id == id)
            .is_some_and(|detail| detail.drafted);
        ui.separator();
        ui.heading(egui::RichText::new("Cast").color(theme::color(theme::OXBLOOD)));
        if !drafted {
            ui.label("cast appears after drafting");
            return;
        }
        if !self.cast_note.is_empty() {
            ui.label(&self.cast_note);
        }
        if self.cast_conflict && ui.button("Reload their version").clicked() {
            self.worker_tx
                .send(WorkerCmd::RefreshCast {
                    book_id: id.clone(),
                })
                .ok();
        }
        // Small document (rows + guards); the full detail stays put.
        let cast = match self.cast.clone().filter(|cast| cast.book_id == id) {
            Some(cast) => cast,
            None => {
                if self.cast_note.is_empty() {
                    ui.spinner();
                    ui.label("Loading cast…");
                } else if ui.button("Retry").clicked() {
                    // Covers the stale 404 a finished draft leaves
                    // behind: the note names it, this refetches it.
                    self.worker_tx
                        .send(WorkerCmd::RefreshCast {
                            book_id: id.clone(),
                        })
                        .ok();
                }
                return;
            }
        };
        let currents: Vec<String> = cast.stats.iter().map(|stat| stat.voice.clone()).collect();
        let options = scribe_ui::cast::merge_voice_options(&currents, &self.cast_voices);
        egui::ScrollArea::vertical()
            .max_height(240.0)
            .show(ui, |ui| {
                for stat in &cast.stats {
                    ui.horizontal(|ui| {
                        ui.label(format!("{} [{}]", stat.character, stat.kind));
                        let mut voice = stat.voice.clone();
                        egui::ComboBox::from_id_salt(format!("voice-{}", stat.character))
                            .selected_text(&voice)
                            .show_ui(ui, |ui| {
                                for option in &options {
                                    ui.selectable_value(&mut voice, option.clone(), option);
                                }
                            });
                        if voice != stat.voice {
                            let ops = serde_json::json!([scribe_client::cast_op_set_voice(
                                &stat.character,
                                &voice,
                                None
                            )]);
                            self.worker_tx
                                .send(WorkerCmd::PatchCast {
                                    book_id: id.clone(),
                                    ops,
                                    mtime: cast.mtime,
                                })
                                .ok();
                        }
                        let buffer = self
                            .speed_edits
                            .entry(stat.character.clone())
                            .or_insert_with(|| format!("{}", stat.speed));
                        let response = ui.text_edit_singleline(buffer);
                        let commit = response.lost_focus()
                            || (response.has_focus()
                                && ui.ctx().input(|i| i.key_pressed(egui::Key::Enter)));
                        if commit {
                            match scribe_ui::cast::parse_speed(buffer) {
                                Ok(speed) if speed != stat.speed => {
                                    let ops =
                                        serde_json::json!([scribe_client::cast_op_set_speed(
                                            &stat.character,
                                            speed
                                        )]);
                                    self.worker_tx
                                        .send(WorkerCmd::PatchCast {
                                            book_id: id.clone(),
                                            ops,
                                            mtime: cast.mtime,
                                        })
                                        .ok();
                                }
                                Err(message) => {
                                    self.cast_note = message;
                                }
                                _ => {}
                            }
                        }
                        ui.label(format!("{} lines, {} min", stat.lines, stat.minutes));
                        if stat.low > 0 {
                            ui.label(format!("{} low", stat.low));
                        }
                    });
                }
            });
        ui.horizontal(|ui| {
            ui.label("First person:");
            let mut first = cast.first_person.clone();
            egui::ComboBox::from_id_salt("first-person")
                .selected_text(&first)
                .show_ui(ui, |ui| {
                    for option in scribe_ui::cast::speaker_options(&cast.stats) {
                        ui.selectable_value(&mut first, option.clone(), option);
                    }
                });
            if first != cast.first_person {
                let ops = serde_json::json!([scribe_client::cast_op_set_first_person(&first)]);
                self.worker_tx
                    .send(WorkerCmd::PatchCast {
                        book_id: id.clone(),
                        ops,
                        mtime: cast.mtime,
                    })
                    .ok();
            }
        });
        if let Some(warning) = &cast.comment_warning {
            ui.label(warning);
        }
        for finding in &cast.validation {
            ui.label(format!("{}: {}", finding.rule, finding.message));
        }
        if !cast.minor.is_empty() {
            if ui
                .button(if self.show_minor {
                    "Hide minor".to_owned()
                } else {
                    format!("Minor ({})", cast.minor.len())
                })
                .clicked()
            {
                self.show_minor = !self.show_minor;
            }
            if self.show_minor {
                for entry in &cast.minor {
                    ui.label(format!(
                        "{}: {} lines, resolves to {}",
                        entry.speaker, entry.lines, entry.resolved
                    ));
                }
            }
        }
        let speakers = scribe_ui::cast::speaker_options(&cast.stats);
        self.quotes_ui(ui, &id, cast.mtime, &speakers);
        self.report_ui(ui, &id);
    }

    /// Ask the worker for the current quotes page (filter + page state).
    fn refresh_quotes(&self, book_id: &str) {
        let confidence = if self.quotes_low_only { "low" } else { "all" };
        self.worker_tx
            .send(WorkerCmd::RefreshQuotes {
                book_id: book_id.to_owned(),
                confidence: confidence.to_owned(),
                page: self.quotes_page,
            })
            .ok();
    }

    /// Override picker (CS2): one page of quotes with per-quote speaker
    /// assignment and clearing, plus a regex row. Every write is one
    /// atomic PATCH; the echo's affected count is the rebuild preflight.
    fn quotes_ui(&mut self, ui: &mut egui::Ui, book_id: &str, mtime: f64, speakers: &[String]) {
        ui.separator();
        ui.heading(egui::RichText::new("Overrides").color(theme::color(theme::OXBLOOD)));
        ui.horizontal(|ui| {
            let mut low = self.quotes_low_only;
            ui.checkbox(&mut low, "Low confidence only");
            if low != self.quotes_low_only {
                self.quotes_low_only = low;
                self.quotes_page = 1;
                self.refresh_quotes(book_id);
            }
            if let Some(page) = self.quotes.as_ref().filter(|page| page.book_id == book_id) {
                ui.label(scribe_ui::cast::page_window(
                    page.total,
                    page.page,
                    page.per_page,
                ));
            }
        });
        let page = match self.quotes.clone().filter(|page| page.book_id == book_id) {
            Some(page) => page,
            None => {
                if self.cast_note.is_empty() {
                    ui.label("Loading quotes…");
                } else if ui.button("Retry").clicked() {
                    self.refresh_quotes(book_id);
                }
                return;
            }
        };
        ui.horizontal(|ui| {
            if ui.button("Prev").clicked() && self.quotes_page > 1 {
                self.quotes_page -= 1;
                self.refresh_quotes(book_id);
            }
            if ui.button("Next").clicked() && page.page.saturating_mul(page.per_page) < page.total {
                self.quotes_page += 1;
                self.refresh_quotes(book_id);
            }
        });
        egui::ScrollArea::vertical()
            .max_height(240.0)
            .show(ui, |ui| {
                for quote in &page.quotes {
                    let key = (quote.chapter, quote.block, quote.quote);
                    let fallback = quote
                        .resolved
                        .clone()
                        .unwrap_or_else(|| quote.speaker.clone());
                    let selected = self
                        .override_speakers
                        .entry(key)
                        .or_insert_with(|| fallback.clone())
                        .clone();
                    ui.group(|ui| {
                        ui.label(format!(
                            "{}.{}.{}: {}",
                            quote.chapter, quote.block, quote.quote, quote.text
                        ));
                        ui.horizontal(|ui| {
                            ui.label(format!(
                                "was {}",
                                quote.resolved.as_deref().unwrap_or(&quote.speaker)
                            ));
                            let mut speaker = selected.clone();
                            egui::ComboBox::from_id_salt(format!(
                                "override-{}-{}-{}",
                                quote.chapter, quote.block, quote.quote
                            ))
                            .selected_text(&speaker)
                            .show_ui(ui, |ui| {
                                for option in speakers {
                                    ui.selectable_value(&mut speaker, option.clone(), option);
                                }
                            });
                            if speaker != selected {
                                self.override_speakers.insert(key, speaker.clone());
                            }
                            if ui.button("Override").clicked() {
                                let ops =
                                    serde_json::json!([scribe_client::cast_op_add_override_quote(
                                        quote.chapter,
                                        quote.block,
                                        quote.quote,
                                        &speaker
                                    )]);
                                self.worker_tx
                                    .send(WorkerCmd::PatchCast {
                                        book_id: book_id.to_owned(),
                                        ops,
                                        mtime,
                                    })
                                    .ok();
                                // Fall back to the fresh `resolved` once the
                                // echo lands (the arm resyncs the picker).
                                self.override_speakers.remove(&key);
                            }
                            if ui.button("Clear").clicked() {
                                let ops = serde_json::json!([
                                    scribe_client::cast_op_remove_override_quote(
                                        quote.chapter,
                                        quote.block,
                                        quote.quote
                                    )
                                ]);
                                self.worker_tx
                                    .send(WorkerCmd::PatchCast {
                                        book_id: book_id.to_owned(),
                                        ops,
                                        mtime,
                                    })
                                    .ok();
                                self.override_speakers.remove(&key);
                            }
                        });
                    });
                }
            });
        ui.horizontal(|ui| {
            ui.label("Match:");
            ui.text_edit_singleline(&mut self.match_text);
            let mut speaker = if self.match_speaker.is_empty() {
                speakers.first().cloned().unwrap_or_default()
            } else {
                self.match_speaker.clone()
            };
            egui::ComboBox::from_id_salt("override-match")
                .selected_text(&speaker)
                .show_ui(ui, |ui| {
                    for option in speakers {
                        ui.selectable_value(&mut speaker, option.clone(), option);
                    }
                });
            if speaker != self.match_speaker {
                self.match_speaker = speaker.clone();
            }
            if ui.button("Add match").clicked() {
                if self.match_text.trim().is_empty() || speaker.is_empty() {
                    self.cast_note = "match needs a pattern and a speaker".to_owned();
                } else {
                    let ops = serde_json::json!([scribe_client::cast_op_add_override_match(
                        self.match_text.trim(),
                        &speaker
                    )]);
                    self.worker_tx
                        .send(WorkerCmd::PatchCast {
                            book_id: book_id.to_owned(),
                            ops,
                            mtime,
                        })
                        .ok();
                }
            }
        });
    }

    /// Read-only cast report (CS2): markdown text behind one button.
    fn report_ui(&mut self, ui: &mut egui::Ui, book_id: &str) {
        ui.separator();
        ui.heading(egui::RichText::new("Report").color(theme::color(theme::OXBLOOD)));
        if self.report_md.is_none() {
            if ui.button("Load report").clicked() {
                self.worker_tx
                    .send(WorkerCmd::RefreshReport {
                        book_id: book_id.to_owned(),
                    })
                    .ok();
            }
            return;
        }
        if ui
            .button(if self.show_report {
                "Hide report".to_owned()
            } else {
                "Show report".to_owned()
            })
            .clicked()
        {
            self.show_report = !self.show_report;
        }
        if self.show_report {
            if let Some(markdown) = self.report_md.as_ref() {
                egui::ScrollArea::vertical()
                    .max_height(240.0)
                    .show(ui, |ui| {
                        ui.label(markdown);
                    });
            }
        }
    }

    /// Native pick + upload (the dialog is owner-tested; the helper owns
    /// the one workspace copy and refuses non-ebooks with a shaped error).
    fn add_book(&mut self) {
        let Some(path) = rfd::FileDialog::new()
            .add_filter("Ebooks", &["epub", "pdf"])
            .pick_file()
        else {
            return;
        };
        self.books_note = format!(
            "uploading {}…",
            path.file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_default()
        );
        self.worker_tx.send(WorkerCmd::Upload { path }).ok();
    }

    /// Settings editor, reachable from any machine state (a bad helper path
    /// must stay fixable: the halt screens route here, never past it).
    fn settings_ui(&mut self, ui: &mut egui::Ui) {
        ui.heading(egui::RichText::new("Settings").color(theme::color(theme::OXBLOOD)));
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
        if ui.button("First-run wizard").clicked() {
            self.wizard = Some(WizardStep::Welcome);
        }
        if !self.notice.is_empty() {
            ui.label(&self.notice);
        }
    }

    /// Halt screens (missing/crashed/version): reason + Retry.
    fn halt_ui(&mut self, ui: &mut egui::Ui, title: &str, reason: &str) {
        ui.heading(egui::RichText::new(title).color(theme::color(theme::OXBLOOD)));
        ui.group(|ui| {
            ui.label(egui::RichText::new(reason).color(theme::color(theme::SAGE)));
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
        ui.visuals_mut().extreme_bg_color = theme::color(theme::PAPER);
        ui.horizontal(|ui| {
            ui.heading(egui::RichText::new("Auloud Scribe").color(theme::color(theme::OXBLOOD)));
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
                if self.wizard.is_some() {
                    self.wizard_ui(ui);
                } else {
                    self.screen_ui(ui);
                }
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
        Box::new(|cc| {
            // DS2: serif display face for titles (Source Serif 4, OFL-1.1
            // vendored in `fonts/`; Merriweather stays one swap away).
            let mut fonts = egui::FontDefinitions::default();
            fonts.font_data.insert(
                scribe_ui::theme::SERIF_FAMILY.to_owned(),
                std::sync::Arc::new(egui::FontData::from_static(include_bytes!(
                    "../fonts/source-serif-4-latin-400-normal.ttf"
                ))),
            );
            fonts.families.insert(
                egui::FontFamily::Name(scribe_ui::theme::SERIF_FAMILY.into()),
                vec![scribe_ui::theme::SERIF_FAMILY.to_owned()],
            );
            cc.egui_ctx.set_fonts(fonts);
            cc.egui_ctx.all_styles_mut(scribe_ui::theme::apply);
            Ok(Box::new(App::new()))
        }),
    )
    .expect("auloud scribe runs");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn job_state_tone_reads_failure_and_done_only() {
        assert_eq!(job_state_tone("done"), Tone::Ok);
        assert_eq!(job_state_tone("failed"), Tone::Warn);
        // The helper's other real states (see `progress`): live or
        // user-stopped work is neither good news nor bad news.
        for state in ["running", "paused", "interrupted", "cancelled"] {
            assert_eq!(job_state_tone(state), Tone::Neutral, "{state}");
        }
    }
}
