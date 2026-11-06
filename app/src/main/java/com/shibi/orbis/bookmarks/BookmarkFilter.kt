package com.shibi.orbis.bookmarks

import java.util.Locale

/** Bookmarks whose title or URL contains [query] (trimmed, case-insensitive). A blank query returns everything. */
fun filterBookmarks(list: List<Bookmark>, query: String): List<Bookmark> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return list
    return list.filter {
        it.title.lowercase(Locale.ROOT).contains(q) || it.url.lowercase(Locale.ROOT).contains(q)
    }
}
