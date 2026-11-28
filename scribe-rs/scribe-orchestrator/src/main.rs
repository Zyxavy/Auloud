use clap::Parser;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::str::FromStr;
use std::sync::atomic::AtomicBool;
use std::sync::mpsc::{self, Receiver};
use std::sync::Arc;
use tracing_subscriber::EnvFilter;

use scribe_orchestrator::protocol::*;
use scribe_orchestrator::supervisor::Supervisor;
use scribe_orchestrator::{
    encode_frame, next_frame, run_worker, spawn_frame_reader, Frame, WorkerLink, BATCH_TIMEOUT,
    STARTUP_TIMEOUT,
};

#[derive(Parser, Debug)]
#[command(name = "scribe-orchestrator", version)]
struct Args {
    #[arg(long)]
    manifest: String,
    #[arg(long, default_value_t = 2)]
    workers: usize,
    #[arg(long, default_value_t = 32)]
    batch_size: usize,
    #[arg(long, default_value = "python")]
    python: String,
    /// Folder holding worker.py (the scribe package root).
    #[arg(long, default_value = ".")]
    worker_dir: String,
    #[arg(long, default_value = "info")]
    log_level: String,
    #[arg(long)]
    results: Option<String>,
}

#[derive(Deserialize)]
struct Manifest {
    v: u32,
    book_id: String,
    models_dir: String,
    device: String,
    cache_dir: String,
    workers: usize,
    batch_size: usize,
    chapters: Vec<ManifestChapter>,
}

#[derive(Deserialize, Clone)]
struct ManifestChapter {
    index: usize,
    title: String,
    sentences: Vec<ManifestSentence>,
}

#[derive(Deserialize, Clone)]
struct ManifestSentence {
    sid: usize,
    text: String,
    voice: String,
    speed: f32,
}

#[derive(Serialize)]
struct ResultsFile {
    book_id: String,
    chapters: Vec<ChapterResult>,
}

#[derive(Serialize)]
struct ChapterResult {
    index: usize,
    title: String,
    results: Vec<ResultItem>,
}

fn emit_json(value: serde_json::Value) {
    println!("{value}");
    let _ = std::io::stdout().flush();
}

fn emit_progress(done: u64, total: u64, rtf: f64, eta_s: f64, cache_hit_rate: f64) {
    emit_json(serde_json::json!({
        "type": "progress",
        "done": done,
        "total": total,
        "rtf": rtf,
        "eta_s": eta_s,
        "cache_hit_rate": cache_hit_rate,
    }));
}

fn emit_state(state: &str) {
    emit_json(serde_json::json!({"type": "state", "state": state}));
}

fn emit_error(kind: &str, message: &str) {
    emit_json(serde_json::json!({"type": "error", "kind": kind, "message": message}));
}

fn fail(kind: &str, message: &str) -> ! {
    emit_error(kind, message);
    std::process::exit(1);
}

