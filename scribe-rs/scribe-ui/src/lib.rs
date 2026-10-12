// SPDX-License-Identifier: AGPL-3.0-or-later
//! Shared UI code (Slice 17 AP1): design tokens, helper lifecycle worker,
//! lifecycle state machine, and settings. Screens live in `main.rs`.

pub mod books;
pub mod helper;
pub mod machine;
pub mod progress;
pub mod render;
pub mod settings;

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
}
