# V4 Follow-ups Implementation Plan (issues #29, #32-#35)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Close the open-to-everyone follow-up issues from PR #36 that need no device: #29, #32, #33, #34, #35, plus the pre-existing licenseScan false positive. #30 (device matrix) and #31 (verification results comment) are out of scope.

**Architecture:** Small, independent fixes in the existing structure; each task names its files, its tests, and the issue checkbox it closes. Pure-JVM seams for Android logic, no Robolectric.

**Tech Stack:** Kotlin/Compose/Media3 1.5.1/Room (player), Python 3.11 + pytest (scribe), Rust (scribe-rs).

**Spec:** the issue bodies (#29, #32-#35 on github.com/Zyxavy/Auloud) and docs/plans/V4-Audit-2026-11-26.md; spec/bundle.md is law (do not change it).

**Branch:** feat/optimization-andriod12 (PR #36).

## Global Constraints

- minSdk 24: every API above 24 behind `Build.VERSION.SDK_INT >= N`. No `java.time`.
- No new dependency (Kotlin, Python or Rust). numpy and soundfile are already Scribe dependencies.
- core flavor stays Apache-2.0 with no sherpa code; full-only code stays in `src/full`.
- Bundle format and spec/bundle.md unchanged.
- No em-dashes and no arrow characters in code, comments or docs.
- One or more commits per task, subject `FU<n>: <summary>`, body may say `Part of #NN` or `Fixes #NN`. NO Co-Authored-By and no Claude attribution lines (owner rule). Stage only your files (never scribe/tmp/, bench_ort_profile_*.json).
- Player tests (from player/): `./gradlew.bat :app:testCoreDebugUnitTest --tests "<P>" :app:testFullDebugUnitTest --tests "<P>" --no-daemon -q`.
- Scribe tests (from scribe/): `uv run pytest <path> -q` (ui extra installed). Known unrelated failure: tests/test_packaging.py::test_force_include_covers_every_flat_module (owner's untracked scribe/tmp/).
- Rust: run cargo from PowerShell (Git Bash's link shadows MSVC link.exe): `cargo test --workspace`, `cargo clippy --workspace --all-targets -- -D warnings` in scribe-rs/.
- Never claim device behavior is verified; list "needs device test" items.

## Review Focus

1. API 24 Tab E behavior unchanged by every Player task.
2. Media session restriction (Task 5) must not cut off system UI, lock screen or Bluetooth controllers.
3. Room single instance (Task 4) must not change the DB file name or migrations.
4. Source-section checks (Task 10) must not hash every book on every library rescan.
5. Orchestrator timeouts (Task 11) must not kill a healthy slow CPU worker on a long batch.

---

### Task 1: FU1 build checks and manifest test tightening (#32 items, licenseScan)

**Files:** player/app/build.gradle.kts (licenseScan), player/app/src/test/java/app/auloud/player/ReleaseManifestTest.kt, player/app/src/main/AndroidManifest.xml (comment), player/app/src/main/java/app/auloud/player/storage/StoragePermissions.kt (KDoc), player/app/src/main/java/app/auloud/player/MainActivity.kt (requestPermission lambda).

- [ ] licenseScan: core fails because the dex marker "espeak-ng-data" matches the app's own folder-name constants (tts/KittenPack.kt:28, tts/ModelPacks.kt:26). Remove "espeak-ng-data" from dexMarkers; entry-name markers ("espeak" in file names) and the "com/k2fsa" / "onnxruntime" dex markers still prove the native libs. Update the comment block above the task. Verify: `:app:assembleCoreRelease :app:assembleFullRelease` then `:app:licenseScan` passes for both flavors.
- [ ] ReleaseManifestTest: add `playbackServiceElementHasMediaPlaybackType` and `renderServiceElementHasSpecialUseType` matching each `<service ...>` element by regex on its android:name (`.playback.PlaybackService` / `.render.RenderService`) and asserting its own foregroundServiceType; add `batterySettingsIntentIsQueryable` (IGNORE_BATTERY_OPTIMIZATION_SETTINGS).
- [ ] Stale comments: top manifest comment (WRITE auto-creates /Auloud only on API 24-29; API 30+ uses the app-specific dir, D-214); StoragePermissions KDoc ("Both are requested together on Android 6+" now API-gated).
- [ ] MainActivity requestPermission lambda: launch only when `toRequest(SDK_INT)` is non-empty and some entry is not granted (reuse the guard `requestStoragePermissionIfNeeded` uses; one shared private function, not a copy).

### Task 2: FU2 small Player correctness and test fixes (#32 items)

**Files:** receive/ReceiveServer.kt + ReceiveServerTest.kt; bundle test ChapterTextLoaderTest.kt; render SpoolRendererTest.kt; MainActivity.kt (insets).

- [ ] ReceiveServer: on the wrong-code path use `if (badGuesses.incrementAndGet() > MAX_BAD_GUESSES) return 429` (keep the early cap check for all codes). Test `parallelWrongGuessesNeverExceedCap`: 50 threads send wrong codes (badCodeDelayMs = 0), then assert the counter-visible outcome: at most MAX_BAD_GUESSES responses are 403, the rest 429.
- [ ] Rename test `dotDotTextPathIsUnavailable` to `dotDotTextPathIsInvalid`.
- [ ] SpoolRendererTest resume test: assert every resumed index entry's `samples` equals its spooled PCM length.
- [ ] MainActivity: move `windowInsetsPadding(WindowInsets.safeDrawing)` from the Surface modifier to a Box/Column inside the Surface so the bars area shows the surface color. Compile both flavors. Needs device test (API 35).

### Task 3: FU3 notice for a saved shared /Auloud folder on API 30+ (#32 item 1)

**Files:** storage/PrefsWatchFolderStore.kt (or wherever saved folders load), the library notice path (LibraryViewModel notices), a pure helper + test in storage tests.

Ruling: notice only, no file moves (the app cannot read the old shared folder on API 30+ without a SAF grant).
- [ ] Add pure `fun legacySharedFolderNeedsPick(sdkInt: Int, saved: WatchFolder, appDefault: String): Boolean` (true when sdkInt >= 30 and saved is a FilePath equal to the old shared default `<shared root>/Auloud` and not the app default). Tests: api29 false, api30 shared path true, api30 app-default false, SAF entry false.
- [ ] When true at startup, publish one library notice: "Your Auloud folder moved on Android 11+. Add it again in Settings (Add folder) to keep your books." Use the existing notice mechanism; do not block the library.

### Task 4: FU4 one Room instance per process (#33)

**Files:** data/AuloudDatabase.kt; MainActivity.kt:137, playback/PlaybackService.kt:130, render/RenderService.kt:130.
- [ ] Make `AuloudDatabase.open(context)` return a process-wide singleton (double-checked lock on applicationContext; same DB name, same migrations). Callers unchanged except they must not close it. Test (JVM, no Android): if open() cannot run on JVM, put the memoization in a small pure `internal class Once<T>(create: () -> T)` with a test that 8 threads get the same instance and create runs once.

### Task 5: FU5 restrict the media session to trusted controllers (#33)

**Files:** playback/PlaybackService.kt (onGetSession, onStartCommand extras), a pure policy + test.
- [ ] Add pure `fun allowController(controllerPackage: String, ownPackage: String, isTrusted: Boolean): Boolean` = own package or isTrusted (Media3 1.5.1 `MediaSession.ControllerInfo.isTrusted`; verify it exists in the cached aar ~/.gradle/caches/modules-2/files-2.1/androidx.media3/media3-session/1.5.1 before using; if it does not, report NEEDS_CONTEXT). Tests for own/trusted/untrusted.
- [ ] onGetSession returns null when not allowed. Do not touch the platform session token path (lock screen and Bluetooth use it). Needs device test: lock screen, notification, Bluetooth headset controls still work.
- [ ] onStartCommand: ignore EXTRA_BOOK_ID / EXTRA_SLEEP_OPTION / EXTRA_STREAM_CHAPTER unless the intent's component is this app (intents from other apps cannot target a non-exported... the service stays exported for Media3; check `intent.\`package\`` equals packageName OR document why it cannot be checked). If no reliable check exists without breaking Media3, skip this sub-item and say so.

### Task 6: FU6 library progress for stream-only books (#33)

**Files:** data/RoomProgressRepository.kt (loadAll ~L34), library/LibraryViewModel.kt (progressFraction ~L625).
- [ ] When a book's saved progress has `sentenceSid != null` and positionMs == 0, compute the fraction from sentence position over the book's sentence count if that count is already available without loading chapter JSON; otherwise hide the bar (null fraction) instead of showing 0%. Tests in LibraryViewModelTest for both branches. Constraint: never load chapter JSON for the library list.

### Task 7: FU7 EPUB cover sniff and one open zip (#33)

**Files:** ingest/EpubContainerReader.kt (findCoverEntry ~L905-938, readSpineBytes ~L156-217); EpubContainerReaderTest.kt.
- [ ] Sniff JPEG by reading only the first 2 bytes of the entry stream; try the declared cover / cover-image items first. Test `coverSniffReadsTwoBytesPerImage` via a counting seam (bytes read) on a 50-PNG fixture built in the test.
- [ ] Hold one ZipFile for the whole read instead of reopening per spine item; test that results are unchanged (existing tests) and add `manyChaptersOpenZipOnce` if a seam exists; otherwise keep to the refactor plus existing tests.

### Task 8: FU8 Wi-Fi receiver limits (#33)

**Files:** receive/ReceiveServer.kt, receive/ReceivePipeline.kt, data/RoomLibraryRepository.kt (importBundle cover); tests ReceiveServerTest, RoomLibraryRepository tests.
- [ ] Refuse uploads without Content-Length (411 Length Required) before parsing the body; test `chunkedUploadWithoutLengthIs411`.
- [ ] Before unpacking, require free space on the books root of at least 2x the declared length (File.usableSpace); pass the free-space reader as a parameter so the test can fake it; test `lowFreeSpaceIs507` (507 Insufficient Storage).
- [ ] importBundle: ignore a manifest cover whose path has a `..` segment or is absolute (treat as no cover); test.

### Task 9: FU9 SAF size and notification foreground start (#33)

**Files:** storage/SafBundleStorage.kt (sizeBytes via DocumentsContract COLUMN_SIZE query through the existing SAF backend seam), render/RenderService.kt pendingAction.
- [ ] SafBundleStorage.sizeBytes returns the document size (null on any failure); unit test through the existing fake backend (SafBundleStorageTest).
- [ ] pendingAction: `PendingIntent.getForegroundService` when SDK_INT >= 26, else getService (FLAG_IMMUTABLE kept). Compile; needs device test (Resume after force-stop on API 31).

### Task 10: FU10 Player source-section checks (#29)

**Files:** bundle/BundleValidator.kt (+ ChapterInfo source_index parsing if missing), bundle/ValidatorParityTest.kt (empty KNOWN_PLAYER_SUBSET), BundleValidatorTest.kt.
- [ ] Port SRC-SHA-NOT-HEX, SRC-FILE-MISSING, CHAPTER-SRC-* and SRC-IDX-* with the same wording as scribe/bundle/validate.py. Spec section 7 also requires the source sha256 to match: implement it with a streaming digest ONLY on the import paths that validate a new bundle once (ReceivePipeline, IngestPipeline); if LibraryViewModel.kt:361 validation runs on every rescan, it must skip the hash (add a parameter, default false) so books are not re-hashed on each launch. Report which call sites hash.
- [ ] Delete the four KNOWN_PLAYER_SUBSET entries; ValidatorParityTest passes with an empty map. Commit body: Fixes #29.

### Task 11: FU11 orchestrator framing, fail-fast and timeouts (#34 part 1)

**Files:** scribe-rs/scribe-orchestrator/src/{main.rs,lib.rs}.
- [ ] Move read_frame_sync, encode_frame and run_worker from main.rs to lib.rs (pub(crate) or pub as needed). Tests: zero length prefix is Err, 0xFFFFFFFF prefix is Err without allocating, truncated payload is Err.
- [ ] Shared Arc<AtomicBool> stop flag: a failing worker thread sets it; others check before each batch and stop. EOF maps to "worker N exited".
- [ ] Timeouts via a reader thread + recv_timeout (as scribe-ui/src/helper.rs wait_ready): startup 300 s, per batch 600 s; on timeout kill the child and fail with a message naming the worker. Make both durations constants; a test with tiny timeouts and a fake reader that never answers.

### Task 12: FU12 orchestrator process, results and contract tests (#34 part 2)

**Files:** scribe-rs/scribe-orchestrator/src/{supervisor.rs,main.rs}, scribe-rs/Cargo.toml, scribe/worker.py, Rust + Python tests.
- [ ] supervisor: assign the orchestrator's own process to the Job Object once before spawning (children inherit); a failure is a warn! (not silent); remove the per-child OpenProcess/assign and the expect in Supervisor::default.
- [ ] --results default = manifest parent dir joined with results.json; a results write failure fails the run (non-zero, error event).
- [ ] Add scribe-orchestrator to default-members.
- [ ] Contract tests: Rust test parses every spec/orchestrator/*.json example frame into the protocol types; Python test feeds each orchestrator-to-worker fixture through worker.py _classify. Change _classify to dispatch on frame["type"] (keep behavior for valid frames). Do NOT add a CI workflow (out of scope, say so).

### Task 13: FU13 Audacity export correctness and speed (#35)

**Files:** scribe/export/audacity.py, scribe/tests/test_audacity.py.
- [ ] Label times add the running chapter offset (sum of prior chapter durations). Test with a two-chapter fixture.
- [ ] Files and zip entries are named by speaker index (spk1.wav ...); the real speaker name appears only in label text and LOF XML, XML-escaped. Test with speaker 'A: B"C/D'.
- [ ] Replace per-sentence ffmpeg cuts: decode each chapter once (ffmpeg to WAV via one subprocess, or soundfile if it reads the format), slice segments with numpy, write one WAV per speaker with soundfile. Test asserts the number of ffmpeg subprocess calls equals the chapter count (monkeypatch the runner).

### Task 14: FU14 Scribe IO robustness (#35)

**Files:** scribe/tts/cache.py, scribe/ui/transfer.py, scribe/ui/app.py, scribe/extract/epub.py (or the upload path), tests.
- [ ] cache: decode outside _IO_LOCK, retry once on PermissionError; hold the lock only around os.replace. Test: N threads reading warm keys overlap (a fake decoder that records concurrent entries > 1).
- [ ] transfer record write/delete failures: log a warning and surface the error to the job's error field instead of silently returning success. Test with a read-only path (monkeypatch write to raise OSError).
- [ ] validate_destination / open_folder path checks run via asyncio.to_thread in the async handlers. Test: a monkeypatched slow exists does not block a concurrent /api/health.
- [ ] EPUB upload: sum ZipFile.infolist() file_size before ebooklib and refuse over 1 GiB (1 << 30) with a clear error. Test with a crafted zip whose declared sizes exceed the cap.
- [ ] Remove the dead golden write in tests/test_render_equivalence.py (~L192).

### Task 15: FU15 Scribe build timing without monkeypatching, and dev install note (#35)

**Files:** scribe/build.py (~L1640-1665 and its restore ~L2241), scribe/README (dev section).
- [ ] Replace runtime patching of assemble_chapter, encode_assembled_chapter and tts.cache._read_cached/_store_cached with a small timing context manager at the call sites (thread-safe accumulation with a lock). Existing timing output/log keys unchanged; test that two run_build calls in one process report independent timings and module globals are unchanged after a build.
- [ ] README: document that .venv/Lib/site-packages/scribe can be a stale non-editable copy and how to refresh it (`uv sync --reinstall-package auloud-scribe`, verify with the command you actually tested), or that `python cli.py` runs the source tree. Do not change packaging config.
