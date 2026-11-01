use clap::Parser;
use serde::{Deserialize, Serialize};
use std::io::{BufRead, BufReader, Write};
use std::process::{Command, Stdio};
use std::str::FromStr;
use tracing_subscriber::EnvFilter;

mod protocol;
mod scheduler;
mod supervisor;

use protocol::*;
use scheduler::Scheduler;
use supervisor::Supervisor;

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
    output_dir: String,
    cache_dir: String,
    render_audio_dir: String,
    render_text_dir: String,
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
    speaker: String,
    voice: String,
    speed: f32,
    cache_key: String,
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

fn emit_progress(done: u64, total: u64, rtf: f64, eta_s: f64, cache_hit_rate: f64) {
    let evt = serde_json::json!({
        "type": "progress",
        "done": done,
        "total": total,
        "rtf": rtf,
        "eta_s": eta_s,
        "cache_hit_rate": cache_hit_rate,
    });
    println!("{}", evt);
    let _ = std::io::stdout().flush();
}

fn emit_state(state: &str) {
    let evt = serde_json::json!({"type": "state", "state": state});
    println!("{}", evt);
    let _ = std::io::stdout().flush();
}

fn emit_error(kind: &str, message: &str) {
    let evt = serde_json::json!({"type": "error", "kind": kind, "message": message});
    println!("{}", evt);
    let _ = std::io::stdout().flush();
}

