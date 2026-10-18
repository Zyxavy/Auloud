# Plan: Scribe render throughput (speed tiers + parallel chapters)

Status: decisions accepted 2026-10-18; RT1 and the RT5 gate probe are done
and their numbers are below. Not yet a roadmap slice (`10-V3Roadmap.md`
stops at Slice 22, Installers). No bundle-format change, no spec change,
no new dependency. The book's cast is not part of this work.

## 1. Goal

A render already owns the whole machine and uses about one twentieth of
it. Let `scribe build` spend the idle GPU headroom on the same range, and
give the owner one knob for how greedy a render may be.

**Done when:**

- `scribe build --render-speed fast` renders the same range measurably
  faster than `--render-speed slow` (RT1 predicts about 3.9x on the
  synthesis path; RT4 measures the real build)
- `slow` is exactly today's behavior (one worker, one sentence in flight)
- Tests prove the parallel plumbing matches the serial one exactly with a
  deterministic fake engine, that a stop leaves no partial chapter and no
  bundle, and that a failing chapter still records (non-strict) or aborts
  (strict)
- The A/B numbers, the worker counts and the measured CUDA drift floor
  are in `docs/DECISIONS.md`

## 2. Inputs and what is known

Baseline, the full GPU build of 2026-10-18 (chapters 94-112):

- 19 chapters, 16 rendered, 1751.6 s wall, 2:24:36 audio, RTF 4.95x
- about 0.73 s per sentence end to end; 3.2 s of audio per sentence
- one render process: 0.93 of 20 logical cores busy, 1.4/23.7 GB RAM,
  about 1.1 GB VRAM; CPU-only was about 4.6 s per sentence
- machine: 20 logical cores, 23.7 GB RAM, 6 GB VRAM on a driver capped at
  CUDA 12.9, `onnxruntime-gpu` 1.29.0

**RT1 result** (`scribe/dev/probe_render_scaling.py`, 40 real sentences
from chapters 97-100, voice `am_onyx`, cache untouched, three runs):

| threads | wall s | s/sentence | speedup | run ms | other ms | VRAM MB |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 36.97 | 1.08 | 1.00 | 911 | 13 | 1129 |
| 2 | 20.14 | 1.99 | 1.85 | 996 | -493 | 2181 |
| 4 | 12.47 | 3.21 | 2.99 | 1198 | -886 | 2221 |
| 8 | 9.54 | 4.19 | 3.90 | 1727 | -1488 | 3321 |
| 12 | 11.27 | 3.55 | 3.31 | 3025 | -2743 | 4425 |

- **The gate passes.** T=4 is 2.99x and T=8 is about 3.9x against T=1
  (three runs gave 3.67x, 3.81x, 3.90x). T=12 is consistently *worse*
  (3.16x, 3.17x, 3.31x) and takes 4.4 of 6 GB VRAM, so the knee is 8.
- **Phonemize is not the bottleneck.** At T=1 the whole sentence is
  911 ms inside `session.run` and 13 to 20 ms outside it (2%). The
  plan's original guess (a long CPU phonemize pole) was wrong.
- `run ms` is queue-inclusive when T>1 (eight callers waiting on one
  session), which is why `other ms` goes negative there; only the T=1
  row should be read as a stage split.
- Audio drift at T=8 against a serial run over the first 8 sentences:
  mean 1.2e-11, zero samples above 1e-3. A serial rerun of the same
  sentence drifts the same way. Concurrency does not corrupt audio.
- The full 40-sentence comparison still shows rare outliers (max sample
  delta 0.14 to 0.31) *in both the serial-vs-serial and the parallel
  comparisons*: the CUDA path occasionally moves a trim boundary by a
  sample or two. So byte equality is not a sound acceptance test on the
  CUDA path; see decision 6.

**Cost model** for one `session.run` on this machine, from two points
(1 sentence = 3.7 s audio in 1.16 s, 8 sentences = 23.3 s audio in 3.74 s
in one call): about **0.67 s fixed cost per call plus about 0.13 s per
second of audio generated**. So roughly two thirds of the current render
time is per-call overhead, not synthesis.

