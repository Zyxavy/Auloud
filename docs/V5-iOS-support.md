# Auloud v5: iOS and iPadOS Player (built with xtool)

Scope: a **Player-only** native Swift app for iPad (and iPhone as a free side effect) that plays and reads Scribe-made and device-made bundles. Built from your Windows machine with xtool. Planned for after v2 (and the v3 and v4 work); nothing here changes the Android roadmap.

## 1. What the research established (and what is still an assumption)

**Verified from xtool's own docs and community write-ups**

- xtool builds a SwiftPM package into an iOS app, signs and installs it, and can write an `.ipa` (`xtool dev build --ipa`). On Windows it runs inside WSL; the iPad's USB connection is forwarded into WSL with `usbipd`; Linux needs `usbmuxd`
- Setup needs an **Xcode `.xip` you download yourself** (Apple ID required; Xcode 26 or the version your workflow needs) so xtool can extract the iOS SDK. Login is either an App Store Connect API key (paid Developer Program) or an Apple ID password (any account, uses private APIs, 2FA)
- Project config lives in `xtool.yml`: `bundleID`, `product`, `infoPath` (a custom Info.plist that is merged in), `iconPath` (1024 px PNG), `entitlementsPath`, `resources`, `extensions`
- It is **not** Xcode: no Interface Builder, no simulator, no Xcode projects. Asset catalogs, storyboards and Core Data model compilation depend on Xcode tooling and should be avoided; use SwiftPM resources and plain PNGs
- Free Apple ID limits (from sideloading guides): signed apps **expire after 7 days**, at most **3 sideloaded apps** at a time, about 10 new App IDs per 7 days, and Developer Mode must be enabled on iOS 16+. One project reports a free account allows only one active development certificate

**Verified from Apple's documentation**

- `AVSpeechSynthesizer.write(_:toBufferCallback:)` exists from iOS 13 and hands you audio buffers to store, so on-device rendering with system voices is possible later. Background audio uses the `UIBackgroundModes` `audio` Info.plist key

**Assumptions to prove in Slice I0 (do not build on them yet)**

- That an xtool-signed app with `UIBackgroundModes: audio` plays with the screen off, shows lock-screen controls, and survives interruptions on your iPad
- That the Windows route (WSL, `usbipd`, or `--ipa` plus a Windows sideloader) actually works with your iPad. Your Android tablet never enumerated over USB, so treat iPad USB as unproven
- That iPad Files access (folder picking, file sharing, external drives) works the way the plan below assumes
- Whether a paid Developer Program account ($99/year, my understanding) is worth it to avoid the 7-day expiry; the free account is enough to start

**Risks outside the code**

- Apple's developer agreement reportedly restricts running the Apple SDKs on non-Apple computers; xtool ships no SDK and asks you to supply Xcode. Read the terms yourself
- xtool is largely a one-maintainer project; Xcode and iOS versions move, so pin versions and expect occasional breakage

## 2. Goals and non-goals

**Goals (v5.0):** on the iPad, import bundles, play them with the screen off and lock-screen controls, read along with a synced highlight, keep position, and navigate chapters. Parity with what the v1 Android Player does with bundles, plus reading unrendered books.

**Reads:** rendered bundles with MP3 or AAC audio (spec 1.0 to 2.0), range and partial bundles, PDF-derived text books (Text view), unrendered books (read mode only), and refuses unsupported newer versions cleanly through the version gate.

**Non-goals:** rendering or TTS on the iPad, on-device EPUB import, neural engines, PDF page view, streaming, App Store or TestFlight release, Android changes.

## 3. Key decisions (log in `DECISIONS.md`)

