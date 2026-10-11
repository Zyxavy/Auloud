// SPDX-License-Identifier: AGPL-3.0-or-later
//! Typed helper responses (docs/api.md). Unknown JSON fields are ignored so
//! additive helper changes keep parsing; every field the UI reads is named.

use serde::Deserialize;

use crate::error::ApiError;

/// `GET /api/version` and the ready-line `versions` object.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Versions {
    /// Scribe package version (for example `"0.1.0"`).
    #[serde(default)]
    pub scribe: String,
    /// Spec versions the helper reads and writes.
    #[serde(default)]
    pub spec: Vec<String>,
    /// Contract integer this client pins ([`crate::SUPPORTED_API_VERSION`]).
    pub api: u32,
}

/// `GET /api/health`.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Health {
    /// Always true when the helper serves.
    #[serde(default)]
    pub ok: bool,
}

/// One row of `GET /api/books`.
#[derive(Debug, Clone, Deserialize)]
pub struct BookSummary {
    /// UUIDv5 hex of the source bytes.
    #[serde(default)]
    pub id: String,
    /// Book title.
    #[serde(default)]
    pub title: String,
    /// Book author.
    #[serde(default)]
    pub author: String,
    /// Workspace-relative source path.
    #[serde(default)]
    pub source_file: String,
    /// `epub` or `pdf`.
    #[serde(default)]
    pub source_type: String,
    /// Chapter and sentence counts.
    #[serde(default)]
    pub chapters: u64,
    /// Chapter and sentence counts.
    #[serde(default)]
    pub sentences: u64,
    /// Draft artifacts present.
    #[serde(default)]
    pub drafted: bool,
    /// `cast.yaml` newer than `script.json`.
    #[serde(default)]
    pub cast_edited: bool,
    /// A build/draft job currently covers this book.
    #[serde(default)]
    pub rendering: bool,
    /// 0-100, null when nothing renders.
    #[serde(default)]
    pub rendering_percent: Option<u32>,
    /// Scan state: `drafting | done | failed | cancelled | none`.
    #[serde(default)]
    pub draft_status: String,
    /// Rendered bundle count.
    #[serde(default)]
    pub bundles: u64,
    /// At least one valid bundle exists.
    #[serde(default)]
    pub valid: bool,
    /// A verified transfer record points at current bundles.
    #[serde(default)]
    pub on_tablet: bool,
}

/// One row of `GET /api/books/{id}` chapter lists.
#[derive(Debug, Clone, Deserialize)]
pub struct ChapterEntry {
    /// 1-based chapter index.
    #[serde(default)]
    pub index: u64,
    /// Chapter title.
    #[serde(default)]
    pub title: String,
    /// Sentence count.
    #[serde(default)]
    pub sentences: u64,
    /// Estimated seconds.
    #[serde(default)]
    pub est_seconds: f64,
}

/// One row of bundle lists (detail and validate answers).
#[derive(Debug, Clone, Deserialize)]
pub struct BundleEntry {
    /// Manifest bundle id.
    #[serde(default)]
    pub id: String,
    /// Workspace-relative bundle path.
    #[serde(default)]
    pub path: String,
    /// Chapter count.
    #[serde(default)]
    pub chapters: u64,
    /// Total duration in milliseconds.
    #[serde(default)]
    pub duration_ms: u64,
    /// The bundle validates.
    #[serde(default)]
    pub valid: bool,
    /// Validation errors (empty when valid).
    #[serde(default)]
    pub errors: Vec<ApiError>,
}

/// `GET /api/books/{book_id}` (cast summary stays a document; Slice 19 owns
/// its shape, so the client carries it without pretending to understand it).
#[derive(Debug, Clone, Deserialize)]
pub struct BookDetail {
    /// Book identity.
    #[serde(default)]
    pub id: String,
    /// Book identity.
    #[serde(default)]
    pub title: String,
    /// Workspace-relative source path.
    #[serde(default)]
    pub source_file: String,
    /// Hex sha256 of the source bytes.
    #[serde(default)]
    pub source_sha256: String,
    /// Draft artifacts present.
    #[serde(default)]
    pub drafted: bool,
    /// Scan state (`drafting | done | failed | cancelled | none`).
    #[serde(default)]
    pub draft_status: String,
    /// Chapter rows.
    #[serde(default)]
    pub chapters: Vec<ChapterEntry>,
    /// Sentence total.
    #[serde(default)]
    pub total_sentences: u64,
    /// Opaque cast document (see above).
    #[serde(default)]
    pub cast: serde_json::Value,
    /// Rendered bundles.
    #[serde(default)]
    pub bundles: Vec<BundleEntry>,
    /// The book validates.
    #[serde(default)]
    pub valid: bool,
    /// Validation findings.
    #[serde(default)]
    pub validation: Vec<ApiError>,
    /// A verified transfer record points at current bundles.
    #[serde(default)]
    pub on_tablet: bool,
}

