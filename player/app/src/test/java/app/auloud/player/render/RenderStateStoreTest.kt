package app.auloud.player.render

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RN3: [RenderStateStore] persistence tests on plain JVM.
 *
 * A fake [RenderFileIo] stands in for `java.io.File` so the atomic
 * temp-then-rename retry math (the D-054 Windows answer) runs with no
 * real sleeps: tests count sleeper calls instead of waiting.
 */
class RenderStateStoreTest {

    private class FakeIo(
        var renameFailuresLeft: Int = 0,
        var readFailuresLeft: Int = 0
    ) : RenderFileIo {
        val files = HashMap<String, String>()
        var renames = 0
        var sleeps = 0
        val sleeper: (Long) -> Unit = { sleeps++ }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String {
            if (readFailuresLeft > 0) {
                readFailuresLeft--
                throw IOException("$path: transient lock")
            }
            return files[path] ?: throw IOException("$path: file not found or not readable")
        }

        override fun writeText(path: String, text: String) {
            files[path] = text
        }

        override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean {
            renames++
            if (renameFailuresLeft > 0) {
                renameFailuresLeft--
                return false
            }
            val text = files[tmpPath] ?: return false
            files[targetPath] = text
            files.remove(tmpPath)
            return true
        }

        override fun deleteIfExists(path: String) {
            files.remove(path)
        }
    }

    private var now = 50_000L
    private val clock: () -> Long = { now }

    private fun job(state: RenderJobState = RenderJobState.QUEUED): RenderJob {
        val plan = RenderPlan(
            bookId = "b1", chapterCount = 4, startChapter = 1,
            scope = RenderScope.NextN(2), orderedChapters = listOf(1, 2), createdAt = now
        )
        return RenderJob(
            bookId = "b1", state = state, plan = plan,
            completedChapters = emptyList(), currentChapter = null,
            createdAt = now, updatedAt = now
        )
    }

    @Test
    fun statePath_livesInsideTheBookFolder() {
        assertEquals("/books/b1/render-job.json", RenderStateStore.statePath("/books/b1"))
        assertEquals(
            "/books/b1/render-job.json.tmp",
            RenderStateStore.tempPath("/books/b1")
        )
        assertEquals("render-job.json", RenderStateStore.STATE_FILE)
    }

    @Test
    fun save_thenLoad_roundTrips() {
        val io = FakeIo()
        val original = job(RenderJobState.RUNNING).copy(
            completedChapters = listOf(1), currentChapter = 2
        )

        assertTrue(RenderStateStore.save("/books/b1", original, io, io.sleeper).isSuccess)
        val loaded = RenderStateStore.load("/books/b1", io, io.sleeper).getOrThrow()

        assertEquals(original, loaded)
    }

    @Test
    fun save_roundTripsEveryScope() {
        val scopes = listOf<RenderScope>(
            RenderScope.WholeBook,
            RenderScope.FromHere,
            RenderScope.NextN(3)
        )
        for (scope in scopes) {
            val io = FakeIo()
            val plan = RenderPlan("b1", 5, 2, scope, listOf(2, 3), now)
            val original = RenderJob("b1", RenderJobState.PAUSED, plan, listOf(2), 3, null, now, now)

            assertTrue(RenderStateStore.save("/books/b1", original, io, io.sleeper).isSuccess)
            assertEquals(original, RenderStateStore.load("/books/b1", io, io.sleeper).getOrThrow())
        }
    }

    @Test
    fun load_absentFile_isNull() {
        val io = FakeIo()

        assertNull(RenderStateStore.load("/books/b1", io, io.sleeper).getOrThrow())
    }

