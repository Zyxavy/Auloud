package app.auloud.player.tts

import kotlin.concurrent.thread

/**
 * Runs an engine release on its own thread. Sherpa release() waits for an
 * in-flight JNI generate, so calling it on the main thread can freeze the UI.
 *
 * API 24 safe: plain Kotlin thread.
 */
fun releaseOffMain(name: String = "tts-release", block: () -> Unit) {
    thread(name = name) { block() }
}
