package app.auloud.player.tts

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsReleaseTest {

    @Test
    fun releaseOffMain_runsBlockOffCallerThread() {
        val ran = AtomicReference<Thread>()
        val done = CountDownLatch(1)
        releaseOffMain {
            ran.set(Thread.currentThread())
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotEquals(Thread.currentThread(), ran.get())
    }

    @Test
    fun releaseOffMain_defaultThreadName() {
        val name = AtomicReference<String>()
        val done = CountDownLatch(1)
        releaseOffMain {
            name.set(Thread.currentThread().name)
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("tts-release", name.get())
    }
}
