package com.shibi.orbis.engine

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * File `search_prefs`. `engine`: the selected id (a [SearchEngine] name as before, or `custom:<n>`).
 * `custom_engines`: a JSON array of `{"id":"custom:<n>","name":"...","template":"https://...%s..."}` in the user's order.
 * `custom_next`: the next `<n>`.
 */
class SharedPreferencesSearchEnginePrefs(private val prefs: SharedPreferences) : SearchEnginePrefs {
    override var selectedId: String?
        get() = prefs.getString(KEY_ENGINE, null)
        set(value) {
            prefs.edit { putString(KEY_ENGINE, value) }
        }

    override var custom: List<SearchProvider.Custom>
        get() = decodeCustomEngines(prefs.getString(KEY_CUSTOM, null))
        set(value) {
            prefs.edit { putString(KEY_CUSTOM, encodeCustomEngines(value)) }
        }

    override var nextCustomNumber: Int
        get() = prefs.getInt(KEY_NEXT, 1)
        set(value) {
            prefs.edit { putInt(KEY_NEXT, value) }
        }

    companion object {
        const val FILE_NAME = "search_prefs"
        private const val KEY_ENGINE = "engine"
        private const val KEY_CUSTOM = "custom_engines"
        private const val KEY_NEXT = "custom_next"

        internal fun encodeCustomEngines(list: List<SearchProvider.Custom>): String =
            JSONArray().apply {
                list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("template", it.template)) }
            }.toString()

        /** Entries that no longer pass the rules (a damaged file) are left out; unreadable text gives an empty list. */
        internal fun decodeCustomEngines(text: String?): List<SearchProvider.Custom> {
            if (text.isNullOrEmpty()) return emptyList()
            val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
            val out = ArrayList<SearchProvider.Custom>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                val template = o.optString("template")
                if (!id.startsWith(CUSTOM_ID_PREFIX) || out.any { it.id == id }) continue
                if (validateCustomEngine(name, template, out) != null) continue
                out += SearchProvider.Custom(id, name, template)
            }
            return out
        }
    }
}
