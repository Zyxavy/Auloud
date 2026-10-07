package app.auloud.player.settings

/**
 * CP8: release guards for debug-only UI.
 *
 * The reader preview (RA4 spike screen) and its Settings entry must be
 * unreachable in release builds. Both call sites gate on
 * [isReaderPreviewAvailable] with `BuildConfig.DEBUG`; R8 folds the
 * constant, so release APKs never execute that path. A pure function so the
 * rule itself is unit-testable without Robolectric.
 */
fun isReaderPreviewAvailable(isDebugBuild: Boolean): Boolean = isDebugBuild

/**
 * RN10: beep self-check gate (same seam as the reader preview).
 *
 * The voice-lab beep card and the beep engine wiring check this with
 * `BuildConfig.DEBUG`; R8 folds the constant, so release builds never
 * reach the beep path. Pure function so the rule is unit-testable
 * without Robolectric.
 */
fun isBeepSelfCheckAvailable(isDebugBuild: Boolean): Boolean = isDebugBuild

/**
 * RN9: render debug overlay gate (same seam as the beep card).
 *
 * The partial-book hub overlay (benchmark RTF, current chapter and
 * sentence, battery temperature, spool size) checks this with
 * `BuildConfig.DEBUG`; R8 folds the constant, so release builds never
 * render it. Pure function so the rule is unit-testable without
 * Robolectric.
 */
fun isRenderDebugAvailable(isDebugBuild: Boolean): Boolean = isDebugBuild
