# Auloud Player release signing (v2.1, VC2 pattern)

Version `2.1.0`, `versionCode` 3. Two flavors (D-126 option A): `core`
(Apache-2.0, main release) and `full` (GPL-3.0 combined work, ships
sherpa-onnx with static espeak-ng). Both share the applicationId and
version, so either one upgrades a v1/v2 install in place.

## Key (maintainer, do once)

1. Generate the key ONCE on your PC (NEVER in the repo):
   `keytool -genkeypair -v -keystore E:\auloud-keys\auloud-release.keystore -alias auloud -keyalg RSA -keysize 2048 -validity 10000` (example path, use your own)
2. Keep the `.keystore` file OUTSIDE the repo (for example a USB stick or a second drive).
3. Back it up somewhere safe and note the
   passwords somewhere safe. Losing it means the app must be REINSTALLED:
   updates signed with a new key will not install over the old app.
   Same-key upgrades (below) need the SAME key as the previous release.
4. Point the build at it in `player\local.properties` (gitignored, never
   commit), backslashes doubled (example path, use your own):
   `auloud.keystore.path=E:\\auloud-keys\\auloud-release.keystore`
   `auloud.keystore.storePassword=<store password>`
   `auloud.keystore.keyAlias=auloud`
   `auloud.keystore.keyPassword=<key password, or omit if same as store>`
5. Without these keys the release build signs with the debug key and prints
   a WARNING, so CI and unit tests never break. A debug-signed APK must
   never be distributed as the release.

## Build (from `player/`)

```
.\gradlew.bat --no-daemon :app:assembleCoreRelease :app:assembleFullRelease
```

APKs land in `app\build\outputs\apk\<core|full>\release\`.
Release builds run R8 (`minifyEnabled` + `shrinkResources`); keep rules
live in `app\proguard-rules.pro` (serialization, Room, Media3 session,
Guava futures, Coil, plus the sherpa-onnx JNI bridge for `full`).

Native size note (D-129): only ARM ABIs ship (`armeabi-v7a` for the
Tab E, `arm64-v8a` for modern devices). The sherpa AAR's x86/x86_64
libs (about 73 MB raw) are excluded by the `abiFilters` in
`app\build.gradle.kts`. No per-ABI splits: one universal APK per flavor
keeps sideloading and upgrades simple. Model packs stay separate.

## Verify before sideloading (agent-runnable)

```
.\gradlew.bat --no-daemon :app:licenseScan :app:releaseManifestCheck
.\gradlew.bat --no-daemon :app:testCoreDebugUnitTest :app:testFullDebugUnitTest
```

- `licenseScan`: `core` carries no sherpa/onnx/espeak files or dex
  markers; `full` carries its native libs plus the notices
  (`assets/gpl-3.0.txt`, `assets/SOURCE_OFFER.txt`, `assets/NOTICE.txt`).
- `releaseManifestCheck`: every merged release manifest has no
  `android.permission.INTERNET` (the unit test only sees the source).
- Extra belt: `<sdk>\build-tools\<version>\aapt.exe dump badging <apk> | findstr permission`
  must list NO `android.permission.INTERNET`.
  (`ACCESS_NETWORK_STATE` comes from an AndroidX dependency; accepted
  since v1, see D-047.)

Debug-only code (beep engine, beep self-check card, reader preview
entry, render debug overlay, save-time snapshot) is gated on
`BuildConfig.DEBUG` at every call site and pinned by unit tests
(`BeepDebugGateTest`, `ReleaseGuardsTest`, `RenderDebugGateTest`);
release builds never reach those paths.

## Release smoke checklist (DEVICE-RUN, on the signed APK)

Device verdict 2026-10-10: both checklists pass ("1 and 2 are good and
complete", flavor not specified). Boxes ticked on that verdict, not
from JVM results.

- [x] Sideload: signed `core` release installs over the v1 install (same
  key); library and progress survive (VC3 procedure)
- [x] Import: an EPUB imports on the tablet, chapter list and text read
  correctly (Slice 9 path)
- [x] Playback: a PC-rendered (Scribe) multi-voice book plays with
  read-along highlight within about 300 ms (Slices 1-4 path)
- [x] Screen-off: playback continues with the screen off for 30+ minutes;
  lock-screen controls work (Slice 1 path)
- [x] Render: an imported book renders on-device (System TTS) and plays
  with correct highlight; kill mid-render resumes without redoing
  finished sentences (Slice 10 path)
- [x] Voices: narrator and dialogue voices are clearly distinct and
  level-matched; switching engines re-renders only what is needed
  (Slice 11 path)
- [x] If shipping `full`: a Piper pack loads, synthesizes and renders at
  least one chapter (record the speed); licenses screen shows the
  sherpa-onnx/onnxruntime/espeak-ng/Piper rows
- [x] No debug-only features visible in release: no beep engine, debug
  overlays, or spike/preview entries anywhere in the UI

## VC3 over-install procedure (DEVICE-RUN, needs the tablet)

The Room v1-to-v2 migration cannot run on the JVM (the v1 schema was
never exported and the unit harness has no Robolectric/room-testing),
so data survival is proven here, on the tablet. Same signing key for
both installs, no uninstall in between. Ticked 2026-10-10 on the
device verdict ("good and complete"), not from JVM results.

- [x] Install the signed `v1.0.0` APK (same release key as the `2.0.0`
  build); confirm the install succeeds and the app launches
- [x] Import at least two books (one PC-rendered Scribe bundle, one
  PC multi-voice bundle); play each past chapter 1 so a mid-book
  position is saved
- [x] Record the library count plus each book's chapter and position
  (a note or screenshot) before upgrading
- [x] Install the signed `2.0.0` APK (`core` or `full`) OVER v1 without
  uninstalling; confirm the install succeeds and the app launches
- [x] Library shows the same books (same count and titles, no
  duplicates, no missing flags); each saved position lands on the same
  chapter and offset (v1 ms positions read back with NULL sid per the
  Slice 9 rule)
- [x] Playback resumes from a saved position with read-along; a fresh
  EPUB import works alongside the migrated library
- [ ] Fallback if anything is lost: N/A (nothing lost per the verdict).
  If it ever applies: re-import (books are plain bundles
  on storage); report the failure with `adb logcat -s Auloud:V` output

Repeat the same steps for `2.0.0`-to-`2.1.0` upgrades (same key, no uninstall); that round is still open.
