// SPDX-License-Identifier: AGPL-3.0-or-later
//! Shared UI code (Slice 17 AP1): design tokens, helper lifecycle worker,
//! lifecycle state machine, and settings. Screens live in `main.rs`.

pub mod books;
pub mod cast;
pub mod components;
pub mod helper;
pub mod machine;
pub mod models;
pub mod progress;
pub mod render;
pub mod send;
pub mod settings;
pub mod validate;

/// Design tokens from `scribe/ui/static/index.html` (light theme).
pub mod theme {
    /// Paper background.
    pub const PAPER: (u8, u8, u8) = (0xFA, 0xF7, 0xF0);
    /// Body ink.
    pub const INK: (u8, u8, u8) = (0x1A, 0x17, 0x14);
    /// Oxblood accent (titles, primary actions).
    pub const OXBLOOD: (u8, u8, u8) = (0x7B, 0x2D, 0x26);
    /// Sage accent (progress, ok states).
    pub const SAGE: (u8, u8, u8) = (0x5F, 0x71, 0x66);

    /// Serif display family for titles (DS2: Source Serif 4, OFL-1.1,
    /// vendored under `scribe-ui/fonts/`).
    pub const SERIF_FAMILY: &str = "serif-display";

    /// DS3: the one type scale. Body and Heading keep egui's sizes
    /// (13 and 18); Small was 9.0, too small to read in a chip.
    pub const TYPE_SMALL: f32 = 12.0;
    /// DS3: the one row spacing (x, y); egui's default is (8.0, 3.0).
    pub const SPACE_ROW: (f32, f32) = (8.0, 6.0);
    /// DS3: the one button padding (x, y); egui's default is (4.0, 1.0).
    pub const BUTTON_PAD: (f32, f32) = (8.0, 4.0);

    /// DS3: the one chip shape (fill for the neutral tone, its edge, and
    /// the shared corner and padding). Tone colours live in `components`.
    pub const CHIP_NEUTRAL: (u8, u8, u8) = (0xE8, 0xE2, 0xD8);
    /// Chip edge for the neutral tone (the dark tones stroke with their fill).
    pub const CHIP_EDGE: (u8, u8, u8) = (0xC9, 0xC1, 0xB4);
    /// Chip corner radius in points.
    pub const CHIP_RADIUS: egui::CornerRadius = egui::CornerRadius::same(4);
    /// Chip inner margin (x, y) in points.
    pub const CHIP_MARGIN: egui::Margin = egui::Margin::symmetric(6, 2);

    /// Token triple to a paint colour (one definition for the whole app).
    pub fn color(rgb: (u8, u8, u8)) -> egui::Color32 {
        egui::Color32::from_rgb(rgb.0, rgb.1, rgb.2)
    }

    /// The one style entry point (DS3): spacing, type scale, serif
    /// titles. Called once from `main`, so every screen inherits it.
    pub fn apply(style: &mut egui::Style) {
        style.spacing.item_spacing = egui::vec2(SPACE_ROW.0, SPACE_ROW.1);
        style.spacing.button_padding = egui::vec2(BUTTON_PAD.0, BUTTON_PAD.1);
        style.text_styles.insert(
            egui::TextStyle::Small,
            egui::FontId::new(TYPE_SMALL, egui::FontFamily::Proportional),
        );
        serif_titles(style);
    }

    /// Point titles at the serif family, keeping their sizes (pure over
    /// the style; `apply` is the call site).
    pub fn serif_titles(style: &mut egui::Style) {
        if let Some(size) = style
            .text_styles
            .get(&egui::TextStyle::Heading)
            .map(|font| font.size)
        {
            style.text_styles.insert(
                egui::TextStyle::Heading,
                egui::FontId::new(size, egui::FontFamily::Name(SERIF_FAMILY.into())),
            );
        }
    }

    /// WCAG relative luminance of one sRGB triple.
    pub fn luminance(rgb: (u8, u8, u8)) -> f64 {
        fn channel(value: u8) -> f64 {
            let v = f64::from(value) / 255.0;
            if v <= 0.03928 {
                v / 12.92
            } else {
                ((v + 0.055) / 1.055).powf(2.4)
            }
        }
        0.2126 * channel(rgb.0) + 0.7152 * channel(rgb.1) + 0.0722 * channel(rgb.2)
    }

    /// WCAG contrast ratio of two colors (1.0 to 21.0; 4.5 is the AA bar
    /// for text).
    pub fn contrast(a: (u8, u8, u8), b: (u8, u8, u8)) -> f64 {
        let (high, low) = if luminance(a) >= luminance(b) {
            (a, b)
        } else {
            (b, a)
        };
        (luminance(high) + 0.05) / (luminance(low) + 0.05)
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn ratio_matches_known_vectors() {
            assert!((contrast((0, 0, 0), (255, 255, 255)) - 21.0).abs() < 0.01);
            assert!((contrast((119, 45, 38), (119, 45, 38)) - 1.0).abs() < 0.01);
        }

        #[test]
        fn text_pairs_pass_aa_on_paper() {
            // The pairs the app actually renders as text (D-059 measured
            // the same numbers on the web side).
            assert!(contrast(INK, PAPER) >= 4.5);
            assert!(contrast(OXBLOOD, PAPER) >= 4.5);
            assert!(contrast(SAGE, PAPER) >= 4.5);
        }

        #[test]
        fn serif_titles_keep_size_and_change_family() {
            let mut style = egui::Style::default();
            let size = style.text_styles[&egui::TextStyle::Heading].size;
            serif_titles(&mut style);
            assert_eq!(
                style.text_styles[&egui::TextStyle::Heading],
                egui::FontId::new(size, egui::FontFamily::Name(SERIF_FAMILY.into()))
            );
            // Body text untouched.
            assert_eq!(
                style.text_styles[&egui::TextStyle::Body].family,
                egui::FontFamily::Proportional
            );
        }

        #[test]
        fn apply_sets_the_one_spacing_and_type_scale() {
            let mut style = egui::Style::default();
            apply(&mut style);
            assert_eq!(style.spacing.item_spacing, egui::vec2(SPACE_ROW.0, SPACE_ROW.1));
            assert_eq!(
                style.spacing.button_padding,
                egui::vec2(BUTTON_PAD.0, BUTTON_PAD.1)
            );
            assert_eq!(style.text_styles[&egui::TextStyle::Small].size, TYPE_SMALL);
            // Titles ride along with the same scale.
            assert_eq!(
                style.text_styles[&egui::TextStyle::Heading].family,
                egui::FontFamily::Name(SERIF_FAMILY.into())
            );
        }

        #[test]
        fn neutral_chip_reads_against_the_paper() {
            // A tint can be too close to the paper to see at all; the
            // edge stroke carries the rest, so this guards the fill.
            assert!(contrast(CHIP_NEUTRAL, PAPER) >= 1.2);
            assert!(contrast(CHIP_EDGE, PAPER) > contrast(CHIP_NEUTRAL, PAPER));
        }
    }
}
