# Scribe Render Pipeline Optimization Plan (Option A, revised)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Cut wall-clock render time of `scribe build` without changing output bytes or the bundle format.

**Architecture:** A staged pipeline instead of one process pool:

```
[Stage A: prep pool]  -> [Stage B: inference workers] -> [Stage C: encode pool] -> [Stage D: ordered assembler]
 N processes             K workers (K by device)          ffmpeg subprocesses        single process, in order
 split/phonemize/        only place the model lives        PCM -> MP3 per chunk       concat, timings, manifest
 cache-key/cache-check
```

**Tech Stack:** Python 3.11+, `concurrent.futures`, `multiprocessing` (spawn context), `onnxruntime`, ffmpeg subprocesses.

**Spec:** `docs/05-ScribeDesign.md`, `docs/DECISIONS.md` (revisits D-192).

## Global Constraints
- Python 3.11+, Windows 11 (`spawn` start method; all worker entry points must be importable top-level functions, and the build entry must sit under `if __name__ == "__main__"`).
- ffmpeg stays a separate process, never linked.
- **Bundle format is law:** output must be byte-identical (or, if MP3 encoder timing causes drift, identical in sentence text/order and within a documented timing tolerance). `scribe validate` must pass.
- Cache key stays `text + voice + speed + engine`. Do not change it, or every user cache invalidates.
- `--workers` defaults to current behavior (1). The new path is opt-in until benchmarks pass.

## Success Criteria (define before coding)
- Wall-clock reduction of at least X% on the reference chapter (set X after Task 0; suggest 25%).
- Peak RSS (all processes summed) under a budget you choose, e.g. 70% of free RAM on the dev machine.
- Zero cache corruption across 20 consecutive interrupted-and-resumed runs.
- Output equivalence test passes against the sequential baseline.

---

### Task 0: Baseline, profile, and decide what's worth parallelizing
**Files:**
- Create: `scribe/dev/bench_render.py`, `scribe/tests/fixtures/bench_chapter.*`
- Modify: `docs/DECISIONS.md` (new decision recording results)

This task decides whether Tasks 2 and 3 are worth doing.

- [ ] **Step 1: Pin a reference input.** One public-domain chapter (about 3,000 to 5,000 sentences if available, with dialogue and multiple speakers), plus a short one for fast iteration.
- [ ] **Step 2: Per-stage timing.** Instrument with `time.perf_counter()` around: sentence split, phonemization, `session.run`, PCM to MP3 (ffmpeg), concat and timing assembly, cache read and write. Output a table of seconds and % of total.
- [ ] **Step 3: Check which execution provider actually runs.** Log `session.get_providers()` at startup. If it reports only `CPUExecutionProvider`, the GPU path was never active, which explains the 1.28x vs 1.38x gap. Fix that first (install `onnxruntime-gpu` matching your CUDA/cuDNN, set `providers=["CUDAExecutionProvider","CPUExecutionProvider"]`). This may beat all the parallelism work.
- [ ] **Step 4: Record CPU utilization and GPU utilization** (`nvidia-smi dmon -s u` during a build). Low GPU % means the GPU is starved by serial prep, so Stage A parallelism helps. High GPU % means inference-bound, so the remaining wins are batching and encode overlap.
- [ ] **Step 5: Record peak RSS** per process (`psutil`) and total.
- [ ] **Step 6: Write the decision.** Add a `docs/DECISIONS.md` entry with the numbers and which later tasks are justified. Commit.

**Decision gate:** If phonemize + split + encode together are under about 15% of wall time, skip Task 2 and put the effort into Task 3 (inference concurrency) and Task 4 (encode overlap).

### Task 1: Equivalence and safety tests (before any refactor)
**Files:**
- Create: `scribe/tests/test_render_equivalence.py`, `scribe/tests/test_cache_concurrency.py`

- [ ] **Step 1: Golden sequential run.** Render the short fixture with the current code and store per-sentence cache keys, PCM hashes (hash decoded samples, not MP3 bytes), and the sentence-timing list.
- [ ] **Step 2: Equivalence test.** Assert the new path produces identical sentence order, identical cache keys, identical PCM hashes, and timings within ±1 ms (or exact, if feasible). Run `scribe validate` on the output.
- [ ] **Step 3: Cache concurrency test.** Spawn 8 processes writing the same and different keys simultaneously. Assert no partial files, no exceptions, and the entry readable afterwards.
- [ ] **Step 4: Kill-and-resume test.** Start a build, `terminate()` a worker mid-write, and resume. Assert it completes and output matches the golden run.
- [ ] **Step 5: Commit** (tests expected to pass against the sequential path now, and gate every later task).

