# Slice 20 Implementation Plan: Validate, Wi-Fi Transfer, Models, First Run

Follows `docs/10-V3Roadmap.md` Slice 20, the `rust-ui` skill, the
`android-api24` skill (Player side), and `docs/api.md` sections 3-7.
Project: the app closes the loop. A per-book validate list, Wi-Fi
bundle receive on the tablet with a pairing code, a Kokoro model
manager with a first-run wizard, all against the jobs flow proven in
Slice 18 and the cast editing proven in Slice 19.

## 1. Goal

Reach a first valid bundle on the tablet without leaving the app:
**open a rendered book, check its validation list, start receiving on
the tablet, push the bundle over Wi-Fi from the PC with a pairing
code, and have it import cleanly** — with a fresh install guided by a
wizard that ensures the Kokoro models exist first.

**Done when:**

- Validate list per rendered book: every bundle with file/rule/message
  lines and a valid tick, rendered from the existing endpoint
- Wi-Fi transfer: tablet "Receive from PC" screen (IP + port + pairing
  code, receiver only while open), PC pushes the zipped bundle with
  send progress, sha256 verified before unzip, existing import
  validation after unzip
- Model manager: Kokoro pair status, download with checksum + progress,
  sideload, delete, license line
- First-run wizard: missing models → download/sideload → device
  setting → Books; skippable, re-runnable
- Owner acceptance logged; suites + clippy + deny + lint green; tagged
  `slice-20`

## 2. Inputs and what is known

- Contract (`docs/api.md` 7): `POST /api/books/{book_id}/validate`
  (inline, read-only, `scribe validate` wording) and `POST
  /api/books/{book_id}/transfer` (folder copy, job kind `transfer`,
  cannot pause, cancel deletes the partial copy) exist. The Rust
  `scribe-client` already wraps `validate` (`client.rs:508`); transfer
  wrappers are missing and small (established style, same commit as
  first use). The folder-copy endpoint stays as the API fallback but
  gets no UI in this slice.
- Wi-Fi receive is new on both sides. Direction is decided:
  **tablet-hosts** (the PC pushing to the tablet). PC-hosts is
  rejected: the helper's 127.0.0.1-only bind is a deliberate guard
  (D8/UI2) and opening it to the LAN needs a bigger security review.
- Player posture change (owner-approved, option A): the Player gains
  `INTERNET` only (install-time normal, no runtime prompt on API 24);
  no `ACCESS_WIFI_STATE` (`NetworkInterface` enumeration needs none,
  D-167). This supersedes the no-network posture in `AGENTS.md` and the
  `android-api24` skill; the `DECISIONS.md` entry lands at AC.
  `releaseManifestCheck` now requires `INTERNET` for the merged
  core/full manifests and the README offline claim becomes LAN-only
  (no cloud); both are updated in the same slice.
- Models (decided): Kokoro pair only (`kokoro-v1.0.onnx` +
  `voices-v1.0.bin`); downloads happen in the Rust app with checksums
  into the models folder (v3 roadmap decision 4); hardcoded URL +
  sha256 per file, supplied by the owner; the helper's `doctor` stays
  the CLI verifier. No helper contract change for models.
- Tablet network facts (API 24): plain `java.net.ServerSocket` works
  with no new dependency; `NsdManager` (API 16+) exists if manual IP
  entry proves painful; `WifiManager` reports the device IP with
  `ACCESS_WIFI_STATE`. All file writes go through `BundleStorage`;
  received bytes are untrusted input until the hash and the import
  validation pass.

**Unknowns this slice resolves:** the server-crate fork (embedded
HTTP crate vs hand-rolled `ServerSocket` loop), pairing-code ergonomics
on the real tablet, and download reliability for ~hundred-MB files on a
home network.

## 3. Scope

| In | Out (later) |
| --- | --- |
| Validate list per book (existing endpoint) | Folder-copy transfer UI (endpoint stays, no UI) |
| Wi-Fi receive: tablet screen + receiver, PC push + progress, pairing, hash verify, import | Auto-discovery (NSD) unless typing proves painful |
| Kokoro manager: status, download, sideload, delete, license | Piper/Kitten packs (later slices) |
| First-run wizard ending at Books | Fresh-install-to-first-render timing claims (note honesty only) |
| Permission + manifest-check + README updates for INTERNET | Wi-Fi Direct / hotspot / mobile-data paths (same-network only) |