/// `POST /api/books/upload` answer.
#[derive(Debug, Clone, Deserialize)]
pub struct UploadResult {
    /// UUIDv5 hex of the source bytes.
    #[serde(default)]
    pub book_id: String,
    /// Stored filename.
    #[serde(default)]
    pub filename: String,
    /// Always `"drafting"` (a draft job was queued).
    #[serde(default)]
    pub status: String,
    /// The queued draft job.
    #[serde(default)]
    pub job_id: String,
}

/// `POST /api/books/{book_id}/build` answer.
#[derive(Debug, Clone, Deserialize)]
pub struct BuildResult {
    /// The queued build job.
    #[serde(default)]
    pub job_id: String,
    /// Echo of the book.
    #[serde(default)]
    pub book_id: String,
    /// Initial job state.
    #[serde(default)]
    pub state: String,
}

/// `POST /api/books/{book_id}/plan` answer.
#[derive(Debug, Clone, Deserialize)]
pub struct PlanResult {
    /// Echo of the book.
    #[serde(default)]
    pub book_id: String,
    /// Book title.
    #[serde(default)]
    pub title: String,
    /// Chapters the selection resolved to.
    #[serde(default)]
    pub selected_chapters: Vec<u64>,
    /// Chapter/sentence counts (cached vs needing render).
    #[serde(default)]
    pub cached_chapters: u64,
    /// Chapter/sentence counts (cached vs needing render).
    #[serde(default)]
    pub to_render_chapters: u64,
    /// Chapter/sentence counts (cached vs needing render).
    #[serde(default)]
    pub cached_sentences: u64,
    /// Chapter/sentence counts (cached vs needing render).
    #[serde(default)]
    pub to_render_sentences: u64,
    /// Estimated seconds for the render (null without RTF history).
    #[serde(default)]
    pub estimated_seconds: Option<f64>,
    /// Real-time factor the estimate used (null when no history).
    #[serde(default)]
    pub rtf_used: Option<f64>,
    /// Page-range resolution note (empty unless pages were given).
    #[serde(default)]
    pub page_resolution: String,
    /// True for chapter-range bundles.
    #[serde(default)]
    pub is_range: bool,
    /// Human-readable plan text.
    #[serde(default)]
    pub message: String,
}

/// `summary` inside every job response.
#[derive(Debug, Clone, Default, Deserialize)]
pub struct JobSummary {
    /// Sentences served from cache.
    #[serde(default)]
    pub cached: u64,
    /// Sentences rendered this run.
    #[serde(default)]
    pub rendered: u64,
    /// Audio milliseconds produced.
    #[serde(default)]
    pub audio_ms: u64,
    /// Wall seconds spent.
    #[serde(default)]
    pub wall_s: f64,
}

/// The public job shape behind every jobs route.
#[derive(Debug, Clone, Deserialize)]
pub struct Job {
    /// Job id (`job-` plus hex).
    #[serde(default)]
    pub id: String,
    /// Owning book (empty for voices jobs).
    #[serde(default)]
    pub book_id: String,
    /// `build | draft | voices-sample | transfer`.
    #[serde(default)]
    pub kind: String,
    /// `queued | running | paused | done | failed | cancelled | interrupted`.
    #[serde(default)]
    pub state: String,
    /// Child pid (null when not running).
    #[serde(default)]
    pub pid: Option<u32>,
    /// Child exit code (null until reaped).
    #[serde(default)]
    pub exit: Option<i32>,
    /// Progress counters.
    #[serde(default)]
    pub summary: JobSummary,
    /// Terminal failure, if any.
    #[serde(default)]
    pub error: Option<ApiError>,
    /// Chapter range the job renders, if any.
    #[serde(default)]
    pub chapters: Option<String>,
    /// Device the job renders on, if any.
    #[serde(default)]
    pub device: Option<String>,
    /// Voices the sample job covers, if any.
    #[serde(default)]
    pub voices: Option<Vec<String>>,
    /// Engine the sample job uses, if any.
    #[serde(default)]
    pub engine: Option<String>,
}

