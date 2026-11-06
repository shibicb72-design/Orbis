package com.shibi.orbis.history

import com.shibi.orbis.engine.cleanStoredTitle
import com.shibi.orbis.engine.isStorableWebUrl
import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON codec for the history list (newest first). */
object HistoryCodec {
    private const val VERSION = 1

    fun encode(items: List<HistoryEntry>): String {
        val array = JSONArray()
        for (e in items) {
            array.put(JSONObject().put("url", e.url).put("title", e.title).put("visited", e.visitedAt))
        }
        return JSONObject().put("version", VERSION).put("history", array).toString()
    }

    /**
     * Throws if the document as a whole cannot be parsed (the caller then sets the file aside). A single bad entry is
     * skipped instead: malformed, not a storable URL, or a repeated URL (the first one, the newest, wins). Titles are
     * cleaned again and the list is cut to [MAX_HISTORY_ENTRIES].
     */
    fun decode(text: String): List<HistoryEntry> {
        val root = JSONObject(text)
        val array = root.getJSONArray("history")
        val out = ArrayList<HistoryEntry>()
        val urls = HashSet<String>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_HISTORY_ENTRIES) break
            val o = array.optJSONObject(i) ?: continue
            val url = string(o, "url")
            if (!isStorableWebUrl(url) || !urls.add(url)) continue
            val visited = if (o.isNull("visited")) 0L else o.optLong("visited", 0L)
            out += HistoryEntry(url, cleanStoredTitle(string(o, "title")), visited.coerceAtLeast(0L))
        }
        return out
    }

    /** org.json's optString turns JSON null into the text "null" on some implementations; avoid that. */
    private fun string(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")
}
