// SPDX-License-Identifier: AGPL-3.0-or-later
//! Offline client tests: recorded helper responses parse into typed shapes,
//! the loopback guard refuses off-machine origins, the version gate is loud,
//! and the SSE parser handles framing without a helper.

use std::io::Cursor;
use std::path::PathBuf;

use scribe_client::{
    check_compatible, BookDetail, BuildResult, CastPayload, EventStream, Health, Job, PatchResult,
    PlanResult, SampleResult, Settings, UploadResult, ValidateResult, Versions, VoicesResponse,
    SUPPORTED_API_VERSION,
};

fn data(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("data")
        .join(name)
}

fn read_json<T>(name: &str) -> T
where
    T: serde::de::DeserializeOwned,
{
    let text = std::fs::read_to_string(data(name)).expect("fixture readable");
    serde_json::from_str(&text).expect("fixture parses")
}

#[test]
fn recorded_version_pins_contract() {
    let versions: Versions = read_json("version.json");
    assert_eq!(versions.api, SUPPORTED_API_VERSION);
    assert!(!versions.scribe.is_empty());
    assert!(versions.spec.contains(&"2.0".to_owned()));
    check_compatible(&versions).expect("recorded helper is compatible");
}

#[test]
fn foreign_contract_fails_loudly() {
    let versions = Versions {
        scribe: "9.9.9".to_owned(),
        spec: vec!["9.9".to_owned()],
        api: SUPPORTED_API_VERSION + 1,
    };
    let err = check_compatible(&versions).expect_err("must refuse");
    let text = err.to_string();
    assert!(text.contains(&format!("API {}", SUPPORTED_API_VERSION + 1)));
    assert!(text.contains("docs/api.md"));
}

#[test]
fn loopback_guard_refuses_off_machine() {
    for bad in [
        "http://example.com:8137",
        "http://127.0.0.1.evil.com:8137",
        "http://[::1]:8137",
        "https://127.0.0.1:8137",
        "http://user@127.0.0.1:8137",
        "not-a-url",
    ] {
        let err = scribe_client::Client::new(bad, "tok").expect_err("must refuse");
        assert!(
            matches!(
                err,
                scribe_client::Error::NonLoopback(_) | scribe_client::Error::BadUrl(_)
            ),
            "unexpected refusal for {bad}: {err}"
        );
    }
    for good in ["http://127.0.0.1:8137", "http://localhost:9999/"] {
        let client = scribe_client::Client::new(good, "tok").expect("must accept");
        assert!(client.base_url().starts_with("http://"));
    }
}

#[test]
fn recorded_health_books_jobs_settings_parse() {
    let health: Health = read_json("health.json");
    assert!(health.ok);
    let books: serde_json::Value = read_json("books-empty.json");
    assert_eq!(books["books"].as_array().map(Vec::len), Some(0));
    let jobs: serde_json::Value = read_json("jobs-empty.json");
    assert_eq!(jobs["jobs"].as_array().map(Vec::len), Some(0));
    let settings: Settings = read_json("settings.json");
    assert_eq!(settings.device, "auto");
}

#[test]
fn recorded_draft_flow_parses() {
    let upload: UploadResult = read_json("upload.json");
    assert!(!upload.book_id.is_empty());
    assert_eq!(upload.status, "drafting");
    assert!(!upload.job_id.is_empty());
    let draft: Job = read_json("draft-job.json");
    assert_eq!(draft.state, "done");
    assert_eq!(draft.kind, "draft");
    assert_eq!(draft.id, upload.job_id);
    let detail: BookDetail = read_json("book-detail.json");
    assert_eq!(detail.id, upload.book_id);
    assert!(!detail.chapters.is_empty());
    let plan: PlanResult = read_json("plan.json");
    assert_eq!(plan.selected_chapters, vec![1]);
    assert!(plan.is_range, "chapters=1 on a 2-chapter book is a range");
    assert!(plan.to_render_sentences > 0);
}

#[test]
fn recorded_build_and_validate_parse() {
    let queued: BuildResult = read_json("build-queued.json");
    assert!(!queued.job_id.is_empty());
    let build: Job = read_json("build-job.json");
    assert_eq!(build.state, "done");
    assert_eq!(build.kind, "build");
    assert!(build.summary.audio_ms > 0);
    let report: ValidateResult = read_json("validate.json");
    assert!(report.valid);
    assert!(!report.bundles.is_empty());
}

