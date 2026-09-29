# Slice 1 Implementation Plan: Player Shell

Project: Auloud Player (Android). Target: Samsung Galaxy Tab E, Android 7.1.1 (`minSdk 24`). Follows `04-Roadmap.md` (Slice 1), `06-PlayerDesign.md`, `03-BundleSpec.md`.

## 1. Goal

Import a hand-made bundle and play its chapters with the screen off, with correct resume and system controls. No text display yet (that is Slice 3).

**Done when** (from the roadmap):

- The test bundle plays end to end with the screen off for 30+ minutes
- Closing and reopening the app resumes at the same spot
- Lock screen controls work

## 2. Scope

| In | Out (later slices) |
| --- | --- |
| Import a bundle folder, parse `manifest.json` | Reading `text/*.json` (Slice 3) |
| Library screen (cover, title, progress) | Reader view, highlight, modes (Slice 3) |
| Foreground media service, chapter playlist | Speed control, sleep timer (Slice 3) |
| Play, pause, seek, next/previous chapter | PDF pages (Slice 5) |
| Notification and Bluetooth controls | Chapter list screen (Slice 5) |
| Save and restore position |  |
| First-run battery-optimization prompt |  |

## 3. Prerequisites (Slice 0)

- Tab E in developer mode with USB debugging; `adb devices` lists it
- Test bundle on the microSD card: 2 chapters, valid `manifest.json`, MP3s per `03-BundleSpec.md` section 9
- Android Studio, JDK 17, and an Android project with `minSdk 24`

## 4. Work packages (in order)

### WP1: Project setup (S)

