//! A full run through the real orchestrator binary and the fake worker must exit 0.
use std::path::PathBuf;
use std::process::Command;

fn fresh_dir(tag: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("scribe-orch-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).expect("create temp dir");
    dir
}

#[test]
fn full_happy_run_exits_zero() {
    let dir = fresh_dir("happy");
    // The orchestrator only checks that worker.py exists; the fake ignores its contents.
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
        out.status.success(),
        "exit {:?}\nstdout:\n{stdout}\nstderr:\n{stderr}",
        out.status.code()
    );
    assert!(
        stdout.contains(r#""state":"done""#),
        "no done state:\n{stdout}"
    );
    assert!(
        dir.join("results.json").is_file(),
        "results.json not written"
    );
}
