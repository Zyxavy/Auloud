# Licenses and Legal Notes

## 1. own code

| Component | Proposed license | Why |
| --- | --- | --- |
| Player (Android app) | Apache-2.0 (or MIT) | Permissive, patent grant, compatible with the AndroidX/Media3 libraries it uses |
| Scribe (PC tool) | AGPL-3.0 | ebooklib is AGPL-3.0 (METADATA: GNU Affero General Public License, AGPLv3+), so Scribe which imports it is AGPL-3.0; also covers hosted-service use via the network clause (see D-024) |
| Bundle spec (`03-BundleSpec.md`) | CC0 or Apache-2.0 | So anyone can write compatible tools |

The Player only plays audio and shows text, so no GPL code ships in the app. If v2 adds on-device TTS with Piper/espeak-ng, revisit the Player's license (see section 5).

If you host Scribe as a network service for other people, check the AGPL points in section 3.

## 2. Player dependencies (Android)

| Library | License | Verified |
| --- | --- | --- |
| Kotlin, coroutines | Apache-2.0 | \[ \] |
| Jetpack Compose, AndroidX (Room, DataStore, Navigation), Media3 | Apache-2.0 | \[ \] |
| kotlinx.serialization | Apache-2.0 | \[ \] |
| Coil | Apache-2.0 | \[ \] |
| JUnit (test only) | EPL-1.0 | \[ \] |
| MockK, Turbine (test only) | Apache-2.0 | \[ \] |

In-app: add a "Licenses" screen listing these (a Gradle license plugin can generate it), and ship a `NOTICE` file.

## 3. Scribe dependencies (Python)

| Library | License (as I understand it) | Notes | Verified |
| --- | --- | --- | --- |
| EbookLib | AGPL-3.0 | Strong copyleft. Fine inside an open-source GPL/AGPL tool; if you offer Scribe as a hosted service, AGPL's network clause means users must be able to get the source. Consider licensing Scribe as AGPL-3.0 in that case. | \[x\] |
| PyMuPDF | AGPL-3.0 (commercial license available) | Same AGPL consideration. Alternative: `pypdf`/`pdfminer.six` (permissive) if you want to avoid it. | \[ \] |
| beautifulsoup4, lxml | MIT, BSD-3 |  | \[ \] |
| pysbd | MIT |  | \[ \] |
| spaCy, `en_core_web_sm` | MIT | spacy 3.8.16 + en-core-web-sm 3.8.0 (installed METADATA says MIT for both; transitives MIT/BSD/Apache-2.0, tqdm dual MPL-2.0 AND MIT, nothing GPL). Model pinned by wheel URL in `scribe/pyproject.toml` + `uv.lock` (not on PyPI); `scribe doctor` checks present + version and proves a parse. | \[x\] |
| PyYAML, numpy, typer, rich, pytest | MIT/BSD |  | \[ \] |
| soundfile | BSD-3 | Uses libsndfile (LGPL) | \[ \] |
| pyrubberband (optional) | ISC | Uses Rubber Band (GPL, commercial option) | \[ \] |
| ffmpeg / ffprobe | LGPL or GPL depending on the build | Run as a separate program, do not link it. Uses LAME (LGPL) for MP3. | \[ \] |

## 4. TTS engines and voices

| Item | License (as I understand it) | Notes | Verified |
| --- | --- | --- | --- |
| Kokoro (Python package) | Apache-2.0 | Needs espeak-ng for phonemes | \[ \] |
| Kokoro model weights | Apache-2.0 | Check the model card | \[ \] |
| Piper (current GPL fork) | GPL-3.0 | Older `rhasspy/piper` was MIT; check which you use | \[ \] |
| Piper voice models | **Each voice has its own license** | Some permissive, some restricted (for example non-commercial). Record it per voice. | \[ \] |
| espeak-ng | GPL-3.0 | Used for phonemization by both engines | \[ \] |

**Rules for voices:**

- Keep a table of every voice you ship or recommend, with its license and source URL.
- Download models at runtime rather than committing them to the repo, and do not bundle them in the APK.
- Generated audio: check each model's terms about using the output, especially if you ever share audiobooks.
- No voice cloning of real people without their permission (not in v1 scope).

## 5. What changes in v2 (on-device TTS)

Bundling espeak-ng (GPL-3.0) or a GPL Piper build into the app means the app is distributed with GPL code. Options then: license the Player under GPL-3.0, or choose an engine path without GPL components. Decide this at the v2 benchmark spike, and log the decision in `DECISIONS.md`.

## 6. Books and content

- **Test content:** use public-domain texts (for example Project Gutenberg; check that a title is public domain in your country) for the repo, demos and test bundles.
- **Never commit copyrighted books or audio** made from them to the repo, releases, or issues.
- Converting books you legally own for your own listening is a personal use; **distributing** generated audiobooks of copyrighted works is not something the project should enable or encourage. Say this plainly in the README.
- Cover art: only use covers you have the rights to, or generate neutral ones.

## 7. Release checklist for licensing

- [ ] `LICENSE` file in each component (Player: Apache-2.0; Scribe: GPL-3.0)
- [ ] `NOTICE` and `THIRD_PARTY_LICENSES.md` generated
- [ ] All "Verified" boxes above ticked
- [ ] Voice table complete, with per-voice licenses
- [ ] In-app licenses screen
- [ ] README states the copyright/personal-use position
- [ ] Contribution policy (for example DCO sign-off or a CLA) decided