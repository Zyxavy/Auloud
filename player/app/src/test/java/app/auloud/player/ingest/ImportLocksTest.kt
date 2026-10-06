package app.auloud.player.ingest

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IN8: [ImportLocks] serialization on plain JVM (Slice 9).
 *
 * Same-key holders never overlap; different keys run together. The
 * pipeline-level proof (two concurrent same-book imports end as one
 * `Imported` plus one `Duplicate`, never a rename `Failed`) lives in
 * `IngestPipelineTest`.
 */
class ImportLocksTest {

    @Test
    fun sameKey_holdersNeverOverlap(): Unit = runBlocking {
        val active = Collections.synchronizedSet(mutableSetOf<String>())
        var maxActive = 0
        val release = CompletableDeferred<Unit>()

        suspend fun holder(): String = ImportLocks.withBookLock("book") {
            assertTrue("same-key overlap", active.add("book"))
            maxActive = maxOf(maxActive, active.size)
            release.await()
            active.remove("book")
            "done"
        }

        val first = async { holder() }
        delay(50)
        val second = async { holder() }
        delay(50)
        release.complete(Unit)
        assertEquals(listOf("done", "done"), awaitAll(first, second))
        assertEquals(1, maxActive)
    }

    @Test
    fun differentKeys_runTogether(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            ImportLocks.withBookLock("a") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        // The second key must NOT wait for the first holder's release.
        val second = async {
            ImportLocks.withBookLock("b") { "b-done" }
        }
        assertEquals("b-done", second.await())
        release.complete(Unit)
        first.await()
    }
}
