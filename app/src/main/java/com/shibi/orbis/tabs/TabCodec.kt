package com.shibi.orbis.tabs

import com.shibi.orbis.engine.isWebUrl
import org.json.JSONArray
import org.json.JSONObject

/** One tab as written to disk. [savedState] is the engine's opaque blob; null means "reload [url] instead". */
data class PersistedTab(
    val id: Long,
    val url: String,
    val title: String,
    val desktop: Boolean,
    val savedState: String?,
)

data class TabsSnapshot(val tabs: List<PersistedTab>, val selectedId: Long)

/** Pure JSON codec for the tab list. Never throws: bad input decodes to null. */
object TabCodec {
    private const val VERSION = 1

    /** A saved state bigger than this is dropped; the tab then restores from its url alone. */
    const val MAX_SAVED_STATE_CHARS = 512 * 1024

    /** Across all tabs, so 50 big tabs cannot make a multi-megabyte file that is read on every launch. */
    const val MAX_TOTAL_SAVED_STATE_CHARS = 8 * 1024 * 1024

    private const val MAX_TITLE_CHARS = 300

    fun encode(snapshot: TabsSnapshot): String {
        // Selected tab first, so it is the last to lose its saved state when the total budget runs out.
        val order = snapshot.tabs.indices.sortedBy { if (snapshot.tabs[it].id == snapshot.selectedId) 0 else 1 }
        val keepState = HashSet<Int>()
        var budget = MAX_TOTAL_SAVED_STATE_CHARS
        for (i in order) {
            val size = snapshot.tabs[i].savedState?.length ?: continue
            if (size <= MAX_SAVED_STATE_CHARS && size <= budget) {
                keepState += i
                budget -= size
            }
        }
        val array = JSONArray()
        snapshot.tabs.forEachIndexed { i, tab ->
            val o = JSONObject()
            o.put("id", tab.id)
            o.put("url", tab.url)
            o.put("title", tab.title)
            o.put("desktop", tab.desktop)
            if (i in keepState) o.put("state", tab.savedState)
            array.put(o)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("selected", snapshot.selectedId)
            .put("tabs", array)
            .toString()
    }

    /** Null for missing, empty, corrupt or tab-less input. Salvages what it can from a partly bad file. */
    fun decode(text: String?): TabsSnapshot? {
        if (text.isNullOrBlank()) return null
        val root = try { JSONObject(text) } catch (e: Exception) { return null }
        val array = root.optJSONArray("tabs") ?: return null
        val tabs = ArrayList<PersistedTab>()
        val seen = HashSet<Long>()
        for (i in 0 until array.length()) {
            if (tabs.size >= MAX_TABS) break
            val o = array.optJSONObject(i) ?: continue
            val id = if (o.isNull("id")) -1L else o.optLong("id", -1L)
            if (id <= 0 || !seen.add(id)) continue
            // A restored url is later loaded as the fallback, so it must be a web url or nothing.
            val url = string(o, "url").takeIf { isWebUrl(it) } ?: ""
            val state = string(o, "state").takeIf { it.isNotEmpty() && it.length <= MAX_SAVED_STATE_CHARS }
            tabs += PersistedTab(
                id = id,
                url = url,
                title = string(o, "title").take(MAX_TITLE_CHARS),
                desktop = !o.isNull("desktop") && o.optBoolean("desktop", false),
                savedState = state,
            )
        }
        if (tabs.isEmpty()) return null
        val selected = if (root.isNull("selected")) -1L else root.optLong("selected", -1L)
        return TabsSnapshot(tabs, if (tabs.any { it.id == selected }) selected else tabs.first().id)
    }

    /** org.json's optString turns JSON null into the text "null" on some implementations; avoid that. */
    private fun string(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")
}