## 4. Key decisions (proposed; log on acceptance)

1. **Tablet-hosts, screen-open only.** The receiver runs only while the
   Receive screen is open; leaving the screen stops it. No background
   server, ever.
2. **Manual IP + pairing code first.** The tablet shows exactly what to
   type (IP:port + code); the code gates each transfer. NSD only if
   typing proves painful on the Tab E.
3. **Zip + sha256 + existing validation.** The PC zips the bundle dir;
   the tablet verifies the hash before unzip and runs the existing
   import validation after (bad bundles skip with a message, never a
   crash). Received bytes are untrusted until both pass.
4. **Server-crate fork at implementation.** Prefer a tiny embedded HTTP
   server crate only after its license (Apache-2.0/MIT/BSD) and
   `minSdk 24` support check pass (Context7); else a hand-rolled
   `ServerSocket` loop with no new dependency. No new dependency
   without owner approval either way (standing rule).
5. **No logic crosses any boundary.** Rust never reimplements
   validation; the tablet reuses its import path; checksums live in the
   app, `doctor` stays the CLI verifier.
6. **Tablet-receive protocol documented here + KDoc.** No helper
   contract change, so no `docs/api.md` edit; the wire shape (zip +
   code header + sha256) is pinned in this plan and the receiver's
   KDoc.

## 5. Work packages

### VT1: Validate list (S)

- Per-book validate section (detail gains it): per-bundle valid tick
  plus file/rule/message lines from the existing endpoint shapes; no
  data when no bundle exists yet (names the next action: render).
- Tests: rendering over recorded responses (no live helper in the
  default suite).
- **Verify:** a valid and an invalid bundle each render truthfully;
  suite + clippy + deny green.

### TR1: Wi-Fi receive (L, the slice's risk)

- Tablet: Receive screen (IP:port + pairing code display, start/stop,
  receive progress, result message naming file + rule on failure);
  receiver (crate or `ServerSocket` per decision 4), hash verify
  before unzip, existing import path after.
- PC: transfer section (IP field, code field, send with progress over
  the zipped bundle dir); send errors as file + rule.
- Tests: protocol/verify/import unit tests over loopback fixtures on
  the JVM; Rust send-side tests over recorded shapes; no tablet in the
  default suites.
- **Verify:** real push PC → Tab E on one Wi-Fi network, wrong code
  refused, corrupted zip rejected before unzip, bad bundle skipped
  with a message after unzip; Player suites + lint green, Rust suite +
  clippy + deny green.

### MM1: Kokoro model manager (M, source resolved 2026-10-14)

- Source: `model-files-v1.1` release of
  `thewh1teagle/kokoro-onnx` (same source `scribe` uses; see
  `MODELS_HELP` in `scribe/cli.py:79-84`).
  - `kokoro-v1.0.onnx`: 325,505,369 bytes,
    `sha256:beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a`,
    `.../releases/download/model-files-v1.1/kokoro-v1.0.onnx`
  - `voices-v1.0.bin`: 28,214,398 bytes,
    `sha256:bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d`,
    `.../releases/download/model-files-v1.1/voices-v1.0.bin`
  - Digests are the GitHub API `digest` fields for the release assets
    (verified 2026-10-14, not hand-copied). License: Apache-2.0 per the
    Kokoro-82M model card (`docs/08-Licenses.md`).
- Status caveat (checked 2026-10-14): the working `scribe/models/`
  `voices-v1.0.bin` matches the pinned digest byte-for-byte, but the
  local `kokoro-v1.0.onnx` (325,532,232 bytes) predates the upstream
  2026-08-18 re-upload and differs. The manager therefore reports
  three states per file — missing, present-pinned (hash matches), and
  present-unpinned (works with Scribe today, re-download offered) —
  never a bare pass/fail.

- Models screen: pair status (present/missing per file, sizes, sha256
  match computed locally), Download with progress + checksum verify +
  atomic rename, Sideload via the native file dialog, Delete, license
  line (Apache-2.0 + model-card URL).
- One new HTTP crate for downloads: owner approval + `cargo deny`
  gate before code (standing rule); large-file resume only if the
  first real download proves flaky.
- Tests: status/checksum-verify unit tests; download against a local
  file server in tests, never the real URL.