fn main() {
    let args = Args::parse();
    let filter = EnvFilter::from_str(&args.log_level).unwrap_or_else(|_| EnvFilter::new("info"));
    // stdout carries the JSON protocol; tracing must never write there.
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .with_env_filter(filter)
        .init();

    let manifest_str = match std::fs::read_to_string(&args.manifest) {
        Ok(s) => s,
        Err(e) => fail("manifest_read", &format!("failed to read manifest: {e}")),
    };
    let manifest: Manifest = match serde_json::from_str(&manifest_str) {
        Ok(m) => m,
        Err(e) => fail("manifest_parse", &format!("failed to parse manifest: {e}")),
    };
    if manifest.v != 1 {
        fail("protocol_version", &format!("unsupported manifest version: {}", manifest.v));
    }

    // Joins the Job Object before any worker is spawned; dropping it kills them.
    let _supervisor = match Supervisor::new() {
        Ok(s) => s,
        Err(e) => fail("supervisor", &format!("failed to create supervisor: {e}")),
    };

    // One worker takes one chunk at a time; never fewer than one.
    let worker_count = args.workers.min(manifest.workers).max(1);
    let batch_size = args.batch_size.min(manifest.batch_size).max(1);

    let mut children = spawn_workers(&args, &manifest, worker_count);
    let io = handshake(&mut children, worker_count);

    emit_state("running");

    // Every batch up front, in document order, then dealt out to the workers
    // round-robin. Each worker thread sends and reads its own share, so N
    // workers synthesise at the same time instead of one after another.
    let mut batches: Vec<(usize, String, Vec<BatchItem>)> = Vec::new();
    for chapter in &manifest.chapters {
        for (n, chunk) in chapter.sentences.chunks(batch_size).enumerate() {
            batches.push((
                chapter.index,
                format!("ch{}-b{}", chapter.index, n),
                chunk
                    .iter()
                    .map(|s| BatchItem {
                        index: s.sid,
                        text: s.text.clone(),
                        voice: s.voice.clone(),
                        speed: s.speed,
                    })
                    .collect(),
            ));
        }
    }
    let total_sentences: u64 = manifest.chapters.iter().map(|c| c.sentences.len() as u64).sum();

    let (tx, rx) = mpsc::channel::<(u64, u64, u64)>(); // done, audio_ms, hits
    // Set by the first failing worker; the others stop before their next batch.
    let stop = Arc::new(AtomicBool::new(false));
    let mut threads = Vec::new();
    for (widx, ((stdin, frames), mut child)) in io.into_iter().zip(children).enumerate() {
        let mine: Vec<(usize, String, Vec<BatchItem>)> = batches
            .iter()
            .skip(widx)
            .step_by(worker_count)
            .cloned()
            .collect();
        let tx = tx.clone();
        let stop = Arc::clone(&stop);
        threads.push(std::thread::spawn(move || {
            let link = WorkerLink { index: widx, stdin, frames, timeout: BATCH_TIMEOUT };
            let result = run_worker(link, mine, &stop, &tx);
            // A failed or hung worker is killed; a finished one has been sent Shutdown.
            if result.is_err() {
                let _ = child.kill();
            }
            let _ = child.wait();
            result
        }));
    }
    drop(tx);

    let start = std::time::Instant::now();
    let (mut done, mut audio_ms, mut hits) = (0u64, 0u64, 0u64);
    for (n, ms, hit) in rx {
        done += n;
        audio_ms += ms;
        hits += hit;
        let elapsed = start.elapsed().as_secs_f64();
        // Real-time factor: audio seconds produced per wall second.
        let rtf = if elapsed > 0.0 { audio_ms as f64 / 1000.0 / elapsed } else { 0.0 };
        let per_s = if elapsed > 0.0 { done as f64 / elapsed } else { 0.0 };
        let eta = if per_s > 0.0 { (total_sentences.saturating_sub(done)) as f64 / per_s } else { 0.0 };
        let hit_rate = if done > 0 { hits as f64 / done as f64 } else { 0.0 };
        emit_progress(done, total_sentences, rtf, eta, hit_rate);
    }

    let mut by_chapter: HashMap<usize, Vec<ResultItem>> = HashMap::new();
    let mut worker_error: Option<String> = None;
    for thread in threads {
        match thread.join() {
            Ok(Ok(finished)) => {
                for (chapter, items) in finished {
                    by_chapter.entry(chapter).or_default().extend(items);
                }
            }
            Ok(Err(e)) => worker_error = worker_error.or(Some(e)),
            Err(_) => worker_error = worker_error.or(Some("worker thread panicked".into())),
        }
    }

    if let Some(e) = worker_error {
        fail("worker", &e);
    }

    let results = ResultsFile {
        book_id: manifest.book_id.clone(),
        chapters: manifest
            .chapters
            .iter()
            .map(|chapter| {
                let mut items = by_chapter.remove(&chapter.index).unwrap_or_default();
                items.sort_by_key(|r| r.index);
                ChapterResult { index: chapter.index, title: chapter.title.clone(), results: items }
            })
            .collect(),
    };
    let results_path = match &args.results {
        Some(path) => PathBuf::from(path),
        None => default_results_path(&args.manifest),
    };
    if let Err(e) = write_results(&results_path, &results) {
        fail(
            "results",
            &format!("failed to write results to {}: {e}", results_path.display()),
        );
    }
    tracing::info!("results written to {}", results_path.display());
    emit_state("done");
    // Dropping the last job handle would kill this process (a job member) during teardown; let process exit close it.
    std::mem::forget(_supervisor);
}