/// `GET /api/settings` and `PUT /api/settings` answers.
#[derive(Debug, Clone, Deserialize)]
pub struct Settings {
    /// Keep Windows awake while a job runs.
    #[serde(default = "default_keep_awake")]
    pub keep_awake: bool,
    /// `auto | cpu | cuda`.
    #[serde(default = "default_device")]
    pub device: String,
}

fn default_keep_awake() -> bool {
    true
}

fn default_device() -> String {
    "auto".to_owned()
}

/// One row of `details` in `GET /api/cast/voices`.
#[derive(Debug, Clone, Deserialize)]
pub struct VoiceDetail {
    /// Voice id.
    #[serde(default)]
    pub name: String,
    /// BCP-47-ish locale or `"unknown"`.
    #[serde(default)]
    pub locale: String,
    /// Lowercase gender or `"unknown"`.
    #[serde(default)]
    pub gender: String,
}

/// `GET /api/cast/voices` answer.
#[derive(Debug, Clone, Deserialize)]
pub struct VoicesResponse {
    /// Voice ids.
    #[serde(default)]
    pub voices: Vec<String>,
    /// `"engine"` (read live) or `"palette"` (D-036 fallback).
    #[serde(default)]
    pub source: String,
    /// Echo of the engine.
    #[serde(default)]
    pub engine: String,
    /// Package version (model never loaded).
    #[serde(default)]
    pub engine_version: String,
    /// 12-hex hash of the audition sentence.
    #[serde(default)]
    pub text_hash: String,
    /// Per-voice locale/gender rows.
    #[serde(default)]
    pub details: Vec<VoiceDetail>,
}

/// `POST /api/voices/sample` answer: cached clips come back inline,
/// otherwise a job id to poll (`coalesced` reuses an active superset job).
#[derive(Debug, Clone, Deserialize)]
pub struct SampleResult {
    /// Every clip served from cache.
    #[serde(default)]
    pub cached: bool,
    /// The render job, when `cached` is false.
    #[serde(default)]
    pub job_id: Option<String>,
    /// Voices covered.
    #[serde(default)]
    pub voices: Vec<String>,
    /// Echo of the engine.
    #[serde(default)]
    pub engine: String,
    /// True when an active superset job was reused.
    #[serde(default)]
    pub coalesced: bool,
}

/// `GET /api/books/{book_id}/cast` answer (the `cast` document itself stays
/// opaque; see [`BookDetail::cast`]).
#[derive(Debug, Clone, Deserialize)]
pub struct CastPayload {
    /// Echo of the book.
    #[serde(default)]
    pub book_id: String,
    /// Conflict-guard mtime from GET (echo back on PUT).
    #[serde(default)]
    pub mtime: f64,
    /// The file carries `#` comments a save would drop.
    #[serde(default)]
    pub has_comments: bool,
    /// Opaque cast document.
    #[serde(default)]
    pub cast: serde_json::Value,
    /// Quotes re-voiced by the last save.
    #[serde(default)]
    pub affected: Option<AffectedQuotes>,
}

/// `affected` inside cast save answers.
#[derive(Debug, Clone, Deserialize)]
pub struct AffectedQuotes {
    /// Quotes needing re-render.
    #[serde(default)]
    pub quotes: u64,
}

/// `POST /api/books/{book_id}/validate` answer.
#[derive(Debug, Clone, Deserialize)]
pub struct ValidateResult {
    /// Echo of the book.
    #[serde(default)]
    pub book_id: String,
    /// The book validates.
    #[serde(default)]
    pub valid: bool,
    /// Validation findings.
    #[serde(default)]
    pub validation: Vec<ApiError>,
    /// Per-bundle rows.
    #[serde(default)]
    pub bundles: Vec<BundleEntry>,
}
