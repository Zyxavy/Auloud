package app.auloud.player.tts

/**
 * VC1 (D-126 option A): `full` build pin for the bundled Piper engine
 * (mirrors the render service literal). Pinned to the exact JitPack
 * AAR in `gradle/libs.versions.toml`: sherpa-onnx 1.13.8.
 */
internal fun piperEngineVersion(): String = "sherpa-1.13.8"
