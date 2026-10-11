// SPDX-License-Identifier: AGPL-3.0-or-later
//! Spike harness (Slice 16 UI1): identical canned data for all three toolkit
//! spikes. Cast rows are generated deterministically; job progress replays
//! the real recorded SSE sample from `scribe-client/tests/data/`, so every
//! spike advances through the same steps.

use std::io::Cursor;

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

/// Voice palette (D-036 shortlist plus generics).
pub const PALETTE: [&str; 9] = [
    "am_onyx",
    "bf_isabella",
    "bm_lewis",
    "im_nicola",
    "jf_alpha",
    "zf_xiaoxiao",
    "am_eric",
    "af_bella",
    "am_adam",
];

/// One cast-table row.
#[derive(Debug, Clone)]
pub struct CastRow {
    /// Character name (or `narrator` / generics).
    pub character: String,
    /// Assigned voice id (editable via dropdown in the spike).
    pub voice: String,
    /// Speed offset.
    pub speed: f32,
    /// Line count.
    pub lines: u32,
    /// Spoken minutes.
    pub minutes: f32,
}

/// Sixty deterministic rows: narrator, two generics, 57 characters cycling
/// the palette (enough to prove a virtualized/scrolled table).
pub fn cast_rows() -> Vec<CastRow> {
    let mut rows = vec![
        CastRow {
            character: "narrator".to_owned(),
            voice: "am_onyx".to_owned(),
            speed: 1.0,
            lines: 412,
            minutes: 38.5,
        },
        CastRow {
            character: "default_female".to_owned(),
            voice: "af_bella".to_owned(),
            speed: 1.0,
            lines: 37,
            minutes: 3.1,
        },
        CastRow {
            character: "default_male".to_owned(),
            voice: "am_adam".to_owned(),
            speed: 1.0,
            lines: 29,
            minutes: 2.4,
        },
    ];
    for (i, voice) in PALETTE.iter().cycle().take(57).enumerate() {
        rows.push(CastRow {
            character: format!("Character {:02}", i + 1),
            voice: (*voice).to_owned(),
            speed: 1.0,
            lines: (i as u32 * 7 % 120) + 1,
            minutes: (i as f32 * 1.7 % 22.0 * 10.0).round() / 10.0,
        });
    }
    rows
}

/// Raw recorded SSE sample (real helper output, scrubbed of tokens/ports).
pub const EVENTS_SAMPLE: &str = include_str!("../../scribe-client/tests/data/events-sample.txt");

/// One replay step: fraction done plus the label to show.
#[derive(Debug, Clone)]
pub struct ReplayStep {
    /// 0.0-1.0 across the sample.
    pub fraction: f32,
    /// Short label (event kind + chapter).
    pub label: String,
}

/// Replay steps parsed from [`EVENTS_SAMPLE`] with the real client parser,
/// so every spike advances through identical states.
pub fn replay_steps() -> Vec<ReplayStep> {
    let mut stream = scribe_client::EventStream::new(Cursor::new(EVENTS_SAMPLE));
    let mut events = Vec::new();
    while let Some(item) = stream.next_event() {
        if let Ok(event) = item {
            events.push(event);
        }
    }
    let total = events.len().max(1) as f32;
    events
        .iter()
        .enumerate()
        .map(|(i, event)| {
            let kind = event
                .data
                .get("event")
                .and_then(|v| v.as_str())
                .unwrap_or("event");
            let chapter = event
                .data
                .get("chapter")
                .and_then(|v| v.as_u64())
                .unwrap_or(0);
            ReplayStep {
                fraction: (i + 1) as f32 / total,
                label: format!("{kind} ch{chapter}"),
            }
        })
        .collect()
}

/// Three tray jobs sharing one replay: a chapter build, a draft, a sample.
#[derive(Debug, Clone)]
pub struct TrayJob {
    /// Job label.
    pub label: String,
    /// Lifecycle state shown on the row.
    pub state: String,
}

impl TrayJob {
    /// The tray all three spikes render.
    pub fn tray() -> Vec<Self> {
        vec![
            Self {
                label: "build ch001 (kokoro)".to_owned(),
                state: "running".to_owned(),
            },
            Self {
                label: "draft book.epub".to_owned(),
                state: "queued".to_owned(),
            },
            Self {
                label: "sample af_bella".to_owned(),
                state: "done".to_owned(),
            },
        ]
    }
}
