package app.auloud.player.storage

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OPEN3: the stall guard must resume the caller even when the guarded
 * call never returns. The pre-OPEN3 shape (`withTimeout` over a blocking
 * read) measured 5.016s for a 100ms timeout in ChapterLoadTest, so these
 * tests bound the wall clock, not just the outcome.
 */
class StallGuardTest {

    @Test
    fun blockedRead_reportsStallWithoutWaiting() = runBlocking {
        val latch = CountDownLatch(1)
        val started = System.currentTimeMillis()
        val outcome = try {
            guardedRead(timeoutMs = 100L) {
                latch.await()
                "late"
            }
        } finally {
            latch.countDown()
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("must not wait for the read, took ${elapsed}ms", elapsed < 5_000L)
        val failed = outcome as? ReadGuardOutcome.Failed
            ?: throw AssertionError("want Failed, got $outcome")
        assertTrue("a stalled read reports stalled", failed.stalled)
    }

    @Test
    fun fastRead_returnsValue() = runBlocking {
        val outcome = guardedRead { "ok" }
        assertEquals("ok", (outcome as ReadGuardOutcome.Ok).value)
    }

    @Test
    fun rogueThrowable_namesClassWithoutStall() = runBlocking {
        val outcome = guardedRead { throw UnsatisfiedLinkError("no native lib here") }
        val failed = outcome as? ReadGuardOutcome.Failed
            ?: throw AssertionError("want Failed, got $outcome")
        assertTrue(!failed.stalled)
        assertEquals("UnsatisfiedLinkError", failed.cause?.javaClass?.simpleName)
    }

    @Test
    fun slowButCompletingRead_returnsItsValue() = runBlocking {
        // Completes inside the budget: no stall, the value comes through.
        val outcome = guardedRead(timeoutMs = 5_000L) {
            TimeUnit.MILLISECONDS.sleep(50L)
            "done"
        }
        assertEquals("done", (outcome as ReadGuardOutcome.Ok).value)
    }
}
