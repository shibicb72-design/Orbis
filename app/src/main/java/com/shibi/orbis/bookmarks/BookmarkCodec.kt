package com.shibi.orbis.bookmarks

import com.shibi.orbis.engine.cleanStoredTitle
import com.shibi.orbis.engine.isStorableWebUrl
import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON codec for the bookmark list. */
object BookmarkCodec {
    private const val VERSION = 1

    fun encode(items: List<Bookmark>): String {
        val array = JSONArray()
        for (b in items) {
            array.put(
                JSONObject()
                    .put("id", b.id)
                    .put("url", b.url)
                    .put("title", b.title)
                    .put("created", b.createdAt),
            )
        }
        return JSONObject().put("version", VERSION).put("bookmarks", array).toString()
    }

    /**
     * Throws if the document as a whole cannot be parsed (the caller then sets the file aside). A single bad entry is
     * skipped instead: malformed, not a storable URL, or a duplicate id or URL (the first one wins). Titles are
     * cleaned again, since the file may have been edited by hand or by an older build.
     */
    fun decode(text: String): List<Bookmark> {
        val root = JSONObject(text)
        val array = root.getJSONArray("bookmarks")
        val out = ArrayList<Bookmark>()
        val ids = HashSet<Long>()
        val urls = HashSet<String>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_BOOKMARKS) break
            val o = array.optJSONObject(i) ?: continue
            val id = if (o.isNull("id")) -1L else o.optLong("id", -1L)
            if (id <= 0) continue
            val url = string(o, "url")
            if (!isStorableWebUrl(url)) continue
            if (id in ids || url in urls) continue
            ids += id
            urls += url
            val created = if (o.isNull("created")) 0L else o.optLong("created", 0L)
            out += Bookmark(
                id = id,
                url = url,
                title = cleanStoredTitle(string(o, "title")).ifEmpty { url },
                createdAt = created.coerceAtLeast(0L),
            )
        }
        return out
    }

    /** org.json's optString turns JSON null into the text "null" on some implementations; avoid that. */
    private fun string(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")
}
