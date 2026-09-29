package app.auloud.player.storage

import android.content.Intent

/**
 * WP3/WP5 refinement: system folder picker (`ACTION_OPEN_DOCUMENT_TREE`,
 * API 21+, fine for minSdk 24) for adding watch folders — including the
 * microSD card, which arrives with a persistable URI permission instead of a
 * raw path.
 *
 * [action] and [flags] are pure values (plain-JVM-tested); [newIntent] builds
 * the framework `Intent` on-device only. After the picker returns, callers
 * must call `takePersistableUriPermission(uri, persistFlags())` before
 * persisting the tree string in [WatchFolderStore].
 *
 * DEVICE-TEST (user on the Tab E): pick internal `Auloud/`, the SD-card
 * `Auloud/`, and a nested folder; deny/abandon the picker; reboot and
 * confirm the grant survived (books still list without re-picking).
 */
object WatchFolderIntents {

    /** `android.intent.action.OPEN_DOCUMENT_TREE` (API 21+). */
    fun action(): String = Intent.ACTION_OPEN_DOCUMENT_TREE

    /**
     * Read + write + persistable: the grant must survive reboots, and audio
     * playback reads through the document URI.
     */
    fun flags(): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    /** Take flags for `takePersistableUriPermission` (read + write). */
    fun persistFlags(): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Framework intent; call on-device only (not from unit tests). */
    fun newIntent(): Intent = Intent(action()).addFlags(flags())
}
