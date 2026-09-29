package app.auloud.player.storage

import android.content.SharedPreferences

/**
 * WP3 test fake: in-memory `SharedPreferences` so [PrefsWatchFolderStore] is
 * exercised on plain JVM unit tests without Robolectric. Implements the
 * framework interface directly; no Android framework code ever runs.
 */
class FakeSharedPreferences : SharedPreferences {

    private val data = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun contains(key: String): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        data[key] as? Boolean ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        data[key] as? Float ?: defValue

    override fun getInt(key: String, defValue: Int): Int =
        data[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        data[key] as? Long ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getString(key: String, defValue: String?): String? =
        (data[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (data[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)
    }

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun apply() {
            commit()
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) {
                data.clear()
                clearAll = false
            }
            for (key in removals) data.remove(key)
            removals.clear()
            data.putAll(pending)
            pending.clear()
            return true
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            stage(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            stage(key, value)

        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            stage(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            stage(key, value)

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            stage(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = stage(key, values)

        override fun remove(key: String): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        private fun stage(key: String, value: Any?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
    }
}