#[test]
fn recorded_pause_resume_shapes_parse() {
    // The tray's Pause/Resume buttons rely on these response shapes (a Job
    // in its new state); pin the contract the worker arms parse.
    let paused: Job = read_json("job-paused.json");
    assert_eq!(paused.state, "paused");
    assert_eq!(paused.kind, "build");
    assert!(paused.pid.is_none(), "paused jobs hold no child");
    let resumed: Job = read_json("job-resumed.json");
    assert_eq!(resumed.state, "running");
    assert_eq!(resumed.book_id, paused.book_id);
    assert!(resumed.summary.audio_ms > 0);
}

#[test]
fn recorded_voices_parse() {
    let voices: VoicesResponse = read_json("voices-kokoro.json");
    assert!(!voices.voices.is_empty());
    assert_eq!(voices.engine, "kokoro");
    assert!(!voices.text_hash.is_empty());
    let initial: SampleResult = read_json("sample-initial.json");
    assert!(!initial.voices.is_empty());
    if initial.cached {
        assert!(initial.job_id.is_none());
    } else {
        assert!(initial.job_id.is_some());
    }
}

#[test]
fn recorded_errors_carry_shapes() {
    let denied: serde_json::Value = read_json("error-403.json");
    assert_eq!(denied["status"], 403);
    assert_eq!(denied["body"]["detail"], "forbidden");
    let missing: serde_json::Value = read_json("error-404-book.json");
    assert_eq!(missing["status"], 404);
    let bad_id: serde_json::Value = read_json("error-400-job.json");
    assert_eq!(bad_id["status"], 400);
    assert_eq!(bad_id["body"]["rule"], "bad-job-id");
}

#[test]
fn recorded_cast_parses() {
    let cast: CastPayload = read_json("cast.json");
    assert!(!cast.book_id.is_empty());
    assert!(cast.cast.is_object());
    // CS1: the table renders from these (dropped by the old struct).
    assert_eq!(cast.first_person, "narrator");
    assert_eq!(cast.stats.len(), 3);
    assert_eq!(cast.stats[0].character, "narrator");
    assert_eq!(cast.stats[0].kind, "narrator");
    assert_eq!(cast.stats[0].lines, 50);
    assert!(cast.minor.is_empty());
}

#[test]
fn recorded_patch_result_parses() {
    // Derived from cast.json plus the PATCH-only keys (same commit that
    // adds patch_cast; the helper echoes the payload with affected/applied).
    let patch: PatchResult = read_json("patch-result.json");
    assert!(!patch.cast.book_id.is_empty());
    assert_eq!(patch.applied, 1);
    assert_eq!(patch.cast.affected.map(|found| found.quotes), Some(5));
    assert_eq!(patch.cast.stats.len(), 3);
}

#[test]
fn sse_sample_parses_with_increasing_ids() {
    let text = std::fs::read_to_string(data("events-sample.txt")).expect("sse sample");
    assert!(text.contains("data:"));
    let mut stream = EventStream::new(Cursor::new(text));
    let mut last_id = 0;
    let mut count = 0;
    for event in &mut stream {
        let event = event.expect("event parses");
        assert!(event.id > last_id, "ids increase");
        assert!(event.data.is_object());
        last_id = event.id;
        count += 1;
    }
    assert!(count > 0, "sample holds at least one event");
}

#[test]
fn sse_parser_skips_heartbeats_and_fails_loudly() {
    let text = ": ping\n\nid: 4\ndata: {\"event\":\"x\"}\n\n: ping\n\nid: oops\ndata: {}\n\n";
    let mut stream = EventStream::new(Cursor::new(text));
    let first = stream.next_event().expect("one event").expect("parses");
    assert_eq!(first.id, 4);
    let bad = stream.next_event().expect("second item");
    assert!(bad.is_err(), "bad id fails loudly");
}

#[test]
fn sse_data_without_id_is_an_error() {
    let mut stream = EventStream::new(Cursor::new("data: {\"event\":\"x\"}\n\n"));
    let item = stream.next_event().expect("one item");
    assert!(item.is_err());
}
