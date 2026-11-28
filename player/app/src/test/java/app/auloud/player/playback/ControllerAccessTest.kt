package app.auloud.player.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FU5: the media session admits only our own controllers and
 * system-trusted ones (notification, lock screen, Bluetooth). Plain JVM.
 */
class ControllerAccessTest {

    private val own = "app.auloud.player"

    @Test
    fun ownPackage_isAllowed_evenWhenNotTrusted() {
        assertTrue(allowController(own, own, isTrusted = false))
    }

    @Test
    fun trustedOtherPackage_isAllowed() {
        assertTrue(allowController("com.android.systemui", own, isTrusted = true))
    }

    @Test
    fun untrustedOtherPackage_isRefused() {
        assertFalse(allowController("com.example.other", own, isTrusted = false))
    }
}
