package app.auloud.player.ingest

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * IN8: per-book import serialization (Slice 9).
 *
 * [IngestPipeline] wraps each import in [withBookLock] keyed by the
 * lowercase source SHA, so two concurrent imports of the SAME book can
 * never race through the duplicate scan into the rename: the second
 * waiter blocks until the first finishes, then sees the finished book
 * in its duplicate scan and reports `Duplicate`. Imports of DIFFERENT
 * books use different keys and still run concurrently.
 *
 * Entries are never removed: one small mutex per distinct imported book
 * is negligible next to the book itself, and removal would race an
 * incoming waiter against the remover. API 24 safe: coroutines plus
 * `java.util.concurrent` only, no Android classes, JVM-testable.
 */
object ImportLocks {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs [block] holding the mutex for [key] (empty keys get their own
     * shared bucket; callers pass real hashes). Cancellation while waiting
     * throws without entering; cancellation inside runs [block]'s own
     * cleanup (the pipeline deletes its temp/final residue).
     */
    suspend fun <T> withBookLock(key: String, block: suspend () -> T): T {
        val mutex = locks.getOrPut(key) { Mutex() }
        return mutex.withLock { block() }
    }
}
