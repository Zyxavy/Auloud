# Licenses and Legal Notes

## 1. own code

| Component | Proposed license | Why |
| --- | --- | --- |
| Player (Android app) | Apache-2.0 (or MIT) | Permissive, patent grant, compatible with the AndroidX/Media3 libraries it uses |
| Scribe (PC tool) | AGPL-3.0-or-later | ebooklib is AGPL-3.0 (METADATA: GNU Affero General Public License, AGPLv3+), so Scribe which imports it is AGPL-3.0-or-later; also covers hosted-service use via the network clause (see D-024) |
| Bundle spec (`03-BundleSpec.md`) | CC0 or Apache-2.0 | So anyone can write compatible tools |

The Player only plays audio and shows text, so no GPL code ships in the app. If v2 adds on-device TTS with Piper/espeak-ng, revisit the Player's license (see section 5).

If you host Scribe as a network service for other people, check the AGPL points in section 3.

## 2. Player dependencies (Android)

| Library | License | Verified |
| --- | --- | --- |
| Kotlin, coroutines | Apache-2.0 | [x] |
| Jetpack Compose, AndroidX (Room, DataStore, Navigation), Media3 | Apache-2.0 | [x] |
| kotlinx.serialization | Apache-2.0 | [x] |
| Coil | Apache-2.0 | [x] |
| JUnit (test only) | EPL-1.0 | [x] |
| MockK, Turbine (test only) | Apache-2.0 | [x] |
| jsoup (planned, pin in IN4) | MIT | \[ \] |

In-app: add a "Licenses" screen listing these (a Gradle license plugin can generate it), and ship a `NOTICE` file.

jsoup note (Slice 9): HTML parser for sloppy EPUB XHTML, planned for IN4.
MIT per https://jsoup.org/license. Runs on Java 8 and up including Android
with core library desugaring (NIO spec) per https://jsoup.org/download.
Floor 1.23.2 (current release confirmed 2026-10-05); minSdk 24 is above the
API 21 baseline jsoup validates against. No Gradle pin in this task (IN4
pins `org.jsoup:jsoup` in `player/gradle/libs.versions.toml`); see D-079.

## 3. Scribe dependencies (Python)

