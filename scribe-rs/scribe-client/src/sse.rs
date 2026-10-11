// SPDX-License-Identifier: AGPL-3.0-or-later
//! SSE event stream over `GET /api/jobs/{id}/events` (docs/api.md section 4).
//!
//! The parser reads any buffered line source, so tests feed it canned text
//! with no helper. Server rules it mirrors: `: ping` comments and blank
//! lines are skipped, each event is an `id: <n>` line plus a `data: <json>`
//! line, and the stream ends when the server closes (job terminal).

use std::io::{BufRead, BufReader};

use serde_json::Value;

use crate::error::Error;

/// One parsed SSE event: the 1-based events.jsonl line number plus its body.
#[derive(Debug, Clone)]
pub struct Event {
    /// 1-based line index in the job's `events.jsonl`.
    pub id: u64,
    /// Event body (`{event, chapter, sid, cached, rendered, audio_ms,
    /// wall_s}` plus phase markers).
    pub data: Value,
    /// Raw `data:` line, kept for debugging.
    pub raw: String,
}

/// Blocking event iterator; ends when the server closes the stream.
pub struct EventStream<R: std::io::Read> {
    lines: std::io::Lines<BufReader<R>>,
    pending_id: Option<u64>,
}

impl<R: std::io::Read> EventStream<R> {
    /// Wrap a streaming response body (or canned text in tests).
    pub fn new(reader: R) -> Self {
        Self {
            lines: BufReader::new(reader).lines(),
            pending_id: None,
        }
    }

    /// Next event, skipping heartbeats, comments, and blank lines. A `data:`
    /// line without a preceding `id:` line is a protocol error; invalid JSON
    /// fails loudly instead of dropping an event silently.
    ///
    /// # Errors
    ///
    /// Returns [`Error::Json`] on malformed lines and [`Error::Io`] when the
    /// stream breaks mid-event.
    pub fn next_event(&mut self) -> Option<Result<Event, Error>> {
        for line in self.lines.by_ref() {
            let line = match line {
                Ok(line) => line,
                Err(err) => return Some(Err(Error::Io(err.to_string()))),
            };
            let text = line.trim();
            if text.is_empty() || text.starts_with(':') {
                continue;
            }
            if let Some(id) = text.strip_prefix("id:") {
                match id.trim().parse::<u64>() {
                    Ok(id) => self.pending_id = Some(id),
                    Err(_) => {
                        return Some(Err(Error::Json(format!("bad event id: {text:?}"))));
                    }
                }
                continue;
            }
            if let Some(data) = text.strip_prefix("data:") {
                let Some(id) = self.pending_id.take() else {
                    return Some(Err(Error::Json(format!("data without id: {text:?}"))));
                };
                let raw = data.trim().to_owned();
                match serde_json::from_str::<Value>(&raw) {
                    Ok(value) => {
                        return Some(Ok(Event {
                            id,
                            data: value,
                            raw,
                        }))
                    }
                    Err(err) => return Some(Err(Error::Json(err.to_string()))),
                }
            }
            // Unknown field lines are ignored (forward-compatible framing).
        }
        None
    }
}

impl<R: std::io::Read> Iterator for EventStream<R> {
    type Item = Result<Event, Error>;

    fn next(&mut self) -> Option<Self::Item> {
        self.next_event()
    }
}
