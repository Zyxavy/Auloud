# Orchestrator Protocol v1.0

Contract between `scribe-orchestrator` (Rust) and its Python workers. One child process per worker, communicating over its stdin/stdout. Stdio dies with the parent, needs no ports. Workers write the same FLAC sentence cache Python reads (`tts.cache`: `<cache_key>.flac`, PCM_24) and return only the path plus metadata; audio never crosses the pipe.

The orchestrator is started by `scribe build --engine rust`. Python drafts, resolves the cast, writes the manifest and assembles the bundle; Rust only drives synthesis so several sentences render at once.

## 1. Transport

- One child process per worker, spawned by the orchestrator, killed with it (Windows Job Object, `KILL_ON_JOB_CLOSE`).
- stdin = orchestrator -> worker, stdout = worker -> orchestrator.
- All logging goes to stderr or a file. A stray `print` on stdout corrupts framing. Worker stderr is inherited, never piped, so heavy logging cannot block it.
- Parent death detection: worker exits on stdin EOF.
- Worker cwd = the scribe package folder, so `worker.py` and its flat imports resolve.

## 2. Framing

Each frame: a 4-byte little-endian unsigned length prefix, followed by that many bytes of UTF-8 JSON.

```
[ u32 LE ][ JSON bytes ]
```

Maximum frame size: 16 MiB. Larger payloads are split into batches (section 5).

## 3. Versioning

Every message carries `v` (protocol version integer), `id` (correlation id, string) and `type` (message name). `hello` carries `protocol_version`; a worker that sees a different value exits non-zero instead of answering. Current protocol version: `1`.

## 4. Messages

Orchestrator -> worker:

- `hello {v, id, type: hello, protocol_version}`
- `synth_batch {v, id, type: synth_batch, batch_id, items[]}`
- `shutdown {v, id, type: shutdown}`

Worker -> orchestrator:

- `ready {v, id, type: ready, providers[], model_hash, rss_mb}`
- `batch_done {v, id, type: batch_done, batch_id, results[]}`
- `batch_error {v, id, type: batch_error, batch_id, kind, message, retryable}`

Batch item: `{index, text, voice, speed}`. The worker derives the cache key itself from those plus the voice pitch constant and the engine version, so the key can never drift from the one Python uses.

Result item: `{index, cache_key, cache_hit, audio_path, duration_ms, sample_rate}`.

### 4.1 Orchestrator -> caller events

One JSON object per line on stdout:

- `progress {done, total, rtf, eta_s, cache_hit_rate}` (`rtf` = audio seconds produced per wall second)
- `state {running|done}`
- `error {kind, message}`

## 5. Batching

Default 32 sentences per frame (tunable). One round trip per batch, never per sentence. Batches are dealt to the workers round-robin and each worker sends and reads its own share on its own thread, so N workers synthesise at once. Batches are whole sentences: a batch is never split across workers.

## 6. Error semantics

A `batch_error` stops the run with a non-zero exit and leaves the cache intact; the cache is the resume state, so re-running the build synthesises only what is still missing. There is no automatic retry or worker respawn. A worker that cannot load its engine exits non-zero before answering `ready`, so a broken model folder fails the build instead of reporting success.

## 7. Manifest

Python writes `<work-dir>/build_manifest.json` and passes it with `--manifest`. Results go to `--results` (informational; Python reads the cache, not the results).

```json
{
  "v": 1,
  "book_id": "…",
  "models_dir": "models",
  "device": "auto",
  "cache_dir": "…/cache",
  "workers": 4,
  "batch_size": 32,
  "chapters": [
    {"index": 1, "title": "…", "sentences": [{"sid": 0, "text": "…", "voice": "am_adam", "speed": 1.0}]}
  ]
}
```

## 8. Assembly boundary

Rust orchestrates synthesis only. Timing assembly, bundle writing and validation are spec logic and stay Python (section 7 of the old note still holds: never reimplement cast, attribution, spec or validation outside Python).

## 9. Fixtures

Shared fixtures under `spec/orchestrator/` (JSON schema + example frames) document the wire format for both sides.
