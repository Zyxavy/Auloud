# Chapter render fingerprint (Slice 10 as built; VS0 notes for Slice 11)

VS0 prep doc. Sources: `player/.../render/RenderFingerprint.kt`,
`RenderVoices.kt`, `SpoolRenderer.kt`, `RenderFinalize.kt`, `RenderRecovery.kt`,
`RenderService.kt`, `BundleValidator.kt`, `BundleModels.kt`, `IngestWriter.kt`,
Slice 8 `tts/` (`TtsEngine`, `EngineRegistry`, `VoiceMapper`, `PrefsTtsStore`,
`VoiceAuditionViewModel`, `VoiceAuditionScreen`), `RenderPanel.kt`,
`PartialBookScreen.kt`, `BookScreen.kt`, `MainActivity.kt`, `SettingsScreen.kt`,
`spec/bundle.md` sections 3/7.

## 1. Which fields go into a chapter fingerprint today

Built once per render run in `RenderVoices.resolve` from the global
`PrefsTtsStore` plus the `EngineRegistry` plus a `versionOf` lambda:

- `engine`: one namespace when both roles share an engine, else the sorted
  namespaces joined with `+` (`RenderFingerprint.combineEngine`).
  Examples: `system`, `piper+system`.
- `voices`: `{narrator, dialogue}` with the full namespaced TTS voice ids
  (for example `system:...`). Both keys always present.
- `speeds`: `{narrator, dialogue}` with the clamped per-role multipliers
  (`clampTtsSpeed`: 0.5 to 2.0, garbage becomes 1.0). Both keys always present.
- `engine_versions`: `{namespace: version}` with one non-blank version string
  per engine namespace used. A blank version fails the render fast (a
  placeholder would validate but never invalidate after an update).

Real Tab E version strings live in `RenderService.engineVersion`: the system
TTS package version (fallback `system`), `sherpa-1.13.8` for Piper, the beep
version for the debug engine. Fixtures carry placeholder version strings.

## 2. How "roles used" is determined today: it is NOT determined

Both roles are always included, even for a chapter with zero dialogue
sentences (the dialogue pass then runs over an empty list, but the fingerprint
still carries the dialogue voice and speed).

- `RenderVoices.resolve` always binds both roles and fails when either role
  setting is blank, unparseable, or not offered.
- `RenderFingerprint.fromJsonObject` requires both reserved roles and returns
  null otherwise (a corrupt index means "fingerprint unknown", which
  invalidates the spool, never a crash).
- `BundleValidator.validateChapterFingerprint` requires both roles with
  non-blank voices and positive speeds.

Consequence: today a dialogue-only voice change invalidates every chapter
spool, including dialogue-free chapters. VS2 must narrow this (see section 6).

## 3. Fingerprint string, file format, and where it is stored

- `canonicalString()`: `engine` plus newline, then sorted `role=voice` lines,
  then sorted `role=float-bits-hex` lines (`Float.toBits`, so `1.0f` compares
  equal across processes without decimal formatting drift), then sorted
  `namespace=version` lines.
- `fileTag()`: first 8 hex chars of SHA-256 over `canonicalString()`.
- `toJsonObject()`: `{engine, voices{}, speeds{}, engine_versions{}}` with
  keys sorted; speeds are written as JSON doubles (`Float` to `Double`).
  Shape matches spec 2.0 part 2 section 3.

Stored in three places:

1. `manifest.json`, per rendered chapter entry, as `render_fingerprint`
   (written by `RenderFinalize.finalizeChapter` via
   `buildUpdatedManifestJson`, temp-then-rename; 2.0 only, rendered chapters
   only, never on unrendered chapters).
2. Spool index `chNNN-index.json` (full fingerprint, rewritten after every
   spooled sentence; read back for resume and assembly).
3. Spool PCM file names `chNNN-sMMM-<tag>.pcm` (tag only, never the raw
   object) under the spool dir (`RenderServicePolicy.spoolDirFor`:
   `<cache>/render-spool/<bookId>`).

