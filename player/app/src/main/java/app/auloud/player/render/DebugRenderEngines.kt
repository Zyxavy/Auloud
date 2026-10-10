package app.auloud.player.render

import app.auloud.player.tts.TtsEngine

/**
 * RN10: debug-only engine wiring (Slice 10 plan RN10).
 *
 * The single place a [BeepTtsEngine] enters a registry: debug builds get
 * it, release builds get null (callers use `listOfNotNull`, so the
 * release engine list is exactly what it was before RN10). Production
 * call sites pass `BuildConfig.DEBUG`; R8 folds the constant, so the
 * release APK never instantiates the beep engine (the same seam as the
 * CP8 spike screen). Unit tests pin both branches.
 *
 * API 24 safe: pure Kotlin. No new dependency, no permission, no
 * manifest change.
 */
object DebugRenderEngines {

    /**
     * Beep engine for debug builds, null for release builds.
     *
     * A fresh instance per call (the engine holds no state, so sharing
     * is safe too; freshness keeps call sites independent).
     */
    fun beepEngineIfDebug(isDebugBuild: Boolean): TtsEngine? =
        if (isDebugBuild) BeepTtsEngine() else null
}
