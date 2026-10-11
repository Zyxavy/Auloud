// SPDX-License-Identifier: AGPL-3.0-or-later
//! Crate errors: shaped helper failures, transport problems, version refusal.

use thiserror::Error;

/// Shaped helper error body `{file, rule, message}` (docs/api.md section 2).
#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize)]
pub struct ApiError {
    /// File or area the error belongs to (for example `"cast.yaml"`).
    #[serde(default)]
    pub file: String,
    /// Machine-readable rule (for example `"conflict"`, `"work-lock-held"`).
    #[serde(default)]
    pub rule: String,
    /// Human message, usually repeating `file: rule: detail`.
    #[serde(default)]
    pub message: String,
}

impl std::fmt::Display for ApiError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}: {}: {}", self.file, self.rule, self.message)
    }
}

/// Every failure a [`crate::Client`] call can return.
#[derive(Debug, Error)]
#[non_exhaustive]
pub enum Error {
    /// The base URL is malformed (need `http://127.0.0.1:<port>` or
    /// `http://localhost:<port>`).
    #[error("bad helper url {0:?}: need http://127.0.0.1:<port> or http://localhost:<port>")]
    BadUrl(String),
    /// The base URL points off this machine. The helper only ever serves
    /// loopback; anything else is refused before any byte is sent.
    #[error("refusing non-loopback helper url {0:?}")]
    NonLoopback(String),
    /// The request never got a usable response (connection refused, reset,
    /// timeout, broken framing).
    #[error("transport: {0}")]
    Transport(String),
    /// The helper answered with a non-2xx status; the shaped body rides along.
    #[error("helper returned HTTP {status}: {error}")]
    Http {
        /// Raw HTTP status code.
        status: u16,
        /// Shaped `{file, rule, message}` body (synthesized when unshaped).
        error: ApiError,
    },
    /// The helper speaks a contract this client does not know. Upgrade one
    /// side so both agree (docs/api.md section 8); never degrade silently.
    #[error(
        "helper speaks API {have}, this client speaks {want}: upgrade scribe-client or the helper so both agree (docs/api.md)"
    )]
    VersionMismatch {
        /// `api` integer the helper reported.
        have: u32,
        /// `api` integer this client speaks.
        want: u32,
    },
    /// A 2xx body was not the JSON this client parses.
    #[error("bad JSON from helper: {0}")]
    Json(String),
    /// Local file read failed (upload path, fixture files).
    #[error("io: {0}")]
    Io(String),
}

impl From<ureq::Error> for Error {
    fn from(value: ureq::Error) -> Self {
        Self::Transport(value.to_string())
    }
}

impl From<std::io::Error> for Error {
    fn from(value: std::io::Error) -> Self {
        Self::Io(value.to_string())
    }
}

impl From<serde_json::Error> for Error {
    fn from(value: serde_json::Error) -> Self {
        Self::Json(value.to_string())
    }
}
