package app.aaps.core.interfaces.sharedPreferences

import androidx.annotation.StringRes

/**
 * Created by adrian on 2019-12-23.
 */

interface SP {

    // Using a helper Editor interface to distinguish its
    // methods from SP's. The latter always run apply().
    // The whole point of the scoped edit()/commit() functions is to
    // _avoid_ unnecessary apply() / commit() calls, so
    // we cannot use SP's put* methods in edit().
    interface Editor {

        fun clear()

        fun remove(@StringRes resourceID: Int)
        fun remove(key: String)

        fun putBoolean(key: String, value: Boolean)
        fun putBoolean(@StringRes resourceID: Int, value: Boolean)
        fun putDouble(key: String, value: Double)
        fun putDouble(@StringRes resourceID: Int, value: Double)
        fun putLong(key: String, value: Long)
        fun putLong(@StringRes resourceID: Int, value: Long)
        fun putInt(key: String, value: Int)
        fun putInt(@StringRes resourceID: Int, value: Int)
        fun putString(key: String, value: String)
        fun putString(@StringRes resourceID: Int, value: String)
    }

    /**
     * Allows for editing shared preferences in a scoped manner.
     *
     * Applies the changes asynchronously. Persistence failures are not reported;
     * use [commit] when the caller needs a persistence result.
     *
     * Example:
     *
     *     sp.edit {
     *         putString("my-key", "abc123")
     *     }
     */
    fun edit(block: Editor.() -> Unit)

    /**
     * Writes the changes synchronously and returns whether persistence succeeded.
     * A false result does not roll back in-memory values: callers must treat the
     * active preferences and their persisted state as potentially different.
     */
    fun commit(block: Editor.() -> Unit): Boolean

    fun getAll(): Map<String, *>
    fun clear()
    fun contains(key: String): Boolean
    fun contains(resourceId: Int): Boolean
    fun remove(@StringRes resourceID: Int)
    fun remove(key: String)
    fun getString(@StringRes resourceID: Int, defaultValue: String): String
    fun getStringOrNull(@StringRes resourceID: Int, defaultValue: String?): String?
    fun getStringOrNull(key: String, defaultValue: String?): String?
    fun getString(key: String, defaultValue: String): String
    fun getBoolean(@StringRes resourceID: Int, defaultValue: Boolean): Boolean
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getDouble(@StringRes resourceID: Int, defaultValue: Double): Double
    fun getDouble(key: String, defaultValue: Double): Double
    fun getInt(@StringRes resourceID: Int, defaultValue: Int): Int
    fun getInt(key: String, defaultValue: Int): Int
    fun getLong(@StringRes resourceID: Int, defaultValue: Long): Long
    fun getLong(key: String, defaultValue: Long): Long
    fun incLong(key: String)
    fun putBoolean(key: String, value: Boolean)
    fun putBoolean(@StringRes resourceID: Int, value: Boolean)
    fun putDouble(key: String, value: Double)
    fun putDouble(@StringRes resourceID: Int, value: Double)
    fun putLong(key: String, value: Long)
    fun putLong(@StringRes resourceID: Int, value: Long)
    fun putInt(key: String, value: Int)
    fun putInt(@StringRes resourceID: Int, value: Int)
    fun incInt(key: String)
    fun putString(@StringRes resourceID: Int, value: String)
    fun putString(key: String, value: String)
}
