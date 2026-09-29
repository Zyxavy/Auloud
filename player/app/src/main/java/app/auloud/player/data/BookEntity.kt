package app.auloud.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WP4: one imported book, keyed by manifest `id`.
 *
 * Field names follow Slice 1 WP4. Times are epoch millis (API 24 has no
 * `java.time`). `bundlePath`/`coverPath` are plain absolute-path strings
 * supplied via `BundleStorage`; `java.io.File` never leaves the storage layer.
 *
 * A bundle whose folder disappears is marked [isMissing] (never deleted), so
 * the library can show it as unavailable and clear the flag on re-import.
 */
@Entity(tableName = "books")
data class BookEntity(
    /** Manifest `id` (UUID, never changes for a given book). Progress keys by it. */
    @PrimaryKey
    val id: String,
    val title: String,
    /** Manifest `author`, null when the bundle omits it. */
    val author: String?,
    /** Absolute bundle dir path, as listed by `BundleStorage`. */
    val bundlePath: String,
    /** Absolute cover path, or null when the bundle has no (readable) cover. */
    val coverPath: String?,
    /** Sum of the manifest chapters' `duration_ms`. */
    val durationMs: Long,
    /** Epoch millis of first import; preserved when re-importing the same `id`. */
    val addedAt: Long,
    /** True when the bundle folder vanished from storage; the row is kept. */
    val isMissing: Boolean = false
)