fn main() {
    let args = Args::parse();
    let filter = EnvFilter::from_str(&args.log_level).unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt().with_env_filter(filter).init();

    // Read manifest
    let manifest_str = match std::fs::read_to_string(&args.manifest) {
        Ok(s) => s,
        Err(e) => {
            emit_error("manifest_read", &format!("failed to read manifest: {e}"));
            std::process::exit(1);
        }
    };
    let manifest: Manifest = match serde_json::from_str(&manifest_str) {
        Ok(m) => m,
        Err(e) => {
            emit_error("manifest_parse", &format!("failed to parse manifest: {e}"));
            std::process::exit(1);
        }
    };

    if manifest.v != 1 {
        emit_error("protocol_version", &format!("unsupported manifest version: {}", manifest.v));
        std::process::exit(1);
    }

    // Create supervisor (Job Object for orphan prevention)
    let supervisor = match Supervisor::new() {
        Ok(s) => s,
        Err(e) => {
            emit_error("supervisor", &format!("failed to create supervisor: {e}"));
            std::process::exit(1);
        }
    };

    let worker_count = args.workers.min(manifest.workers);
    let batch_size = args.batch_size.min(manifest.batch_size);

    emit_state("running");

    // Spawn workers
    let mut workers: Vec<std::process::Child> = Vec::new();
    for i in 0..worker_count {
        let mut cmd = Command::new(&args.python);
        let manifest_dir = std::path::Path::new(&args.manifest).parent().unwrap_or(std::path::Path::new("."));
        let manifest_dir = std::path::Path::new(&args.manifest).parent().unwrap_or(std::path::Path::new("."));
        cmd.arg("-c")
            .arg(format!(
                "import sys; sys.path.insert(0, '.'); from worker import main; main(['--models-dir', '{}', '--device', '{}', '--cache-dir', '{}'])",
                manifest.models_dir, manifest.device, manifest.cache_dir
            ))
            .current_dir(manifest_dir)
            .arg("--models-dir").arg(&manifest.models_dir)
            .arg("--device").arg(&manifest.device)
            .arg("--cache-dir").arg(&manifest.cache_dir)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        match cmd.spawn() {
            Ok(mut child) => {
                let pid = child.id();
                let _ = supervisor.assign_process(pid);
                workers.push(child);
                tracing::info!("spawned worker {}", i);
            }
            Err(e) => {
                emit_error("spawn", &format!("failed to spawn worker {}: {e}", i));
                std::process::exit(1);
            }
        }
    }

    // Handshake: wait for ready from each worker
    let mut worker_stdins: Vec<std::process::ChildStdin> = Vec::new();
    let mut worker_stdouts: Vec<BufReader<std::process::ChildStdout>> = Vec::new();
    for (i, child) in workers.iter_mut().enumerate() {
        let mut stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let mut reader = BufReader::new(stdout);

        // Send hello
        let hello = Envelope {
            v: PROTOCOL_VERSION,
            id: format!("hello-{}", i),
            body: MessageBody::Hello { protocol_version: PROTOCOL_VERSION },
        };
        let frame = encode_frame(&hello);
        if let Err(e) = stdin.write_all(&frame) {
            emit_error("handshake", &format!("worker {} write failed: {e}", i));
            std::process::exit(1);
        }
        let _ = stdin.flush();

        // Read ready (with timeout)
        // For simplicity, read synchronously with a timeout via a thread
        // (tokio async would be better but this is a scaffold)
        let ready_result = read_frame_sync(&mut reader);
        match ready_result {
            Ok(env) => {
                match env.body {
                    MessageBody::Ready { providers, .. } => {
                        tracing::info!("worker {} ready: {:?}", i, providers);
                    }
                    _ => {
                        emit_error("handshake", &format!("worker {} unexpected response", i));
                        std::process::exit(1);
                    }
                }
            }
            Err(e) => {
                emit_error("handshake", &format!("worker {} handshake failed: {e}", i));
                std::process::exit(1);
            }
        }
        worker_stdins.push(stdin);
        worker_stdouts.push(reader);
    }

    // Process chapters: for each chapter, send synth_batch for all sentences
    let mut all_results: Vec<ChapterResult> = Vec::new();
    let total_sentences: u64 = manifest.chapters.iter().map(|c| c.sentences.len() as u64).sum();
    let mut done: u64 = 0;
    let start_time = std::time::Instant::now();

    for chapter in &manifest.chapters {
        let mut chapter_results: Vec<ResultItem> = Vec::new();
        // Batch sentences
        for chunk in chapter.sentences.chunks(batch_size) {
            let items: Vec<BatchItem> = chunk.iter().map(|s| BatchItem {
                index: s.sid,
                text: s.text.clone(),
                voice: s.voice.clone(),
                speed: s.speed,
                cache_key: s.cache_key.clone(),
            }).collect();
            let batch_id = format!("ch{}-b{}", chapter.index, done);
            let synth = Envelope {
                v: PROTOCOL_VERSION,
                id: batch_id.clone(),
                body: MessageBody::SynthBatch { batch_id: batch_id.clone(), items },
            };
            let frame = encode_frame(&synth);

            // Send to first available worker (round-robin)
            let widx = (done as usize) % worker_count;
            if let Err(e) = worker_stdins[widx].write_all(&frame) {
                emit_error("send", &format!("write failed: {e}"));
                std::process::exit(1);
            }
            let _ = worker_stdins[widx].flush();

            // Read response
            match read_frame_sync(&mut worker_stdouts[widx]) {
                Ok(env) => {
                    match env.body {
                        MessageBody::BatchDone { results, .. } => {
                            let n = results.len();
                            for r in results {
                                chapter_results.push(r);
                            }
                            done += n as u64;
                            let elapsed = start_time.elapsed().as_secs_f64();
                            let rtf = if elapsed > 0.0 { done as f64 / elapsed } else { 0.0 };
                            let eta = if rtf > 0.0 { (total_sentences - done) as f64 / rtf } else { 0.0 };
                            emit_progress(done, total_sentences, rtf, eta, 0.0);
                        }
                        MessageBody::BatchError { message, .. } => {
                            emit_error("batch", &message);
                        }
                        _ => {
                            emit_error("response", "unexpected response type");
                        }
                    }
                }
                Err(e) => {
                    emit_error("read", &format!("read failed: {e}"));
                    std::process::exit(1);
                }
            }
        }
        all_results.push(ChapterResult {
            index: chapter.index,
            title: chapter.title.clone(),
            results: chapter_results,
        });
    }

    // Send shutdown to all workers
    let shutdown = Envelope {
        v: PROTOCOL_VERSION,
        id: "shutdown".to_string(),
        body: MessageBody::Shutdown,
    };
    let frame = encode_frame(&shutdown);
    for stdin in &mut worker_stdins {
        let _ = stdin.write_all(&frame);
        let _ = stdin.flush();
    }

    // Wait for workers to exit
    for mut child in workers {
        let _ = child.wait();
    }

    // Write results
    let results = ResultsFile {
        book_id: manifest.book_id.clone(),
        chapters: all_results,
    };
    let results_path = args.results.clone().unwrap_or_else(|| {
        format!("{}/results.json", manifest.output_dir)
    });
    match std::fs::write(&results_path, serde_json::to_string_pretty(&results).unwrap()) {
        Ok(_) => tracing::info!("results written to {}", results_path),
        Err(e) => emit_error("results", &format!("failed to write results: {e}")),
    }

    emit_state("done");
}

fn encode_frame(env: &Envelope) -> Vec<u8> {
    let json = serde_json::to_vec(env).unwrap();
    let mut frame = Vec::with_capacity(4 + json.len());
    frame.extend_from_slice(&(json.len() as u32).to_le_bytes());
    frame.extend_from_slice(&json);
    frame
}

fn read_frame_sync(reader: &mut BufReader<std::process::ChildStdout>) -> Result<Envelope, String> {
    let mut hdr = [0u8; 4];
    use std::io::Read;
    reader.read_exact(&mut hdr).map_err(|e| e.to_string())?;
    let n = u32::from_le_bytes(hdr) as usize;
    if n == 0 || n > 16 * 1024 * 1024 {
        return Err(format!("bad frame size {n}"));
    }
    let mut buf = vec![0u8; n];
    reader.read_exact(&mut buf).map_err(|e| e.to_string())?;
    serde_json::from_slice(&buf).map_err(|e| e.to_string())
}