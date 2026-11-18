package com.shibi.orbis.addons

import android.content.SharedPreferences
import androidx.core.content.edit

/** What [AddonManager] remembers between launches, per add-on id. */
interface AddonPrefs {
    /** When the add-on was last checked for an update (or installed), in epoch milliseconds; 0 when never. */
    fun lastUpdateCheckMillis(id: String): Long

    fun setLastUpdateCheckMillis(id: String, millis: Long)

    /** True when the engine refused the add-on's last update because it asks for more access. */
    fun updateBlocked(id: String): Boolean

    fun setUpdateBlocked(id: String, blocked: Boolean)

    /** Forgets everything stored for [id] (the add-on was removed). */
    fun clear(id: String)
}

class SharedPreferencesAddonPrefs(private val prefs: SharedPreferences) : AddonPrefs {
    override fun lastUpdateCheckMillis(id: String): Long = prefs.getLong(CHECK_PREFIX + id, 0L)

    override fun setLastUpdateCheckMillis(id: String, millis: Long) {
        prefs.edit { putLong(CHECK_PREFIX + id, millis) }
    }

    override fun updateBlocked(id: String): Boolean = prefs.getBoolean(BLOCKED_PREFIX + id, false)

    override fun setUpdateBlocked(id: String, blocked: Boolean) {
        prefs.edit { if (blocked) putBoolean(BLOCKED_PREFIX + id, true) else remove(BLOCKED_PREFIX + id) }
    }

    override fun clear(id: String) {
        prefs.edit {
            remove(CHECK_PREFIX + id)
            remove(BLOCKED_PREFIX + id)
        }
    }

    companion object {
        const val FILE_NAME = "addon_prefs"
        private const val CHECK_PREFIX = "last_update_check_millis:"
        private const val BLOCKED_PREFIX = "update_blocked:"
    }
}
