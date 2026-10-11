// SPDX-License-Identifier: AGPL-3.0-or-later
//! Slint spike (Slice 16 UI1): same two screens — cast rows with per-row
//! voice dropdowns plus audition buttons, and a jobs tray with progress
//! replayed from the recorded SSE sample. Markup is inline (no build.rs);
//! UI2 moves it to files if Slint wins.

use std::rc::Rc;
use std::time::Duration;

use slint::{ComponentHandle, Model, ModelRc, Timer, TimerMode, VecModel};

slint::slint! {
    import { Button, ComboBox, ProgressIndicator, ScrollView } from "std-widgets.slint";

    struct CastEntry {
        character: string,
        voice: string,
        speed-text: string,
        lines-text: string,
    }

    export component Spike inherits Window {
        title: "Auloud spike (slint)";
        in-out property <[CastEntry]> cast-rows;
        in-out property <[string]> voices;
        in-out property <float> job-fraction;
        in-out property <string> job-step;
        in-out property <string> job-state;
        in-out property <string> auditioned;
        callback set-voice(int, string);
        callback audition(int);
        callback pause();
        callback resume();
        callback cancel();

        VerticalLayout {
            Text { text: "Cast"; font-size: 24px; color: #7B2D26; }
            Text { text: "cast.yaml changed on disk: review before render (conflict line)"; }
            ScrollView {
                height: 320px;
                VerticalLayout {
                    for entry[idx] in cast-rows : HorizontalLayout {
                        Text { text: entry.character; width: 140px; }
                        ComboBox {
                            model: voices;
                            current-value: entry.voice;
                            selected(v) => { root.set-voice(idx, v); }
                        }
                        Text { text: entry.speed-text; width: 60px; }
                        Text { text: entry.lines-text; width: 60px; }
                        Button { text: "Audition"; clicked => { root.audition(idx); } }
                    }
                }
            }
            Text { text: "auditioned: " + auditioned; }
            Text { text: "Jobs"; font-size: 24px; color: #7B2D26; }
            Text { text: "build ch001 (kokoro) — running"; }
            Text { text: "draft book.epub — queued"; }
            Text { text: "sample af_bella — done"; }
            ProgressIndicator { progress: job-fraction; }
            Text { text: job-step; }
            HorizontalLayout {
                Button { text: "Pause"; clicked => { root.pause(); } }
                Button { text: "Resume"; clicked => { root.resume(); } }
                Button { text: "Cancel"; clicked => { root.cancel(); } }
                Text { text: job-state; }
            }
        }
    }
}

fn main() {
    let ui = Spike::new().expect("slint spike builds");
    let rows: Vec<CastEntry> = scribe_ui::cast_rows()
        .iter()
        .map(|row| CastEntry {
            character: row.character.as_str().into(),
            voice: row.voice.as_str().into(),
            speed_text: format!("{:.2}", row.speed).as_str().into(),
            lines_text: row.lines.to_string().as_str().into(),
        })
        .collect();
    let model = Rc::new(VecModel::from(rows));
    ui.set_cast_rows(ModelRc::from(model.clone()));
    let voices: Vec<slint::SharedString> = scribe_ui::PALETTE
        .iter()
        .map(|v| slint::SharedString::from(*v))
        .collect();
    ui.set_voices(ModelRc::from(Rc::new(VecModel::from(voices))));
    ui.set_job_state("running".into());
    ui.set_auditioned("none yet".into());

    let steps = scribe_ui::replay_steps();
    let names: Vec<(String, String)> = scribe_ui::cast_rows()
        .iter()
        .map(|row| (row.character.clone(), row.voice.clone()))
        .collect();
    ui.on_set_voice({
        let model = model.clone();
        move |idx, voice| {
            let i = idx as usize;
            if let Some(mut entry) = model.row_data(i) {
                entry.voice = voice;
                model.set_row_data(i, entry);
            }
        }
    });
    ui.on_audition({
        let ui_weak = ui.as_weak();
        move |idx| {
            let label = names
                .get(idx as usize)
                .map(|(character, voice)| format!("{character} as {voice}"))
                .unwrap_or_else(|| "unknown row".to_owned());
            if let Some(ui) = ui_weak.upgrade() {
                ui.set_auditioned(label.as_str().into());
            }
        }
    });

    let ui_weak = ui.as_weak();
    let tick = std::cell::Cell::new(0_usize);
    let timer = Timer::default();
    timer.start(TimerMode::Repeated, Duration::from_millis(500), move || {
        let next = tick.get() + 1;
        tick.set(next);
        if let Some(ui) = ui_weak.upgrade() {
            if !steps.is_empty() {
                let step = &steps[next % steps.len()];
                ui.set_job_fraction(step.fraction);
                ui.set_job_step(step.label.as_str().into());
            }
        }
    });
    let ui_weak = ui.as_weak();
    ui.on_pause(move || {
        if let Some(ui) = ui_weak.upgrade() {
            ui.set_job_state("paused".into());
        }
    });
    let ui_weak = ui.as_weak();
    ui.on_resume(move || {
        if let Some(ui) = ui_weak.upgrade() {
            ui.set_job_state("running".into());
        }
    });
    let ui_weak = ui.as_weak();
    ui.on_cancel(move || {
        if let Some(ui) = ui_weak.upgrade() {
            ui.set_job_state("cancelled".into());
        }
    });

    ui.run().expect("slint spike runs");
}
