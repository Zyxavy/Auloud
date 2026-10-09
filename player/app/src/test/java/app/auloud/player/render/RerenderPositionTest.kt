package app.auloud.player.render

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence
import app.auloud.player.data.ProgressEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * VS3: re-render position conversion through sentence ids (D-117).
 *
 * Old milliseconds find their sentence in the old timings, then the
 * sid maps into the new timings via [RenderProgress.convertOnRender].
 * Cancel or failure skips the call entirely so old audio plus the old
 * position stay valid (covered by the swap cancel tests).
 */
class RerenderPositionTest {

    private fun oldChapter(): ChapterText = ChapterText(
        specVersion = "2.0",
        chapter = 1,
        title = "Ch 1",
        durationMs = 2000L,
        blocks = listOf(
            Block(
                id = 1,
                type = "para",
                sentences = listOf(
                    Sentence(sid = 1, speaker = "narrator", startMs = 0L, endMs = 500L, text = "Hello. "),
                    Sentence(sid = 2, speaker = "dialogue", startMs = 750L, endMs = 1250L, text = "Hi. "),
                    Sentence(sid = 3, speaker = "narrator", startMs = 1500L, endMs = 2000L, text = "Bye. ")
                )
            )
        )
    )

    private fun newChapter(): ChapterText = ChapterText(
        specVersion = "2.0",
        chapter = 1,
        title = "Ch 1",
        durationMs = 3000L,
        blocks = listOf(
            Block(
                id = 1,
                type = "para",
                sentences = listOf(
                    Sentence(sid = 1, speaker = "narrator", startMs = 0L, endMs = 800L, text = "Hello. "),
                    Sentence(sid = 2, speaker = "dialogue", startMs = 1000L, endMs = 1800L, text = "Hi. "),
                    Sentence(sid = 3, speaker = "narrator", startMs = 2200L, endMs = 3000L, text = "Bye. ")
                )
            )
        )
    )

    @Test
    fun sidForMs_startMiddleEnd() {
        val old = oldChapter()
        assertEquals(1, RenderProgress.sidForMs(old, 0L))
        assertEquals(1, RenderProgress.sidForMs(old, 499L))
        // Gap 500..749 belongs to the previous sentence (highlight holds).
        assertEquals(1, RenderProgress.sidForMs(old, 600L))
        assertEquals(2, RenderProgress.sidForMs(old, 750L))
        assertEquals(2, RenderProgress.sidForMs(old, 1249L))
        assertEquals(3, RenderProgress.sidForMs(old, 1500L))
        assertEquals(3, RenderProgress.sidForMs(old, 1999L))
        assertEquals(3, RenderProgress.sidForMs(old, 5000L))
    }

    @Test
    fun convert_start_middle_end() {
        val old = oldChapter()
        val new = newChapter()
        // Start of chapter: old 0ms is sid 1, new sid 1 starts at 0.
        val atStart = ProgressEntity("b1", 0, 0L, 10L, sentenceSid = null)
        assertEquals(0L, RenderProgress.convertOnRerender(atStart, 0, old, new)?.positionMs)
        // Middle: old 800ms is sid 2 (750..1250), new sid 2 starts at 1000.
        val mid = ProgressEntity("b1", 0, 800L, 10L, sentenceSid = null)
        assertEquals(1000L, RenderProgress.convertOnRerender(mid, 0, old, new)?.positionMs)
        // End: old 1900ms is sid 3, new sid 3 starts at 2200.
        val end = ProgressEntity("b1", 0, 1900L, 10L, sentenceSid = null)
        assertEquals(2200L, RenderProgress.convertOnRerender(end, 0, old, new)?.positionMs)
        // Converted rows are ms rows (sid cleared).
        assertNull(RenderProgress.convertOnRerender(mid, 0, old, new)?.sentenceSid)
    }

    @Test
    fun convert_otherChapter_untouched() {
        val saved = ProgressEntity("b1", 1, 800L, 10L, sentenceSid = null)
        assertEquals(saved, RenderProgress.convertOnRerender(saved, 0, oldChapter(), newChapter()))
    }

    @Test
    fun convert_sidSave_delegatesToFirstRender() {
        val saved = ProgressEntity("b1", 0, 0L, 10L, sentenceSid = 2)
        val converted = RenderProgress.convertOnRerender(saved, 0, oldChapter(), newChapter())
        assertEquals(1000L, converted?.positionMs)
        assertNull(converted?.sentenceSid)
    }

    @Test
    fun convert_untimedOldOrNew_untouched() {
        val untimed = oldChapter().copy(durationMs = null)
        val saved = ProgressEntity("b1", 0, 800L, 10L, sentenceSid = null)
        assertEquals(saved, RenderProgress.convertOnRerender(saved, 0, untimed, newChapter()))
        assertEquals(saved, RenderProgress.convertOnRerender(saved, 0, oldChapter(), untimed))
    }

    @Test
    fun convert_nullSaved_isNull() {
        assertNull(RenderProgress.convertOnRerender(null, 0, oldChapter(), newChapter()))
    }

    @Test
    fun afterSwap_readsNewJsonAndDelegates() {
        // Wired path: the service calls convertAfterSwap after a
        // successful finalize, reading the freshly written JSON from
        // disk and delegating to convertOnRerender (no inline duplicate).
        val io = FakeAfterSwapIo()
        val bundleDir = "/books/b1"
        io.files["$bundleDir/text/ch001.json"] = newChapterJson()
        val old = oldChapter()
        val saved = ProgressEntity("b1", 0, 800L, 10L, sentenceSid = null)

        val converted = RerenderProgressAfterSwap.convertAfterSwap(
            bundleDir, 1, saved, 0, old, io
        )

        // Old 800ms is sid 2; new sid 2 starts at 1000.
        assertEquals(1000L, converted?.positionMs)
        assertNull(converted?.sentenceSid)
    }

    @Test
    fun afterSwap_unreadableNewJson_keepsOld() {
        val io = FakeAfterSwapIo()
        val saved = ProgressEntity("b1", 0, 800L, 10L, sentenceSid = null)

        val converted = RerenderProgressAfterSwap.convertAfterSwap(
            "/books/b1", 1, saved, 0, oldChapter(), io
        )

        assertEquals(saved, converted)
    }

    private fun newChapterJson(): String =
        """{"spec_version":"2.0","chapter":1,"title":"Ch 1","duration_ms":3000,"blocks":[{"id":1,"type":"para","sentences":[{"sid":1,"speaker":"narrator","start_ms":0,"end_ms":800,"text":"Hello. "},{"sid":2,"speaker":"dialogue","start_ms":1000,"end_ms":1800,"text":"Hi. "},{"sid":3,"speaker":"narrator","start_ms":2200,"end_ms":3000,"text":"Bye. "}]}]}"""

    private class FakeAfterSwapIo : RenderFileIo {
        val files = HashMap<String, String>()

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun readText(path: String): String =
            files[path] ?: throw java.io.IOException("$path: file not found or not readable")

        override fun writeText(path: String, text: String) {
            files[path] = text
        }

        override fun renameTempToTarget(tmpPath: String, targetPath: String): Boolean {
            val text = files[tmpPath] ?: return false
            files[targetPath] = text
            files.remove(tmpPath)
            return true
        }

        override fun deleteIfExists(path: String) {
            files.remove(path)
        }
    }
}
