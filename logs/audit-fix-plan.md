# Audit fix plan (2026-10-10, all 20 findings)

Source: the read-only audit of the same day (player + scribe + spec +
docs). Load assumed: one maintainer, one tablet, one localhost server.
Every item below has a concrete file:line case in the audit; `shortcut:`
comments naming their limit are decisions, not work. One work package
per session; agent rules (`AGENTS.md`, slice-workflow) apply throughout.

## Goal

Clear all 20 findings with the smallest diffs that fully work, preferring
deletion, with a JVM test (or pytest) pinning each behavior fix. No new
dependency, permission, or bundle-format change without asking first.

**Done when:** every box ticked, Player suites green in both flavors plus
lint clean, `uv run pytest` plus `ruff` clean in `scribe/`, and no finding
marked "deferred" without a `DECISIONS.md` entry saying why.

## FP1: stream playback correctness (Must: findings 1, 2)

- [x] **Sleep timer on streams.** `SleepTimer` reads sentence index/count
  as milliseconds (`PlaybackService.kt` sleep loop, `SleepTimer.kt:76-102`):
  End-of-chapter shows a few hundred "ms" and `fadeVolume()` pins volume
  near zero for the whole chapter. Convert stream progress to estimated
  milliseconds before calling the timer, or refuse End-of-chapter on the
  stream path. Verify: JVM tests on the conversion/refusal; device: one
  streamed chapter on End-of-chapter plus a minute option.
- [x] **Wake lock timeout.** `StreamPlayer.kt:276` acquires 10 minutes;
  chapters run longer. Call `acquire()` with no timeout (every path
  already releases). Verify: code inspection plus the ST7 screen-off
  round on a 15+ minute chapter.

## FP2: library and progress safety (Must 3-4, Should 7-9)

- [x] **Save throttle advances before the write** (`PlaybackService.kt:978-1011`).
  Advance `lastSaveUptimeMs` only on write success; surface consecutive
  failures through the notice channel. Verify: JVM tests (fake failing
  store: throttle holds, notice fires); device: full-disk listen is
  aspirational, note if skipped.
- [x] **Delete drops the row on failed folder delete** (`RoomLibraryRepository.kt:84-90`,
  `LibraryViewModel.kt:450-481`). Verify the folder is gone before the
  row delete; remove book plus progress rows in one Room transaction.
  Verify: JVM tests (failing storage double, kill-between-steps case).
- [x] **Same id in two folders flaps the row** (`RoomLibraryRepository.kt:60-73`).
  Key imports by `(bundlePath, id)` or skip-and-report mismatches.
  Verify: JVM tests (internal plus SAF copy of one id).
- [x] **Rescan check-then-set race** (`LibraryViewModel.kt:253-261`). Guard
  with a `Mutex` across the whole scan. Verify: JVM test (two rapid
  rescans, one runs).
- [x] **Progress N+1 per library emission** (`LibraryViewModel.kt:245,519-538`).
  Debounce emissions during rescan or add one `loadAll()` map query.
  Verify: JVM test counting loads over a 3-book import; cold-start feel
  on device is a bonus note.

## FP3: reader and player UI cost (Should 13-14, Nice 16-17)

- [x] **Sleep button forgets the timer on rotation** (`PlayerScreen.kt:122,160-162`).
  Derive the cycle base from `state.sleepRemainingMs`. Verify: JVM test
  on the base derivation (rotation itself is device-only).
- [x] **Scroll hit-tests run where results die** (`ReaderScreen.kt:205-224`,
  `ReaderViewModel.kt:135-136`). Key the effect on reader mode; skip
  unless Read. Verify: inspection plus fling feel on device.
- [x] **Tap-confirm rebuilds the sentence list per recomposition**
  (`ReaderScreen.kt:142-159`). Hoist into `remember(chapter, pending)`.
  Verify: inspection (no behavior change to test).
