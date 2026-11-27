use clap::Parser;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::{BufReader, Read, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::str::FromStr;
use std::sync::mpsc;
use tracing_subscriber::EnvFilter;

use scribe_orchestrator::protocol::*;
use scribe_orchestrator::supervisor::Supervisor;

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
    tracing_subscriber::fmt().with_env_filter(filter).init();

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

    let supervisor = match Supervisor::new() {
        Ok(s) => s,
        Err(e) => fail("supervisor", &format!("failed to create supervisor: {e}")),
    };

    // One worker takes one chunk at a time; never fewer than one.
    let worker_count = args.workers.min(manifest.workers).max(1);
    let batch_size = args.batch_size.min(manifest.batch_size).max(1);

    let mut children = spawn_workers(&args, &manifest, worker_count, &supervisor);
    let mut io = handshake(&mut children, worker_count);

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
    let mut threads = Vec::new();
    for (widx, (stdin, reader)) in io.drain(..).enumerate() {
        let mine: Vec<(usize, String, Vec<BatchItem>)> = batches
            .iter()
            .skip(widx)
            .step_by(worker_count)
            .cloned()
            .collect();
        let tx = tx.clone();
        threads.push(std::thread::spawn(move || run_worker(stdin, reader, mine, tx)));
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

    for mut child in children {
        let _ = child.wait();
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
    let results_path = args
        .results
        .clone()
        .unwrap_or_else(|| format!("{}/results.json", args.manifest));
    match std::fs::write(&results_path, serde_json::to_string_pretty(&results).unwrap()) {
        Ok(_) => tracing::info!("results written to {results_path}"),
        Err(e) => tracing::warn!("failed to write results: {e}"),
    }

    emit_state("done");
}

fn spawn_workers(
    args: &Args,
    manifest: &Manifest,
    worker_count: usize,
    supervisor: &Supervisor,
) -> Vec<Child> {
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
                let _ = supervisor.assign_process(child.id());
                children.push(child);
                tracing::info!("spawned worker {i}");
            }
            Err(e) => fail("spawn", &format!("failed to spawn worker {i}: {e}")),
        }
    }
    children
}

fn handshake(children: &mut [Child], worker_count: usize) -> Vec<(ChildStdin, BufReader<ChildStdout>)> {
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
        let mut reader = BufReader::new(stdout);
        let hello = Envelope {
            v: PROTOCOL_VERSION,
            id: format!("hello-{i}"),
            body: MessageBody::Hello { protocol_version: PROTOCOL_VERSION },
        };
        if let Err(e) = stdin.write_all(&encode_frame(&hello)) {
            fail("handshake", &format!("worker {i} write failed: {e}"));
        }
        let _ = stdin.flush();
        match read_frame_sync(&mut reader) {
            Ok(Envelope { body: MessageBody::Ready { providers, .. }, .. }) => {
                tracing::info!("worker {} ready ({} workers): {:?}", i, worker_count, providers);
            }
            Ok(_) => fail("handshake", &format!("worker {i} sent an unexpected reply")),
            Err(e) => fail("handshake", &format!("worker {i} handshake failed: {e}")),
        }
        io.push((stdin, reader));
    }
    io
}

/// Run one worker to the end of its share: `(chapter, results)` per batch.
fn run_worker(
    mut stdin: ChildStdin,
    mut reader: BufReader<ChildStdout>,
    work: Vec<(usize, String, Vec<BatchItem>)>,
    tx: mpsc::Sender<(u64, u64, u64)>,
) -> Result<Vec<(usize, Vec<ResultItem>)>, String> {
    let mut finished = Vec::new();
    for (chapter, batch_id, items) in work {
        let batch = Envelope {
            v: PROTOCOL_VERSION,
            id: batch_id.clone(),
            body: MessageBody::SynthBatch { batch_id, items },
        };
        stdin
            .write_all(&encode_frame(&batch))
            .map_err(|e| format!("write failed: {e}"))?;
        let _ = stdin.flush();
        match read_frame_sync(&mut reader)? {
            Envelope { body: MessageBody::BatchDone { results, .. }, .. } => {
                let done = results.len() as u64;
                let audio_ms = results.iter().map(|r| r.duration_ms).sum();
                let hits = results.iter().filter(|r| r.cache_hit).count() as u64;
                finished.push((chapter, results));
                let _ = tx.send((done, audio_ms, hits));
            }
            Envelope { body: MessageBody::BatchError { message, .. }, .. } => {
                return Err(message)
            }
            _ => return Err("unexpected reply from worker".into()),
        }
    }
    let shutdown = Envelope {
        v: PROTOCOL_VERSION,
        id: "shutdown".to_string(),
        body: MessageBody::Shutdown,
    };
    let _ = stdin.write_all(&encode_frame(&shutdown));
    let _ = stdin.flush();
    Ok(finished)
}

fn encode_frame(env: &Envelope) -> Vec<u8> {
    let json = serde_json::to_vec(env).unwrap();
    let mut frame = Vec::with_capacity(4 + json.len());
    frame.extend_from_slice(&(json.len() as u32).to_le_bytes());
    frame.extend_from_slice(&json);
    frame
}

fn read_frame_sync(reader: &mut BufReader<ChildStdout>) -> Result<Envelope, String> {
    let mut hdr = [0u8; 4];
    reader.read_exact(&mut hdr).map_err(|e| e.to_string())?;
    let n = u32::from_le_bytes(hdr) as usize;
    if n == 0 || n > 16 * 1024 * 1024 {
        return Err(format!("bad frame size {n}"));
    }
    let mut buf = vec![0u8; n];
    reader.read_exact(&mut buf).map_err(|e| e.to_string())?;
    serde_json::from_slice(&buf).map_err(|e| e.to_string())
}
