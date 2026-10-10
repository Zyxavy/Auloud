package app.auloud.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WP4: saved listening position, keyed by manifest `id`.
 *
 * Rendered books store chapter index + position in ms (per the bundle
 * rules). Unrendered 2.0 books have no milliseconds, so they store chapter
 * index + sentence sid instead ([sentenceSid]); when audio is rendered it
 * converts using the timings (Slice 10). No foreign key to `books`: book
 * rows are never deleted (a vanished folder only marks the book missing),
 * so there is nothing to cascade.
 *
 * IN1 (spec 2.0): [sentenceSid] is null for ms-based positions (all rows
 * written before v2, and every rendered-book save) and holds the 1-based
 * sid for unrendered-book positions (the sid reader lands in IN9).
 *
 * API 24 safe: epoch millis, no `java.time`.
 */
@Entity(tableName = "progress")
data class ProgressEntity(
    /** Manifest `id` of the book this position belongs to. */
    @PrimaryKey
    val bookId: String,
    val chapterIndex: Int,
    val positionMs: Long,
    val updatedAt: Long,
    /** 1-based sentence sid for unrendered books, null for ms positions. */
    val sentenceSid: Int? = null
)
