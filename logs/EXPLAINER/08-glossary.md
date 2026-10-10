# Glossary

One meaning per word, as used in this repo.

- **Scribe**: the PC program that converts ebooks into bundles. Python, AGPL-3.0.
- **Player**: the Android app that imports, renders, and plays books. Kotlin, dual-licensed.
- **Bundle**: a folder with `manifest.json`, chapter MP3s, and chapter sentence JSON. The contract in `docs/03-BundleSpec.md`.
- **Cast**: the `cast.yaml` mapping of roles to voices, plus overrides. Human-editable.
- **Role**: narrator, a named character, or a generic fallback (`default_female`, `default_male`).
- **Attribution**: deciding which role speaks a quoted line. Rule-based, with confidence.
- **sid**: sentence id, 1-based per chapter. The unit of highlight, saves, and streams.
- **split_pair**: two halves of one sentence split at a quote boundary. Rendered with a 100 ms tag pause.
- **render_state**: per-chapter status on-device: `none`, `partial`, `complete`.
- **Stale**: a rendered chapter whose voices no longer match the current settings. Needs re-render.
- **Fingerprint**: the hash of engine, voices, speeds, and versions that decides staleness.
- **Stream**: live `speak()` playback of an unrendered chapter. No audio file involved.
- **Slice**: a planned build unit with work packages (WP/ST) and a done gate. v1 had slices 1-6, v2 has 7-13.
- **Gate**: a measurement plus a verdict that decides whether work continues (streaming RTF, encoder offset).
- **core / full**: Player flavors. `core` is Apache-2.0 with system voices; `full` adds Piper and is GPL-3.0.
- **GATE_PASSED**: the streaming flag, flipped true by the accepted D-138 verdict.
- **Device-run**: any check that needs physical hardware. Agents never claim it; only device verdicts tick those boxes.
