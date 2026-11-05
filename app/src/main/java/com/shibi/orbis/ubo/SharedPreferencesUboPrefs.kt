package com.shibi.orbis.ubo

import android.content.SharedPreferences

class SharedPreferencesUboPrefs(private val prefs: SharedPreferences) : UboPrefs {
    override var decision: UboDecision
        get() =
            prefs.getString(KEY_DECISION, null)
                ?.let { name -> UboDecision.entries.firstOrNull { it.name == name } }
                ?: UboDecision.UNDECIDED
        set(value) {
            prefs.edit().putString(KEY_DECISION, value.name).apply()
        }

    override var lastUpdateCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
        }

    companion object {
        const val FILE_NAME = "ubo_prefs"
        private const val KEY_DECISION = "decision"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check_millis"
    }
}
