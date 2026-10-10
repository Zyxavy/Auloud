package app.auloud.player.tts

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * KT1: KittenTTS pack detection (pure Kotlin, no native imports).
 *
 * A Kitten pack is a folder with a model file (`model*.onnx`: the name
 * varies per variant, see `docs/kitten.md`), `voices.bin`, `tokens.txt`
 * and `espeak-ng-data/`. Voice ids are speaker sids (`kitten:<sid>`);
 * names and genders stay numeric until KT0 verifies the mapping, so the
 * adapter lists bare sids and KT5 maps them.
 *
 * The speaker count reads an optional `pack.json` (`{"speakers": N}`);
 * without it every legacy KittenTTS model to date is 8-speaker
 * ([LEGACY_SPEAKER_COUNT]). A wrong count only mislabels numeric rows
 * until the pack file arrives: visible, never silent corruption.
 *
 * API 24 safe: `java.io.File` only.
 */
const val KITTEN_NAMESPACE = "kitten"
const val KITTEN_VOICES_FILENAME = "voices.bin"
const val KITTEN_TOKENS_FILENAME = "tokens.txt"
const val KITTEN_ESPEAK_DIRNAME = "espeak-ng-data"
const val KITTEN_PACK_FILENAME = "pack.json"

/** Speakers in every legacy KittenTTS model (KT0 mapping evidence). */
const val LEGACY_SPEAKER_COUNT = 8

/** KittenTTS models render at 24 kHz (actual rate still rides per call). */
const val KITTEN_NATIVE_HZ = 24_000

/**
 * Static load cost until KT3 measures it (nano int8 is about 25 MB on
 * disk; RAM is larger once loaded).
 */
const val KITTEN_LOAD_MB = 40

/** One validated Kitten pack folder. */
data class KittenPack(
    val dir: File,
    val model: File,
    val speakerCount: Int
)

/**
 * Voice id for [sid] (`kitten:<sid>`).
 */
fun kittenVoiceId(sid: Int): String = "$KITTEN_NAMESPACE:$sid"

/**
 * Speaker sid for a Kitten voice id, or null when the id is not a
 * `kitten:<int>` id.
 */
fun parseKittenSid(voice: TtsVoice): Int? {
    if (voice.engine != KITTEN_NAMESPACE) return null
    return voice.id.substringAfter(':').toIntOrNull()?.takeIf { it >= 0 }
}

/**
 * Validate a Kitten pack folder (null when incomplete). Picks the first
 * `model*.onnx` in sorted order; variant tarballs ship exactly one.
 */
fun detectKittenPack(dir: File): KittenPack? {
    val model = try {
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("model") && it.extension == "onnx" }
            .orEmpty().sortedBy { it.name }.firstOrNull()
    } catch (_: Exception) {
        return null
    } ?: return null
    if (!File(dir, KITTEN_VOICES_FILENAME).isFile) return null
    if (!File(dir, KITTEN_TOKENS_FILENAME).isFile) return null
    if (!File(dir, KITTEN_ESPEAK_DIRNAME).isDirectory) return null
    return KittenPack(dir = dir, model = model, speakerCount = readPackSpeakers(dir))
}

private fun readPackSpeakers(dir: File): Int {
    return try {
        val root = Json.parseToJsonElement(File(dir, KITTEN_PACK_FILENAME).readText()).jsonObject
        root["speakers"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: LEGACY_SPEAKER_COUNT
    } catch (_: Exception) {
        LEGACY_SPEAKER_COUNT
    }
}
