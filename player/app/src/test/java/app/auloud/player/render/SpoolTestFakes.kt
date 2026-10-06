package app.auloud.player.render

import app.auloud.player.bundle.Block
import app.auloud.player.bundle.ChapterText
import app.auloud.player.bundle.Sentence
import app.auloud.player.tts.SynthesizedAudio
import app.auloud.player.tts.TtsCapabilities
import app.auloud.player.tts.TtsEngine
import app.auloud.player.tts.TtsRole
import app.auloud.player.tts.TtsVoice
import app.auloud.player.tts.TtsVoiceStore

/**
 * RN4 test fakes (JVM): scripted engine, in-memory spool IO, map voice
 * store, plus small chapter builders. Production code never touches these.
 */

/** Deterministic engine: PCM length from text, amplitude per voice, scripted failures. */
internal class ScriptedSpoolEngine(
    override val namespace: String,
    private val voiceIds: List<String>,
    val sampleRateHz: Int = 22050,
    var samplesPerChar: Int = 100,
    var amplitudeFor: (voiceId: String) -> Float = { 0.5f },
    val failOnceTexts: MutableSet<String> = mutableSetOf(),
    val failAlwaysTexts: MutableSet<String> = mutableSetOf()
) : TtsEngine {

    data class Call(val text: String, val voiceId: String, val speed: Float)

    val calls = ArrayList<Call>()

    override fun voices(): List<TtsVoice> =
        voiceIds.map { TtsVoice(id = it, engine = namespace) }

    override fun capabilities(): TtsCapabilities = TtsCapabilities(
        multiSpeaker = true,
        loadCostMb = 0,
        sampleRateHz = sampleRateHz
    )

    override suspend fun synthesize(text: String, voice: TtsVoice, speed: Float): SynthesizedAudio {
        calls.add(Call(text, voice.id, speed))
        if (text in failAlwaysTexts) throw IllegalStateException("scripted permanent failure")
        if (failOnceTexts.remove(text)) throw IllegalStateException("scripted transient failure")
        require(text.isNotBlank()) { "blank text" }
        require(voice.engine == namespace) { "voice ${voice.id} not owned by $namespace" }
        return SynthesizedAudio(
            sampleRateHz = sampleRateHz,
            samples = FloatArray(text.length * samplesPerChar) { amplitudeFor(voice.id) }
        )
    }
}

/** In-memory [SpoolIo] (no sleeps, no disk). */
internal class MemSpoolIo : SpoolIo {
    val bytes = LinkedHashMap<String, ByteArray>()
    val texts = LinkedHashMap<String, String>()

    override fun exists(path: String): Boolean =
        bytes.containsKey(path) || texts.containsKey(path)

    override fun readText(path: String): String =
        texts[path] ?: throw IllegalStateException("$path: no such spool text")

    override fun writeBytes(path: String, bytes: ByteArray) {
        this.bytes[path] = bytes.copyOf()
    }

    override fun writeText(path: String, text: String) {
        texts[path] = text
    }

    override fun deleteIfExists(path: String) {
        bytes.remove(path)
        texts.remove(path)
    }

    override fun listFiles(dir: String, prefix: String, suffix: String): List<String> {
        val root = dir.trimEnd('/') + '/'
        return (bytes.keys + texts.keys)
            .filter { it.startsWith(root + prefix) && it.endsWith(suffix) }
            .sorted()
    }

    fun pcmPaths(): List<String> = bytes.keys.sorted()
}

/** Map-backed [TtsVoiceStore] (speeds pass through raw; resolution clamps). */
internal class MemRenderVoiceStore(
    var narratorId: String = "",
    var dialogueId: String = "",
    var narratorSpeed: Float = 1.0f,
    var dialogueSpeed: Float = 1.0f
) : TtsVoiceStore {
    override fun voiceId(role: TtsRole): String =
        if (role == TtsRole.Narrator) narratorId else dialogueId

    override fun setVoiceId(role: TtsRole, voiceId: String) {
        if (role == TtsRole.Narrator) narratorId = voiceId else dialogueId = voiceId
    }

    override fun speed(role: TtsRole): Float =
        if (role == TtsRole.Narrator) narratorSpeed else dialogueSpeed

    override fun setSpeed(role: TtsRole, speed: Float) {
        if (role == TtsRole.Narrator) narratorSpeed = speed else dialogueSpeed = speed
    }
}

/** One para block per text: sid order follows the list (1-based across blocks). */
internal fun sentencesAsBlocks(texts: List<Pair<String, String>>, startBlock: Int = 1): List<Block> {
    var sid = 0
    var block = startBlock
    return texts.map { (speaker, text) ->
        sid++
        Block(id = block++, type = "para", sentences = listOf(Sentence(sid = sid, speaker = speaker, text = text)))
    }
}

/** Chapter of single-sentence para blocks (joins stay exact, no spacing traps). */
internal fun blockChapter(number: Int, texts: List<Pair<String, String>>): ChapterText =
    ChapterText(
        specVersion = "2.0",
        chapter = number,
        title = "Chapter $number",
        blocks = sentencesAsBlocks(texts)
    )
