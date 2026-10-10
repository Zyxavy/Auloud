package app.auloud.player.tts

/**
 * VC1 (D-126 option A): `full` build pin for the bundled Piper engine
 * (mirrors the render service literal). Pinned to the exact JitPack
 * AAR in `gradle/libs.versions.toml`: sherpa-onnx 1.13.8.
 */
internal fun piperEngineVersion(): String = "sherpa-1.13.8"

/**
 * KT2: same runtime pin for the bundled Kitten engine (one sherpa-onnx
 * AAR serves both engines; KT6 refines the fingerprint version with the
 * pack variant).
 */
internal fun kittenEngineVersion(): String = "sherpa-1.13.8"
