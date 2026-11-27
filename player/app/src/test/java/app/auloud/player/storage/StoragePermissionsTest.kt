package app.auloud.player.storage

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: storage-permission contract verifies on plain JVM.
 * Below API 30 READ + WRITE are requested together and the grant check is
 * the AND of both. From API 30 (D-214) no storage permission is requested.
 * API 33+ adds POST_NOTIFICATIONS to the launch request, but it never gates
 * storage. (`Manifest.permission` values are compile-time constants, so no
 * framework runs here.)
 */
class StoragePermissionsTest {

    private val read = Manifest.permission.READ_EXTERNAL_STORAGE
    private val write = Manifest.permission.WRITE_EXTERNAL_STORAGE
    private val notifications = Manifest.permission.POST_NOTIFICATIONS

    @Test
    fun required_api24_containsReadAndWrite() {
        assertEquals(setOf(read, write), StoragePermissions.required(24).toSet())
    }

    @Test
    fun required_api29_readAndWrite() {
        assertEquals(setOf(read, write), StoragePermissions.required(29).toSet())
    }

    @Test
    fun required_api30_empty() {
        assertTrue(StoragePermissions.required(30).isEmpty())
    }

    @Test
    fun required_api33_empty() {
        assertTrue(StoragePermissions.required(33).isEmpty())
    }

    @Test
    fun allGranted_api24_trueOnlyWhenBothGranted() {
        assertTrue(StoragePermissions.allGranted(24) { true })
        assertFalse(StoragePermissions.allGranted(24) { false })
        assertFalse(StoragePermissions.allGranted(24) { it == read })
        assertFalse(StoragePermissions.allGranted(24) { it == write })
    }

    @Test
    fun allGranted_api30_trueWithoutAnyGrant() {
        assertTrue(StoragePermissions.allGranted(30) { false })
    }

    @Test
    fun allGranted_ignoresNotifications() {
        assertTrue(StoragePermissions.allGranted(33) { it != notifications })
    }

    @Test
    fun toRequest_api33_onlyNotifications() {
        assertEquals(setOf(notifications), StoragePermissions.toRequest(33).toSet())
        assertEquals(1, StoragePermissions.toRequest(33).size)
    }

    @Test
    fun toRequest_api24_readWrite() {
        assertEquals(setOf(read, write), StoragePermissions.toRequest(24).toSet())
    }
}
