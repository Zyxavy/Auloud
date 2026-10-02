package app.auloud.player.reader

import app.auloud.player.bundle.Block

/**
 * RA2: time-to-sentence lookup for one loaded chapter.
 *
 * Built once per chapter load from its blocks: a sorted `(start_ms, sid)`
 * array for binary search plus a sid-to-location table. Pure Kotlin, no
 * Android dependencies, safe to call from any thread (immutable after
 * construction).
 *
 * Lookup rule (spec section 6: gaps allowed, ends exclusive): the current
 * sentence is the last one whose `start_ms <= pos`, so a position inside a
 * gap keeps the previous highlight. Before the first start the answer is
 * the first sentence; an empty chapter answers `null` everywhere.
 */
class SentenceIndex(blocks: List<Block>) {

    /** Where a sentence lives: block index + position inside that block. */
    data class SentenceLocation(val blockIndex: Int, val indexInBlock: Int)

    private val starts: LongArray
    private val sids: IntArray
    private val startsBySid: Map<Int, Long>
    private val locations: Map<Int, SentenceLocation>

    /** Sentences in time order (empty when the chapter has none). */
    val size: Int
        get() = sids.size

    init {
        val flat = blocks.flatMapIndexed { blockIndex, block ->
            block.sentences.mapIndexed { indexInBlock, sentence ->
                Triple(sentence.startMs, sentence.sid, SentenceLocation(blockIndex, indexInBlock))
            }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        starts = LongArray(flat.size) { flat[it].first }
        sids = IntArray(flat.size) { flat[it].second }
        startsBySid = flat.associate { it.second to it.first }
        locations = flat.associate { it.second to it.third }
    }

    /**
     * Sid visible at [positionMs], or `null` when the chapter has no
     * sentences. Never throws for negative or past-the-end positions.
     */
    fun currentSid(positionMs: Long): Int? {
        if (sids.isEmpty()) return null
        var low = 0
        var high = sids.size - 1
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= positionMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        // No start <= pos (before the first): highlight the first sentence.
        return if (answer < 0) sids[0] else sids[answer]
    }

    /** Media time a sentence starts at, or `null` for an unknown sid. */
    fun startMsOf(sid: Int): Long? = startsBySid[sid]

    /** Block location of a sentence, or `null` for an unknown sid. */
    fun locationOf(sid: Int): SentenceLocation? = locations[sid]
}
