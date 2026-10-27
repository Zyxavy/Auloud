// SPDX-License-Identifier: AGPL-3.0-or-later
//! DS3: the one chip shape (Slice 21). The other four named components
//! (cast table, stepper, jobs tray, audition row) already share egui's
//! row, group and scroll widgets, so they are unified by the single
//! spacing and type scale in [`crate::theme::apply`]; only the chip
//! needed a builder, because five screens were each spelling status
//! text their own way.

use crate::theme;

/// What a chip says, not how it looks.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Tone {
    /// A name or a count, neither good nor bad.
    Neutral,
    /// Done, valid or current.
    Ok,
    /// Failed, invalid or in conflict.
    Warn,
}

impl Tone {
    /// (fill, edge, text) for this tone.
    pub fn colors(self) -> (egui::Color32, egui::Color32, egui::Color32) {
        match self {
            Tone::Neutral => (
                theme::color(theme::CHIP_NEUTRAL),
                theme::color(theme::CHIP_EDGE),
                theme::color(theme::INK),
            ),
            Tone::Ok => (
                theme::color(theme::SAGE),
                theme::color(theme::SAGE),
                theme::color(theme::PAPER),
            ),
            Tone::Warn => (
                theme::color(theme::OXBLOOD),
                theme::color(theme::OXBLOOD),
                theme::color(theme::PAPER),
            ),
        }
    }
}

/// One status chip: one padding, one corner, one text style.
pub fn chip(ui: &mut egui::Ui, text: impl Into<String>, tone: Tone) -> egui::Response {
    let (fill, edge, ink) = tone.colors();
    egui::Frame::new()
        .fill(fill)
        .stroke(egui::Stroke::new(1.0, edge))
        .corner_radius(theme::CHIP_RADIUS)
        .inner_margin(theme::CHIP_MARGIN)
        .show(ui, |ui| {
            ui.label(
                egui::RichText::new(text.into())
                    .color(ink)
                    .text_style(egui::TextStyle::Small),
            )
        })
        .response
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_tone_reads_on_its_fill() {
        for tone in [Tone::Neutral, Tone::Ok, Tone::Warn] {
            let (fill, _edge, ink) = tone.colors();
            let fill = (fill.r(), fill.g(), fill.b());
            let ink = (ink.r(), ink.g(), ink.b());
            assert!(
                theme::contrast(fill, ink) >= 4.5,
                "{tone:?} text on its own fill fails AA"
            );
        }
    }

    #[test]
    fn tones_do_not_collapse_into_one() {
        let [neutral, ok, warn] = [Tone::Neutral, Tone::Ok, Tone::Warn].map(|tone| tone.colors().0);
        assert_ne!(neutral, ok);
        assert_ne!(ok, warn);
        assert_ne!(neutral, warn);
    }
}
