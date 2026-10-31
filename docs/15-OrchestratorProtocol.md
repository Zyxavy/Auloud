# Orchestrator Protocol v1.0

Contract between `scribe-orchestrator` (Rust) and `scribe worker` (Python). One child process per worker, communicating over its stdin/stdout. Stdio dies with the parent, needs no ports. Workers write PCM/WAV to the sentence cache and return only the path plus metadata; audio never crosses the pipe.

## 1. Transport

- One child process per worker, spawned by the orchestrator.
- stdin = orchestrator -> worker, stdout = worker -> orchestrator.
- All logging goes to stderr or a file. A stray `print` on stdout corrupts framing.
- Parent death detection: worker exits on stdin EOF; also polls parent PID on Windows.

Fallback: Windows named pipes if stdio buffering proves problematic.

## 2. Framing

Each frame: a 4-byte little-endian unsigned length prefix, followed by that many bytes of UTF-8 JSON.

```
[ u32 LE ][ JSON bytes ]
```

Maximum frame size: 16 MiB. Larger payloads are split into batches (section 5).

## 3. Versioning

Every message carries `v` (protocol version integer) and `id` (correlation id, string). `hello` carries `protocol_version`. On mismatch the orchestrator refuses to start with a clear message. Current protocol version: `1`.

## 4. Messages

Orchestrator -> worker:

- `hello {v, id, protocol_version}`
- `prepare_batch {v, id, batch_id, items[]}`
- `synth_batch {v, id, batch_id, items[]}`
- `cancel {v, id, batch_id}`
- `shutdown {v, id}`

Worker -> orchestrator:

- `ready {v, id, providers[], model_hash, rss}`
- `batch_done {v, id, batch_id, results[]}`
- `batch_error {v, id, batch_id, kind, message, retryable}`
- `heartbeat {v, id, rss, inflight}`

Result item: `{index, cache_key, cache_hit, audio_path, duration_ms, sample_rate}`.

### 4.1 Orchestrator -> caller (Web UI / CLI) events

One JSON object per line on stdout:

- `progress {done, total, rtf, eta_s, cache_hit_rate}`
- `state {running|paused|cancelling|done|failed}`
- `error {...}`

Mapped onto existing Jobs tray fields; no UI redesign.

## 5. Batching

Default 32 sentences per frame (tunable). One round trip per batch, never per sentence.

## 6. Error semantics

`retryable` errors retried up to 2 times with a respawned worker. Non-retryable errors abort cleanly and leave the cache intact. A sentence failing twice fails the build with its index and text.

## 7. Assembly boundary

Rust orchestrates synthesis and (optionally) ffmpeg per chapter, then hands control back to Python for timing assembly, manifest, and validation. Those are spec logic and stay Python.

## 8. Fixtures

Shared fixtures under `spec/orchestrator/` (JSON schema + example frames) are consumed by both the Python schema test and the Rust serde round-trip test.