- [x] **Two identical mm:ss formatters** (`PlayerScreen.kt:434-440`,
  `PlaybackButtons.kt:53-59`). One shared `formatMmSs(ms)`, one JVM test.

## FP4: import validation and recovery (Should 10, 15)

- [x] **Text cap counts chars after full read** (`BundleValidator.kt:240`).
  Enforce on bytes before reading. Verify: JVM tests (7M-char CJK
  input, oversize file short-circuits the read).
- [x] **Unreadable manifest orphans per-chapter temps**
  (`RenderRecovery.kt:101-124`, `StrayTempSweep.kt:55-73`). Sweep
  `audio/*.tmp` and `text/*.tmp` by directory listing on that path.
  Verify: JVM tests (kill-mid-finalize fixture with broken manifest).

## FP5: Scribe web server (Must 5, Should 6)

- [x] **Uploads held whole in RAM, placed non-atomically**
  (`scribe/ui/app.py:494-533`). Cap the size, stream to disk in chunks,
  create with `O_CREAT|O_EXCL` plus retry. Verify: pytest (oversize
  refused, same-name concurrency, partial-file draft refused).
- [x] **Resume deletes a stored absolute path unchecked**
  (`scribe/ui/jobs.py:996-1002`). Re-run destination validation plus a
  dirname-match check before any delete. Verify: pytest (moved-drive
  job refuses instead of deleting).

## FP6: Scribe bundle and build (Should 11-12, Nice 18-19)

- [x] **Source hash loads the whole file** (`scribe/bundle/validate.py:815`).
  Stream in 1 MB chunks like `draft.sha256_of_file`. Verify: pytest
  with a large source fixture (or a size-threshold unit test).
- [x] **Bundle writes land in the output dir directly**
  (`scribe/bundle/writer.py:151-162`). Write to a sibling tmp dir,
  rename on success. Verify: pytest (killed rebuild keeps the old
  bundle valid).
- [x] **Dead `_narrator_voice` wrapper** (`scribe/build.py:543-545`).
  Delete it. Verify: full pytest green.
- [x] **TypeError back-compat masks engine bugs** (`scribe/build.py:654-660`).
  Update the 3 single-arg doubles (`test_build.py:405`,
  `test_voices.py:225,239`) to two-arg, delete the branch. Verify:
  full pytest green plus a genuine-TypeError regression test.

## FP7: public docs (Nice 20, plus the OSS wording pass)

- [x] **README status frozen** (`README.md:5`): rewrite the banner for
  v2.1.0 (streaming shipped behind the accepted gate), fix the sideload
  version, add a short Contributing section (DCO per D-127, license
  map, test commands). Verify: read-through by a second person.
- [x] **Owner-centric wording**: `OWNER-RUN` becomes device-run
  (`docs/ReleaseSigning.md`, `docs/07-TestPlan.md`); machine-specific
  key paths become placeholders; `AGENTS.md` v2 accuracy (on-device
  TTS/rendering exist now, fix the stale plan path and APK name).
  History (`test-log.md`, `DECISIONS.md`, `docs/archive/`) and legal
  files (`SOURCE_OFFER.txt`) stay verbatim. Verify: grep for
  `OWNER-RUN`, `D:\keys`, and v1-only claims comes back clean on
  living docs.

## Order

FP1 first (bites during long screen-off listens), then FP2 and FP5
(data loss), FP4 and FP6, FP3, FP7 with everything. One commit per
package (`FPn: short summary`); decisions and surprises in
`docs/DECISIONS.md`.

## Definition of done

All boxes ticked; Player `:app:testCoreDebugUnitTest`
`:app:testFullDebugUnitTest` plus `:app:lintCoreDebug`
`:app:lintFullDebug` green; scribe `uv run pytest` plus `ruff check`
clean; device rounds (FP1 wake/sleep, FP2 full-disk aspirational)
recorded in `docs/test-log.md` or explicitly waived by the maintainer.
