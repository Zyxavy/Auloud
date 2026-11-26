package app.auloud.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP8 plus Slice 20 TR1: the shipped manifest requests INTERNET for the
 * LAN-only Wi-Fi bundle receiver (user-approved option A, superseding
 * D-016's no-network posture; logged in docs/DECISIONS.md at TR1
 * acceptance). Reads the manifest SOURCE (unit tests cannot see the
 * merged manifest); the merged-manifest side is releaseManifestCheck in
 * app/build.gradle.kts (now requires INTERNET instead of forbidding it).
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
    fun internetPermissionPresent() {
        assertTrue(
            "INTERNET permission must ship (TR1 Wi-Fi bundle receiver)",
            sourceManifest().contains("android.permission.INTERNET")
        )
    }

    @Test
    fun noWifiStatePermission() {
        assertFalse(
            "ACCESS_WIFI_STATE must not ship (TR1 reads the IP via " +
                "NetworkInterface, which needs no permission)",
            sourceManifest().contains("android.permission.ACCESS_WIFI_STATE")
        )
    }

    @Test
    fun expectedPermissionsPresent() {
        val manifest = sourceManifest()
        for (permission in listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.WAKE_LOCK",
            "android.permission.INTERNET"
        )) {
            assertTrue("expected permission $permission", manifest.contains(permission))
        }
    }

    @Test
    fun playbackServiceDeclaresMediaPlaybackType() {
        val manifest = sourceManifest()
        assertTrue(
            "PlaybackService needs foregroundServiceType mediaPlayback (API 29+)",
            manifest.contains("android:foregroundServiceType=\"mediaPlayback\"")
        )
        assertTrue(
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK must ship (API 34+)",
            manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK")
        )
    }

    @Test
    fun renderServiceDeclaresSpecialUseType() {
        val manifest = sourceManifest()
        assertTrue(
            "RenderService needs foregroundServiceType specialUse (API 34+)",
            manifest.contains("android:foregroundServiceType=\"specialUse\"")
        )
        assertTrue(
            "FOREGROUND_SERVICE_SPECIAL_USE must ship (API 34+)",
            manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
        )
        assertTrue(
            "specialUse needs its PROPERTY_SPECIAL_USE_FGS_SUBTYPE declaration",
            manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE")
        )
    }

    @Test
    fun ttsEnginesAreQueryable() {
        val manifest = sourceManifest()
        assertTrue("manifest needs a <queries> block (API 30+ package visibility)", manifest.contains("<queries>"))
        assertTrue(
            "TTS engines must be queryable on API 30+",
            manifest.contains("android.intent.action.TTS_SERVICE")
        )
    }

    @Test
    fun notificationPermissionDeclared() {
        assertTrue(
            "POST_NOTIFICATIONS must ship (API 33+ runtime permission)",
            sourceManifest().contains("android.permission.POST_NOTIFICATIONS")
        )
    }

    @Test
    fun legacyStorageCappedAtApi29() {
        val manifest = sourceManifest()
        val write = Regex("<uses-permission[^>]*WRITE_EXTERNAL_STORAGE[^>]*>").find(manifest)?.value
        val read = Regex("<uses-permission[^>]*READ_EXTERNAL_STORAGE[^>]*>").find(manifest)?.value
        assertTrue("WRITE_EXTERNAL_STORAGE element missing", write != null)
        assertTrue("READ_EXTERNAL_STORAGE element missing", read != null)
        assertTrue("WRITE_EXTERNAL_STORAGE must carry maxSdkVersion=29", write!!.contains("android:maxSdkVersion=\"29\""))
        assertTrue("READ_EXTERNAL_STORAGE must carry maxSdkVersion=32", read!!.contains("android:maxSdkVersion=\"32\""))
    }
}
