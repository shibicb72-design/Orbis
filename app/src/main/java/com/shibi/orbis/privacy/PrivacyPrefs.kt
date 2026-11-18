package com.shibi.orbis.privacy

import android.content.SharedPreferences
import androidx.core.content.edit

/** Settings > Lock private tabs. Its own file, so nothing else in Settings is touched by it. */
interface PrivacyPrefs {
    /** Hide private tabs whenever Orbis leaves the screen, until the user unlocks. Off by default. */
    var lockPrivateTabs: Boolean
}

class SharedPreferencesPrivacyPrefs(private val prefs: SharedPreferences) : PrivacyPrefs {
    override var lockPrivateTabs: Boolean
        get() = prefs.getBoolean(KEY_LOCK_PRIVATE_TABS, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_PRIVATE_TABS, value) }

    companion object {
        const val FILE_NAME = "privacy_prefs"
        private const val KEY_LOCK_PRIVATE_TABS = "lock_private_tabs"
    }
}