    @Test
    fun load_corruptJson_failsNamed() {
        val io = FakeIo()
        io.files[RenderStateStore.statePath("/books/b1")] = "{ not json"

        val result = RenderStateStore.load("/books/b1", io, io.sleeper)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("render-job.json"))
    }

    @Test
    fun load_wrongVersion_failsNamed() {
        val io = FakeIo()
        io.files[RenderStateStore.statePath("/books/b1")] =
            """{"version":99,"bookId":"b1","state":"QUEUED"}"""

        val result = RenderStateStore.load("/books/b1", io, io.sleeper)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("version"))
    }

    @Test
    fun save_retriesTransientRenameFailures() {
        val io = FakeIo(renameFailuresLeft = 2)

        val result = RenderStateStore.save("/books/b1", job(), io, io.sleeper)

        assertTrue(result.isSuccess)
        assertEquals(3, io.renames)
        assertEquals(2, io.sleeps)
        assertEquals(job(), RenderStateStore.load("/books/b1", io, io.sleeper).getOrThrow())
    }

    @Test
    fun save_exhaustedRetries_failsNamed() {
        val io = FakeIo(renameFailuresLeft = 999)

        val result = RenderStateStore.save("/books/b1", job(), io, io.sleeper)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("render-job.json"))
        assertEquals(RenderStateStore.WRITE_RETRIES, io.renames)
    }

    @Test
    fun load_retriesTransientReadFailures() {
        val io = FakeIo()
        assertTrue(RenderStateStore.save("/books/b1", job(), io, io.sleeper).isSuccess)
        io.readFailuresLeft = 2
        io.sleeps = 0

        val loaded = RenderStateStore.load("/books/b1", io, io.sleeper).getOrThrow()

        assertEquals(job(), loaded)
        assertEquals(2, io.sleeps)
    }

    @Test
    fun markInterrupted_convertsRunningAndPaused_only() {
        for (state in listOf(RenderJobState.RUNNING, RenderJobState.PAUSED)) {
            val io = FakeIo()
            assertTrue(RenderStateStore.save("/books/b1", job(state), io, io.sleeper).isSuccess)

            val marked = RenderStateStore.markInterruptedIfActive("/books/b1", io, clock, io.sleeper)
                .getOrThrow()!!

            assertEquals(RenderJobState.INTERRUPTED, marked.state)
            // Persisted, so a second recovery sees the same state.
            assertEquals(
                RenderJobState.INTERRUPTED,
                RenderStateStore.load("/books/b1", io, io.sleeper).getOrThrow()!!.state
            )
        }
        for (state in listOf(
            RenderJobState.QUEUED, RenderJobState.DONE,
            RenderJobState.FAILED, RenderJobState.CANCELLED,
            RenderJobState.INTERRUPTED
        )) {
            val io = FakeIo()
            val original = job(state).copy(error = if (state == RenderJobState.FAILED) "x" else null)
            assertTrue(RenderStateStore.save("/books/b1", original, io, io.sleeper).isSuccess)

            val kept = RenderStateStore.markInterruptedIfActive("/books/b1", io, clock, io.sleeper)
                .getOrThrow()!!

            assertEquals(state, kept.state)
        }
    }

    @Test
    fun interrupted_thenResumed_survivesARestart() {
        val io = FakeIo()
        var stored = RenderJobs.transition(job(), RenderJobState.RUNNING, clock)
        stored = RenderJobs.onChapterDone(stored, 1, clock)
        assertTrue(RenderStateStore.save("/books/b1", stored, io, io.sleeper).isSuccess)

        // The process dies here; a fresh start recovers without redoing
        // the finished chapter, then resumes it.
        val recovered = RenderStateStore.markInterruptedIfActive("/books/b1", io, clock, io.sleeper)
            .getOrThrow()!!
        assertEquals(RenderJobState.INTERRUPTED, recovered.state)
        assertEquals(listOf(1), recovered.completedChapters)

        val resumed = RenderJobs.transition(recovered, RenderJobState.RUNNING, clock)
        assertEquals(2, resumed.currentChapter)
        assertEquals(listOf(1), resumed.completedChapters)
    }

    @Test
    fun toJson_isStableAcrossWrites() {
        val first = RenderStateStore.toJson(job())
        val second = RenderStateStore.toJson(job())

        assertEquals(first, second)
    }

    @Test
    fun failedState_preservesErrorThroughStore() {
        val io = FakeIo()
        val failed = RenderJobs.transition(job(RenderJobState.RUNNING), RenderJobState.FAILED, clock, error = "no engine")
        assertTrue(RenderStateStore.save("/books/b1", failed, io, io.sleeper).isSuccess)

        val loaded = RenderStateStore.load("/books/b1", io, io.sleeper).getOrThrow()!!

        assertEquals(RenderJobState.FAILED, loaded.state)
        assertEquals("no engine", loaded.error)
    }

    private fun <T> Result<T>.getOrThrow(): T = getOrElse { throw it }
}