| Library | License (as I understand it) | Notes | Verified |
| --- | --- | --- | --- |
| EbookLib | AGPL-3.0 | Strong copyleft. Fine inside an open-source GPL/AGPL tool; if you offer Scribe as a hosted service, AGPL's network clause means users must be able to get the source. Consider licensing Scribe as AGPL-3.0 in that case. | \[x\] |
| PyMuPDF | AGPL-3.0 (commercial license available) | AGPL is compatible inside AGPL-3.0-or-later Scribe (never ships in the Apache-2.0 Player); pymupdf 1.28.2 pinned in `scribe/pyproject.toml` + `uv.lock`, proven by `scribe doctor` (new `pymupdf` row: import + in-memory open). | \[x\] |
| FastAPI UI stack (`scribe[ui]` extra) | fastapi MIT, uvicorn BSD-3-Clause, starlette BSD-3-Clause, python-multipart Apache-2.0, pydantic MIT | All permissive, AGPL-compatible inside Scribe (never ships in the Player); checked 2026-10-04 via the PyPI JSON API (`license_expression`: fastapi 0.142.2 MIT, uvicorn 0.54.0 BSD-3-Clause, starlette 1.7.0 BSD-3-Clause, python-multipart 0.0.32 Apache-2.0, pydantic 2.13.5 MIT) and re-verified 2026-10-05 from installed dist metadata (same versions, same expressions). Floors pinned in `scribe/pyproject.toml` (`fastapi>=0.115.0`, `uvicorn>=0.30.0`, `starlette>=0.46.0`, `python-multipart>=0.0.18`, `pydantic>=2.9.0`, satisfying fastapi's own requirements) + `uv.lock`, proven by `scribe doctor` (new `ui` row: fastapi + uvicorn import + versions). Base CLI never imports them (lazy import with a `uv sync --extra ui` hint). Gap: full license texts not read line by line (SPDX expressions only); no vendoring, standard for permissive deps. | \[x\] |
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
| Kokoro model weights | Apache-2.0 | Model card `license: apache-2.0` (https://huggingface.co/hexgrad/Kokoro-82M), checked 2026-10-04 | [x] |
| Piper (current GPL fork) | GPL-3.0 | Older `rhasspy/piper` was MIT; check which you use | \[ \] |
| piper-tts 1.8.0 (Scribe PC engine, SW1) | GPL-3.0-or-later | Reference Piper runtime (OHF); PyPI `license` field 2026-10-05. GPL-3.0 combines into AGPL-3.0-or-later Scribe (GPLv3 section 13, combined work stays AGPL); pinned `piper-tts>=1.8.0` in `scribe/pyproject.toml` + `uv.lock`, proven by `scribe doctor` (`piper-models` row). Phonemization bundled in the wheel (internal espeak-ng data, no external install); never ships in the Player, so D-066 is untouched. | [x] |
| Piper voice models | **Each voice has its own license** | Some permissive, some restricted (for example non-commercial). Record it per voice. | \[ \] |
| Piper proof voice `en_US-lessac-low` (SW1 manual proof + SW4 mixed-engine proof) | Blizzard 2013 Lessac license (research/non-commercial, University of Edinburgh CSTR) | Per its MODEL_CARD (dataset: cstr.ed.ac.uk Blizzard 2013 Lessac, license page linked there). Local proof audio only under `models/piper/` (gitignored, never committed, never distributed); the link would not deliver permissive voices in session time (all Piper voices are 45-65 MB), so the second Piper voice is deferred breadth, not a new code path. | [x] |
| Piper SW4 proof voice `en_US-ljspeech-medium` | Public domain | Per its MODEL_CARD (dataset keithito.com LJ-Speech, license public domain). Under `models/piper/` (gitignored, never committed). | [x] |
| Piper SW4 proof voice `en_US-kathleen-low` | CC0 | Per its MODEL_CARD (dataset github.com/rhasspy/dataset-voice-kathleen, license CC0). Under `models/piper/` (gitignored, never committed). | [x] |
| espeak-ng | GPL-3.0-or-later | Used for phonemization by both engines; repo states GPL-3.0-or-later, installed 1.52.0 matches the latest release, checked 2026-10-04 | [x] |

**v1 voice palette (CP8): every voice below is a configuration inside the
Kokoro-82M weights, so the model-card Apache-2.0 license covers all of them.
Source for all rows: https://huggingface.co/hexgrad/Kokoro-82M. Models are
downloaded at runtime, never committed or bundled in the APK. Unattributable
lines fall back to the generic female/male voices.**

| Voice | Role | License | Verified |
| --- | --- | --- | --- |
| am_onyx | Narrator | Apache-2.0 (Kokoro-82M) | [x] |
| bf_isabella | Character | Apache-2.0 (Kokoro-82M) | [x] |
| bm_lewis | Character | Apache-2.0 (Kokoro-82M) | [x] |
| im_nicola | Character | Apache-2.0 (Kokoro-82M) | [x] |
| jf_alpha | Character | Apache-2.0 (Kokoro-82M) | [x] |
| zf_xiaoxiao | Character | Apache-2.0 (Kokoro-82M) | [x] |
| am_eric | Character | Apache-2.0 (Kokoro-82M) | [x] |
| af_bella | Generic female | Apache-2.0 (Kokoro-82M) | [x] |
| am_adam | Generic male | Apache-2.0 (Kokoro-82M) | [x] |

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

- [x] `LICENSE` file in each component (Player: `player/LICENSE` Apache-2.0; Scribe: `scribe/LICENSE` AGPL-3.0-or-later; root `LICENSE` MIT is the repo default) - decided D-015/D-050 on release/v1.0
- [x] `NOTICE` and `THIRD_PARTY_LICENSES.md` generated (`player/NOTICE` + `player/THIRD_PARTY_LICENSES.md`, CP8)
- [ ] All "Verified" boxes above ticked (Player section 2 + voices done; Scribe section 3 rows for bs4/lxml, pysbd, yaml/numpy/typer/rich/pytest, soundfile, pyrubberband, ffmpeg and section 4 Kokoro package/Piper still open)
- [x] Voice table complete, with per-voice licenses (section 4 palette: 9 Kokoro-82M voices, all Apache-2.0, CP8)
- [x] In-app licenses screen (Settings entry rendering the static list; legibility on the Tab E left for the user)
- [x] README states the copyright/personal-use position (verbatim paragraph, CP10)
- [ ] Contribution policy (for example DCO sign-off or a CLA) decided