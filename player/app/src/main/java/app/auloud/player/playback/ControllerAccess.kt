package app.auloud.player.playback

/**
 * FU5: may this controller bind to the media session? Our own UI controllers
 * and system-trusted ones (Media3 [isTrusted]: notification, lock screen,
 * Bluetooth) pass; any other app is refused at connect time.
 */
fun allowController(controllerPackage: String, ownPackage: String, isTrusted: Boolean): Boolean =
    controllerPackage == ownPackage || isTrusted
