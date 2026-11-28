//! Test double for scribe/worker.py: speaks the orchestrator protocol on stdin/stdout.
//! Built only for the scribe-orchestrator tests; it ignores the worker.py path and flags it is given.
//! FAKE_WORKER_MODE=exit_mid_batch makes it exit on the first synth_batch without replying.
use serde_json::{json, Value};
use std::io::{self, ErrorKind, Read, Write};

fn read_frame(input: &mut impl Read) -> io::Result<Option<Value>> {
    let mut header = [0u8; 4];
    match input.read_exact(&mut header) {
        Ok(()) => {}
        Err(e) if e.kind() == ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(e),
    }
    let mut payload = vec![0u8; u32::from_le_bytes(header) as usize];
    input.read_exact(&mut payload)?;
    serde_json::from_slice(&payload)
        .map(Some)
        .map_err(io::Error::other)
}

fn write_frame(out: &mut impl Write, frame: &Value) -> io::Result<()> {
    let raw = serde_json::to_vec(frame).map_err(io::Error::other)?;
    out.write_all(&(raw.len() as u32).to_le_bytes())?;
    out.write_all(&raw)?;
    out.flush()
}

fn main() -> io::Result<()> {
    let stdin = io::stdin();
    let mut input = stdin.lock();
    let stdout = io::stdout();
    let mut out = stdout.lock();
    let exit_mid_batch = std::env::var("FAKE_WORKER_MODE").as_deref() == Ok("exit_mid_batch");
    while let Some(frame) = read_frame(&mut input)? {
        let id = frame["id"].as_str().unwrap_or_default().to_string();
        match frame["type"].as_str() {
            Some("hello") => write_frame(
                &mut out,
                &json!({"v": 1, "id": id, "type": "ready", "providers": ["CPUExecutionProvider"],
                        "model_hash": "fake", "rss_mb": 0}),
            )?,
            Some("synth_batch") if exit_mid_batch => return Ok(()),
            Some("synth_batch") => {
                let results: Vec<Value> = frame["items"]
                    .as_array()
                    .map(|items| {
                        items
                            .iter()
                            .map(|item| {
                                json!({
                                    "index": item["index"],
                                    "cache_key": format!("fake-{}", item["index"]),
                                    "cache_hit": false,
                                    "audio_path": "",
                                    "duration_ms": 1000,
                                    "sample_rate": 24000
                                })
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                write_frame(
                    &mut out,
                    &json!({"v": 1, "id": id, "type": "batch_done",
                            "batch_id": frame["batch_id"], "results": results}),
                )?;
            }
            Some("shutdown") => break,
            _ => {}
        }
    }
    Ok(())
}
