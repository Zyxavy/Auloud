package app.auloud.player.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * ST5: stable service-intent surface for [PlaybackService].
 *
 * The service class carries media3's `UnstableApi` marker (it hosts the
 * live-stream player), so UI code must not name the service class or its
 * companion constants: every such reference would need the marker too.
 * Everything here is stable: the extras the service honors plus builders
 * that address the service by package/class-name string instead of a
 * class literal. The string values stay exactly as before (a running
 * service reads the same extras).
 *
 * API 24 safe: intents and component names only.
 */
object PlaybackIntents {

    /** Manifest `id` of the book to load. */
    const val EXTRA_BOOK_ID = "app.auloud.player.extra.BOOK_ID"

    /** A [SleepOption] name (sleep timer command). */
    const val EXTRA_SLEEP_OPTION = "app.auloud.player.extra.SLEEP_OPTION"

    /** 0-based manifest chapter position (live chapter jump). */
    const val EXTRA_STREAM_CHAPTER = "app.auloud.player.extra.STREAM_CHAPTER"

    /** Explicit intent for the playback service (stable class address). */
    fun serviceIntent(appContext: Context): Intent = Intent()
        .setClassName(appContext.packageName, SERVICE_CLASS_NAME)

    /** Component for the media-session token (stable class address). */
    fun serviceComponent(appContext: Context): ComponentName =
        ComponentName(appContext.packageName, SERVICE_CLASS_NAME)

    private const val SERVICE_CLASS_NAME = "app.auloud.player.playback.PlaybackService"
}
