# Rust Orchestrator Implementation Plan (Option B, revised)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Move queueing, scheduling, supervision, and progress reporting for `scribe build` into a Rust binary, so orchestration no longer depends on Python's interpreter, and so a future Tauri shell can reuse it. Python keeps all text and audio logic.

**Architecture:**

```
scribe CLI / Web UI (Python)
        │ spawns, passes build manifest
        ▼
scribe-orchestrator (Rust, tokio)
  ├─ scheduler: bounded queue, ordering, retries, backpressure, pause/resume/cancel
  ├─ worker supervisor: spawns N `scribe worker` Python processes, health checks, respawn
  ├─ progress aggregator: RTF, ETA, cache-hit rate -> JSON events on stdout
  └─ ffmpeg supervisor (optional, Task 6)
        │ length-prefixed frames over each worker's stdin/stdout
        ▼
scribe worker (Python, long-lived)
  prep (split/phonemize/cache-key/cache-check) + inference + cache write
```

**Tech Stack:** Rust (stable), `tokio`, `serde` + `serde_json` (or `rmp-serde`), `tracing`, `windows-sys` (Job Objects), `clap`; Python 3.11+ worker entry point in `scribe/`.

**Spec:** `docs/05-ScribeDesign.md`, `docs/archive/v3/10-V3Roadmap.md`, `docs/DECISIONS.md` (new decision required).


## Global Constraints
- **Python stays canonical.** Cast resolution, speaker attribution, cache-key computation, bundle spec, validation, and timing math never get reimplemented in Rust. Rust treats `cache_key` as an opaque string from Python.
- Bundle format is law. Output must pass `scribe validate` and match the sequential baseline (PCM-hash equivalence, same as the Python plan).
- Python 3.11+, Windows 11 first-class (Linux/macOS builds compile but are untested).
- ffmpeg stays a separate process.
- No network exposure. The orchestrator binds nothing reachable from outside the machine.
- The Python-only path (`--engine python`) remains until the Rust path passes the full benchmark matrix.
- **Deviation from the original draft:** use stdio pipes, not a localhost HTTP API (see Task 1). A localhost port adds firewall prompts on Windows, port-collision handling alongside the Web UI's own port (8137), and no automatic lifetime coupling, which is the orphaned-process risk this plan lists as a review focus.

