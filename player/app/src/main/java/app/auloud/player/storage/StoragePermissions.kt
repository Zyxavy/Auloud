package app.auloud.player.storage

import android.Manifest

/**
 * WP3/WP5 refinement: shared-internal `/Auloud` lives on shared storage, so
 * Slice 1 now needs `WRITE_EXTERNAL_STORAGE` alongside the WP3
 * `READ_EXTERNAL_STORAGE` (user-approved). Both are requested together on
 * Android 6+; `allGranted` keeps the check in one pure place for plain-JVM
 * tests (callers pass `checkSelfPermission(...) == GRANTED`).
 *
 * DEVICE-TEST (user on the Tab E): accept and deny each permission and
 * confirm the library shows the no-permission state with a working retry.
 *
 * API 24 safe: legacy storage permissions only (no scoped-storage behavior).
 */
object StoragePermissions {

    /** Both permissions, requested together. */
    fun required(): Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    /** True when [isGranted] holds for every [required] permission. */
    fun allGranted(isGranted: (permission: String) -> Boolean): Boolean =
        required().all { isGranted(it) }
}
