package app.auloud.player.storage

import android.Manifest
import android.os.Build

/**
 * WP3/WP5 refinement, D-214: below API 30 the shared-internal `/Auloud`
 * needs `WRITE_EXTERNAL_STORAGE` alongside `READ_EXTERNAL_STORAGE`
 * (user-approved), requested together on Android 6+. From API 30 the default
 * lives in the app-specific dir and no storage permission is requested. API
 * 33+ adds POST_NOTIFICATIONS to the launch request; it never gates storage.
 * `allGranted` keeps the check in one pure place for plain-JVM tests
 * (callers pass `checkSelfPermission(...) == GRANTED`).
 *
 * DEVICE-TEST (user on the Tab E): accept and deny each permission and
 * confirm the library shows the no-permission state with a working retry.
 *
 * API 24 safe: legacy storage permissions only (no scoped-storage behavior).
 */
object StoragePermissions {

    /** Legacy storage permissions, below API 30 only. */
    fun required(sdkInt: Int): Array<String> =
        if (sdkInt < Build.VERSION_CODES.R) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            emptyArray<String>()
        }

    /** True when [isGranted] holds for every [required] permission (true from API 30). */
    fun allGranted(sdkInt: Int, isGranted: (permission: String) -> Boolean): Boolean =
        required(sdkInt).all { isGranted(it) }

    /** What to request at launch: [required] plus POST_NOTIFICATIONS on API 33+. */
    fun toRequest(sdkInt: Int): Array<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            required(sdkInt) + Manifest.permission.POST_NOTIFICATIONS
        } else {
            required(sdkInt)
        }
}
