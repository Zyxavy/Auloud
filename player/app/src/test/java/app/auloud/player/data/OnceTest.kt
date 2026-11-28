package app.auloud.player.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** FU4: [Once] memoizes one value per process; AuloudDatabase.open relies on it. */
class OnceTest {

    @Test
    fun get_concurrentCallers_getSameInstanceAndCreateRunsOnce() {
        val once = Once<Any>()
        val creates = AtomicInteger(0)
        val go = CountDownLatch(1)
        val results = arrayOfNulls<Any>(8)
        val threads = (0 until 8).map { i ->
            Thread {
                go.await()
                results[i] = once.get {
                    creates.incrementAndGet()
                    Thread.sleep(20)
                    Any()
                }
            }
        }
        threads.forEach { it.start() }
        go.countDown()
        threads.forEach { it.join() }

        assertEquals(1, creates.get())
        results.forEach { assertSame(results[0], it) }
    }

    @Test
    fun get_afterFirstCall_keepsFirstValueAndSkipsCreate() {
        val once = Once<String>()

        assertEquals("first", once.get { "first" })
        assertEquals("first", once.get { error("create must not run again") })
    }
}
