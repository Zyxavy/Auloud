# V4 Android 12 + Audit Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Player installs and works on Android 12 (API 31/32) and does not crash on 13 to 16, while API 24 (Tab E) keeps working unchanged; plus the must-fix and selected should-fix items from the 2026-11-26 audit, each with a test that fails when it breaks.

**Architecture:** Android-version behavior is guarded by `Build.VERSION.SDK_INT` at the few framework seams; every guarded rule is a pure function that takes `sdkInt` so it stays plain-JVM testable. No bundle-format change, no new dependency, no new module.

**Tech Stack:** Kotlin / Compose / Media3 1.5.1 / Room (player, AGP 8.7.3, compileSdk 36, targetSdk 36, minSdk 24); Python 3.11 + pytest (scribe); Rust (scribe-rs).

**Spec:** `docs/plans/V4-Audit-2026-11-26.md` (findings referenced as A#) and roadmap v4 item "Update targetSdk and handle newer background-service and notification permission rules" (`docs/04-Roadmap.md`), D-133.

**Branch:** `feat/optimization-andriod12` (from `dev/optimizations` at ff7d717).

## Global Constraints

- minSdk 24: every API above 24 behind `Build.VERSION.SDK_INT >= N`. No `java.time`.
- No new dependency (no Robolectric, no androidx.core additions, no getrandom crate).
- core flavor stays Apache-2.0 with no sherpa code; full-only files stay in `src/full`.
- Bundle format unchanged (`spec/bundle.md` untouched). Storage still goes through `BundleStorage`.
- Network: INTERNET stays LAN-receiver-only; no other network use.
- No em-dashes and no arrow characters in code, comments or docs.
- One commit per task by the implementer: subject `WPn: short summary`; NO Co-Authored-By or "Generated with Claude" lines (owner rule). Stage only the files you changed (never `git add -A`; never stage `scribe/tmp/`).
- Never claim device behavior is verified; list "needs device test" items.
- Player test command (from `player/`): `./gradlew.bat :app:testCoreDebugUnitTest --tests "<Pattern>" :app:testFullDebugUnitTest --tests "<Pattern>" --no-daemon -q` (testFull-only classes: run only the full task).
- Scribe test command (from `scribe/`): `uv run pytest <path>::<name> -q`.
- Rust (PowerShell, after MSVC Build Tools): `cargo test -p scribe-ui`, `cargo clippy --workspace --all-targets -- -D warnings` in `scribe-rs/`.

## Review Focus

1. Upgrade on API 24 (Tab E): manifest attributes and new guards must change nothing on API 24 to 29; default root stays `/sdcard/Auloud` there (WP3 test `defaultRoot_api29_keepsSharedAuloud`).
2. User denies POST_NOTIFICATIONS on API 33+: the library must still load (permission excluded from `allGranted`; WP3 test `allGranted_ignoresNotifications`).
3. Render resume tapped after the service was killed (background start refused on API 31+): job stays paused, no crash (WP2 test `foregroundStart_refused_returnsFalse`).
4. Correct pairing code sent after the wrong-guess cap: must still be refused (WP5 test `correctCodeRefusedAfterCap`).
5. Rename failure while finalizing a render: the previous `manifest.json` must survive (WP7 test `renameFailureKeepsTarget`).

---

## Phase A: Android 12+ (player)

### Task 1: WP1 Manifest for API 30 to 36 (A3, A4, A8 declaration)

**Files:**
- Modify: `player/app/src/main/AndroidManifest.xml`
- Modify: `player/app/src/test/java/app/auloud/player/ReleaseManifestTest.kt`
- Modify: `docs/DECISIONS.md` (append D-213)

**Interfaces:** Produces the permission names WP2 and WP3 rely on: `android.permission.POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_SPECIAL_USE`.

- [ ] Step 1: add tests to `ReleaseManifestTest`:
  - `playbackServiceDeclaresMediaPlaybackType`: manifest contains `android:foregroundServiceType="mediaPlayback"` and `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
  - `renderServiceDeclaresSpecialUseType`: contains `android:foregroundServiceType="specialUse"`, `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`, and `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`.
  - `ttsEnginesAreQueryable`: contains `<queries>` and `android.intent.action.TTS_SERVICE`.
  - `notificationPermissionDeclared`: contains `android.permission.POST_NOTIFICATIONS`.
  - `legacyStorageCappedAtApi29`: the WRITE_EXTERNAL_STORAGE element carries `android:maxSdkVersion="29"` and READ carries `android:maxSdkVersion="32"` (regex on the `<uses-permission` element text).
  Keep `expectedPermissionsPresent` as is (substrings still present).
- [ ] Step 2: run `--tests "*ReleaseManifestTest*"`; expect the 5 new tests FAIL.
- [ ] Step 3: edit the manifest:
  - permissions above; READ `maxSdkVersion="32"`, WRITE `maxSdkVersion="29"`;
  - `<queries>` with intents `android.intent.action.TTS_SERVICE` and `android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS`;
  - PlaybackService `android:foregroundServiceType="mediaPlayback"`;
  - RenderService `android:foregroundServiceType="specialUse"` with child `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="On-device text-to-speech rendering of a user's own audiobook" />`;
  - rewrite the two service comments (they say "no foregroundServiceType"; now: attributes are ignored below API 29/34, API 24 still uses plain startService).
  specialUse chosen over dataSync because dataSync has a 6 h per day cap on API 35+ and a book render can exceed it.
- [ ] Step 4: rerun; expect PASS. Also run `./gradlew.bat :app:processCoreDebugMainManifest :app:processFullDebugMainManifest --no-daemon -q` and expect success (manifest merge accepts the attributes at compileSdk 36).
- [ ] Step 5: append D-213 to DECISIONS.md (Date 2026-11-26, Status Accepted): new manifest entries, specialUse rationale, POST_NOTIFICATIONS is a runtime permission requested non-blocking (WP3), legacy storage permissions capped. Revisit when: Play Store distribution (specialUse needs a store declaration).

### Task 2: WP2 Render notification channel and safe foreground entry (A1, A9)

**Files:**
- Create: `player/app/src/main/java/app/auloud/player/render/RenderForeground.kt`
- Modify: `player/app/src/main/java/app/auloud/player/render/RenderService.kt` (`buildNotification` ~L1737-1776, `enterForeground` ~L1778-1790, `notifyDone` ~L1823-1840, `onCreate`, callers at ~L293 and ~L328)
- Test: `player/app/src/test/java/app/auloud/player/render/RenderForegroundTest.kt`

**Interfaces:**
- Produces: `object RenderForeground { const val CHANNEL_ID = "render"; fun tryStart(start: () -> Unit, onRefused: (Exception) -> Unit): Boolean }`. `tryStart` runs `start`; on `IllegalStateException` (superclass of API 31's `ForegroundServiceStartNotAllowedException`) or `SecurityException` calls `onRefused` and returns false; other exceptions propagate.

- [ ] Step 1: tests in `RenderForegroundTest`:
  - `foregroundStart_ok_returnsTrue`: start runs once, onRefused never called, returns true.
  - `foregroundStart_refused_returnsFalse`: start throws `IllegalStateException("not allowed")`; returns false; onRefused received that exception.
  - `foregroundStart_securityException_returnsFalse`.
  - `foregroundStart_otherError_propagates`: `IllegalArgumentException` is rethrown (assertThrows).
- [ ] Step 2: run `--tests "*RenderForegroundTest*"`; expect FAIL (unresolved).
- [ ] Step 3: implement `RenderForeground`. In RenderService:
  - `onCreate`: if `SDK_INT >= 26`, `NotificationManager.createNotificationChannel(NotificationChannel(RenderForeground.CHANNEL_ID, "Rendering", IMPORTANCE_LOW))` (idempotent).
  - Both builders: `if (SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)`; delete the two empty `SDK_INT >= 26` placeholder blocks.
  - `enterForeground(n): Boolean` = `RenderForeground.tryStart({ startForeground(NOTIFICATION_ID, n) }) { Log.w(...) }`; drop the `ForegroundServiceType` SuppressLint and its comment (the manifest now declares the type).
  - Callers: in `startRender` and `userResume`, if `enterForeground` returns false: do not launch `renderLoop`, leave the persisted job state as is (paused or not started), call `stopSelf()` and return.
- [ ] Step 4: rerun the test plus `--tests "*RenderService*"` (existing RenderServicePolicyTest/RenderServiceRestartTest); expect PASS.
- Needs device test: render notification visible with Pause/Cancel on API 31 and 34; resume after force-stop.

### Task 3: WP3 Storage and runtime permissions on API 30+ (A2, A8 request)

**Files:**
- Modify: `player/app/src/main/java/app/auloud/player/storage/StoragePermissions.kt`
- Modify: `player/app/src/main/java/app/auloud/player/storage/BooksRootResolver.kt`
- Modify: `player/app/src/main/java/app/auloud/player/MainActivity.kt` (L71-85, L401-408, L650-665)
- Test: `player/app/src/test/java/app/auloud/player/storage/StoragePermissionsTest.kt`, `BooksRootResolverTest.kt`
- Modify: `docs/DECISIONS.md` (append D-214)

**Interfaces:**
- Produces: `StoragePermissions.required(sdkInt: Int): Array<String>` (READ+WRITE when `sdkInt < 30`, else empty); `StoragePermissions.allGranted(sdkInt: Int, isGranted: (String) -> Boolean): Boolean`; `StoragePermissions.toRequest(sdkInt: Int): Array<String>` = `required(sdkInt)` plus `POST_NOTIFICATIONS` when `sdkInt >= 33`.
- Produces: `BooksRootResolver.defaultBooksRootFor(sdkInt: Int, sharedRoot: String?, appExternalFiles: String?, filesDir: String): String`; `defaultBooksRoot(context)` delegates with `Build.VERSION.SDK_INT`, `Environment.getExternalStorageDirectory()?.absolutePath`, `context.getExternalFilesDir(null)?.absolutePath`, `context.filesDir.absolutePath`.

- [ ] Step 1: tests:
  - `required_api29_readAndWrite`, `required_api30_empty`, `required_api33_empty`.
  - `allGranted_api30_trueWithoutAnyGrant` (`allGranted(30) { false }` is true).
  - `allGranted_ignoresNotifications`: `allGranted(33) { it != POST_NOTIFICATIONS }` is true.
  - `toRequest_api33_onlyNotifications` == setOf(POST_NOTIFICATIONS); `toRequest_api24_readWrite` == READ+WRITE.
  - update the two existing tests to pass `sdkInt = 24`.
  - `defaultRoot_api29_keepsSharedAuloud`: (29, "/storage/emulated/0", "/storage/emulated/0/Android/data/app.auloud.player/files", "/data/x") == "/storage/emulated/0/Auloud".
  - `defaultRoot_api30_usesAppExternalFiles`: same inputs with 30 == ".../Android/data/app.auloud.player/files/Auloud".
  - `defaultRoot_api30_noExternal_usesFilesDir`: (30, null, null, "/data/x") == "/data/x/Auloud".
  - `defaultRoot_api24_noShared_usesFilesDir`: (24, null, null, "/data/x") == "/data/x/Auloud".
- [ ] Step 2: run `--tests "*StoragePermissionsTest*" --tests "*BooksRootResolverTest*"` (repeat per task); expect FAIL.
- [ ] Step 3: implement; MainActivity: every `StoragePermissions.allGranted` call passes `Build.VERSION.SDK_INT`; launches use `toRequest(SDK_INT)` and launch only when that array is non-empty and some entry is not granted; the result callback computes `allGranted(SDK_INT) { grants[it] == true || checkSelfPermission(it) == GRANTED }` so a notification-only request never reports storage denied.
- [ ] Step 4: rerun; expect PASS. Grep `StoragePermissions.` across `app/src` to confirm no caller is left on the old signatures.
- [ ] Step 5: append D-214: on API 30+ the default books root is the app-specific external dir `Android/data/app.auloud.player/files/Auloud` (no permission; reachable by USB MTP and the Wi-Fi receiver); user folders use the existing SAF picker; MANAGE_EXTERNAL_STORAGE rejected (policy-restricted, broad access). API 24 to 29 unchanged. Consequence: uninstall deletes the default root on API 30+ (say so in the UI help later).
- Needs device test: fresh install on API 31: library loads, Wi-Fi receive lands, no permission dialog except notifications on 33+.

### Task 4: WP4 Edge-to-edge insets on API 35+ (A10)

**Files:** Modify `player/app/src/main/java/app/auloud/player/MainActivity.kt` (~L244).

- [ ] Step 1: change the root to `Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))` (imports from `androidx.compose.foundation.layout`; already on the classpath via material3). No other screen changes.
- [ ] Step 2: `./gradlew.bat :app:compileCoreDebugKotlin :app:compileFullDebugKotlin --no-daemon -q` succeeds. No unit test (layout only).
- Needs device test: API 35 emulator or device, top bar and bottom controls clear of system bars; API 24 unchanged.

## Phase B: audit must-fix (player)

### Task 5: WP5 Pairing lockout (A5)

**Files:** Modify `player/app/src/main/java/app/auloud/player/receive/ReceiveServer.kt` (L62-L85); Test `player/app/src/test/java/app/auloud/player/receive/ReceiveServerTest.kt`.

- [ ] Step 1: add `correctCodeRefusedAfterCap`: send `MAX_BAD_GUESSES + 1` wrong codes (badCodeDelayMs = 0, as `wrongCodeIsCappedAtTooManyAttempts` does), then a valid bundle with the correct code; expect status 429 and no bundle dir created under the books root.
- [ ] Step 2: run `--tests "*ReceiveServerTest*"`; expect the new test FAIL.
- [ ] Step 3: at the top of the code check: `if (badGuesses.get() >= MAX_BAD_GUESSES) return 429` for any code; keep increment plus sleep for wrong codes; delete `badGuesses.set(0)`. Update the comment (cap lasts for this server lifetime; reopening Receive makes a new server and a new code).
- [ ] Step 4: rerun; all ReceiveServerTest tests PASS (`wrongCodeIsCappedAtTooManyAttempts` still passes).

### Task 6: WP6 Release TTS engines before rebuilding the render graph (A6)

**Files:** Modify `player/app/src/main/java/app/auloud/player/render/RenderService.kt` (`onDestroy` L246-L260, `buildRenderGraph` L694).

- [ ] Step 1: extract `private fun releaseEngines()` holding the three existing try/release/null blocks from `onDestroy` (systemDriver.shutdown, sherpaEngine.release, kittenEngine.release); `onDestroy` calls it; `buildRenderGraph` calls it as its first statement. `rerenderGraph` (L460) must not be released while in use: only call `releaseEngines()` from `buildRenderGraph`, which runs when no graph is active.
- [ ] Step 2: run `--tests "*Render*"` on both flavors; expect PASS (behavior-preserving refactor plus one call).
- Needs device test: render, unplug and replug charger 3 times with the power guard on, check `adb shell dumpsys meminfo app.auloud.player` does not grow by about 170 MB per cycle.

### Task 7: WP7 atomicWriteText never deletes the live file (A7)

**Files:** Modify `player/app/src/main/java/app/auloud/player/render/RenderFinalize.kt` (L435-L472); Test `player/app/src/test/java/app/auloud/player/render/RenderFinalizeTest.kt`.

- [ ] Step 1: tests with a fake `RenderFileIo` (in-memory map; `renameTempToTarget` returns false the first N calls):
  - `renameFailureKeepsTarget`: target holds "old"; rename fails once then succeeds; result success; target == new text; `deleteIfExists(target)` never called.
  - `renameAlwaysFails_keepsOldTarget`: rename always false; result failure; target still "old"; temp deleted.
- [ ] Step 2: run `--tests "*RenderFinalizeTest*"`; expect FAIL.
- [ ] Step 3: delete the line `if (io.exists(targetPath)) io.deleteIfExists(targetPath)`. Check `RenderFileIo` real impl (`RenderStateStore.kt:90`) and `AudioEncoder.kt:358`: if either uses `File.renameTo`, keep it (Android replaces the target); if a JVM test on Windows needs replace semantics, use `java.nio.file.Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` with a fallback to plain `REPLACE_EXISTING` (desugared NIO is available, D-085).
- [ ] Step 4: rerun all `*Render*` tests; expect PASS.

## Phase C: audit should-fix (player)

### Task 8: WP8 Chapter text read guard (A11)

**Files:** Modify `player/app/src/main/java/app/auloud/player/bundle/ChapterTextLoader.kt` (`load`); Test `.../bundle/ChapterTextLoaderTest.kt`.

**Interfaces:** Consumes `BundleStorage.sizeBytes(path): Long?` (null = unknown, allowed). Cap: reuse the validator's text cap constant (grep `BundleValidator` for the 8 MB value; reference it, do not duplicate the number).

- [ ] Step 1: tests with a fake BundleStorage: `oversizeTextIsInvalidWithoutRead` (sizeBytes = cap + 1; result is `ChapterTextInvalid`; readText never called); `dotDotTextPathIsUnavailable` (`"/b/text/../../x.json"`; result `ChapterTextInvalid`; readText never called); `unknownSizeStillLoads` (sizeBytes null, valid JSON, success).
- [ ] Step 2: FAIL, Step 3: implement (segment check `textPath.split('/').any { it == ".." }` before exists), Step 4: PASS.

### Task 9: WP9 Ingest hardening for PDF and EPUB XML (A12)

**Files:** Modify `player/app/src/main/java/app/auloud/player/ingest/PdfText.kt` (L54-L64), `ingest/EpubContainerReader.kt` (`parseXml`/`newDocumentBuilder` ~L465-L501); Tests `PdfTextTest.kt`, `EpubContainerReaderTest.kt`.

- [ ] Step 1: tests: `EpubContainerReaderTest.entityDeclarationIsRejected` (an EPUB whose OPF has `<!DOCTYPE x [<!ENTITY a "aaaa">]>`; read returns the reader's failure type, no exception); `EpubContainerReaderTest.plainDoctypeWithoutEntitiesStillReads` (XHTML DOCTYPE without ENTITY still parses); `PdfTextTest.oversizePdfIsRefusedBeforeLoad` (size check through whatever seam PdfText exposes; if only a File is accepted, extract `internal fun checkPdfSize(lengthBytes: Long): String?` returning an error message above `MAX_PDF_BYTES = 100L * 1024 * 1024`).
- [ ] Step 2: FAIL. Step 3: implement: reject XML text containing `<!ENTITY` before parsing; `PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())` plus the size check. Step 4: PASS (all `*Epub*`, `*Pdf*`, `*Ingest*`).

### Task 10: WP10 Sherpa engines thread safety (A13) (full flavor)

**Files:** Modify `player/app/src/full/java/app/auloud/player/tts/SherpaPiperEngine.kt`, `SherpaKittenEngine.kt`; Tests `player/app/src/testFull/java/app/auloud/player/tts/SherpaPiperEngineTest.kt`, `SherpaKittenEngineTest.kt` (use existing `FakePiperHandle`/`FakeKittenHandle`).
Also check the core-flavor stubs in `src/core/.../tts/` compile unchanged.

- [ ] Step 1: tests: `releaseWaitsForInFlightGenerate` (fake handle whose generate blocks on a latch; start synthesize on a thread, call release on another, assert handle.release was not called before generate returned); `concurrentFirstLoadOpensOneHandle` (two threads synthesize the same voice; openHandle invoked once).
- [ ] Step 2: FAIL. Step 3: guard load+generate and release with one lock (`synchronized(lock)`; synthesize is suspend but the JNI call is blocking, so a plain monitor is fine); after release, synthesize throws `IllegalStateException("engine released")`. Step 4: PASS with `:app:testFullDebugUnitTest --tests "*Sherpa*"`.

### Task 11: WP11 Spool index write throttle (A14) (performance test)

**Files:** Modify `player/app/src/main/java/app/auloud/player/render/SpoolRenderer.kt` (persist after each sentence ~L240-L247, L312); Test `.../render/SpoolRendererTest.kt` (use `SpoolTestFakes.kt`).

**Interfaces:** `internal const val SPOOL_PERSIST_EVERY = 25`.

- [ ] Step 1: tests: `indexWritesAreThrottled` (render a 100-sentence chapter with fakes; count writes to the index path; assert <= 100 / SPOOL_PERSIST_EVERY + 2); `indexPersistedOnCancel` (cancel after 30 sentences; the last persisted index lists all 30); keep existing resume tests green.
- [ ] Step 2: FAIL. Step 3: persist when `count % SPOOL_PERSIST_EVERY == 0` and on cancel, fail and finish; write via the existing `atomicWriteText` (WP7) instead of in-place. Step 4: PASS (`*Spool*`, `*Render*`).

### Task 12: WP12 Stream failure loop cap (A15)

**Files:** Modify `player/app/src/main/java/app/auloud/player/playback/PlaybackService.kt` (`onStreamFailed` L1118-L1128, the sid callback); Create `playback/StreamFailureGate.kt`; Test `playback/StreamFailureGateTest.kt`.

First confirm in `tts/StreamCore.kt` that no existing retry limit covers this; if one does, skip the task and record that in the report.

**Interfaces:** `class StreamFailureGate(private val maxInARow: Int = 2) { fun onFailure(): Boolean /* true = reload allowed */; fun onSpoken() }`.

- [ ] Step 1: tests: first failure allows reload; second in a row refuses; `onSpoken()` resets so the next failure allows again.
- [ ] Step 2: FAIL. Step 3: `onStreamFailed` calls `loadBook` only when `gate.onFailure()`; otherwise publish `STREAM_FAILED_MESSAGE` and stop. Call `gate.onSpoken()` from the first sid callback of a stream. Step 4: PASS (`*Stream*`, `*Playback*`).

### Task 13: WP13 Player validator: consecutive chapter index + parity test (A16)

**Files:** Modify `player/app/src/main/java/app/auloud/player/bundle/BundleValidator.kt` (manifest checks near L109-L175); Create `player/app/src/test/java/app/auloud/player/bundle/ValidatorParityTest.kt`.

- [ ] Step 1: tests: `BundleValidatorTest.nonConsecutiveChapterIndexIsRejected` (indexes 1,3) and `duplicateChapterIndexIsRejected` (2,2); message names `manifest.json` and `index`. `ValidatorParityTest`: for each `parity/expected/<name>.json` with a matching fixture dir under `spec/fixtures/<name>` (find the real fixture root with `ls spec/fixtures`; path from the module dir is `../../`), assert `BundleValidator.validateBundle(dir).isEmpty() == expected.valid`, except names listed in a `KNOWN_PLAYER_SUBSET` set with a one-line reason each (start empty; add only rules the spec allows the Player to skip, and say so in the report).
- [ ] Step 2: FAIL. Step 3: add `chapter.index == position + 1` check with the same wording as `scribe/bundle/validate.py`. Step 4: PASS (`*Bundle*`, `*Golden*`, `*Parity*`).

## Phase D: Scribe (python)

### Task 14: WP14 open-folder only opens folders (A17)

**Files:** Modify `scribe/ui/transfer.py` (`open_folder`, L435-L461); Test `scribe/tests/test_ui_transfer.py`.
- [ ] Step 1: test `test_open_folder_refuses_file`: a temp file path; `open_folder` raises/returns the module's existing error shape; `os.startfile` (monkeypatched) never called. Step 2 FAIL. Step 3: require `target.is_dir()`. Step 4 PASS plus `uv run pytest tests/test_ui_transfer.py -q`.

### Task 15: WP15 Helper prints ready only once listening (A18)

**Files:** Modify `scribe/ui/helper.py` (`run_helper`, L163-L187); Test `scribe/tests/test_helper.py`.
- [ ] Step 1: test `test_ready_line_port_is_bound`: run the helper the way existing helper tests do (skip without the ui extra, same guard as D-212); after reading the ready JSON, a TCP connect to the port succeeds immediately. Step 2 FAIL or flaky-fail. Step 3: create and bind one socket (127.0.0.1, port 0), read its port, print ready, then `uvicorn.Server(config).run(sockets=[sock])`. Step 4 PASS.

### Task 16: WP16 SSE stream reads only new bytes (A19) (performance test)

**Files:** Modify `scribe/ui/app.py` (L978-L1029); Test `scribe/tests/test_ui_jobs.py` (or the file holding current SSE tests: grep `events_path` in tests).
- [ ] Step 1: test `test_sse_tail_reads_incrementally`: write 20,000 event lines, open the stream, append 1 line; assert the new event arrives and the generator's read count of bytes for the second poll is under 1 KB (patch the reader helper to count bytes) . Step 2 FAIL. Step 3: keep a byte offset per stream; `open(..., "rb")`, `seek(offset)`, read, split complete lines only (keep a partial tail); keep the `id:` numbering identical. Step 4 PASS plus all SSE tests.

### Task 17: WP17 Diagnose baseline failure test_kill_resume_identical_bundle

**Files:** `scribe/tests/test_build.py:L180-L215`, `scribe/build.py` resume/skip logic.
- [ ] Use superpowers:systematic-debugging. Reproduce with `uv run pytest tests/test_build.py::test_kill_resume_identical_bundle -q`. Find why the resumed build reports `(skipped, rendered) == (0, 3)` instead of `(1, 2)` (likely the rebuild fingerprint from D-211 or gain/assembly changes in a48ddbb invalidating chapter 1). Fix the root cause in code if the skip logic regressed; change the test only if the new behavior is intended per DECISIONS (cite the entry). Then run the full `uv run pytest -q` and report the count.

## Phase E: Rust (needs MSVC Build Tools)

### Task 18: WP18 Unguessable helper token + logs off stdout (A20)

**Files:** Modify `scribe-rs/scribe-ui/src/helper.rs` (`generate_token`, L141-L161); `scribe-rs/scribe-orchestrator/src/main.rs` (tracing init ~L81).
- [ ] Step 1: tests in helper.rs: `token_is_32_hex_chars`, `tokens_differ_across_calls` (1,000 calls, all distinct). Step 2: run; the length test may already pass; distinctness passes today too, so the real gate is review: token bits must come from `std::collections::hash_map::RandomState::new()` (OS-seeded per instance) hashed twice with different inputs into 128 bits, not pid/clock. Step 3: implement; tracing `.with_writer(std::io::stderr).with_ansi(false)`. Step 4: `cargo test -p scribe-ui`, `cargo build -p scribe-orchestrator`, `cargo clippy --workspace --all-targets -- -D warnings` PASS.

## Phase F: final verification (orchestrator)

- [ ] `player/`: full unit tests both flavors; `:app:lintCoreDebug :app:lintFullDebug`; `:app:assembleCoreDebug :app:assembleFullDebug :app:licenseScan`.
- [ ] `scribe/`: `uv run pytest -q` (full).
- [ ] `scribe-rs/`: `cargo test --workspace`, clippy.
- [ ] Whole-branch ponytail-review.
- [ ] Roadmap v4: leave the targetSdk box `[~]` (code done, device test pending); never `[x]` without a device run.
- [ ] Device test list for the user (API 24 Tab E regression + an API 31/32 device or emulator, plus API 34+ for FGS types).