Do not confuse with manifest `voices`: that is `Map<String, JsonObject>`
with `{engine, voice, speed, pitch}` per key. Device 2.0 books carry
`narrator` plus `dialogue` placeholders (`system`/`default`/1.0/1.0, written
by `IngestWriter.defaultVoices`); 1.x Scribe books carry per-character keys
(`narrator`, `Ana`, ...). Fingerprint voices are flat namespaced TTS ids for
exactly the two device roles. Different shapes, different purposes.

## 4. What happens on mismatch today

- Spool: any fingerprint inequality (or a corrupt/unparseable index) makes
  `readUsableIndex` return null, which deletes the stale chapter spool
  best-effort and re-renders the whole chapter. The filename tag is
  self-invalidating the same way: old files never match the current name.
- Manifest `render_fingerprint`: nothing compares it. No stale marking, no
  re-render trigger, no UI. Readers ignore it (raw `JsonElement` in
  `BundleModels`; loudness and timings are baked into the audio) and playback
  is unaffected.
- Recovery: the forward-complete repair invents no fingerprint and no gain
  or offset (absent means unknown, never up to date); the downgrade repair
  strips the fingerprint with the entry.
- Validator: shape check only (2.0 only, rendered chapters only, both roles,
  speeds above 0, non-empty versions). Every error names the file and the rule.

## 5. The Slice 8 voice settings side (what VS1/VS4 build on)

- Settings are global only: `PrefsTtsStore` in the shared `auloud_settings`
  file (`tts_narrator_voice`, `tts_dialogue_voice`, plus per-role speeds).
  There is no per-book voice storage yet.
- `TtsEngine` seam: `namespace`, `voices()`, `capabilities()`,
  `synthesize(text, voice, speed)`. `EngineRegistry` routes a namespaced id
  to its engine on the namespace prefix.
- `VoiceMapper.mapVoice`: keep the current voice when the target engine
  offers it, else the same local id after the colon, else the first sorted
  voice of the target engine (`roleDefault`; the role only picks the slot,
  not the voice). `recommendEngine`: Piper where present, else System TTS,
  else first sorted.
- `VoiceAuditionViewModel` treats the global store as source of truth
  (persists immediately); engine switch remaps both roles via `VoiceMapper`.
  Audition plays one fixed self-made sentence per preview.

## 6. What VS1/VS2 will need to change

- VS1: add the per-book `BookVoices` model (narrator, dialogue, speeds,
  engine) read and written through the manifest `voices` entries, with global
  defaults as fallback; flag Scribe-rendered books read-only. The fingerprint
  compare target moves from the global store to the book settings.
- VS2: include only used roles in the fingerprint, so a chapter with no
  dialogue sentences stays current when only the dialogue voice changes;
  accept single-role fingerprints in `fromJsonObject` and the validator;
  keep old both-role fingerprints working as "stale at worst" (a missing
  role never reads as current, never crashes). Add the three states:
  Current, Stale (voice, speed, or engine differs), Outdated (only the
  engine version string differs; shown quietly, never auto re-rendered).

## 7. Render panel and hub voice link (call site for VS4)

Today every voice link lands on global Settings, not on a book voice screen:

- `RenderPanel.kt:106`: `TextButton(onClick = onOpenVoiceSettings)` labeled
  `Choose voices` (param declared at line 47).
- `PartialBookScreen.kt:87` declares `onOpenVoiceSettings` and passes it to
  the panel at line 227.
- `BookScreen.kt:82` declares `onOpenVoiceSettings` and passes it to the hub
  at line 136.
- `MainActivity.kt:252-255` supplies it: clears the book selection and sets
  `showSettings = true`.
- `SettingsScreen.kt:104-106` hosts `VoiceAuditionHost` behind the
  `Choose voices` entry (lines 145-148), so the panel link needs one tap
  more to reach any voice UI at all.

VS4 must open the book voice screen (or global defaults with scope choice)
from the panel and the hub instead of this Settings landing.
