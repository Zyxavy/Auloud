package app.auloud.player.tts

/**
 * ST2: one streamable sentence (role already resolved by the caller via
 * `isDialogueSpeaker`: `dialogue` speaker reads as [TtsRole.Dialogue],
 * everything else as [TtsRole.Narrator]).
 */
data class StreamSentence(
    val sid: Int,
    val text: String,
    val role: TtsRole,
)

/** One queued utterance: speech or silence. Ids are attempt-scoped. */
sealed interface StreamUtterance {
    val id: String
}

/** Speak [text] with [role] at [volume] (0..1, ST6 calibrates it). */
data class SpeakUtterance(
    override val id: String,
    val sid: Int,
    val text: String,
    val role: TtsRole,
    val volume: Float,
) : StreamUtterance

/** Silent pause (rendered-rule lengths, measured by LiveSil). */
data class PauseUtterance(
    override val id: String,
    val durationMs: Long,
) : StreamUtterance

/**
 * ST2: queue planner (one core use: next sentences with ids, voice role
 * and volume per sentence, silent pauses).
 *
 * Plans at most [maxSentences] sentences from [startIndex], each with
 * its configured pause (`pausesAfterSid`, absent or non-positive means
 * none). No pause trails the chapter's last sentence. Pure, no state.
 */
object StreamPlanner {

    /** Sentences planned per chunk (plan decision 2: queue 3-5 ahead). */
    const val QUEUE_AHEAD = 4

    fun speakId(attempt: Int, sid: Int): String = "s$attempt:$sid"

    fun pauseId(attempt: Int, sid: Int): String = "s$attempt:$sid:p"

    fun plan(
        sentences: List<StreamSentence>,
        startIndex: Int,
        maxSentences: Int = QUEUE_AHEAD,
        attempt: Int = 0,
        volumeFor: (TtsRole) -> Float = { 1.0f },
        pausesAfterSid: Map<Int, Long> = emptyMap(),
    ): List<StreamUtterance> {
        require(maxSentences > 0) { "maxSentences must be positive." }
        if (startIndex < 0 || startIndex >= sentences.size) return emptyList()
        val out = mutableListOf<StreamUtterance>()
        var index = startIndex
        var count = 0
        while (index < sentences.size && count < maxSentences) {
            val sentence = sentences[index]
            out += SpeakUtterance(
                id = speakId(attempt, sentence.sid),
                sid = sentence.sid,
                text = sentence.text,
                role = sentence.role,
                volume = volumeFor(sentence.role),
            )
            val pauseMs = pausesAfterSid[sentence.sid] ?: 0L
            if (pauseMs > 0 && index < sentences.size - 1) {
                out += PauseUtterance(pauseId(attempt, sentence.sid), pauseMs)
            }
            index++
            count++
        }
        return out
    }
}

/**
 * ST2: stream core (event reducer plus sid position tracking).
 *
 * One core per chapter; [start] once, then drive it with start/done/
 * error callbacks from the driver. Every method returns the utterances
 * the driver must newly speak (empty means nothing to do). Attempt ids
 * make staleness structural: pause, seek and requeue bump the attempt
 * and drop the old queue, so late callbacks for it are unknown ids and
 * ignored.
 *
 * Rules:
 * - highlight follows the voice: [currentSid] moves only on start;
 *   done never moves it.
 * - missing done tolerated: starting a new speak supersedes an older
 *   started-but-undone one (its late done is then ignored).
 * - duplicate done ignored (id already removed).
 * - error skips that utterance and continues; [MAX_CONSECUTIVE_ERRORS]
 *   in a row fails the stream; any done resets the count.
 * - pause stops at a sentence boundary: resume restarts the in-progress
 *   sentence, or the next unstarted one when between sentences.
 * - seek restarts audibly at the sid (unknown sids ignored); speed-only
 *   changes use [requeue] (voice/volume changes update [volumes] first,
 *   or rebuild the core, then requeue).
 *
 * Pure Kotlin, no Android: tested against a fake driver (see
 * `StreamCoreTest`). Speed lives in the driver, not here.
 */
