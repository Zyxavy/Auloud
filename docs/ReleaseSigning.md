# Auloud Player release signing (v2, VC2)

Version `2.0.0`, `versionCode` 2. Two flavors (D-126 option A): `core`
(Apache-2.0, main release) and `full` (GPL-3.0 combined work, ships
sherpa-onnx with static espeak-ng). Both share the applicationId and
version, so either one upgrades a v1 install in place.

## Key (owner, do once)

1. Generate the key ONCE on your PC (NEVER in the repo):
   `keytool -genkeypair -v -keystore D:\keys\auloud-release.keystore -alias auloud -keyalg RSA -keysize 2048 -validity 10000`
2. Keep the `.keystore` file OUTSIDE the repo (for example `D:\keys\`).
3. Back it up (for example a USB stick kept elsewhere) and note the
   passwords somewhere safe. Losing it means the app must be REINSTALLED:
   updates signed with a new key will not install over the old app.
   The v1-to-v2 upgrade (VC3) needs the SAME key as the v1 release.
4. Point the build at it in `player\local.properties` (gitignored, never
   commit), backslashes doubled:
   `auloud.keystore.path=D:\\keys\\auloud-release.keystore`
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

## Release smoke checklist (OWNER-RUN, on the signed APK)

Do not tick these from JVM results; they need the Tab E.

- [ ] Sideload: signed `core` release installs over the v1 install (same
  key); library and progress survive (VC3 procedure)
- [ ] Import: an EPUB imports on the tablet, chapter list and text read
  correctly (Slice 9 path)
- [ ] Playback: a PC-rendered (Scribe) multi-voice book plays with
  read-along highlight within about 300 ms (Slices 1-4 path)
- [ ] Screen-off: playback continues with the screen off for 30+ minutes;
  lock-screen controls work (Slice 1 path)
- [ ] Render: an imported book renders on-device (System TTS) and plays
  with correct highlight; kill mid-render resumes without redoing
  finished sentences (Slice 10 path)
- [ ] Voices: narrator and dialogue voices are clearly distinct and
  level-matched; switching engines re-renders only what is needed
  (Slice 11 path)
- [ ] If shipping `full`: a Piper pack loads, synthesizes and renders at
  least one chapter (record the speed); licenses screen shows the
  sherpa-onnx/onnxruntime/espeak-ng/Piper rows
