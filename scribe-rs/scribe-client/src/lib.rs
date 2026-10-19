// SPDX-License-Identifier: AGPL-3.0-or-later
//! Typed client for the Auloud helper API (docs/api.md, contract v1).
//!
//! Blocking calls over plain HTTP: the helper only serves loopback, so no
//! TLS and no async runtime. Unknown helper versions fail loudly via
//! [`Client::ensure_compatible`]; the UI never talks to a contract it does
//! not know. No Scribe logic lives here: the crate shapes requests and
//! parses responses, nothing more.

mod client;
mod error;
mod sse;
mod types;

pub use client::{
    cast_op_add_override_match, cast_op_add_override_quote, cast_op_remove_override_quote,
    cast_op_set_first_person, cast_op_set_speed, cast_op_set_voice, check_compatible, Client,
    SampleRequest, Selection, SUPPORTED_API_VERSION, TOKEN_HEADER,
};
pub use error::{ApiError, Error};
pub use sse::{Event, EventStream};
pub use types::{
    AffectedQuotes, BookDetail, BookSummary, BuildResult, BundleEntry, CastPayload, CastReport,
    CastStat, ChapterEntry, DeleteResult, Health, Job, JobSummary, MinorEntry, PatchResult,
    PlanResult, QuoteEntry, QuotesPage, SampleResult, Settings, UploadResult, ValidateResult,
    Versions, VoiceDetail, VoicesResponse,
};
