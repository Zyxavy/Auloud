// SPDX-License-Identifier: AGPL-3.0-or-later
//! Live helper test (API2 verify: the client talks to a real helper).
//!
//! Ignored by default: needs models, a TTS-capable machine, and minutes.
//! Run explicitly with the helper under test:
//!
//! ```powershell
//! $env:AULOUD_TEST_HELPER = "D:\...\helper-win64\python\python.exe"
//! $env:AULOUD_TEST_MODELS = "D:\...\scribe\models"
//! cargo test -p scribe-client --test live_helper -- --ignored --nocapture
//! ```
//!
//! `AULOUD_TEST_HELPER` is the helper exe, or a `python[.exe]` (then
//! `-m scribe` is prepended and the book runs from the working tree).
//! `AULOUD_TEST_BOOK` overrides the default tiny fixture book.

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use scribe_client::{Client, Job, Selection};

fn crate_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn helper_command(workspace: &std::path::Path, token: &str) -> Command {
    let exe = std::env::var("AULOUD_TEST_HELPER")
        .expect("live test needs AULOUD_TEST_HELPER (helper exe, or a python for -m scribe)");
    let mut command = if PathBuf::from(&exe)
        .file_stem()
        .is_some_and(|stem| stem.eq_ignore_ascii_case("python"))
    {
        let mut command = Command::new(exe);
        command.arg("-m").arg("scribe");
        command
    } else {
        Command::new(exe)
    };
    command
        .arg("helper")
        .arg("--workspace")
        .arg(workspace)
        .arg("--port")
        .arg("0")
        .env("AULOUD_HELPER_TOKEN", token)
        // Working tree, never the stale installed copy (same trick as
        // scribe/dev/record_client_fixtures.py): the repo root on
        // PYTHONPATH for `python -m scribe`, cwd in scribe/ so relative
        // `models` resolves to the dev models.
        .env(
            "PYTHONPATH",
            crate_dir().join("../../").to_string_lossy().to_string(),
        )
        .current_dir(crate_dir().join("../../scribe"))
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    command
}

fn wait_ready(child: &mut Child, timeout: Duration) -> String {
    let stdout = child.stdout.take().expect("piped stdout");
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if sender.send(line).is_err() {
                break;
            }
        }
    });
    let start = Instant::now();
    loop {
        if let Some(status) = child.try_wait().expect("poll child") {
            panic!("helper exited early: {status}");
        }
        match receiver.recv_timeout(Duration::from_millis(200)) {
            Ok(line) => {
                if let Ok(payload) = serde_json::from_str::<serde_json::Value>(&line) {
                    if payload.get("ready") == Some(&serde_json::Value::Bool(true)) {
                        let port = payload["port"].as_u64().expect("ready port");
                        return format!("http://127.0.0.1:{port}");
                    }
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                assert!(start.elapsed() < timeout, "no ready line in {timeout:?}");
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                panic!("helper stdout closed without a ready line");
            }
        }
    }
}

fn poll_job(client: &Client, job_id: &str, timeout: Duration) -> Job {
    let start = Instant::now();
    loop {
        let job = client.job(job_id).expect("job readable");
        match job.state.as_str() {
            "done" => return job,
            "failed" | "cancelled" => panic!("job ended {}: {:?}", job.state, job.error),
            _ => {
                assert!(start.elapsed() < timeout, "job {job_id} never terminal");
                std::thread::sleep(Duration::from_secs(5));
            }
        }
    }
}

/// Kills the helper on drop so a panicking assertion cannot leak it.
struct ChildGuard(Child);

impl Drop for ChildGuard {
    fn drop(&mut self) {
        self.0.kill().ok();
        self.0.wait().ok();
    }
}

#[test]
#[ignore = "needs a real helper, models, and minutes (API2 live proof)"]
fn live_helper_end_to_end() {
    // Unique per run: two sequential runs share a process id each only
    // within one harness invocation, never across them, so stamp it.
    let workspace = std::env::temp_dir().join(format!(
        "client-live-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock")
            .as_millis()
    ));
    std::fs::create_dir_all(&workspace).expect("workspace");
    let token = "live-test-token";
    let mut child = ChildGuard(
        helper_command(&workspace, token)
            .spawn()
            .expect("spawn helper"),
    );
    let base = wait_ready(&mut child.0, Duration::from_secs(120));
    eprintln!("live helper: {base}");
    let client = Client::new(&base, token).expect("bind");
    client.ensure_compatible().expect("contract pins");
    assert!(client.health().expect("health").ok);

    let book = std::env::var("AULOUD_TEST_BOOK")
        .map(PathBuf::from)
        .unwrap_or_else(|_| crate_dir().join("../../spec/fixtures/epub2-minimal/source/book.epub"));
    let upload = client.upload_book(&book).expect("upload");
    assert_eq!(upload.status, "drafting");
    eprintln!("live: uploaded {}", upload.book_id);
    let draft = poll_job(&client, &upload.job_id, Duration::from_secs(300));
    assert_eq!(draft.kind, "draft");
    eprintln!("live: draft done");

    let selection = Selection {
        chapters: Some("1".to_owned()),
        pages: None,
        device: Some("cpu".to_owned()),
    };
    let plan = client
        .plan_build(&upload.book_id, &selection)
        .expect("plan");
    assert!(!plan.selected_chapters.is_empty());
    let build = client
        .queue_build(&upload.book_id, &selection)
        .expect("queue build");
    let finished = poll_job(&client, &build.job_id, Duration::from_secs(900));
    assert_eq!(finished.kind, "build");
    assert!(finished.summary.audio_ms > 0);
    eprintln!("live: build done ({} ms)", finished.summary.audio_ms);

    let events: Vec<_> = client
        .events(&finished.id, 0)
        .expect("open events")
        .collect();
    assert!(!events.is_empty(), "stream yields events");
    for event in &events {
        event.as_ref().expect("event parses");
    }

    let report = client.validate(&upload.book_id).expect("validate");
    assert!(report.valid, "bundle validates: {:?}", report.validation);
    eprintln!("live: validate ok");
}
