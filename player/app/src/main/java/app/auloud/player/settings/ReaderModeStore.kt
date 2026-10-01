package app.auloud.player.settings

import app.auloud.player.reader.ReaderMode

/**
 * RA7: reader mode + reading behavior settings (global, no database).
 *
 * The mode is restored on launch and shared by every book; [keepScreenOn]
 * defaults to on (the reader holds the screen while visible in Read and
 * Read + listen). Backed by `SharedPreferences` in production, fakes in
 * tests. Font size joins this store in RA10.
 */
interface ReaderModeStore {
    fun mode(): ReaderMode
    fun setMode(mode: ReaderMode)
    fun keepScreenOn(): Boolean
    fun setKeepScreenOn(keepOn: Boolean)
}
