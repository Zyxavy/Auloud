package app.auloud.player.tts

import android.content.Context
import app.auloud.player.render.BeepTtsEngine

/**
 * VS5: shared live engine version strings for staleness compares.
 *
 * Hoisted from the VS4 book-voice host (which owned a private copy):
 * the library stale scan (via MainActivity) and the complete-book stale
 * load (via BookScreen) need the same sources as the render service
 * (`RenderService.engineVersion`: system TTS package version, the piper
 * build pin, the beep pin). Unknown namespaces read as null, never
 * current (an unbuildable expectation classifies stale-ward per VS2).
 *
 * VC1: the piper pin lives per flavor ([piperEngineVersion]): null in
 * `core` (no bundled engine), the sherpa pin in `full`. API 24 safe:
 * package-manager read with a best-effort fallback.
 */
internal fun bookVoiceVersionOf(appContext: Context): (String) -> String? = { namespace ->
    when (namespace) {
        SystemTtsAdapter.SYSTEM_NAMESPACE -> bookVoiceSystemVersion(appContext)
        SherpaPiperEngine.PIPER_NAMESPACE -> piperEngineVersion()
        KITTEN_NAMESPACE -> kittenEngineVersion()
        BeepTtsEngine.NAMESPACE -> BeepTtsEngine.VERSION
        else -> null
    }
}

/** VS5: system TTS package version, `"system"` when unreadable. */
private fun bookVoiceSystemVersion(appContext: Context): String {
    return try {
        val engine = android.provider.Settings.Secure.getString(
            appContext.contentResolver,
            android.provider.Settings.Secure.TTS_DEFAULT_SYNTH
        )
        val info = appContext.packageManager.getPackageInfo(engine, 0)
        @Suppress("DEPRECATION")
        info.versionName?.takeIf { !it.isNullOrBlank() } ?: "system"
    } catch (_: Exception) {
        "system"
    }
}
