// SPDX-License-Identifier: AGPL-3.0-or-later
//! Shared UI code (Slice 17 AP1): design tokens, helper lifecycle worker,
//! lifecycle state machine, and settings. Screens live in `main.rs`.

pub mod books;
pub mod cast;
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
    }
}
