package com.shibi.orbis.engine

import android.content.SharedPreferences

class SharedPreferencesSearchEnginePrefs(private val prefs: SharedPreferences) : SearchEnginePrefs {
    override var engine: SearchEngine
        get() = SearchEngine.fromName(prefs.getString(KEY_ENGINE, null))
        set(value) {
            prefs.edit().putString(KEY_ENGINE, value.name).apply()
        }

    companion object {
        const val FILE_NAME = "search_prefs"
        private const val KEY_ENGINE = "engine"
    }
}