**RT5 gate probe** (`scribe/dev/probe_batch_windows.py`, same 8
sentences):

| case | wall s | `session.run` calls | audio s | ms/sentence |
| --- | --- | --- | --- | --- |
| A: 8 per-sentence calls (today) | 10.00 | 8 | 29.67 | 1250 |
| B: one `create_timed` call | 4.66 | 1 | 23.27 | 583 |
| C: `continuous=True` | FAILED | - | - | - |

- Batching is real: **2.49x faster with 8x fewer calls**, exactly what
  the cost model predicts.
- **But it is blocked**: the model reports `has_timings: False` and a
  single `waveform` output, so case B's audio carries no per-sentence
  marks (`continuous=True` raises "needs a model that reports
  durations"). Without timings there is no way to slice the batched
  audio back into per-sentence pieces, which the read-along sync map and
  the sentence cache both need.
- B also returned 23.27 s where A returned 29.67 s for the same text:
  different pause handling, so it is not a drop-in even in principle.

Code facts (verified):

- `build.py` `_run_build_locked` (line 1419) renders chapters in a plain
  `for chapter in render_chapters:` loop (line 1599). Per chapter:
  `resolve_chapter`, `_fingerprint`, `chapter_up_to_date`, then
  `assemble_chapter(chapter, _emit_synth, voice_of=...)`,
  `apply_loudness_gain`, `encode_assembled_chapter` (ffmpeg subprocess),
  `_write_json`.
- Per-sentence audio goes through `tts/cache.get_or_synth` (line 165):
  content-keyed FLAC, written atomically (temp file plus `os.replace`,
  per-PID temp name). Concurrent writers are already safe; two writers
  of the same key just duplicate the work. `CacheStats` is a plain
  per-chapter counter object.
- The engine is built once (`create_engine`, build.py line 289,
  `(models_dir, *, device="auto") -> TTSEngine`) and shared by every
  `_synth` call.
- Bundle assembly runs after the loop and reads dicts keyed by chapter
  index, so it does not care in what order chapters finished.
- `run_build` (line 1278) already takes `stop_event: threading.Event`,
  `stop_file`, `progress_listener` and a test-injected `engine`.
- The `build` CLI command (cli.py line 533) already has `--device`,
  `--chapters`, `--events-jsonl`, `--stop-file`; one more option is
  local.
- `kokoro_onnx` runs the model at batch 1 (`[[0, *tokens, 0]]`, line
  127) and processes phoneme windows serially (`_create_batches`, line
  317). `create_stream` (line 348) is an asyncio producer/consumer over
  those serial windows: useful for streaming playback, not for
  parallelism.
- `device.create_engine_with_device` builds the session with no
  `SessionOptions` (line 169), so ORT uses its default thread pools.

## 3. Scope

| In | Out (later, or not this plan) |
| --- | --- |
| Chapter-level worker pool in one process, one shared engine | Multi-process orchestration, N bundles, lock changes |
| `--render-speed slow\|balanced\|fast` on `scribe build` | A raw `--jobs N` knob |
| Thread-safe counters, events and progress | ORT session-option tuning (phonemize is 2% of wall) |
| Keeping `slow` byte-identical to today | RT5 batching: gated on a model with duration output |
| RT1/RT5 probes and the recorded numbers | Player-side changes; bundle format (untouched) |

## 4. Key decisions (accepted 2026-10-18 unless noted)

1. **Threads, not processes.** One engine, one CUDA context, one copy of
   the model (1.1 GB VRAM) shared by every worker. On Windows a
   `ProcessPoolExecutor` spawns interpreters that each reload the ONNX
   models, and the per-book lock plus one-bundle-per-range rule would
   need rework. RT1 shows why threads pay: the 0.67 s fixed cost inside
   each `session.run` is host work that overlaps, and Python releases
   the GIL around `session.run`, numpy and the ffmpeg wait.
2. **The chapter is the unit of work.** A chapter already owns its two
   output files and its `CacheStats`; sentences stay serial inside a
   chapter, so no timing, ordering or assembly code changes.
3. **Events in completion order.** Accepted: sentence and chapter events
   are emitted under one lock as chapters finish, so in parallel mode
   the stream is no longer ordered by chapter number. `docs/api.md`
   gains one line saying so, and the UI jobs tray keys by chapter.
4. **Tiers: `slow` = 1, `balanced` = 4, `fast` = 8, with 12 as the hard
   ceiling.** Accepted as one `--render-speed` option with `balanced` as
   the default. `fast` is set to the measured knee (8): 12 was asked
   for, but 12 measured 3.2x against 8's 3.9x and used 4.4 of 6 GB VRAM.
   It is one constant, and a card with more VRAM may move the knee.
5. **No new dependency.** `concurrent.futures`, `threading` and
   `os.cpu_count` are stdlib.
6. **Byte equality is tested on the fake engine, not on CUDA.** The
   CUDA path already jitters by itself (a serial rerun differs on rare
   sentences, max sample delta up to 0.31, from a trim boundary moving).
   So RT2 proves exact equality with a deterministic fake engine, and
   the real-engine check in RT4 compares durations, sample counts and
   PCM against the measured jitter floor instead of demanding bytes.

## 5. Work packages

### RT1: Scaling probe (S) - DONE 2026-10-18

- `scribe/dev/probe_render_scaling.py` (throwaway, writes nothing).
  Results in section 2. Gate passed: threads are the right lever.
- Follow-up left open: a second look at why one `session.run` costs
  0.67 s of fixed time would be its own spike, not this plan's work.

### RT2: Chapter worker pool (M)

- Extract the per-chapter body of the loop (lines 1608 to 1756) into
  `_render_one_chapter(chapter, *, cast, engines, narrator, render_audio,
  render_text, strict, emit_sentence) -> ChapterOutcome`, calling the
  same functions in the same order. `ChapterOutcome` (dataclass) carries
  `index, title, hit, timed, plan, audio_path, audio_ms, stats,
  sentences, skipped, wall_s, error, stopped`.
- The parent keeps its loop shape but submits to a
  `ThreadPoolExecutor(max_workers=n)` and folds each outcome in as it
  completes into `timed`, `audio_paths`, `plans`, `chapter_audio_ms`,
  the cumulative counters, `stats_entries` and `log_lines`. Counters,
  `_emit` and the Rich progress updates go under one `threading.Lock`.
- Stop: `stopped_fn()` is already checked per sentence inside the
  worker's emit wrapper; on stop the worker unlinks its own partial
  `mp3_path` and `json_path` and returns `stopped=True`. The parent
  cancels pending futures and raises `BuildStoppedError` exactly as
  today. A `strict` chapter error cancels pending futures and raises.
- Tests (deterministic fake engine with a controlled sleep, injected via
  `run_build(engine=...)`):
  (a) four fake chapters at n=4 finish well under n=1 wall time;
  (b) every rendered MP3 and text JSON is byte-identical to a serial run
  (exact, because the engine is fake: see decision 6);
  (c) a stop mid-run leaves no partial chapter file and no bundle;
  (d) a failing chapter in non-strict mode is recorded and the rest
  still render, with no bundle.
- **Verify:** pytest green; one real 4-chapter A/B (n=1 versus n=4) on
  the same book recorded.

### RT3: `--render-speed` (S)

- One Typer option on `build`: `--render-speed` with values
  `slow|balanced|fast`, default `balanced`. Mapping in one place:
  `{"slow": 1, "balanced": 4, "fast": min(8, max(2, cpu_count() - 1))}`,
  as `workers_for_speed(speed: str) -> int`. Plumbed through `run_build`
  (new keyword `render_speed: str = "balanced"`) into
  `_run_build_locked`.
- The resolved worker count joins the existing `build start:` log line,
  so every render records its own mode.
- Tests: `workers_for_speed` unit test (20 cores gives 1/4/8; a one-core
  machine still gives a legal value); Typer rejects bad values.
- **Verify:** `scribe build --help` shows the option; pytest green.

### RT4: Measure the real build and record (S)

- A/B the real Hammer range at `slow` and at `fast`. Cheap to compare:
  the sentence cache is content-keyed and shared by both modes, so warm
  one mode first.
- Record wall time, core utilization, RAM, VRAM and the scaling in
  `docs/DECISIONS.md`, and add the one `docs/api.md` line about parallel
  event ordering. RT1 predicts about 29 min becoming about 8 to 9 min
  for this range; the log line proves it.
- **Verify:** numbers in `docs/DECISIONS.md`; tablet copy, import and
  read-along stay listed as needs device test.

### RT5 (gated, not this plan): batching windows

- Measured upside: 2.49x and 8x fewer `session.run` calls (section 2).
- Hard gate: the batched audio needs per-sentence marks, which needs a
  model that reports durations. The current `kokoro-v1.0.onnx` does not
  (`has_timings: False`, one `waveform` output); `continuous=True`
  raises. Clearing the gate means re-exporting with
  `scripts/export.py`, which changes the audio of every chapter, breaks
  the sentence cache and needs its own parity pass against the CPU path.
- Do not start this without RT4's numbers and the owner's go-ahead.

## 6. GPU utilization in fast mode (measured)

The GPU was never idle because the work was too small for it. It was
idle because one Python thread drives it: at T=1, 911 of about 930 ms in
each sentence sit inside `session.run`, and 0.67 s of that is per-call
host work (input copies, the 17 memcpy nodes the CUDA EP adds to the
graph, the output copy back, launch), leaving about 0.13 s of actual
compute per second of audio.

So "use the GPU harder" has exactly two mechanisms, and both are now
measured:

1. **Overlap the per-call overhead with concurrent callers.** This is
   RT2, and it is worth about 3.9x at 8 workers (the knee; 12 regresses
   and eats 4.4 of 6 GB VRAM). It needs no audio change.
2. **Give each call more audio to synthesize.** One 510-phoneme window
   costs about 0.67 s + 0.13 s per audio second, so fewer and longer
   calls are the only way to attack the 0.67 s. RT5 measures 2.49x for
   8 sentences in one call, and is blocked on the model's missing
   duration output.

Not worth doing, with reasons:

- **ORT thread tuning** (`intra_op_num_threads`, execution mode, IO
  binding): phonemize plus glue is 2% of wall time, so there is almost
  nothing on the CPU side to reclaim, and one shared session already
  means one ORT thread pool.
- **True GPU batching (batch > 1)**: the library runs batch 1 and its
  windows are serial; rolling our own means padding sentences to a
  common length and trusting the model to mask the padding, which would
  corrupt audio if it does not, plus a new timing and cache story.
- **fp16 or int8**: a different model file, so different audio, every
  sentence cache key moves, and the model is not the bottleneck.
- **CUDA graphs**: the CUDA EP already reports 17 memcpy nodes added to
  the graph, and per-call launch cost is small next to 0.67 s of host
  work.

## 7. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | RT1 (done) | know threads give 3.9x and the knee is 8 |
| 2 | RT2 | run a parallel render at the current default |
| 3 | RT3 | choose a tier per render |
| 4 | RT4 | have the real build numbers, and decide on RT5 |

Check-in after RT4, with the A/B numbers and the `docs/api.md` line.

## 8. Verification

- `uv run --no-sync pytest` in `scribe/` green.
- The two probe tables and the RT4 A/B table recorded.
- `uv run --no-sync scribe build` on a 4-chapter range at `slow` and at
  `fast`, then `scribe validate` on both bundles: same chapter list, same
  sentence counts, same durations, PCM inside the measured drift floor.
- No bundle-format change, so no `spec_version` bump and no spec edit.

## 9. Decisions taken (2026-10-18)

1. One `--render-speed {slow,balanced,fast}` option, not three switches.
2. Default `balanced`.
3. `fast` caps at 8 (the measured knee), ceiling 12. Asked for 12; 12
   measured slower.
4. Completion-order events accepted; `docs/api.md` gets the line and the
   UI jobs tray keys by chapter.
5. The book's `cast.yaml` fallback question is dropped; no cast change.
6. Byte-equality acceptance moved to the fake engine, because the CUDA
   path is not byte-reproducible by itself.
