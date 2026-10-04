# Auloud Player: third-party licenses (CP8)

Versions below match `gradle/libs.versions.toml` exactly. License names were
read from each artifact's own POM `<licenses>` block in the local Gradle
cache on 2026-10-04. Test-only and build-only items never ship in the APK.

## Shipped in the app

| Library | Version | License |
| --- | --- | --- |
| Kotlin standard library | 2.0.21 | Apache-2.0 |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 |
| Jetpack Compose BOM | 2024.09.00 | Apache-2.0 |
| androidx.activity (activity-compose) | 1.9.3 | Apache-2.0 |
| Media3 (exoplayer, session) | 1.5.1 | Apache-2.0 |
| Room (runtime, ktx) | 2.6.1 | Apache-2.0 |
| kotlinx-serialization-json | 1.7.3 | Apache-2.0 |
| Coil (coil-compose) | 2.6.0 | Apache-2.0 |

## Test only (never shipped)

| Library | Version | License |
| --- | --- | --- |
| JUnit | 4.13.2 | EPL-1.0 |
| MockK | 1.13.12 | Apache-2.0 |
| Turbine | 1.2.1 | Apache-2.0 |
| androidx.test (runner) | 1.6.2 | Apache-2.0 |
| androidx.test.ext (junit) | 1.2.1 | Apache-2.0 |

## Build only (never shipped)

| Tool | Version | License |
| --- | --- | --- |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 |
| Kotlin Gradle plugins | 2.0.21 | Apache-2.0 |
| KSP | 2.0.21-1.0.28 | Apache-2.0 |

## Scribe-side voice items (PC only, never bundled in the APK)

| Item | Version | License |
| --- | --- | --- |
| Kokoro-82M model weights (incl. all palette voices) | v1.0 | Apache-2.0 |
| espeak-ng (phonemizer) | 1.52.0 | GPL-3.0-or-later |
| spaCy en_core_web_sm (language model) | 3.8.0 | MIT |

The same list (minus the build-only tools) is shown in the app under
Settings > Licenses.