### Task 2: Stage A, parallel prep (only if Task 0 justifies it)
**Files:**
- Modify: `scribe/tts/engine.py`
- Create: `scribe/tts/prep.py` (worker-side functions, no model imports)

**Interfaces:**
- Produces: `prep_chunk(job: PrepJob) -> PrepResult` where `PrepResult` is a picklable dataclass: `index`, `text`, `voice`, `speed`, `phoneme_ids/tokens`, `cache_key`, `cache_hit: bool`.
- Consumes: a read-only snapshot of the cast (`dict`), not the live `cast.yaml` file.

- [ ] **Step 1: Make prep a pure function.** No model, no globals, no file writes. Phonemize via espeak-ng here (one espeak instance per process, which sidesteps its thread-unsafe global state).
- [ ] **Step 2: Load `cast.yaml` once in the parent.** Resolve voice and speed per sentence in the parent and send plain values to workers. Workers never open `cast.yaml`, so there is no read race with the Web UI's "conflict" detection.
- [ ] **Step 3: Use `ProcessPoolExecutor(max_workers=N, mp_context=get_context("spawn"))`** with an `initializer` that sets up espeak once. Default `N = min(4, os.cpu_count() // 2)`, overridable with `--prep-workers`.
- [ ] **Step 4: Chunk the work.** Submit batches (e.g. 64 sentences per task), not one task per sentence, because pickling and IPC overhead on Windows will otherwise erase the gain.
- [ ] **Step 5: Bound the queue.** Keep at most `N * 4` batches in flight using a semaphore or a bounded `deque` of futures. This is the main memory-pressure control, since an unbounded `executor.map` on a 10-hour book queues everything.
- [ ] **Step 6: Return results in order.** Use `as_completed` with an index-keyed reorder buffer so Stage D always receives sentences in sequence.
- [ ] **Step 7: Run Task 1 tests, then benchmark vs the Task 0 baseline. Commit.**

### Task 3: Stage B, inference concurrency (replaces "lock in `cache.py`")
**Files:**
- Modify: `scribe/tts/engine.py`
- Create: `scribe/tts/inference.py`
- Modify (minimal): `scribe/tts/cache.py` (atomic writes only)

`cache.py` should own storage, not GPU policy. Put device policy in `inference.py`.

Choose the model by what Task 0 found:

- **Option B1 (recommended default): one inference process or thread, no lock needed.** A single worker owns the `InferenceSession` and consumes a bounded queue. Serialization comes from the architecture instead of a lock, so it can't be forgotten. Throughput improves by overlapping prep and encode with inference (Stage A and C), not by running inference concurrently.
- **Option B2: multiple sessions, if the GPU is underutilized.** `K` workers (start K=2), each with its own `InferenceSession`. This costs K × model RAM/VRAM. Only do this if `nvidia-smi` shows headroom and Task 0 shows inference-bound.
- **Option B3 (CPU-only fallback): K sessions with partitioned `intra_op_num_threads`,** e.g. 2 sessions × 4 threads on an 8-core machine, which often beats 1 session × 8 threads for many short sentences.

- [ ] **Step 1: Verify thread-safety assumption empirically.** `InferenceSession.run` is documented as thread-safe in ONNX Runtime, so a lock may be unnecessary. Write a test that calls `run` from 4 threads on the same session for 1,000 short sentences and compares outputs to sequential. If identical and stable, do not add a lock.
- [ ] **Step 2: Implement the chosen option** with a `--device {auto,cpu,cuda}` and `--inference-workers K` flag (matching the Web UI Settings `auto/cpu/cuda`).
- [ ] **Step 3: If a lock is still required** (e.g. a non-ORT engine path), use `multiprocessing.Lock` passed through the pool `initializer`. A `threading.Lock` is process-local and will not work across processes. Hold it only around the `run()` call, never around cache I/O or encoding.
- [ ] **Step 4: Sort by length within a window** (e.g. 32 sentences) before inference to reduce padding waste if the engine batches. Restore order before Stage D. Skip if the engine doesn't batch.
- [ ] **Step 5: Atomic cache writes.** In `cache.py`: write to `<key>.tmp.<pid>`, `flush` and `os.fsync`, then `os.replace()` to the final name. Reads treat a missing or short file as a miss. Two workers computing the same key is harmless because `replace` is atomic and the content is identical.
- [ ] **Step 6: Failure handling.** If an inference worker dies (OOM, driver reset), mark its in-flight sentences failed, respawn the worker once, and retry those sentences. On a second failure, abort cleanly so the resumable cache keeps everything finished. The Web UI should show `interrupted`, not a hang.
- [ ] **Step 7: Run Task 1 tests, then benchmark. Commit.**

