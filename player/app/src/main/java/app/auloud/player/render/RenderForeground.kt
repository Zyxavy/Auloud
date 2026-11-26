package app.auloud.player.render

/**
 * WP2: foreground-entry guard for [RenderService].
 *
 * API 31+ refuses a background foreground start with
 * ForegroundServiceStartNotAllowedException, a subclass of
 * IllegalStateException; SecurityException covers a missing permission.
 * Both are reported through [tryStart]'s callback so the service stops
 * cleanly instead of crashing.
 */
object RenderForeground {
    /** Notification channel for the render service (created on API 26+). */
    const val CHANNEL_ID = "render"

    fun tryStart(start: () -> Unit, onRefused: (Exception) -> Unit): Boolean {
        return try {
            start()
            true
        } catch (e: IllegalStateException) {
            onRefused(e)
            false
        } catch (e: SecurityException) {
            onRefused(e)
            false
        }
    }
}
