# Auloud Player: third-party licenses, full flavor (VC1)

VC1 (D-126 option A): the `full` build is a GPL-3.0 combined work
(Apache-2.0 app code plus the bundled speech runtime below). The
`core` list (no GPL code) is THIRD_PARTY_LICENSES.md. The full notice
is NOTICE.full; the license note for this config is LICENSE.full.

Versions below match `gradle/libs.versions.toml` exactly. POM license
blocks were read from the local Gradle cache (sherpa-onnx 1.13.8 POM:
Apache License 2.0, confirmed 2026-10-09); the onnxruntime MIT license
is the upstream file (confirmed 2026-10-09); espeak-ng is
GPL-3.0-or-later per its repo (08-Licenses.md section 4, already
verified). Test-only and build-only items never ship in the APK.

## Shipped in the full app (same as core, plus the speech runtime)

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
| jsoup | 1.23.2 | MIT |
| sherpa-onnx (JitPack AAR, full flavor only) | 1.13.8 | Apache-2.0 (POM) |
| onnxruntime native (libonnxruntime.so inside the sherpa AAR) | as built into sherpa-onnx 1.13.8 | MIT |
| espeak-ng (static, inside the sherpa native libs) | as built into sherpa-onnx 1.13.8 | GPL-3.0-or-later |

The sherpa AAR ships one libonnxruntime.so plus three sherpa libs per
ABI (arm64-v8a, armeabi-v7a, x86, x86_64) and no separate libespeak;
the Slice 7 spike found espeak strings inside libsherpa-onnx-jni.so,
which reads as statically linked espeak-ng. That static copy is what
makes the distributed full APK a GPL-3.0 combined work. Phonemizer
voice data (espeak-ng-data) is not in the APK; it comes from the
sideloaded packs below.

## Sideloaded Piper packs (user files, never bundled in any APK)

| Item | Version | License |
| --- | --- | --- |
| Piper voice packs in /Auloud/models/ | per pack | per-pack license (check its MODEL_CARD) |
| Proof voice en_US-lessac-low | lessac pack | Blizzard 2013 Lessac (research/non-commercial) |
| Proof voice en_US-ljspeech-medium | ljspeech pack | Public domain |
| Proof voice en_US-kathleen-low | kathleen pack | CC0 |

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

## Scribe-side voice items (PC only, never bundled in any APK)

| Item | Version | License |
| --- | --- | --- |
| Kokoro-82M model weights (incl. all palette voices) | v1.0 | Apache-2.0 |
| espeak-ng (phonemizer) | 1.52.0 | GPL-3.0-or-later |
| spaCy en_core_web_sm (language model) | 3.8.0 | MIT |

The same list (minus the build-only tools) is shown in the full build
under Settings > Licenses.