### Task 4: Stage C, overlap MP3 encoding
**Files:**
- Modify: `scribe/tts/engine.py` (or the module that calls ffmpeg)

- [ ] **Step 1: Confirm in Task 0 how much time ffmpeg takes** and whether it currently runs after all synthesis. If per-chapter encoding is serial at the end, this is the cheapest win.
- [ ] **Step 2: Encode per chapter as soon as that chapter's sentences are complete**, using a small thread pool (threads are fine here because the work is in the ffmpeg subprocess). Cap concurrent ffmpeg processes at 2 to 3.
- [ ] **Step 3: Stream PCM to ffmpeg via stdin** (or temp WAV) with `-ac 1 -b:a 64k` to match the bundle spec. Don't change encoder flags, so output stays identical.
- [ ] **Step 4: Handle Windows pipe deadlocks.** Drain stderr in a thread or redirect to a file, and always `communicate()`.
- [ ] **Step 5: Run equivalence tests, benchmark. Commit.**

### Task 5: Resource governance and observability
**Files:**
- Modify: `scribe/tts/engine.py`, Web UI job progress reporting (existing RTF/ETA/cache-hit fields)

- [ ] **Step 1: Memory guard.** Before starting, estimate `N × prep RSS + K × model RSS` from the Task 0 measurements and compare with `psutil.virtual_memory().available`. Auto-reduce workers (with a printed note) if it exceeds 70%.
- [ ] **Step 2: `scribe doctor` row** reporting detected providers, physical cores, free RAM, and the recommended `--prep-workers` and `--inference-workers`.
- [ ] **Step 3: Per-stage timings in the job log** (same table as Task 0) so regressions show up in future runs.
- [ ] **Step 4: Cancellation.** Pause, resume, and cancel from the Jobs tray must drain queues and shut down pools without orphaned processes. Test by cancelling mid-build and checking Task Manager and `psutil.children()`.
- [ ] **Step 5: Keep-awake and detached-build behavior** unchanged. Verify the build still survives closing the browser.
- [ ] **Step 6: Commit.**

### Task 6: Final benchmark, docs, rollout
- [ ] **Step 1: Matrix run:** workers (1, 2, 4) × device (cpu, cuda) × fixture (short, chapter). Record wall time, RTF, peak total RSS, GPU %.
- [ ] **Step 2: Soak:** one full-length book twice with a forced kill in the middle of the second run. Validate the bundle, and diff against a sequential-built bundle.
- [ ] **Step 3: Set defaults** from the matrix. Keep the sequential path available via `--workers 1`.
- [ ] **Step 4: Update docs:** `docs/05-ScribeDesign.md` (pipeline diagram), `docs/DECISIONS.md` (new decision superseding D-192, with numbers), README flags list.
- [ ] **Step 5: Commit.**

## Risk Register

| Risk | Mitigation |
|---|---|
| Memory blowup from queued prep results | Bounded in-flight queue (Task 2 Step 5), memory guard (Task 5) |
| espeak-ng global-state corruption | One instance per process, never threads (Task 2 Step 1) |
| `threading.Lock` ineffective across processes | Single inference owner or `multiprocessing.Lock` via initializer (Task 3) |
| Cache corruption on crash or concurrent write | Atomic temp-and-replace writes, kill-and-resume test (Tasks 1, 3) |
| Spawn overhead on Windows erasing gains | Batched submissions, persistent pool, `initializer` setup (Task 2) |
| Output drift breaking bundle contract | PCM-hash equivalence test and `scribe validate` gate every task |
| GPU driver crash or OOM with K>1 sessions | K defaults to 1, worker respawn and retry, abort leaves cache intact (Task 3) |
| Speedup smaller than expected | Task 0 decision gate prevents building unjustified stages |

One thing to confirm in Task 0 Step 3: if CUDA turns out not to be active, fixing the provider is likely the biggest single speedup, and the rest of the plan then becomes tuning on top of it.