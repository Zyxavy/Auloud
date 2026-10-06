package app.auloud.player.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * WP2: bundle manifest models.
 *
 * Field names, required/optional status and chapter entry shape come from
 * `docs/03-BundleSpec.md` section 3. Unknown JSON keys are ignored at parse
 * time via `Json { ignoreUnknownKeys = true }` (see [BundleParser]); missing
 * optional fields fall back to the defaults below.
 *
 * IN1 (spec 2.0): `render_state` (`none`, `partial`, `complete`) is
 * required in 2.0 manifests and absent in 1.x; the manifest `audio`
 * object is absent for `none` books. Per-chapter `audio`/`durationMs`
 * are conditional the same way (absent for unrendered chapters, so
 * `durationMs` is nullable and a blank `audio` means unrendered).
 * Per-chapter state is inferred from `durationMs` presence.
 *
 * API 24 safe: pure Kotlin + kotlinx.serialization, no java.time, no
 * java.nio.file, no Android dependencies.
 */
@Serializable
data class Manifest(
    @SerialName("spec_version")
    val specVersion: String,
    val id: String,
    val title: String,
    val type: String,
    val chapters: List<ChapterInfo>,
    val audio: AudioInfo? = null,
    @SerialName("render_state")
    val renderState: String? = null,
    val author: String? = null,
    val language: String? = null,
    val source: SourceInfo? = null,
    val cover: String? = null,
    val voices: Map<String, JsonObject> = emptyMap(),
    @SerialName("created_at")
    val createdAt: String? = null,
    val generator: String? = null
)

@Serializable
data class ChapterInfo(
    val index: Int,
    val title: String,
    val text: String,
    val audio: String = "",
    @SerialName("duration_ms")
    val durationMs: Long? = null
)

@Serializable
data class AudioInfo(
    val format: String = "mp3",
    val channels: Int = 1,
    @SerialName("sample_rate")
    val sampleRate: Int = 24000,
    @SerialName("bitrate_kbps")
    val bitrateKbps: Int = 64,
    val cbr: Boolean = true
)

@Serializable
data class SourceInfo(
    val file: String? = null,
    val sha256: String? = null
)