- Package name (for example `app.auloud.player`); single `app` module; Kotlin, Compose, version catalog
- Dependencies: Media3 (`exoplayer`, `session`), Room (+ KSP), kotlinx.serialization, Coil, coroutines; test: JUnit, Turbine, MockK
- Permissions in the manifest: `READ_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `WAKE_LOCK`
- Before pinning versions, check each library's release notes that it still supports `minSdk 24`
- **Verify:** empty app installs and launches on the Tab E

### WP2: Bundle models and parser (S)

- `Manifest`, `ChapterInfo`, `AudioInfo`, `SourceInfo` as `@Serializable` classes; `ignoreUnknownKeys = true`
- `BundleParser.parse(dir): Result<Manifest>`; `BundleValidator` checks required fields, that each chapter's MP3 exists, and that `duration_ms` is positive
- **Verify (unit tests):** valid manifest parses; missing field, missing MP3, bad JSON and extra unknown keys behave as specified

### WP3: Storage layer (S)

```kotlin
interface BundleStorage {
    fun listBundleDirs(root: String): List<String>
    fun readText(path: String): String
    fun exists(path: String): Boolean
    fun audioUri(bundleDir: String, relPath: String): Uri
}
```

- `FileBundleStorage` implementation using file paths (the SAF version comes in v3)
- Runtime permission request for `READ_EXTERNAL_STORAGE` (needed on Android 6+)
- Settings: "books folder" path (default: microSD root `Auloud/`, or internal `Auloud/`)
- **Verify:** app lists the test bundle's folder from the microSD card (test that SD paths are readable on your unit)

### WP4: Database and repositories (S)

- Entities: `BookEntity(id, title, author, bundlePath, coverPath, durationMs, addedAt)`, `ProgressEntity(bookId, chapterIndex, positionMs, updatedAt)`
- DAOs and `LibraryRepository` (`importBundle`, `books(): Flow`), `ProgressRepository` (`save`, `load`)
- Import upserts by manifest `id`; a bundle whose folder disappears is marked missing, not deleted
- **Verify:** instrumented test: import twice, one book row; progress round-trips

### WP5: Library screen and import (M)

- `LibraryViewModel`: scan the books folder, show imported books, "Rescan" button
- Book row: cover (Coil, placeholder if none), title, author, progress bar
- Tap a book opens the player screen (WP7)
- Empty and error states (no permission, no books, invalid bundle with the reason)
- **Verify:** the test bundle appears with correct title; a corrupt bundle shows an error, not a crash

### WP6: Playback service (M) *(the core of the slice)*

```kotlin
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        session = MediaSession.Builder(this, player).build()
    }
    override fun onGetSession(info: MediaSession.ControllerInfo) = session
    override fun onDestroy() { session?.player?.release(); session?.release(); super.onDestroy() }
}
```

- Declare the service in the manifest with the Media3 session intent filter
- Load a book: one `MediaItem` per chapter (URI, title, book title as artist, cover as artwork) via `setMediaItems(items, chapterIndex, positionMs)`
- Notification through Media3's default provider (play/pause, previous, next)
- Save progress: every 5 s while playing, and on pause, on chapter change (`onMediaItemTransition`), and in `onDestroy`
- Stop at the end of the book; mark finished
- **Verify:** start playback, lock the screen, audio continues; notification controls work; Bluetooth media buttons work (if available)

### WP7: Player screen and controller (M)

- `PlaybackController`: wraps `MediaController` (via `SessionToken`), exposes `StateFlow<PlaybackState>` (isPlaying, chapterIndex, chapterTitle, positionMs, durationMs)
- UI: cover, book and chapter titles, seek bar (chapter position), play/pause, previous/next chapter, a position ticker about every 500 ms
- Restore: on opening a book, read `ProgressEntity` and start the service from that chapter and position (paused)
- **Verify:** seek and chapter buttons behave; leaving and returning to the screen keeps the session

### WP8: Battery-optimization prompt (S)

- On first playback, show a dialog explaining why; deep link to the battery optimization settings (`Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` or the app details screen as fallback); remember that it was shown
- Add a "Help" entry in Settings to reopen it
- **Verify:** dialog appears once; leads to a real settings screen on the Tab E (Samsung menus vary, so document what you find)

### WP9: Debug overlay and logging (S)

- Debug build only: current chapter, `positionMs`, player state, last progress save time
- Log service lifecycle (create, destroy, task removed) to help debug Samsung service kills
- **Verify:** overlay values match what you hear

### WP10: Device testing and hardening (M)

Run the Slice 1 rows of `07-TestPlan.md`:

- [ ] 30+ minutes of screen-off playback (then extend to 2 hours)
- [ ] Resume after app restart and after tablet reboot
- [ ] Lock screen and notification controls
- [ ] Swipe from recents: does playback continue? Record the result
- [ ] Phone-style interruptions: unplug headphones, other app takes audio, Bluetooth disconnect
- [ ] Corrupt or missing chapter file: skipped with a message, no crash
- [ ] Memory: no growth over an hour

## 5. Suggested build order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | WP1, WP2 | see the manifest parsed in a unit test |
| 2 | WP3, WP4 | list bundles from the microSD card |
| 3 | WP6 (with a hard-coded book) | hear audio with the screen off, the most important risk retired early |
| 4 | WP5, WP7 | full flow from library to player |
| 5 | WP8, WP9 | prompt and debugging aids |
| 6 | WP10 | pass the "done when" list |

Doing WP6 early on purpose: the biggest unknown is whether Samsung's Android 7 keeps the service alive, so find out before building the rest.

## 6. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Compose feels sluggish on the low-end Tab E | Slice 1 UI is simple; measure now. If scrolling or navigation lags, switch screens to the classic View system before Slice 3's reader gets heavy |
| Samsung kills the service | Foreground service, wake lock, battery prompt, and logs from WP9; record which setting fixes it |
| SD card path not readable | Test in WP3 first; fallback: import (copy) bundles to internal storage, or use SAF |
| Library versions dropping API 24 support | Check release notes when pinning versions; pin, don't float |
| MP3 seek inaccuracy | Test bundle must be CBR (per spec); test seeking at several points |
| Only 8 GB internal storage | Keep bundles on the microSD card; do not copy them in by default |

## 7. Definition of done for Slice 1

- [ ] All WP verifications passed
- [ ] The three "done when" conditions above met on the Tab E
- [ ] Debug overlay and logs confirm no service restarts during a 30-minute screen-off run
- [ ] Any decisions or surprises logged in `DECISIONS.md`
- [ ] Tagged in git (for example `slice-1`)

## 8. Next

Slice 2 (Scribe, single voice) can start in parallel once `03-BundleSpec.md` is frozen, since it only needs to produce what the Player already reads.