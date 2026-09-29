package app.auloud.player.storage

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WP3/WP5 refinement: storage-permission contract verifies on plain JVM —
 * READ + WRITE are requested together, and the grant check is the AND of
 * both. (`Manifest.permission` values are compile-time constants, so no
 * framework runs here.)
 */
class StoragePermissionsTest {

    @Test
    fun required_containsReadAndWrite() {
        val required = StoragePermissions.required().toSet()

        assertEquals(
            setOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            required
        )
    }

    @Test
    fun allGranted_trueOnlyWhenBothGranted() {
        assertTrue(StoragePermissions.allGranted { true })
        assertFalse(StoragePermissions.allGranted { false })
        assertFalse(
            StoragePermissions.allGranted { it == Manifest.permission.READ_EXTERNAL_STORAGE }
        )
        assertFalse(
            StoragePermissions.allGranted { it == Manifest.permission.WRITE_EXTERNAL_STORAGE }
        )
    }
}
