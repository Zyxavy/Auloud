package app.auloud.player.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WP4: saved listening position, keyed by manifest `id`.
 *
 * Stores chapter index + position in ms (per the bundle rules). No foreign key
 * to `books`: book rows are never deleted (a vanished folder only marks the
 * book missing), so there is nothing to cascade.
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
    val updatedAt: Long
)