1. **Two Swift packages.** `AuloudCore`: pure Swift, no Apple-only frameworks (bundle models, parser, validator, version gate, sentence index, follow and mode state machines, progress, chapter mapping, sentence-id to milliseconds conversion). `AuloudPlayer`: the SwiftUI app target (audio, storage, UI). Core compiles and tests on Linux or WSL with `swift test`, so most logic is verified without an iPad.
2. **Shared fixtures are the contract.** The Swift core reads the same `spec/fixtures/` golden bundles (scribe, multivoice, aac, partial, unrendered, pdf, range) and the shared test vectors (timing, dialogue, parity) as Scribe and the Android Player. This catches drift between the three implementations.
3. **No new dependencies.** Foundation, AVFoundation, MediaPlayer, SwiftUI and UIKit only. Persistence is plain JSON files with atomic writes (library index, per-book progress and state), not Core Data (needs Xcode's model compiler) or SwiftData (macros and iOS 17+ risk under xtool).
4. **Reader built on UIKit text where needed.** SwiftUI's `Text` cannot map a tap to a character offset. The reader uses a text-view-based paragraph (UIKit via SwiftUI interop) for tap-to-jump and highlighting, chosen by a spike with a 5,000-sentence chapter, as the Android reader was.
5. **Playback on AVPlayer** with the playback audio session category in spoken-audio mode, precise seeking (zero tolerance), the speech-friendly time-pitch algorithm for speed changes, Now Playing and remote commands, and interruption and route-change handling.
6. **Getting bundles onto the iPad:** support two paths: (a) the app's Documents folder exposed through the Files app and file sharing, and (b) picking a folder anywhere in Files (iCloud Drive, an SMB share from your PC, or a USB-C drive on iPads that support it) and keeping access with a security-scoped bookmark, reading in place. Bundles can be hundreds of MB, so reading in place avoids doubling storage. Handle iCloud files that are not downloaded.
7. **Distribution is personal installs only**, signed through xtool with your Apple ID. Expect a weekly re-sign on a free account; a paid account removes that friction. App Store and TestFlight are out of scope (they normally need Apple's Mac-only upload tools).
8. **Minimum iOS** is set from your iPad's iPadOS version after Slice I0 (SwiftUI features available depend on it).
9. **License:** Apache-2.0 like the Android Player, with its own `NOTICE` and in-app licenses screen.

## 4. Slices

### Slice I0: Environment and feasibility gate (S-M), do first

- Install WSL2 Ubuntu, the Swift toolchain xtool needs, xtool, and download Xcode's `.xip`; run `xtool setup` with a **spare Apple ID** (the password mode uses private APIs)
- Build and install a hello-world SwiftUI app on the iPad; enable Developer Mode on the iPad
- Prove USB: `xtool devices` works through `usbipd`. If not, prove the fallback: `xtool dev build --ipa` then install with a Windows sideloader
- **Audio gate:** a throwaway app with `infoPath` setting `UIBackgroundModes: audio` plays a CBR MP3 and an AAC file with the screen off for 30 minutes, shows lock-screen controls, pauses on headphone unplug, and survives a call. Also test importing a folder through Files
- Record: iPad model and iPadOS version, minimum iOS decision, free-account re-sign procedure and how long it takes, install steps
- **Go/no-go:** if the toolchain or background audio does not work, record it and evaluate the alternatives (a Mac or rented Mac, a macOS CI runner) before spending more

### Slice I1: AuloudCore (M)

- Port from the spec and fixtures, not from memory: manifest and chapter models (EPUB blocks and sentences, PDF text form, `pages` marks, unrendered form), parser, validator (file and rule errors identical in wording to the other implementations), version gate, `source_index` and partial-book handling
- Sentence index (binary search, gap rule), follow state machine, mode transitions, sid-to-ms and ms-to-sid conversion, chapter-to-media-item mapping
- `swift test` on WSL running the shared fixtures and vectors
- **Done when:** all golden bundles load and validate identically to Scribe and Android; each bad fixture fails with the expected message

### Slice I2: Storage and library (M)

- Storage abstraction with both import paths from decision 6; library scan; JSON library index and progress; cover loading; missing-storage and not-downloaded handling; delete; clear messages for unsupported spec versions
- Library screen for iPad (list or grid, progress, "not rendered" and "partially rendered" chips)
- **Verify:** core logic tests on WSL; **device:** import from Documents file sharing, from an iCloud or SMB folder, and from a USB drive if your iPad supports it

### Slice I3: Playback (L)

- Chapter playlist on AVPlayer, background audio, Now Playing, remote commands (play, pause, next, previous, seek, speed), interruptions, route changes
- Speed with the speech time-pitch setting, sleep timer with fade, progress saved every few seconds and on backgrounding, finished-book behavior matching Android, partial-book playback skipping unrendered chapters
- Positions from a periodic time observer fast enough for highlighting (about 5 Hz)
- **Verify:** logic tests on WSL with a fake player; **device:** beep bundle sync, 30 minute and 2 hour screen-off runs, lock-screen controls, headphone unplug, call, Bluetooth, resume after app kill

### Slice I4: Reader and read-along (L)

- **Spike first:** reader rendering options with a 5,000-sentence chapter on the iPad (memory, scroll smoothness, highlight updates, tap hit-testing); decision logged
- Blocks, headings, quotes, breaks, italic and bold spans, dialogue marking for unrendered books, font size and Dynamic Type
- Highlight follows audio; Following, Detached and "back to now"; tap to jump with the confirm prompt Android has; the three modes with one shared position; read mode saving by sentence id; PDF Text view; iPad layouts (landscape and portrait, Split View and Slide Over)
- **Verify:** logic tests on WSL; **device:** the same sync and mode checklist as Android Slice 3

### Slice I5: Complete pass (M)

- Chapter list and jump in all modes, error handling (corrupt chapter skipped with a message, storage loss, bad bundles), settings (font size, theme, keep screen awake while reading), licenses screen and `NOTICE`, app icon, `README` iOS section (install steps, weekly refresh, importing bundles), `07-TestPlan` iOS section
- Signed `.ipa` build procedure written down and repeated from a clean WSL
- **Soak:** a full multi-voice novel over several days on the iPad: screen-off listening, mode switching, resume, memory behavior (a debug overlay with lag and memory)
- Tag `v5.0.0`

## 5. Order and check-ins

| Step | Slice | You can then... |
| --- | --- | --- |
| 1 | I0 | know whether iPad building and background audio work from Windows |
| 2 | I1 | have the Swift core verified against every golden bundle, no iPad needed |
| 3 | I2, I3 | import a book and listen with the screen off |
| 4 | I4 | read along with highlight |
| 5 | I5 | harden, document, soak, tag |

## 6. Definition of done for v5.0

- [ ] Slice I0 gate passed and recorded
- [ ] Swift core passes all shared fixtures and vectors (`swift test`)
- [ ] On the iPad: import, playback with the screen off and lock-screen controls, read-along sync, modes, resume, chapter navigation
- [ ] Soak passed; reproducible `.ipa` build and install documented, including the weekly re-sign
- [ ] Licenses and `NOTICE` complete; decisions logged; tagged `v5.0.0`

## 7. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| xtool or its SDK flow breaks on new Xcode or iOS releases | Pin versions; write the setup down; keep the Mac or CI fallback in mind |
| iPad USB does not work from WSL | `--ipa` build plus a Windows sideloader as the fallback (tested in I0) |
| Free account expiry (7 days), 3-app limit, App ID limits | Weekly re-sign routine; a paid account if it becomes a chore; do not change bundle ID or capabilities often |
| No simulator or previews | Core logic tested on Linux; thin UI layer; on-device checks written as runbooks |
| SwiftUI cannot hit-test text | UIKit text view interop decided by the I4 spike |
| Features silently diverge from Kotlin and Python | Shared fixtures and vectors as contract tests |
| Background audio does not behave under xtool signing | Tested first, in I0, before any real code |
| Large bundles and storage | Read in place with bookmarks; handle evicted iCloud files |
| Apple developer-agreement concerns | Read the terms; the project distributes no Apple SDK material |
| Private-API login flagged by Apple | Use a spare Apple ID; consider the API-key mode if you take a paid account |

## 8. Working with the agent

- Add a project skill `ios-xtool` (xtool.yml keys, no-Xcode limits, commands) and `swift-core` (Linux-testable core conventions); update `AGENTS.md`
- The agent writes Swift and runs `swift test` for the core; **you** run `xtool dev build`, install, and every device check; it must not claim iPad results
- Commit per slice step (`In: summary`); tag as above

## 9. After v5.0 (not planned, noted for later)

- **v5.1:** on-device speech on the iPad using Apple's speech API: live speaking with word-level highlight callbacks and rendering through `write(_:toBufferCallback:)`; both beat what Android 7.1.1 allows
- **v5.2:** on-device EPUB import by porting the ingestion rules, reusing the same shared dialogue and parity vectors from Slice 9
- Neural engines on iPad hardware, and App Store distribution (needs a paid account and a Mac-based upload path)

## 10. What you need to prepare

- [ ] Your iPad's model and iPadOS version, and whether it has USB-C
- [ ] A spare Apple ID (and decide free vs paid developer account)
- [ ] Windows 11 with WSL2 and Ubuntu; plenty of free disk (the Xcode download and SDK extraction are large)
- [ ] The Apple USB drivers (iTunes or the Apple Devices app) if you use the sideloader fallback
- [ ] A few bundles to test: the Scribe golden bundles, an AAC bundle rendered on the tablet, and a beep bundle for sync checks