## Success Criteria (set numbers after the Prerequisite Gate)
- Wall-clock gain over the best Python-only result (not the original baseline, otherwise Rust gets credit for Option A's work).
- Per-sentence IPC overhead under 1 ms p99 and under 2% of per-sentence synthesis time.
- Zero orphaned `python.exe` / `ffmpeg.exe` after: normal exit, Ctrl+C, orchestrator crash (`taskkill /F`), Web UI cancel.
- Resume after kill produces an identical bundle.
- Peak total RSS within budget.

## Review Focus
- **IPC latency and serialization cost:** batch frames and avoid per-sentence round trips.
- **Process lifetime:** nothing outlives the orchestrator.
- **Protocol drift:** Python and Rust must agree on message shapes, so version and test the contract.
- **Build/distribution cost:** a Rust toolchain is now a dev dependency, and a prebuilt binary becomes a release artifact.

---

### Task 0: Decision record and baseline
**Files:**
- Modify: `docs/DECISIONS.md`
- Create: `scribe/dev/bench_render.py` (shared with the Python plan)

- [ ] **Step 1: Reuse the Python plan's benchmark** and fixtures. Record the best Python-only wall time, per-stage table, peak RSS.
- [ ] **Step 2: Measure orchestrator-only overhead** in the current code: time spent outside `run()`, prep, and ffmpeg. This is the ceiling on what Rust can recover.
- [ ] **Step 3: Write the decision** (go, defer, or go-for-architecture-reasons) with numbers. Commit.

### Task 1: Define the protocol (before any code)
**Files:**
- Create: `docs/15-OrchestratorProtocol.md`
- Create: `spec/orchestrator/` (JSON schema + fixtures, shared by both sides)

- [ ] **Step 1: Choose the transport.** One child process per worker, communicating over its stdin/stdout. Stdio dies with its parent, needs no ports, and works identically in tests. Fallback: Windows named pipes if stdio buffering proves problematic.
- [ ] **Step 2: Choose framing and encoding.** 4-byte little-endian length prefix + JSON (start simple and debuggable). Keep audio out of band: workers write PCM/WAV to the sentence cache and return only the path and metadata, never audio bytes through the pipe. Revisit MessagePack only if Task 0 shows JSON parsing is measurable.
- [ ] **Step 3: Define messages** (each carries `v` protocol version and `id`):
  - Orchestrator → worker: `hello`, `prepare_batch {batch_id, items[]}`, `synth_batch {batch_id, items[]}`, `cancel {batch_id}`, `shutdown`
  - Worker → orchestrator: `ready {providers, model_hash, rss}`, `batch_done {batch_id, results[]}`, `batch_error {batch_id, kind, message, retryable}`, `heartbeat {rss, inflight}`
  - Result item: `{index, cache_key, cache_hit, audio_path, duration_ms, sample_rate}`
- [ ] **Step 3b: Orchestrator → caller (Web UI / CLI) events,** one JSON object per line on stdout: `progress {done, total, rtf, eta_s, cache_hit_rate}`, `state {running|paused|cancelling|done|failed}`, `error {...}`. Map these onto the existing Jobs tray fields so the UI needs no redesign.
- [ ] **Step 4: Batching rule.** Default 32 sentences per frame (tunable). One round trip per batch, never per sentence.
- [ ] **Step 5: Versioning.** `hello` carries `protocol_version`; mismatch makes the orchestrator refuse to start with a clear message. Add a schema-validation test in Python and a serde round-trip test in Rust against the same fixtures.
- [ ] **Step 6: Specify error semantics.** `retryable` errors are retried up to 2 times with a respawned worker. Non-retryable errors abort cleanly and leave the cache intact.
- [ ] **Step 7: Commit the doc and fixtures.**

### Task 2: Python worker entry point
**Files:**
- Create: `scribe/worker.py` (or `scribe/tts/worker.py`)
- Modify: `scribe/cli.py` (hidden subcommand `scribe worker`)

- [ ] **Step 1: Implement a long-lived loop** that reads frames from stdin and writes frames to stdout. **Redirect all logging to stderr or a file.** A stray `print` on stdout corrupts the protocol, so replace `sys.stdout` with a guard after capturing the real stream.
- [ ] **Step 2: Load the model once** at startup, then emit `ready` including the active ONNX Runtime providers.
- [ ] **Step 3: Reuse existing Python functions** (prep, cache-key, cache read/write, inference). The worker is a thin adapter, with no logic copied.
- [ ] **Step 4: Atomic cache writes** (temp file, `os.replace`), as in the Python plan.
- [ ] **Step 5: Parent-death detection.** Exit when stdin hits EOF. Also poll the parent PID on Windows as a backstop.
- [ ] **Step 6: Test with a pure-Python harness** that speaks the protocol (no Rust yet): run the fixture through the worker and compare PCM hashes to the golden baseline. This isolates worker bugs from Rust bugs.
- [ ] **Step 7: Commit.**

### Task 3: Rust scaffolding
**Files:**
- Create: `scribe-orchestrator/Cargo.toml`, `src/main.rs`, `src/lib.rs`, `src/protocol.rs`
- Modify: `Cargo.toml` (workspace root)

- [ ] **Step 1: Workspace and crate layout.** Put logic in a **library crate** (`lib.rs`) with a thin binary (`main.rs`), so Tauri can later depend on the library directly with no rewrite.
- [ ] **Step 2: Implement `protocol.rs`** with `serde` types, frame encode/decode (`tokio_util::codec::LengthDelimitedCodec`), and the fixture round-trip tests.
- [ ] **Step 3: CLI** via `clap`: `--manifest <path>`, `--workers N`, `--batch-size`, `--python <path>`, `--log-level`. The manifest is a JSON file Python writes describing the build (sentence list with voice/speed already resolved, output dirs, ranges).
- [ ] **Step 4: Logging** with `tracing` to a file in the workspace (stdout is reserved for events).
- [ ] **Step 5: CI-style checks:** `cargo fmt --check`, `cargo clippy -- -D warnings`, `cargo test`. Add to the existing test flow.
- [ ] **Step 6: Commit.**

### Task 4: Process supervision and lifetime safety
**Files:**
- Create: `scribe-orchestrator/src/supervisor.rs`

- [ ] **Step 1: Windows Job Object** with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. Assign every spawned worker (and ffmpeg) to it, so the OS kills them if the orchestrator dies for any reason, including `taskkill /F`.
- [ ] **Step 2: Spawn workers** with `tokio::process::Command`, piped stdio, `kill_on_drop(true)`, and `CREATE_NO_WINDOW`.
- [ ] **Step 3: Startup handshake.** Wait for `ready` with a timeout (model load can take several seconds). On timeout or crash, fail with the worker's stderr tail included.
- [ ] **Step 4: Health.** Track heartbeats. A worker silent for T seconds (generous, since a long sentence can take a while) is killed and respawned, and its in-flight batch is re-queued.
- [ ] **Step 5: Graceful shutdown:** send `shutdown`, wait a few seconds, then kill.
- [ ] **Step 6: Orphan test.** An integration test starts a build, hard-kills the orchestrator, and asserts no child processes remain (poll via `sysinfo`). Repeat for Ctrl+C and for worker crash.
- [ ] **Step 7: Commit.**

### Task 5: Scheduler and orchestration logic
**Files:**
- Create: `scribe-orchestrator/src/orchestrator.rs`, `src/scheduler.rs`, `src/progress.rs`

- [ ] **Step 1: Bounded work queue** (`tokio::sync::mpsc` with a capacity of about `workers × 4` batches) so a 10-hour book never sits fully in memory.
- [ ] **Step 2: Dispatch.** Idle workers pull the next batch. Track `in_flight: HashMap<batch_id, Batch>`.
- [ ] **Step 3: Ordered completion.** Results arrive out of order. Use a reorder buffer keyed by sentence index and emit "chapter complete" to the next stage only when contiguous. Chapter boundaries drive ffmpeg encoding (Task 6) and the final manifest assembly.
- [ ] **Step 4: Retries and failure policy** per Task 1 Step 6. Never silently drop a sentence: a sentence that fails twice fails the build with its index and text.
- [ ] **Step 5: Pause, resume, cancel** via control messages on the orchestrator's stdin (`{"cmd":"pause"}`). Pause stops dispatch and lets in-flight batches finish. Cancel sends `cancel`, drains, and exits with a distinct code. The cache preserves everything done.
- [ ] **Step 6: Progress.** Aggregate sentences done, audio seconds produced, and wall time into RTF, ETA, and cache-hit rate. Emit throttled events (e.g. 4 Hz max).
- [ ] **Step 7: Cache-aware preflight.** Because workers report `cache_hit`, cached sentences should skip inference. Keep the Web UI's "will-render vs cached" preflight working by letting Python produce it (it already knows), not Rust.
- [ ] **Step 8: Unit tests** with a fake in-process worker (a Rust test double speaking the protocol): out-of-order completion, worker crash mid-batch, retry exhaustion, pause/resume, cancel.
- [ ] **Step 9: Commit.**

### Task 6: Integration and final assembly boundary
**Files:**
- Modify: `scribe/cli.py`, the build path in `scribe/`, Web UI job launcher
- Optional: `scribe-orchestrator/src/ffmpeg.rs`

- [ ] **Step 1: Decide the boundary explicitly.** Recommended: Rust orchestrates synthesis and (optionally) ffmpeg per chapter, then hands control back to Python for timing assembly, manifest, and validation. Those are spec logic, so they stay Python. Record this in the protocol doc.
- [ ] **Step 2: Add `--engine {python,rust}`** to `scribe build` (default `python`). `rust` writes the manifest, locates the binary (`SCRIBE_ORCHESTRATOR` env var, then a packaged path, then `cargo`-built path, with a clear `doctor` hint if missing), spawns it, and consumes progress events.
- [ ] **Step 3: Web UI jobs.** The existing detached-job model should spawn the same command. Verify pause/resume/cancel buttons map to control messages, and that closing the browser and reattaching still works (state file plus event log, same as today).
- [ ] **Step 4: Optional ffmpeg supervision in Rust.** Only if Task 0 shows encode overlap is valuable and the Python plan has not already captured it. Otherwise skip, to keep the Rust surface small.
- [ ] **Step 5: `scribe doctor` rows:** orchestrator binary found, protocol version match, worker handshake succeeds.
- [ ] **Step 6: End-to-end test:** short fixture with `--engine rust`, then `scribe validate` and the PCM-hash equivalence test.
- [ ] **Step 7: Commit.**

### Task 7: Benchmarks, hardening, rollout
- [ ] **Step 1: Matrix:** engine (python-best, rust) × workers (1, 2, 4) × device (cpu, cuda) × fixture (short, chapter). Record wall time, RTF, p99 IPC latency, peak RSS, GPU %.
- [ ] **Step 2: Chaos run on a full book:** random worker kills and one orchestrator hard-kill, then resume. Validate the bundle and diff against the sequential baseline.
- [ ] **Step 3: IPC profiling.** If per-batch overhead exceeds the criteria, raise batch size before considering MessagePack or shared memory.
- [ ] **Step 4: Packaging.** Decide how users get the binary: a prebuilt `scribe-orchestrator.exe` shipped alongside `scribe/`, or `cargo build --release` documented in setup. Avoid requiring Rust for end users. Add the binary's license check to `docs/08-Liscenses.md` (crate dependency licenses, compatibility with AGPL Scribe).
- [ ] **Step 5: Docs:** update `docs/05-ScribeDesign.md` (architecture diagram), README requirements and flags, `docs/DECISIONS.md`.
- [ ] **Step 6: Flip the default** to `rust` only after the matrix and chaos run pass. Keep `--engine python` for one release as the fallback.
- [ ] **Step 7: Commit.**

## Risk Register

| Risk | Mitigation |
|---|---|
| Rust adds complexity without a measurable speedup | Prerequisite Gate and Task 0 decision record; defer if the profile says so |
| Orphaned Python or ffmpeg processes | Job Object with kill-on-close, stdin-EOF exit, orphan tests (Task 4) |
| Stray stdout output corrupts the protocol | Guard `sys.stdout` in the worker; logs to stderr/file only (Task 2) |
| IPC overhead negates the gain | Batching, audio out of band via the cache, p99 criteria (Tasks 1, 7) |
| Protocol drift between Python and Rust | Version handshake, shared schema fixtures, round-trip tests on both sides (Task 1) |
| Logic duplication in Rust | Python-canonical constraint; Rust sees opaque keys and resolved values only |
| Toolchain and distribution burden | Prebuilt binary, doctor checks, Python fallback engine (Tasks 6, 7) |
| Model loaded N times consumes RAM/VRAM | Memory guard shared with the Python plan; default workers conservative |
| Windows antivirus or SmartScreen flags the unsigned exe | Document it; consider code signing if distributing beyond your own machines |

**Relationship to Option A:** treat them as sequential, not alternatives. Option A's staged pipeline, atomic cache writes, equivalence tests, and benchmarks are reused here, and Option B changes who runs the scheduler. If Option A reaches your target, Option B is justified mainly by the Tauri direction, not by speed.