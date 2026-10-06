package app.auloud.player.settings

import app.auloud.player.reader.ReaderFontSize
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

    /** RA8: global playback speed (0.75x-2.0x). */
    fun playbackSpeed(): Float
    fun setPlaybackSpeed(speed: Float)

    /** RA10: reader font size. */
    fun fontSize(): ReaderFontSize
    fun setFontSize(size: ReaderFontSize)

    /**
     * IN9: dialogue marking (dialogue sentences in the reader drawn in the
     * accent color). Defaults to on; the reader re-reads on open, so changes
     * apply then like the other reading prefs.
     */
    fun dialogueMarking(): Boolean
    fun setDialogueMarking(marked: Boolean)
}
