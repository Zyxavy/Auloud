//! A worker that dies mid-batch must fail the whole run: non-zero exit and an error event on stdout.
use std::path::PathBuf;
use std::process::Command;

fn fresh_dir(tag: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("scribe-orch-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("create temp dir");
    dir
}

#[test]
fn worker_exit_mid_batch_fails_the_run() {
    let dir = fresh_dir("exit-mid-batch");
    // The fake reads its mode from FAKE_WORKER_MODE and exits after the first synth_batch.
    std::fs::write(dir.join("worker.py"), "").expect("write worker.py");
    let manifest = dir.join("build_manifest.json");
    let body = serde_json::json!({
        "v": 1,
        "book_id": "book",
        "models_dir": "models",
        "device": "cpu",
        "cache_dir": "cache",
        "workers": 2,
        "batch_size": 32,
        "chapters": [{
            "index": 1,
            "title": "One",
            "sentences": [{"sid": 0, "text": "Hi.", "voice": "am_adam", "speed": 1.0}]
        }]
    });
    std::fs::write(&manifest, body.to_string()).expect("write manifest");

    let out = Command::new(env!("CARGO_BIN_EXE_scribe-orchestrator"))
        .env("FAKE_WORKER_MODE", "exit_mid_batch")
        .arg("--manifest")
        .arg(&manifest)
        .arg("--python")
        .arg(env!("CARGO_BIN_EXE_fake-worker"))
        .arg("--worker-dir")
        .arg(&dir)
        .output()
        .expect("run orchestrator");

    let stdout = String::from_utf8_lossy(&out.stdout);
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(
        !out.status.success(),
        "a worker that exits mid-batch must fail the run\nstdout:\n{stdout}\nstderr:\n{stderr}"
    );
    assert!(
        stdout
            .lines()
            .any(|line| line.contains(r#""type":"error""#) && line.contains(r#""kind":"worker""#)),
        "no worker error event on stdout:\n{stdout}"
    );
    assert!(
        !stdout.contains(r#""state":"done""#),
        "a failed run must not report done:\n{stdout}"
    );
    assert!(!dir.join("results.json").exists(), "a failed run must not write results");
}
