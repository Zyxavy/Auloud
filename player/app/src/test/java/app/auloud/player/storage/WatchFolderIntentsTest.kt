package app.auloud.player.storage

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: folder-picker intent construction verifies on plain
 * JVM — the action is `ACTION_OPEN_DOCUMENT_TREE` (API 21+, fine for
 * minSdk 24) and the flags carry read + write + persistable. (Intent action
 * and flag values are compile-time constants, so no framework runs here;
 * [WatchFolderIntents.newIntent] itself is device-only.)
 */
class WatchFolderIntentsTest {

    @Test
    fun action_isOpenDocumentTree() {
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, WatchFolderIntents.action())
        assertEquals("android.intent.action.OPEN_DOCUMENT_TREE", WatchFolderIntents.action())
    }

    @Test
    fun flags_includeReadWriteAndPersistable() {
        val flags = WatchFolderIntents.flags()

        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0)
    }

    @Test
    fun persistFlags_includeReadAndWrite() {
        val flags = WatchFolderIntents.persistFlags()

        assertTrue(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }
}
