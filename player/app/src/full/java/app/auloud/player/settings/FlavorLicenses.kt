package app.auloud.player.settings

/**
 * VC1 (D-126 option A): `full`-flavor license data. This build is a
 * GPL-3.0 combined work: Apache-2.0 app code plus the bundled
 * sherpa-onnx runtime whose native libs include static espeak-ng
 * (GPL-3.0). The GPL-3.0 text and the written source offer ship inside
 * this build (see the assets); the per-flavor notice is
 * `player/NOTICE.full`. Scribe-side voice items run on the PC.
 */
val flavorLicenseHeader: String =
    "Auloud Player full is a GPL-3.0 combined work (Apache-2.0 app code " +
        "plus bundled sherpa-onnx with static espeak-ng). The GPL-3.0 " +
        "license text and the written source offer ship in this build. " +
        "The Scribe-side items below run on the PC."

/** Everything `core` ships, plus the bundled speech runtime and packs. */
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
        "sherpa-onnx",
        "1.13.8 (JitPack AAR)",
        "Apache-2.0 (POM)",
        "Bundled speech runtime. Its native libs include static espeak-ng " +
            "(GPL-3.0), which makes this build a GPL-3.0 combined work."
    ),
    LicenseEntry(
        "onnxruntime",
        "as built into sherpa-onnx 1.13.8",
        "MIT",
        "Bundled inference runtime (libonnxruntime.so inside the sherpa AAR)."
    ),
    LicenseEntry(
        "espeak-ng (bundled)",
        "as built into sherpa-onnx 1.13.8",
        "GPL-3.0-or-later",
        "Bundled static phonemizer inside the sherpa native libs. Pack " +
            "voice data (espeak-ng-data) comes from sideloaded packs."
    ),
    LicenseEntry(
        "Piper voice packs",
        "sideloaded by you",
        "per-pack license",
        "Copied with a file manager into /Auloud/models/, never bundled. " +
            "Each pack has its own license (check its MODEL_CARD). Proof " +
            "voices: en_US-lessac-low (research/non-commercial), " +
            "en_US-ljspeech-medium (public domain), en_US-kathleen-low (CC0)."
    ),
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
        "Scribe-side (PC) phonemizer. Separate from the bundled copy above."
    ),
    LicenseEntry(
        "spaCy en_core_web_sm",
        "3.8.0",
        "MIT",
        "Scribe-side (PC) language model. Not bundled in this app."
    )
)
