package app.auloud.player.tts

import android.content.Context
import app.auloud.player.storage.BooksRootResolver
import java.io.File

/**
 * Sideloaded model packs for the TTS registry (files only).
 *
 * Shared by the settings voice lab, the book voice host and the panel
 * guard registry: internal plus removable roots, never throws (I/O
 * errors read as absent).
 *
 * API 24 safe: File plus Context only.
 */
fun scanAppModelPacks(appContext: Context): List<ModelPack> {
    return try {
        val internal = File(BooksRootResolver.defaultBooksRoot(appContext))
        val removable = try {
            BooksRootResolver.findRemovableRoot(appContext)
        } catch (_: Exception) {
            null
        }
        ModelPacks.scan(ModelPacks.roots(internal, removable))
    } catch (_: Exception) {
        emptyList()
    }
}