- **Verify:** delete → download → checksum pass → render works;
  sideload path; doctored failure message on a bad checksum; suite +
  clippy + deny green.

### WZ1: First-run wizard (S, needs MM1)

- Launch gate when the Kokoro pair is incomplete: welcome → models
  (download/sideload via MM1) → device setting → Books. Skippable,
  re-runnable from settings, never blocks an already-set-up install.
- Tests: gate-condition unit tests (models present/missing/partial).
- **Verify:** fresh workspace goes wizard → drafted book; suite green.

### AC: Acceptance + tag (S)

- Acceptance checklist (`docs/acceptance-slice20.md`): validate list
  both ways, Wi-Fi push end to end + wrong-code + corrupt-zip +
  bad-bundle, manager download/sideload/delete, wizard on a fresh
  workspace.
- **Verify:** owner acceptance logged; all suites + clippy + deny +
  lint green; tag `slice-20`.

## 6. Order and check-ins

| Step | Work packages | You can then... |
| --- | --- | --- |
| 1 | TR1 | push bundles over Wi-Fi (resolves the crate fork first) |
| 2 | VT1 | see validation without the console (any order with MM1) |
| 3 | MM1 | install models inside the app (needs URL + checksums) |
| 4 | WZ1 | onboard a fresh install |
| 5 | AC | tag |

TR1 goes first as the riskiest; VT1 and MM1 are independent of it.

## 7. Fallback paths (decided at AC)

| Result | Decision |
| --- | --- |
| Server crate fails license/minSdk | Hand-rolled `ServerSocket` loop, no new dep |
| Typing IP:port proves painful | NSD auto-discovery as a follow-up WP |
| Big downloads flaky | Resume support; browser-download + sideload stays valid |
| Wi-Fi unusable on the network | Folder-copy endpoint is the fallback (UI only then) |
| Pairing code ergonomics poor | Code lifetime/scope tightened, never removed |

## 8. Definition of done

- [ ] Validate list with file/rule/message + valid tick per bundle
- [ ] Wi-Fi push PC → tablet with pairing code, progress both ends
- [ ] Wrong code refused; corrupt zip rejected pre-unzip; bad bundle
  skipped post-unzip with a message
- [ ] Receiver runs only while the Receive screen is open
- [ ] Kokoro download/sideload/delete with checksum verify + license
- [ ] First-run wizard reaches Books on a fresh workspace
- [x] INTERNET permission + manifest-check + README updates landed
- [ ] Owner acceptance logged; suites + clippy + deny + lint green;
  tagged `slice-20`

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| LAN receiver accepts malicious input | Pairing code + hash-before-unzip + existing import validation; screen-open only |
| New server/HTTP crate (license, minSdk, size) | Check-first fork (decision 4); hand-rolled fallback ready |
| Same-network requirement confuses | Screen states the requirement; manual IP keeps it visible |
| Download size (~hundreds of MB) on flaky Wi-Fi | Progress + checksum; resume only on evidence; sideload always works |
| Scope creep into discovery/polish | NSD, QR, themes stay out; Slice 21 owns polish |
| Tablet slowness on receive + unzip | Stream to disk, never hold the zip in memory; progress stays truthful |

## 10. Prerequisites for you

- [x] Kokoro download URL + sha256 for both files (resolved 2026-10-14, recorded in MM1 above)
- [ ] Tablet + PC on the same Wi-Fi for the TR1 proof
- [ ] A valid and an invalid bundle for VT1
- [ ] Type the shown IP + code; judge progress wording, pairing flow,
  wizard steps
- [ ] Approve any new dependency before code (standing rule)

## 11. Working with the agent

- One work package per session; it writes the screens, receiver,
  manager, and tests; **you** run the tablet receives, press the
  flows, and judge density/wording
- It must not claim transfer behaviors it did not run against the
  real tablet (loopback is not device proof)
- No new dependency without a license + minSdk check; no helper
  contract change without a `docs/api.md` update in the same commit
- Commit per work package (`VT1:`/`TR1:`/`MM1:`/`WZ1:`); tag
  `slice-20`

## 12. Next

Slice 21 builds on this slice: design tokens, keyboard and
screen-reader pass, scaling, and the UI runbook — over the
validate/transfer/models/wizard screens proven here.
