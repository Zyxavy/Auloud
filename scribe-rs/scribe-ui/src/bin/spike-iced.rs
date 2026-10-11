// SPDX-License-Identifier: AGPL-3.0-or-later
//! iced spike (Slice 16 UI1): same two screens as the egui spike — a
//! scrollable cast list with per-row voice pickers plus audition buttons,
//! and a jobs tray with progress replayed from the recorded SSE sample.

use std::time::Duration;

use iced::widget::{button, column, container, pick_list, progress_bar, row, scrollable, text};
use iced::{Color, Element, Length, Subscription, Task, Theme};
use scribe_ui::{cast_rows, replay_steps, ReplayStep, TrayJob};

const OXBLOOD: Color = Color::from_rgb(
    0x7B as f32 / 255.0,
    0x2D as f32 / 255.0,
    0x26 as f32 / 255.0,
);

struct Spike {
    rows: Vec<scribe_ui::CastRow>,
    voices: Vec<String>,
    jobs: Vec<TrayJob>,
    steps: Vec<ReplayStep>,
    ticks: usize,
    auditioned: String,
    job_note: String,
}

#[derive(Debug, Clone)]
enum Message {
    VoiceSelected(usize, String),
    Audition(usize),
    Pause,
    Resume,
    Cancel,
    Tick,
}

impl Spike {
    fn new() -> Self {
        Self {
            rows: cast_rows(),
            voices: scribe_ui::PALETTE.iter().map(|v| (*v).to_owned()).collect(),
            jobs: TrayJob::tray(),
            steps: replay_steps(),
            ticks: 0,
            auditioned: "none yet".to_owned(),
            job_note: "running".to_owned(),
        }
    }

    fn fraction(&self) -> f32 {
        self.steps
            .get(self.ticks % self.steps.len().max(1))
            .map(|step| step.fraction)
            .unwrap_or(0.0)
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::VoiceSelected(i, voice) => {
                if let Some(row) = self.rows.get_mut(i) {
                    row.voice = voice;
                }
            }
            Message::Audition(i) => {
                if let Some(row) = self.rows.get(i) {
                    self.auditioned = format!("{} as {}", row.character, row.voice);
                }
            }
            Message::Pause => self.job_note = "paused".to_owned(),
            Message::Resume => self.job_note = "running".to_owned(),
            Message::Cancel => self.job_note = "cancelled".to_owned(),
            Message::Tick => self.ticks += 1,
        }
        Task::none()
    }

    fn view(&self) -> Element<'_, Message> {
        let mut cast = column![text("Cast").size(24).color(OXBLOOD)].spacing(6);
        cast = cast.push(text(
            "cast.yaml changed on disk: review before render (conflict line)",
        ));
        let mut table = column![].spacing(4);
        for (i, row) in self.rows.iter().enumerate() {
            table = table.push(row![
                text(&row.character).width(Length::Fixed(140.0)),
                pick_list(
                    self.voices.as_slice(),
                    Some(row.voice.clone()),
                    move |voice| Message::VoiceSelected(i, voice),
                )
                .width(Length::Fixed(160.0)),
                text(format!("{:.2}", row.speed)).width(Length::Fixed(60.0)),
                text(row.lines.to_string()).width(Length::Fixed(60.0)),
                button("Audition").on_press(Message::Audition(i)),
            ]);
        }
        cast = cast.push(container(scrollable(table)).height(Length::Fixed(320.0)));
        cast = cast.push(text(format!("auditioned: {}", self.auditioned)));

        let mut jobs = column![text("Jobs").size(24).color(OXBLOOD)].spacing(6);
        for job in &self.jobs {
            jobs = jobs.push(row![text(&job.label), text(&job.state)].spacing(12));
        }
        jobs = jobs.push(progress_bar(0.0..=1.0, self.fraction()));
        jobs = jobs.push(
            row![
                button("Pause").on_press(Message::Pause),
                button("Resume").on_press(Message::Resume),
                button("Cancel").on_press(Message::Cancel),
                text(&self.job_note),
            ]
            .spacing(8),
        );

        container(column![cast, jobs].spacing(16))
            .padding(12)
            .into()
    }

    fn subscription(&self) -> Subscription<Message> {
        iced::time::every(Duration::from_millis(500)).map(|_| Message::Tick)
    }
}

pub fn main() -> iced::Result {
    // Free functions (not closures) where the builder needs higher-ranked
    // `for<'a> Fn(&'a State)`: closures ground the elided lifetime early.
    fn view(spike: &Spike) -> Element<'_, Message> {
        spike.view()
    }
    fn theme(_spike: &Spike) -> Theme {
        Theme::Light
    }
    iced::application(
        Spike::new,
        |spike: &mut Spike, message: Message| spike.update(message),
        view,
    )
    .theme(theme)
    .subscription(|spike: &Spike| spike.subscription())
    .run()
}