/// `results.json` next to the manifest, so a run without `--results` leaves its output beside its input.
fn default_results_path(manifest: &str) -> PathBuf {
    Path::new(manifest).with_file_name("results.json")
}

fn write_results(path: &Path, results: &ResultsFile) -> std::io::Result<()> {
    let json = serde_json::to_string_pretty(results).map_err(std::io::Error::other)?;
    std::fs::write(path, json)
}

fn spawn_workers(args: &Args, manifest: &Manifest, worker_count: usize) -> Vec<Child> {
    let worker_dir = Path::new(&args.worker_dir);
    let script = worker_dir.join("worker.py");
    if !script.is_file() {
        fail("worker_dir", &format!("worker.py not found in {}", worker_dir.display()));
    }

    let mut children = Vec::new();
    for i in 0..worker_count {
        let mut cmd = Command::new(&args.python);
        cmd.arg(&script)
            .arg("--models-dir").arg(&manifest.models_dir)
            .arg("--device").arg(&manifest.device)
            .arg("--cache-dir").arg(&manifest.cache_dir)
            .current_dir(worker_dir)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            // Inherited, never piped: nobody reads it, and a full pipe would
            // block the worker (and the whole build) forever.
            .stderr(Stdio::inherit());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        match cmd.spawn() {
            Ok(child) => {
                children.push(child);
                tracing::info!("spawned worker {i}");
            }
            Err(e) => fail("spawn", &format!("failed to spawn worker {i}: {e}")),
        }
    }
    children
}

fn handshake(children: &mut [Child], worker_count: usize) -> Vec<(ChildStdin, Receiver<Frame>)> {
    let mut io = Vec::new();
    for (i, child) in children.iter_mut().enumerate() {
        let mut stdin = match child.stdin.take() {
            Some(s) => s,
            None => fail("handshake", &format!("worker {i} has no stdin")),
        };
        let stdout = match child.stdout.take() {
            Some(s) => s,
            None => fail("handshake", &format!("worker {i} has no stdout")),
        };
        let hello = Envelope {
            v: PROTOCOL_VERSION,
            id: format!("hello-{i}"),
            body: MessageBody::Hello { protocol_version: PROTOCOL_VERSION },
        };
        if let Err(e) = stdin.write_all(&encode_frame(&hello)) {
            fail("handshake", &format!("worker {i} write failed: {e}"));
        }
        let _ = stdin.flush();
        let frames = spawn_frame_reader(BufReader::new(stdout));
        match next_frame(i, &frames, STARTUP_TIMEOUT) {
            Ok(Envelope { body: MessageBody::Ready { providers, .. }, .. }) => {
                tracing::info!("worker {} ready ({} workers): {:?}", i, worker_count, providers);
            }
            Ok(_) => {
                child.kill().ok();
                fail("handshake", &format!("worker {i} sent an unexpected reply"));
            }
            Err(e) => {
                child.kill().ok();
                fail("handshake", &e);
            }
        }
        io.push((stdin, frames));
    }
    io
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_results_path_sits_next_to_the_manifest() {
        let nested = default_results_path("work/build_manifest.json");
        assert_eq!(nested, Path::new("work").join("results.json"));
        assert_eq!(
            default_results_path("build_manifest.json"),
            Path::new("results.json")
        );
    }

    #[test]
    fn results_write_failure_is_reported() {
        let missing = std::env::temp_dir()
            .join(format!("no-such-dir-{}", std::process::id()))
            .join("results.json");
        let results = ResultsFile {
            book_id: "b".to_string(),
            chapters: Vec::new(),
        };
        assert!(write_results(&missing, &results).is_err());
    }
}
