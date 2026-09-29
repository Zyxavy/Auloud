# Player Design (Android)

Target: Samsung Galaxy Tab E, Android 7.1.1 (`minSdk 24`). Kotlin, Jetpack Compose, Media3. License: Apache-2.0 (or MIT).

## 1. Principles

- A player, not a converter: no models, no EPUB parsing. It reads bundles (`03-BundleSpec.md`).
- Low memory: stream audio, load one chapter's JSON at a time.
- Playback lives in a service; the UI is a client of it.
- One source of truth for position: the player's `positionMs` plus the chapter index.

## 2. Structure

Single activity, Compose UI, MVVM, manual dependency wiring (add Hilt later only if needed).

```
app/
  data/
    bundle/     BundleModels.kt, BundleParser.kt, BundleValidator.kt
    storage/    BundleStorage.kt (interface), FileBundleStorage.kt
    db/         AppDatabase, BookEntity, ProgressEntity, SettingsEntity, Daos
    repo/       LibraryRepository, ProgressRepository, SettingsRepository
  playback/
    PlaybackService.kt   (MediaSessionService)
    PlaybackController.kt (client wrapper for the UI)
    SleepTimer.kt
  ui/
    library/    LibraryScreen, LibraryViewModel
    reader/     ReaderScreen, ReaderViewModel, ReaderState, SentenceIndex
    chapters/   ChapterListScreen
    settings/   SettingsScreen
    theme/
  util/
```

## 3. Data

**Bundle models** (kotlinx.serialization): `Manifest`, `ChapterInfo`, `ChapterText`, `Block`, `Sentence`, `Span`, `PageMark`. Unknown JSON keys are ignored.

**Room**

- `BookEntity(id, title, author, type, bundlePath, coverPath, durationMs, addedAt)`
- `ProgressEntity(bookId, chapterIndex, positionMs, mode, updatedAt)`
- `SettingsEntity` (or DataStore): speed, font size, theme, sleep timer default, last mode.

**Storage abstraction** `BundleStorage { list(); open(path): InputStream; uriFor(path): Uri }`. v1 implementation uses file paths with the legacy read-storage permission (works on API 24, including microSD). Keeping it behind an interface means v3 can swap in Storage Access Framework for newer Android versions without touching the rest.

## 3b. Import

1. User picks a folder (or the app scans a configured books folder).
2. `BundleParser` reads `manifest.json`, `BundleValidator` runs light checks (files exist, chapters listed, durations plausible).
3. Insert or update the book in Room by manifest `id`. A bad chapter is flagged and skipped in playback, with a message.

## 4. Playback service

- `PlaybackService : MediaSessionService` with an `ExoPlayer`.
- Playlist = one `MediaItem` per chapter (file URI, metadata: title, book, cover).
- `setWakeMode(C.WAKE_MODE_LOCAL)`, audio focus handling on, `setHandleAudioBecomingNoisy(true)` (pause when headphones unplug).
- Foreground notification via Media3's default provider (play/pause, previous/next chapter, seek).
- Speed via `PlaybackParameters` (0.75x to 2.0x, pitch preserved).
- Progress saved: every 5 seconds while playing, plus on pause, on chapter change, and on service destroy.
- Auto-advance to the next chapter; stop at the end of the book and mark it finished.
- `SleepTimer`: counts down (or "end of chapter") and pauses playback with a short fade.
- UI talks to the service through a `MediaController` wrapped by `PlaybackController`, exposing `StateFlow<PlaybackState>`.

**Screen-off:** works because the player runs in a foreground media service with a wake lock (ExoPlayer manages it) and a notification. On first run, prompt the user to exempt the app from Samsung's battery optimization.

## 5. Reader view

**State**: `ReaderState(chapterIndex, chapter, mode, followAudio, currentSid, positionMs, isPlaying)`.

**Modes**

- Read only: no audio needed; scroll position saved as the nearest `sid`, mapped to `start_ms`.
- Listen only: reader UI can be hidden or minimal (title, cover, controls).
- Read + listen: highlight follows audio.

**Sentence index**: on chapter load, build an array of `(start_ms, sid)` sorted by time. Finding the current sentence is a binary search on `positionMs`. A ticker reads the player position about every 200 ms while playing.

**Rendering**

- `LazyColumn` with one item per block.
- A paragraph is one `Text` with an `AnnotatedString`; italic/bold `spans` applied; the current sentence gets a background highlight span. Only the block containing the current sentence recomposes when it changes.
- Auto-scroll: keep the current block roughly in the upper third of the screen.
- **Follow state machine:** `Following` (default in read + listen) becomes `Detached` on manual scroll; a "back to now" button returns to `Following` and scrolls to the current sentence.
- Tap a sentence: seek to its `start_ms`, set `Following`. Use `pointerInput` with text layout offset hit testing to find the tapped sentence.
- Font size, line spacing and theme (light, dark, sepia) from settings.

**PDF books**: `PdfRenderer` renders the current page to a bitmap (recycled on change); page changes when playback crosses the next `PageMark.start_ms`. Manual page turns seek audio to that page's `start_ms`.

## 6. Screens

| Screen | Contents |
| --- | --- |
| Library | Grid or list of books, cover, title, progress bar; import button |
| Reader | Text, mini controls, mode switch, speed, sleep timer |
| Chapters | List with durations; tap to jump |
| Settings | Font size, theme, default speed, battery-optimization help, storage location, about/licenses |

Navigation: Compose Navigation, a single back stack (Library, Reader, Chapters, Settings).

## 7. Edge cases

- Incoming call or other app takes audio focus: pause, resume when focus returns (if it was playing).
- Bluetooth disconnect: pause.
- Missing or corrupt MP3/JSON: skip that chapter, show a snackbar, keep the rest playable.
- App swiped from recents: playback should continue (test on the Tab E; if Samsung kills it, document the setting the user must change).
- Storage removed (microSD ejected): pause and show a message; resume when it returns.
- Very long chapters: never load more than the current chapter's JSON.

## 8. Libraries

| Purpose | Library |
| --- | --- |
| UI | Jetpack Compose (Material 3), Navigation Compose |
| Playback | `androidx.media3` (exoplayer, session, ui minimal) |
| Storage/DB | Room, DataStore (settings) |
| JSON | kotlinx.serialization |
| Async | Kotlin coroutines and Flow |
| Images | Coil |
| Testing | JUnit, Turbine, Compose UI tests, MockK |

Check that the versions you pick still support `minSdk 24`.

## 9. Build and release

- Build variants: `debug` (with logging overlay: position, current `sid`, chapter), `release` (minified).
- Sideload the APK first; publish to F-Droid or GitHub releases when open-sourcing (see licenses doc, later).
- Permissions: `READ_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE` (declared, harmless on API 24), `WAKE_LOCK`. No internet permission in v1.