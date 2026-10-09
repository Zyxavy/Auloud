package app.auloud.player.settings

/**
 * VC1 (D-126 option A): `core`-flavor license data. This build ships no
 * GPL code: only the Apache-2.0/MIT libraries below plus the test-only
 * items (never shipped). Scribe-side voice items are listed because the
 * books you hear are made with them on the PC, not because they ship
 * here. On-device Piper voices need the `full` release (last row).
 */
val flavorLicenseHeader: String =
    "Auloud Player core is Apache-2.0 (see LICENSE). This build ships " +
        "no GPL code. The voice items below run on the PC (Scribe)."

/** Exactly what the `core` build ships, then Scribe-side voice items. */
val flavorLicenses: List<LicenseEntry> = listOf(
    LicenseEntry("Kotlin standard library", "2.0.21", "Apache-2.0", "App code language"),
    LicenseEntry("kotlinx-coroutines (Android)", "1.9.0", "Apache-2.0", "Background work and UI state"),
    LicenseEntry("Jetpack Compose BOM", "2024.09.00", "Apache-2.0", "Reader and library UI"),
    LicenseEntry("androidx.activity (Compose)", "1.9.3", "Apache-2.0", "App entry point"),
    LicenseEntry("Media3 (ExoPlayer, Session)", "1.5.1", "Apache-2.0", "Audio playback"),
    LicenseEntry("Room (Runtime, KTX)", "2.6.1", "Apache-2.0", "Library and progress storage"),
    LicenseEntry("kotlinx-serialization (JSON)", "1.7.3", "Apache-2.0", "Bundle manifest and chapter parsing"),
    LicenseEntry("Coil (Compose)", "2.6.0", "Apache-2.0", "Cover art loading"),
    LicenseEntry("jsoup", "1.23.2", "MIT", "On-device EPUB HTML parsing"),
    LicenseEntry("JUnit", "4.13.2", "EPL-1.0", "Unit tests only, not shipped in the app"),
    LicenseEntry("MockK", "1.13.12", "Apache-2.0", "Unit tests only, not shipped in the app"),
    LicenseEntry("Turbine", "1.2.1", "Apache-2.0", "Unit tests only, not shipped in the app"),
    LicenseEntry("androidx.test (runner)", "1.6.2", "Apache-2.0", "Instrumented tests only, not shipped in the app"),
    LicenseEntry("androidx.test.ext (junit)", "1.2.1", "Apache-2.0", "Instrumented tests only, not shipped in the app"),
    LicenseEntry(
        "Kokoro-82M voices",
        "v1.0 (54 voices)",
        "Apache-2.0",
        "Scribe-side (PC): narrator am_onyx, bf_isabella, bm_lewis, " +
            "im_nicola, jf_alpha, zf_xiaoxiao, am_eric, af_bella, am_adam. " +
            "Not bundled in this app."
    ),
    LicenseEntry(
        "espeak-ng",
        "1.52.0",
        "GPL-3.0-or-later",
        "Scribe-side (PC) phonemizer. Not bundled in this app."
    ),
    LicenseEntry(
        "spaCy en_core_web_sm",
        "3.8.0",
        "MIT",
        "Scribe-side (PC) language model. Not bundled in this app."
    ),
    LicenseEntry(
        "On-device Piper voices",
        "full release only",
        "per-pack license",
        "Not in this build: sideloaded Piper packs need the full release, " +
            "which bundles the speech runtime. Each pack has its own license."
    )
)
