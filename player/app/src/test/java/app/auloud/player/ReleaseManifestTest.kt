package app.auloud.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP8: the shipped manifest requests no network access (D-016). Reads the
 * manifest SOURCE (unit tests cannot see the merged manifest) and fails if
 * an INTERNET permission is ever added; the merged-manifest check itself is
 * a documented release-runbook step (see docs/ReleaseSigning.md).
 *
 * Runs on plain JVM: the working directory of a module unit-test task is the
 * module directory, so `src/main/AndroidManifest.xml` resolves directly.
 */
class ReleaseManifestTest {

    private fun sourceManifest(): String {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue(
            "expected module-relative manifest at ${file.absolutePath}",
            file.exists()
        )
        return file.readText()
    }

    @Test
    fun noInternetPermission() {
        assertFalse(
            "INTERNET permission must never ship (D-016)",
            sourceManifest().contains("android.permission.INTERNET")
        )
    }

    @Test
    fun expectedPermissionsPresent() {
        val manifest = sourceManifest()
        for (permission in listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.WAKE_LOCK"
        )) {
            assertTrue("expected permission $permission", manifest.contains(permission))
        }
    }
}
