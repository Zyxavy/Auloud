package app.auloud.player.data

/**
 * FU4: process-wide lazy holder (double-checked lock). [get] runs `create`
 * at most once; later and concurrent callers get the same instance.
 */
internal class Once<T : Any> {
    private val lock = Any()

    @Volatile
    private var value: T? = null

    fun get(create: () -> T): T =
        value ?: synchronized(lock) { value ?: create().also { value = it } }
}