class StreamCore(
    private val sentences: List<StreamSentence>,
    var volumes: Map<TtsRole, Float> = mapOf(
        TtsRole.Narrator to 1.0f,
        TtsRole.Dialogue to 1.0f,
    ),
    private val pausesAfterSid: Map<Int, Long> = emptyMap(),
) {

    enum class Status {
        Idle,
        Playing,
        Paused,
        Done,
        Failed,
    }

    companion object {
        /** Consecutive engine errors that fail the stream. */
        const val MAX_CONSECUTIVE_ERRORS = 3
    }

    var status: Status = Status.Idle
        private set

    /** Sid of the most recently started speak (null before the first). */
    var currentSid: Int? = null
        private set

    var consecutiveErrors: Int = 0
        private set

    private var attempt = 0

    /** Sentence index past the last planned one. */
    private var plannedUpTo = 0

    private var resumeSid: Int? = null
    private var inProgressSid: Int? = null
    private val active = LinkedHashMap<String, StreamUtterance>()
    private val started = mutableSetOf<String>()
    /** Sids started this attempt (cleared with it; drives pause resume). */
    private val heard = mutableSetOf<Int>()

    private fun volumeFor(role: TtsRole): Float = volumes[role] ?: 1.0f

    private fun indexOfSid(sid: Int): Int = sentences.indexOfFirst { it.sid == sid }

    private fun takePlan(index: Int, maxSentences: Int = StreamPlanner.QUEUE_AHEAD): List<StreamUtterance> {
        if (index < 0 || index >= sentences.size) return emptyList()
        val planned = StreamPlanner.plan(
            sentences, index, maxSentences, attempt, ::volumeFor, pausesAfterSid
        )
        planned.forEach { active[it.id] = it }
        plannedUpTo = index + planned.count { it is SpeakUtterance }
        return planned
    }

    /**
     * Refill after a completion: top the unstarted speaks back up to
     * [StreamPlanner.QUEUE_AHEAD] sentences ahead (steady shallow queue,
     * decision 2).
     */
    private fun topUp(): List<StreamUtterance> {
        val unstarted = active.values
            .filterIsInstance<SpeakUtterance>()
            .count { it.id !in started }
        val need = (StreamPlanner.QUEUE_AHEAD - unstarted).coerceAtLeast(0)
        if (need == 0) return emptyList()
        return takePlan(plannedUpTo, need)
    }

    private fun finishIfDrained(): Boolean {
        if (active.isEmpty() && plannedUpTo >= sentences.size && status == Status.Playing) {
            status = Status.Done
            return true
        }
        return false
    }

    /** Fresh start at [startSid]; unknown sids return empty, staying Idle. */
    fun start(startSid: Int): List<StreamUtterance> {
        val index = indexOfSid(startSid)
        if (index < 0) return emptyList()
        attempt = 0
        active.clear()
        started.clear()
        heard.clear()
        consecutiveErrors = 0
        currentSid = null
        inProgressSid = null
        resumeSid = startSid
        status = Status.Playing
        return takePlan(index)
    }

    fun onStart(id: String) {
        if (status != Status.Playing) return
        val utterance = active[id] ?: return
        started += id
        if (utterance is SpeakUtterance) {
            active.keys
                .filter { it != id && it in started && active[it] is SpeakUtterance }
                .forEach { active.remove(it); started.remove(it) }
            currentSid = utterance.sid
            inProgressSid = utterance.sid
            heard += utterance.sid
        }
    }

    /** Returns refill utterances (empty when nothing new to speak). */
    fun onDone(id: String): List<StreamUtterance> {
        if (status != Status.Playing) return emptyList()
        val utterance = active.remove(id) ?: return emptyList()
        started.remove(id)
        if (utterance is SpeakUtterance) {
            consecutiveErrors = 0
            if (inProgressSid == utterance.sid) inProgressSid = null
        }
        val refill = topUp()
        finishIfDrained()
        return refill
    }

    /** Returns refill utterances, or empty when failed/starved. */
    fun onError(id: String): List<StreamUtterance> {
        if (status != Status.Playing) return emptyList()
        val utterance = active.remove(id) ?: return emptyList()
        started.remove(id)
        consecutiveErrors++
        if (utterance is SpeakUtterance && inProgressSid == utterance.sid) {
            inProgressSid = null
        }
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            status = Status.Failed
            attempt++
            active.clear()
            started.clear()
            heard.clear()
            return emptyList()
        }
        val refill = if (active.isEmpty()) topUp() else emptyList()
        finishIfDrained()
        return refill
    }

    /**
     * Pause at a sentence boundary. Returns the sid [resume] restarts
     * from (null when nothing was ever planned). Not playing: paused
     * returns its resume sid, anything else returns [currentSid].
     */
    fun pause(): Int? {
        if (status == Status.Paused) return resumeSid
        if (status != Status.Playing) return currentSid
        status = Status.Paused
        attempt++
        active.clear()
        started.clear()
        resumeSid = inProgressSid
            ?: sentences.firstOrNull { it.sid !in heard }?.sid
            ?: currentSid
        heard.clear()
        inProgressSid = null
        return resumeSid
    }

    /** Resume after [pause]; empty unless paused with somewhere to go. */
    fun resume(): List<StreamUtterance> {
        if (status != Status.Paused) return emptyList()
        val sid = resumeSid ?: return emptyList()
        val index = indexOfSid(sid)
        if (index < 0) return emptyList()
        status = Status.Playing
        heard.clear()
        return takePlan(index)
    }

    /**
     * Tap-to-jump: restart audibly at [sid]. Unknown sids are ignored.
     * Playing or paused only; terminal states need a fresh [start].
     */
    fun seek(sid: Int): List<StreamUtterance> {
        if (status != Status.Playing && status != Status.Paused) return emptyList()
        val index = indexOfSid(sid)
        if (index < 0) return emptyList()
        attempt++
        active.clear()
        started.clear()
        heard.clear()
        consecutiveErrors = 0
        inProgressSid = null
        resumeSid = sid
        status = Status.Playing
        return takePlan(index)
    }

    /**
     * Re-plan from the current sentence (speed change; voice/volume
     * changes update [volumes] first). Playing only, else empty.
     */
    fun requeue(): List<StreamUtterance> {
        if (status != Status.Playing) return emptyList()
        val sid = inProgressSid ?: currentSid ?: return emptyList()
        val index = indexOfSid(sid)
        if (index < 0) return emptyList()
        attempt++
        active.clear()
        started.clear()
        heard.clear()
        resumeSid = sid
        return takePlan(index)
    }
}
